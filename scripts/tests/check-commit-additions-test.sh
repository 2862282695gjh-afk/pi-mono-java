#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
readonly CHECK_SCRIPT="$ROOT/scripts/check-commit-additions.sh"

test_repository="$(mktemp -d)"
trap 'rm -rf "$test_repository"' EXIT

git -C "$test_repository" init -q
git -C "$test_repository" config user.name "Commit Addition Test"
git -C "$test_repository" config user.email "commit-addition-test@example.com"

write_lines() {
  local count="$1"
  local path="$2"

  mkdir -p "$(dirname "$path")"
  awk -v count="$count" 'BEGIN { for (i = 1; i <= count; i++) print "line " i }' > "$path"
}

commit_all() {
  local message="$1"

  git -C "$test_repository" add .
  git -C "$test_repository" commit -q -m "$message"
  git -C "$test_repository" rev-parse HEAD
}

assert_passes() {
  local label="$1"
  local base="$2"
  local head="$3"
  local output

  if ! output="$(cd "$test_repository" && "$CHECK_SCRIPT" "$base" "$head" 2>&1)"; then
    printf 'FAIL: %s\n%s\n' "$label" "$output" >&2
    exit 1
  fi
}

assert_fails_with() {
  local label="$1"
  local base="$2"
  local head="$3"
  local expected="$4"
  local output

  if output="$(cd "$test_repository" && "$CHECK_SCRIPT" "$base" "$head" 2>&1)"; then
    printf 'FAIL: %s unexpectedly passed\n%s\n' "$label" "$output" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    printf 'FAIL: %s did not report %q\n%s\n' "$label" "$expected" "$output" >&2
    exit 1
  fi
}

write_lines 1 "$test_repository/README.md"
baseline="$(commit_all "baseline")"

write_lines 2000 "$test_repository/src/boundary.java"
write_lines 2500 "$test_repository/docs/large-reference.java"
write_lines 2500 "$test_repository/guide.md"
boundary="$(commit_all "accept boundary and documentation")"
assert_passes "accepts exactly 2000 code lines and ignores documentation" "$baseline" "$boundary"

write_lines 2001 "$test_repository/src/oversized.java"
oversized="$(commit_all "reject oversized code")"
assert_fails_with "rejects 2001 code lines" "$boundary" "$oversized" "2001/2000"

git -C "$test_repository" switch -q -c split-commits "$baseline"
write_lines 1500 "$test_repository/src/first.java"
commit_all "first acceptable commit" >/dev/null
write_lines 1500 "$test_repository/src/second.java"
split_head="$(commit_all "second acceptable commit")"
assert_passes "checks each commit instead of the aggregate diff" "$baseline" "$split_head"

git -C "$test_repository" mv src/first.java src/renamed.java
renamed_head="$(commit_all "rename code without additions")"
assert_passes "parses renamed paths without counting unchanged lines" "$split_head" "$renamed_head"

git -C "$test_repository" switch -q -c docs-only "$baseline"
write_lines 2500 "$test_repository/docs/design.html"
write_lines 2500 "$test_repository/REFERENCE.RST"
docs_head="$(commit_all "documentation only")"
assert_passes "ignores documentation paths and extensions" "$baseline" "$docs_head"

echo "PASS: commit addition limit tests"
