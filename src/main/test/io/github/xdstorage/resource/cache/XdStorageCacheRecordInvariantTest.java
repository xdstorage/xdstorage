package io.github.xdstorage.resource.cache;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.XdStorageIsolationLevel;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Инварианты государственной машины {@link XdStorageCacheRecord}: фабрики записей,
 * переходы mark/commit/rollback, матрица видимости resolveVisible (SCAN/BY_ID/WRITE_OUT
 * — консолидированный MVCC-переключатель шести сайтов чтения), record-latch и
 * политика canBeChangedByTransaction.
 *
 * МОДЕЛЬ ВЛАДЕНИЯ (важно для сценариев): owner записи назначается lock() либо фабрикой
 * insert-записи; markUpdate/markDelete НЕ назначают владельца — в реальном контуре
 * update() сначала захватывает latch (владелец), затем метит мутацию.
 */
class XdStorageCacheRecordInvariantTest extends CacheInvariantTestSupport {

    private StubCacheContext context;

    @BeforeEach
    void setUp() {
        context = new StubCacheContext();
    }

    // =========================================================================
    // ФАБРИКИ
    // =========================================================================

    @Test
    void readRecordStartsInCleanReadState() {
        TestEntity e = entity(1L, "a");
        XdStorageCacheRecord record = XdStorageCacheRecord.newReadRecord(context, context.idField(), e);

        assertEquals(1L, record.getId());
        assertSame(e, record.getObject());
        assertNull(record.getNewObject());
        assertNull(record.getOwnerTransaction());
        assertNull(record.getOwnerTransactionId());
        assertTrue(record.isReadChange());
        assertFalse(record.isInsertChange());
        assertFalse(record.isUpdateChange());
        assertFalse(record.isDeleteChange());
        assertFalse(record.isCommitedState());
        assertEquals("undefined", record.getState());
        assertEquals(0L, record.getVersion());
    }

    @Test
    void insertRecordHoldsContentInNewObjectUnderLock() {
        TestEntity e = entity(7L, "v");
        XdStorageTransaction tx = mkTx("t1", 100L);
        XdStorageCacheRecord record = XdStorageCacheRecord.newInsertRecord(context, context.idField(), e, tx);

        assertEquals(7L, record.getId());
        assertNull(record.getObject(), "object обязан быть null до коммита вставки");
        assertSame(e, record.getNewObject());
        assertSame(tx, record.getOwnerTransaction());
        assertTrue(record.isInsertChange());
        assertFalse(record.isCommitedState());
        assertEquals("locked", record.getState());
        assertEquals(100L, record.getRecordTimestamp());
        assertEquals(0L, record.getVersion());
    }

    // =========================================================================
    // ПЕРЕХОДЫ MARK / COMMIT / ROLLBACK
    // =========================================================================

    @Test
    void markUpdateKeepsCommittedObjectUntilCommit() {
        TestEntity v1 = entity(1L, "v1");
        XdStorageCacheRecord record = readRecordOf(v1);

        record.markUpdate(mkTx("t1", 100L));

        assertEquals(1L, record.getVersion());
        assertTrue(record.isUpdateChange());
        assertEquals("locked", record.getState());
        assertEquals(100L, record.getRecordTimestamp());
        assertSame(v1, record.getObject(), "markUpdate не подменяет закоммиченную версию — она уходит в prevObject");
        assertNull(record.getNewObject(), "контент новой версии ставит вызывающий (setNewObject)");
    }

    @Test
    void repeatedMarksKeepTimestampGridRestorableByRollback() {
        TestEntity v1 = entity(1L, "v1");
        XdStorageCacheRecord record = readRecordOf(v1);

        record.markUpdate(mkTx("t1", 100L)); // холодная запись: prevTimestamp остается MIN_VALUE
        record.setNewObject(entity(1L, "v2"));
        record.markUpdate(mkTx("t2", 200L)); // реальная история: prevTimestamp = 100
        assertEquals(2L, record.getVersion());
        assertSame(v1, record.getObject());

        record.rollback();

        assertEquals(100L, record.getRecordTimestamp(),
                "СУБД ТАЙМСТАМП-БАРЬЕР: откат возвращается к ПРЕДЫДУЩЕМУ реальному времени, а не к MIN_VALUE");
        assertSame(v1, record.getObject(), "mark-операции не подменяют закоммиченную версию");
        assertNull(record.getNewObject());
        assertEquals(2L, record.getVersion(), "rollback не двигает версию записи");
        assertTrue(record.isUpdateChange(), "rollback снимает ОДИН уровень истории (откат к состоянию после mark1)");
    }

    @Test
    void commitRequiresBothPhases() {
        XdStorageCacheRecord record = readRecordOf(entity(1L, "v1"));
        record.markUpdate(mkTx("t1", 100L));
        XdStorageTransaction tx = withCommitSerial(mkTx("t2", 200L), 42L);

        XdStorageRuntimeException noPhases = assertThrows(XdStorageRuntimeException.class, () -> record.commit(tx));
        assertTrue(noPhases.getMessage().contains("Illegal state of record to commit"),
                "коммит без фаз запрещен — 'Illegal state of record to commit'");

        record.prepareCommit();
        assertThrows(XdStorageRuntimeException.class, () -> record.commit(tx),
                "только первая фаза — коммит по-прежнему запрещен");
    }

    @Test
    void commitMakesNewObjectPermanentAndStampsCommitSerial() throws XdStorageException {
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(1L, "v2");
        XdStorageCacheRecord record = readRecordOf(v1);
        XdStorageTransaction tx = withCommitSerial(mkTx("t1", 100L), 42L);
        record.lock(tx); // владелец назначается lock'ом (как в реальном контуре update)

        record.markUpdate(tx);
        record.setNewObject(v2);
        record.prepareCommit();
        record.performCommit();
        record.commit(tx);

        assertSame(v2, record.getObject());
        assertNull(record.getNewObject());
        assertTrue(record.isCommitedState());
        assertEquals("committed", record.getState());
        assertEquals(100L, record.getRecordTimestamp());
        assertEquals(2L, record.getVersion());
        assertFalse(record.isUpdateChange());
        assertFalse(record.isInsertChange());
        assertFalse(record.isDeleteChange());
        assertFalse(record.isReadChange());
        assertSame(tx, record.getOwnerTransaction(), "owner не вычищается commit'ом — это делает unlock");
    }

    @Test
    void commitWithoutTxSerialFallsBackToGlobalCommitCounter() {
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(1L, "v2");
        XdStorageCacheRecord record = readRecordOf(v1);
        XdStorageTransaction tx = mkTx("t1", 100L); // commit serial = 0 (sentinel мока)

        record.markUpdate(tx);
        record.setNewObject(v2);
        record.prepareCommit();
        record.performCommit();
        record.commit(tx);

        // Глобальный счетчик стартует с 1 и инкрементится на каждый коммит, поэтому
        // серий этой версии >= 2: читатель с serial=1 обязан видеть prevObject...
        XdStorageTransaction earlyReader = withSnapshotSerial(mkTx("early", 50L), 1L);
        assertSame(v1, record.resolveVisible(earlyReader, XdStorageCacheRecord.View.SCAN, 0L),
                "ранний снапшот-читатель видит предыдущую версию");
        // ...а читатель после коммита (timestart позже) — новую версию
        XdStorageTransaction lateReader = withSnapshotSerial(mkTx("late", 150L), Long.MAX_VALUE / 2);
        assertSame(v2, record.resolveVisible(lateReader, XdStorageCacheRecord.View.SCAN, 0L));
    }

    @Test
    void rollbackRestoresPriorCommittedState() {
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(1L, "v2");
        XdStorageCacheRecord record = readRecordOf(v1);
        XdStorageTransaction committer = withCommitSerial(mkTx("t1", 100L), 42L);
        record.markUpdate(committer);
        record.setNewObject(v2);
        record.prepareCommit();
        record.performCommit();
        record.commit(committer);

        record.markUpdate(mkTx("t2", 200L));
        long versionAfterMark = record.getVersion();
        record.setNewObject(entity(1L, "v3"));
        record.rollback();

        assertSame(v2, record.getObject(), "откат возвращает последнюю закоммиченную версию");
        assertNull(record.getNewObject());
        assertTrue(record.isCommitedState(), "состояние возвращается к committed");
        assertEquals(100L, record.getRecordTimestamp());
        assertEquals(versionAfterMark, record.getVersion(),
                "rollback не двигает версию (version++ делают только mark/commit)");
        assertFalse(record.isUpdateChange());
        assertFalse(record.isReadChange());
    }

    @Test
    void coldStartRollbackRestoresCleanRead() {
        TestEntity v1 = entity(1L, "v1");
        XdStorageCacheRecord record = readRecordOf(v1);

        record.markUpdate(mkTx("t1", 100L));
        record.setNewObject(entity(1L, "v2"));
        record.rollback();

        assertSame(v1, record.getObject());
        assertNull(record.getNewObject());
        assertTrue(record.isReadChange());
        assertEquals("undefined", record.getState());
        assertEquals(Long.MIN_VALUE, record.getRecordTimestamp(), "холодная запись возвращается к MIN_VALUE");
        assertNull(record.getOwnerTransaction());
    }

    @Test
    void rollbackInsertKeepServingReturnsRecordToLiveReadState() {
        TestEntity live = entity(1L, "live");
        XdStorageTransaction tx = mkTx("t1", 100L);
        XdStorageCacheRecord record = XdStorageCacheRecord.newInsertRecord(context, context.idField(), live, tx);
        record.prepareCommit();

        record.rollbackInsertKeepServing();

        assertSame(live, record.getObject());
        assertNull(record.getNewObject());
        assertTrue(record.isReadChange());
        assertEquals("undefined", record.getState());
        assertNull(record.getOwnerTransaction());
        assertEquals(Long.MIN_VALUE, record.getRecordTimestamp());
    }

    @Test
    void isChangedByTransactionRequiresIdentityAndTimestartMatch() {
        XdStorageTransaction owner = mkTx("t1", 100L);
        XdStorageCacheRecord record = XdStorageCacheRecord.newInsertRecord(context, context.idField(), entity(1L, "v"), owner);

        assertTrue(record.isChangedByTransaction(owner));
        assertFalse(record.isChangedByTransaction(mkTx("t1", 100L)),
                "чужая транзакция с тем же id — НЕ владелец (идентичность по ссылке)");
        assertFalse(record.isChangedByTransaction(mkTx("other", 100L)));

        XdStorageCacheRecord readRecord = readRecordOf(entity(2L, "r"));
        assertFalse(readRecord.isChangedByTransaction(mkTx("t9", 100L)),
                "read-запись никогда не 'изменена транзакцией'");
    }

    // =========================================================================
    // МАТРИЦА ВИДИМОСТИ resolveVisible (SNAPSHOT)
    // =========================================================================

    @Test
    void scanVisibilityMatrixForUncommittedMutations() throws XdStorageException {
        XdStorageTransaction reader = mkTx("r", 50L);

        // ЧУЖОЕ незакоммиченное обновление: скан видит последнюю закоммиченную (object)
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(1L, "v2");
        XdStorageCacheRecord updated = readRecordOf(v1);
        XdStorageTransaction writer = mkTx("w", 100L);
        updated.lock(writer); // владелец = writer: без lock isChangedByTransaction не сработает
        updated.markUpdate(writer);
        updated.setNewObject(v2);
        assertSame(v1, updated.resolveVisible(reader, XdStorageCacheRecord.View.SCAN, 0L),
                "SCAN: чужое незакоммиченное обновление -> закоммиченная версия");
        assertSame(v2, updated.resolveVisible(writer, XdStorageCacheRecord.View.SCAN, 0L),
                "SCAN: свое незакоммиченное обновление -> newObject");

        // НЕЗАКОММИЧЕННАЯ вставка: чужой читатель не видит, свой видит (владелец от фабрики)
        TestEntity insertedEntity = entity(2L, "new");
        XdStorageTransaction inserter = mkTx("i", 100L);
        XdStorageCacheRecord inserted = XdStorageCacheRecord.newInsertRecord(context, context.idField(), insertedEntity, inserter);
        assertSame(insertedEntity, inserted.resolveVisible(inserter, XdStorageCacheRecord.View.SCAN, 0L));
        assertNull(inserted.resolveVisible(reader, XdStorageCacheRecord.View.SCAN, 0L),
                "SCAN: чужая незакоммиченная вставка невидима");

        // НЕЗАКОММИЧЕННОЕ удаление: чужой видит объект, свой — нет
        TestEntity v3 = entity(3L, "v3");
        XdStorageCacheRecord deleted = readRecordOf(v3);
        XdStorageTransaction deleter = mkTx("d", 100L);
        deleted.lock(deleter);
        deleted.markDelete(deleter);
        assertSame(v3, deleted.resolveVisible(reader, XdStorageCacheRecord.View.SCAN, 0L),
                "SCAN: чужое незакоммиченное удаление -> объект еще виден");
        assertNull(deleted.resolveVisible(deleter, XdStorageCacheRecord.View.SCAN, 0L),
                "SCAN: свое удаление -> объект скрыт");
    }

    @Test
    void committedDeleteServesPreimageBySnapshotGate() {
        TestEntity v1 = entity(1L, "v1");
        XdStorageCacheRecord record = readRecordOf(v1);
        XdStorageTransaction deleter = withCommitSerial(mkTx("d", 100L), 9L);
        record.markDelete(deleter);
        record.prepareCommit();
        record.performCommit();
        record.commit(deleter);

        assertSame(v1, record.resolveVisible(mkTx("before", 50L), XdStorageCacheRecord.View.SCAN, 0L),
                "SNAPSHOT: читатель с timestart раньше коммита удаления видит prevObject");
        assertNull(record.resolveVisible(mkTx("after", 200L), XdStorageCacheRecord.View.SCAN, 0L),
                "читатель после коммита удаления не видит объект");
    }

    @Test
    void byIdCommittingSerialBarrierServesNewStateAtomically() throws XdStorageException {
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(1L, "v2");
        XdStorageCacheRecord record = readRecordOf(v1);
        XdStorageTransaction committer = withCommitSerial(mkTx("c", 100L), 5L);
        record.lock(committer); // владелец = committer: запись "чужая" для всех остальных
        record.markUpdate(committer);
        record.setNewObject(v2);
        record.prepareCommit(); // фаза-1 выполнена, фаза-2 еще НЕТ

        XdStorageTransaction insideSnapshot = withSnapshotSerial(mkTx("inside", 50L), 10L);
        assertEquals(5L, record.committingSerialVisibleTo(insideSnapshot),
                "снапшот читателя >= serial коммиттера -> барьер = serial");
        assertSame(v2, record.resolveVisible(insideSnapshot, XdStorageCacheRecord.View.BY_ID,
                        record.committingSerialVisibleTo(insideSnapshot)),
                "R-45: читатель, чей снапшот включает commit-serial, видит НОВОЕ состояние до фазы-2");

        assertSame(v1, record.resolveVisible(withSnapshotSerial(mkTx("early", 50L), 3L), XdStorageCacheRecord.View.BY_ID, 0L),
                "читатель до serial: владелец жив, отказ -> последняя закоммиченная");

        assertSame(v1, record.resolveVisible(insideSnapshot, XdStorageCacheRecord.View.SCAN, 0L),
                "барьер действует ТОЛЬКО в BY_ID: скан того же читателя видит старую версию");
    }

    @Test
    void byIdInsertCommittingSerialBarrierIsScanExclusive() {
        TestEntity insertedEntity = entity(2L, "new");
        XdStorageTransaction committer = withCommitSerial(mkTx("c", 100L), 5L);
        XdStorageCacheRecord inserted = XdStorageCacheRecord.newInsertRecord(context, context.idField(), insertedEntity, committer);
        inserted.prepareCommit();

        XdStorageTransaction reader = withSnapshotSerial(mkTx("inside", 50L), 10L);
        assertSame(insertedEntity, inserted.resolveVisible(reader, XdStorageCacheRecord.View.BY_ID, 5L),
                "BY_ID: барьер открывает чужую вставку, вошедшую в коммит");
        assertNull(inserted.resolveVisible(reader, XdStorageCacheRecord.View.SCAN, 0L),
                "SCAN: та же вставка по-прежнему невидима");
    }

    @Test
    void writeOutServesLatestCommittedIgnoringSnapshotRouting() {
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(1L, "v2");
        XdStorageCacheRecord record = readRecordOf(v1);
        XdStorageTransaction committer = withCommitSerial(mkTx("c", 100L), 9L);
        record.markUpdate(committer);
        record.setNewObject(v2);
        record.prepareCommit();
        record.performCommit();
        record.commit(committer);

        XdStorageTransaction olderReader = mkTx("old", 50L);
        assertSame(v1, record.resolveVisible(olderReader, XdStorageCacheRecord.View.SCAN, 0L),
                "SCAN: снапшотное роутирование дает предыдущую версию");
        assertSame(v2, record.resolveVisible(olderReader, XdStorageCacheRecord.View.WRITE_OUT, 0L),
                "R-15: выписка на диск берет последнее закоммиченное — иначе ранний коммиттер записал бы файл соседа его старой версией");
    }

    @Test
    void readCommittedTurnsOffSerialAndTimestartGates() {
        TestEntity v1 = entity(1L, "v1");
        TestEntity v2 = entity(1L, "v2");
        XdStorageCacheRecord updated = readRecordOf(v1);
        XdStorageTransaction committer = withCommitSerial(mkTx("c", 100L), 5L);
        updated.markUpdate(committer);
        updated.setNewObject(v2);
        updated.prepareCommit();
        updated.performCommit();
        updated.commit(committer);

        // Читатель с реальным снапшот-номером РАНЬШЕ коммита, но timestart ПОЗЖЕ версии:
        XdStorageTransaction reader = withSnapshotSerial(mkTx("r", 150L), 3L);
        assertSame(v1, updated.resolveVisible(reader, XdStorageCacheRecord.View.SCAN, 0L),
                "SNAPSHOT: serial-гейт видит prevObject (читатель зафиксировал снапшот до коммита)");

        context.isolationLevel = XdStorageIsolationLevel.READ_COMMITTED;
        assertSame(v2, updated.resolveVisible(reader, XdStorageCacheRecord.View.SCAN, 0L),
                "READ_COMMITTED: serial-гейт выключен — последнее закоммиченное состояние");

        XdStorageCacheRecord deleted = readRecordOf(entity(2L, "v3"));
        XdStorageTransaction deleter = withCommitSerial(mkTx("d", 100L), 10L);
        deleted.markDelete(deleter);
        deleted.prepareCommit();
        deleted.performCommit();
        deleted.commit(deleter);

        assertNull(deleted.resolveVisible(mkTx("old", 50L), XdStorageCacheRecord.View.SCAN, 0L),
                "READ_COMMITTED: timestart-гейт удалений выключен — закоммиченное удаление видно сразу");
    }

    // =========================================================================
    // RECORD-LATCH
    // =========================================================================

    @Test
    void latchBlocksForeignTxUntilTimeout() throws Exception {
        XdStorageCacheRecord record = readRecordOf(entity(1L, "v1"));
        XdStorageTransaction holder = mkTx("holder", 100L);
        record.lock(holder);

        long start = System.nanoTime();
        XdStorageTransaction contender = mkTx("contender", 200L, 200L);
        XdStorageException timeout = assertThrows(XdStorageException.class, () -> record.lock(contender),
                "чужая транзакция не может захватить занятый latch и завершается по бюджету");
        assertTrue(timeout.getMessage().contains("locked for"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 150L, "ожидание обязано длиться близко к бюджету транзакции, было: " + elapsedMs + " мс");
        assertTrue(elapsedMs <= 1000L, "и не превышать его многократно, было: " + elapsedMs + " мс");

        record.unlock(holder);
        record.lock(contender);
        assertSame(contender, record.getOwnerTransaction(), "после освобождения latch достается конкуренту");
    }

    @Test
    void deadOwnerRecordIsAdoptedImmediately() throws XdStorageException {
        XdStorageCacheRecord record = readRecordOf(entity(1L, "v1"));
        XdStorageTransaction dead = mkTx("dead", 100L);
        record.lock(dead);

        context.deadTransactions.add(dead);

        XdStorageTransaction adopter = mkTx("adopter", 200L);
        record.lock(adopter);
        assertSame(adopter, record.getOwnerTransaction(),
                "контур мертвых владельцев: latch переходит живой транзакции без ожидания");
    }

    @Test
    void unlockByNonOwnerThrows() throws XdStorageException {
        XdStorageCacheRecord record = readRecordOf(entity(1L, "v1"));
        XdStorageTransaction holder = mkTx("holder", 100L);
        record.lock(holder);

        XdStorageException e = assertThrows(XdStorageException.class, () -> record.unlock(mkTx("other", 200L)));
        assertTrue(e.getMessage().contains("trying to release locking object"));

        record.unlock(holder);
        assertNull(record.getOwnerTransaction());
    }

    @Test
    void reentrantLockBySameOwnerNeedsSingleUnlock() throws XdStorageException {
        XdStorageCacheRecord record = readRecordOf(entity(1L, "v1"));
        XdStorageTransaction holder = mkTx("holder", 100L);

        record.lock(holder);
        record.lock(holder);
        record.unlock(holder);

        assertNull(record.getOwnerTransaction(), "повторный lock той же транзакцией не требует второго unlock");
    }

    @Test
    void infrastructureRecordsSkipTheLatch() throws XdStorageException {
        context.infrastructureCache = true;
        XdStorageCacheRecord record = readRecordOf(entity(1L, "v1"));
        XdStorageTransaction first = mkTx("first", 100L);
        XdStorageTransaction second = mkTx("second", 200L);

        record.lock(first);
        record.lock(second);
        assertSame(first, record.getOwnerTransaction(),
                "R-27: инфра-записи не дают latch — чужой lock не блокируется и не отнимает владельца");

        record.unlock(first);
        assertNull(record.getOwnerTransaction());

        record.lock(second);
        assertSame(second, record.getOwnerTransaction());
    }

    // =========================================================================
    // ПОЛИТИКА canBeChangedByTransaction
    // =========================================================================

    @Test
    void canBeChangedMatrix() throws XdStorageException {
        XdStorageCacheRecord readRecord = readRecordOf(entity(1L, "v1"));
        assertTrue(readRecord.canBeChangedByTransaction(mkTx("any", 1L)),
                "undefined-запись меняется кем угодно");

        // locked живым чужим владельцем — отказ
        XdStorageTransaction owner = mkTx("owner", 100L);
        XdStorageCacheRecord locked = XdStorageCacheRecord.newInsertRecord(context, context.idField(), entity(2L, "v2"), owner);
        assertFalse(locked.canBeChangedByTransaction(mkTx("foreign", 200L)));
        assertTrue(locked.canBeChangedByTransaction(owner));

        // цепочка вложенности: parent владеет — child (и grandchild) может
        XdStorageTransaction child = withParent(mkTx("child", 300L), owner);
        XdStorageTransaction grandchild = withParent(mkTx("grandchild", 300L), child);
        assertTrue(locked.canBeChangedByTransaction(child),
                "R-40: nested-транзакция — тот же логический писатель, что и родитель");
        assertTrue(locked.canBeChangedByTransaction(grandchild));
        assertFalse(locked.canBeChangedByTransaction(mkTx("stranger", 300L)));

        // мертвый владелец — наследование
        context.deadTransactions.add(owner);
        assertTrue(locked.canBeChangedByTransaction(mkTx("adopter", 400L)),
                "мертвый владелец не держит запись");

        // committed с живым владельцем: читатель с более поздним timestart может, более ранний — нет
        XdStorageCacheRecord committed = readRecordOf(entity(3L, "v3"));
        XdStorageTransaction committer = mkTx("c", 100L);
        committed.lock(committer);
        committed.markUpdate(committer);
        committed.prepareCommit();
        committed.performCommit();
        // LOSTUPD (docs/tasks/2.1.0/40): коммит метит версию ИСТИННЫМ моментом коммита
        // (commitTimeNano) — читатель, прочитавший ДО чужого коммита, запись обновить
        // не может (первый коммитёр выигрывает); прочитавший ПОСЛЕ — может. Момент
        // коммита фиксируем часами вокруг вызова commit().
        final long beforeCommitNano = System.nanoTime();
        committed.commit(committer);
        final long afterCommitNano = System.nanoTime();
        assertTrue(committed.canBeChangedByTransaction(mkTx("late", 200L)));
        assertFalse(committed.canBeChangedByTransaction(mkTx("early", 50L)));

        // двухаргументная форма: readTimestamp решает вместо timestart читателя —
        // и сравнивается с истинным моментом коммита версии (см. LOSTUPD выше)
        assertTrue(committed.canBeChangedByTransaction(mkTx("late-read", 1L), afterCommitNano));
        assertFalse(committed.canBeChangedByTransaction(mkTx("early-read", 999L), beforeCommitNano));
    }

    // =========================================================================
    // ПОМОЩНИКИ
    // =========================================================================

    private XdStorageCacheRecord readRecordOf(final TestEntity entity) {
        return XdStorageCacheRecord.newReadRecord(context, context.idField(), entity);
    }
}
