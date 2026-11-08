---
title: "[2.2.0][OSPATH] OS-dependent paths: audit, abstract, and test beyond Linux"
labels: [2.2.0-train, P1, engine, testing]
status: open
---

## Summary

Audit every OS-sensitive surface — file paths (case sensitivity, separators, the
`File`-based APIs), charset handling, file locking semantics, atomic-rename behavior,
`Files.walk` ordering assumptions, the folder-lock implementation — and add a Windows
CI leg (or a documented Windows-unsupported statement with the exact blockers).

## Why (discovery § First priority 8)

The engine is developed and CI-tested on Linux only; the atomic-rename, folder-lock and
charset behaviors differ materially on Windows/macOS. An embedder on Windows hits
differences the project has never run.

## Scope

1. The audit: every `File`/`Path`/charset/lock site classified (portable / POSIX-dependent
   / Windows-divergent).
2. The Windows CI leg (GitHub windows-latest) running the non-stress suite; failures
   triaged into fixes vs documented divergences.
3. Charset: the default-charset writes (`Charset.defaultCharset()` in the journal/writer)
   pinned to UTF-8 explicitly.
4. EMBEDDING.md gains a supported-platforms statement.

Estimate: ~1–2 sessions for audit + first Windows triage.
