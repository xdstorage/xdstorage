# XdStorage Quickstart

A runnable, end-to-end sample of [XdStorage](https://github.com/evgeny-korolev/xdstore) —
the embedded, file-backed object store for Java. One test class walks the entire lifecycle:

**open → transaction → save → indexed query (`EQUALS`) → range query (`BETWEEN`, `GTE` +
`ORDER BY DESC` + `LIMIT`) → `count()` → commit → shutdown → reopen from disk → load → shutdown**

The domain model is two plain JavaBeans — [`Customer`](src/main/java/quickstart/Customer.java)
(indexed by country) and [`Account`](src/main/java/quickstart/Account.java) (indexed by
balance) — and the whole story is asserted in
[`QuickstartTest`](src/test/java/quickstart/QuickstartTest.java). That test is the code to
copy: ~130 lines, no configuration, no server. The same lifecycle also runs as a plain
program, [`Quickstart`](src/main/java/quickstart/Quickstart.java) — that is what runs where
there is no test runner: a JRE, a container (below).

## Run it

Prerequisites: a **JDK** (the engine is built and tested with 17 and 21 — it compiles the
wrapper classes itself at runtime, which takes a JDK; see below for the way around it) and
**Maven 3.6.3+**.

```bash
cd examples/quickstart
mvn test          # compiles the entities + runs the lifecycle test — that's the whole demo
java -cp target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
     quickstart.Quickstart /tmp/quickstart-db     # the same lifecycle as a plain program
```

Then open the two entity classes and `QuickstartTest` in your IDE and start editing —
every save/query primitive the engine has is exercised there with comments.

## Run it on a JRE (no JDK, nothing to write at runtime)

The engine needs a generated wrapper class for every entity class. On a JDK it compiles
them itself when they are first needed; on a JRE — or from a working directory nothing can
write to — it cannot, and it refuses to open a storage by name. The way out is to generate
the wrappers **while the application is built**: they end up inside the application jar, and
the runtime needs no compiler and no `./gencode`.

[`run-on-jre.sh`](run-on-jre.sh) does the whole thing on a JDK 17+ with `jlink`:

```bash
cd examples/quickstart
# the engine and the codegen plugin must be in the Maven repository the build uses:
#   from Maven Central with the release, or built here —
#   (repository root)            mvn install -DskipTests
#   (repository root)            mvn -f tools/xdstorage-codegen-maven-plugin/pom.xml install
./run-on-jre.sh
```

1. `mvn -Pjre package` — the `jre` profile binds `xdstorage-codegen-maven-plugin`: 39
   wrapper classes (13 entity classes × 3 — the two of this example and the engine's own
   eleven) go into `target/classes`, hence into the jar, with their manifest;
2. `jlink` — a runtime image of the modules the engine needs (about 65 MB, no compiler);
3. the demo **from a read-only working directory**, and — where
   [bubblewrap](https://github.com/containers/bubblewrap) is installed — once more inside a
   sandbox whose whole root filesystem is read-only. Both print `QUICKSTART-OK`; neither
   creates `./gencode`.

[`Dockerfile`](Dockerfile) packages the same three steps for a real container
(`docker run --read-only …`). It was written from the script but **has not been built
yet** — no Docker on the machine that wrote it; treat it as the recipe to verify.

## Where the dependency comes from

The pom depends on exactly one production artifact — `io.github.xdstorage:xdstorage`
(currently `2.2.0`; bump `<xdstorage.version>` in the pom):

- **from Maven Central** once the release is published — nothing else to do;
- **from a local build** while the release is in flight: in the *repository root* run
  `mvn install -DskipTests`, then `mvn test` here resolves `2.2.0` from your local `~/.m2`.

## Notes you will hit in real use

- **Storage = one folder.** The test gives every run a unique folder (`@TempDir`) and a
  unique storage name — the provider keeps a per-JVM registry by name, and a folder is
  exclusively locked while open ([EMBEDDING.md](../../docs/EMBEDDING.md) § Lifecycle).
- **Transactions are explicit and thread-bound.** Every CRUD call takes a transaction (or
  uses the current thread's binding); commit/rollback belongs in a `catch`/`finally`.
- **Top-level entities only.** The wrapper classes are generated from your beans — at
  build time or at runtime; nested classes cannot be resolved. Without a wrapper the engine
  **refuses by name** (a JRE with no build-time wrappers; a read-only working directory on
  a JDK) — it no longer degrades to broken wrappers
  ([EMBEDDING.md](../../docs/EMBEDDING.md) § Codegen requirements, § Running on a JRE).
- **Logging is yours.** The library logs via SLF4J and ships no backend or config — this
  example therefore prints nothing from the engine. Add any SLF4J backend to see the
  lifecycle `INFO` lines (EMBEDDING.md § Observability).
- **Durability** is process-crash-safe by default (atomic rename); power-loss safety is
  `XdStorageConfig.builder().durableWrites(true)` at ~8× write cost (BENCHMARKS.md).
- **Reopen is safe for every query shape.** The test deliberately re-runs the range and
  count queries **after** the close/open cycle: search-index key types are restored to the
  indexed field's type at reopen (BUG-E, fixed —
  [docs/tasks/2.1.0/18](../../docs/tasks/2.1.0/18-bug-search-index-key-types-after-reopen.md);
  before the fix, numeric range/`EQUALS` seeks crashed after a reopen).

## Where to go next

- [README.md](../../README.md) — the full guide (data model, transactions, search engine).
- [docs/DECISION-GUIDE.md](../../docs/DECISION-GUIDE.md) — when XdStorage is the right choice.
- [docs/EMBEDDING.md](../../docs/EMBEDDING.md) — the operational contract.
- [docs/BENCHMARKS.md](../../docs/BENCHMARKS.md) — what the engine costs, measured honestly.
