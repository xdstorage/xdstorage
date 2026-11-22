#!/usr/bin/env python3
# PROFILE (docs/tasks/2.2.0/14): aggregates async-profiler collapsed stacks into the
# bottleneck ledger — inclusive attribution per frame, the subsystem rollup, and the
# top engine methods. Usage: render-profile-ledger.py <collapsed.txt> [<more.txt>...]
import collections
import sys

SUBSYSTEMS = [
    ("serialization (yaml codec)", ("serialization.yaml", "serialization.search")),
    ("btree", ("btree",)),
    ("resource/cache (2PC, records)", ("resource.cache", "resource.XdStorageResource", "resource.XdStorageAbstractResource")),
    ("resource manager/naming", ("resource.XdStorageResourcesManager", "resource.XdStorageAbstractResourcesManager", "resource.XdStorageResourceNamingService")),
    ("index (hash/btree idx)", ("index.",)),
    ("search", ("search.",)),
    ("transaction", ("transaction.",)),
    ("factories/cloner (wrappers)", ("factories.",)),
    ("reflection/metadata", ("utils.XdStorageClass", "utils.XdStorageObject", "utils.XdStorageReflection", "utils.XdStorageAutoSave", "utils.XdStorageAtomic", "utils.XdStorageIdGen")),
    ("lock", ("lock.",)),
    ("foreignkey", ("foreignkey.",)),
]

def classify(frame):
    # async-profiler 4.x emits JVM-slash names; normalize to dots first
    dotted = frame.replace("/", ".").replace("::", ".")
    if dotted.startswith(("G1", "GC", "OopOop", "memmove", "getrusage", "JVM_", "os::", "Malloc", "WorkerThread", "GCTask")) or dotted.startswith(("void G1", "G1Par", "G1Rebuild", "G1CM", "G1Task")):
        return "JVM/GC (native)"
    if dotted.startswith("io.github.xdstorage."):
        rest = dotted[len("io.github.xdstorage."):]
        for label, prefixes in SUBSYSTEMS:
            for prefix in prefixes:
                if rest.startswith(prefix):
                    return label
        return "io.github.xdstorage (other)"
    if dotted.startswith(("java.", "javax.", "jdk.", "sun.")):
        return "JDK"
    return "JVM/native (other)"

def main():
    inclusive = collections.Counter()
    leaf = collections.Counter()
    total = 0
    for path in sys.argv[1:]:
        for line in open(path, encoding="utf-8", errors="replace"):
            line = line.strip()
            if not line or ";" not in line:
                continue
            stack, _, cnt = line.rpartition(" ")
            try:
                count = int(cnt)
            except ValueError:
                continue
            total += count
            frames = [f.replace("/", ".").replace("::", ".") for f in stack.split(";")]
            for fr in frames:
                inclusive[fr] += count
            leaf[frames[-1]] += count
    if total == 0:
        print("no samples")
        return

    # The rollup by the LEAF frame's bucket — the EXCLUSIVE attribution (sums to 100%:
    # each sample counted once, where the time was actually spent). The inclusive method
    # ranking below does NOT sum to 100% (every stack frame gets the count).
    buckets = collections.Counter()
    for frame, count in leaf.items():
        buckets[classify(frame)] += count

    print(f"total samples: {total:,}")
    print()
    print("== The share by bucket (EXCLUSIVE — by the leaf frame; sums to ~100%) ==")
    for label, count in buckets.most_common():
        print(f"  {label:42s} {count:>10,}  {100.0 * count / total:5.1f}%")

    print()
    print("== Top engine methods (inclusive) ==")
    engine = [(f, c) for f, c in inclusive.items() if f.startswith("io.github.xdstorage.")]
    for frame, count in sorted(engine, key=lambda kv: -kv[1])[:25]:
        print(f"  {frame[:100]:100s} {count:>9,}  {100.0 * count / total:5.1f}%")

    print()
    print("== Top leaves (the real hot spots) ==")
    for frame, count in leaf.most_common(20):
        print(f"  {frame[:100]:100s} {count:>9,}  {100.0 * count / total:5.1f}%")

if __name__ == "__main__":
    main()
