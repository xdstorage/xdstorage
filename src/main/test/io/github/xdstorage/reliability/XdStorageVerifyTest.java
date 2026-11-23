package io.github.xdstorage.reliability;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.XdStorageVerifyReport;
import io.github.xdstorage.search.RangeOpsEntity;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VERIFY (docs/tasks/2.1.0/26): the closed-folder integrity walk — the recovery recipe,
 * mechanized. Every corruption class the task-23 power-loss matrix taught us about must be
 * DETECTED with a report entry naming the file; corruption is applied to COPIES, never to
 * the live originals; verify must never mutate the folder and must not require the folder
 * lock (a live storage is reported "in use", not refused).
 */
@DisplayName("VERIFY: read-only closed-folder integrity walk")
public class XdStorageVerifyTest {

    private static final long TX_TIMEOUT = 60_000L;

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ fixtures

    private IXdFileStorage open(final Path dir, final String name) {
        return XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 64);
    }

    private void saveEntities(final IXdFileStorage storage, final int count) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(TX_TIMEOUT);
        try {
            for (int i = 1; i <= count; i++) {
                storage.save(new RangeOpsEntity("vf-" + i, (long) i * 100, "label-" + i), tx);
            }
            storage.commitTransaction(tx);
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    /** a cleanly closed storage: bases authoritative, no journals (the W4a shape) */
    private Path buildGreenFolder(final String tag, final int count) throws Exception {
        final Path dir = tempDir.resolve("green-" + tag);
        final IXdFileStorage storage = open(dir, "verify-green-" + tag + "-" + System.nanoTime());
        saveEntities(storage, count);
        storage.shutdown();
        return dir;
    }

    /** the crash proxy: a folder copy taken WHILE the storage is open — journals present */
    private Path buildWhileOpenCopy(final String tag) throws Exception {
        final Path live = tempDir.resolve("live-" + tag);
        final IXdFileStorage storage = open(live, "verify-live-" + tag + "-" + System.nanoTime());
        saveEntities(storage, 5);
        final Path copy = tempDir.resolve("crash-" + tag);
        try (final Stream<Path> walk = Files.walk(live)) {
            for (final Path src : walk.collect(Collectors.toList())) {
                final Path dest = copy.resolve(live.relativize(src).toString());
                if (src.toFile().isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        storage.shutdown();
        return copy;
    }

    private Path findDataBase(final Path folder) throws Exception {
        try (final Stream<Path> walk = Files.walk(folder.resolve("data"))) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        final String n = p.getFileName().toString();
                        return n.startsWith("RangeOpsEntity-") && n.endsWith(".yaml")
                                && !n.endsWith("-journal.yaml") && !n.endsWith(".journal.yaml");
                    })
                    .findFirst().orElseThrow(() -> new IllegalStateException("no data base under " + folder));
        }
    }

    private Path findDataJournal(final Path folder) throws Exception {
        try (final Stream<Path> walk = Files.walk(folder.resolve("data"))) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("-journal.yaml"))
                    .filter(p -> p.toString().contains("RangeOpsEntity"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no data journal under " + folder));
        }
    }

    /** byte-identical folder fingerprint (content, not mtimes) */
    private long folderChecksum(final Path folder) throws Exception {
        final CRC32 crc = new CRC32();
        final List<Path> files = new ArrayList<>();
        try (final Stream<Path> walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        for (final Path file : files) {
            crc.update(folder.relativize(file).toString().getBytes("UTF-8"));
            crc.update(Files.readAllBytes(file));
        }
        return crc.getValue();
    }

    // ------------------------------------------------------------------ the walk

    @Test
    @DisplayName("a healthy folder verifies clean — verdict OK, counts consistent, nothing mutated")
    public void greenFolderVerifiesClean() throws Exception {
        final Path dir = buildGreenFolder("ok", 5);
        final long before = folderChecksum(dir);

        final XdStorageVerifyReport report = XdStorageProvider.verify(dir);

        assertEquals(XdStorageVerifyReport.Verdict.OK, report.verdict,
                "a healthy folder must verify clean, got: " + report);
        assertTrue(report.findings.isEmpty(), "no findings on a healthy folder: " + report.findings);
        assertTrue(report.isOk());
        assertNotNull(report.formatVersion, "the format marker is read");
        assertEquals("3", report.formatVersion); // the v3 layout generation (task 51 LAYOUTMIG)
        assertFalse(report.inUse);

        // counts: the entity fragment holds 5 objects, the id-index 5 entries
        final XdStorageVerifyReport.FileReport base = report.files.stream()
                .filter(f -> f.path.contains("data/RangeOpsEntity") && "base".equals(f.kind))
                .findFirst().orElseThrow(() -> new IllegalStateException("no base row in " + report.files));
        assertEquals(5, base.entries, "the base entry count");
        final XdStorageVerifyReport.FileReport index = report.files.stream()
                .filter(f -> "index".equals(f.kind))
                .findFirst().orElseThrow(() -> new IllegalStateException("no index row in " + report.files));
        assertEquals(5, index.entries, "id-index entries match the data objects");
        assertFalse(report.toString().isEmpty());

        assertEquals(before, folderChecksum(dir), "verify must never mutate the folder");
    }

    @Test
    @DisplayName("a truncated base is detected CORRUPT, the file named")
    public void truncatedBaseDetected() throws Exception {
        final Path dir = buildGreenFolder("trunc", 5);
        final Path copy = tempDir.resolve("copy-trunc");
        try (final Stream<Path> walk = Files.walk(dir)) {
            for (final Path src : walk.collect(Collectors.toList())) {
                final Path dest = copy.resolve(dir.relativize(src).toString());
                if (src.toFile().isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        final byte[] all = Files.readAllBytes(findDataBase(copy));
        Files.write(findDataBase(copy), java.util.Arrays.copyOf(all, all.length * 6 / 10));

        final XdStorageVerifyReport report = XdStorageProvider.verify(copy);
        assertEquals(XdStorageVerifyReport.Verdict.CORRUPT, report.verdict, "truncation is corruption: " + report);
        assertTrue(report.findings.stream().anyMatch(f -> f.contains("TRUNCATED") && f.contains("RangeOpsEntity")),
                "the finding names the truncated base: " + report.findings);
    }

    @Test
    @DisplayName("a torn journal is detected (designed lost-tail — WARNINGS, the file named)")
    public void tornJournalDetected() throws Exception {
        final Path copy = buildWhileOpenCopy("torn");
        final Path journal = findDataJournal(copy);
        final byte[] all = Files.readAllBytes(journal);
        Files.write(journal, java.util.Arrays.copyOf(all, all.length * 6 / 10));

        final XdStorageVerifyReport report = XdStorageProvider.verify(copy);
        assertEquals(XdStorageVerifyReport.Verdict.WARNINGS, report.verdict,
                "a torn journal opens clean by design (lost tail) but must be named: " + report);
        assertTrue(report.findings.stream().anyMatch(f -> f.contains("JOURNAL TORN")),
                "the torn journal is named: " + report.findings);
        assertEquals(1, report.findings.stream().filter(f -> f.contains("JOURNAL TORN")).count(),
                "exactly the cut journal is reported torn");
    }

    @Test
    @DisplayName("a missing format marker is detected (legacy folder — WARNINGS, never CORRUPT)")
    public void missingMarkerDetected() throws Exception {
        final Path dir = buildGreenFolder("nomark", 5);
        Files.delete(dir.resolve(".xdstorage.meta"));

        final XdStorageVerifyReport report = XdStorageProvider.verify(dir);
        assertEquals(XdStorageVerifyReport.Verdict.WARNINGS, report.verdict,
                "a 2.0 folder without its marker is a legacy note, not corruption: " + report);
        assertTrue(report.findings.stream().anyMatch(f -> f.contains("NO FORMAT MARKER")),
                "the missing marker is named: " + report.findings);
        assertNull(report.formatVersion);
    }

    @Test
    @DisplayName("a journal without its base (the un-checkpointed crash shape) is detected — the file named")
    public void orphanedJournalDetected() throws Exception {
        // the crash proxy: while open, the W4a write path journals WITHOUT a base — every
        // data journal is deliberately base-less, exactly what the finding must name
        final Path copy = buildWhileOpenCopy("orphan");

        final XdStorageVerifyReport report = XdStorageProvider.verify(copy);
        assertTrue(report.findings.stream().anyMatch(f -> f.contains("ORPHANED JOURNAL") && f
                .contains("RangeOpsEntity")),
                "the base-less journal is named: " + report.findings);
        // and the folder still verifies as tolerable: the open replays the journals
        assertEquals(XdStorageVerifyReport.Verdict.WARNINGS, report.verdict,
                "base-less journals are the designed un-checkpointed shape, not corruption: " + report);
    }

    @Test
    @DisplayName("stale .tmp litter is detected — the file named")
    public void tmpLitterDetected() throws Exception {
        final Path dir = buildGreenFolder("tmp", 5);
        Files.write(dir.resolve("data/RangeOpsEntity/RangeOpsEntity-vf-1.yaml.tmp"),
                "half a crash".getBytes("UTF-8"));

        final XdStorageVerifyReport report = XdStorageProvider.verify(dir);
        assertTrue(report.findings.stream().anyMatch(f -> f.contains("STALE TMP") && f.contains(".yaml.tmp")),
                "the litter is named: " + report.findings);
    }

    @Test
    @DisplayName("a hand-edited folder: data objects vs id-index entries mismatch is detected CORRUPT")
    public void countMismatchDetected() throws Exception {
        final Path five = buildGreenFolder("five", 5);
        final Path four = buildGreenFolder("four", 4);
        final Path copy = tempDir.resolve("copy-mismatch");
        try (final Stream<Path> walk = Files.walk(five)) {
            for (final Path src : walk.collect(Collectors.toList())) {
                final Path dest = copy.resolve(five.relativize(src).toString());
                if (src.toFile().isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // the hand edit: the base silently loses one object (a valid, sentinel-carrying file
        // that simply disagrees with the index)
        Files.write(findDataBase(copy), Files.readAllBytes(findDataBase(four)));

        final XdStorageVerifyReport report = XdStorageProvider.verify(copy);
        assertEquals(XdStorageVerifyReport.Verdict.CORRUPT, report.verdict, "a count mismatch is corruption: " +
                report);
        assertTrue(report.findings.stream().anyMatch(f -> f.contains("COUNT MISMATCH") && f
                .contains("4 data object(s) vs 5 id-index")),
                "the mismatch names the class dir and both numbers: " + report.findings);
    }

    @Test
    @DisplayName("verifying a live storage reports IN USE (never refuses); closed — not in use")
    public void verifyWhileOpenReportsInUse() throws Exception {
        final Path dir = tempDir.resolve("inuse");
        final IXdFileStorage storage = open(dir, "verify-inuse-" + System.nanoTime());
        saveEntities(storage, 5);

        final XdStorageVerifyReport live = XdStorageProvider.verify(dir);
        assertTrue(live.inUse, "a live storage holds the folder lock");
        assertTrue(live.findings.stream().anyMatch(f -> f.contains("IN USE")),
                "the in-use finding is present: " + live.findings);
        assertNotNull(live.verdict);

        storage.shutdown();
        final XdStorageVerifyReport closed = XdStorageProvider.verify(dir.toFile());
        assertFalse(closed.inUse, "the lock is released at shutdown (the file itself may remain)");
    }
}
