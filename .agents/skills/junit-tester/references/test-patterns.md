# Test patterns — XdStorage JUnit test design

## The FK-6 scenario pattern (mandatory for every FK feature)

Every FK requirement decomposes into 6 sub-scenarios. This is the **FK-6 pattern**:

### 1. Collection-type independence (3 tests)

```java
@Test void testSetInsertParentWithChildrenSuccess() {
    // Parent with children in HashSet — must succeed
}
@Test void testMapValuesInsertParentWithChildrenSuccess() {
    // Parent with children in HashMap values — must succeed
}
@Test void testArrayInsertParentWithChildrenSuccess() {
    // Parent with children in array — must succeed
}
```

**Why**: The FK engine handles `Collection<?>`, `Map<?, ?>`, and `Object[]` via
different code paths. A bug in one path won't be caught by testing only one.

### 2. Insert-side boundaries (3 tests)

```java
@Test void testInsertParentOnlySuccess() {
    // Parent saved alone (no children yet) → plain insert, commit succeeds
}
@Test void testSaveChildWithoutParentThrows() {
    // Child has @XdStorageParentObject, parent not saved → veto at commit
}
@Test void testSaveChildrenWithoutParentThrows() {
    // Multiple children, parent not saved → all veto
}
```

**Why**: The FK engine must catch "save a child without its parent" at commit
time, not at save time (the parent might be saved in the same transaction). The
parent-only success pins the boundary: no children = no FK work, no false veto.

### 3. Parent veto (3 tests)

```java
@Test void testDeleteParentAloneThrows() {
    // Parent has children, delete parent alone → veto
}
@Test void testDeleteOneChildAndUpdateParentSuccess() {
    // Delete child1 AND remove it from the parent's collection → commit succeeds
}
@Test void testDeleteChildWithoutParentUpdateThrows() {
    // Delete child, don't update parent's collection → veto
}
```

**Why**: The FK veto on the parent side is the harder path — it must track
both the forward index (parent→children) and the reverse index (child→parent).

### 4. Mixed diff (2 tests)

```java
@Test void testUpdateParentRemoveOneInsertOneSuccess() {
    // Remove child1, add child3 → commit succeeds (child1 deleted first)
}
@Test void testUpdateParentRemoveOneInsertOneNoDeleteThrows() {
    // Remove child1, add child3, DON'T delete child1 → veto
}
```

**Why**: This is the hardest FK scenario — the engine must handle a parent update
that both removes and adds children in the same transaction.

### 5. Deferred ID (1 test)

```java
@Test void testAutoIdParentFirstChildrenLaterSuccess() {
    // Save parent (gets auto-ID), then save child → FK resolves at commit
}
```

**Why**: The child's `@XdStorageObjectId` with `DATABASE_GENERATOR` means the child
gets its ID from the DB. The FK engine must wait for this ID to land before
registering the pair.

### 6. Manual ID (2 tests)

```java
@Test void testManualIdChildWithoutParentThrows() {
    // Child has manual ID, parent not saved → veto
}
@Test void testAutoSaveParentWithManualChildSuccess() {
    // Parent has @XdStorageAutoSave, child has manual ID → cascade saves both
}
```

**Why**: Manual IDs must be validated at commit time via `validatePendingFks()`.
The pending-fk map must be cleared when the parent is saved in the same tx.

## Policy combination rules

### StoreAsClassObjects + StoreAsClassObjects (standard)
- Parent: `@XdStorageObjectId` + `@XdStorageObjectIdIndexType(BTree|Hash)`
- Child: `@XdStorageObjectId` + `@XdStorageObjectIdIndexType(BTree|Hash)` + `DATABASE_GENERATOR`
- Test: both entities get auto-generated IDs at commit

### StoreAsClassObjects + StoreAsSingleObject
- Parent: `@XdStorageObjectId` + `@XdStorageObjectIdIndexType(BTree|Hash)`
- Child: `@XdStorageObjectId` (bare, no index) — **NO `@XdStorageObjectIdIndexType`**
- Test: child is file-based; `validatePendingFks` reads references resource

### StoreAsSingleObject + StoreAsSingleObject
- Parent: `@XdStorageObjectId` (bare) — **NO `@XdStorageObjectIdIndexType`**
- Child: `@XdStorageObjectId` (bare) — **NO `@XdStorageObjectIdIndexType`**
- Test: both file-based; FK validation reads references resource for BOTH sides

### StoreAsSingleObject + StoreAsClassObjects
- Parent: `@XdStorageObjectId` (bare) — **NO `@XdStorageObjectIdIndexType`**
- Child: `@XdStorageObjectId` + `@XdStorageObjectIdIndexType(BTree|Hash)`
- Test: parent is file-based, child has index; `validatePendingFks` reads
  references resource for parent, index for child

## The critical annotation gotchas

| Annotation | Correct use | Bug if wrong |
|---|---|---|
| `@XdStorageObjectIdIndexType` | Only on `StoreAsClassObjects` | NPE when `validatePendingFks` tries `lockIndexResource` on SingleObject |
| `@XdStorageObjectFieldProperties(idGeneratorType=DATABASE_GENERATOR)` | On child IDs that get IDs at commit | Child keeps null ID → FK veto "parent does not exist" |
| `@XdStorageAutoSave` | On parent's children field | Children not saved → FK veto "child not persisted" |
| `@XdStorageParentObject` | On child's parent reference | No income relation registered → FK never fires for this child |

## The test file structure

```
src/main/test/io/github/xdstorage/foreignkey/policies/
├── btree/
│   ├── ParentClassChildClass/
│   │   ├── PolicyParentClassChildClass.java        ← parent entity
│   │   ├── PolicyChildClass.java                    ← child entity
│   │   └── PolicyParentClassChildClassBTreeTest.java ← 14 tests
│   ├── ParentClassChildSingleObject/
│   │   ├── PolicyParentClassChildSingleObject.java
│   │   ├── PolicyChildSingleObject.java
│   │   └── PolicyParentClassChildSingleObjectBTreeTest.java
│   ├── ParentSingleObjectChildSingleObject/
│   │   ├── PolicyParentSingleObjectChildSingleObject.java
│   │   ├── PolicyChildSingleObject.java
│   │   └── PolicyParentSingleObjectChildSingleObjectBTreeTest.java
│   └── ParentSingleObjectChildClass/
│       ├── PolicyParentSingleObjectChildClass.java
│       ├── PolicyChildClass.java
│       └── PolicyParentSingleObjectChildClassBTreeTest.java
├── hash/
│   ├── (same structure as btree/)
│   └── PolicyParentClassChildClassHashTest.java
```

## The test class template

```java
package io.github.xdstorage.foreignkey.policies.btree.ParentClassChildClass;

import io.github.xdstorage.entities.foreignkeys.policies.btree.ParentClassChildClass.PolicyParentClassChildClass;
import io.github.xdstorage.entities.foreignkeys.policies.btree.ParentClassChildClass.PolicyChildClass;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.foreignkey.index.btree.AbstractForeignKeyBaseSpecificationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FK Store Policies (BTree): Parent=ClassObjects, Child=ClassObjects")
public class PolicyParentClassChildClassBTreeTest extends AbstractForeignKeyBaseSpecificationTest {

    @Test
    @DisplayName("1a. Вставка родителя с детьми в Set — Успешный коммит")
    void testSetInsertParentWithChildrenSuccess() throws Exception {
        tx = storage.beginTransaction();
        PolicyParentClassChildClass parent = new PolicyParentClassChildClass();
        parent.setChildren(new HashSet<>());
        PolicyChildClass child1 = new PolicyChildClass(); child1.setParent(parent); parent.addChild(child1);
        PolicyChildClass child2 = new PolicyChildClass(); child2.setParent(parent); parent.addChild(child2);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
    }

    @Test
    @DisplayName("6b. Parent с AutoSave + Auto-ID child — Успешно")
    void testAutoSaveParentWithAutoIdChildSuccess() throws Exception {
        tx = storage.beginTransaction();
        PolicyParentClassChildClass parent = new PolicyParentClassChildClass();
        PolicyChildClass child = new PolicyChildClass();
        child.setParent(parent);
        parent.addChild(child);
        storage.save(parent);  // cascade saves child with auto-generated ID
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
        assertNotNull(child.getId());
    }
}
```

## The 251-test breakdown

| Category | Count | Files |
|---|---|---|
| Policy tests (new) | 112 | 8 classes × 14 tests |
| Save scenarios | 8 | 2 × 4 |
| Delete scenarios | 8 | 2 × 4 |
| Update scenarios | 12 | 2 × 6 |
| Different collections | 48 | 2 × 24 |
| AutoId insertion/deletion | 4 | 4 × 1 |
| ManualId specification | 12 | 2 × 6 |
| ManualId deletion | 4 | 2 × 2 |
| Reference scenarios | 2 | 2 × 1 |
| Registry test | 2 | 1 × 2 |
| AutoSave test | 2 | 1 × 2 |
| Deletion test (real storage) | 3 | 1 × 3 |
| ParentObject test | 1 | 1 × 1 |
| Nested transactions | 1 | 1 × 1 |
| Cache unit tests | 10 | 1 × 10 |
| **Total** | **251** | |
