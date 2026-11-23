---
name: junit-tester
description: "Design, write, and analyze JUnit tests for the XdStorage repository. Use whenever the user asks to create new tests, audit coverage, hunt bugs through tests, fix broken tests, design test scenarios, or review a test suite's quality. Covers both unit tests (mocked) and integration tests (real storage), the policy-combination test pattern, and the house conventions for test organization."
---

# JUnit tester — XdStorage test design, coverage, and quality

You are the project's test engineer. Your recurring job: design and write correct
tests that exercise the real behavior, not mock paths. Audit coverage against the
requirements doc (FOREIGN_KEYS.md / EMBEDDING.md / task specs). Hunt bugs by
reading tests backwards — what a test asserts reveals what the code promises.
Compile coverage reports that answer the exact questions the maintainer asks
("what's covered?", "what's missing?", "is it green?"). This skill compiles the
patterns proven across the 2.1.0 FK release (4 policy combinations × 2 indexes ×
14 scenarios = 112 policy tests + 251 total FK tests) AND the 2026-11-23
property-based/diagnostic wave (the jqwik seed-model estate, the red-pin triage
ladders, the honest-pin format).

Read [references/test-patterns.md](references/test-patterns.md) before writing any
new test. Read [references/coverage-methodology.md](references/coverage-methodology.md)
before compiling a coverage report. Read [references/house-rules.md](references/house-rules.md)
for the hard-won gotchas. Read
[references/general-recommendations.md](references/general-recommendations.md) for the
abstract, project-portable principles (R1–R12) behind all of these — reason from it
whenever the situation is not covered by the house-specific files. For the
property-based tests (jqwik), the red-pin triage ladders, the honest-pin format, and
the harness lifecycle patterns, read
[references/property-and-harness-patterns.md](references/property-and-harness-patterns.md).

## 0. Ground rules

- **Real storage first.** The single biggest pattern that distinguishes good FK tests
  from broken ones: always use `AbstractForeignKeyBaseSpecificationTest` (real storage)
  unless the test explicitly targets unit-level behavior (cache state machine,
  write-set isolation). The mock-based `AbstractForeignKeyEngineIntegrationTest`
  path is a known failure mode — see the 8 disabled tests in FOREIGN_KEYS.md §9.
- **Never trust a green suite from a piped grep.** The verdict is Maven's own
  `BUILD SUCCESS` / `Tests run: N, Failures: M` lines. A 112-error cascade was
  caused by concurrent builds on the same `target/` — timestamps prove it.
- **Never run two maven builds on one `target/`.** Concurrent builds destroy each
  other's class files. If a full-suite run collapses with `NoClassDefFoundError`
  or codegen-degradation cascades, re-run one failing class in isolation.
- **Unique storage name + path per test run.** The R-50 stale-dir trap: `File.delete`
  legally fails on IDE-held files → storage parses stale disk state → confusing
  first-save failures. Use `UUID.randomUUID()` in the test name.
- **Never commit** unless the user asked in this turn. Foreign WIP in the tree is
  normal; sweeping it into a commit is the recurring trap.

## 1. Test design (before writing code)

### 1.1 Scenario decomposition

Every FK requirement decomposes into **6 mandatory scenario categories** (the "FK-6"
pattern) totaling the **14-test skeleton** (§4.2 — the per-class counts in
test-patterns.md and here must always sum to 14):

| # | Scenario | What it proves | Test name convention |
|---|---|---|---|
| 1a | Parent + children in a Set | Collection-type independence | `*InsertParentWithChildrenSuccess` |
| 1b | Parent + children in Map values | Map-values cascade | `*MapValuesInsertParentWithChildrenSuccess` |
| 1c | Parent + children in an Array | Array cascade | `*ArrayInsertParentWithChildrenSuccess` |
| 1d | Children saved alone (children-only) | Insert-side boundary veto | `*InsertChildrenOnlyThrows` |
| 2a | Parent saved alone (no children) | Parent-only insert succeeds | `*InsertParentOnlySuccess` |
| 2b | Child saved without parent (orphan veto) | FK veto on unsaved parent | `*InsertChildWithoutParentThrows` |
| 3a | Parent deleted with children (veto) | FK veto on parent alone | `*DeleteParentAloneThrows` |
| 3b | One child deleted + parent updated (success) | Mixed diff: remove + keep | `*DeleteOneChildAndUpdateParentSuccess` |
| 3c | One child deleted without parent update (veto) | No-update veto | `*DeleteChildWithoutParentUpdateThrows` |
| 4a | Parent updated: remove one, add one (success) | Mixed diff: remove + insert | `*UpdateParentRemoveOneInsertOneSuccess` |
| 4b | Parent updated: remove one, add one (veto) | Remove without delete | `*UpdateParentRemoveOneInsertOneNoDeleteThrows` |
| 5a | DB-generated IDs (deferred waiter) | Auto-ID resolution at commit | `*AutoIdParentFirstChildrenLaterSuccess` |
| 6a | Manual-ID child without parent (veto) | Pending-fk validation | `*ManualIdChildWithoutParentThrows` |
| 6b | Manual-ID + cascade save (success) | AutoSave + manual-ID combo | `*AutoSaveParentWithManualChildSuccess` |

### 1.2 Policy-combination matrix

Every FK requirement must be tested across **all 4 store-policy combinations** ×
**2 index types**:

| Parent policy | Child policy | BTree test class | Hash test class |
|---|---|---|---|
| StoreAsClassObjects | StoreAsClassObjects | `PolicyParentClassChildClassBTreeTest` | `PolicyParentClassChildClassHashTest` |
| StoreAsClassObjects | StoreAsSingleObject | `PolicyParentClassChildSingleObjectBTreeTest` | `PolicyParentClassChildSingleObjectHashTest` |
| StoreAsSingleObject | StoreAsSingleObject | `PolicyParentSingleObjectChildSingleObjectBTreeTest` | `PolicyParentSingleObjectChildSingleObjectHashTest` |
| StoreAsSingleObject | StoreAsClassObjects | `PolicyParentSingleObjectChildClassBTreeTest` | `PolicyParentSingleObjectChildClassHashTest` |

**Total per scenario**: 4 policies × 2 indexes = **8 test classes** × **14 tests each** = **112 tests**.

### 1.3 Entity class design

Each policy pair needs **2 entity classes** (parent + child) with correct annotations:
- `@XdStorageObjectId` + `@XdStorageObjectFieldProperties(idGeneratorType=DATABASE_GENERATOR)`
  for auto-generated IDs (child classes that get IDs at commit time)
- `@XdStorageObjectId` (bare) for manual IDs (parent classes that get IDs from the user)
- `@XdStorageAutoSave` on the parent's children collection field
- `@XdStorageParentObject` on the child's parent reference field
- `@XdStorageObjectIdIndexType(indexType=BTree, t=64)` or `@XdStorageObjectIdIndexType(indexType=Hash)`

**Critical gotcha**: `StoreAsSingleObject` entities must NOT have
`@XdStorageObjectIdIndexType` — they are file-based, not index-based. The
`validatePendingFks` path reads the references resource, not an index.

## 2. Coverage analysis

### 2.1 The coverage checklist

Before declaring a feature "fully tested", verify against this checklist:

```
□ Children in different collections (Set, Map values, Array)
□ Children are inserted (FK veto for orphan children)
□ Children are deleted (FK veto for orphaned parent reference)
□ Parent updated: part deleted, part inserted (mixed diff)
□ Database generated identifiers (deferred waiter resolves)
□ Manual identifiers (pending-registry validation at phase-1)
□ All 4 store-policy combinations × both index types
□ Nested transactions (FK state survives across commit boundaries)
□ AutoSave cascade boundary (FK tracks cascade-saved children)
□ Duplicate pair rejection (later-wins: delete then insert restores)
□ Concurrent transactions (write-set isolation)
□ Read-through overlay (read-your-own-read)
□ Enable/Disable FK configuration (engine bypass)
□ Embedded (StoreWithParentObject) bypass
```

### 2.2 Compiling coverage

When asked "what's covered?", answer with:

1. **Count**: "N tests, M passing, K skipped, 0 failures"
2. **Scenario matrix**: the table above, with ✅/❌ per cell
3. **Disabled tests**: list each with the reason (P2 = mock infrastructure, P1 = real bug)
4. **Known gaps**: what the checklist reveals is missing
5. **Verdict**: "green 3/3" (the release phrase meaning 3 consecutive green runs)

### 2.3 The 8 disabled tests (FOREIGN_KEYS.md §9)

All 8 use `AbstractForeignKeyEngineIntegrationTest` (mock infrastructure).
The fix pattern: migrate to `AbstractForeignKeyBaseSpecificationTest`.
They represent ~13 scenarios that are theoretically covered by the real-storage
scenarios but are not explicitly pinned as unit-level assertions.

## 3. Bug hunting through tests

### 3.1 Reverse-engineer from test to spec

When the user asks "is this bug real?" or "does this scenario work?", the workflow:

1. Find the test that exercises the path (grep by class name + scenario name).
2. Read the test: what does it assert? What does it assume about the code?
3. Find the corresponding code path (engine → resource → cache → tree).
4. Check if the code path matches the test's assumptions:
   - Does the entity have the correct policy annotations?
   - Does the index type match?
   - Does the FK registration happen before the veto check?
   - Are IDs resolved in the correct order (parent before child)?
5. If the code path diverges from the test's expectation → bug found.

### 3.2 Common bug patterns to check

| Bug pattern | Where to look | Fix |
|---|---|---|
| Key-type mismatch (String vs Long CCE) | `readByParentObjectId`, `readByChildObjectId` | Convert key type before comparison |
| References resource vs index for SingleObject | `isParentPersistedInIndex`, `validatePendingFks` | Use `manager.lockReferencesResource()` for SingleObject |
| Cascade save order (parent before child) | `processOutcomeRelations` INSERT path | Save parent first; cascade handles children |
| Update-diff stale snapshot | `processUpdateParentObjectWithChildren` | The engine reads from the FK tree, not the diff |
| Deferred-ID waiter never fires | `registerChildIdWaiter` + `onNewIdIsSet` | The ObservableService must fire the trigger |
| Write-set not promoted | `commitAllFxPairs` called at the right 2PC phase | Must be after all entity records (POINT 6) |
| Concurrent test interference | Multiple `AbstractForeignKeyBaseSpecificationTest` | Each class uses unique UUID storage name |

### 3.3 The FK veto chain

Every FK veto passes through this chain. A broken veto means the chain is incomplete:

```
storage.save(child) → XdStorageTriggerManager.fire()
  → XdStorageForeignKeyEngine.process(child, INSERT)
    → incomeRelations: resource.insert(parentId, childId)
      → isChildInParentIndex(childId) → false        ← the path discriminator (§7 A2.4)
        → registerPendingFk(parentId, childId)
  → commit: validatePendingFks()
    → pendingFkRegistrations.get(txId)
    → isParentPersistedInIndex(parentId) → false
      → throw XdStorageRuntimeException("Cannot persist FK...")
```

If any link in this chain is missing or miswired, the veto fails silently.

## 4. Test writing patterns

The example-based patterns below are complemented by the PROPERTY patterns
(the jqwik seed-model estate, the integrity comparators) and the DIAGNOSTIC patterns
(the red-pin triage ladders, the honest pins, the one-driver-owned-storage loop) —
[references/property-and-harness-patterns.md](references/property-and-harness-patterns.md).
The rule of thumb: the example-based tests pin the KNOWN contracts; the property tests
GENERATE the unknown shapes; the diagnostic ladders turn a red pin into a named
mechanism. All three layers feed each other.

### 4.1 Real-storage test template

```java
@DisplayName("FK policy: Parent=X, Child=Y (BTree/Hash)")
public class PolicyXxYyBTreeTest extends AbstractForeignKeyBaseSpecificationTest {

    @Test
    @DisplayName("1a. Scenario name")
    void testScenario() throws Exception {
        tx = storage.beginTransaction();
        Parent parent = new Parent();
        Child child = new Child();
        child.setParent(parent);
        parent.addChild(child);
        storage.save(parent);
        assertDoesNotThrow(() -> storage.commitTransaction(tx));
        assertNotNull(parent.getId());
        assertNotNull(child.getId());
    }

    @Test
    @DisplayName("2a. Veto scenario")
    void testVetoScenario() throws Exception {
        tx = storage.beginTransaction();
        Parent parent = new Parent();
        Child child = new Child();
        child.setParent(parent);
        parent.addChild(child);
        storage.save(child);  // parent not saved first
        assertThrows(XdStorageRuntimeException.class,
            () -> storage.commitTransaction(tx));
    }
}
```

### 4.2 The 14-test skeleton per class

Every policy class MUST have these 14 tests (renamed per scenario):
1. 1a: Insert parent + children (Set) → success
2. 1b: Insert parent + children (Map) → success
3. 1c: Insert parent + children (Array) → success
4. 1d: Insert children-only → veto
5. 2a: Insert parent-only → success
6. 2b: Insert child without parent → veto
7. 3a: Delete parent alone → veto
8. 3b: Delete one child + update parent → success
9. 3c: Delete one child without update → veto
10. 4a: Update parent: remove one, insert one → success
11. 4b: Update parent: remove one, insert one → veto (no delete)
12. 5a: Auto-ID parent + child → success
13. 6a: Manual-ID child without parent → veto
14. 6b: Manual-ID child + cascade save → success

### 4.3 Mock tests (cache/state machine)

Only the FK write-set (`XdStorageForeignKeyCache`) and pair state machine
(`XdStorageForeignKeyCacheRecord`) need mock-based tests. Use `AbstractForeignKeyEngineIntegrationTest`
or plain mocks for these — they test **state transitions**, not persistence.

## 5. Coverage reporting

When compiling a coverage report, the format is:

```
## Foreign Keys Coverage Report — <date>

### Test estate
- Total: 251 (243 passing, 8 skipped, 0 failures)
- Quick-gate: 1193 tests, BUILD SUCCESS
- Policy tests: 8 classes × 14 tests = 112 (all green)
- Scenario tests: 20 classes × ~8 tests = 139 (all green)
- Unit tests: 10 classes × ~10 tests = 100 (all green)

### Scenario matrix
| Scenario | BTree | Hash | Notes |
|---|---|---|---|
| Children in collections | ✅ 24 | ✅ 24 | Set, Map, array |
| Children inserted (veto) | ✅ 4 | ✅ 4 | Orphan veto |
| Children deleted (veto) | ✅ 4 | ✅ 4 | Parent veto |
| Parent updated (mixed) | ✅ 4 | ✅ 4 | Remove+insert |
| DB-generated IDs | ✅ 2 | ✅ 2 | Deferred waiter |
| Manual IDs | ✅ 2 | ✅ 2 | Pending validation |

### Disabled tests (8)
| File | Tests | Reason | Priority |
|---|---|---|---|
| SingleEntityForeignKeySpecificationTest | 7 | Mock infra | P2 |
| ... | ... | ... | ... |

### Known bugs (P1)
1. FK key-type restoration (A2.3) — String vs Long CCE
2. FK-cost benchmark unmeasured

### Recommendations
1. ...
2. ...
```

## 6. House rules inherited from architect-analysis

- **Verdict** = Maven's own `BUILD SUCCESS` / `Tests run: N` (never grep exit code).
- **Unique storage name** per test run (UUID in `@BeforeAll` / `@BeforeEach`).
- **Never run two maven builds on one `target/`** — the 112-error cascade.
- **All performance claims** cite BENCHMARKS.md canonical baseline.
- **Rejected alternatives** are deliverables — write them as companion files.
- **Scope discipline**: FK tests stay within the FK feature boundary; no scope creep.

## 7. The release checklist (green ×3)

Before a release, the maintainer asks "are all FK tests green?" — the answer must
be verified by:

```bash
# 3 consecutive green runs (the release phrase)
mvn clean test -Dtest="io.github.xdstorage.foreignkey.**.*Test" -Pquick-gate
mvn clean test -Dtest="io.github.xdstorage.foreignkey.**.*Test" -Pquick-gate
mvn clean test -Dtest="io.github.xdstorage.foreignkey.**.*Test" -Pquick-gate
```

Each run must show `Tests run: N, Failures: 0, Errors: 0` and `BUILD SUCCESS`.
The 8 skipped tests are **expected** (documented disabled tests) — they must be
listed as skipped, not failed. If any test fails that is NOT in the disabled list,
that is a release-blocker.
