package io.github.xdstorage.foreignkey;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.index.IXdStorageIndexDaoResource;
import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import io.github.xdstorage.observing.XdStorageObserverService;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.resource.IXdStorageInternalListener;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class XdStorageForeignKeyEngine implements IXdStorageForeignKeyEngine {

    private final boolean enabled;
    private final XdStorageForeignKeyResourcesManager foreignKeyResourcesManager;
    private final XdStorageForeignKeyRegistry foreignKeyRegistry;

    /** CRASHPURGE: services access for the parent-record existence probes. */
    private final io.github.xdstorage.services.XdStorageServicesLocator services;

    public XdStorageForeignKeyEngine(XdStorageForeignKeyResourcesManager foreignKeyResourcesManager,
                                     XdStorageForeignKeyRegistry foreignKeyRegistry, boolean enabled) {
        this(foreignKeyResourcesManager, foreignKeyRegistry, enabled, foreignKeyResourcesManager.getServices());
    }

    public XdStorageForeignKeyEngine(XdStorageForeignKeyResourcesManager foreignKeyResourcesManager,
                                     XdStorageForeignKeyRegistry foreignKeyRegistry, boolean enabled,
                                     final io.github.xdstorage.services.XdStorageServicesLocator services) {
        this.enabled = enabled;
        this.foreignKeyResourcesManager = foreignKeyResourcesManager;
        this.foreignKeyRegistry = foreignKeyRegistry;
        this.services = services;
    }

    @Override
    public void process(Object object, XdStorageForeignKeyObjectChangeType type, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        if (!enabled) return;

        // FNKEY (docs/tasks/2.1.0/42): the facade hands over WRAPPER proxies (the loaded
        // objects are SimpleWrappers) — the ClassInfo id-field access reads NULL on a
        // wrapper instance. Unwrap to the entity once at the entry; every downstream
        // field read works on the real object.
        object = XdStorageObjectUtils.getWrappedObjectOrSameObject(object);
        XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(XdStorageObjectUtils.getEntityClass(object.getClass()));

        Collection<XdStorageForeignKeyRelation> incomeRelations = foreignKeyRegistry.getIncomeRelations(clInfo);
        if (!incomeRelations.isEmpty()) {
            processIncomeRelations(clInfo, object, type, incomeRelations, transaction);
        }

        Collection<XdStorageForeignKeyRelation> outcomeRelations = foreignKeyRegistry.getOutcomeRelations(clInfo);
        if (!outcomeRelations.isEmpty()) {
            processOutcomeRelations(clInfo, object, type, outcomeRelations, transaction);
        }
    }

    private void processIncomeRelations(XdStorageClassInfo clInfo, Object object, XdStorageForeignKeyObjectChangeType type,
                                        Collection<XdStorageForeignKeyRelation> incomeRelations, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        if (type == XdStorageForeignKeyObjectChangeType.INSERT) {
            Comparable<?> childObjectId = clInfo.getIdField().get(object);
            if (childObjectId == null) {
                for (XdStorageForeignKeyRelation relation : incomeRelations) {
                    if (relation.isReverseRelation()) {
                        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(
                                relation.getClassFrom(), relation.getClassTo(), relation.getField(), transaction
                        );

                        IXdStorageIdObservableWrapper observableObject = XdStorageObserverService.getObservableWrapper(object);
                        resource.insert((Comparable<?>) null, observableObject, transaction);
                    }
                }
            } else {
                // FNKEY (docs/tasks/2.1.0/42): the child INSERT under a parent-anchored
                // relation declares the (parent, child) pair — the parent's save in THIS
                // transaction must register it (or the commit veto fires). The immediate
                // rejections (no parent reference / duplicate pair) fire on the spot.
                for (XdStorageForeignKeyRelation relation : incomeRelations) {
                    if (!relation.isReverseRelation() && relation.isParentAnchored()) {
                        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(
                                relation.getClassFrom(), relation.getClassTo(), relation.getField(), transaction
                        );
                        final Object parentReference = findParentReference(clInfo, object, relation.getClassFrom());
                        if (parentReference == null) {
                            // no parent declared at all — the immediate violation
                            resource.registerChildInsert(childObjectId, null, transaction);
                        } else {
                            final Comparable<?> parentObjectId = relation.getClassFrom().getIdField().get(parentReference);
                            if (parentObjectId != null) {
                                resource.registerChildInsert(childObjectId, parentObjectId, transaction);
                            } else {
                                // the parent reference exists but its id is not generated
                                // yet — the standard deferred-id waiter registers the pair
                                // when the identifier lands (the insertWaiting gate covers
                                // the commit if it never does)
                                IXdStorageIdObservableWrapper observableParent =
                                        XdStorageObserverService.getObservableWrapper(parentReference);
                                resource.insert(observableParent, childObjectId, transaction);
                            }
                        }
                    }
                }
            }
        } else if (type == XdStorageForeignKeyObjectChangeType.UPDATE) {
            // do nothing - income reference is not changed
        } else if (type == XdStorageForeignKeyObjectChangeType.DELETE) {
            Comparable<?> childObjectId = clInfo.getIdField().get(object);
            if (childObjectId != null) {
                for (XdStorageForeignKeyRelation relation : incomeRelations) {
                    if (!relation.isReverseRelation()) {
                        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(
                                relation.getClassFrom(), relation.getClassTo(), relation.getField(), transaction
                        );
                        if (relation.isParentAnchored()) {
                            // FNKEY: the child is deleted — the parent side must dissolve
                            // every still-present entry within this transaction or the
                            // commit veto fires (the "children are deleted first" rule).
                            // CRASHPURGE (docs/tasks/2.1.0/35): a parent whose RECORD is
                            // already gone (a torn crash commit) can never dissolve its
                            // entry — the pair is orphaned residue and is dissolved HERE,
                            // immediately, instead of arming a commit veto nobody can lift.
                            resource.markChildDeleted(childObjectId, transaction);
                            Collection<Object> parentIds = resource.readByChildObjectId(childObjectId, transaction);
                            for (Object parentObjectId : parentIds) {
                                if (parentRecordMissing(relation.getClassFrom(), parentObjectId, transaction)) {
                                    resource.delete(null, (Comparable<?>) parentObjectId, null, childObjectId, transaction);
                                } else {
                                    resource.registerOrphanedEntry((Comparable<?>) parentObjectId, childObjectId, transaction);
                                }
                            }
                        } else {
                            // child-anchored relation: the declarer itself is gone —
                            // dissolve its entries blindly
                            Collection<Object> parentIds = resource.readByChildObjectId(childObjectId, transaction);
                            for (Object parentObjectId : parentIds) {
                                resource.delete(null, (Comparable<?>) parentObjectId, object, childObjectId, transaction);
                            }
                        }
                    }
                }
            } else {
                // do nothing - object was not inserted
            }
        } else {
            throw new XdStorageException("undefined change type " + type);
        }
    }

    /**
     * CRASHPURGE (docs/tasks/2.1.0/35): whether the PARENT record still exists. A missing
     * parent (a torn crash commit persisted the FK pair but lost the record) makes the
     * pair orphaned residue: no live transaction can ever dissolve it, so the recovery
     * delete path dissolves it immediately instead of arming an unlift-able veto.
     *
     * @param parentClInfo the parent class metadata
     * @param parentObjectId the parent's identifier
     * @param transaction the deleting transaction
     * @return {@code true} when the parent record is absent (or its probe failed)
     */
    private boolean parentRecordMissing(final XdStorageClassInfo parentClInfo, final Object parentObjectId,
                                        final IXdStorageTransaction transaction) {
        try {
            return services.getStorage().load(parentClInfo.getClazz(), parentObjectId, transaction) == null;
        } catch (final XdStorageException | XdStorageConnectionException probeFailure) {
            org.slf4j.LoggerFactory.getLogger(XdStorageForeignKeyEngine.class).warn(
                    "CRASHPURGE: parent record probe failed for {}#{} — treating as missing (residue): {}",
                    parentClInfo.getClazz().getSimpleName(), parentObjectId, probeFailure.getMessage());
            return true;
        }
    }

    /**
     * FNKEY: the child's {@code @XdStorageParentObject} reference of the declared parent
     * class, or {@code null} when the field is unset or absent.
     */
    private Object findParentReference(final XdStorageClassInfo childClInfo, final Object childObject,
                                       final XdStorageClassInfo parentClInfo) throws XdStorageException {
        for (final XdStorageObjectField field : childClInfo.getFields().values()) {
            if (field.isParent() && field.getFieldInfo().getValueClass() == parentClInfo.getClazz()) {
                // FNKEY: the reference may be a wrapper proxy — unwrap before the id read
                return XdStorageObjectUtils.getWrappedObjectOrSameObject(field.get(childObject));
            }
        }
        return null;
    }

    private void processOutcomeRelations(XdStorageClassInfo clInfo, Object object, XdStorageForeignKeyObjectChangeType type,
                                         Collection<XdStorageForeignKeyRelation> outcomeRelations, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        if (type == XdStorageForeignKeyObjectChangeType.INSERT) {
            for (XdStorageForeignKeyRelation relation : outcomeRelations) {
                if (!relation.isReverseRelation()) {
                    XdStorageClassInfo clInfoTo = relation.getClassTo();
                    XdStorageObjectField clField = relation.getField();

                    Object objectByField = clField.get(object);
                    processInsertParentObjectWithChildren(clInfo, object, clInfoTo, clField, objectByField, transaction);
                }
            }
        } else if (type == XdStorageForeignKeyObjectChangeType.UPDATE) {
            for (XdStorageForeignKeyRelation relation : outcomeRelations) {
                if (!relation.isReverseRelation()) {
                    XdStorageClassInfo clInfoTo = relation.getClassTo();
                    XdStorageObjectField clField = relation.getField();

                    processUpdateParentObjectWithChildren(clInfo, object, clInfoTo, clField, transaction);
                }
            }
        } else if (type == XdStorageForeignKeyObjectChangeType.DELETE) {
            Comparable<?> parentObjectId = clInfo.getIdField().get(object);
            for (XdStorageForeignKeyRelation relation : outcomeRelations) {
                if (!relation.isReverseRelation()) {
                    IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(
                            relation.getClassFrom(), relation.getClassTo(), relation.getField(), transaction
                    );
                    Collection<Object> childIds = resource.readByParentObjectId(parentObjectId, transaction);
                    for (Object childObjectId : childIds) {
                        resource.delete(object, parentObjectId, null, (Comparable<?>) childObjectId, transaction);
                    }
                }
            }
        } else {
            throw new XdStorageException("undefined change type " + type);
        }
    }

    private void processInsertParentObjectWithChildren(XdStorageClassInfo clInfo, Object childObject,
                                                       XdStorageClassInfo clInfoTo, XdStorageObjectField clField,
                                                       Object objectByField, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        XdStorageObjectFieldInfo fieldInfo = clField.getFieldInfo();
        if (fieldInfo.isArray()) {
            Object[] arrObjects = (Object[]) objectByField;
            if (arrObjects == null) return;
            for (Object parentObject : arrObjects) {
                processInsertParentObjectWithChildObject(clInfo, childObject, clInfoTo, clField, parentObject, transaction);
            }
        } else if (fieldInfo.isCollection()) {
            Collection<?> collectionObjects = (Collection<?>) objectByField;
            if (collectionObjects == null) return;
            for (Object parentObject : collectionObjects) {
                processInsertParentObjectWithChildObject(clInfo, childObject, clInfoTo, clField, parentObject, transaction);
            }
        } else if (fieldInfo.isMap()) {
            Map<?, Object> mapObjects = (Map<?, Object>) objectByField;
            if (mapObjects == null) return;
            for (Object parentObject : mapObjects.values()) {
                processInsertParentObjectWithChildObject(clInfo, childObject, clInfoTo, clField, parentObject, transaction);
            }
        } else {
            processInsertParentObjectWithChildObject(clInfo, childObject, clInfoTo, clField, objectByField, transaction);
        }
    }

    private void processInsertParentObjectWithChildObject(XdStorageClassInfo clInfo, Object childObject,
                                                          XdStorageClassInfo clInfoTo, XdStorageObjectField clField,
                                                          Object parentObject, XdStorageTransaction transaction)
            throws XdStorageConnectionException, XdStorageException {

        if (parentObject == null) return;

        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(
                clInfo, clInfoTo, clField, transaction
        );

        Comparable<?> childObjectId = clInfo.getIdField().get(childObject);
        XdStorageObjectIdField idField = clInfoTo.getIdField();
        if (idField == null) {
            throw new XdStorageException("the class of auto-save fields must have id-field, but " + clInfoTo.getClazz() + " does not");
        }
        Comparable<?> parentObjectId = idField.get(parentObject);
        if (childObjectId != null) {
            if (parentObjectId != null) {
                resource.insert(childObjectId, parentObjectId, transaction);
            } else {
                IXdStorageIdObservableWrapper observableChildObject = XdStorageObserverService.getObservableWrapper(parentObject);
                resource.insert(childObjectId, observableChildObject, transaction);
            }
        } else {
            IXdStorageIdObservableWrapper observableParentObject = XdStorageObserverService.getObservableWrapper(childObject);
            if (parentObjectId != null) {
                resource.insert(observableParentObject, parentObjectId, transaction);
            } else {
                IXdStorageIdObservableWrapper observableChildObject = XdStorageObserverService.getObservableWrapper(parentObject);
                resource.insert(observableParentObject, observableChildObject, transaction);
            }
        }
    }

    private void processUpdateParentObjectWithChildren(XdStorageClassInfo clInfo, Object parentObject,
                                                       XdStorageClassInfo clInfoTo, XdStorageObjectField clField,
                                                       XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(
                clInfo, clInfoTo, clField, transaction
        );
        Comparable<?> parentObjectId = clInfo.getIdField().get(parentObject);
        Collection<?> childIds = new ArrayList<>(resource.readByParentObjectId(parentObjectId, transaction));

        XdStorageObjectFieldInfo fieldInfo = clField.getFieldInfo();
        if (fieldInfo.isArray()) {
            Object[] arrObjects = clField.get(parentObject);
            if (arrObjects == null) return;
            for (Object childObject : arrObjects) {
                processUpdateParentObjectWithChildObject(clInfo, parentObject, clInfoTo, clField, childObject, childIds, resource, transaction);
            }
        } else if (fieldInfo.isCollection()) {
            Collection<?> collectionObjects = clField.get(parentObject);
            if (collectionObjects == null) return;
            for (Object childObject : collectionObjects) {
                processUpdateParentObjectWithChildObject(clInfo, parentObject, clInfoTo, clField, childObject, childIds, resource, transaction);
            }
        } else if (fieldInfo.isMap()) {
            Map<?, Object> mapObjects = clField.get(parentObject);
            if (mapObjects == null) return;
            for (Object childObject : mapObjects.values()) {
                processUpdateParentObjectWithChildObject(clInfo, parentObject, clInfoTo, clField, childObject, childIds, resource, transaction);
            }
        } else {
            Object childObject = clField.get(parentObject);
            processUpdateParentObjectWithChildObject(clInfo, parentObject, clInfoTo, clField, childObject, childIds, resource, transaction);
        }

        for (Object childObjectId : childIds) {
            // FNKEY: the update diff removes the reference legitimately — the blind
            // dissolve (parentObject = null), no dangling-child expectation.
            resource.delete(null, parentObjectId, null, (Comparable<?>) childObjectId, transaction);
        }
    }

    private void processUpdateParentObjectWithChildObject(XdStorageClassInfo clInfo, Object parentObject,
                                                          XdStorageClassInfo clInfoTo, XdStorageObjectField clField,
                                                          Object childObject, Collection<?> childIds,
                                                          IXdStorageForeignKeyResource resource, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        if (childObject == null) return;

        Comparable<?> parentObjectId = clInfo.getIdField().get(parentObject);
        Comparable<?> childObjectId = clInfoTo.getIdField().get(childObject);
        if (childObjectId == null) {
            IXdStorageIdObservableWrapper observableChildObject = XdStorageObserverService.getObservableWrapper(childObject);
            if (parentObjectId == null) {
                IXdStorageIdObservableWrapper observableParentObject = XdStorageObserverService.getObservableWrapper(parentObject);
                resource.insert(observableParentObject, observableChildObject, transaction);
            } else {
                resource.insert(parentObjectId, observableChildObject, transaction);
            }
        } else if (parentObjectId != null) {
            if (!childIds.contains(childObjectId)) {
                resource.insert(parentObjectId, childObjectId, transaction);
            }
            childIds.remove(childObjectId);
        } else {
            IXdStorageIdObservableWrapper observableParentObject = XdStorageObserverService.getObservableWrapper(parentObject);
            resource.insert(observableParentObject, childObjectId, transaction);
            childIds.remove(childObjectId);
        }
    }

}
