package io.github.xdstorage.utils;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

/**
 * A2: the on-disk format version marker. Every storage folder carries {@code .xdstorage.meta}
 * — a tiny properties file recording which generation of the on-disk format wrote it:
 * <ul>
 *   <li>{@code formatVersion=1} — the 1.x era: no YAML EOF sentinel, no commit journal;</li>
 *   <li>{@code formatVersion=2} — the 2.0 era: YAML EOF sentinel (S-1) and the optional
 *       per-transaction commit journal (W-1, journalWrites=ON default since W-3).</li>
 *   <li>{@code formatVersion=3} — the 2.1 era: the {@code indexes/} layout generation
 *       (task 51 LAYOUTMIG). The CONTENT format is unchanged (sentinel + journal); the
 *       generation stamps the on-disk ARRANGEMENT: the id-index/references/search bases
 *       and the B+Tree infra live under {@code indexes/}. A v2 folder opened by this
 *       library runs the one-time legacy-layout migration and the marker is rewritten to
 *       3 on success; a v3 folder opened by a 2.0 library refuses loudly (the pre-existing
 *       newer-version gate) — the documented downgrade refusal is enforced by this stamp.</li>
 * </ul>
 * The marker is what makes the compatibility statement in docs/EMBEDDING.md checkable instead
 * of folklore: at open time the provider announces "opened storage X, format vN", detects
 * legacy 1.x folders (files but no marker), and — critically for the future — refuses folders
 * written by a NEWER library loudly, before any engine state is built. Reading a v1 folder
 * still honors {@code XdStorageConfig.acceptMissingEofSentinel} for the YAML format.
 */
public final class XdStorageFormatMeta {

    private static final Logger log = LoggerFactory.getLogger(XdStorageFormatMeta.class);

    /** Name of the marker file inside the storage folder ({@code .xdstorage.meta}). */
    public static final String META_FILE_NAME = ".xdstorage.meta";

    /** The current generation of the on-disk format. Bump ONLY with a documented migration path in EMBEDDING.md. */
    public static final int FORMAT_VERSION = 3;

    private static final String KEY_FORMAT_VERSION = "formatVersion";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_STORAGE = "storage";
    private static final String KEY_LIBRARY_VERSION = "libraryVersion";
    private static final String KEY_CREATED_AT = "createdAt";

    private XdStorageFormatMeta() {
    }

    /**
     * Open-time flow, called by the provider under the exclusive folder lock, before any engine
     * state is built: initialize the marker on a fresh folder, announce the format version on
     * a known folder, flag a legacy 1.x folder, reject a folder from a newer library.
     *
     * @param folder the storage folder whose marker is checked or initialized
     * @param storageName the open-time storage name (used in the messages)
     * @param format the storage format name announced at open
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the marker is corrupt
     *         or was written by a newer library generation
     */
    public static void onOpen(final File folder, final String storageName, final String format) {
        final Properties meta = read(folder);
        if (meta == null) {
            if (hasStorageFiles(folder)) {
                log.info("storage '{}' at {}: legacy folder without a format marker — assuming on-disk format v1; "
                                + "see EMBEDDING.md 'Compatibility & support statement' (YAML readers require "
                                + "acceptMissingEofSentinel=true for 1.x files)",
                        storageName, folder);
                return;
            }
            write(folder, storageName, format, FORMAT_VERSION);
            log.info("created storage '{}' at {} (format {}, on-disk format v{}, library {})",
                    storageName, folder, format, FORMAT_VERSION, XdStorageVersion.getVersion());
            return;
        }

        final int version = parseFormatVersion(meta, storageName, folder);
        if (version > FORMAT_VERSION) {
            throw new XdStorageRuntimeException("storage '" + storageName + "' at " + folder
                    + " uses on-disk format v" + version + ", but this library supports up to v" + FORMAT_VERSION
                    + " — upgrade xdstorage (folder metadata: " + META_FILE_NAME + ")");
        }
        log.info("opened storage '{}' at {} (format {}, on-disk format v{}, library {})",
                storageName, folder, meta.getProperty(KEY_FORMAT, format), version, XdStorageVersion.getVersion());
    }

    /** Reads the marker; null when absent (fresh or legacy folder).
     *
     * @param folder the storage folder to read the marker from
     * @return the marker properties, or {@code null} when the folder has no marker file
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the marker exists but cannot be read
     */
    public static Properties read(final File folder) {
        final File metaFile = new File(folder, META_FILE_NAME);
        if (!metaFile.isFile()) {
            return null;
        }
        final Properties props = new Properties();
        try (final FileInputStream in = new FileInputStream(metaFile)) {
            props.load(in);
        } catch (final IOException e) {
            throw new XdStorageRuntimeException("storage folder " + folder + " has an unreadable " + META_FILE_NAME, e);
        }
        return props;
    }

    /**
     * Writes the marker file with the given format version plus the library version and the
     * creation timestamp. Called at storage creation (and by tests/tools that materialize a
     * folder directly); the marker file is overwritten.
     *
     * @param folder the storage folder to write the marker into
     * @param storageName the storage name recorded in the marker
     * @param format the storage format name recorded in the marker
     * @param formatVersion the on-disk format generation to record
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the file cannot be written
     */
    public static void write(final File folder, final String storageName, final String format, final int formatVersion)
            {
        final Properties props = new Properties();
        props.setProperty(KEY_FORMAT_VERSION, String.valueOf(formatVersion));
        props.setProperty(KEY_FORMAT, format);
        props.setProperty(KEY_STORAGE, storageName);
        props.setProperty(KEY_LIBRARY_VERSION, XdStorageVersion.getVersion());
        props.setProperty(KEY_CREATED_AT, java.time.OffsetDateTime.now().toString());
        final File metaFile = new File(folder, META_FILE_NAME);
        try (final OutputStream out = new FileOutputStream(metaFile)) {
            props.store(out, "XdStorage on-disk format marker (generated; do not edit)");
        } catch (final IOException e) {
            throw new XdStorageRuntimeException("cannot write " + META_FILE_NAME + " into storage folder " + folder, e);
        }
    }

    /**
     * Task 51 (LAYOUTMIG): rewrites the marker to the current format generation after a
     * successful legacy-layout migration (or on a first open of a v2 folder that needs no
     * migration — the stamp catch-up). Preserves {@code createdAt}, {@code format} and
     * {@code storage}; refreshes {@code libraryVersion} (the last writer is this library).
     * A no-op when the marker is absent (legacy 1.x folders keep the acceptMissingEofSentinel
     * contract untouched) or already at the current version. The rewrite is what makes the
     * crash-tolerance of the migration observable: a v2 marker + a mixed layout means the
     * migration did not finish and the next open completes it.
     *
     * @param folder the storage folder whose marker is upgraded
     * @param storageName the storage name recorded in the (rewritten) marker
     * @param format the storage format name recorded in the (rewritten) marker
     * @return {@code true} when the marker was rewritten
     * @throws io.github.xdstorage.exceptions.XdStorageRuntimeException when the marker cannot be written
     */
    public static boolean upgradeFormatMarkerToCurrent(final File folder, final String storageName, final String format)
            {
        final Properties meta = read(folder);
        if (meta == null) {
            return false; // the legacy 1.x no-marker contract stays untouched
        }
        final int version = parseFormatVersion(meta, storageName, folder);
        if (version >= FORMAT_VERSION) {
            return false; // already current
        }
        final Properties upgraded = new Properties();
        upgraded.setProperty(KEY_FORMAT_VERSION, String.valueOf(FORMAT_VERSION));
        upgraded.setProperty(KEY_FORMAT, meta.getProperty(KEY_FORMAT, format));
        upgraded.setProperty(KEY_STORAGE, meta.getProperty(KEY_STORAGE, storageName));
        upgraded.setProperty(KEY_LIBRARY_VERSION, XdStorageVersion.getVersion());
        upgraded.setProperty(KEY_CREATED_AT, meta.getProperty(KEY_CREATED_AT, java.time.OffsetDateTime.now()
                .toString()));
        final File metaFile = new File(folder, META_FILE_NAME);
        try (final OutputStream out = new FileOutputStream(metaFile)) {
            // Properties.store(OutputStream) is fixed ISO-8859-1 with unicode escapes by
            // spec — deterministic cross-OS (not a platform-default-charset write)
            upgraded.store(out, "XdStorage on-disk format marker (generated; do not edit)");
        } catch (final IOException e) {
            throw new XdStorageRuntimeException("cannot rewrite " + META_FILE_NAME + " in storage folder " + folder, e);
        }
        log.info("LAYOUTMIG: format marker upgraded v{} -> v{} for storage '{}' at {}",
                version, FORMAT_VERSION, storageName, folder);
        return true;
    }

    /** Anything that is not the lock file or the marker itself counts as storage content. */
    private static boolean hasStorageFiles(final File folder) {
        final File[] entries = folder.listFiles();
        if (entries == null) {
            return false;
        }
        for (final File entry : entries) {
            final String name = entry.getName();
            if (!name.equals(XdStorageFolderLock.LOCK_FILE_NAME) && !name.equals(META_FILE_NAME)) {
                return true;
            }
        }
        return false;
    }

    private static int parseFormatVersion(final Properties meta, final String storageName, final File folder) {
        final String raw = meta.getProperty(KEY_FORMAT_VERSION);
        if (raw != null) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (final NumberFormatException e) {
                throw new XdStorageRuntimeException("storage '" + storageName + "' at " + folder + " has a corrupt "
                        + META_FILE_NAME + " (formatVersion='" + raw + "')");
            }
        }
        throw new XdStorageRuntimeException("storage '" + storageName + "' at " + folder + " has a corrupt "
                + META_FILE_NAME + " (no formatVersion key)");
    }
}
