package io.github.xdstorage.helpers;

import java.nio.file.Path;

/**
 * Path spellings for the tests — the ones that must read the same on every CI leg.
 *
 * <p>{@code Path.toString()} says the platform's separator: {@code /} on Linux, {@code \} on
 * Windows. A test that spells the separator literally — {@code path.toString().contains(
 * "/data/")}, an expected {@code "io/github/…/X.java"} against {@code relativize(file)} — is
 * green on Linux and red on Windows. Three Windows runs of the suite lost tests to this
 * habit (2026-11-27, 2026-11-28); {@code XdStorageTestPortabilityContractTest} now refuses
 * the most common spelling in the test sources.</p>
 *
 * <p>Test fixture — not part of any API.</p>
 */
public final class StoragePaths {

    private StoragePaths() {
    }

    /**
     * The path spelled with the slash, whatever the platform's separator is — for a string
     * comparison, a {@code contains("/data/")}, a source location, a report line.
     *
     * @param path the path to spell
     * @return the path's string form with every backslash turned into a slash
     */
    public static String slashes(final Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * Whether {@code element} is a whole name of the path — a directory or the file itself —
     * which is what {@code toString().contains("/" + element + "/")} means and only says where
     * the separator is a slash.
     *
     * @param path    the path to look at
     * @param element the name to find
     * @return whether one of the path's names equals {@code element}
     */
    public static boolean hasElement(final Path path, final String element) {
        for (final Path name : path) {
            if (name.toString().equals(element)) {
                return true;
            }
        }
        return false;
    }
}
