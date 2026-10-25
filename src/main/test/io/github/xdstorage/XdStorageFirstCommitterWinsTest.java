package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.XdStorageIndexType;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOSTUPD (docs/tasks/2.1.0/40) — the first-committer-wins regression test.
 *
 * <p>The proven defect: a transaction that READ a record before another transaction's
 * commit could write its stale version after that commit — the write passed the conflict
 * check (which compared the read moment against the conflicting writer's START time, not
 * its commit time) and the stale state became durable. Observable end state in the field:
 * a committed parent collection resurrecting a reference whose target was committed-deleted
 * in between ("cannot load by reference" at the next indexed read).
 *
 * <p>The contract pinned here: with the writer's commit between the reader's read and the
 * reader's write, the reader's update is REJECTED loudly, the writer's version stays
 * stored, and a transaction that reads after the commit can still update normally (no
 * over-rejection).
 */
public class XdStorageFirstCommitterWinsTest {

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    public static class FcwEntity {
        @XdStorageObjectId
        private Long id;
        private String value;

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }
    }

    private static final String STORAGE_NAME = "fcw-test-" + System.nanoTime();
    private static final String STORAGE_DIR = "./target/fcw-db-" + System.nanoTime();

    private IXdFileStorage storage;

    @BeforeEach
    public void openStorage() {
        deleteDir(new File(STORAGE_DIR));
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 200);
    }

    @AfterEach
    public void closeStorage() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
        deleteDir(new File(STORAGE_DIR));
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }

    private FcwEntity insertOne(final String value) throws Exception {
        final FcwEntity entity = new FcwEntity();
        entity.setValue(value);
        final IXdStorageTransaction tx = storage.beginTransaction(15000L);
        try {
            storage.save(entity, tx);
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
        return entity;
    }

    @Test
    public void staleWriteAfterForeignCommitIsRejected() throws Exception {
        final FcwEntity entity = insertOne("v0");
        final Long id = entity.getId();

        // The writer begins first, the reader second — the reader's read happens inside
        // the writer's lifetime (before the writer's commit).
        final IXdStorageTransaction writer = storage.beginTransaction(15000L);
        final IXdStorageTransaction reader = storage.beginTransaction(15000L);

        final FcwEntity writersCopy = storage.load(FcwEntity.class, id, writer);
        final FcwEntity readersCopy = storage.load(FcwEntity.class, id, reader);
        assertNotNull(writersCopy);
        assertNotNull(readersCopy);

        // The writer commits first: its version becomes the stored state.
        writersCopy.setValue("written");
        storage.update(writersCopy, writer);
        writer.commit();

        // The reader's write carries the pre-commit state — it must be REJECTED
        // (first-committer-wins), never silently committed over the writer's version.
        readersCopy.setValue("stale");
        assertThrows(XdStorageException.class, () -> storage.update(readersCopy, reader),
                "a read older than a foreign commit must not be writable (first-committer-wins)");
        reader.rollback();

        // The writer's version survived; the loser's stale state is nowhere.
        final IXdStorageTransaction check = storage.beginTransaction(15000L);
        final FcwEntity stored = storage.load(FcwEntity.class, id, check);
        check.commit();
        assertNotNull(stored);
        assertEquals("written", stored.getValue());
    }

    @Test
    public void readerAfterCommitCanStillUpdate() throws Exception {
        final FcwEntity entity = insertOne("v0");
        final Long id = entity.getId();

        // A transaction that reads AFTER the foreign commit is not stale — its update
        // must go through (guards against over-rejection of the first-committer-wins fix).
        final IXdStorageTransaction late = storage.beginTransaction(15000L);
        final FcwEntity lateCopy = storage.load(FcwEntity.class, id, late);
        lateCopy.setValue("late");
        storage.update(lateCopy, late);
        late.commit();

        final IXdStorageTransaction check = storage.beginTransaction(15000L);
        final FcwEntity stored = storage.load(FcwEntity.class, id, check);
        check.commit();
        assertNotNull(stored);
        assertEquals("late", stored.getValue());
    }
}
