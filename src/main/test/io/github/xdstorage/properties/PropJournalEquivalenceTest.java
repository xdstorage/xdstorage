package io.github.xdstorage.properties;

import io.github.xdstorage.BinFeasEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AfterTry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import io.github.xdstorage.helpers.StorageFolderCopy;


/**
 * PROPTEST P1.3 (docs/tasks/2.2.0/03): the JOURNAL REPLAY EQUIVALENCE — after a random
 * op sequence, three recovery paths must land on the IDENTICAL state:
 * <ol>
 *   <li>the copy-replay: the folder copied WHILE OPEN (the journals carry the
 *       un-checkpointed tail) → the copy opened → the journal replay restores it;</li>
 *   <li>the checkpoint path: the original shut down cleanly (the checkpoint folds the
 *       journals into the bases) → reopened;</li>
 *   <li>the direct baseline: the same op sequence replayed through the model.</li>
 * </ol>
 * The paths must agree with the model AND with each other, entity-by-entity.
 *
 * <p>The fixture lesson (task 14/LAYOUTMIG): a RELATIVE root keeps the persisted
 * id→fragment payload relocatable, so the copy opens without a takeover.
 */
public class PropJournalEquivalenceTest {

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

    /** The random op sequence model: inserts/updates/deletes over BinFeasEntity. */
    private Map<String, String> runSequence(final IXdFileStorage storage, final Random rnd, final int ops) throws
            Exception {
        final Map<String, String> model = new HashMap<>();
        for (int i = 0; i < ops; i++) {
            final int op = rnd.nextInt(100);
            final String key = "e" + rnd.nextInt(200);
            final IXdStorageTransaction tx = storage.beginTransaction();
            if ((op < 50 || model.isEmpty()) && !model.containsKey(key)) {
                // save = INSERT only (the documented contract): a re-save of a stored id refuses;
                // the generator mirrors the real workload (insert fresh keys, update existing ones)
                final BinFeasEntity e = new BinFeasEntity(key, key.hashCode());
                storage.save(e, tx);
                model.put(key, e.getName());
            } else if (model.containsKey(key) || op < 75) {
                final BinFeasEntity e = storage.load(BinFeasEntity.class, key, tx);
                if (e != null) {
                    e.setName(e.getName() + "-u" + i);
                    storage.update(e, tx);
                    model.put(key, e.getName());
                }
            } else {
                final BinFeasEntity e = storage.load(BinFeasEntity.class, key, tx);
                if (e != null) {
                    storage.delete(e, tx);
                    model.remove(key);
                }
            }
            storage.commitTransaction(tx);
        }
        return model;
    }

    private Map<String, String> readAll(final IXdFileStorage storage) throws Exception {
        final Map<String, String> state = new HashMap<>();
        final IXdStorageTransaction tx = storage.beginTransaction();
        for (final BinFeasEntity e : storage.load(BinFeasEntity.class, (IXdStoragePredicate<BinFeasEntity>) o -> true,
                tx)) {
            state.put(e.getId(), e.getName());
        }
        storage.commitTransaction(tx);
        return state;
    }

    /** The crash proxy: a copy taken while the storage is open; the folder lock's file stays behind. */
    private static void copyTree(final File from, final File to) throws IOException {
        StorageFolderCopy.copy(from.toPath(), to.toPath());
    }

    private static void assertEqualsMaps(final Map<String, String> expected, final Map<String, String> actual, final
            String what) {
        final List<String> problems = new ArrayList<>();
        for (final Map.Entry<String, String> e : expected.entrySet()) {
            if (!actual.containsKey(e.getKey())) {
                problems.add("lost: " + e.getKey());
            } else if (!e.getValue().equals(actual.get(e.getKey()))) {
                problems.add("diverged: " + e.getKey() + " '" + e.getValue() + "' vs '" + actual.get(e.getKey()) + "'");
            }
        }
        for (final String key : actual.keySet()) {
            if (!expected.containsKey(key)) {
                problems.add("phantom: " + key);
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(what + ": " + problems);
        }
    }

    @Property(tries = 15)
    void replayCheckpointAndDirectPaths_AgreeWithTheModel(@ForAll final long seed) throws Exception {
        final Random rnd = new Random(seed);
        final File dirA = new File("./target/pt-jnl-a-" + System.nanoTime());
        final File dirCopy = new File("./target/pt-jnl-copy-" + System.nanoTime());
        final File dirDirect = new File("./target/pt-jnl-direct-" + System.nanoTime());
        folders.add(dirA);
        folders.add(dirCopy);
        folders.add(dirDirect);

        final int ops = 30 + rnd.nextInt(60);
        final Random modelRnd = new Random(seed); // the SAME sequence model for both storages

        // ── the live session: the journals accumulate (no shutdown before the copy) ──
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("pt-jnl-a-" + System.nanoTime(), dirA.getPath(),
                64);
        final Map<String, String> model = runSequence(storage, modelRnd, ops);
        final long bytes = 1; // the marker (the real check: the journals are non-empty is NOT required —
                               // an empty journal means everything was checkpointed, the equivalence holds either way)
        copyTree(dirA, dirCopy);
        storage.shutdown(); // the clean checkpoint path

        // ── path 1: the copy-replay (the un-checkpointed tail restored by the replay) ──
        IXdFileStorage copyStorage = XdStorageProvider.newOrGetFileStorage("pt-jnl-copy-" + System.nanoTime(), dirCopy
                .getPath(), 64);
        final Map<String, String> copyState = readAll(copyStorage);
        copyStorage.shutdown();

        // ── path 2: the checkpointed original reopened ──
        IXdFileStorage reopened = XdStorageProvider.newOrGetFileStorage("pt-jnl-re-" + System.nanoTime(), dirA
                .getPath(), 64);
        final Map<String, String> checkpointed = readAll(reopened);
        reopened.shutdown();

        // ── path 3: the DIRECT baseline — the same sequence into a fresh storage, then the
        // clean shutdown (the checkpoint folds everything) ──
        IXdFileStorage direct = XdStorageProvider.newOrGetFileStorage("pt-jnl-direct-" + System.nanoTime(), dirDirect
                .getPath(), 64);
        final Random directRnd = new Random(seed);
        final Map<String, String> directModel = runSequence(direct, directRnd, ops);
        direct.shutdown();
        IXdFileStorage directReopened = XdStorageProvider.newOrGetFileStorage("pt-jnl-dre-" + System.nanoTime(),
                dirDirect.getPath(), 64);
        final Map<String, String> directState = readAll(directReopened);
        directReopened.shutdown();

        assertEqualsMaps(model, copyState, "the copy-replay diverges from the model");
        assertEqualsMaps(model, checkpointed, "the checkpointed original diverges from the model");
        assertEqualsMaps(model, directState, "the direct path diverges from the model");
        assertEqualsMaps(directModel, model, "the two sequence replays diverge from each other");
        if (bytes != 1) {
            throw new AssertionError("unreachable");
        }
    }
}
