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

## External cross-reference (2026-11-23, the evaluation intake)

The external evaluation (§3.3.10) independently confirms the JDK requirement and adds
a design constraint for the AOT path: `./gencode` resolves against the PROCESS CWD
(`XdStorageClassGenerator.java:37/47`), so a container with a read-only or relocated
working directory breaks wrapper generation silently (the JRE log.error barrier
catches only the no-compiler case). The build-time generation path must not inherit
the CWD coupling; the runtime fallback keeps it only with the loud startup failure
this task already demands.
