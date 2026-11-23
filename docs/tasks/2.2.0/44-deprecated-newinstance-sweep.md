---
title: "[2.2.0][REFLECT] The deprecated Class.newInstance() ×34: getDeclaredConstructor().newInstance() with the cause-preserving unwrap"
labels: [2.2.0-train, P3, engine, tech-debt]
status: open
---

## Summary

34 no-arg `.newInstance()` call sites in `src/main/java` (the evaluation's §3.4 count,
verified by grep). `Class.newInstance()` is deprecated since Java 9, warns on the
modern JDK runs (17/21 legs), and has treacherous semantics: it throws the instantiated
class's checked exceptions UNWRAPPED, hidden behind `InvocationTargetException`-less
`ReflectiveOperationException` lies. The replacement
(`getDeclaredConstructor().newInstance()`) compiles on the house release=8 bytecode
and silences the warnings on the CI legs.

## Why

- Mechanical hygiene with one real trap: the old call PROPAGATES the constructor's
  RuntimeExceptions directly; the new one wraps everything in
  `InvocationTargetException`. A naive sweep changes which exception the callers see —
  the catch-site audit per call site is the actual work.

## Scope

1. The per-site inventory (34 sites: file, line, what the surrounding catch expects).
2. The sweep with the cause-preserving unwrap: `ite.getTargetException()` /
   `getCause()` rethrown per the site's original contract — each site's diff reviewed
   against its catch clauses, not bulk-sedded.
3. The grep-proof: 0 no-arg `.newInstance()` left (or each survivor named + justified).
4. The estate ×3 + the quick-gate ×3 (the reflective paths: the readers, the codegen,
   the cloner — the suites that exercise instantiation).

## Out of scope

The with-argument constructor sites; the security-manager variants (nobody's Java 8
runtime has them in play here); any refactor of the instantiation patterns themselves.

## Acceptance criteria

- [ ] The inventory committed into this task (34 rows, the per-site contract noted).
- [ ] The sweep done; the grep-proof recorded.
- [ ] The reflective-path suites ×3 green.

Estimate: ~0.5–1 session.

## References

- The external evaluation 2026-11-23, §3.4 (the ×34 count)
- Task 34 CHECKSTYLE (the same hygiene wave; the deprecation warnings cleanup feeds
  the release profile's clean-build posture)
