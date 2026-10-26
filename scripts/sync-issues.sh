#!/usr/bin/env bash
# Idempotent GitHub-issue sync for ALL train work packages under docs/tasks/<version>/.
#
# Source of truth: each task file's frontmatter + body (in every docs/tasks/*/ directory)
#   title:  issue title — MUST carry the "[x.y.z]" train prefix (matching is GLOBAL, the
#           prefix prevents cross-train collisions)
#   labels: [a, b, c]    applied at creation; missing labels are ADDED at update (never removed)
#   status: open | done  <- the DEVELOPER sets 'done' when the task is finished;
#                          the next sync closes the matching GitHub issue
# Milestone = the directory name (docs/tasks/2.1.0 -> milestone "2.1.0"), attached at
# creation only if that milestone exists in the repo.
#
# OWNERSHIP MARKER: every created/updated issue body ends with
#   <!-- xdstorage-task: <relative task-file path> -->
# The sync matches issues by that marker FIRST (stable across title edits), falling back to
# exact-title matching for issues created before markers existed (auto-backfilled on the
# first update). Keep task FILE paths stable — a renamed file orphans its issue by design.
#
# State machine per task file (every case idempotent):
#   status=open,  no issue                    -> CREATE the issue (open)
#   status=open,  issue open, content matches -> nothing (in sync)
#   status=open,  issue open, CONTENT CHANGED -> UPDATE title/body/labels on the issue
#   status=open,  issue closed                -> update content + WARN (a machine never reopens)
#   status=done,  no issue                    -> CREATE, then CLOSE with an explanatory comment
#   status=done,  issue open                  -> UPDATE content (if changed), then CLOSE
#   status=done,  issue closed, content changed -> UPDATE the body (the closure stays)
#   status=done,  issue closed                -> nothing (in sync)
#
# Runs automatically via .github/workflows/issues-sync.yml on every push to main,
# or locally:  gh auth login && bash scripts/sync-issues.sh [--dry-run]
set -euo pipefail

TASKS_ROOT="docs/tasks"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1
shopt -s nullglob

command -v gh >/dev/null 2>&1 || { echo "gh CLI not installed - https://cli.github.com"; exit 1; }
[ -d "$TASKS_ROOT" ] || { echo "$TASKS_ROOT not found (run from the repository root)"; exit 1; }

# Token: gh reads GH_TOKEN; XDSTORAGE_ISSUES_TOKEN (the same secret the issues-sync
# workflow uses) is accepted as a fallback for local runs. Precedence: explicit
# GH_TOKEN > XDSTORAGE_ISSUES_TOKEN > gh's own stored auth (gh auth login).
if [ -z "${GH_TOKEN:-}" ] && [ -n "${XDSTORAGE_ISSUES_TOKEN:-}" ]; then
  export GH_TOKEN="$XDSTORAGE_ISSUES_TOKEN"
fi

created=0; closed=0; updated=0; insync=0; warned=0
SUMMARY="| action | count |
|---|---|
"

# --- 1. Labels (idempotent, global across trains) ----------------------------------------
label() {
  [ "$DRY_RUN" = 1 ] && return 0
  gh label create "$1" --color "$2" --description "$3" >/dev/null 2>&1 || true
}
label "P0"          "B60205" "Must land in the current train"
label "P1"          "D93F0B" "Should land in the current train"
label "P2"          "0E8A16" "Nice to have in the current train"
label "logging"     "5319E7" "Logging subsystem"
label "testing"     "2374CA" "Test estate: coverage, grouping, suites"
label "engine"      "F9D0C4" "Storage engine internals"
label "search"      "C2E0C6" "Search engine / query model"
label "docs"        "0075CA" "Documentation"
label "ci"          "E99695" "Build / CI / pipelines"
label "refactor"    "FBCA04" "Restructure without behavior change"
label "feature"     "A2EEEF" "New capability"
label "chore"       "CCCCCC" "Maintenance"
label "tech-debt"   "D4C5F9" "Known debt to pay down"
label "performance" "FEF2C0" "Performance work"
label "bug"         "EE0701" "Engine defect discovered by the test estate"

# --- 2. Milestones (existence cached per name; attached at creation only) ----------------
MILESTONE_CACHE=" "
milestone_arg() { # $1 = milestone name -> sets MILESTONE_ARG
  local m="$1"
  MILESTONE_ARG=()
  [ "$DRY_RUN" = 1 ] && return 0
  case "$MILESTONE_CACHE" in
    *" $m=yes "*) MILESTONE_ARG=(--milestone "$m"); return 0 ;;
    *" $m=no "*)  return 0 ;;
  esac
  if gh api "repos/:owner/:repo/milestones" --jq '.[].title' 2>/dev/null | grep -qx "$m"; then
    MILESTONE_CACHE="$MILESTONE_CACHE $m=yes "
    MILESTONE_ARG=(--milestone "$m")
  else
    MILESTONE_CACHE="$MILESTONE_CACHE $m=no "
  fi
}

# --- 3. Existing issues, fetched ONCE globally (bodies base64-encoded for line safety) ----
# Each element: "<number>\t<title>\t<STATE>\t<body_b64>\t<labels_csv>"
ISSUES_FILE=$(mktemp)
trap 'rm -f "$ISSUES_FILE"' EXIT
# a failed fetch must abort the run LOUDLY: continuing with an empty list would
# create duplicate issues for every task file
gh issue list --state all --limit 1000 \
    --json number,title,state,body,labels \
    --jq '.[] | [(.number|tostring), .title, .state,
                 (.body // "" | @base64),
                 ([.labels[].name] | sort | join(","))] | @tsv' > "$ISSUES_FILE"
mapfile -t ISSUES < "$ISSUES_FILE"

issue_lookup_line() { # $1 = exact title -> prints the full matched line, or returns 1
  printf '%s\n' ${ISSUES[@]+"${ISSUES[@]}"} | awk -F'\t' -v t="$1" '
    $2 == t { print; found = 1; exit }
    END { if (!found) exit 1 }'
}

issue_body_of() { # $1 = issue number -> prints the stored (decoded) body
  local n="$1" line b64
  for line in ${ISSUES[@]+"${ISSUES[@]}"}; do
    if [ "${line%%$'\t'*}" = "$n" ]; then
      printf '%s' "$line" | awk -F'\t' '{print $4}' | base64 -d
      return 0
    fi
  done
}

issue_labels_of() { # $1 = issue number -> prints the stored labels CSV (sorted; or "")
  local n="$1" line
  for line in ${ISSUES[@]+"${ISSUES[@]}"}; do
    if [ "${line%%$'\t'*}" = "$n" ]; then
      printf '%s' "$line" | awk -F'\t' '{print $5}'
      return 0
    fi
  done
}

marker_for() { # $1 = task file path -> the ownership marker line
  printf '<!-- xdstorage-task: %s -->' "$1"
}

# normalize: CR stripped; trailing newlines trimmed by command substitution
norm() { printf '%s' "$1" | sed 's/\r$//'; }

body_of() { # task file -> issue body (frontmatter stripped) + the ownership marker
  local f="$1"
  sed '1{/^---$/d}' "$f" | sed '1,/^---$/d'
  printf '\n%s\n' "$(marker_for "$f")"
}

create_issue() { # $1 = file, $2 = title, $3 = comma labels -> prints issue number
  local tmp num
  tmp=$(mktemp)
  body_of "$1" > "$tmp"
  # shellcheck disable=SC2086
  num=$(gh issue create --title "$2" --body-file "$tmp" --label "$3" \
        ${MILESTONE_ARG[@]+"${MILESTONE_ARG[@]}"} | grep -oE '[0-9]+$')
  rm -f "$tmp"
  printf '%s' "$num"
}

update_issue_content() { # $1 = number, $2 = new title ("" = unchanged), $3 = file, $4 = current labels csv, $5 = wanted labels csv
  local num="$1" newtitle="$2" f="$3" cur="$4" want="$5"
  local tmp
  tmp=$(mktemp)
  body_of "$f" > "$tmp"
  local args=()
  [ -n "$newtitle" ] && args+=(--title "$newtitle")
  args+=(--body-file "$tmp")
  # labels: ADD the missing ones; never remove (humans may have labeled by hand)
  local missing="" l
  local IFS=','
  for l in $want; do
    if ! printf '%s' "$cur" | tr ',' '\n' | grep -Fxq "$l"; then
      missing="${missing:+$missing,}$l"
    fi
  done
  unset IFS
  [ -n "$missing" ] && args+=(--add-label "$missing")
  if [ "$DRY_RUN" = 1 ]; then
    echo "(dry-run) would edit #$num"
  else
    # shellcheck disable=SC2086
    gh issue edit "$num" "${args[@]}" >/dev/null
  fi
  rm -f "$tmp"
}

close_issue() { # $1 = number, $2 = task file path
  gh issue close "$1" --reason completed --comment \
    "Automatically closed by the issues-sync workflow: the task file \`$2\` is marked \`status: done\` (the developer's completion mark)."
}

# --- 4. Per train directory, per task file -------------------------------------------------
for DIR in "$TASKS_ROOT"/*/; do
  MILESTONE="$(basename "${DIR%/}")"
  milestone_arg "$MILESTONE"
  # train label derived from the directory — a new train needs no script edit
  label "${MILESTONE}-train" "1D76DB" "Work package of the ${MILESTONE} release train"
  synced_in_dir=0

  for f in "$DIR"[0-9][0-9]-*.md; do
    [ -f "$f" ] || continue
    synced_in_dir=$((synced_in_dir+1))
    relpath="${f#./}"
    title=$(sed -n 's/^title: "\(.*\)"$/\1/p' "$f")
    labels=$(sed -n 's/^labels: \[\(.*\)\]$/\1/p' "$f" | tr -d ' ')
    status=$(sed -n 's/^status: \(.*\)$/\1/p' "$f")
    status=${status:-open}
    if [ -z "$title" ] || [ -z "$labels" ]; then
      echo "WARN: $f - missing/invalid frontmatter (title/labels), skipped"
      warned=$((warned+1)); continue
    fi
    case "$status" in open|done) ;; *)
      echo "WARN: $f - invalid status '$status' (open|done), skipped"
      warned=$((warned+1)); continue ;;
    esac

    marker=$(marker_for "$relpath")

    # --- ownership: marker match FIRST (stable across title edits), title fallback ------
    matched_line=""
    for line in ${ISSUES[@]+"${ISSUES[@]}"}; do
      b64=$(printf '%s' "$line" | awk -F'\t' '{print $4}')
      if printf '%s' "$b64" | base64 -d 2>/dev/null | grep -qF "$marker"; then
        matched_line="$line"
        break
      fi
    done
    if [ -z "$matched_line" ]; then
      matched_line=$(issue_lookup_line "$title" || true)
    fi

    num=""; state=""; stored_title=""
    if [ -n "$matched_line" ]; then
      num=$(printf '%s' "$matched_line" | awk -F'\t' '{print $1}')
      state=$(printf '%s' "$matched_line" | awk -F'\t' '{print $3}')
      stored_title=$(printf '%s' "$matched_line" | awk -F'\t' '{print $2}')
    fi

    # --- content drift: stored body/labels/title vs the file's current state ------------
    content_changed=0
    title_differs=0
    labels_differs=0
    if [ -n "$num" ]; then
      stored=$(issue_body_of "$num")
      expected=$(body_of "$f")
      [ "$(norm "$stored")" = "$(norm "$expected")" ] || content_changed=1
      printf '%s' "$stored" | grep -qF "$marker" || content_changed=1   # legacy body: no marker yet
      [ "$stored_title" = "$title" ] || title_differs=1
      cur_labels=$(issue_labels_of "$num")
      cur_sorted=$(printf '%s' "$cur_labels" | tr ',' '\n' | sort | tr '\n' ',' | sed 's/,$//')
      want_sorted=$(printf '%s' "$labels" | tr ',' '\n' | sort | tr '\n' ',' | sed 's/,$//')
      [ "$cur_sorted" = "$want_sorted" ] || labels_differs=1
    fi

    if [ -z "$num" ]; then
      if [ "$DRY_RUN" = 1 ]; then
        echo "PLAN: create '$title' (status: $status, milestone: $MILESTONE)"
      else
        newnum=$(create_issue "$f" "$title" "$labels")
        echo "created #$newnum: $title"
        if [ "$status" = "done" ]; then
          close_issue "$newnum" "$relpath"
          echo "closed  #$newnum (status: done at creation)"
          closed=$((closed+1))
        fi
      fi
      created=$((created+1)); continue
    fi

    case "$state:$status" in
      OPEN:open|CLOSED:done)
        if [ "$content_changed" = 1 ] || [ "$title_differs" = 1 ] || [ "$labels_differs" = 1 ]; then
          newtitle=""; [ "$title_differs" = 1 ] && newtitle="$title"
          if [ "$DRY_RUN" = 1 ]; then
            echo "PLAN: update #$num '$title' (content drifted)"
          else
            update_issue_content "$num" "$newtitle" "$f" "$cur_labels" "$labels"
            echo "updated #$num: $title"
          fi
          updated=$((updated+1))
        else
          insync=$((insync+1))
        fi ;;
      OPEN:done)
        if [ "$DRY_RUN" = 1 ]; then
          echo "PLAN: close #$num '$title' (status: done)"
        else
          if [ "$content_changed" = 1 ] || [ "$title_differs" = 1 ]; then
            update_issue_content "$num" "$title" "$f" "$cur_labels" "$labels"
          fi
          close_issue "$num" "$relpath"
          echo "closed  #$num: $title (status: done)"
        fi
        closed=$((closed+1)) ;;
      CLOSED:open)
        if [ "$content_changed" = 1 ]; then
          if [ "$DRY_RUN" = 1 ]; then
            echo "PLAN: update #$num body (closed; state mismatch warned)"
          else
            update_issue_content "$num" "" "$f" "$cur_labels" "$labels"
          fi
        fi
        echo "WARN: #$num '$title' is closed on GitHub but the file says status: open - left closed (a machine never reopens; fix the file or the issue by hand)"
        warned=$((warned+1)) ;;
    esac
  done

  [ "$synced_in_dir" -gt 0 ] && echo "train $MILESTONE: $synced_in_dir task file(s) processed"
done

echo "sync complete: $created created, $updated updated, $closed closed, $insync already in sync, $warned warnings"
SUMMARY="${SUMMARY}| created | ${created} |
| updated | ${updated} |
| closed | ${closed} |
| already in sync | ${insync} |
| warnings | ${warned} |
"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf '%s\n' "$SUMMARY" >> "${GITHUB_STEP_SUMMARY}"
fi
