# Coverage methodology — XdStorage test coverage

## How to compile a coverage report

When the user asks "what's covered?" or "compile coverage", the workflow:

### Step 1: Count tests

```bash
# FK tests (run from the repo root)
cd /home/evgeny/Java/projects/xdstore
mvn test -Dtest="io.github.xdstorage.foreignkey.**.*Test" -Pquick-gate 2>&1 | \
  grep "Tests run:" | tail -1

# Full quick-gate
mvn test -Pquick-gate 2>&1 | grep "BUILD SUCCESS\|Tests run:" | tail -2
```

### Step 2: Scenario matrix

For each scenario, check if there are tests covering all policy combinations:

| Scenario | BTree | Hash | Coverage |
|---|---|---|---|
| Children in collections | ✅ 24 | ✅ 24 | Complete |
| Children inserted | ✅ 4 | ✅ 4 | Complete |
| Children deleted | ✅ 4 | ✅ 4 | Complete |
| Parent updated | ✅ 6 | ✅ 6 | Complete |
| Auto-ID | ✅ 2 | ✅ 2 | Complete |
| Manual-ID | ✅ 2 | ✅ 2 | Complete |
| Nested tx | ✅ 1 | — | Partial |
| AutoSave | ✅ 2 | — | Partial |
| Deletion | ✅ 3 | — | Partial |
| Manual-ID deletion | ✅ 2 | ✅ 2 | Complete |
| Auto-ID deletion | ✅ 2 | ✅ 2 | Complete |
| Registry | ✅ 2 | — | Partial |
| Cache (unit) | ✅ 10 | — | Partial |

### Step 3: Disabled tests

List each disabled test with its reason and priority:

```markdown
### Disabled tests (8)
| File | Tests | Reason | Priority |
|---|---|---|---|
| SingleEntityForeignKeySpecificationTest | 7 | Mock infra (null resources manager) | P2 |
| XdStorageForeignKeyInsertionTest | 2 | Mock infra | P2 |
| OutcomeRelationsForeignKeyTest | 2/5 | Broken test — UPDATE/DELETE paths | P2 |
| IncomeAndObservabilityForeignKeyTest | 2/3 | Broken + wrong test | P2 |
| XdStorageForeignKeyResourceTest | 1/3 | Broken test — delete-from-both-trees | P2 |
| StructuralGroupsForeignKeySpecificationTest | 1/4 | Needs real storage | P2 |
```

### Step 4: Known bugs

List each known bug with severity and fix location:

```markdown
### Known bugs (P1)
1. FK key-type restoration (A2.3) — String vs Long CCE
   Location: XdStorageForeignKeyResource.readByParentObjectId()
   Severity: Would crash at runtime for numeric-ID entities

2. FK-cost benchmark unmeasured
   Location: task 50-fk-cost-benchmark.md
   Severity: Cannot quantify production impact
```

### Step 5: Recommendations

Prioritized by severity:

```markdown
### Recommendations
| Priority | Item | Action |
|---|---|---|
| P1 | FK key-type restoration | Convert String keys to correct type |
| P1 | FK-cost benchmark | Measure and document |
| P2 | Move 8 disabled tests to real storage | Migrate to AbstractForeignKeyBaseSpecificationTest |
| P2 | Rename isChildInParentIndex | → isChildIdInParentIndex |
| P2 | FK + AutoSave cycle test | Add explicit test |
| P3 | validatePendingFks batching | Batch lookups when >1 pending |
| P3 | Embedded FK bypass test | Add assertion |
```

## The green ×3 release phrase

"Green ×3" means 3 consecutive green runs of the relevant test suite.
The release checklist:

```bash
mvn clean test -Dtest="io.github.xdstorage.foreignkey.**.*Test" -Pquick-gate
# Verify: Tests run: N, Failures: 0, Errors: 0, Skipped: 8, BUILD SUCCESS

mvn clean test -Dtest="io.github.xdstorage.foreignkey.**.*Test" -Pquick-gate
# Same verdict

mvn clean test -Dtest="io.github.xdstorage.foreignkey.**.*Test" -Pquick-gate
# Same verdict → green ×3 ✓
```

The 8 skipped tests are **expected and documented** — they must be listed as
skipped in the report, not counted as failures.

## What "full coverage" means

A feature is "fully tested" when:
1. ✅ All 4 policy combinations × 2 indexes × 14 scenarios = 112 policy tests pass
2. ✅ All scenario tests (save/delete/update/diff) pass
3. ✅ All unit tests (cache, state machine) pass
4. ✅ 0 failures, 0 errors
5. ✅ 8 disabled tests are documented with reasons
6. ✅ All P1 bugs are acknowledged (fixed or scheduled)
7. ✅ Quick-gate full suite passes (1193 tests)

If any of these conditions fails, the feature is **not fully covered**.
