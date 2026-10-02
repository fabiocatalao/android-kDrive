#!/usr/bin/env bash
#
# Keeps the fork-sync workflow's GitHub issue up to date.
#
# Usage: .github/fork-sync/report.sh failure | success
#
#   failure  Opens an issue explaining what failed and how to fix it, or updates the open one.
#            A failure that is already reported by the open issue adds nothing, so a daily
#            schedule doesn't pile up comments.
#   success  Closes the open issue, if any.
#
# Environment, set by the workflow: GH_TOKEN, ISSUE_LABEL, FLAVOR, the GITHUB_* defaults, the
# outcome of each workflow step (SYNC_OUTCOME, PLAN_OUTCOME, BUILD_OUTCOME, PUSH_OUTCOME,
# RELEASE_OUTCOME), and the outputs of sync.sh (TARGET, TARGET_SHA, REBASED, CONFLICT_COMMIT,
# CONFLICT_SUBJECT, CONFLICT_FILES) and of the plan step (PLAN_REASON).

set -euo pipefail

label="${ISSUE_LABEL:-fork-sync}"
run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
fence='```'

report_success() {
    local number
    for number in $(gh issue list --label "$label" --state open --json number --jq '.[].number'); do
        gh issue close "$number" --comment "Fixed: [this run]($run_url) synced and built \`main\` successfully."
    done
}

local_fix_steps() { # local_fix_steps <step that reproduces the problem>
    echo "${fence}sh"
    echo "git switch main"
    echo "git pull --rebase origin main   # main is rewritten by every sync"
    echo "$1"
    echo "git push --force-with-lease origin main"
    echo "$fence"
    echo
    echo "The push runs the workflow again; when it succeeds it publishes a release and closes this issue."
}

report_failure() {
    local title body
    if [[ "${SYNC_OUTCOME:-}" == failure && -n "${CONFLICT_COMMIT:-}" ]]; then
        title="Fork sync: conflict rebasing onto upstream $TARGET"
        body="$(
            echo "\`main\` could not be rebased onto upstream **$TARGET** (\`${TARGET_SHA:0:9}\`)."
            echo "Applying \`${CONFLICT_COMMIT:0:9}\` \"$CONFLICT_SUBJECT\" conflicts in:"
            echo
            [[ -z "$CONFLICT_FILES" ]] || sed 's/.*/- `&`/' <<< "$CONFLICT_FILES"
            echo
            echo "\`main\` was left unchanged. To resolve the conflict on your machine:"
            echo
            local_fix_steps "$(
                echo ".github/fork-sync/sync.sh $TARGET   # stops at the conflict"
                echo "# fix the files, then 'git add' them and 'git rebase --continue' (repeat if it stops again)"
            )"
            echo
            echo "Details: [workflow run]($run_url)."
        )"
    elif [[ "${BUILD_OUTCOME:-}" == failure && "${REBASED:-}" == true ]]; then
        title="Fork sync: build fails on upstream $TARGET"
        body="$(
            echo "\`main\` rebases cleanly onto upstream **$TARGET** (\`${TARGET_SHA:0:9}\`), but the result doesn't build."
            echo "That usually means upstream changed code that this fork's commits rely on."
            echo "\`main\` was left unchanged; the compiler errors are in the [workflow run]($run_url)."
            echo
            echo "To fix it on your machine:"
            echo
            local_fix_steps "$(
                echo ".github/fork-sync/sync.sh $TARGET"
                echo "./gradlew assemble${FLAVOR^}Debug   # fix the errors and commit the fix"
            )"
        )"
    elif [[ "${BUILD_OUTCOME:-}" == failure ]]; then
        title="Fork sync: main does not build"
        body="\`main\` (\`${GITHUB_SHA:0:9}\`) does not build: see the [workflow run]($run_url). Pushing a fix to \`main\` runs the workflow again."
    elif [[ "${PLAN_OUTCOME:-}" == failure && -n "${PLAN_REASON:-}" ]]; then
        title="Fork sync: setup needed"
        body="$PLAN_REASON"$'\n\n'"Setup instructions: [\`.github/fork-sync/README.md\`]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/blob/main/.github/fork-sync/README.md). Details: [workflow run]($run_url)."
    elif [[ "${PUSH_OUTCOME:-}" == failure ]]; then
        title="Fork sync: could not push the rebased main"
        body="$(
            echo "\`main\` was rebased onto upstream **$TARGET** and built, but pushing it failed: see the [workflow run]($run_url)."
            echo
            echo "If \`main\` changed while the run was going, the next run retries on its own. Otherwise, check that the"
            echo "\`SYNC_TOKEN\` secret hasn't expired and has read and write access to **Contents** and **Workflows** on this repository."
        )"
    elif [[ "${RELEASE_OUTCOME:-}" == failure ]]; then
        title="Fork sync: could not publish the release"
        body="The APK was built (and \`main\` updated, if it was rebased), but publishing the GitHub release failed: see the [workflow run]($run_url). Running the workflow manually with **force release** retries it."
    else
        title="Fork sync failed"
        body="The fork-sync workflow failed: see the [workflow run]($run_url)."
    fi

    local existing number existing_title
    existing="$(gh issue list --label "$label" --state open --limit 1 --json number,title --jq '.[0] // empty | "\(.number)\t\(.title)"')"
    if [[ -n "$existing" ]]; then
        IFS=$'\t' read -r number existing_title <<< "$existing"
        if [[ "$existing_title" == "$title" ]]; then
            echo "Already reported in issue #$number"
        else
            gh issue comment "$number" --body "$body"
            gh issue edit "$number" --title "$title"
        fi
        return
    fi

    gh label create "$label" --force --color D93F0B --description "Problems syncing this fork with upstream" || true
    gh issue create --title "$title" --body "$body" --label "$label" --assignee "$GITHUB_REPOSITORY_OWNER" ||
        gh issue create --title "$title" --body "$body" --label "$label"
}

case "${1:-}" in
    failure) report_failure ;;
    success) report_success ;;
    *) echo "usage: $0 failure | success" >&2; exit 1 ;;
esac
