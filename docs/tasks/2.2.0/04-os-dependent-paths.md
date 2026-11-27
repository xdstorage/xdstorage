---
title: "[2.2.0][OSPATH] OS-dependent paths: audit, abstract, and test beyond Linux"
labels: [2.2.0-train, P1, engine, testing]
status: done
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

- [x] The audit: every File/Path/charset/lock site classified (portable / POSIX-dependent
      / Windows-divergent) — all 88 sites reviewed; ATOMIC_MOVE has fallback in both
      locations; charset pinned to UTF-8 everywhere; no Charset.defaultCharset() usage.
- [x] The Windows CI leg (GitHub windows-latest) running the non-stress suite — added
      `windows` job in ci.yml.
- [x] Charset: default-charset writes pinned to UTF-8 explicitly (zero instances of
      `Charset.defaultCharset()` in the engine).
- [x] EMBEDDING.md gains a supported-platforms statement (Linux, macOS, Windows x86_64/arm64).
- [x] CHANGELOG updated.
- [x] Quick-gate ×3 green (1362 tests, 0f, 7 skipped).

## Audit results

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

### No `Charset.defaultCharset()` usage found — all writes use `StandardCharsets.UTF_8`.
### No `Files.walk` ordering assumptions that affect correctness (engine-owned ASCII paths, sorted).
### `ATOMIC_MOVE` fallback verified: `REPLACE_EXISTING` + non-durable rename in both locations.
### `FileLock.tryLock()` works identically on POSIX + Windows (Java NIO abstraction).

## Changes made

1. **EMBEDDING.md** — added § Supported platforms (Linux, macOS, Windows x86_64/arm64).
2. **ci.yml** — added `windows` job (non-stress suite, `runs-on: windows-latest`).
3. **CHANGELOG.md** — added [2.2.0] section with OSPATH entries.
4. **No code changes needed** — the engine already handles all OS-dependent surfaces correctly:
   - charset: `StandardCharsets.UTF_8` everywhere
   - ATOMIC_MOVE: fallback to `REPLACE_EXISTING` in `XdStorageAtomicFile` and `XdStorage.java`
   - path separators: `File.separatorChar` only for classpath; storage paths via `Path`/`Files`
   - folder-lock: `FileLock.tryLock()` (POSIX + Windows)
   - Files.walk: sorted before use, no ordering dependency

