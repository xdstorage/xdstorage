# House rules for XdStorage JUnit tests

These are the hard-won rules from the 2.1.0/2.2.0 trains. Each exists because
something broke. They instantiate the abstract principles in
[general-recommendations.md](general-recommendations.md) (R1–R13) for this
repository — when a case isn't covered here, reason from those.

## 1. Verification

### 1.1 The verdict is Maven's own output

```
Tests run: 251, Failures: 0, Errors: 0, Skipped: 8
BUILD SUCCESS
```

**Never** judge a test suite by:
- ❌ A piped grep's exit code (the R-52 gotcha: a failed build masqueraded as
  success until launch — `BUILD FAILURE` was hidden by a `tail` truncating the output)
- ❌ A test class's `@Disabled` status (disabled tests are part of the estate)
- ❌ A single test's pass/fail (full-suite failures are often contention, not code)

### 1.2 Re-run failing classes in isolation

A full-suite red is frequently:
- **Contention**: a daemon-flag failure (`S4`) that only appears under load
- **Ordering**: a test that depends on another test's side effects (rare but real)
- **Concurrency**: two maven builds on the same `target/` destroying each other's
  class files (the 2026-10-26 112-error cascade — `NoClassDefFoundError` for
  nested classes + wrapper-codegen degradation)

**Always** re-run a failing class isolated before diagnosing it as a code bug.

### 1.3 Unique storage per test run

```java
@BeforeAll static void initStorage() {
    storageName = "fk_spec_db_" + UUID.randomUUID().toString().replace("-", "");
    tempFolder = Files.createTempDirectory("xdstorage_fk_tests_");
    storage = XdStorageProvider.newOrGetFileStorage(storageName, tempFolder.toAbsolutePath().toString());
}
```

The R-50 stale-dir trap: `File.delete` legally fails on IDE-held files → the
storage parses stale disk state → confusing first-save failures. **Always use
UUID in the storage name**, never a fixed name like "fk_test".

## 2. Test organization

### 2.1 Real storage vs mock

| When to use | Pattern | Example |
|---|---|---|
| FK lifecycle, veto, persistence | `AbstractForeignKeyBaseSpecificationTest` (real storage) | Policy tests, scenario tests |
| Cache state machine, write-set isolation | Mock-based `AbstractForeignKeyEngineIntegrationTest` | `XdStorageForeignKeyCacheTest` |
| Unit-level (no storage needed) | Plain JUnit with mocks | `XdStorageForeignKeyCacheTest` |

**The rule**: if a test exercises persistence (save → commit → load → verify),
use real storage. The mock path was the cause of 8 disabled tests.

### 2.2 Test class naming

| Test type | Naming convention | Example |
|---|---|---|
| Scenario test | `ForeignKey<Scenario>Test` | `ForeignKeySaveScenariosTest` |
| Policy test | `Policy<Parent><Child><Index>Test` | `PolicyParentClassChildClassHashTest` |
| Integration test | `XdStorageForeignKey<Scenario>Test` | `XdStorageForeignKeyDeletionTest` |
| Unit test | `XdStorageForeignKey<Component>Test` | `XdStorageForeignKeyCacheTest` |

### 2.3 Display names

All `@DisplayName` must be in Russian (the house convention):
- "Успешный коммит" = success
- "Исключение" = veto/throws
- "Ошибка" = error/veto
- "Сценарий" = scenario
- "Инвариант" = invariant/always-true

## 3. Test writing rules

### 3.1 Each test must be independent

No test depends on another test's side effects. Each test:
1. Creates fresh entities
2. Saves them
3. Commits
4. Loads fresh
5. Verifies

No shared `@Before` state between tests in the same class (except the `storage`
fixture which is `static` and created per-class via `@BeforeAll`).

### 3.2 Assertion style

```java
// ✅ Correct
assertThrows(XdStorageRuntimeException.class, () -> storage.commitTransaction(tx));
assertDoesNotThrow(() -> storage.commitTransaction(tx));
assertNotNull(parent.getId());
assertEquals(42L, child.getId());

// ❌ Wrong — don't use assertTrue for "must throw"
assertTrue(transaction.isRollbackOnly());  // use assertThrows instead
```

### 3.3 Test scope discipline

A test must test **one** thing. If a test does 3 things, split it:

```java
// ❌ Bad — tests both insertion and deletion
@Test
void testSaveAndDelete() throws Exception {
    storage.save(parent);
    storage.commitTransaction(tx);
    storage.delete(child);
    storage.commitTransaction(tx);
}

// ✅ Good — two tests
@Test void testSaveParentWithChildrenSuccess() { ... }
@Test void testDeleteChildWithParentUpdateSuccess() { ... }
```

### 3.4 The 14-test skeleton is mandatory

Every policy test class MUST have exactly 14 tests (the FK-6 pattern × 4 policies
× 2 indexes = 112 tests total). If a class has fewer, it's incomplete.

## 4. Known environment facts

- Benchmarks run on: AMD Ryzen 9 5900X (12C/24T), 64 GB RAM, NVMe SSD (970 EVO Plus)
- The suite grows every wave (~1.2k tests as of 2026-11) — **always take the live
  count from the run you just judged**, never a remembered number; `quick-gate`
  excludes the stress/integration tags and the documented-flaky families
- `gh` CLI is not installed locally — issues-sync runs on push
- `docs/tasks/discovery.md` is the maintainer's planning input
- CI has three legs on the same commit: `gate` and `jdk21` (`ubuntu-latest`), and
  `windows` (`windows-latest`, the non-stress suite, since 2.2.0). A test is green
  when all three are — a red `windows` leg is triaged in
  `docs/tasks/2.2.0/04-os-dependent-paths.md`
- **Windows facts for tests** (the first run, 2026-11-27: 25 red, all in the tests):
  the `.xdstorage.lock` of an open storage cannot be read or copied there (the lock
  is mandatory; on Linux it is advisory) — copy a storage folder with
  `io.github.xdstorage.helpers.StorageFolderCopy.copy(from, to)`, which leaves that
  file out, and skip it in any file dump; a `Path` says `\` there, so never spell
  `/` as the separator; worker names (`xdstorage-worker-N`) are per storage, so a
  daemon check looks at the thread that ran the storage's own task
- **A log capture in a test is a shared collection** (the second Windows run,
  2026-11-28: 1 red): the first `markRollbackOnly` arms the asynchronous rollback
  sweeper, which logs from an `xdstorage-worker-*` thread a moment later; and
  `config.getLoggerConfig("io.github.xdstorage.transaction.XdStorageTransaction")`
  is the `io.github.xdstorage` config — every WARN of the engine reaches the appender.
  Filter by `event.getLoggerName()`, read a snapshot under the appender's lock, count
  only the events naming the test's own transaction (the shape is in
  `XdStorageRollbackOnlyCauseTest.ListCaptureAppender`)
- surefire's class order is the file system's (`runOrder=filesystem`): sorted on NTFS
  (its documented enumeration — not yet read off a Windows report), hash order on ext4 —
  `-Dsurefire.runOrder=alphabetical` runs the Windows order on Linux

## 5. The release gate

Before release, the user asks "are all tests green?" — the answer must be:

```bash
# 3 consecutive green runs
mvn clean test -Dtest="io.github.xdstorage.foreignkey.**.*Test" -Pquick-gate
# → Tests run: 251, Failures: 0, Errors: 0, Skipped: 8, BUILD SUCCESS
```

Each run must show `BUILD SUCCESS` and `Failures: 0, Errors: 0`.
The 8 skipped tests are **expected** — they are documented disabled tests.
Any test that fails that is NOT in the disabled list is a **release-blocker**.
