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
                // do nothing
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
