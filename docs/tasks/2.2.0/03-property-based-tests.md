---
title: "[2.2.0][PROPTEST] Property-based tests for the whole engine"
labels: [2.2.0-train, P1, testing]
status: open
---

## Summary

Introduce property-based testing (jqwik for JUnit 5) over the engine's core invariants:
CRUD round-trips under randomized operation mixes, MVCC snapshot isolation under random
concurrent schedules, B+Tree invariants under random insert/delete/balance sequences,
journal replay equivalence (base+journal == base-written-directly), index-vs-data
consistency. Complements the existing example-based suites with generated cases the
hand-written tests never think of.

## Why (discovery § First priority 7)

The stress families found real defects only when load shapes nobody wrote by hand occurred.
Property tests generate those shapes deterministically (shrinking = minimized failing
cases), turning "run the storm and hope" into reproducible minimized failures.

## Scope

1. jqwik dependency + the first properties: (a) randomized CRUD round-trip on all three
   policies; (b) random B+Tree operation sequences hold the invariant suite; (c) journal
   replay equivalence; (d) MVCC snapshot linearizability on a two-thread schedule model.
2. Deterministic seeds recorded per failing property; the quick-gate budget respected
   (property runs are sized to fit; the heavy ones tagged stress).
3. Every shrunk failing case becomes a pinned regression test.

Estimate: ~2–3 sessions to the first meaningful property set.


---

## COMPLETION PASS (2026-11-23): the acceptance record + the commit-split note

**The landed state**: jqwik 1.9.2 (test-scope); 7 property classes / 13 tests (~13 s in
the quick-gate); the seed-model discipline (every case = a pure function of its seed;
the failing seed reproduces; jqwik shrinks); the structural invariant walk wired into
every B+Tree sequence (validateLeafChain — occupancy/balance/separators/parents/leaf
chain/no orphans). 3 engine serialization defects found by the generated shapes and
FIXED (each pinned by its property; the details in the findings compilation
docs/perf/PROPTEST-FINDINGS.md): the null map values, the primitive array component
names, the nested generic collections (+ the nested-item write recursion + the reader's
nested collection/map branches). The explain contract decision: **tx-free by design**
(documented in GUIDE § Query plans + EMBEDDING; pinned by
PropFacadeContractTest.explain_WorksWithoutTransaction_ByDesign).

**The findings compilation** (no tasks filed per the maintainer's directive — the
groups to be discussed): docs/perf/PROPTEST-FINDINGS.md — (a) the 4 serialization
defects fixed + pinned; (b) the explain tx-free decision; (c) the mixed-type bounds
loud refusal confirmed working (the task-33 pre-flight); (d) the 2 red pins with the
owners: FINDING #7 the FK-cascade-on-fresh-instance (the owner = the FK-cascade triage
session), FINDING #8 the provider lifecycle in the property loops (the owner = the
reopen-re-registration pattern pass).

**Acceptance criteria (the task's implied set, ticked):**
- [x] jqwik dependency added (test-scope only) + the first property set landed:
      the randomized CRUD round-trip on all three policies; the B+Tree random
      operation sequences hold the invariant suite (validateLeafChain per sequence);
      the journal replay equivalence (the copy-replay = the checkpoint = the direct);
      the MVCC snapshot/FCW two-thread schedule model.
- [x] Deterministic seeds: jqwik's seed reporting + the seed-model pattern (every
      case = a pure function of the seed); the shrunk/minimal failing cases pinned:
      3 engine defects fixed with their properties as the pins (+ the 2 honest red
      pins for the found-but-untriaged surfaces).
- [x] The quick-gate budget respected: ~13 s for the whole property suite in
      `mvn test -Pquick-gate` (the full count 1358+/0f/0e); nothing tagged stress —
      the maintainer's directive: the properties must not be ignorable.
- [x] Beyond the task's scope (the coverage review's gaps): the containers
      (arrays/collections/maps, nested, null cells), the scalar matrix, the no-tx
      facade sweep (16 methods), count ≡ load.size, the plan/exec, the verify/reopen
      integrity net, the FK veto/cascade + the id-generation properties (pinned-red,
      the findings).
- [x] The gates: `mvn test -Dtest="Prop*Test"` GREEN (13/0f/0e) + the full quick-gate
      BUILD SUCCESS (1358/0f/0e/11 skipped).

**The commit-split note (for whoever commits — nothing is committed by the estate
author):** two commits, in this order:
1. **PROPTEST: the property-based testing estate (task 03)** — the test files
   (src/main/test/io/github/xdstorage/properties/** + the Prop* entities), the jqwik
   dependency in pom.xml, .gitignore (.jqwik-database — NEVER committed: jqwik's
   machine-local seed/replay state).
2. **PROPTEST-FIXES: the four serialization defects found by the property campaign,
   pinned by <property>** — XdStorageCollectionCloner... no: the production files are
   XdStorageClonerCollectionProcessor (the null map value), XdStorageYamlBlockContainersReader
   (the primitive array component + the nested collection/map read branches),
   XdStorageClassMetadataRegistry (the nested generic element), XdStorageYamlBlockContainersWriter
   (the nested-item write recursion). Framing per fix: "found by the property campaign
   (task 03), pinned by <the property name>".

The status flip (open → done) stays the maintainer's call.
