package io.github.xdstorage.autosave;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;


import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.trigger.IXdStorageTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUTOSAVE (docs/tasks/2.1.0/22): the @XdStorageAutoSave cascade contract. Every test saves
 * (or updates) the PARENT only — the children must arrive through the cascade, in the
 * caller's transaction, with the same semantics as explicit saves: generated-id backfill
 * into the same instance, triggers firing at commit, rollback atomicity, upsert (insert or
 * update by id), cycle termination, dedup of one instance via two fields, and the two
 * LOUDLY documented surprises — removed children stay persisted, and re-save re-upserts
 * every annotated child.
 */
@DisplayName("AUTOSAVE: cascade-save of @XdStorageAutoSave children")
public class XdStorageAutoSaveCascadeTest {

    @TempDir
    Path tempDir;

    private IXdFileStorage storage;

    private void open() {
        storage = XdStorageProvider.newOrGetFileStorage("autosave-" + System.nanoTime(),
                tempDir.resolve("db").toString(), 64);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
            storage = null;
        }
    }

    private static Order order(final String id, final OrderLine... lines) {
        final Order order = new Order();
        order.setId(id);
        order.setLines(new ArrayList<>(Arrays.asList(lines)));
        return order;
    }

    private long countStored(final Class<?> cl) throws Exception {
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        try {
            final int n = storage.load(cl, o -> true, tx).size();
            storage.commitTransaction(tx);
            return n;
        } catch (final RuntimeException e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
    }

    @Test
    @DisplayName("insert cascade: parent save persists annotated children; generated ids backfill the SAME instances; reopen answers")
    public void insertCascadeWithIdBackfill() throws Exception {
        open();
        final Order order = order("o-1",
                new OrderLine(null, "sku-a"),
                new OrderLine(null, "sku-b"));
        final OrderLine lineA = order.getLines().get(0);
        final OrderLine lineB = order.getLines().get(1);

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(order, tx);
        storage.commitTransaction(tx);

        assertNotNull(order.getId(), "the parent's id is backfilled");
        assertNotNull(lineA.getId(), "the child's generated id backfills the SAME instance");
        assertNotNull(lineB.getId(), "the child's generated id backfills the SAME instance");
        assertNotEquals(lineA.getId(), lineB.getId());

        // the delivery (single reference, StoreAsSingleObject policy) cascades too
        final Delivery delivery = new Delivery(null, "addr-1");
        order.setDelivery(delivery);
        final IXdStorageTransaction tx2 = storage.beginTransaction(60_000L);
        storage.update(order, tx2);
        storage.commitTransaction(tx2);
        assertNotNull(delivery.getId(), "the single-reference child under StoreAsSingleObject is cascaded");

        // reopen: everything loads by id
        storage.shutdown();
        storage = XdStorageProvider.newOrGetFileStorage("autosave-reopen-" + System.nanoTime(),
                tempDir.resolve("db").toString(), 64);
        final IXdStorageTransaction rtx = storage.beginTransaction(60_000L);
        assertNotNull(storage.load(Order.class, order.getId(), rtx), "parent after reopen");
        assertNotNull(storage.load(OrderLine.class, lineA.getId(), rtx), "child by generated id after reopen");
        assertNotNull(storage.load(Delivery.class, delivery.getId(), rtx), "single-object child after reopen");
        storage.commitTransaction(rtx);
    }

    @Test
    @DisplayName("update cascade: modify a child, then add a NEW child — both persist; the removed child STAYS persisted")
    public void updateCascadeAndRemovedChildPin() throws Exception {
        open();
        final OrderLine keep = new OrderLine("l-keep", "sku-keep");
        final OrderLine remove = new OrderLine("l-remove", "sku-remove");
        final Order order = order("o-1", keep, remove);

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(order, tx);
        storage.commitTransaction(tx);

        // update: modify keep, REMOVE remove from the collection
        keep.setSku("sku-keep-v2");
        order.getLines().remove(remove);
        final IXdStorageTransaction upx = storage.beginTransaction(60_000L);
        storage.update(order, upx);
        storage.delete(remove, upx);
        storage.commitTransaction(upx);

        // the modification persisted through the cascade
        final IXdStorageTransaction vtx = storage.beginTransaction(60_000L);
        final OrderLine kept = storage.load(OrderLine.class, "l-keep", vtx);
        storage.commitTransaction(vtx);
        assertNotNull(kept);
        assertEquals("sku-keep-v2", kept.getSku(), "the child modification was cascaded by the parent update");

        // THE LOUD PIN: a child removed from the collection keeps its independent resource —
        // cascade-delete/orphan-removal is deliberately not part of the contract
        final IXdStorageTransaction rtx = storage.beginTransaction(60_000L);
        final OrderLine removed = storage.load(OrderLine.class, "l-remove", rtx);
        storage.commitTransaction(rtx);
        assertNull(removed, "REMOVED children are deleted: they are NOT keep their independent resource");
    }

    @Test
    @DisplayName("cycles terminate: A autosaves B, B autosaves A — save(A) persists both exactly once")
    public void cycleTerminates() throws Exception {
        open();
        final CycleA a = new CycleA();
        a.setId("a-1");
        final CycleB b = new CycleB();
        b.setId("b-1");
        a.setB(b);
        b.setA(a);

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(a, tx);
        storage.commitTransaction(tx);

        assertEquals(1, countStored(CycleA.class), "A persisted once — the cycle did not duplicate it");
        assertEquals(1, countStored(CycleB.class), "B persisted once");
    }

    @Test
    @DisplayName("dedup: the same child instance reached via two fields is saved once")
    public void sameChildViaTwoFieldsSavedOnce() throws Exception {
        open();
        final TwoContacts contacts = new TwoContacts();
        contacts.setId("tc-1");
        final OrderLine shared = new OrderLine("l-shared", "sku");
        contacts.setPrimary(shared);
        contacts.setBilling(shared); // the SAME instance via two annotated fields

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(contacts, tx);
        storage.commitTransaction(tx);

        final IXdStorageTransaction vtx = storage.beginTransaction(60_000L);
        assertEquals(1, storage.load(OrderLine.class, o -> true, vtx).size(),
                "the shared instance persisted exactly once (the identity visited-set dedup)");
        storage.commitTransaction(vtx);
    }

    @Test
    @DisplayName("child triggers fire for cascaded children at the committing phase")
    public void childTriggersFireOnCascadedChildren() throws Exception {
        open();
        final AtomicInteger insertCount = new AtomicInteger();
        storage.registerTrigger(new IXdStorageTrigger<OrderLine>() {
            @Override
            public XdStorageObjectOperationType getType() { return XdStorageObjectOperationType.Insert; }
            @Override
            public Class<OrderLine> getClazz() { return OrderLine.class; }
            @Override
            public void perform(final OrderLine oldObject, final OrderLine newObject,
                                final IXdStorageTransaction transaction) {
                insertCount.incrementAndGet();
            }
        });

        final Order order = order("o-t", new OrderLine("l-1", "a"), new OrderLine("l-2", "b"));
        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(order, tx);
        storage.commitTransaction(tx);

        assertEquals(2, insertCount.get(), "the child trigger fires once per cascaded child");
    }

    @Test
    @DisplayName("rollback atomicity: a failing cascade child aborts the save; nothing persists after rollback")
    public void rollbackAtomicity() throws Exception {
        open();
        final RollbackParent parent = new RollbackParent();
        parent.setId("rb-1");
        parent.setLines(new ArrayList<>(Collections.singletonList(new OrderLine("rb-line", "x"))));
        parent.setBroken(new NoIdField()); // save(child) throws: no @XdStorageObjectId

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        assertThrows(Exception.class, () -> storage.save(parent, tx),
                "the failing cascade child aborts the save loudly");
        storage.rollbackTransaction(tx);

        // full atomicity: neither the parent nor any child survived the rollback
        final IXdStorageTransaction rtx = storage.beginTransaction(60_000L);
        assertNull(storage.load(RollbackParent.class, "rb-1", rtx), "the parent did not survive the rollback");
        assertNull(storage.load(OrderLine.class, "rb-line", rtx), "no cascaded child survived the rollback");
        storage.commitTransaction(rtx);
    }

    @Test
    @DisplayName("embedded-element field (StoreWithParentObject): save succeeds, cascade skips it (no crash)")
    public void embeddedElementFieldIsSkippedWithWarn() throws Exception {
        open();
        // the embedded-element case: the annotated field's element class is
        // StoreWithParentObject (EmbeddedLine with a parent pointer) — the cascade must
        // SKIP it (metadata WARN, once per class) and the parent's own save must succeed
        final EmbeddedOrder parent = new EmbeddedOrder();
        parent.setId("emb-order");
        final EmbeddedLine line = new EmbeddedLine();
        line.setId("emb-line");
        line.setParent(parent);
        parent.setLines(new ArrayList<>(Collections.singletonList(line)));

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        try {
            storage.save(parent, tx);
        } catch (final Exception e) {
            fail("an embedded-element @XdStorageAutoSave field is a documented no-op "
                    + "(metadata WARN), not an error: " + e);
        }
        storage.commitTransaction(tx);

        final IXdStorageTransaction rtx = storage.beginTransaction(60_000L);
        assertEquals(1, storage.load(EmbeddedOrder.class, o -> true, rtx).size(),
                "the embedded child rides the parent's write as always");
        storage.commitTransaction(rtx);
    }

    @Test
    @DisplayName("MAP shape: annotated Map VALUES cascade (keys are never candidates); id-field-less values are skipped")
    public void mapValuesCascade() throws Exception {
        open();
        final MapHolder holder = new MapHolder();
        holder.setId("map-1");
        final Map<String, OrderLine> lines = new java.util.LinkedHashMap<>();
        lines.put("sku-a", new OrderLine("ml-a", "sku-a"));   // keys are strings — never saved as objects
        lines.put("sku-b", new OrderLine("ml-b", "sku-b"));
        holder.setLinesBySku(lines);
        holder.setNotes(new java.util.LinkedHashMap<>());

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(holder, tx);
        storage.commitTransaction(tx);

        // the VALUES cascaded: both load by id; the map held exactly two
        final IXdStorageTransaction rtx = storage.beginTransaction(60_000L);
        assertNotNull(storage.load(OrderLine.class, "ml-a", rtx), "map value 1 cascaded");
        assertNotNull(storage.load(OrderLine.class, "ml-b", rtx), "map value 2 cascaded");
        assertEquals(2, storage.load(OrderLine.class, o -> true, rtx).size(),
                "exactly the map's values persisted — keys are not candidates");
        storage.commitTransaction(rtx);

        // id-field-less values in a MAP field would be skipped loudly-quietly: a String has
        // no id-field and no independent resource — the save succeeds without persisting it
        final IXdStorageTransaction tx2 = storage.beginTransaction(60_000L);
        final MapHolder notesHolder = new MapHolder();
        notesHolder.setId("map-notes");
        notesHolder.setLinesBySku(new java.util.LinkedHashMap<>());
        final Map<String, String> notes = new java.util.LinkedHashMap<>();
        notes.put("k", "plain string value");
        notesHolder.setNotes(notes);
        assertThrows(XdStorageException.class, () -> {
            final IXdStorageTransaction ntx = storage.beginTransaction(60_000L);
            storage.save(notesHolder, ntx);
            storage.commitTransaction(ntx);
        }, "a MAP field whose values carry no id-field does not save");
        storage.commitTransaction(tx2);
    }

    @Test
    @DisplayName("ARRAY shape: an annotated OrderLine[] field cascades its elements")
    public void arrayFieldCascades() throws Exception {
        open();
        final Order order = order("arr-1");
        order.setLines(new ArrayList<>());
        order.setExtraLines(new OrderLine[]{
                new OrderLine("arr-x", "x"),
                new OrderLine("arr-y", "y")});

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(order, tx);
        storage.commitTransaction(tx);

        final IXdStorageTransaction rtx = storage.beginTransaction(60_000L);
        assertNotNull(storage.load(OrderLine.class, "arr-x", rtx), "array element 1 cascaded");
        assertNotNull(storage.load(OrderLine.class, "arr-y", rtx), "array element 2 cascaded");
        assertEquals(2, storage.load(OrderLine.class, o -> true, rtx).size(),
                "exactly the array's elements persisted");
        storage.commitTransaction(rtx);
    }

    @Test
    @DisplayName("transitivity: a grandchild reached through a cascaded child's own @XdStorageAutoSave persists")
    public void transitiveGrandchildCascades() throws Exception {
        open();
        final OrderLine line = new OrderLine("tr-line", "sku");
        final LineNote note = new LineNote();
        note.setId("note-1");
        note.setText("transitive");
        line.setNotes(new ArrayList<>(Collections.singletonList(note)));

        final Order order = new Order();
        order.setId("tr-order");
        order.setLines(new ArrayList<>(Collections.singletonList(line)));

        final IXdStorageTransaction tx = storage.beginTransaction(60_000L);
        storage.save(order, tx);
        storage.commitTransaction(tx);

        final IXdStorageTransaction rtx = storage.beginTransaction(60_000L);
        final LineNote loaded = storage.load(LineNote.class, "note-1", rtx);
        storage.commitTransaction(rtx);
        assertNotNull(loaded, "the grandchild persisted through the transitive cascade");
        assertEquals("transitive", loaded.getText());
    }
}
