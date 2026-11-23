package io.github.xdstorage.utils;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S-3: exclusive cross-process + cross-instance lock over a storage folder, held for the
 * storage's lifetime. Without it two OS processes (or two differently-named storages in one
 * JVM pointing at one folder) silently clobber each other file-by-file: last rename wins,
 * index/data pairs tear.
 *
 * <p>Two layers:</p>
 * <ul>
 *   <li>a static registry keyed by canonical folder path guards the same-JVM case
 *       (POSIX file locks would happily "succeed" twice inside one process);</li>
 *   <li>an OS {@link FileLock} on {@code <folder>/.xdstorage.lock} guards the cross-process
 *       case; the lock file carries the holder's pid + timestamp for diagnosis.</li>
 * </ul>
 */
public final class XdStorageFolderLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(XdStorageFolderLock.class);

    /** Name of the lock file placed inside the storage folder ({@code .xdstorage.lock}). */
    public static final String LOCK_FILE_NAME = ".xdstorage.lock";

    /** canonical folder path → held lock (same-JVM guard: POSIX locks do not fail twice in-process). */
    private static final Map<String, XdStorageFolderLock> HELD = new ConcurrentHashMap<>();

    private final Path lockFilePath;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private XdStorageFolderLock(final Path lockFilePath, final FileChannel channel, final FileLock lock) {
        this.lockFilePath = lockFilePath;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the exclusive lock on {@code folder}. Throws {@link XdStorageInUseException}
     * when the folder is already open — in this JVM (any storage instance) or in another
     * process (holder pid is reported when the lock file carries one).
     *
     * @param folder the storage folder to lock; created when absent
     * @return the held lock; keep it open for the storage's lifetime and {@link #close()} it on shutdown
     * @throws IOException when the folder cannot be created or the lock file cannot be opened/written
     * @throws XdStorageInUseException when the folder is already locked in this JVM or by another process
     */
    public static XdStorageFolderLock acquire(final File folder) throws IOException {
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("storage folder " + folder + " does not exist and cannot be created");
        }
        final String canonical = folder.getCanonicalPath();

        final XdStorageFolderLock placeholderHeld = HELD.putIfAbsent(canonical, DUMMY);
        if (placeholderHeld != null && placeholderHeld != DUMMY) {
            throw new XdStorageInUseException("storage folder " + canonical
                    + " is already opened in this JVM (another storage instance holds it)");
        }

        final Path lockFilePath = new File(canonical, LOCK_FILE_NAME).toPath();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (final OverlappingFileLockException e) {
                throw new XdStorageInUseException("storage folder " + canonical
                        + " is already locked by this JVM (lock file " + lockFilePath + ")");
            }
            if (lock == null) {
                throw new XdStorageInUseException("storage folder " + canonical
                        + " is locked by another process" + holderDescription(lockFilePath)
                        + " — opening it concurrently corrupts the database; close the other storage first");
            }
            writeHolderInfo(channel);

            final XdStorageFolderLock result = new XdStorageFolderLock(lockFilePath, channel, lock);
            // replace the reservation placeholder with the real lock; if another thread snuck in
            // between (only possible on the placeholder==DUMMY window), bail out cleanly
            if (!HELD.replace(canonical, DUMMY, result)) {
                result.closeInternal();
                throw new XdStorageInUseException("storage folder " + canonical
                        + " is already opened in this JVM (another storage instance holds it)");
            }
            return result;
        } catch (final IOException | RuntimeException e) {
            if (channel != null)
                channel.close();
            HELD.remove(canonical, DUMMY);
            throw e;
        }
    }

    private static void writeHolderInfo(final FileChannel channel) throws IOException {
        final String info = "pid=" + currentPid() + " opened=" + new java.util.Date()
                + " thread=" + Thread.currentThread().getName() + System.lineSeparator();
        channel.truncate(0);
        channel.write(java.nio.ByteBuffer.wrap(info.getBytes(StandardCharsets.UTF_8)));
        channel.force(false);
    }

    /** Java-8-safe pid (library bytecode stays 1.8-compatible; ProcessHandle is 9+). */
    private static String currentPid() {
        try {
            final String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"
            final int at = name.indexOf('@');
            return at > 0 ? name.substring(0, at) : name;
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String holderDescription(final Path lockFilePath) {
        try {
            final String content = new String(Files.readAllBytes(lockFilePath), StandardCharsets.UTF_8).trim();
            if (!content.isEmpty())
                return " (lock file says: " + content + ")";
        } catch (final IOException ignored) {
            // unreadable lock file — report without holder details
        }
        return "";
    }

    /** Releases the OS lock and the same-JVM registry entry; idempotent, safe to call from any thread. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeInternal();
            // remove only OUR entry: by shutdown time HELD still maps this canonical path to this instance
            for (final Map.Entry<String, XdStorageFolderLock> entry : HELD.entrySet()) {
                if (entry.getValue() == this) {
                    HELD.remove(entry.getKey(), this);
                }
            }
        }
    }

    private void closeInternal() {
        try {
            lock.release();
        } catch (final IOException e) {
            log.warn("failed to release folder lock {}: {}", lockFilePath, e.getMessage());
        }
        try {
            channel.close();
        } catch (final IOException e) {
            log.warn("failed to close folder lock channel {}: {}", lockFilePath, e.getMessage());
        }
    }

    /** Thrown (unchecked) when the storage folder is already locked — the provider API cannot add checked exceptions
     * without breaking compat. */
    public static final class XdStorageInUseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the in-use error with a diagnostic message.
         *
         * @param message the human-readable description of who holds the folder and why the open failed
         */
        public XdStorageInUseException(final String message) {
            super(message);
        }
    }

    /** reservation placeholder so a half-acquired lock still blocks concurrent acquirers */
    private static final XdStorageFolderLock DUMMY = new XdStorageFolderLock(null, null, null);
}
