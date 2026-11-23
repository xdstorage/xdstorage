# Property-based tests, diagnostic ladders, and the harness patterns (the 2026-11-23 wave)

The patterns proven by the PROPTEST campaign (task 03: 7 jqwik classes / 13 properties)
and the red-pin triage wave (tasks 28/30). They extend the FK-6 example-based pattern —
the properties generate the shapes the hand-written tests never think of, and the
diagnostic ladders turn a red pin into a named mechanism.

---

## 1. The SEED-MODEL pattern (jqwik property tests)

**Every case is a pure function of its integer seed.** The jqwik Arbitrary shrinks the
seed; the failing seed reproduces exactly; the field-by-field comparator is the
integrity gate.

```java
@Property(tries = 60)
void crudRoundTrip_RestoresEveryField_OnEveryPolicy(@ForAll final int seed,
        @ForAll @IntRange(min = 0, max = 2) final int arm) throws Exception {
    final PropWideLike original = entityFor(arm, seed);   // the model: factory(seed)
    ... save → commit → load ...
    PropModel.assertFieldEquals(original, loaded, arm + " round-trip");  // the integrity gate
}
```

**The rules:**
- The model AND the expected content are built from the same seed — the comparator has
  no independent source of truth to drift.
- The comparator is FIELD-BY-FIELD with the aggregated divergence report (a list of all
  the diverging fields in one assertion failure — failing on the first field hides the
  rest).
- The value ranges avoid the KNOWN format edges ON PURPOSE and the exclusions are
  documented in the generator (single-line strings — the YAML block format is
  line-based; finite doubles — NaN/Infinity have no scalar form; non-null map KEYS —
  the documented drop). Each exclusion = a separate pinned edge test (Этап 2's matrix).
- jqwik lifecycle: `@AfterTry` from `net.jqwik.api.lifecycle` (the JUnit `@AfterEach`
  does NOT fire per-try on a jqwik property).
- The edge-cases mode MIXIN injects the boundary seeds (MIN_VALUE, 0) FIRST — the
  edge-case failures are the real findings, not noise.

---

## 2. The veto-phase choreography (the FK vetoes fire at the COMMIT)

The FK vetoes (the duplicate pair, the referenced child, the parent with children) fire
**at `commitTransaction`** — the phase-1 FK validation runs when the deletion/update is
PROCESSED, not when the delete/update call is made. The CANON (2026-11-20, the
deletion test): delete(child) → update(parent) → `assertThrows(commit)`.

```java
// WRONG: the assert on the delete call — the delete only STAGES, nothing thrown
assertThrows(XdStorageRuntimeException.class, () -> storage.delete(child, vetoTx));

// RIGHT: the delete stages, the veto fires at the commit
storage.delete(child, vetoTx);
final XdStorageRuntimeException veto = assertThrows(XdStorageRuntimeException.class,
        () -> storage.commitTransaction(vetoTx), "the veto must refuse at the commit");
storage.rollbackTransaction(vetoTx);
```

The phase discipline applies to EVERY commit-time check (the FK vetoes, the FCW
conflicts, the commit-time revalidation): **assert the refusal at the phase that owns
it.** An assert on the wrong phase produced the PROPTEST campaign's own false failures
(the "nothing was thrown" at the commit when the refusal had already fired at the
update — and vice versa).

After a refused commit: `rollbackTransaction` — the reader's tx holds nothing staged
after the refusal (its commit would be a quiet no-op).

---

## 3. The no-transaction sweep (the method-table pattern)

The facade-wide contract sweep: the method table (name → the no-tx invocation) + the
throwing functional interface + the refusal classification.

```java
private interface ThrowingCall { void run() throws Exception; }

final Map<String, ThrowingCall> methods = new HashMap<>();
methods.put("save(obj)", () -> storage.save(entity));
methods.put("load(search)", () -> storage.load(Cl.class, "idx", query));
... // every tx-bound facade method

for (final Map.Entry<String, ThrowingCall> entry : methods.entrySet()) {
    try {
        entry.getValue().run();
        silent.add(entry.getKey());   // a silent method = the contract breach
    } catch (final Exception refused) {
        // the LOUD classification: the checked XdStorageException, its runtime wrapper,
        // or the null-guard NPE — all of them are the designed refusals
        if (!(refused instanceof XdStorageException)
                && !(refused instanceof XdStorageRuntimeException)
                && !(refused instanceof NullPointerException)) {
            silent.add(entry.getKey() + " threw " + refused.getClass().getSimpleName());
        }
    }
}
assertTrue(silent.isEmpty(), "the tx-bound methods must refuse: silent = " + silent);
```

**The traps:**
- The lambdas as `Runnable` cannot throw the checked exceptions — the custom
  `ThrowingCall` interface carries the `throws Exception` through.
- The checked `XdStorageException` can NEVER appear in a
  `catch (RuntimeException ...)` — the compiler rejects the impossible instanceof.
- A method EXCLUDED from the sweep (the explain tx-free-by-design exception) carries
  the decision comment AT THE EXCLUSION SITE — an unexplained gap reads as an
  oversight.

---

## 4. The measurement/benchmark integrity gate

A measurement (a benchmark, a profile driver, an experiment) that cannot prove it
touched the right data proves nothing. EVERY measured loop asserts its data:

- the field-by-field equality against the deterministic model (built from the same
  seed), all N — not a sample;
- every lookup's payload asserted (not just non-null);
- the file truth asserted where the durability is the claim (the bases magic-verified,
  the fragment files present, the marker upgraded).

The cyclic-op shape for the destructive ops: a pure one-shot delete drains the pool
and kills the trial — the delete+restore cycle (BOTH arms pay the same restore). The
restore entry: a NEW id + the POOL ENTRY REPLACED (the same-id reinsert save hits "is
exists" — the unique invariant has no overlay on the plain path).

The concurrent workers MUST retry the designed conflicts (the FCW refusals — a real
workload retries); the conflict counter is itself the contention evidence (the
hot-worker concentration = the designed response working).

---

## 5. The red-pin triage ladder (a disabled/failing pin)

1. **Re-enable and run ONE pin; read WHICH assert fails.** The disable-note's
   mechanism is a hypothesis, not a fact (both W-4 pins' aliasing notes were wrong).
2. **The stash-check against HEAD**: identical failure on HEAD = pre-existing;
   different = the wave's regression.
3. **The isolate-the-trigger ladder**: (a) the plain op, (b) the variant, (c) the
   reference-loaded-vs-stub, (d) the repeat — one variable at a time.
4. **The green-pair control**: run the GREEN estate's entity pair through the same
   ladder — if the green pair fails too, the suspicion moves to the contract/config
   (the FK veto lived in the deletion test's CANON comment; grep the existing suites
   FIRST).
5. **grep the run's WARN/ERROR lines BEFORE theorizing** — the engine announces what
   it drops (the JavaBeans field-drop WARN was in every run before anyone read it).
6. **Fix the test, not the engine** when the engine's documented contract is the
   cause (the setChildren JavaBeans fix — the MassFillSecond pattern); file the engine
   issue ONLY with a proven mechanism.
7. **The probe promotion**: the diagnostic driver (the XxFkDiag-style file-truth +
   the index dumps) graduates into the permanent pin — the probe IS the regression
   test for the found mechanism.

---

## 6. The honest pin format (a skipped test = documented debt)

```java
@org.junit.jupiter.api.Disabled("FINDING #7 (PROPTEST Этап 4): <the mechanism, one "
    + "sentence — what breaks and the observed signature>. OWNER: <the session/pass "
    + "that owns the triage or fix>. DISPOSITION: documented debt — a skipped test, "
    + "never a silent skip.")
```

The three mandatory clauses: the MECHANISM (what breaks, the observed signature), the
OWNER (who/what pass resolves it), the DISPOSITION (the explicit statement that this
is tracked debt). An @Disabled without all three is a silent skip wearing a pin.

When the pin un-reds: remove @Disabled, keep the TRIAGE comment (the date + the
result), record the resolution in the task file.

---

## 7. The one-driver-owned-storage pattern (the property/harness loops)

The reopen-burn lifecycle: the reopen helper RETURNS the live reopened instance, the
caller REASSIGNS immediately, and EVERY subsequent op — the tx objects INCLUDED — goes
through the CURRENT reference.

```java
private IXdFileStorage reopenAndCollect(final IXdFileStorage storage, final File dir, final int extraSaves) {
    final IXdFileStorage reopened = XdStorageProvider.newOrGetFileStorage(...);
    ... saves ...
    return reopened;   // LIVE — never shut down before returning
}
// the caller:
storage = reopenAndCollect(storage, dir, n);   // REASSIGN immediately
final IXdStorageTransaction tx = storage.beginTransaction();  // from the CURRENT reference
```

The traps (each cost a cycle): the helper shut the fresh storage down before
returning (the caller used a dead instance); the tx object created from the OLD
reference while the save went to the reopened one; a load-only session holds the
folder lock — the reopen rounds of the same folder block forever on it (shut the fill
session down before the rounds).

---

## 8. The Java compilation traps for the test code (each cost a compile cycle)

- The nested test-entity classes cannot be runtime-compiled (the wrapper generation)
  — ALL entity classes are top-level files.
- The entity fields are PRIVATE with the getter/setter pairs — a public field without
  the JavaBeans pairs is DROPPED from persistence (the scanner WARN was in every run).
- `var` is Java 10+ — the house bytecode is release=8 (the explicit types).
- The switch arrow / multi-label forms are Java 14+ (the classic switch).
- `List.of`/`Set.of` are Java 9+ — `Arrays.asList` on Java 8.
- `Files.newBufferWriter` does not exist — `Files.newBufferedWriter`.
- A checked exception cannot appear in a `catch (RuntimeException ...)` (the
  impossible instanceof) — catch `Exception` and classify.
- The double-brace initialization (`new X() {{ ... }}`) creates an anonymous class —
  it breaks the runtime wrapper generation; a plain local + the setters.
- `io.github.xdstorage.transaction.IXdStorageTransaction` — the import package trap
  (NOT `io.github.xdstorage.`); likewise the search-query classes live in
  `io.github.xdstorage.search.query`.

---

## The campaign summary (the source cases)

- The CRUD round-trip property found the null-map-value drop on the FIRST run (the
  aggregated divergence report showed all the meta fields at once).
- The container-jungle property found the primitive-array unreadability, the nested
  generic drop, AND the nested-item empty write (the three fixes, one property).
- The FK sweep found the explain no-tx asymmetry (the contract decision + the docs).
- The veto-phase choreography was validated against the CANON (the deletion test's
  own comment) — the diagnostic ladder's green-pair control found it.
