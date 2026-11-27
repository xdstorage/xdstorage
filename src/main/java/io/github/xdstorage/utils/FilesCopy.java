package io.github.xdstorage.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The copy behind {@code IXdFileStorage.backupTo} (FP-5, docs/tasks/2.2.0/15).
 *
 * <p><b>Why a plain copy is not a backup.</b> The id index stores, for every record, the
 * PATH of the file that holds it — the path as the storage's folder was given, verbatim:
 * absolute for a folder opened by its absolute path, working-directory-relative for a
 * relative one (EMBEDDING § Upgrade from 2.0.0, limitation 1). A copy of the folder
 * therefore still points INTO THE ORIGINAL: opened where the original is gone, it answers
 * {@code null} for records that sit in its own {@code data/} directory; opened next to a
 * living original, it answers with the original's current files. {@link #copyStorage}
 * copies the tree and rewrites that prefix, in every file of the copy, to the copy's own
 * path — so the copy stands on its own.</p>
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public final class FilesCopy {

    /** The folder lock's file — the one file of a storage that must not travel with a copy. */
    public static final String LOCK_FILE_NAME = XdStorageFolderLock.LOCK_FILE_NAME;

    private FilesCopy() {
    }

    /**
     * Copies a closed storage folder and makes the copy self-contained.
     *
     * @param source         the storage folder, closed
     * @param target         where the copy goes; created when absent, expected empty
     * @param recordedPrefix the folder path as the storage was given it — what its files name
     * @param ownPrefix      what the copy's files name instead: the target's absolute path
     * @return the number of files copied
     * @throws IOException when a file cannot be read or written
     */
    public static int copyStorage(final Path source, final Path target, final String recordedPrefix,
                                  final String ownPrefix) throws IOException {
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        int copied = 0;
        for (final Path file : files) {
            if (file.getFileName().toString().equals(LOCK_FILE_NAME)) {
                continue;
            }
            final Path into = target.resolve(source.relativize(file).toString());
            Files.createDirectories(into.getParent());
            final String name = file.getFileName().toString();
            if (name.endsWith(".yaml") || name.endsWith(".xml") || name.startsWith(".")) {
                Files.write(into, relocate(Files.readAllBytes(file), recordedPrefix, ownPrefix));
            } else {
                Files.copy(file, into);
            }
            copied++;
        }
        return copied;
    }

    /**
     * Rewrites the recorded folder prefix in the content of a resource file — UTF-8 text,
     * YAML or XML. The prefix is rewritten only where a path is written: directly after a
     * quote or the end of an XML tag, and directly before a path separator; in each of the
     * three spellings a path can take in those files (plain, single-quoted YAML with the
     * apostrophe doubled, XML with its five entities).
     *
     * @param content the file's bytes
     * @param from    the recorded prefix — the folder path as the storage was given it
     * @param to      the copy's own path
     * @return the rewritten bytes, or {@code content} itself when nothing was rewritten
     */
    static byte[] relocate(final byte[] content, final String from, final String to) {
        if (from.isEmpty()) {
            return content;
        }
        final String text = new String(content, StandardCharsets.UTF_8);
        final Set<String> forms = new LinkedHashSet<>();
        forms.add(from + "\n" + to);
        forms.add(yaml(from) + "\n" + yaml(to));
        forms.add(xml(from) + "\n" + xml(to));
        String result = text;
        for (final String form : forms) {
            final int cut = form.indexOf('\n');
            result = replacePaths(result, form.substring(0, cut), form.substring(cut + 1));
        }
        return result.equals(text) ? content : result.getBytes(StandardCharsets.UTF_8);
    }

    private static String replacePaths(final String text, final String from, final String to) {
        final StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (true) {
            final int found = text.indexOf(from, at);
            if (found < 0) {
                return out.append(text, at, text.length()).toString();
            }
            final int after = found + from.length();
            final boolean pathStart = found == 0 || isDelimiter(text.charAt(found - 1));
            final boolean pathContinues = after < text.length()
                    && (text.charAt(after) == '/' || text.charAt(after) == '\\');
            if (pathStart && pathContinues) {
                out.append(text, at, found).append(to);
                at = after;
            } else {
                out.append(text, at, after);
                at = after;
            }
        }
    }

    private static boolean isDelimiter(final char before) {
        return before == '\'' || before == '"' || before == '>';
    }

    private static String yaml(final String path) {
        return path.replace("'", "''");
    }

    private static String xml(final String path) {
        return path.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Deletes a directory tree. Best effort: what cannot be deleted is left and not reported
     * — the callers use it to take back a half-written copy.
     *
     * @param root the directory to delete
     */
    public static void deleteRecursively(final Path root) {
        if (!Files.exists(root)) {
            return;
        }
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(files::add);
        } catch (final IOException ignored) {
            return;    // nothing to take back
        }
        files.sort(java.util.Comparator.reverseOrder());
        for (final Path file : files) {
            file.toFile().delete();
        }
    }
}
