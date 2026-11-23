package io.github.xdstorage.properties;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.Child;
import io.github.xdstorage.entities.foreignkeys.Parent;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import org.junit.jupiter.api.Disabled;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AfterTry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPTEST Этап 4 (docs/tasks/2.2.0/03 — beyond the task's scope): the HIGH-LEVEL
 * contracts as properties:
 * <ul>
 *   <li>P4.1: the FK veto/cascade contracts hold under RANDOM parent/child shapes —
 *       the legal remove cycle (remove → update → delete) succeeds; the three vetoes
 *       (the duplicate pair, the referenced child, the parent with children) refuse
 *       loudly; the round trips are exact;</li>
 *   <li>P4.2: the id generation NEVER reissues an id (the generated Long ids are unique
 *       across the whole run including the reopens), the app-assigned ids are never
 *       overwritten.</li>
 * </ul>
 */
public class PropCoreContractsTest {

    private final List<File> folders = new ArrayList<>();

    @AfterTry
    void cleanup() {
        for (final File dir : folders) {
            deleteRecursively(dir);
        }
        folders.clear();
    }

    private static void deleteRecursively(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteRecursively(f);
            }
        }
        file.delete();
    }

    // ───────────────────────── P4.1: the FK veto/cascade property ─────────────────────────

    @Property(tries = 3)
    // TRIAGE (2026-11-23): temporarily enabled — FINDING #7, the history in the task file
    // (the triage result: NOT reproducible outside the jqwik run — see the diag prints)
    void fkVetoAndLegalCycle_HoldUnderRandomShapes(@ForAll final int seed) throws Exception {
        final Random rnd = new Random(seed);
        final File dir = new File("./target/pt-fk-" + System.nanoTime());
        folders.add(dir);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "pt-fk-" + System.nanoTime(), dir.getPath(), 128,
                io.github.xdstorage.serialization.FilesFormat.YAML);
        try {
            // the random parent/child shape — the CANONICAL FK pattern: the parent saved
            // FIRST (the AutoSave cascade persists the children; the FK engine registers
            // the relations on the parent's save)
            final Parent parent = new Parent();
            parent.setId("p" + seed);
            final int children = 1 + rnd.nextInt(5);
            final List<String> childIds = new ArrayList<>();
            for (int c = 0; c < children; c++) {
                final Child child = new Child();
                child.setId("p" + seed + "-c" + c);
                child.setParent(parent);
                parent.addChild(child);
            }
            final IXdStorageTransaction saveTx = tx(storage);
            System.out.println("[PT-FK][step] before-save instance=" + System.identityHashCode(storage)
                    + " name=" + storage.getName() + " tx=" + saveTx.getTransactionId());
            storage.save(parent, saveTx);
            System.out.println("[PT-FK][step] after-save has=" + storage.has(Parent.class, parent.getId(), saveTx));
            storage.commitTransaction(saveTx);
            System.out.println("[PT-FK][step] after-commit has=" + storage.has(Parent.class, parent.getId(),
                    storage.beginTransaction(60000L)));
            for (int c = 0; c < children; c++) {
                childIds.add("p" + seed + "-c" + c);
            }
            { // the save-probe: is the parent reachable IN THE SAME storage right after?
                final IXdStorageTransaction probe = tx(storage);
                final boolean has = storage.has(Parent.class, parent.getId(), probe);
                final Parent probed = storage.load(Parent.class, parent.getId(), probe);
                final IXdStorageTransaction childTx = storage.beginTransaction(60000L);
                final Child childProbe = storage.load(Child.class, childIds.get(0), childTx);
                System.out.println("[PT-FK][diag] seed=" + seed + " children=" + children
                        + " id='" + parent.getId() + "' has=" + has
                        + " probe-load=" + (probed == null ? "NULL" : "ok(" + probed.getChildren().size() + ")")
                        + " child-load=" + (childProbe == null ? "NULL" : childProbe.getId()));
                storage.commitTransaction(childTx);
                // the FILE TRUTH dump (the java-profiler skill's file-truth discipline)
                final File root = dir;
                try (final java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root.toPath())) {
                    for (final Path p2 : (Iterable<Path>) walk.filter(java.nio.file.Files::isRegularFile)::iterator) {
                        final String content = new String(java.nio.file.Files.readAllBytes(p2),
                                java.nio.charset.StandardCharsets.UTF_8);
                        System.out.println("[PT-FK][file] " + root.toPath().relativize(p2) + " (" + content.length() + "b) head='"
                                + content.substring(0, Math.min(60, content.length())).replace("\n", "|").replace("\r", "") + "'");
                    }
                }
                storage.rollbackTransaction(probe);
            }

            // the round trip: the parent + all the children load with the exact relations
            final IXdStorageTransaction check = tx(storage);
            final Parent loaded = storage.load(Parent.class, parent.getId(), check);
            assertEquals(children, loaded.getChildren().size(), "the child count diverges");
            for (final String cid : childIds) {
                final Child child = storage.load(Child.class, cid, check);
                requireNotNull(child, "the child must load");
                assertEquals(parent.getId(), child.getParent().getId(), "the back-reference diverges");
            }
            storage.commitTransaction(check);

            // VETO 1: deleting a child ALONE (the parent still references it) refuses loudly
            final String victim = childIds.get(rnd.nextInt(childIds.size()));
            final IXdStorageTransaction vetoTx = tx(storage);
            final Child child = storage.load(Child.class, victim, vetoTx);
            storage.delete(child, vetoTx); // the delete STAGES; the veto fires at the commit
            final XdStorageRuntimeException veto = assertThrows(XdStorageRuntimeException.class,
                    () -> storage.commitTransaction(vetoTx),
                    "the delete-child-alone veto must refuse loudly at the commit (the CANON phase-1)");
            assertTrue(veto.getMessage().contains("foreign key") || veto.getMessage().contains("foreign")
                    || veto.getMessage().contains("child"), "the veto names the FK mechanism: " + veto.getMessage());
            storage.rollbackTransaction(vetoTx);

            // the LEGAL cycle: remove from the collection → update the parent → delete the child
            final IXdStorageTransaction legalTx = tx(storage);
            final Parent pForRemove = storage.load(Parent.class, parent.getId(), legalTx);
            final Child legalChild = storage.load(Child.class, victim, legalTx);
            requireNotNull(legalChild, "the child must load for the legal cycle");
            pForRemove.getChildren().removeIf(c -> victim.equals(c.getId()));
            storage.update(pForRemove, legalTx);
            storage.delete(legalChild, legalTx);
            storage.commitTransaction(legalTx);
            childIds.remove(victim);

            // VETO 2: deleting the PARENT while children remain refuses loudly
            if (!childIds.isEmpty()) {
                final IXdStorageTransaction veto2 = tx(storage);
                final Parent remaining = storage.load(Parent.class, parent.getId(), veto2);
                storage.delete(remaining, veto2); // stages
                assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(veto2),
                        "the delete-parent-with-children veto must refuse loudly at the commit");
                storage.rollbackTransaction(veto2);
            }

            // the FULL legal deletion: the children first, the parent after — succeeds
            final IXdStorageTransaction fullTx = tx(storage);
            for (final String cid : childIds) {
                final Child c = storage.load(Child.class, cid, fullTx);
                if (c != null) {
                    storage.delete(c, fullTx);
                }
            }
            final Parent parentLast = storage.load(Parent.class, parent.getId(), fullTx);
            if (parentLast != null) {
                storage.delete(parentLast, fullTx);
            }
            storage.commitTransaction(fullTx);
        } finally {
            storage.shutdown();
        }
    }

    private static IXdStorageTransaction tx(final IXdFileStorage storage) {
        // the 60s budget: the first-save wrapper codegen + the FK registration exceed the
        // 3s default — the sweeper swept the save mid-commit (the proven trap)
        return storage.beginTransaction(60000L);
    }

    private static void requireNotNull(final Object o, final String message) {
        if (o == null) {
            throw new AssertionError(message);
        }
    }

    // ───────────────────────── P4.2: the id generation never reissues ─────────────────────────

    @Property(tries = 6)
    // TRIAGE (2026-11-23): temporarily enabled — FINDING #8 (the lifecycle fix landed: the
    // reopenAndCollect returns the LIVE reopened storage; the caller reassigns)
    void generatedIds_NeverReissued_AcrossRandomWavesAndReopens(@ForAll final long seed) throws Exception {
        final Random rnd = new Random(seed);
        final File dir = new File("./target/pt-id-" + System.nanoTime());
        folders.add(dir);
        final Set<Long> allIssuedIds = new HashSet<>();
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "pt-id-" + System.nanoTime(), dir.getPath(), 64);
        try {
            // the random waves of saves: every wave issues fresh generated ids
            for (int wave = 0; wave < 4; wave++) {
                final int count = 5 + rnd.nextInt(20);
                for (int i = 0; i < count; i++) {
                    final IXdStorageTransaction tx = storage.beginTransaction();
                    final io.github.xdstorage.properties.PropIdEntity e = new io.github.xdstorage.properties.PropIdEntity();
                    e.setPayload("wave" + wave + "-" + i);
                    storage.save(e, tx);
                    storage.commitTransaction(tx);
                    final Long id = e.getId();
                    if (id == null) {
            throw new AssertionError("the generated id must backfill the same instance");
        }
                    assertTrue(allIssuedIds.add(id),
                            "the generated id " + id + " was REISSUED (the counter reused an id)");
                }
                if (rnd.nextBoolean()) {
                    storage.shutdown(); // the reopen burns the batch remainder — never reissues
                    storage = reopenAndCollect(storage, dir, rnd.nextInt(5));
                }
            }
            storage.shutdown();

            // the app-assigned id is never overwritten: save → the same id comes back
            final IXdFileStorage reopened = XdStorageProvider.newOrGetFileStorage(
                    "pt-id-re-" + System.nanoTime(), dir.getPath(), 64);
            final PropIdEntity manual = new PropIdEntity();
            manual.setId(999_999_999L);
            manual.setPayload("manual");
            final IXdStorageTransaction tx = reopened.beginTransaction();
            reopened.save(manual, tx);
            reopened.commitTransaction(tx);
            reopened.shutdown();
            assertEquals(999_999_999L, manual.getId().longValue(), "the app-assigned id was overwritten");
        } finally {
            storage.shutdown();
        }
    }

    /** The reopen burns the batch remainder; the extra saves collect their fresh ids; RETURNS the live storage. */
    private IXdFileStorage reopenAndCollect(final IXdFileStorage storage, final File dir, final int extraSaves) throws Exception {
        final IXdFileStorage reopened = XdStorageProvider.newOrGetFileStorage(
                "pt-id-reopen-" + System.nanoTime(), dir.getPath(), 64);
        final Set<Long> ids = new HashSet<>();
        for (int i = 0; i < extraSaves; i++) {
            final IXdStorageTransaction tx = reopened.beginTransaction();
            final io.github.xdstorage.properties.PropIdEntity e = new io.github.xdstorage.properties.PropIdEntity();
            e.setPayload("reopen-" + i);
            reopened.save(e, tx);
            reopened.commitTransaction(tx);
            ids.add(e.getId());
        }
        allIssuedIds.addAll(ids);
        return reopened;
    }

    private final Set<Long> allIssuedIds = new HashSet<>();
}
