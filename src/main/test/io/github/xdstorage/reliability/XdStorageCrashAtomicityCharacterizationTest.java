package io.github.xdstorage.reliability;

import io.github.xdstorage.BenchmarkEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.code.ValueChild;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A CHARACTERIZATION, not a requirement (WAL design round, docs/tasks/2.2.0/06 —
 * docs/analysis/COMMIT_ATOMICITY_DESIGN.md). It records a hole the engine has TODAY, so that
 * the claim "a commit is not crash-atomic across resources" is an executed fact rather than a
 * reading of the code — and so that the fix has a test to turn around.
 *
 * <p>One transaction over two classes is FOUR journal appends: the data journal and the
 * id-index journal of each class. A crash between two of them leaves a state no committed and
 * no aborted transaction can produce. Here the crash seams are realized on real post-crash
 * folders (a child process commits and is killed): one of the four appends is removed — the
 * state a crash one append earlier would have left — and the folder is reopened.</p>
 *
 * <p><b>What the test asserts is the defect:</b> whatever single append is missing, exactly
 * one of the two rows survives. When the cross-resource commit marker lands, this class must
 * be rewritten to assert the opposite — that NO row of the transaction survives any seam.</p>
 */
@DisplayName("ХАРАКТЕРИЗАЦИЯ (дыра): коммит не атомарен между ресурсами при сбое процесса")
public class XdStorageCrashAtomicityCharacterizationTest {

    /** The four appends of the transaction, as journal files under the storage folder. */
    private enum Append {
        CUSTOMER_DATA("BenchmarkEntity", "BenchmarkEntity-c-1-journal.yaml", "the customer's data journal"),
        CUSTOMER_INDEX("BenchmarkEntity", "index-journal.yaml", "the customer's id-index journal"),
        ORDER_DATA("ValueChild", "ValueChild-1-journal.yaml", "the order's data journal"),
        ORDER_INDEX("ValueChild", "index-journal.yaml", "the order's id-index journal");

        final String classDir;
        final String fileName;
        final String title;

        Append(final String classDir, final String fileName, final String title) {
            this.classDir = classDir;
            this.fileName = fileName;
            this.title = title;
        }

        Path in(final Path folder) {
            return folder.resolve("data").resolve(classDir).resolve("journal").resolve(fileName);
        }
    }

    @TempDir
    static Path root;

    private static Path control;

    @BeforeAll
    static void crashOnce() throws Exception {
        final List<String> specs = new ArrayList<>();
        control = root.resolve("control");
        specs.add(control + "|" + XdStorageCrashCommitter.TWO_CLASSES);
        for (final Append append : Append.values()) {
            specs.add(root.resolve("without-" + append.name()) + "|" + XdStorageCrashCommitter.TWO_CLASSES);
        }
        XdStorageCrashedStorages.crashAfterCommits(root, specs.toArray(new String[0]));
    }

    /** The rows of the transaction a reader can actually read after the reopen. */
    private static List<String> rowsReadableIn(final Path folder) throws Exception {
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("reopen-" + UUID.randomUUID(),
                folder.toString(), 128);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
            final List<String> rows = new ArrayList<>();
            try {
                final BenchmarkEntity customer = storage.load(BenchmarkEntity.class, "c-1", tx);
                if (customer != null && "Alice".equals(customer.getPayload())) {
                    rows.add("customer");
                }
            } catch (final Exception unreadable) {
                // not readable
            }
            try {
                final ValueChild order = storage.load(ValueChild.class, 1L, tx);
                if (order != null && "child".equals(order.getLabel())) {
                    rows.add("order");
                }
            } catch (final Exception unreadable) {
                // not readable
            }
            storage.rollbackTransaction(tx);
            return rows;
        } finally {
            storage.shutdown();
        }
    }

    @Test
    @DisplayName("Контроль: все четыре дописывания на диске — транзакция видна целиком")
    public void control_TheTransactionIsWholeWhenEveryAppendSurvived() throws Exception {
        for (final Append append : Append.values()) {
            assertTrue(Files.exists(append.in(control)), append.title + " exists after the commit: " + append.in(
                    control));
        }
        assertEquals(Arrays.asList("customer", "order"), rowsReadableIn(control));
    }

    @Test
    @DisplayName("Одно из четырёх дописываний не долетело до диска: выживает ровно одна строка из двух")
    public void aCrashBetweenAppends_LeavesTheTransactionHalfApplied() throws Exception {
        final List<String> report = new ArrayList<>();
        for (final Append append : Append.values()) {
            final Path folder = root.resolve("without-" + append.name());
            assertTrue(Files.deleteIfExists(append.in(folder)), append.title + " is removed: the seam before it");

            final List<String> rows = rowsReadableIn(folder);
            report.add(append.title + " missing -> " + rows);

            // THE HOLE: neither "all" nor "none"
            assertEquals(1, rows.size(), "with " + append.title + " missing the transaction is half applied: "
                    + rows + " (an atomic commit would leave either both rows or none)");
        }
        System.out.println("[CRASH-ATOMICITY] the characterization, one seam per line:");
        for (final String line : report) {
            System.out.println("[CRASH-ATOMICITY]   " + line);
        }
    }
}
