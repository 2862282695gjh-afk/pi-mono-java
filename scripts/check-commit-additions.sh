#!/usr/bin/env bash
# Enforces a per-pull-request limit on added non-documentation lines.

set -euo pipefail

readonly MAX_ADDED_LINES=2000

usage() {
  echo "Usage: $0 <base-revision> <head-revision>" >&2
  exit 2
}

die() {
  echo "[pr-additions] ERROR: $*" >&2
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

check_pull_request() {
  local range_base="$1"
  local head_revision="$2"
  local stats_file="$work_directory/stats"
  local entry
  local additions
  local remainder
  local path
  local old_path
  local counted_additions
  local renamed
  local total=0
  local -a added_files=()

  if ! git diff --numstat -z -M --no-ext-diff --no-textconv \
    "$range_base" "$head_revision" > "$stats_file"; then
    die "cannot read pull-request diff from $range_base to $head_revision"
  fi

  while IFS= read -r -d '' entry; do
    additions="${entry%%$'\t'*}"
    remainder="${entry#*$'\t'}"
    remainder="${remainder#*$'\t'}"
    path="$remainder"
    old_path=""
    renamed=false

    if [[ -z "$path" ]]; then
      IFS= read -r -d '' old_path || die "cannot parse old renamed path"
      IFS= read -r -d '' path || die "cannot parse renamed path"
      renamed=true
    fi

    if [[ "$additions" == "-" ]] || is_documentation_path "$path"; then
      continue
    fi
    if [[ ! "$additions" =~ ^[0-9]+$ ]]; then
      die "cannot parse added-line count for $path"
    fi

    counted_additions="$additions"
    if $renamed && is_documentation_path "$old_path"; then
      count_blob_lines "$head_revision" "$path"
      counted_additions="$blob_line_count"
    fi

    total=$((total + counted_additions))
    if (( counted_additions > 0 )); then
      added_files+=("+$counted_additions $path")
    fi
  done < "$stats_file"

  if (( total <= MAX_ADDED_LINES )); then
    printf '[pr-additions] PASS: %d/%d added code lines in pull request.\n' \
      "$total" "$MAX_ADDED_LINES"
    return 0
  fi

  printf '[pr-additions] FAIL: %d/%d added code lines in pull request.\n' \
    "$total" "$MAX_ADDED_LINES" >&2
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
check_pull_request "$range_base" "$head_revision"
