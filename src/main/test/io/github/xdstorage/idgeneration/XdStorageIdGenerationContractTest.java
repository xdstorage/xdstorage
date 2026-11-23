package io.github.xdstorage.idgeneration;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-3a (docs/tasks/2.1.0/06): the id-generation contracts, through the public facade.
 * Pins: per-class Hi-Lo sequences starting at 1; explicit ids are respected; the batch
 * boundary refetches (id 101 works after the first 100-batch is consumed); the burn
 * contract — a reopen discards the unused remainder of the current batch, so a crash
 * costs at most PART-1 (≤99) ids, and the counter never reissues an id; String ids get
 * UUIDs (explicit property and by-type default).
 */
@DisplayName("T-3a: id generation contracts (Hi-Lo batches, UUID, burn on reopen)")
public class XdStorageIdGenerationContractTest {

    @TempDir
    Path tempDir;

    private IXdFileStorage storage;
    private String lastName;

    private IXdFileStorage open() {
        lastName = "idgen-" + System.nanoTime();
        storage = XdStorageProvider.newOrGetFileStorage(lastName, tempDir.resolve(lastName).toString(), 512);
        return storage;
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
    }

    @Test
    @DisplayName("Long ids: per-class sequence 1,2,3…; explicit ids respected; counters are per entity class")
    public void longSequenceStartsAtOneAndRespectsExplicitIds() throws Exception {
        final IXdFileStorage s = open();
        final IXdStorageTransaction tx = s.beginTransaction(60000L);
        for (int i = 0; i < 3; i++) {
            final AutoIdEntity.LongId e = new AutoIdEntity.LongId();
            e.setLabel("e" + i);
            s.save(e, tx);
            assertEquals((long) (i + 1), e.getId(),
                    "the generated sequence starts at 1 and is gapless in a fresh batch");
        }
        final AutoIdEntity.LongId explicit = new AutoIdEntity.LongId();
        explicit.setId(777L);
        s.save(explicit, tx);
        storage.commitTransaction(tx);
        assertEquals(777L, explicit.getId(), "an app-assigned id is never overwritten");

        // a different entity class owns a different counter starting at its own 1
        final IXdStorageTransaction tx2 = s.beginTransaction(60000L);
        final AutoIdEntity.IntId intEntity = new AutoIdEntity.IntId();
        s.save(intEntity, tx2);
        storage.commitTransaction(tx2);
        assertEquals(Integer.valueOf(1), intEntity.getId(), "counters are per entity class");
    }

    @Test
    @DisplayName("the batch boundary refetches: ids 1..100 in the first batch, 101 still works")
    public void hiLoBatchBoundaryRefetches() throws Exception {
        final IXdFileStorage s = open();
        final Set<Long> seen = new HashSet<>();
        final IXdStorageTransaction tx = s.beginTransaction(60000L);
        for (int i = 0; i < 101; i++) {
            final AutoIdEntity.LongId e = new AutoIdEntity.LongId();
            e.setLabel("b" + i);
            s.save(e, tx);
            assertTrue(seen.add(e.getId()), "ids are unique within the run, got " + e.getId());
        }
        storage.commitTransaction(tx);
        assertEquals(101, seen.size());
        assertTrue(seen.contains(100L), "the last id of the first batch is issued");
        assertTrue(seen.contains(101L), "crossing the batch boundary refetches and continues");
    }

    @Test
    @DisplayName("the burn contract: a reopen discards the unused batch remainder (≤99 ids), never reissues")
    public void reopenBurnsUnusedBatchRemainder() throws Exception {
        final IXdFileStorage first = open();
        Long lastBefore = null;
        final IXdStorageTransaction tx = first.beginTransaction(60000L);
        for (int i = 0; i < 3; i++) {
            final AutoIdEntity.LongId e = new AutoIdEntity.LongId();
            e.setLabel("burn" + i);
            first.save(e, tx);
            lastBefore = e.getId();
        }
        first.commitTransaction(tx);
        assertEquals(3L, lastBefore);
        first.shutdown();

        // reopen on the SAME directory: the persisted counter is 100 → the new batch starts at 101;
        // ids 4..100 are burned — at most 99 lost per crash, and 1..3 are never reissued
        final IXdFileStorage second = XdStorageProvider.newOrGetFileStorage("idgen-reopen-" + System.nanoTime(),
                tempDir.resolve(lastName).toString(), 512);
        try {
            final IXdStorageTransaction tx2 = second.beginTransaction(60000L);
            final AutoIdEntity.LongId e = new AutoIdEntity.LongId();
            e.setLabel("after-reopen");
            second.save(e, tx2);
            second.commitTransaction(tx2);
            assertEquals(101L, e.getId(),
                    "the reopen refetches from the persisted counter and burns the unused remainder");
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("String ids: the DATABASE default and the UUID_GENERATOR property both auto-generate UUIDs")
    public void stringIdGenerationContract() throws Exception {
        final IXdFileStorage s = open();
        final IXdStorageTransaction tx = s.beginTransaction(60000L);
        final AutoIdEntity.StringId byDefault = new AutoIdEntity.StringId();
        s.save(byDefault, tx);
        final AutoIdEntity.ExplicitUuidId generated = new AutoIdEntity.ExplicitUuidId();
        s.save(generated, tx);
        final AutoIdEntity.ExplicitUuidId assigned = new AutoIdEntity.ExplicitUuidId();
        assigned.setId("app-chosen-id");
        s.save(assigned, tx);
        storage.commitTransaction(tx);

        // BUG-D decided contract (docs/tasks/2.1.0/14, option (a)): UUID_GENERATOR generates,
        // like the DATABASE default — the mux dispatches by id type and validates the pair
        assertNotNull(byDefault.getId(), "a String id under the DATABASE default is auto-generated");
        UUID.fromString(byDefault.getId());
        assertNotNull(generated.getId(), "UUID_GENERATOR auto-generates (it never did before the fix)");
        UUID.fromString(generated.getId());
        assertNotEquals(byDefault.getId(), generated.getId());
        assertEquals("app-chosen-id", assigned.getId(), "an app-assigned id is never overwritten");

        // the generated ids are durable and addressable after reopen
        s.shutdown();
        final IXdFileStorage second = XdStorageProvider.newOrGetFileStorage("idgen-uuid-reopen-" + System.nanoTime(),
                tempDir.resolve(lastName).toString(), 512);
        try {
            final IXdStorageTransaction readTx = second.beginTransaction(60000L);
            final AutoIdEntity.ExplicitUuidId loaded =
                    second.load(AutoIdEntity.ExplicitUuidId.class, generated.getId(), readTx);
            second.commitTransaction(readTx);
            assertNotNull(loaded, "the UUID_GENERATED object is addressable by its generated id after reopen");
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("UUID_GENERATOR on a numeric id field fails loudly")
    public void uuidGeneratorOnNumericIdFailsLoudly() {
        final XdStorageDefaultIdGenerator mux = new XdStorageDefaultIdGenerator(null);
        assertThrows(io.github.xdstorage.exceptions.XdStorageException.class,
                () -> mux.generate(AutoIdEntity.UuidOnLongId.class, null, null),
                "UUID_GENERATOR + Long id is a loud contract error");
    }

    @Test
    @DisplayName("generated ids are addressable by id in the same storage")
    public void generatedIdsAddressableInStorage() throws Exception {
        final IXdFileStorage s = open();
        final IXdStorageTransaction tx = s.beginTransaction(60000L);
        final AutoIdEntity.LongId e = new AutoIdEntity.LongId();
        e.setLabel("addressable");
        s.save(e, tx);
        storage.commitTransaction(tx);

        final IXdStorageTransaction readTx = s.beginTransaction(60000L);
        final AutoIdEntity.LongId loaded = s.load(AutoIdEntity.LongId.class, e.getId(), readTx);
        storage.commitTransaction(readTx);
        assertNotNull(loaded, "the object is addressable by its generated id");
        assertEquals("addressable", loaded.getLabel());
    }

    /**
     * BUG-C acceptance (docs/tasks/2.2.0/03): objects with AUTO-GENERATED ids stay
     * addressable by id after a reopen. Root cause found during the fix: the defect was
     * never about generation timing — the id-index record keys lose their entity id-field
     * TYPE in the YAML round-trip ("9000" vs Long 9000), so every numeric-id lookup missed
     * after a reopen, generated or explicit (String ids round-tripped fine, which masked
     * the defect for years). The hash id-index resource now restores each record key to
     * the id-field type when it fills its cache from disk.
     */
    @Test
    public void generatedIdsSurviveReopen() throws Exception {
        final IXdFileStorage first = open();
        Long generated = null;
        final IXdStorageTransaction tx = first.beginTransaction(60000L);
        final AutoIdEntity.LongId e = new AutoIdEntity.LongId();
        e.setLabel("survivor");
        first.save(e, tx);
        first.commitTransaction(tx);
        generated = e.getId();
        first.shutdown();

        final IXdFileStorage second = XdStorageProvider.newOrGetFileStorage("idgen-survive-" + System.nanoTime(),
                tempDir.resolve(lastName).toString(), 512);
        try {
            final IXdStorageTransaction readTx = second.beginTransaction(60000L);
            final AutoIdEntity.LongId loaded = second.load(AutoIdEntity.LongId.class, generated, readTx);
            second.commitTransaction(readTx);
            assertNotNull(loaded, "the object is addressable by its generated id after reopen");
            assertEquals("survivor", loaded.getLabel());
        } finally {
            second.shutdown();
        }
    }

    @Test
    @DisplayName("explicit numeric ids are addressable by id after a reopen (the BUG-C regression is type-wide)")
    public void explicitNumericIdsAddressableAfterReopen() throws Exception {
        final IXdFileStorage first = open();
        final IXdStorageTransaction tx = first.beginTransaction(60000L);
        final AutoIdEntity.LongId e = new AutoIdEntity.LongId();
        e.setId(4242L);
        e.setLabel("explicit-numeric");
        first.save(e, tx);
        first.commitTransaction(tx);
        first.shutdown();

        final IXdFileStorage second = XdStorageProvider.newOrGetFileStorage("idgen-explicit-reopen-" + System
                .nanoTime(),
                tempDir.resolve(lastName).toString(), 512);
        try {
            final IXdStorageTransaction readTx = second.beginTransaction(60000L);
            final AutoIdEntity.LongId loaded = second.load(AutoIdEntity.LongId.class, 4242L, readTx);
            second.commitTransaction(readTx);
            assertNotNull(loaded, "an explicit numeric id resolves after reopen");
            assertEquals("explicit-numeric", loaded.getLabel());
        } finally {
            second.shutdown();
        }
    }
}
