---
title: "[2.2.0][AUDCLAIMS] Verify the three unverified audit claims: the XML end marker; the short-class-name folder collisions; the metadata caches that never evict"
labels: [2.2.0-train, P2, engine, testing]
status: open
---

## Summary

The external evaluation (§3.3.14) carries three claims its own author did NOT verify
(they came from a timed-out sub-audit). The house rule — verify before filing — applies
to them literally: this task is the verification round, and its deliverable is the
triage report with confirmed/dismissed verdicts plus the spun tasks for whatever
confirms.

## The claims

1. **XML has neither an end-of-stream marker nor a journal**: after a hard kill, what
   does an XML-format folder open do — a loud refusal, a silent partial read, a
   recovery? The YAML line has the commit journal + the torn-tail machinery; the XML
   line allegedly has nothing. Verify the reader's behavior on a truncated file, then
   align the posture with tasks 02/06.
2. **Data directories are named by the SHORT class name**: two registered classes with
   the same simple name from different packages collide in one folder (silent mixing?).
   Write the repro (two `com.a.Item` / `com.b.Item`); if it confirms, the design is a
   layout question — the task-07 indexes/ subdirectory precedent (the one-time
   migration pattern) is the template.
3. **The class-metadata caches never evict**: registered entity metadata lives for the
   JVM's lifetime. Verify; the impact assessment is about the hot-redeploy contexts
   (app servers) — for the embedded posture it may be a documented non-issue.

## Scope

1. The three verifications, each with the evidence (the repro or the grep-proof).
2. The triage report in this task: confirmed → the spun task (the number recorded);
   dismissed → the evidence paragraph.
3. No fixes in THIS task (the spins own them) — except a trivial dismissal note in the
   docs if a claim dissolves into "documented behavior".

## Out of scope

The fixes themselves; the XML format redesign (if claim 1 confirms badly, the spin
decides repair-vs-loud with task 06's machinery as the reference).

## Acceptance criteria

- [ ] Three verdicts recorded, each with the evidence.
- [ ] The spins filed (or the dismissals documented).
- [ ] The repro corpus committed if the repros are kept as pins.

Estimate: ~1 session.

## References

- The external evaluation 2026-11-23, §3.3.14 (explicitly marked "не перепроверено")
- Task 07 indexes-subdirectory (the migration-pattern template)
- Tasks 02/06 (the recovery posture the XML verdict aligns to)
