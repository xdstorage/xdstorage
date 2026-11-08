# XdStorage — Release Engineering Plan

Prepared 2026-10-20 (release-engineering pass over MASTER-PLAN Step 0). This document is the
operating manual for cutting releases: what is already in place, the exact first-release
checklist, the recurring release procedure, and standing recommendations.

- **Quality bar — split by who runs it:**
  - **The RC protocol (human, before the tag is cut)** — the FULL suite (`mvn clean test`,
    stress families included, EXCEPT the two tests excluded by the 2026-10-26 decision —
    XdStorageNestedTransactionIsolationStressTest and XdStorageFacadeStressTest, see
    [FLAKY.md](FLAKY.md) § Excluded by decision — the nightly applies the same exclusion)
    green ×3 with a Maven `BUILD SUCCESS` verdict (never a piped grep's exit code — the R-52
    gotcha), the JMH smoke row assertion passing, and every open [FLAKY.md](FLAKY.md) row
    named in the release notes.
  - **The tag pipeline (automated, on tag push)** — the NON-STRESS suite via the `release`
    profile (`mvn clean deploy -Prelease`: the profile excludes the `stress` / `integration`
    **tags** (T-2, primary mechanism since 2.1.0) with the legacy filename patterns
    (`**/*StressTest.java` + `XmlDataStorageMultithreadsTest` + the `integration` package) kept
    as a backstop until 3.0 — the slow, load-sensitive part of the run) plus the JMH smoke row
    assertion. The deploy phase cannot run unless the test phase passed — they share one
    Maven invocation.
  - Stress coverage is therefore mandatory twice around every release: nightly ×3 + RC
    protocol ×3. The pipeline skipping it is a speed choice, never a safety hole — do not
    tag without the RC protocol.
  - **Grouping contract (T-2):** a stress/integration class is BOTH named per convention
    (`*StressTest`, or living in the `integration` package) AND tagged
    (`@Tag("stress")` / `@Tag("integration")`). The pairing is enforced in both directions by
    `XdStorageTestGroupingContractTest` in the default run — a misnamed or untagged class fails
    the build instead of silently joining or leaving the release gate.
- **Versioning** — strict semver from 2.0.0. MAJOR = public API break or on-disk format break;
  MINOR = features, config-default changes, additive format changes; PATCH = fixes only.
- **Single source of truth for the version** — the pom `<version>`. It reaches the jar as the
  Maven-filtered `xdstorage.properties` resource and is exposed at runtime via
  `XdStorageProvider.getVersion()` / `storage.getVersion()`. The release workflow refuses to
  run when the tag and the pom version disagree.
- **On-disk format generations** — v1 (1.x era) / v2 (2.0 era), recorded per folder in
  `.xdstorage.meta`; the library refuses folders written by a newer generation and flags
  legacy v1 folders at open. A format generation bump is a MINOR event; removing read support
  for a generation is MAJOR. Bump `XdStorageFormatMeta.FORMAT_VERSION` only together with a
  migration section in [EMBEDDING.md](EMBEDDING.md).
- **Branches and tags** — work lands on the working branch and `main`; a release is an
  **annotated tag `vMAJOR.MINOR.PATCH`** on a commit where the gate is green (2.0.0 is the
  first tag ever). Hotfixes: branch `release/x.y` from the tag, cherry-pick, tag `x.y+1`.
- **Artifacts & channel** — `xdstorage-<version>.jar` + `-sources` + `-javadoc`, GPG-signed,
  published to Maven Central via the Central Portal; every release also creates a GitHub
  Release whose notes are the version's CHANGELOG section.

---

## 1. First release (2.0.0) — current situation

MASTER-PLAN Step 0 ("make it adoptable at all") is implemented except for the parts that
require accounts and secrets a human must create. Exit criterion (from the architect
evaluation): **a stranger can add the dependency from Central and read a license.**

### 1.1 Already in place (the prep commit)

| Item | Where | State |
|---|---|---|
| A2 API freeze | `FilesFormat` (JSON removed, F-5 decided), `XdStorageFormatMeta` (`.xdstorage.meta` marker, startup INFO, legacy detection, newer-format loud rejection), `getVersion()` on `IXdFileStorage` + `XdStorageProvider` | DONE (+ regression tests) |
| E-1 project meta | `LICENSE` (Apache-2.0), `CHANGELOG.md` (backfilled from the R-journals), pom version `2.0.0` + name/description/url/scm/licenses | DONE |
| E-2 build hygiene | pom `release` profile (source/javadoc jars, GPG, release-only enforcer: no SNAPSHOT deps, no duplicate versions), always-on enforcer (JDK 17+/Maven 3.6.3+), `project.build.outputTimestamp` | DONE |
| E-3 distribution | `central-publishing-maven-plugin` in the `release` profile, server id `central` (Central Portal — the 2025+ path for first-time publishers; supersedes the legacy-OSSRH plan) | SCAFFOLDED — activation below |
| E-4 release workflow | `.github/workflows/release.yml`: tag `v*` → version/tag sanity → JMH smoke rows → signed build + NON-STRESS suite + deploy in one Maven invocation (`-Prelease` excludes the stress families — see Quality bar) → GitHub Release with CHANGELOG section | DONE — activation below |
| E-5 RC protocol | documented in §1.2 step 5 / §2 | DONE (docs) — execution below |
| E-6 compatibility statement | [EMBEDDING.md](EMBEDDING.md) § Compatibility & support statement: JDK matrix, format matrix, 1.x JSON migration recipe, deprecation policy, flake/benchmark disclosure rules | DONE |
| D-3 dependency freshness | `.github/dependabot.yml` (maven + github-actions, weekly) | DONE |

### 1.2 Step-by-step: what remains before 2.0.0 is public

**EXECUTED — 2.0.0 shipped 2026-10-21** (tag `v2.0.0`, Central Portal + GitHub Release).
What this section cost in practice, on top of the plan: two Central validation rounds —
central-publishing-maven-plugin had to move to 0.12.0 (Maven 3.10's `maven-metadata-local.xml` /
`_remote.repositories` leaking into the bundle → "content that does NOT have a .pom file") and
the pom needed a `<developers>` section ("Developers information is missing"). Both are now
standing requirements; the recurring procedure in §2 inherits a working pipeline.

Historical checklist, kept for the record:

Every step is a human/infrastructure action unless marked otherwise; commands assume the repo
root on branch `xdstorage-2.0`.

1. **Decide the publishing identity.** DONE (2026-10-21): publishing as
   **`io.github.xdstorage`**. The repository moved to the GitHub organization
   `xdstorage` (github.com/xdstorage/xdstorage, transferred from `ewgeny/xdstore`) and the
   Central Portal namespace `io.github.xdstorage` is verified. The alternatives were
   impossible: `org.flib` needs flib.org (third-party since 2014), and `io.github.flib`
   needs the GitHub account `flib` (third-party). Java packages were migrated to
   `io.github.xdstorage` in the same release; the groupId is frozen from 2.0.0 on.
2. **Register the Central Portal namespace and create a user token** — these become
   `CENTRAL_USERNAME` / `CENTRAL_PASSWORD`. Namespace `io.github.xdstorage`: DONE
   (verified 2026-10-21). Remaining: create the Portal user token and wire the secrets (step 4).
3. **Create a signing key**: `gpg --full-generate-key` (RSA 4096, no expiry is fine for a
   project key), publish the public key to a keyserver (Central checks it), keep the private
   key in the CI secret store only.
4. **Wire GitHub secrets** (repo → Settings → Secrets → Actions):
   `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY` (`gpg --armor --export-secret-keys`),
   `GPG_PASSPHRASE`.
5. **Run the RC protocol** (E-5) on the exact commit you intend to tag:
   - `mvn clean test` **three consecutive times** green (locally and/or via the nightly job);
   - kill-9 recovery: start a process committing with `journalWrites=true`, `kill -9` it
     mid-load, reopen — the journal replay must restore a consistent state (the suite covers
     this family: `XdStorageW2ReopenBTreeTest` et al.; the manual kill-9 walk is the
     belt-and-braces check);
   - `durableWrites=true` spot benchmark; compare against [BENCHMARKS.md](BENCHMARKS.md);
   - 1.x migration walkthrough per [EMBEDDING.md](EMBEDDING.md) § Compatibility
     (`acceptMissingEofSentinel` on a 1.x YAML folder; the `*.json`→`*.xml` rename on a 1.x
     JSON-constant folder);
   - fresh-clone README walkthrough: `git clone` into a scratch dir, follow README
     Requirements/Quick start verbatim (JDK only, no IDE).
6. **Finalize release metadata on the release commit** (single commit "RELEASE: 2.0.0"):
   pom `<version>2.0.0</version>` (already set), `CHANGELOG.md` — replace "planned" with the
   release date, `project.build.outputTimestamp` — bump to the release moment. Verify:
   `mvn -Prelease -Dgpg.skip=true verify` produces the four artifacts.
7. **Tag and push:**
   ```bash
   git tag -a v2.0.0 -m "XdStorage 2.0.0 — first public release"
   git push origin v2.0.0
   ```
   The `release.yml` pipeline then runs the gate, the JMH smoke, deploys to Central and
   creates the GitHub Release. Watch the Actions run.
8. **Verify the exit criterion**: `https://central.sonatype.com` shows `io.github.xdstorage:xdstorage:2.0.0`
   (autoPublish may take ~10–30 min after validation), then the stranger test from a scratch
   project:
   ```bash
   mvn dependency:get -Dartifact=io.github.xdstorage:xdstorage:2.0.0
   ```
9. **Post-release**: add the LICENSE copyright holder / NOTICE if step 1 established a legal
   entity; announce with the CHANGELOG link; move the working branch to `2.0.1-SNAPSHOT`
   thinking (see §2 step 7); update the memory/journal with the release commit.

---

## 2. Recurring release procedure (follow releases)

Rough cadence per [MASTER-PLAN.md](MASTER-PLAN.md): 2.0.x/2.1 after Step 1, 2.1.x after
Step 2, 2.2 after Step 3, demand-driven minors after that. Version is chosen by content, not
ambition:

> API or on-disk format break → MAJOR. Anything user-visible that is not a fix (new query
> operators, a new config default, a new module) → MINOR. Fixes only → PATCH.

1. **Pick the train** from MASTER-PLAN; confirm its exit criteria are actually met (e.g. Step
   2's "nightly ×3 clean, JMH write ≥10× vs baseline, zero new flake rows" — a gate is not
   "probably fine").
2. **Freeze the branch.** No new scope after freeze; only fixes. If the train slipped in
   half-done work, cut it from the release, not the date.
3. **Bump the version** (`2.1.0`), bump `project.build.outputTimestamp`, write the CHANGELOG
   section (`## [2.1.0] — YYYY-MM-DD`, curated — never paste the R-journal). One commit:
   `RELEASE: 2.1.0`.
4. **Run the gates**: `mvn clean test` (FULL suite, stress included — not `quick-gate`, not
   `-Prelease`; the pipeline's non-stress run is not a substitute, it exists for tag-day
   speed), the JMH smoke, and — for any engine/write-path change — the nightly ×3. Compare
   JMH against [BENCHMARKS.md](BENCHMARKS.md); a >25% regression in any scenario is either
   fixed or disclosed in the release notes, never silent.
5. **RC** (for MINOR/MAJOR): tag `v2.1.0-rc1`, run the §1.2 step-5 protocol scaled to the
   change: recovery tests mandatory if the write path/journal/format touched; migration
   walkthrough mandatory if EMBEDDING.md's compatibility matrix changed. Fix-forward and
   re-RC (`-rc2`) — never mutate an RC tag.
6. **Final tag** `v2.1.0` → the release workflow ships it (non-stress suite + deploy in one
   invocation — the stress families already passed ×3 in this procedure's steps 4–5). Verify
   Central + the stranger test.
7. **Post-release housekeeping** (same day): `CHANGELOG.md` link-check; move the working
   version (`2.1.1-SNAPSHOT` mindset — bump the pom at the start of the next train instead of
   carrying SNAPSHOT through the cycle, per this project's no-SNAPSHOT enforcer rule);
   refresh [BENCHMARKS.md](BENCHMARKS.md) baseline for MINOR/MAJOR releases; update
   [FLAKY.md](FLAKY.md) if the RC protocol surfaced anything; journal the release in the
   MASTER-PLAN table.
8. **Hotfix flow** (production bug on the current minor): branch `release/2.1` from
   `v2.1.0`, cherry-pick/fix, run the FULL gate + the recovery family if the write path
   moved (hotfixes skip the RC protocol — that is exactly when the stress suite matters
   most), bump to `2.1.1`, CHANGELOG section, tag `v2.1.1`. Merge the fix back to the main
   line. Two hotfixes on the same tag = the next minor is due, not a third patch.

---

## 3. Standing recommendations for future release prep

1. **Legal/identity before first publish** — groupId namespace decided (2026-10-21:
   `io.github.xdstorage`, verified; see §1.2 step 1); still open: the LICENSE copyright
   holder (`XdStorage contributors` is fine for an unincorporated project; an entity name
   if one exists). After 2.0.0 the coordinates are frozen forever.
2. **Rehearse the pipeline before the real tag.** `release.yml` is tag-triggered; rehearse
   once with a throwaway tag (`v2.0.0-rc0`) against a scratch namespace, or temporarily point
   the deploy step at GitHub Packages, before cutting the real 2.0.0. A pipeline that has
   never run is not a pipeline.
3. **Reproducibility check** — once per release: build twice from the same tag in clean
   clones and diff the artifact checksums (`sha256sum target/xdstorage-*.jar`). The
   `outputTimestamp` property makes this work; if it drifts, a plugin stopped honoring it.
4. **Signing hygiene** — the private key lives only in CI secrets; publish the public key to
   multiple keyservers at creation; rotate by cross-signing, not by silently replacing.
5. **Keep doclint honest** — the `release` profile ships javadoc with `doclint=none` for
   2.0.0 (mixed-language sources). Tighten in a 2.0.x/2.1 window: enable HTML/doclint checks
   on the facade packages first, engine-internal packages can stay loose.
6. **The bundled `log4j2.xml` is a library smell** — the main jar ships a default log4j2
   config (rolling `xdstorage.log`), which can override an embedder's own configuration
   depending on classpath order. Before 2.1, move it to the test resources and document
   "bring your own log4j2 config" in EMBEDDING.md (the `-Dxdstorage.log.file` relocation
   switch only matters while the default exists).
7. **Dependabot discipline** — merge security bumps immediately; batch the rest monthly.
   Test-scope churn (JUnit/Mockito) batches freely; runtime deps (`log4j-core`,
   `commons-lang3`) get a suite run per bump — their history is exactly why D-3 exists.
8. **CHANGELOG is curation, not a journal dump** — three to eight bullets per section that a
   user can act on; the R-journals stay the deep history. The release workflow greps for the
   `## [<version>]` section and refuses to publish without one.
9. **Never release off a red nightly.** The one tolerated flake row (BTreeStress contention,
   Track B3) is documented and load-specific — if it fires at RC time, re-run once; twice is
   a new row, not bad luck.
10. **Format-version changes ride MINOR, announced one minor ahead** — if v3 is coming, 2.x
    release notes say so and EMBEDDING.md's matrix gains the v3 row before any code writes it.
11. **Benchmark the release, not the release candidate's vibes** — MINOR/MAJOR releases
    refresh [BENCHMARKS.md](BENCHMARKS.md) baselines so the *next* release has a real
    comparison point; the Step-2 gate (≥10× writes vs the 2026-10-18 baseline) depends on it.
12. **Scaling up later** — this manual checklist is right for a train of a few releases per
    year. If cadence grows (Step-4 ecosystem modules), adopt `versions-maven-plugin` +
    `maven-release-plugin` (or `jreleaser`) — but keep the gate/RC discipline of this document;
    tooling automates steps, it does not replace them.
