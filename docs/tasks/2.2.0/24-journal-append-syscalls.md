---
title: "[2.2.0][JOURNALAPPEND] Journal append path: eliminate the per-commit metadata syscalls"
labels: [2.2.0-train, P2, engine, performance]
status: open
---

## Summary

The PROFILE ledger (task 14, docs/perf/profile-2026-11-23/LEDGER.md) measured the
single-commit write path: **~34% of write_heavy CPU is file-metadata syscalls** —
`_lxstat` 18.4% + `__open64` 3.9% + `xstat` 3.4% + `access` 2.6% (+ `write` 5.3% the
irreducible payload) — sourced from the journal append path:
`XdStorageYamlCommitJournal.append` runs `parent.mkdirs()` (line ~417) and the
exists/length probes PER COMMIT.

## The fix sketch

The journal is append-only with a single writer per resource:
1. cache the journal's existence and size IN MEMORY once at resolution (the journal
   is created/opened then; the size tracks the append offset after every append);
2. `mkdirs` once at resolution, not per append;
3. the checkpoint condition (`sizeOf != 0`) reads the cached counter instead of
   stat-ing the file.

The crash-tolerance semantics are untouched: the appends, the fsync gating and the
replay stay byte-identical — only the metadata probes become in-memory counters.

## Acceptance

- [ ] The per-commit syscalls reduced to `open(existing)+write` (verify by strace or
      the same ctimer profile: lxstat/xstat leaves gone).
- [ ] The write_heavy JMH shape improves measurably (the single-commit cost −15–25%
      expected from the ~34% syscall share × the syscall count removed).
- [ ] The journal estates green ×2 (the naming chain, the checkpoint, PWRLOSS).

Estimate: ~0.5 day. Evidence: docs/perf/profile-2026-11-23/LEDGER.md § the write path.
