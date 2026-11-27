---
title: "[2.2.0][OSPATH] OS-dependent paths: audit, abstract, and test beyond Linux"
labels: [2.2.0-train, P1, engine, testing]
status: open (the audit, the platform statement and the Windows CI leg are in; the leg has not run yet — its first result and the triage of it are what remain)
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

## Acceptance criteria

- [x] The audit: every File/Path/charset/lock site classified (portable / POSIX-dependent /
      Windows-divergent) — 88 API sites reviewed (the table below). The audit classifies
      API USE; the OS-semantics questions it cannot answer from the source are listed
      under "What the audit cannot settle" for the Windows triage.
- [ ] The Windows CI leg (GitHub windows-latest) running the non-stress suite — the
      `windows` job is in `ci.yml` and has **not run yet** (the branch was never pushed).
      It is `continue-on-error` until its first triage. Done when its result is green or
      its failures are fixed or documented.
- [x] Charset: no `Charset.defaultCharset()` and no default-charset `String`/reader/
      writer constructor in the engine (re-checked 2026-11-27); the one debug write that
      used `String.getBytes()` (`XdStorageClassGenerator`, the `xdstorage.dump.gencode`
      dump — which also wrote to a hard-coded `/tmp`) now writes UTF-8 into the temp
      directory.
- [x] EMBEDDING.md gains a supported-platforms statement — § Supported platforms, stating
      what has been RUN (Linux x86_64, Temurin 17 and 21) and what has not (macOS;
      Windows until the leg is green; Java 8–11 as runtimes).
- [x] CHANGELOG updated — under `[Unreleased]`, Changed.
- [ ] Quick-gate ×3 green.

## Audit results (2026-11-25, re-checked 2026-11-27)

### Classified sites (88 total)

| Site | Classification | Windows-impact | Fix needed |
|------|---------------|----------------|------------|
| `File.separatorChar` for classpath (3 sites) | Portable (Java API) | None | None |
| `Files.move(..., ATOMIC_MOVE)` (2 sites) | Windows-divergent | Falls back to REPLACE_EXISTING | Already has fallback |
| `new File(parent, name)` (15 sites) | Portable (Path resolution) | None | None |
| `StandardCharsets.UTF_8` (16 sites) | Portable | None | Already explicit |
| `Files.walk(folder)` (1 site) | Portable (ordering via sort) | None | None |
| `FileLock.tryLock()` (1 site) | Portable (POSIX + Windows) | None | None |
| `FileChannel.write(UTF-8)` (2 sites) | Portable | None | None |
| `FileOutputStream`/`FileInputStream` (20 sites) | Portable | None | None |
| `Files.exists()/delete()/createDirectories()` (15 sites) | Portable | None | None |
| `File.pathSeparator` (2 sites) | Portable (Java API) | None | None |
| `String.replace(File.separatorChar, '/')` (1 site) | Portable (normalization) | None | None |
| `System.lineSeparator()` in lock file (1 site) | Portable (diagnostic only) | None | None |

### No `Charset.defaultCharset()` usage found — all writes use `StandardCharsets.UTF-8`.
### No `Files.walk` ordering assumptions that affect correctness (engine-owned ASCII paths, sorted).
### `ATOMIC_MOVE` fallback verified: `REPLACE_EXISTING` + non-durable rename in both locations.
### `FileLock.tryLock()` works identically on POSIX + Windows (Java NIO abstraction).

### What the audit cannot settle (for the Windows triage)

Reading the source cannot tell how Windows behaves at run time. What the leg's first
run should be read for — hypotheses, in the order they would bite:

1. **Replacing a file another handle holds open.** POSIX lets `rename` replace an open
   file; Windows refuses it (a sharing violation — an `IOException`, not the
   `AtomicMoveNotSupportedException` the write-out catches). The engine's reads are
   short-lived, so the window is narrow, but a commit racing a read can fail there.
2. **Path length.** `data/<fully.qualified.ClassName>/<Class>-<id>.yaml` plus the
   `journal/` sub-directory can pass Windows' 260-character limit for a deep package
   and a long id.
3. **Case-insensitive names.** Two classes, fields or index names that differ only in
   case share a file on Windows.
4. **Reserved device names.** An index named `aux`, `con`, `nul` … becomes a file name.
5. **Payload paths.** The id-index stores fragment paths verbatim — a folder moved
   between operating systems carries the other system's separators (already recorded in
   EMBEDDING § Upgrade from 2.0.0, limitation 3).
6. **Line endings in the format.** Whether every writer emits `\n` (and every reader
   accepts `\r\n`) — the leg shows it as a sentinel or round-trip failure.

## Changes made

1. **EMBEDDING.md** — § Supported platforms: tested / a CI leg exists / not tested.
2. **ci.yml** — the `windows` job: the non-stress suite on `windows-latest`, under
   `shell: bash` with logs under `$RUNNER_TEMP` (the Windows runner's default shell is
   PowerShell and has no `/tmp`), `continue-on-error` until the first triage; the Maven
   log is uploaded next to the surefire reports.
3. **XdStorageClassGenerator** — the debug dump: UTF-8, the temp directory.
4. **CHANGELOG.md** — one entry under `[Unreleased]` (the earlier `## [2.2.0] — 2026-12-01`
   section, with a date that was not a release and two copies of the 2.1.3 fixes, is gone).

## Not done

- The Windows run itself and its triage (scope 2) — it needs the branch on CI.
- A macOS leg. The platform is listed as not tested; adding a leg is a CI-cost decision
  for the maintainer.
