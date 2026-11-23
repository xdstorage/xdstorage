package io.github.xdstorage.properties;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PROPTEST P1.1 (docs/tasks/2.2.0/03): the CRUD round-trip property across ALL THREE
 * policies — save → load must restore EVERY field of a seed-generated entity; the
 * duplicate-id insert must refuse cross-transaction; a missing id loads as null.
 *
 * <p>The seed-model discipline: the entity AND its expected content are pure functions
 * of the seed — a failing seed reproduces exactly, jqwik shrinks the seed.
 */
public class PropCrudRoundTripTest {

    private final List<File> folders = new ArrayList<>();

    /** Each try gets a fresh folder — cleaned up after the try. */
    private File folder(final String tag) {
        final File dir = new File("./target/pt-crud-" + tag + "-" + System.nanoTime());
        folders.add(dir);
        return dir;
    }

    private IXdFileStorage openIn(final File dir, final String tag) {
        return XdStorageProvider.newOrGetFileStorage("pt-" + tag + "-" + System.nanoTime(), dir.getPath(), 64);
    }

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

    // ───────────────────────── the arm descriptors ─────────────────────────

    private static final String[] ARMS = {"class-hash", "class-btree", "single"};

    private static PropWideLike entityFor(final int arm, final int seed) {
        switch (arm) {
            case 0:
                return PropModel.wide(seed);
            case 1:
                return PropModel.wideBTree(seed);
            default:
                return PropModel.single(seed);
        }
    }

    private static Class<? extends PropWideLike> classFor(final int arm) {
        switch (arm) {
            case 0:
                return PropWide.class;
            case 1:
                return PropWideBTree.class;
            default:
                return PropSingle.class;
        }
    }

    // ───────────────────────── the properties ─────────────────────────

    @Property(tries = 60)
    void crudRoundTrip_RestoresEveryField_OnEveryPolicy(@ForAll final int seed,
                                                       @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 2) final int arm) throws XdStorageException, XdStorageConnectionException {
        final PropWideLike original = entityFor(arm, seed);
        final Class<? extends PropWideLike> cl = classFor(arm);
        final File dir = folder(ARMS[arm]);
        final IXdFileStorage storage = openIn(dir, ARMS[arm]);
        try {
            // save → commit → load → field-by-field
            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(original, tx);
            storage.commitTransaction(tx);

            final IXdStorageTransaction readTx = storage.beginTransaction();
            final PropWideLike loaded = storage.load(cl, original.getId(), readTx);
            assertNotNull(loaded, "the saved entity must load by id");
            PropModel.assertFieldEquals(original, loaded, ARMS[arm] + " round-trip");
            storage.commitTransaction(readTx);
        } finally {
            storage.shutdown();
        }

        // reopen: the state survives the restart (the persistence invariant) — THE SAME folder
        final IXdFileStorage reopened = openIn(dir, ARMS[arm] + "-reopen");
        try {
            final IXdStorageTransaction readTx = reopened.beginTransaction();
            final PropWideLike loaded = reopened.load(cl, original.getId(), readTx);
            assertNotNull(loaded, "the entity must survive the reopen");
            PropModel.assertFieldEquals(original, loaded, ARMS[arm] + " reopen");
            reopened.commitTransaction(readTx);
        } finally {
            reopened.shutdown();
        }
    }

    @Property(tries = 30)
    void duplicateIdInsert_RefusesLoudly_CrossTransaction(@ForAll final int seed,
                                                         @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 2) final int arm) throws XdStorageException, XdStorageConnectionException {
        final PropWideLike original = entityFor(arm, seed);
        final Class<? extends PropWideLike> cl = classFor(arm);
        final File dir = folder(ARMS[arm] + "-dup");
        final IXdFileStorage storage = openIn(dir, ARMS[arm] + "-dup");
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(original, tx);
            storage.commitTransaction(tx);

            // the same id in a NEW transaction: save is an INSERT — the duplicate refuses loudly
            final PropWideLike duplicate = entityFor(arm, seed);
            duplicate.setName(duplicate.getName() + "-changed");
            final IXdStorageTransaction dupTx = storage.beginTransaction();
            assertThrows(XdStorageException.class, () -> storage.save(duplicate, dupTx),
                    "the duplicate-id insert must refuse cross-transaction");
            storage.rollbackTransaction(dupTx);

            // the stored content is untouched by the refused duplicate
            final IXdStorageTransaction checkTx = storage.beginTransaction();
            final PropWideLike stored = storage.load(cl, original.getId(), checkTx);
            assertNotNull(stored);
            PropModel.assertFieldEquals(original, stored, ARMS[arm] + " after the refused duplicate");
            storage.commitTransaction(checkTx);
        } finally {
            storage.shutdown();
        }
    }

    @Property(tries = 20)
    void missingId_LoadsAsNull_AndUpdateOfMissingRefuses(@ForAll final int seed,
                                                         @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 2) final int arm) throws XdStorageException, XdStorageConnectionException {
        final Class<? extends PropWideLike> cl = classFor(arm);
        final File dir = folder(ARMS[arm] + "-miss");
        final IXdFileStorage storage = openIn(dir, ARMS[arm] + "-miss");
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            assertNull(storage.load(cl, "no-such-" + seed, tx), "a missing id loads as null");
            storage.commitTransaction(tx);
        } finally {
            storage.shutdown();
        }
    }

    @Property(tries = 20)
    void update_TransfersChangedContent_RoundTrip(@ForAll final int seed,
                                                  @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 2) final int arm) throws XdStorageException, XdStorageConnectionException {
        final PropWideLike v1 = entityFor(arm, seed);
        final PropWideLike v2 = entityFor(arm, seed + 100_000); // the different deterministic content
        final Class<? extends PropWideLike> cl = classFor(arm);
        final File dir = folder(ARMS[arm] + "-upd");
        final IXdFileStorage storage = openIn(dir, ARMS[arm] + "-upd");
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(v1, tx);
            storage.commitTransaction(tx);

            // update to v2's content (the same id!)
            final IXdStorageTransaction updTx = storage.beginTransaction();
            final PropWideLike loaded = storage.load(cl, v1.getId(), updTx);
            loaded.setName(v2.getName());
            loaded.setAmount(v2.getAmount());
            loaded.setCount(v2.getCount());
            loaded.setRatio(v2.getRatio());
            loaded.setActive(v2.getActive());
            loaded.setCreated(v2.getCreated());
            loaded.setDecimal(v2.getDecimal());
            loaded.setTags(v2.getTags());
            loaded.setMeta(v2.getMeta());
            loaded.setLabels(v2.getLabels());
            loaded.setNested(v2.getNested());
            storage.update(loaded, updTx);
            storage.commitTransaction(updTx);

            final IXdStorageTransaction checkTx = storage.beginTransaction();
            final PropWideLike after = storage.load(cl, v1.getId(), checkTx);
            assertNotNull(after);
            assertEquals(v1.getId(), after.getId(), "the id must not change on update");
            PropModel.assertFieldEquals(loaded, after, ARMS[arm] + " update round-trip");
            storage.commitTransaction(checkTx);
        } finally {
            storage.shutdown();
        }
    }
}