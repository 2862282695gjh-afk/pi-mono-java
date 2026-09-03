#!/usr/bin/env bash
# Enforces a per-commit limit on added non-documentation lines.

set -euo pipefail

readonly MAX_ADDED_LINES=2000

usage() {
  echo "Usage: $0 <base-revision> <head-revision>" >&2
  exit 2
}

die() {
  echo "[commit-additions] ERROR: $*" >&2
  exit 2
}

is_documentation_path() {
  local path="$1"
  local name="${path##*/}"

  case "$path" in
    docs/* | */docs/* | doc/* | */doc/* | documentation/* | */documentation/*)
      return 0
      ;;
    *.[mM][dD] | *.[mM][dD][xX] | *.[rR][sS][tT] | *.[aA][dD][oO][cC] | *.[aA][sS][cC][iI][iI][dD][oO][cC])
      return 0
      ;;
  esac

  case "$name" in
    README | README.* | CHANGELOG | CHANGELOG.* | CONTRIBUTING | CONTRIBUTING.* | LICENSE | LICENSE.* | NOTICE | NOTICE.*)
      return 0
      ;;
  esac

  return 1
}

check_commit() {
  local commit="$1"
  local parent
  local entry
  local additions
  local remainder
  local path
  local total=0
  local subject
  local short_sha
  local -a added_files=()

  parent="$(git rev-parse "${commit}^" 2>/dev/null || true)"
  if [[ -z "$parent" ]]; then
    parent="$(git hash-object -t tree /dev/null)"
  fi

  while IFS= read -r -d '' entry; do
    additions="${entry%%$'\t'*}"
    remainder="${entry#*$'\t'}"
    remainder="${remainder#*$'\t'}"
    path="$remainder"

    if [[ -z "$path" ]]; then
      IFS= read -r -d '' _ || die "cannot parse renamed path in $commit"
      IFS= read -r -d '' path || die "cannot parse renamed path in $commit"
    fi

    if [[ "$additions" == "-" ]] || is_documentation_path "$path"; then
      continue
    fi

    total=$((total + additions))
    if (( additions > 0 )); then
      added_files+=("+$additions $path")
    fi
  done < <(git diff-tree --numstat -z -M --no-commit-id -r "$parent" "$commit")

  short_sha="$(git rev-parse --short=12 "$commit")"
  subject="$(git show -s --format=%s "$commit")"
  if (( total <= MAX_ADDED_LINES )); then
    printf '[commit-additions] PASS %s: %d/%d added code lines - %s\n' \
      "$short_sha" "$total" "$MAX_ADDED_LINES" "$subject"
    return 0
  fi

  printf '[commit-additions] FAIL %s: %d/%d added code lines - %s\n' \
    "$short_sha" "$total" "$MAX_ADDED_LINES" "$subject" >&2
  printf '  Added code lines by file:\n' >&2
  printf '    %s\n' "${added_files[@]}" >&2
  return 1
}

[[ $# -eq 2 ]] || usage

base_revision="$1"
head_revision="$2"
git cat-file -e "${base_revision}^{commit}" 2>/dev/null || die "base revision is not a commit: $base_revision"
git cat-file -e "${head_revision}^{commit}" 2>/dev/null || die "head revision is not a commit: $head_revision"

range_base="$(git merge-base "$base_revision" "$head_revision")" \
  || die "base and head do not share a commit"
checked=0
failed=0

while IFS= read -r commit; do
  [[ -n "$commit" ]] || continue
  checked=$((checked + 1))
  if ! check_commit "$commit"; then
    failed=1
  fi
done < <(git rev-list --reverse --no-merges "${range_base}..${head_revision}")

merge_count="$(git rev-list --count --merges "${range_base}..${head_revision}")"
if (( merge_count > 0 )); then
  printf '[commit-additions] INFO: skipped %d merge commit(s); their non-merge commits were checked.\n' "$merge_count"
fi

if (( checked == 0 )); then
  echo "[commit-additions] PASS: no non-merge commits to check."
elif (( failed == 0 )); then
  printf '[commit-additions] PASS: checked %d commit(s); limit is %d added code lines per commit.\n' \
    "$checked" "$MAX_ADDED_LINES"
fi

exit "$failed"
