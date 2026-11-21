package io.github.xdstorage.utils;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Crash-safe write-out for resource files: content is written to a temporary
 * sibling file which then atomically replaces the target, so a crash can never
 * truncate an existing resource file halfway (the old delete-then-recreate
 * scheme lost the resource if the process died between the two steps).
 *
 * <p>S-2 (durability): by default the guarantee stops at the process boundary —
 * {@code BufferedWriter.close()} only flushes to the OS page cache, so a power
 * loss right after a commit can still lose the newest file version. With
 * {@code durable = true} the temp file is {@code fsync}ed before the rename and
 * the parent directory is {@code fsync}ed after it, making the commit
 * power-loss-safe at the cost of one sync per written file. Java-8 compatible:
 * directory sync via a read-only channel {@code force}, best-effort on
 * filesystems/OSes that do not support it.</p>
 */
public final class XdStorageAtomicFile {

    private static final Logger log = LoggerFactory.getLogger(XdStorageAtomicFile.class);

    private XdStorageAtomicFile() {
    }

    /**
     * Non-durable convenience overload: writes the new content over the target through a
     * temporary sibling file and an atomic rename, without the fsync round of
     * {@link #writeReplacing(String, boolean, ContentWriter)}.
     *
     * @param fileName the target resource file path, replaced in whole on success
     * @param content the callback that streams the new content into the writer
     * @throws IOException when the write or the replace fails; the target keeps its previous
     *         content and the temporary file is removed
     */
    public static void writeReplacing(final String fileName, final ContentWriter content) throws IOException {
        writeReplacing(fileName, false, content);
    }

    /**
     * Crash-safe full replacement of one resource file: the content is streamed into a
     * temporary sibling ({@code fileName + ".tmp"}) which is then atomically renamed over the
     * target, so no reader or crash can ever observe a half-written resource. A stale temp
     * file from an earlier mid-write crash is removed first, and any failure deletes the temp
     * file and leaves the target with its previous content. With {@code durable} the temp file
     * is {@code fsync}ed before the rename and the parent directory after it, extending the
     * guarantee to power loss (directory sync is best-effort — see the class doc).
     *
     * @param fileName the target resource file path
     * @param durable {@code true} to sync the file to disk before the rename and the directory
     *                after it; {@code false} stops the guarantee at the process boundary
     * @param content the callback producing the new content
     * @throws IOException when the write or the replace fails, wrapping any non-IOException
     *         thrown by the callback
     */
    public static void writeReplacing(final String fileName, final boolean durable, final ContentWriter content) throws IOException {
        final File target = new File(fileName);
        final File tmp = new File(fileName + ".tmp");
        if (tmp.exists()) {
            // незакрытый .tmp от предыдущего сбоя: безопасно удаляем (он не читается никем)
            log.warn("stale temp file {} found (previous crash mid-write) — removing", tmp);
            tmp.delete();
        }
        try {
            if (tmp.exists())
                tmp.delete();
            final File parentFile = tmp.getParentFile();
            if (parentFile != null && !parentFile.exists())
                parentFile.mkdirs();
            tmp.createNewFile();

            Writer writer = null;
            FileOutputStream fos = null;
            try {
                // Phase D: буферизация обязательна — сырой FileWriter делает syscall на каждый
                // append сериализации; на full-file write-out это доминирующая стоимость коммита
                fos = new FileOutputStream(tmp);
                writer = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8), 1 << 16); // task 51: pinned cross-OS
                content.write(writer);
                writer.flush();
                if (durable) {
                    // S-2: данные файла должны дойти до диска ДО переименования
                    fos.getFD().sync();
                }
            } finally {
                if (writer != null)
                    writer.close();
                if (fos != null)
                    fos.close();
            }

            moveReplacing(tmp, target);
            if (durable) {
                // S-2: сам rename должен пережить отключение питания — синхронизируем каталог
                syncDirectory(target.getParentFile());
            }
        } catch (final Exception e) {
            tmp.delete();
            if (e instanceof IOException)
                throw (IOException) e;
            throw new IOException("error by writing content of resource " + fileName, e);
        }
    }

    /** best-effort: not every filesystem allows opening/syncing a directory — degrade to the non-durable rename */
    private static void syncDirectory(final File dir) {
        if (dir == null)
            return;
        FileChannel channel = null;
        try {
            channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ);
            channel.force(true);
        } catch (final Exception e) {
            log.warn("directory fsync unsupported for {} — rename durability is best-effort here ({})",
                    dir, e.getMessage());
        } finally {
            if (channel != null)
                try {
                    channel.close();
                } catch (final IOException ignored) {
                }
        }
    }

    private static void moveReplacing(final File source, final File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            log.warn("atomic rename unsupported for {} — falling back to a non-atomic move "
                    + "(a crash during the swap may lose the file; consider a filesystem with rename support)", target);
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * S-5: removes stale {@code *.tmp} litter left by a previous crash, before the first
     * write of each resource would lazily clean its own. Returns the number removed.
     *
     * @param folder the storage folder to sweep, recursively into subfolders
     * @return the number of stale temp files actually removed
     */
    public static int sweepStaleTempFiles(final File folder) {
        int removed = 0;
        final File[] entries = folder.listFiles();
        if (entries == null)
            return 0;
        for (final File entry : entries) {
            if (entry.isDirectory()) {
                removed += sweepStaleTempFiles(entry);
            } else if (entry.getName().endsWith(".tmp")) {
                if (entry.delete()) {
                    log.warn("stale temp file {} removed at storage open (previous crash mid-write)", entry);
                    removed++;
                } else {
                    log.warn("stale temp file {} could not be removed at storage open", entry);
                }
            }
        }
        return removed;
    }

    /** Streaming callback producing the new content of one resource file (engine-internal). */
    public interface ContentWriter {
        /**
         * Streams the full new content of the resource; the engine opens and closes the writer
         * and discards the temporary file when this throws.
         *
         * @param writer the buffered writer over the temporary sibling file
         * @throws Exception any failure aborts the whole replacement; the target file stays untouched
         */
        void write(Writer writer) throws Exception;
    }
}
