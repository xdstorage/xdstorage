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

    public XdStorageForeignKeyEngine(XdStorageForeignKeyResourcesManager foreignKeyResourcesManager,
                                     XdStorageForeignKeyRegistry foreignKeyRegistry, boolean enabled) {
        this.enabled = enabled;
        this.foreignKeyResourcesManager = foreignKeyResourcesManager;
        this.foreignKeyRegistry = foreignKeyRegistry;
    }

    @Override
    public void process(Object object, XdStorageForeignKeyObjectChangeType type, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        if (!enabled) return;

        XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(object.getClass());

        Collection<XdStorageForeignKeyRelation> incomeRelations = foreignKeyRegistry.getIncomeRelations(clInfo);
        if (!incomeRelations.isEmpty()) {
            processIncomeRelations(clInfo, object, type, incomeRelations, transaction);
        }

        Collection<XdStorageForeignKeyRelation> outcomeRelations = foreignKeyRegistry.getOutcomeRelations(clInfo);
        if (!outcomeRelations.isEmpty()) {
            processOutcomeRelations(clInfo, object, type, outcomeRelations, transaction);
        }
    }

    /**
     * FNKEYS-EAGER: видна ли запись родителя (зако­мичена или стейджнута этой транзакцией).
     * Любой сбой пробы (юнит-контуры с частичными сервисами) трактуется консервативно как
     * «видна» — энергичная регистрация ac9f1c1-семантики.
     */
    private boolean parentRecordVisible(final XdStorageForeignKeyRelation relation, final Comparable<?> parentObjectId, final XdStorageTransaction transaction) {
        try {
            return foreignKeyResourcesManager.getServices().getStorage().load(relation.getClassFrom().getClazz(), parentObjectId, transaction) != null;
        } catch (final Throwable probeFailure) {
            return true;
        }
    }

    private void processIncomeRelations(XdStorageClassInfo clInfo, Object object, XdStorageForeignKeyObjectChangeType type,
                                        Collection<XdStorageForeignKeyRelation> incomeRelations, XdStorageTransaction transaction)
            throws XdStorageException, XdStorageConnectionException {

        Comparable<?> childObjectId = clInfo.getIdField().get(object);
        if (type == XdStorageForeignKeyObjectChangeType.INSERT) {
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
                // FNKEY: a child INSERTED with an id under a parent-anchored relation
                // DECLARES the (parent, child) pair. The parent's save in this transaction
                // must make the pair registerable: when the child's parent reference
                // carries an id the pair registers immediately; when the parent is not
                // saved yet the deferred parent-id waiter registers it when the id lands —
                // and if it never lands, the phase-1 insert-waiter check vetoes (the
                // "save a child without saving its parent" pin).
                for (XdStorageForeignKeyRelation relation : incomeRelations) {
                    if (!relation.isReverseRelation() && relation.isParentAnchored()) {
                        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(
                                relation.getClassFrom(), relation.getClassTo(), relation.getField(), transaction
                        );
                        final Object parentReference = findParentReference(clInfo, object, relation.getClassFrom());
                        if (parentReference != null) {
                            final Comparable<?> parentObjectId = relation.getClassFrom().getIdField().get(parentReference);
                            if (parentObjectId != null && !resource.readByChildObjectId(childObjectId, transaction).contains(parentObjectId)) {
                                // FNKEYS-EAGER: регистрируем энергично ТОЛЬКО против существующей записи
                                // родителя; за рукопоставленным id записи может не быть — тогда пара ждёт
                                // появления родителя в ЭТОЙ же транзакции (несохранённый родитель ветирует
                                // коммит, ручной аналог auto-id вейтера). Сбой пробы (юнит-контуры с
                                // частичными сервисами) трактуем консервативно: «запись есть».
                                if (parentRecordVisible(relation, parentObjectId, transaction)) {
                                    resource.insert(parentObjectId, childObjectId, transaction);
                                } else {
                                    resource.insertAwaitingParentRecord(parentObjectId, childObjectId, transaction);
                                }
                            } else if (parentObjectId != null) {
                                // the pair is already registered (a re-save of an existing child)
                            } else {
                                IXdStorageIdObservableWrapper observableParent =
                                        XdStorageObserverService.getObservableWrapper(parentReference);
                                resource.insert(observableParent, childObjectId, transaction);
                            }
                        }
                    }
                }
            }
        } else if (type == XdStorageForeignKeyObjectChangeType.UPDATE) {
            // do nothing — a child update dissolves no pairs on its own. The pair
            // bookkeeping for child updates is owned by the PARENT-update diff (it
            // registers the waitForChildDelete expectation resolved by THIS child's own
            // commit event) and by the parent's commit-time reference check. Registering
            // a parent-update waiter here cannot work: the cascade rewrite hands this
            // engine a stored-state clone whose parent reference is null, so "the child
            // still references the parent" is undecidable here, and a waiter registered
            // for a still-referenced child never resolves (the parent's update event
            // never reports the reference removed) — the commit dead-locks on the veto.
        } else if (type == XdStorageForeignKeyObjectChangeType.DELETE) {
            if (childObjectId != null) {
                for (XdStorageForeignKeyRelation relation : incomeRelations) {
                    if (!relation.isReverseRelation()) {
                        IXdStorageForeignKeyResource resource = foreignKeyResourcesManager.lockForeignKeyResource(
                                relation.getClassFrom(), relation.getClassTo(), relation.getField(), transaction
                        );

                        Collection<Object> parentIds = resource.readByChildObjectId(childObjectId, transaction);
                        for (Object parentObjectId : parentIds) {
                            resource.delete(null, (Comparable<?>) parentObjectId, object, childObjectId, transaction);
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
     * FNKEY: the child's parent-reference object (the {@code @XdStorageParentObject}
     * field typed as the relation's parent class). {@code null} when the child declares
     * no parent at all — no pair expectation.
     */
    private Object findParentReference(final XdStorageClassInfo childClInfo, final Object childObject,
                                       final XdStorageClassInfo parentClInfo) {
        final Object entity = XdStorageObjectUtils.getWrappedObjectOrSameObject(childObject);
        io.github.xdstorage.utils.XdStorageObjectField parentReferenceField =
                childClInfo.getFields().values().stream()
                        .filter(f -> f.isParent() && f.getFieldInfo().getValueClass() == parentClInfo.getClazz())
                        .findFirst().orElse(null);
        if (parentReferenceField == null) {
            parentReferenceField = childClInfo.getFields().values().stream()
                    .filter(f -> f.getFieldInfo().getValueClass() == parentClInfo.getClazz())
                    .findFirst().orElse(null);
        }
        if (parentReferenceField == null) {
            return null;
        }
        try {
            return parentReferenceField.get(entity);
        } catch (final Exception e) {
            return null;
        }
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
                if (!relation.isReverseRelation() && relation.isParentAnchored()) {
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

        XdStorageObjectIdField childIdField = clInfoTo.getIdField();

        XdStorageObjectFieldInfo fieldInfo = clField.getFieldInfo();
        if (fieldInfo.isArray()) {
            Object[] arrObjects = clField.get(parentObject);
            if (arrObjects != null) {
                for (Object childObject : arrObjects) {
                    processUpdateParentObjectWithChildObject(clInfo, parentObject, clInfoTo, clField, childObject, childIds, resource, transaction);

                    Comparable<?> childId = childIdField.get(childObject);
                    if (childId != null) {
                        childIds.remove(childId);
                    }
                }
            }
        } else if (fieldInfo.isCollection()) {
            Collection<?> collectionObjects = clField.get(parentObject);
            if (collectionObjects != null) {
                for (Object childObject : collectionObjects) {
                    processUpdateParentObjectWithChildObject(clInfo, parentObject, clInfoTo, clField, childObject, childIds, resource, transaction);

                    Comparable<?> childId = childIdField.get(childObject);
                    if (childId != null) {
                        childIds.remove(childId);
                    }
                }
            }
        } else if (fieldInfo.isMap()) {
            Map<?, Object> mapObjects = clField.get(parentObject);
            if (mapObjects != null) {
                for (Object childObject : mapObjects.values()) {
                    processUpdateParentObjectWithChildObject(clInfo, parentObject, clInfoTo, clField, childObject, childIds, resource, transaction);

                    Comparable<?> childId = childIdField.get(childObject);
                    if (childId != null) {
                        childIds.remove(childId);
                    }
                }
            }
        } else {
            Object childObject = clField.get(parentObject);
            processUpdateParentObjectWithChildObject(clInfo, parentObject, clInfoTo, clField, childObject, childIds, resource, transaction);

            if (childObject != null) {
                Comparable<?> childId = childIdField.get(childObject);
                if (childId != null) {
                    childIds.remove(childId);
                }
            }
        }

        for (Object childObjectId : childIds) {
            resource.delete(parentObject, parentObjectId, null, (Comparable<?>) childObjectId, transaction);
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
