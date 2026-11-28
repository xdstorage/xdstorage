---
title: "[2.2.0][JRE] FP-3 pulled forward: AOT wrapper generation — run on a JRE, no JDK in the container"
labels: [2.2.0-train, P0, engine, feature]
status: done (build-time wrappers through a standalone Maven plugin + the command line; the loud startup refusal; the demo on a jlink image in a read-only sandbox. NOT done — the container image itself, the native-image leg, a Gradle plugin, the plugin's publishing)
---

## Summary

The discovery's explicit demand ('I need to use it with JRE, not JDK!!!'): build-time
wrapper generation (FP-3, previously 2.3.0) is pulled into the 2.2.0 train as its own
platform track. Maven plugin first: wrappers compile into the consumer build; runtime
codegen stays as fallback; a JRE-only container runs the full demo; the native-image smoke
goes green (FP-12's CI leg follows).

## Scope

Per the FP-3 acceptance already written in MASTER-PLAN Step 3 (JRE-only container demo,
cold-start first-save measured before/after, loud startup failure when neither path is
available) + the annotation-drift risk controls (generation fingerprint checked at load).

Estimate: ~3–4 sessions.

## External cross-reference (2026-11-23, the evaluation intake)

The external evaluation (§3.3.10) independently confirms the JDK requirement and adds
a design constraint for the AOT path: `./gencode` resolves against the PROCESS CWD
(`XdStorageClassGenerator.java:37/47`), so a container with a read-only or relocated
working directory breaks wrapper generation silently (the JRE log.error barrier
catches only the no-compiler case). The build-time generation path must not inherit
the CWD coupling; the runtime fallback keeps it only with the loud startup failure
this task already demands.

## LANDED (2026-11-27) — what shipped, the decisions, and what was NOT done

**Delivered against the scope** (the MASTER-PLAN Step 3 acceptance + the intake's CWD
constraint):

- [x] Build-time wrapper compilation into the consumer jar — the Maven plugin
      (`xdstorage-codegen-maven-plugin:generate-wrappers`) and a command-line entry; the
      quickstart's jar carries 39 wrapper classes and the manifest.
- [x] Runtime codegen preserved as the fallback — a JDK compiles over a missing or a stale
      build-time wrapper, exactly as before.
- [x] **Loud startup failure** when neither path is available: `newOrGetFileStorage` refuses
      by name, with the fix, before the storage folder is locked. The silent-JRE degradation
      is gone.
- [x] A JRE-only runtime runs the demo without `./gencode` — on a jlink image without the
      compiler, from a read-only working directory, and inside a sandbox whose whole root
      filesystem is read-only (`examples/quickstart/run-on-jre.sh`). The container IMAGE was
      not built — see "Not done".
- [x] Cold-start first save measured before/after (BENCHMARKS § Cold start). The honest
      reading: the first save is FLAT (176 against 175 ms); the javac bill of the runtime
      codegen is paid by the first *load* (+231 ms), so a process that saves and reads once
      takes 2.0× longer without build-time wrappers (491 → 246 ms).
- [x] Generation fingerprint checked at load (the annotation-drift control): a digest of
      everything the wrapper generators read — the class name, the superclass chain, the id
      field, the parent getter, the getter/setter/close-method signature lists with their
      generics — plus the engine version.
- [x] The build-time path does not inherit the CWD coupling (it needs no directory at all);
      the runtime fallback keeps it only with the loud startup failure.

### The red-before record (the engine at `850ab69`, a clean copy, the final test sources)

`XdStorageJreRuntimeTest` on the engine as it was: **8 run, 7 failed** — the JDK control is
the one that passes, by design.

- JRE without wrappers (test 2): `Exception in thread "main" java.lang.NoClassDefFoundError:
  javax/tools/DiagnosticListener` — an unnamed JVM error at the first use of a class, no
  cause, no way out.
- JDK without wrappers, read-only working directory (test 6): "cannot clone and wrap
  object", and three causes down: "cannot create simple wrapper reference for …JreCustomer
  (wrapper generation degraded to dummy: check system compiler availability)" — the dummy
  degradation, its reason invisible on the line an operator reads.
- Tests 3, 4, 5, 7, 8: the generator did not exist (`Could not find or load main class
  io.github.xdstorage.tools.GenerateWrappers`).

### Decision 1 — the plugin is a standalone module, not a reactor module

The task says "Maven plugin first". A reactor module needs `<modules>` in the root pom, and
Maven 3 refuses them in a `jar` project ("Aggregator projects require 'pom' as packaging").
Turning the library into a multi-module build is not this task's to do — so the plugin lives
in `tools/xdstorage-codegen-maven-plugin/` with its own pom, built on request like
`examples/quickstart`:

```
(repository root)  mvn install -DskipTests
(here)             mvn -f tools/xdstorage-codegen-maven-plugin/pom.xml verify
```

Built against `maven-plugin-api` 3.6.3 (the library's Maven floor) with Java 8 bytecode, so
it loads in any supported Maven. Same version number as the engine. Its test
(`GenerateWrappersMojoTest`, 7) runs against the engine artifact and is therefore NOT part of
the library's gate — the plugin's own `verify` is. Rejected: making the library's pom a
`pom` aggregator (touches every path, CI, the release profile).

### Decision 2 — the startup check compiles nothing

The first version asked for a real wrapper at open (`getClassSimpleWrapper` of the engine's
record base class). It worked — and it would have moved the javac bill onto the first save:
the measurement (BENCHMARKS § Cold start) showed that the write path needs no compiled
wrapper at all and that the runtime codegen is paid at the first *load*. So the check only
asks whether a wrapper CAN be delivered: the build-time one for the probe class (a lookup),
else whether the compiler API's module is on the module graph (`javax.tools.JavaCompiler` —
absent from a JRE image and from a limited graph), else whether `./gencode` can be created.
On a JDK without build-time wrappers nothing is compiled at open, and the first save stays
where it was. `./gencode` is now created at open on that path (before: when the generator
class was loaded).

### Decision 3 — a stale wrapper is named, not treated as a missing one

The first message for a stale wrapper on a JRE was "no pre-generated wrapper is on the
classpath" — true in effect and wrong in fact: the operator would look for a missing plugin
while the fix is a rebuild. The lookup keeps its WARN for a JDK (it compiles over the stale
wrapper) and the no-compiler failure asks the manifest what it recorded: "… it is STALE —
generated for fingerprint X, the class as loaded has Y … Fix: rebuild the application to
refresh the wrappers (a clean build), or run on a JDK."

### Decision 4 — the fingerprint digests what the generators read, and it is sorted

The generators read three annotations from a class — `@XdStorageObjectId` (the id field),
`@XdStorageParentObject` (the parent getter), `@XdStorageLoadByGetMethod` (which getters are
lazy) — and the public method list. The fingerprint is built from those facts by
`XdStorageWrapperFingerprint` alone (never from reflection `toString()`), and every list is
SORTED: `getDeclaredMethods()` returns its methods in an order no specification guarantees,
and a JDK and a jlink image listed the same entity's methods in two different orders — an
order-dependent fingerprint called a perfectly good wrapper stale on the image. Pinned: the
order-independence, the shape (a method, another id field), and the annotation the
generators read. Limit, recorded: two builds of the same version string (SNAPSHOT against
SNAPSHOT) are not told apart — the version is part of the digest, the generator's code is not.

### Decision 5 — the wrappers are made by the engine the application runs

The generator class carries no compile dependency on the engine: the plugin (and the
command line) put the application's compile classpath into an isolated loader and run the
generator of the engine copy found THERE, by name. A build tool that happens to carry an
older engine never generates with it. The isolated loader needs the engine's dependencies
too (the first plugin run died on `org/slf4j/LoggerFactory`): the command line defaults its
classpath to its own JVM's, the plugin uses the project's compile classpath, and a missing
class is named.

### Decision 6 — every entry of the compile classpath is scanned

The engine's own classes are entities (eleven of them: B+trees and their nodes, index
records, the record base class) and need wrappers like the application's; a model may live
in another module's jar. So the goal scans it all — 342 classes for the quickstart, 0.17 s
when nothing changed, 0.70 s cold (BENCHMARKS § Cold start). Rejected: scanning the
application's classes only (the engine's own wrappers would stay runtime-generated — on a
JRE the open would still fail); a byte-level class-file pre-filter (unmeasured need).

### Decision 7 — the wrappers are compiled for release 8 unless the project says otherwise

A wrapper compiled for 8 runs on every later Java; one compiled for the build JDK's default
does not run on an older target. The plugin takes its `release` parameter, then
`maven.compiler.release`, else 8. The test pins all three (class-file major versions 52 and
61).

### Decision 8 — the dummy wrappers stay, for one case only

The recursion guard of a self-referencing entity graph still resolves the in-progress class
to the dummy wrapper; that is a generation mechanism, not a degradation, and it is the
dummy's last use. The javadoc of the two dummy classes and of the reference provider said
"no system Java compiler (a JRE)" — corrected.

### Found on the way — recorded, not fixed here

- **The first save compiles nothing.** The 2.1.2 changelog entry (the transaction-timeout
  raise) says the first transaction of a cold JVM "pays the runtime wrapper-codegen …
  timeout rollbacks on the very first save". In the quickstart path measured here the save
  path never asks for a compiled wrapper; the first load does (+231 ms). Where that entry's
  first-save payment came from (a facade path? the trigger path?) was not investigated.
- **`Throwable(String, Throwable)` with a null cause makes `initCause` throw.** The JDK
  assigns the field unconditionally, so the later `initCause(error)` raised "Can't overwrite
  cause" — and THAT exception replaced the real failure on its way out. The cause now travels
  through the constructor.
- **The write sandbox was the proof, not the label.** `bwrap --ro-bind / /` refuses
  `touch` in the working directory and in `/opt` ("Read-only file system"); the demo runs
  there and creates nothing. A read-only working directory alone is a weaker claim — the
  engine could write elsewhere. Checked with a negative control in the same sandbox: the same
  image and a jar without wrappers refuses at the open (exit 1, the named cause).

### Not done

- **The container image.** `examples/quickstart/Dockerfile` was written from the script and
  has never been built (no Docker on the machine). The read-only-root sandbox is the same
  kernel mechanism `docker run --read-only` uses, but it is not the image.
- **The Graal native-image leg** (no GraalVM here). The wrappers being ordinary build-time
  classes removes the compiler from the picture; whether the engine's reflection closes under
  native image is untested.
- **A Gradle plugin.** The command line covers it (EMBEDDING § Running on a JRE gives the
  `JavaExec` shape); nothing Gradle-specific was written or tried.
- **The plugin's publishing.** It is a second artifact under the engine's version; whether and
  how it ships to Maven Central is a release-process decision for the maintainer
  (RELEASE-PLAN not amended).
- **The 2.1.x line.** v2.1.3 lacks all of this; the 2.1.4 question stays with the maintainer.
- **The read-only working directory on Windows** (2026-11-28, the wave's first Windows run:
  both read-only runs red). The fixture made the working directory read-only with
  `File.setWritable(false, false)` and ASSERTED the call's result. On Windows the call
  returns `false` for every directory — the JDK's `setPermission0` leaves
  `FILE_ATTRIBUTE_DIRECTORY` alone (read in the OpenJDK 21 source) — and the attribute would
  not keep a child from being created there anyway. So the scenario cannot be set up that
  way on Windows at all. `XdStorageJreSandbox.run` now treats it as an ASSUMPTION: the two
  runs are skipped on Windows and report why; POSIX root, which writes into read-only
  directories, was skipped by the same assumption before. The engine's behaviour is not in
  doubt by reading — its one write into the working directory is `./gencode`, and the
  refusal comes from `ensureCodeDirectory` when `mkdirs()` fails, whatever made it fail — but
  the proof ran on Linux only. What would run it on Windows: a deny ACE on the directory
  (`icacls <dir> /deny <user>:(AD,WD)`, removed again in a `finally`), or a regular file
  named `gencode` in the working directory, which makes the same `mkdirs()` fail — the
  narrower claim ("the working directory rejects `./gencode`") in exchange for a portable
  fixture.

### The pins

`XdStorageJreRuntimeTest` (8 — the application in a child JVM: the JDK control; the JRE
without wrappers refuses at the open; the JRE with build-time wrappers runs and compiles
nothing; the JDK with them compiles nothing; a read-only working directory with them works;
without them the open refuses and names the directory; a stale wrapper on the JRE is refused
by name; on the JDK it is compiled over — the two read-only runs execute on POSIX and are
skipped on Windows by an assumption, see Not done), `XdStoragePreGeneratedWrapperTest` (14 — the lookup
under the application's own loader, no manifest, a kind not generated, a stale record, a
missing class file, another entity's wrapper, the engine taking all three kinds, the
incremental run, a changed record regenerated, the isolated path, the scan wrapping entities
only, and the three fingerprint properties), and `GenerateWrappersMojoTest` (7 — in the
plugin module). The JRE legs link a real jlink image once per build directory
(`target/jre-image`); a JDK without `jmods` would fall back to `--limit-modules`.

**The gates:** `mvn clean test -Pquick-gate` on a quiet box, in a copy of the tree —
**1777/0f/0e/7 skipped, BUILD SUCCESS, 0 checkstyle violations** — six runs, **five green,
one red**. The red (the second run of the first batch) was
`XdStorageCrashAndPurgeStressTest.testScenario1_PowerLossAndSubsequentPurge`: "residual
XdGalaxy" after a purge that skipped 84 subtrees of crash residue, where every other
full-suite run of this wave skipped 28 or fewer. Not reproduced in isolation on either tree
(15/15 without this task, 15/15 with it); by code reading the purge path is untouched here;
**not A/B-proven either way** — ledgered in FLAKY.md (2026-11-27), not quarantined. The
three runs after it were consecutive greens (1755 + the 22 pins of this task). The plugin
module's own gate, `mvn verify` in `tools/xdstorage-codegen-maven-plugin`: 7/0f/0e, 0
checkstyle violations.
