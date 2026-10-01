#!/usr/bin/env bash
#
# Rebases this fork's own commits onto Infomaniak's upstream kDrive.
#
# Usage: .github/fork-sync/sync.sh [<upstream-ref>]
#
#   <upstream-ref>  Upstream tag, branch or commit to rebase onto. Defaults to the latest upstream
#                   release tag (X.Y.Z), or to upstream's development branch when UPSTREAM_TRACK=main.
#
# Environment:
#   UPSTREAM_URL       Upstream repository (default: https://github.com/Infomaniak/android-kDrive.git)
#   UPSTREAM_BRANCH    Upstream development branch (default: main)
#   UPSTREAM_TRACK     "release" (default) or "main"
#   ABORT_ON_CONFLICT  "1" aborts the rebase on conflict (CI). Otherwise the rebase is left in
#                      progress so the conflict can be resolved with `git rebase --continue`.
#
# The fork's own commits are the ones between the most recent upstream commit in the current
# branch's history and its tip. They are replayed with `git rebase --onto`, so upstream history
# is never rewritten and nothing that is already upstream gets replayed.
#
# Exit codes: 0 = already up to date or rebased, 2 = rebase conflict, 1 = any other error.
# When GITHUB_OUTPUT is set, the result is also written there for the workflow.

set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/Infomaniak/android-kDrive.git}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-main}"
UPSTREAM_TRACK="${UPSTREAM_TRACK:-release}"
REMOTE=upstream
# Upstream tags are kept in their own namespace so they never mix with (or get pushed as) the fork's tags.
TAGS_NS=refs/upstream-tags

log() { echo "fork-sync: $*" >&2; }
die() { log "error: $*"; exit 1; }

output() { # output <name> <value>, multi-line safe
    [[ -n "${GITHUB_OUTPUT:-}" ]] || return 0
    local delimiter="EOF_$RANDOM$RANDOM"
    printf '%s<<%s\n%s\n%s\n' "$1" "$delimiter" "$2" "$delimiter" >> "$GITHUB_OUTPUT"
}

git rev-parse --is-inside-work-tree > /dev/null 2>&1 || die "not inside a git repository"
cd "$(git rev-parse --show-toplevel)"

[[ -d "$(git rev-parse --git-path rebase-merge)" || -d "$(git rev-parse --git-path rebase-apply)" ]] &&
    die "a rebase is already in progress; finish it with 'git rebase --continue' or 'git rebase --abort'"
if ! git diff --quiet || ! git diff --cached --quiet; then
    die "the working tree has uncommitted changes"
fi
branch="$(git symbolic-ref --quiet --short HEAD)" || die "HEAD is detached; check out the branch to sync first"

if ! git remote get-url "$REMOTE" > /dev/null 2>&1; then
    log "adding remote '$REMOTE' -> $UPSTREAM_URL"
    git remote add "$REMOTE" "$UPSTREAM_URL"
fi

log "fetching $REMOTE ($(git remote get-url "$REMOTE"))"
git fetch --quiet --no-tags "$REMOTE" \
    "+refs/heads/$UPSTREAM_BRANCH:refs/remotes/$REMOTE/$UPSTREAM_BRANCH" \
    "+refs/tags/*:$TAGS_NS/*"
upstream_head="refs/remotes/$REMOTE/$UPSTREAM_BRANCH"

if [[ $# -gt 0 && -n "$1" ]]; then
    target="$1"
    if git rev-parse --verify --quiet "$TAGS_NS/$target^{commit}" > /dev/null; then
        target_rev="$TAGS_NS/$target"
    else
        target_rev="$target"
    fi
elif [[ "$UPSTREAM_TRACK" == main ]]; then
    target="$UPSTREAM_BRANCH"
    target_rev="$upstream_head"
elif [[ "$UPSTREAM_TRACK" == release ]]; then
    target="$(git for-each-ref --format='%(refname:lstrip=2)' "$TAGS_NS" |
        grep -E '^[0-9]+(\.[0-9]+)+$' | sort -V | tail -n 1 || true)"
    [[ -n "$target" ]] || die "no upstream release tag found"
    target_rev="$TAGS_NS/$target"
else
    die "UPSTREAM_TRACK must be 'release' or 'main', not '$UPSTREAM_TRACK'"
fi
target_sha="$(git rev-parse --verify --quiet "$target_rev^{commit}")" || die "unknown upstream ref '$target'"

old_head="$(git rev-parse HEAD)"
# The newest commit shared by this branch and upstream (its development branch or the target).
old_base="$(git merge-base HEAD "$upstream_head" "$target_sha")" ||
    die "this branch shares no history with $REMOTE"
fork_commits="$(git rev-list --count --no-merges "$old_base..HEAD")"

output target "$target"
output target_sha "$target_sha"
output old_head "$old_head"
output old_base "$old_base"
output fork_commits "$fork_commits"

log "branch:       $branch ($fork_commits fork commit(s) on top of upstream ${old_base:0:9})"
log "upstream ref: $target (${target_sha:0:9})"

if git merge-base --is-ancestor "$target_sha" "$old_base"; then
    log "already up to date with $target"
    output rebased false
    output new_head "$old_head"
    exit 0
fi

log "rebasing $fork_commits commit(s) onto $target"
if git rebase --onto "$target_sha" "$old_base"; then
    new_head="$(git rev-parse HEAD)"
    output rebased true
    output new_head "$new_head"
    log "rebased onto $target:"
    git --no-pager log --oneline --no-decorate "$target_sha..HEAD" >&2
    [[ -n "${GITHUB_ACTIONS:-}" ]] ||
        log "review the result, then publish it with: git push --force-with-lease origin $branch"
    exit 0
fi

stopped_sha="$(git rev-parse --verify --quiet REBASE_HEAD || true)"
stopped_subject="$([[ -n "$stopped_sha" ]] && git log -1 --format=%s "$stopped_sha" || true)"
conflict_files="$(git diff --name-only --diff-filter=U)"
output rebased false
output conflict_commit "$stopped_sha"
output conflict_subject "$stopped_subject"
output conflict_files "$conflict_files"

log "conflict while applying ${stopped_sha:0:9} \"$stopped_subject\" onto $target"
log "conflicting files:"
sed 's/^/  /' <<< "$conflict_files" >&2

if [[ "${ABORT_ON_CONFLICT:-0}" == 1 ]]; then
    git rebase --abort
    log "rebase aborted; $branch is unchanged"
else
    log "resolve the conflicts, 'git add' the files and run 'git rebase --continue'"
    log "(or 'git rebase --abort' to give up), then: git push --force-with-lease origin $branch"
fi
exit 2
