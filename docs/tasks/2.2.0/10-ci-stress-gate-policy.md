---
title: "[2.2.0][CIGATE] CI policy: green gate + stress tests in the gate/nightly decision"
labels: [2.2.0-train, P1, ci]
status: open
---

## Summary

The discovery demands 'CI as green and keep it + enabled stress tests for the gate and
nightly build'. Current state: the gate is non-stress (the tag-driven exclusion), the
nightly runs the FULL suite ×3, stress tests stay excluded from release builds. Decide and
implement the policy: (a) the gate gains a bounded stress job (the quick-gate budget
question), (b) the nightly stress results become gating (a red stress family blocks the
release rather than being ledgered), (c) 'CI as green' = the remaining red (MassFill,
task 36) must be green before this policy can bind.

## Scope

1. The policy decision with the trade-offs written down (gate runtime, flake exposure).
2. The workflow changes; FLAKY.md's ledger semantics updated (a ledgered flake is a ticket,
   not a tolerance).
3. Depends on tasks 35/36 (2.1.0) being closed — a stress gate over red stress tests is
   meaningless.

Estimate: ~0.5–1 session.
