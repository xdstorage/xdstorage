---
title: "[2.2.0][PROCESS] The process posture decision: the commit-message convention; the direct-commit vs review posture; the self-authored review docs' labeling"
labels: [2.2.0-train, P3, chore]
status: open
---

## Summary

The evaluation's §4.3 process critiques, each needing an explicit DECISION rather than
a fix — the solo-maintainer context is real and several "findings" are the deliberate
model, not defects:

1. **The commit subjects average 287 characters (max 3 012).** The house journal
   lives IN the commit messages — that stays. But the SUBJECT line should be a
   ≤72-char handle (`TASKS 34-49: external-evaluation intake (gates, bugs, security)`)
   with the detail in the BODY: no information lost, the log/shortlog/blame become
   readable, and the external reviewers stop finding the repo "unreadable".
2. **339 of 341 commits to main are direct** while the branch is marked protected.
   Record the posture: the direct-commit model is the deliberate solo workflow (the
   push IS the review gate by construction — the maintainer reads every diff before
   pushing), OR a lightweight PR-flow for the risky waves only (the flag-gated cores,
   the migrations). The decision + a short CONTRIBUTING/process note.
3. **ARCHITECT-EVALUATION.md and PRODUCT_REVIEW.md read as role-generated AI
   documents committed as if external.** The evaluation is polite; the repo's own
   honesty culture demands better labeling: re-label them as internal analyses
   (the maintainer-directed AI-role reviews, dated) or move them under docs/analysis/
   with the other internal implementation plans. The external evaluation of
   2026-11-23 — an actual third-party review — becomes the reference point for what
   "external" means in this repo.

## Scope

1. The three decisions recorded in this task (each: the choice + the reason).
2. The commit-subject convention adopted going forward + the optional enforcement
   (a commit-msg hook or a light CI check; NOT a history rewrite).
3. The doc re-labels (the front-matter/header note on the two review docs).
4. The process note (CONTRIBUTING or docs/PROCESS.md) if decisions 2–3 warrant it.

## Out of scope

Any history rewriting; any CI-heavy enforcement; changing the task-file/sync-issues
workflow (it works).

## Acceptance criteria

- [ ] The three decisions recorded with their reasons.
- [ ] New commit subjects follow the ≤72+body convention (observable from this task's
      own commit onward).
- [ ] The two review docs honestly labeled.
- [ ] The process note landed (or the explicit decision that none is needed).

Estimate: ~0.5 session.

## References

- The external evaluation 2026-11-23, §4.3 (all three critiques; their numbers cited
  above) and §5 (the developer analysis that motivates the honest labeling)
- docs/ARCHITECT-EVALUATION.md, docs/PRODUCT_REVIEW.md (the docs to re-label)
- docs/analysis/README.md (the internal-analyses home)
