package io.github.xdstorage;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.utils.XdStorageAtomicFile;
import io.github.xdstorage.utils.XdStorageFolderLock;
import io.github.xdstorage.utils.XdStorageFormatMeta;
import io.github.xdstorage.utils.XdStorageVersion;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The factory and JVM-wide registry for storage instances — the entry point of the
 * library. {@link #newOrGetFileStorage} opens (or returns the already-open) storage,
 * {@link #verify} runs the read-only integrity walk over a folder.
 *
 * <p>Static state is per-JVM, not per-storage: the name&rarr;instance registry below,
 * the codegen caches and the global MVCC commit counter (see {@code docs/EMBEDDING.md}
 * &sect; JVM-wide static state). Instances are closed with {@link IXdStorage#shutdown()},
 * which releases the folder lock and unregisters the name.
 */
public class XdStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(XdStorageProvider.class);

    /** Static factory/registry only — not instantiated (the implicit constructor stays public for source
     * compatibility). */
    public XdStorageProvider() {
    }

    /**
     * Default fragment size (objects per class file, {@code StoreAsClassObjects} policy).
     * 128 — the measured optimum of the configuration sweep (docs/PERFORMANCE-RECOMMENDATIONS.md
     * &sect;5): lowest commit latency, best insert throughput, flat scans; roughly tens-to-hundreds
     * of KB per file for typical small entities. Fragment files above ~1 MB are a red flag (the
     * whole file is rewritten on every commit), below ~10 KB they pay per-file overhead (cold
     * scans, open file descriptors). The 128&ndash;512 range leaves headroom for entities larger
     * than the reference ~430 bytes.
     */
    public static final int DEFAULT_FRAGMENT_SIZE = 128;

    /**
     * A2: the library version (semver, e.g. "2.0.0") — the same value a storage instance
     * returns from {@code getVersion()}. Read from the Maven-filtered
     * {@code /xdstorage.properties} resource; "unknown" without Maven filtering.
     *
     * @return the library version string (e.g. "2.1.0"), never {@code null}
     */
    public static String getVersion() {
        return XdStorageVersion.getVersion();
    }

    private static Map<String, XdStorage> storages = new ConcurrentHashMap<>();

    private static Lock lock = new ReentrantLock();

    /**
     * VERIFY (docs/tasks/2.1.0/26): a read-only integrity walk over a storage folder —
     * the mechanized after-a-power-loss recipe (EMBEDDING.md § After a power loss).
     *
     * <p>Per engine-owned file: the S-1 EOF-sentinel state (truncated base vs intact),
     * journal presence/state/record-count (torn tails named), and structural entry counts;
     * per class: data objects vs id-index entries (enforced on clean folders — with
     * journals present the counts reconcile at replay); folder-level: the format marker,
     * orphaned journals, stale {@code .tmp} litter, and a live folder lock (reported —
     * "results unreliable" — never refused).
     *
     * <p>Boundaries: never repairs, never opens a storage, never takes the folder lock —
     * a stale/traveled {@code .lock} does not block a verify (the task-19 lock semantics).
     * The B+tree key-set reconciliation stays FP-5's scope (2.3.0); this walk checks the
     * tree files' sentinels and reports their entry counts.
     *
     * @param folder the storage folder (open or closed; open is reported as "in use")
     * @return the report; {@link XdStorageVerifyReport#isOk()} answers "nothing to act on"
     */
    public static XdStorageVerifyReport verify(final String folder) {
        return io.github.xdstorage.utils.XdStorageFolderVerifier.verify(new File(folder));
    }

    /**
     * {@link File} form of {@link #verify(String)}.
     *
     * @param folder the storage folder (open or closed)
     * @return the report; {@link XdStorageVerifyReport#isOk()} answers "nothing to act on"
     */
    public static XdStorageVerifyReport verify(final File folder) {
        return io.github.xdstorage.utils.XdStorageFolderVerifier.verify(folder);
    }

    /**
     * {@link java.nio.file.Path} form of {@link #verify(String)}.
     *
     * @param folder the storage folder (open or closed)
     * @return the report; {@link XdStorageVerifyReport#isOk()} answers "nothing to act on"
     */
    public static XdStorageVerifyReport verify(final java.nio.file.Path folder) {
        return io.github.xdstorage.utils.XdStorageFolderVerifier.verify(folder.toFile());
    }


    /**
     * Opens a storage, or returns the instance already open under this name.
     *
     * <p><b>The registry is keyed by name, per JVM.</b> A repeat call with the same
     * {@code name} returns the <em>existing</em> instance and IGNORES {@code folder} (and
     * every other argument) — a WARN is logged. Call {@code shutdown()} on the instance to
     * unregister the name before reopening under changed parameters.
     *
     * <p>The folder is locked exclusively for the storage's lifetime (OS file lock +
     * the same-JVM registry): a second opener — another process or another instance in
     * this JVM — fails with {@code XdStorageFolderLock.XdStorageInUseException}
     * (unchecked; the API predates the lock and cannot declare a checked exception).
     * At open the format marker is checked: a legacy 1.x folder opens with an INFO
     * (YAML content needs {@code acceptMissingEofSentinel(true)} until the first commit),
     * a folder written by a NEWER library is rejected loudly.
     *
     * <p>The storage holds a pool of non-daemon worker threads
     * ({@code XdStorageConfig.builder().workerPoolSize(...)}) — a forgotten
     * {@code shutdown()} blocks JVM exit.
     *
     * @param name the storage name (the registry key; unique per JVM while open)
     * @param folder the storage folder (created when missing; must be lockable exclusively)
     * @return the open storage under this name (never {@code null})
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the folder
     *         cannot be locked for exclusive use, or the on-disk format is not
     *         supported by this library version
     */
    public static IXdFileStorage newOrGetFileStorage(final String name, final String folder) {
        return newOrGetFileStorage(name, folder, DEFAULT_FRAGMENT_SIZE, FilesFormat.YAML, XdStorageConfig.defaults());
    }

    /**
     * Opens (or returns the already-open) storage with an explicit configuration, YAML
     * format and the recommended fragment size — see {@link #newOrGetFileStorage(String, String)}.
     *
     * @param name the storage name (the registry key)
     * @param folder the storage folder
     * @param config the open-time configuration; {@code null} means {@link XdStorageConfig#defaults()}
     * @return the open storage under this name
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the folder
     *         cannot be locked, the format is unsupported, or the config is illegal for
     *         the format (e.g. {@code journalWrites(true)} with a non-YAML format)
     */
    public static IXdFileStorage newOrGetFileStorage(final String name, final String folder, final XdStorageConfig
            config) {
        return newOrGetFileStorage(name, folder, DEFAULT_FRAGMENT_SIZE, FilesFormat.YAML, config);
    }

    /**
     * Opens (or returns the already-open) storage with an explicit fragment size, YAML
     * format and default configuration — see {@link #newOrGetFileStorage(String, String)}.
     *
     * @param name the storage name (the registry key)
     * @param folder the storage folder
     * @param fragmentSize objects per class file ({@code StoreAsClassObjects}); see
     *                     {@link #DEFAULT_FRAGMENT_SIZE}
     * @return the open storage under this name
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the folder
     *         cannot be locked or the format is unsupported
     */
    public static IXdFileStorage newOrGetFileStorage(final String name, final String folder, final int fragmentSize) {
        return newOrGetFileStorage(name, folder, fragmentSize, FilesFormat.YAML, XdStorageConfig.defaults());
    }

    /**
     * Opens (or returns the already-open) storage with an explicit on-disk format — see
     * {@link #newOrGetFileStorage(String, String)}. Journaling ({@code journalWrites}) is
     * supported for the {@link FilesFormat#YAML} format only; a non-YAML format with
     * journaling enabled is rejected at open.
     *
     * @param name the storage name (the registry key)
     * @param folder the storage folder
     * @param fragmentSize objects per class file; see {@link #DEFAULT_FRAGMENT_SIZE}
     * @param format the on-disk serialization format
     * @return the open storage under this name
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the folder
     *         cannot be locked, the format is unsupported, or journaling was requested
     *         for a non-YAML format
     */
    public static IXdFileStorage newOrGetFileStorage(final String name, final String folder, final int fragmentSize,
            FilesFormat format) {
        return newOrGetFileStorage(name, folder, fragmentSize, format, XdStorageConfig.defaults());
    }

    /**
     * Opens (or returns the already-open) storage: YAML format (the engine default) with
     * an explicit fragment size and configuration — see
     * {@link #newOrGetFileStorage(String, String)}.
     *
     * @param name the storage name (the registry key)
     * @param folder the storage folder
     * @param fragmentSize objects per class file; see {@link #DEFAULT_FRAGMENT_SIZE}
     * @param config the open-time configuration; {@code null} means defaults
     * @return the open storage under this name
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the folder
     *         cannot be locked or the format/config combination is illegal
     */
    public static IXdFileStorage newOrGetFileStorage(final String name, final String folder, final int fragmentSize,
            final XdStorageConfig config) {
        return newOrGetFileStorage(name, folder, fragmentSize, FilesFormat.YAML, config);
    }

    /**
     * Opens (or returns the already-open) storage with every parameter explicit — the
     * full form; the other overloads delegate here. See
     * {@link #newOrGetFileStorage(String, String)} for the registry, folder-lock and
     * format-marker contract.
     *
     * @param name the storage name (the registry key)
     * @param folder the storage folder
     * @param fragmentSize objects per class file; see {@link #DEFAULT_FRAGMENT_SIZE}
     * @param format the on-disk serialization format
     * @param config the open-time configuration; {@code null} means
     *               {@link XdStorageConfig#defaults()}
     * @return the open storage under this name
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the folder
     *         cannot be locked, the on-disk format is newer than this library, or the
     *         format/config combination is illegal
     */
    public static IXdFileStorage newOrGetFileStorage(final String name, final String folder, final int fragmentSize,
                                                     final FilesFormat format, final XdStorageConfig config) {
        final XdStorageConfig cfg = config == null ? XdStorageConfig.defaults() : config;
        XdStorage storage = storages.get(name);
        if (storage == null) {
            lock.lock();
            try {
                storage = storages.get(name);
                if (storage == null) {
                    // S-3: эксклюзивная блокировка каталога (между процессами и инстансами JVM).
                    // Checked-исключение в сигнатуру добавить нельзя (ломает API): занятый каталог
                    // = unchecked XdStorageFolderLock.XdStorageInUseException, IOError = unchecked обертка
                    final XdStorageFolderLock folderLock;
                    try {
                        folderLock = XdStorageFolderLock.acquire(new File(folder));
                    } catch (final java.io.IOException e) {
                        throw new io.github.xdstorage.exceptions.XdStorageRuntimeException(
                                "storage folder " + folder + " cannot be locked for exclusive use", e);
                    }
                    boolean created = false;
                    try {
                        // A2: format marker under the exclusive folder lock, before any engine state
                        // is built — announces "opened storage X, format vN", initializes the marker
                        // on a fresh folder, flags a legacy 1.x folder, rejects a newer-format folder.
                        XdStorageFormatMeta.onOpen(new File(folder), name, format.name());
                        storage = new XdStorage(name, folder, fragmentSize, format, cfg);
                        storage.attachFolderLock(folderLock);
                        created = true;
                    } finally {
                        if (!created)
                            folderLock.close();
                    }
                    storages.put(name, storage);

                    // S-5: зачистка .tmp-мусора прошлого сбоя + сводка открытия (объектные
                    // счетчики неизвестны до ленивой prepare — считаем файлы)
                    final File root = new File(folder);
                    final int swept = XdStorageAtomicFile.sweepStaleTempFiles(root);
                    log.info("storage '{}' ready: {} structure file(s), {} data file(s), {} stale .tmp swept; config " +
                            "{}",
                            name, countFiles(new File(root, "structure"), false),
                            countFiles(new File(root, "data"), true), swept, cfg);
                    return storage;
                }
            } finally {
                lock.unlock();
            }
        }
        // GOTCHA для тестов и встраивания: реестр держит инстанс по ИМЕНИ до shutdown() —
        // повторный вызов с тем же именем вернет СТАРЫЙ инстанс (с его кэшами и файлами),
        // даже если каталог удалили и пересоздали. Без этого WARN такие сюрпризы не видны.
        log.warn("storage '{}' requested again — returning the EXISTING instance (registry holds it until shutdown())",
                name);
        return storage;
    }

    private static int countFiles(final File dir, final boolean recursive) {
        if (dir == null || !dir.isDirectory())
            return 0;
        final File[] entries = dir.listFiles();
        if (entries == null)
            return 0;
        int count = 0;
        for (final File entry : entries) {
            if (entry.isDirectory()) {
                if (recursive)
                    count += countFiles(entry, true);
            } else if (!entry.getName().endsWith(".tmp")) {
                count++;
            }
        }
        return count;
    }

    static void unregisterStorage(final IXdStorage storage) {
        final String name = storage.getName();
        if (storages.containsKey(name)) {
            log.info("storage '{}' shut down and unregistered", name);
            storages.remove(name);
        }
    }


}
