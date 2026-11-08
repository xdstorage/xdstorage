# Discovery directions

> **STATUS (architect mapping, 2026-10-26):** every direction below is mapped.
> **2.1.0 additions:** tasks 45 (commit-refusal diagnosis + rollback-failed-commit fix),
> 46 (the wrapper/observer leak). **The 2.2.0 train is compiled** in
> [2.2.0/README.md](2.2.0/README.md) — two parallel road maps (A: Memory/Consistency/
> Durability; B: JRE + server), 11 tasks + the announcement task. Already shipped:
> the durability journal item (W-1/W-3, 2.1.0). Per-direction annotations inline below
> in **[brackets]**.

## For analyzing and planning

### The first priority

- Find the reason why happens "Illegal state of record to commit" **[2.1.0 task 45]**
- Fix rollback failed commit error **[2.1.0 task 45]**
- B+Tree balancing and find on multi-tree (disabled tests should be enabled and b+tree should be fixed) **[2.2.0 task 01]**
- Do CI as green and keep it + enabled stress tests for the gate and nightly build **[gate green = 2.1.0 task 36; the stress-gate policy = 2.2.0 task 10]**
- Rebuild consistency core with standard primitives **[2.2.0 task 01 — amends the frozen F-2 with the new framing]**
- Replace quit self-heals with loud errors (exceptions) **[2.2.0 task 02]**
- Property tests for whole engine **[2.2.0 task 03]**
- OS dependent paths **[2.2.0 task 04]**

### Memory

- I would like to avoid holding full data of database in memory. **[2.2.0 task 05 = W-4d]**
- I would like to hold the data resource during transactions working with it. **[2.2.0 task 05, the retention contract]**
- Memory leak: XdStorageObjectUtils.getWrappedObjectOrSameObject doesn't clean objects if the object does't get identifier. **[2.1.0 task 46]**
- Location of b+tree indexes moving to indexes subdirectory of database directory **[2.2.0 task 07]**

### Durability

- Journal for objects of class with policy StoreAsClassObjects. **[SHIPPED — W-1/W-3, 2.1.0]**

### Consistency

- We need to throw exceptions: when index is cut, self-heal. **[2.2.0 task 02, the index-cut case]**
- WAL for transactions and transaction id at EOF in journals **[2.2.0 task 06]**
- Tests must not be so quiet, must be loud (as example do not skip object doesn't exist) **[2.2.0 task 02]**

## Planning trains (road maps)

- The first road map: Memory, Consistency, Durability **[2.2.0 Road Map A]**
- I need to use it with JRE, not JDK!!! We need the train by this way. **[2.2.0 Road Map B = FP-3 pulled forward, task 08]**
- I need to use it as server - additional option. We need the train by this way. **[2.2.0 task 09 — DESIGN ONLY first; contradicts the recorded no-servers positioning, the go/no-go is explicit]**

## Landing page (task for technical writer)

- I need to see "For what this storage" in the first lines on the landing page. **[2.1.0 task 29 shipped; re-verified in 44]**
- I would like to see "New way for AI to search for data" in the first lines on the landing page. **[2.1.0 task 44 scope added]**
- I would like to see, as example, git-friendly storage of tests' fixtures, or transactional configurations and nice diff **[2.1.0 task 44 scope added]**
- We need to explain the data stores as readable data for scan without ORM and Transformation layers. **[2.1.0 task 44 scope added]**
- Why not SQLite? Why this storage is better? **[SHIPPED — DECISION-GUIDE comparison table]**

## Acceptance criteria

- [x] Compiled tasks (issues) for the train 2.2.0 (2 parallel road maps) — docs/tasks/2.2.0/
- [x] The task to write Feature announcement for the train 2.2.0 (landing page and guide) — 2.2.0 task 11
- [x] Maybe some issues should be added to the train 2.1.0 — tasks 45/46 added
- [x] Do not forget the tasks which were planned earlier — the 2.1.0 open set (16/25/27/33/35/36x2/38/39) carries them; W-4b is the next write-path phase