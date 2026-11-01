---
title: "[2.1.0][FNKEY] Child objects with policies StoreAsSingleObject or StoreAsClassObjects should have foreign keys in relation with parent object
labels: [2.1.0-train, P1, engine, feature]
status: open
---

## Summary

When some transactions modify objects which have relations with other objects by storage policies 
`StoreAsSingleObject` or `StoreAsClassObjects` then relations may be lost. As example child object was
deleted in transaction, but parent object relation was not updated (parent object still refer to
deleted child object).

## Solution

Foreign key solves this case and allows to avoid the situation when child object is deleted, but
parent object still has reference to this deleted object. The next rules describe behaviour of storage:

- Foreign key appears when field in parent class marked with annotation `XdStorageAutoSave`;
- Foreign key appears when field in child class marked with annotation `XdStorageParentObject`;
- Foreign key should be configurable - enabled by default;
- This relation get names **<parent_object_simple_class_name>`_to_`<parent_object_class_field_name>** as `front`
direction, and **<parent_object_simple_class_name>`_from_`<parent_object_class_field_name>** as `reverse` direction;
- Must be organised as persistent B+tree structure.

### Foreign key structure

Multiple B+tree structure should be chosen for both directions of relation.

### Insertion rules

- During saving process must be created 2 indexes: `front` direction and `reverse` direction.
- `front` direction describes relation from parent object to child object. `reverse` direction describes
relation from child object to parent object.
- Creation of foreign keys indexes and index values must be in the same transaction as saving objects.

### Deletion rules

- Child objects must be deleted first. Parent object can be deleted after that;
- Foreign keys should be deleted during deletion of child object;
- Foreign keys must be checked before deletion of any object with policies `StoreAsSingleObject` or
`StoreAsClassObjects` if foreign keys exists.
- Deletion of foreign keys must be in the same transaction as deletion objects.

### Invariants

- Saving foreign key with same pair parent key and child key in one relation **must be rejected**.
- Deletion child object without deletion reference in parent object to deletable object
**must be rejected** (parent class field annotated with `XdStorageAutoSave`).
- Deletion parent object without clearing reference to parent in child object **must be rejected**
(child class field annotated with `XdStorageParentObject`)

### Low-Level Implementation Requirements (Strict Constraints)

1. **Transaction Lifecycle Alignment (2PC Engine integration):**
    - DO NOT modify the persistent B+tree files of Foreign Keys directly inside the `storage.save()` 
    or `storage.delete()` frontend calls.
    - All FK mutations (insert/delete index commands) MUST be registered as transactional changes within 
    the active `XdStorageTransaction` context.
    - Physical flushing to disk MUST occur strictly during the `performCommit` phase of 
    the Two-Phase Commit (2PC) protocol, ensuring atomicity alongside data segments.

2. **Reflection-Independent Validation:**
    - When verifying deletion invariants (e.g., checking if child objects still refer to a parent), 
    the engine MUST query the respective B+tree `reverse` index directly using the object's identifier.
    - DO NOT rely on reading Java object fields or collections directly, as fields annotated 
    with `@XdStorageLoadByGetMethod` might contain unhydrated lazy proxies, which will cause 
    false-positive validation passes.

3. **Configuration & Fail-Safe Defaults:**
    - Introduce the configuration property `enableForeignKeys` (boolean, defaults to true) 
    into `io.github.xdstorage.XdStorageConfig`.
    - If `enableForeignKeys` is disabled at storage initialization, all FK validation barriers during 
    insert/delete operations must be transparently skipped without throwing exceptions.

4. **Error Handling & Cascade Safety:**
    - Any violation of the constraints defined in the "Invariants" section MUST throw 
    a `io.github.xdstorage.exceptions.XdStorageRuntimeException` containing the specific transaction ID, 
    the violated relation name, and the offending object identifiers.
    - This exception must immediately mark the active transaction as `rollback-only`.


## Test scenarios

- Saving parent object with link to child object, when field in class of parent object annotated 
with `XdStorageAutoSave` - commit successful
- Saving only child object when field of class child object annotated with `XdStorageParentObject` - commit
throws `XdStorageRuntimeException`
- Saving child and parent objects in one transaction when field of class child object annotated
with `XdStorageParentObject` - commit successful
- Deleting only parent object with link to child object, when field in class of parent object annotated
with `XdStorageAutoSave` - commit throws `XdStorageRuntimeException`
- Deleting parent object and child object, when field in class of parent object annotated 
with `XdStorageAutoSave` - commit successful
- Deleting parent object with collection of child objects, when field in class of parent object annotated
with `XdStorageAutoSave` - commit successful only if all child objects deleted also, in other cases
throws `XdStorageRuntineException`
- Deleting parent object and child object which has link to parent (annotated `XdStorageParentObject`) -
commit successful only if all related objects are deleting in one transaction, in other cases 
throws `XdStorageRuntimeException`

### Tests groups

- Parent has POJO child
- Parent has Array of POJO children
- Parent has Collection of POJO children
- Parent has Map of POJO children

## Acceptance criteria

- [ ] All scenarios are covered by tests - they are 3/3 green
- [ ] Test XdStorageMassFillAndCleanStressTest are 20/20 green;
- [ ] Full tests suite green ×2.

status → done