package io.github.xdstorage.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * FP-5 (docs/tasks/2.2.0/15): lightweight file-tree copier used by
 * {@link io.github.xdstorage.XdStorage#backupTo(Path)}. Deep-copies all files from
 * a source folder to a target folder, skipping the {@code .xdstorage.lock} file.
 *
 * <p>Contract: the caller must own the source folder lock (or it is already released);
 * the target folder is created if absent. After a copy the target is a self-contained
 * storage folder openable under a new name.
 */
public final class FilesCopy {

    /** Total files copied (for logging). */
    public static int fileCount;

    private FilesCopy() {
    }

    /**
     * Deep-copy {@code src} to {@code target}. Creates directories as needed;
     * skips any file whose name is exactly {@code .xdstorage.lock}.
     *
     * @param src    the source folder (must exist and be a directory)
     * @param target the target folder (created if absent)
     */
    public static void walkAndCopy(File src, File target) throws IOException {
        if (!src.isDirectory()) {
            return;
        }
        File dest = target;
        if (src.getParentFile() == null) {
            // top-level: ensure the target exists
            Files.createDirectories(dest.toPath());
        } else {
            Files.createDirectories(dest.toPath());
        }

        final File[] entries = src.listFiles();
        if (entries == null) {
            return;
        }

        for (final File entry : entries) {
            // skip the OS lock file — it travels with the copy and opens cleanly,
            // but the backup contract excludes it (the copy gets its own lock on open)
            if (entry.getName().equals(".xdstorage.lock")) {
                continue;
            }
            if (entry.isDirectory()) {
                walkAndCopy(entry, new File(dest, entry.getName()));
            } else {
                final File destFile = new File(dest, entry.getName());
                Files.createDirectories(destFile.getParentFile().toPath());
                Files.copy(entry.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                fileCount++;
            }
        }
    }
}
