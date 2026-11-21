package io.github.xdstorage.resource.cache;

import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.btree.XdStorageBTreeInvariants;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOSTUPD-2 (task 53): the COMMIT-TIME revalidation pins — the stage→commit window.
 *
 * <p>The stage-time first-committer-wins (the 2-arg canBeChangedByTransaction) fires
 * at the stage; this suite pins the SECOND net via the extracted revalidation hook
 * ({@link XdStorageBTreeInvariants#simulateCommitTimeRevalidation}): a foreign COMMIT
 * between this tx's read of the record and this tx's commit → the loud commit-time
 * conflict (the stale staged write rejected — the silent lost update impossible);
 * a foreign ABORTED stage → NO conflict (the no-over-rejection pin); the record
 * untouched → no conflict. The choreography mirrors the tested patterns of
 * XdStorageCacheRecordInvariantTest (markUpdate without lock, the 3-step commit).
 */
@DisplayName("LOSTUPD-2: commit-time revalidation of the staged writes")
public class XdStorageCommitTimeRevalidationTest extends CacheInvariantTestSupport {

    private StubCacheContext context;

    @BeforeEach
    void setUp() {
        context = new StubCacheContext();
    }

    private XdStorageCacheRecord readRecordOf(final TestEntity entity) {
        // The read registration: the newReadRecord + the read-moment capture (the same
        // sequence the cache read flow performs: newReadRecord → captureSnapshotIfNeeded →
        // stageWriteReadTimestamp)
        final XdStorageCacheRecord record = XdStorageCacheRecord.newReadRecord(context, context.idField(), entity);
        record.stageWriteReadTimestamp(System.nanoTime());
        return record;
    }

    @Test
    @DisplayName("чужой коммит между read и commit → фаза-1 бросает COMMIT-TIME CONFLICT (громко, данные чужие intact)")
    public void foreignCommitBetweenReadAndCommit_ReaderFailsLoudly() throws Exception {
        final TestEntity v1 = entity(1L, "v1");
        final XdStorageCacheRecord record = readRecordOf(v1);

        // The READER stages its update (the tx open, not committed)
        final XdStorageTransaction reader = mkTx("reader", 100L);
        record.markUpdate(reader);
        record.setNewObject(entity(1L, "reader-stale"));

        // The FOREIGN tx stages its own update and COMMITS first (the first-committer)
        final XdStorageTransaction foreign = mkTx("foreign", 200L);
        record.markUpdate(foreign);
        record.setNewObject(entity(1L, "foreign-fresh"));
        record.prepareCommit();
        record.performCommit();
        record.commit(foreign);
        assertEquals("foreign-fresh", ((TestEntity) record.getObject()).getValue(),
                "данные чужого коммита intact");

        // The READER's commit: the phase-1 revalidation → THE LOUD CONFLICT
        final XdStorageException conflict = assertThrows(XdStorageException.class,
                () -> XdStorageBTreeInvariants.simulateCommitTimeRevalidation(
                        record, reader, true, XdStorageObjectOperationType.Update,
                        record.getWriteReadTimestamp(), record.getDeleteReadTimestamp()),
                "чужой коммит между read и commit обязан ронять коммит читателя ГРОМКО");
        assertTrue(conflict.getMessage().contains("COMMIT-TIME CONFLICT"), "сигнатура конфликта");
        assertEquals("foreign-fresh", ((TestEntity) record.getObject()).getValue(),
                "после отказа данные остались чужими (fresh) — stale write не применён");
    }

    @Test
    @DisplayName("чужой ABORTED стейдж между read и commit → НЕТ конфликта (no-over-rejection)")
    public void abortedForeignStage_NoOverRejection() throws Exception {
        final TestEntity v1 = entity(2L, "v1");
        final XdStorageCacheRecord record = readRecordOf(v1);

        final XdStorageTransaction reader = mkTx("reader", 100L);
        record.markUpdate(reader);
        record.setNewObject(entity(2L, "reader-value"));

        // The foreign tx stages its update... then ABORTS (the rollback) — the record
        // restored to the reader's view; the commitTimeNano NOT bumped (no commit!)
        final XdStorageTransaction foreign = mkTx("foreign", 200L);
        record.markUpdate(foreign);
        record.setNewObject(entity(2L, "foreign-aborted"));
        record.rollback(foreign);

        // The READER's phase-1 revalidation: the commitTimeNano unchanged → NO conflict
        assertDoesNotThrow(() -> XdStorageBTreeInvariants.simulateCommitTimeRevalidation(
                        record, reader, true, XdStorageObjectOperationType.Update,
                        record.getWriteReadTimestamp(), record.getDeleteReadTimestamp()),
                "чужой ABORT не двигает commitTimeNano → over-rejection запрещён");
    }

    @Test
    @DisplayName("запись не тронута со времени read → НЕТ конфликта")
    public void untouchedRecord_NoConflict() throws Exception {
        final TestEntity v1 = entity(3L, "v1");
        final XdStorageCacheRecord record = readRecordOf(v1);

        final XdStorageTransaction reader = mkTx("reader", 100L);
        record.markUpdate(reader);
        record.setNewObject(entity(3L, "reader-value"));

        assertDoesNotThrow(() -> XdStorageBTreeInvariants.simulateCommitTimeRevalidation(
                        record, reader, true, XdStorageObjectOperationType.Update,
                        record.getWriteReadTimestamp(), record.getDeleteReadTimestamp()),
                "нет чужой активности → нет конфликта");
    }

    @Test
    @DisplayName("DELETE: чужой коммит между read и delete-commit → удаление проигрывает громко")
    public void deleteConflict_Loudly() throws Exception {
        final TestEntity v1 = entity(4L, "v1");
        final XdStorageCacheRecord record = readRecordOf(v1);

        // The DELETER stages its delete (the tx open)
        final XdStorageTransaction deleter = mkTx("deleter", 100L);
        record.stageDeleteReadTimestamp(System.nanoTime()); // the read moment (the mutator's set mirrored)
        record.markDelete(deleter);

        // The FOREIGN tx commits an UPDATE to the record after the deleter's read
        final XdStorageTransaction foreign = mkTx("foreign", 200L);
        record.markUpdate(foreign);
        record.setNewObject(entity(4L, "foreign-update"));
        record.prepareCommit();
        record.performCommit();
        record.commit(foreign);
        assertEquals("foreign-update", ((TestEntity) record.getObject()).getValue(),
                "данные чужого update intact");

        // The DELETER's commit: the stale delete → the loud conflict
        final XdStorageException conflict = assertThrows(XdStorageException.class,
                () -> XdStorageBTreeInvariants.simulateCommitTimeRevalidation(
                        record, deleter, true, XdStorageObjectOperationType.Delete,
                        record.getDeleteReadTimestamp(), record.getDeleteReadTimestamp()),
                "чужой update после read делетера → удаление проигрывает громко");
        assertTrue(conflict.getMessage().contains("COMMIT-TIME CONFLICT"), "сигнатура конфликта");
    }

    @Test
    @DisplayName("DELETE: чужой aborted стейдж → НЕТ конфликта (удаление проходит)")
    public void deleteAbortedForeign_NoConflict() throws Exception {
        final TestEntity v1 = entity(5L, "v1");
        final XdStorageCacheRecord record = readRecordOf(v1);

        final XdStorageTransaction deleter = mkTx("deleter", 100L);
        record.stageDeleteReadTimestamp(System.nanoTime());
        record.markDelete(deleter);

        // The foreign stages its update then ABORTS
        final XdStorageTransaction foreign = mkTx("foreign", 200L);
        record.markUpdate(foreign);
        record.setNewObject(entity(5L, "foreign-aborted"));
        record.rollback(foreign);

        assertDoesNotThrow(() -> XdStorageBTreeInvariants.simulateCommitTimeRevalidation(
                        record, deleter, true, XdStorageObjectOperationType.Delete,
                        record.getDeleteReadTimestamp(), record.getDeleteReadTimestamp()),
                "чужой abort не двигает commitTimeNano → удаление проходит");
    }
}
