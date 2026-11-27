package io.github.xdstorage.helpers;

import io.github.xdstorage.utils.XdStorageFolderLock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies a storage folder for the tests — also WHILE the storage is open, which is how the
 * journal tests build their crash proxy: a copy taken before any clean shutdown carries the
 * journals exactly as a power loss left them (task 19 semantics).
 *
 * <p><b>The folder lock's file is left out.</b> While the storage is open the engine holds it
 * with an operating-system lock. That lock is mandatory on Windows: no other handle can so
 * much as read the file, and a copy of an open folder fails there with "The process cannot
 * access the file because it is being used by another process" — on POSIX the lock is advisory
 * and reading works, which is why the first Windows run of the suite (2026-11-27) lost 21
 * tests to this and no Linux run ever did. The file carries no storage content; the copy
 * takes its own lock when it is opened.</p>
 *
 * <p>Test fixture — not part of any API.</p>
 */
public final class StorageFolderCopy {

    private StorageFolderCopy() {
    }

    /**
     * Copies {@code from} into {@code to}, directories and files, replacing what is already
     * there; the folder lock's file is not copied.
     *
     * @param from the storage folder — open or closed
     * @param to   the destination, created when absent
     * @throws IOException when any other file cannot be read or written
     */
    public static void copy(final Path from, final Path to) throws IOException {
        final List<Path> content = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(from)) {
            walk.forEach(content::add);
        }
        for (final Path source : content) {
            final Path target = to.resolve(from.relativize(source).toString());
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
            } else if (!isLockFile(source)) {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Whether the file is the folder lock's — the one file of an open storage that may be unreadable. */
    public static boolean isLockFile(final Path file) {
        final Path name = file.getFileName();
        return name != null && name.toString().equals(XdStorageFolderLock.LOCK_FILE_NAME);
    }
}
