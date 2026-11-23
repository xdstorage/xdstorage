---
title: "[2.2.0][VERIFYAPI] verify() prints diagnostics to System.err unconditionally (11 sites incl. tools/Compact): the structured report"
labels: [2.2.0-train, P2, engine, tech-debt]
status: open
---

## Summary

The public `verify(folder)` integrity walk — a flagship API the docs recommend for the
after-a-power-loss recipe — prints its findings straight to stderr (7 unconditional
`System.err.println` in `XdStorageFolderVerifier.java`; the sweep found 5 more in
`tools/Compact.java`, 11 total in main). A library must not own the application's
stderr; the report is also unstructured, so no caller (or the planned CLI, task 16)
can consume the result programmatically.

## Why

- The evaluation's §3.3.13; also the hard prerequisite for task 34's `System.err`
  regex ban (the ban cannot fail the build while the flagship API itself violates it).
- verify() is THE recovery-story surface — its output belongs in a typed result the
  CLI (task 16) and the SRE recipes (task 19) can render.

## Scope

1. Verify the current signature of `verify(folder)` (does it return anything today?) —
   then the structured result: the per-issue list (the type, the file, the detail),
   the counts, the verdict. Backward-compatible: the existing return contract (if any)
   preserved or the change flagged as API-breaking for the 2.2.0 changelog.
2. The stderr sites become the report entries; a human-readable rendering may log via
   slf4j at the configured level — never unconditionally to stderr.
3. `tools/Compact.java` gets the same treatment (the tool context may legitimately
   print — decide per site, tool-vs-library, and record it).
4. The pins: a corrupted-fixture folder produces the expected structured report; the
   estate ×3; the quick-gate ×3.

## Out of scope

The CLI rendering (task 16 consumes the report — the coordination note goes there);
the verify walk's semantics (its walk/validation logic is not touched).

## Acceptance criteria

- [ ] Zero `System.err.println` in the library paths (grep-proof).
- [ ] The structured report type shipped + pinned (the corrupted-fixture repro).
- [ ] The estate ×3 + the quick-gate ×3 green.
- [ ] Task 16's coordination note recorded (the report is its input).

Estimate: ~1 session.

## References

- The external evaluation 2026-11-23, §3.3.13 (their refs: FolderVerifier 272–321;
  the Compact sites found by the intake sweep)
- Task 34 CHECKSTYLE (the regex ban this task unblocks)
- Task 16 tools-cli (the report's consumer)
- Task 19 recovery-recipe-automation (the other consumer)
