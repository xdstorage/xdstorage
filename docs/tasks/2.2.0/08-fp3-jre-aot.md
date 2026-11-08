---
title: "[2.2.0][JRE] FP-3 pulled forward: AOT wrapper generation — run on a JRE, no JDK in the container"
labels: [2.2.0-train, P0, engine, feature]
status: open
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
