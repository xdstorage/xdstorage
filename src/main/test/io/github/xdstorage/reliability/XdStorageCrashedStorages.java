package io.github.xdstorage.reliability;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs {@link XdStorageCrashCommitter} in a child JVM and kills it once it has committed
 * (WAL, docs/tasks/2.2.0/06). The folders it committed in are then in a true post-crash
 * state — the only faithful way to get one: a folder COPY does not do, because the id-index
 * records carry the ABSOLUTE path of the data file they point at, so a copy's journal leads
 * back into the original folder.
 *
 * <p>Test fixture — not part of any API.</p>
 */
final class XdStorageCrashedStorages {

    /** The child gets the JVM up, generates wrappers and commits; normally a few seconds. */
    private static final long CHILD_TIMEOUT_SECONDS = 120L;

    private XdStorageCrashedStorages() {
    }

    /**
     * Commits in every folder named, then kills the process.
     *
     * @param root   a directory for the ready marker and the child's output
     * @param specs  {@code <folder>|<scenario>} entries, the scenario being one of the
     *               {@link XdStorageCrashCommitter} constants
     * @throws Exception when the child cannot be started
     */
    static void crashAfterCommits(final Path root, final String... specs) throws Exception {
        Files.createDirectories(root);
        final Path marker = root.resolve("crash-" + System.nanoTime() + ".ready");
        final File childLog = root.resolve("crash-child.log").toFile();

        final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        // surefire hides the real classpath behind a manifest-only jar; it names it here
        final String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        final List<String> command = new ArrayList<>(Arrays.asList(javaBin, "-cp", classpath,
                XdStorageCrashCommitter.class.getName(), marker.toString()));
        command.addAll(Arrays.asList(specs));

        final Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(childLog).start();
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHILD_TIMEOUT_SECONDS);
            while (!Files.exists(marker)) {
                if (!child.isAlive()) {
                    fail("the crash committer exited before it committed (exit " + child.exitValue() + "):\n"
                            + read(childLog.toPath()));
                }
                if (System.nanoTime() > deadline) {
                    fail("the crash committer never reported its commits:\n" + read(childLog.toPath()));
                }
                Thread.sleep(25L);
            }
        } finally {
            // the marker exists: every commit() has returned. SIGKILL: no shutdown, no checkpoint.
            child.destroyForcibly();
            child.waitFor(30L, TimeUnit.SECONDS);
        }
    }

    /** Every file under {@code dir} whose name ends in {@code -journal.yaml}. */
    static List<Path> journalsIn(final Path dir) throws IOException {
        final List<Path> journals = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith("-journal.yaml")).forEach(journals::add);
        }
        return journals;
    }

    /** The one journal under {@code dir} whose path contains {@code classDir} and whose name
     * contains {@code namePart}. */
    static Path journal(final Path dir, final String classDir, final String namePart) throws IOException {
        final List<Path> matches = new ArrayList<>();
        for (final Path journal : journalsIn(dir)) {
            if (journal.toString().contains("/" + classDir + "/") && journal.getFileName().toString()
                    .contains(namePart)) {
                matches.add(journal);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("expected exactly one " + classDir + "/" + namePart
                    + " journal under " + dir + ", found " + journalsIn(dir));
        }
        return matches.get(0);
    }

    /**
     * The data journal of one class: its records carry bodies of that class (not index records).
     *
     * @param dir        the crashed storage folder
     * @param classDir   the class's directory name under {@code data/} (the simple name)
     * @param className  the class's binary name as the {@code class:} line spells it
     */
    static Path dataJournal(final Path dir, final String classDir, final String className) throws IOException {
        for (final Path journal : journalsIn(dir)) {
            if (journal.toString().contains("/" + classDir + "/") && read(journal).contains("class: '" + className
                    + "'")) {
                return journal;
            }
        }
        throw new IllegalStateException("no journal of " + classDir + " carries bodies under " + dir + ": "
                + journalsIn(dir));
    }

    /** The id-index journal of one class ({@code index-journal.yaml} in the class's journal directory). */
    static Path indexJournal(final Path dir, final String className) throws IOException {
        return journal(dir, className, "index-journal.yaml");
    }

    static String read(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** How many batch trailers a journal carries — one per complete batch. */
    static int batches(final Path journal) throws IOException {
        return countOf(read(journal), "# xdstorage-eof");
    }

    static int countOf(final String text, final String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + fragment.length())) {
            ++count;
        }
        return count;
    }
}
