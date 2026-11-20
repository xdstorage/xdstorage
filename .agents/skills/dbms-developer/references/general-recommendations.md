# General recommendations — process scaled by the required DBMS complexity

The house rules in SKILL.md and the verification protocol are tier-agnostic; this
file tiers them. The controlling idea: **the process weight is set by the class of
invariant the work can break, not by the lines of code it touches.** A three-line
change inside the commit protocol outranks a three-hundred-line change inside one
reader. Classify FIRST, then run the tier's minimum evidence bundle — and escalate
the moment the work reaches into a bigger class (escalation is one-way; a downgrade
needs the maintainer).

---

## How to classify (ask in order; the FIRST match wins)

1. Does it change a **file format, layout, or public contract** (on-disk structure,
   config surface, facade signatures, Maven coordinates)? → **C5**
2. Does it add or reorder a **lock, an acquisition order, or a commit-phase step**? → **C4**
3. Does it touch the **write path** (journal, checkpoint, recovery, btree mutation,
   overlay, the cache write-set)? → **C3**
4. Does it change **transactional semantics** (isolation, veto flows, visibility,
   first-committer-wins, rollback behavior)? → **C2**
5. Does it change behavior of **one subsystem** with no cross-resource effect
   (one resource type, one index family, codegen, cloner, reader strictness)? → **C1**
6. Data/docs/examples only, no runtime semantics? → **C0**

Escalation signals mid-work: a second resource starts caring about your change (→C2);
you are about to add a latch or touch `lockForCommit` (→C4); a stored file stops being
readable by the previous release (→C5 + a migration task); a fix needs a design
paragraph longer than its diff to justify (→C3).

---

## C0 — Data, docs, examples (no engine semantics)

**Minimum bundle:** the change itself; DOCSLINT green; one runnable proof if an
example claims behavior (the landing-page fence-extraction lesson: an example that
doesn't compile as written is a defect, not prose).

**Discipline:** numbers quoted in prose must come from a recorded run or the canonical
baseline — never from memory. Terminology conforms to the GUIDE glossary.

## C1 — Single-subsystem behavior

**Minimum bundle:** a red-before pinning test (proven red on the old code, green on
the new — "red-before proven" is the house phrase); the subsystem's own suite green;
the neighboring suites named in the routing table; the full quick-gate ×1 before
hand-off.

**Discipline:** the fix records the mechanism at the choke point (a comment where the
invariant lives), not the symptom instance. A silent-degradation fix must THROW or
count loudly — quiet self-heals are the S-class hazard family (2.2.0 task 02).

## C2 — Transactional semantics (isolation, vetoes, visibility, rollback)

**Minimum bundle:** the pinning tests for BOTH the new behavior AND the counter-
scenario (the no-over-rejection pattern of XdStorageFirstCommitterWinsTest — a veto
tightening must prove it does not veto legitimate flows); the relevant family estates
(FK 372 / cache protocol / MVCC invariants); **the full quick-gate ×2**; the task-file
record of the semantic shift (what observable behavior changed and why it is safe).

**Discipline:** isolation estates pin mock clocks — a time-source change re-adapts
the matrix rows or it is not landed. Any change to the stage→commit window must state
explicitly whether it widens or narrows the lost-update window (the known open W-4
layer) — silent widening is forbidden even when the tests stay green.

## C3 — Write-path / durability (journals, checkpoints, recovery, btree mutation, overlay)

**Minimum bundle:** the design recorded BEFORE the implementation (the analysis
quartet discipline: subsystem model + failure families + rollback story — even if
short); **a soundness argument for rollback written in the task file** ("sound by
construction" — state what the reverse operation is and why it cannot corrupt);
prototype-first for novel mechanisms with the patch preserved when the first attempt
fails (the task-53 discipline — two preserved prototypes beat two buried failures);
the full quick-gate **×3**; FKBENCH (or the relevant benchmark) before/after for any
performance claim.

**Discipline:** revert is a legitimate outcome — an incompletely-understood fix in
the write path is worse than a documented limitation (two task-41 compensation
attempts reverted; the overlay replaced them with a sound construction). Structural
transforms (split/join/collapse) are never reverse-undone — stage-then-apply instead.
Every W-4-class fix lands with its pinning tests listed in the task file (the
verification protocol's pinning map).

## C4 — Concurrency-critical (lock orders, acquisition, convoy-class)

**Minimum bundle:** everything in C3, plus: the **total-order argument** written down
(any resource whose acquisition can tie must tie identically in every transaction —
the convoy lesson: a tie broken arbitrarily per-tx IS an acquisition deadlock); the
**opposite-order deadlock pin** (two threads, the same resources, opposite orders —
XdStorageBTreeOverlayTest's pin is the template); the **stress families ×3**
(MassFillFirst, MassFillSecond — with the semaphore constraint honestly removed, not
papered over; BTreeStress; Multithreads; W2Reopen; the facade family); the throughput
and latency targets stated BEFORE the change (FKBENCH t=10 ≥ 100 tx/s, p99 < 100ms —
set the bar, then meet it or say why not).

**Discipline:** never wait inside a commit phase (the 3s visibility budget family);
never hold a shared lock across both phases without a measured justification; a hang
is analysis material (timeout the run, jstack it — the maintainer's 2026-11-21
directive) — never kill-and-guess.

## C5 — Architectural (format breaks, new subsystems, compat surface)

**Minimum bundle:** a task file with a **quantified Why** (the collapse table of task
53, not "it feels slow"); the migration/compat design as an explicit section (a
2.0.0 user must be able to open the folder — crash-tolerant, idempotent, cross-OS);
the analysis quartet equivalent for the new subsystem; the acceptance criteria in
verifiable form before the first commit; docs (GUIDE/EMBEDDING/CHANGELOG) as part of
the bundle, not a follow-up; the RC protocol (the full gate ×3, the benchmark estate
vs the canonical baseline, the fresh-clone walkthrough).

**Discipline:** no default-behavior change rides along with a refactor; a format
change without a filed migration task does not merge. Positioning guards hold (the
single-writer contract, YAML readability, JDK-17 floor) — architectural work that
quietly trades a positioning pillar away is a maintainer decision, not an
implementer's.

---

## Universal principles (every tier)

1. **Soundness beats cleverness.** Prefer constructions whose failure mode is
   "discard and throw" over constructions whose failure mode is "half-applied". The
   overlay (stage → apply-or-discard) replaced four unsound compensation families.
2. **Quantify before you build.** The Why of every C3+ change is a measured table
   (FKBENCH Step 0: 140× collapse), and the target is a number agreed in advance.
3. **Facts at choke points, not instance guesses.** "Registry is first-instance-wins"
   survives; "galaxy 32768e3e was orphaned" does not generalize.
4. **Red-before proven.** A pinning test that never failed proves nothing; run it on
   the old code first (or stash-verify the defect pre-exists at HEAD).
5. **Re-run isolated before diagnosing code.** Full-suite reds are frequently
   contention, ordering, or concurrent-build destruction (the 112-error cascade).
6. **The estate is the verdict.** The tier's ×N on the tier's suites, judged by the
   tool's own summary — the R-52 gotcha applies at every tier.
7. **The probe protocol over the patch reflex.** Bracket the mutated value at every
   hop; the flip between two adjacent probes names the culprit (the debug playbook).
8. **Record the attempt that failed.** Reverted attempts stay in the task file with
   their mechanism — they are the map of where the mine is buried (task 41's two
   attempts are why task 53 exists).
9. **Escalate honestly.** Discovering mid-fix that the change is a class bigger than
   classified is a re-planning event, not a rounding error: stop, re-classify, and
   adopt the bigger tier's bundle — or split the change.
10. **Leave the tree better-classified than you found it.** When a session learns
    that a component belongs to a bigger class than its docs claim, write that down
    in the task file or the failure registry the same sitting.

---

**Cross-references:** the concrete estates and gates —
[verification-protocol.md](verification-protocol.md); the failure signatures —
[failure-families.md](failure-families.md); the commit/rollback algorithms —
[tx-model.md](tx-model.md); the overlay contracts — [btree-overlay.md](btree-overlay.md);
the probe method — [debug-playbook.md](debug-playbook.md).
