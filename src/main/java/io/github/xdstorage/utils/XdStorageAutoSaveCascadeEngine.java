package io.github.xdstorage.utils;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageAutoSaveFieldInfo.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AUTOSAVE (docs/tasks/2.1.0/22) cascade engine — the walk that upserts
  * {@link io.github.xdstorage.annotations.XdStorageAutoSave @XdStorageAutoSave} annotated children after their parent's
  * own
 * persistence. Called at the tail of the facade save/update; child saves re-enter the
 * facade's public save/update (the guards are idempotent), so triggers, observers, id
 * generation, rollback and MVCC behave exactly as for explicit saves.
 *
 * <p>State: a per-thread IDENTITY visited set plus a depth counter. The set lives for
 * exactly ONE top-level save/update (the depth counter detects the outermost cascade and
 * clears the set at its end) — a set that leaked across operations would silently skip every
 * later cascade on the same thread. The set is what makes reference cycles terminate and
 * duplicate references (the same instance via two fields) save once.
 *
 * <p>Shapes: COLLECTION elements and single REFERENCE values cascade as entities (a missing
 * id-field fails loudly in the save, the standard contract); MAP-typed fields cascade only
 * their VALUES whose class carries an id-field — the keys are never candidates, and
 * id-field-less values are skipped with a one-time WARN per class.
 */
public final class XdStorageAutoSaveCascadeEngine {

    private static final Logger log = LoggerFactory.getLogger(XdStorageAutoSaveCascadeEngine.class);

    private static final ThreadLocal<Set<Object>> VISITED = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /** WARN-once registry for map values without an id-field (skipped by the cascade). */
    private static final Set<Class<?>> ID_FIELDLESS_WARNED =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    /** WARN-once registry for embedded-element fields (StoreWithParentObject — no-op). */
    private static final Set<Class<?>> EMBEDDED_WARNED =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    private XdStorageAutoSaveCascadeEngine() {
    }

    /**
     * The cascade entry — called at the tail of the facade save/update AFTER the object's own
     * persistence. The depth counter detects the outermost cascade and clears the visited set
     * when the whole operation unwinds.
     *
     * @param object the just-persisted parent whose {@code @XdStorageAutoSave} fields are walked
     * @param tx the transaction of the enclosing save/update, reused for every child upsert
     * @param services the storage service locator providing the resources manager
     * @param facade the storage facade child saves re-enter (triggers, id generation and MVCC
     *               therefore behave exactly as for explicit saves)
     * @throws io.github.xdstorage.exceptions.XdStorageException when a child upsert fails
     * @throws io.github.xdstorage.exceptions.XdStorageConnectionException when the underlying
     *         resource is unavailable
     */
    public static void cascadeAfterPersist(final Object object, final IXdStorageTransaction tx,
                                           final XdStorageServicesLocator services,
                                           final IXdFileStorage facade)
            throws io.github.xdstorage.exceptions.XdStorageException,
            io.github.xdstorage.exceptions.XdStorageConnectionException {
        final boolean outermost = DEPTH.get() == 0;
        DEPTH.set(DEPTH.get() + 1);
        try {
            Set<Object> visited = VISITED.get();
            if (visited == null) {
                visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                VISITED.set(visited);
            }
            cascadeFields(object, tx, services, facade, visited);
        } finally {
            final int depth = DEPTH.get() - 1;
            DEPTH.set(depth);
            if (depth == 0) {
                VISITED.remove();
            }
        }
    }

    private static void cascadeFields(final Object object, final IXdStorageTransaction tx,
                                      final XdStorageServicesLocator services,
                                      final IXdFileStorage facade,
                                      final Set<Object> visited)
            throws io.github.xdstorage.exceptions.XdStorageException,
            io.github.xdstorage.exceptions.XdStorageConnectionException {
        final Object unwrapped = XdStorageObjectUtils.getWrappedObjectOrSameObject(object);
        if (!visited.add(unwrapped)) {
            return; // fully processed earlier in this operation (dedup + cycle guard)
        }
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(
                XdStorageObjectUtils.getEntityClass(unwrapped.getClass()));
        for (final XdStorageAutoSaveFieldInfo info : clInfo.getAutoSaveFields()) {
            for (final Object child : info.childrenOf(unwrapped)) {
                final Object childUnwrapped = XdStorageObjectUtils.getWrappedObjectOrSameObject(child);
                // MASSFILL FIX: a lazy reference child (a not-yet-loaded stub — a simple
                // wrapper with reference__ set, or an R-53 parser stub marked
                // IXdStorageReferenceMarked) must NEVER be cascade-upserted. Its own fields
                // are not loaded (deeper AutoSave collections read null), and facade.update
                // would overwrite the durable record with that partial state — wiping
                // collections and orphaning the referenced records (the MassFill leak
                // family: a worker's one-level universe update cascade-upserted the seed
                // systems as planets=NULL stubs, wiping their durable planets collections;
                // the purge then walked only reachable collections and never saw the
                // orphaned planets). The parent's write owes a stub nothing but the linkage
                // it already carries: the child's own durable state stays authoritative.
                // Untouched stubs are the only thing skipped — accessing/mutating a stub
                // materializes it (isReference flips false) and the cascade updates it
                // again. Mark visited so reference cycles through stubs terminate quietly.
                if (XdStorageObjectUtils.isReference(child)) {
                    visited.add(childUnwrapped);
                    continue;
                }
                // dedup + cycle guard: the mark happens here, and the recursive walk below
                // is what opens the child's OWN fields — pre-marking before save would make
                // the child's cascade walk early-return and break transitivity (observed)
                if (visited.contains(childUnwrapped)) {
                    continue;
                }
                final Class<?> childClass = XdStorageObjectUtils.getEntityClass(childUnwrapped.getClass());
                final XdStorageClassInfo childInfo = XdStorageObjectUtils.getClassInfo(childClass);
                if (childInfo.getPolicy() == XdStoragePolicy.StoreWithParentObject) {
                    // the parent's own write already embeds StoreWithParentObject children —
                    // a cascade upsert would fail (no independent resource); WARN once per class
                    if (EMBEDDED_WARNED.add(childClass)) {
                        log.warn("@XdStorageAutoSave field element {} uses policy StoreWithParentObject — "
                                + "already embedded in the parent's file; cascade-save is a no-op for it",
                                childClass.getName());
                    }
                    visited.add(childUnwrapped);
                    continue;
                }
                if (info.getShape() == Shape.MAP) {
                    // MAP support (task 22 revision): only value objects with an id-field are
                    // cascade candidates — the keys and id-field-less values are skipped
                    if (childInfo.getIdField() == null) {
                        if (ID_FIELDLESS_WARNED.add(childClass)) {
                            log.warn("@XdStorageAutoSave MAP values of type {} have no id-field — "
                                    + "skipped by the cascade (only id-field-bearing value objects are saved)",
                                    childClass.getName());
                        }
                        visited.add(childUnwrapped);
                        continue;
                    }
                }
                upsert(childUnwrapped, childInfo, tx, services, facade);
                cascadeFields(childUnwrapped, tx, services, facade, visited);
            }
        }
    }

    /** V1 upsert: id null → insert (generated id backfills the same instance); id set → update if the index knows
     * it, insert otherwise. */
    private static void upsert(final Object child, final XdStorageClassInfo childInfo,
                               final IXdStorageTransaction tx, final XdStorageServicesLocator services,
                               final IXdFileStorage facade)
            throws io.github.xdstorage.exceptions.XdStorageException,
            io.github.xdstorage.exceptions.XdStorageConnectionException {
        final Object id = childInfo.getIdField() != null ? childInfo.getIdField().get(child) : null;
        if (id == null) {
            facade.save(child, tx);
            return;
        }
        if (childInfo.getPolicy() == XdStoragePolicy.StoreAsSingleObject) {
            IXdStorageDaoResource referencesResource =
                    services.getResourcesManager().lockReferencesResource(childInfo, (XdStorageTransaction) tx);
            if (!referencesResource.isDeleted(id, (XdStorageTransaction) tx)) {
                if (referencesResource.hasObject(id, (XdStorageTransaction) tx)) {
                    facade.update(child, tx);
                } else {
                    facade.save(child, tx);
                }
            }
        } else {
            final IXdStorageIndexDaoResource indexResource =
                    services.getResourcesManager().lockIndexResource(childInfo, (XdStorageTransaction) tx);
            if (!indexResource.isDeleted(id, (XdStorageTransaction) tx)) {
                if (indexResource.has(id, (XdStorageTransaction) tx)) {
                    facade.update(child, tx);
                } else {
                    facade.save(child, tx);
                }
            }
        }
    }
}
