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

write_commit_stats() {
  local commit="$1"
  local stats_file="$2"
  local parent_line
  local parent_count
  local empty_tree
  local -a commit_and_parents

  if ! parent_line="$(git rev-list --parents -n 1 "$commit")"; then
    die "cannot read parents for commit $commit"
  fi
  read -r -a commit_and_parents <<< "$parent_line"
  if [[ "${commit_and_parents[0]:-}" != "$commit" ]]; then
    die "cannot parse parents for commit $commit"
  fi

  parent_count=$((${#commit_and_parents[@]} - 1))
  case "$parent_count" in
    0)
      if ! empty_tree="$(git hash-object -t tree /dev/null)"; then
        die "cannot create empty tree for root commit $commit"
      fi
      if ! git diff-tree --numstat -z -M --no-commit-id -r "$empty_tree" "$commit" > "$stats_file"; then
        die "cannot read diff for root commit $commit"
      fi
      ;;
    1)
      if ! git diff-tree --numstat -z -M --no-commit-id -r \
        "${commit_and_parents[1]}" "$commit" > "$stats_file"; then
        die "cannot read diff for commit $commit"
      fi
      ;;
    2)
      if ! git show --remerge-diff --numstat -z -M --format= "$commit" > "$stats_file"; then
        die "cannot reconstruct merge-only diff for commit $commit"
      fi
      ;;
    *)
      die "octopus merge commits are unsupported: $commit"
      ;;
  esac
}

count_blob_lines() {
  local commit="$1"
  local path="$2"
  local blob_file="$work_directory/blob"

  if ! git show "${commit}:${path}" > "$blob_file"; then
    die "cannot read destination content for $path in $commit"
  fi
  if ! blob_line_count="$(awk 'END { print NR }' "$blob_file")"; then
    die "cannot count destination content for $path in $commit"
  fi
}

check_commit() {
  local commit="$1"
  local stats_file="$work_directory/stats-$commit"
  local entry
  local additions
  local remainder
  local path
  local old_path
  local counted_additions
  local renamed
  local total=0
  local subject
  local short_sha
  local -a added_files=()

  write_commit_stats "$commit" "$stats_file"

  while IFS= read -r -d '' entry; do
    additions="${entry%%$'\t'*}"
    remainder="${entry#*$'\t'}"
    remainder="${remainder#*$'\t'}"
    path="$remainder"
    old_path=""
    renamed=false

    if [[ -z "$path" ]]; then
      IFS= read -r -d '' old_path || die "cannot parse old renamed path in $commit"
      IFS= read -r -d '' path || die "cannot parse renamed path in $commit"
      renamed=true
    fi

    if [[ "$additions" == "-" ]] || is_documentation_path "$path"; then
      continue
    fi
    if [[ ! "$additions" =~ ^[0-9]+$ ]]; then
      die "cannot parse added-line count for $path in $commit"
    fi

    counted_additions="$additions"
    if $renamed && is_documentation_path "$old_path"; then
      count_blob_lines "$commit" "$path"
      counted_additions="$blob_line_count"
    fi

    total=$((total + counted_additions))
    if (( counted_additions > 0 )); then
      added_files+=("+$counted_additions $path")
    fi
  done < "$stats_file"

  if ! short_sha="$(git rev-parse --short=12 "$commit")"; then
    die "cannot abbreviate commit $commit"
  fi
  if ! subject="$(git show -s --format=%s "$commit")"; then
    die "cannot read subject for commit $commit"
  fi
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

if ! range_base="$(git merge-base "$base_revision" "$head_revision")"; then
  die "base and head do not share a commit"
fi
if ! work_directory="$(mktemp -d)"; then
  die "cannot create temporary work directory"
fi
trap 'rm -rf -- "$work_directory"' EXIT
commit_list="$work_directory/commits"
if ! git rev-list --reverse "${range_base}..${head_revision}" > "$commit_list"; then
  die "cannot enumerate commits from $range_base to $head_revision"
fi

checked=0
failed=0

while IFS= read -r commit; do
  [[ -n "$commit" ]] || continue
  checked=$((checked + 1))
  if ! check_commit "$commit"; then
    failed=1
  fi
done < "$commit_list"

if (( checked == 0 )); then
  echo "[commit-additions] PASS: no commits to check."
elif (( failed == 0 )); then
  printf '[commit-additions] PASS: checked %d commit(s); limit is %d added code lines per commit.\n' \
    "$checked" "$MAX_ADDED_LINES"
fi

exit "$failed"
