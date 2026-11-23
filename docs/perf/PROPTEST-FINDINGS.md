# PROPTEST findings compilation (task 03, 2026-11-23) — for the grouping discussion

The property-based campaign (Этапы 0–4) is GREEN: **13 tests / 0 failures / 2 honest
pins** in the quick-gate. Along the way the generated shapes found and/or pinned the
following — compiled here for the grouping discussion (no tasks filed per the user's
directive; the groups will be discussed).

## The engine DEFECTS found and FIXED by the properties (each pinned by its property)

| # | The defect | The shape that found it | The fix | The pin |
|---|---|---|---|---|
| 1 | **Null map VALUES silently dropped from persistence** (the unwrap-and-clone path dropped the entry before the writer; the YAML writer/reader both support null values) | the CRUD round-trip property: `{k=null}` maps came back empty/minimal across ALL policies and ALL flows (round-trip, update, the refused-duplicate) | `XdStorageClonerCollectionProcessor.unwrapAndCloneMap`: only a null KEY drops the entry (the mirror of the twin cloneMap) | P1.1 CRUD round-trip (the meta field is in the model) |
| 2 | **Primitive-typed arrays (int[], long[], ...) made the resource UNREADABLE after the reopen** (`ClassNotFoundException: int` — the array block stores the RAW component name, the reader resolved it via the class registry) | the container-jungle property: `int[]` round trip | `readArray` resolves the primitive Class directly (`primitiveClassOrNull`) | P2.1 the container jungle |
| 3 | **Nested GENERIC collections (List<List<T>>, List<Map<K,V>>) were silently DROPPED from persistence** (buildFieldInfo cast the element arg to Class — a ParameterizedType threw ClassCastException — the scanner's silent catch dropped the whole field) | the container-jungle property: deepList/mapList absent from the WRITTEN file | `buildFieldInfo` handles the ParameterizedType element arg (getRawType — the mirror of the Map branch) | P2.1 the container jungle |
| 4 | **Nested-container ITEMS were written as empty object blocks** (writeComplexItemInline routed List/Map/array items into the object branch — an empty object block — the reader restored empty containers) | the container-jungle property: listMap values `[]` instead of `[7, 8]` | writeComplexItemInline recurses into the collection/map/array blocks (the reader's readTypedNode already recursed) | P2.1 + the XxJungleDiag file-truth probe |

## The CONTRACT asymmetries found (the decisions needed, not the fixes)

| # | The asymmetry | The current behavior | The decision needed |
|---|---|---|---|
| 5 | **explain works WITHOUT a transaction** — the ONLY tx-bound facade method that does (load/count refuse loudly) | explain returns a plan silently on the no-tx path | either explain joins the refusals, or the docs declare it tx-free (the facade-audit task 07's finding table format) |
| 6 | **The mixed-type range bounds fail loudly** (the DESIGNED behavior from task 33's pre-flight) — the test harness must use the typed bounds (Long vs Integer) | the designed pre-flight loud refusal | none — the designed behavior CONFIRMED working (a positive finding) |

## The OPEN harness questions (the red pins, honest)

| # | The question | The observed behavior | The next step |
|---|---|---|---|
| 7 | **The FK cascade flow on a FRESH instance**: save(parent-with-children) → commit → load(parent) returns NULL (even in-storage), the FK engine's treeParentChild NPEs on the next op — while the SAME shape passes in the FK estate (ForeignKeyUpdateTest) | the probe: save committed, probe-load NULL at 0.05s | the triage = its own session (the config diff vs the estate, the deferred-id/FK registration on the fresh-instance path); the estate's example tests pin the contracts meanwhile |
| 8 | **The provider lifecycle in the property loops**: the reopen-burn pattern hits "storage pt-id-reopen-N is shut down" after the reassignment (the old instance vs the registry) | the wave loop's post-reopen save failed | the lifecycle discipline (one driver-owned storage, the explicit reopen re-registration) — its own pass |

## The infrastructure landed

- **jqwik 1.9.2** (test-scope; the Java 8 + JUnit Platform 1.x compatible).
- **The seed-model pattern**: every entity/sequence is a pure function of its integer seed
  — the failing seed reproduces exactly; jqwik shrinks the seed.
- **7 property classes / 13 tests**: the CRUD round-trip × 3 policies (+ reopen, the
  duplicate refusal, the update round-trip, the missing-id null), the B+tree random
  sequences, the journal replay equivalence (the copy-replay = the checkpoint = the
  direct path), the MVCC snapshot/FCW choreography, the container jungle, the scalar
  matrix, the no-tx facade sweep (16 methods), the count≡load.size + the
  plan/exec + the reopen + verify, the FK veto/cascade (PINNED-RED, #7), the id
  never-reissue (PINNED-RED, #8).
- The runtime: ~13 s total in the quick-gate — the budget respected.
