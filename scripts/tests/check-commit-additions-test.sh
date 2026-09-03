#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
readonly CHECK_SCRIPT="$ROOT/scripts/check-commit-additions.sh"

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT

initialize_repository() {
  local name="$1"

  test_repository="$test_root/$name"
  git init -q -b main "$test_repository"
  git -C "$test_repository" config user.name "Pull Request Addition Test"
  git -C "$test_repository" config user.email "pull-request-addition-test@example.com"
}

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

assert_exits_with() {
  local label="$1"
  local expected_status="$2"
  local base="$3"
  local head="$4"
  local expected_output="$5"
  local output
  local status

  set +e
  output="$(cd "$test_repository" && "$CHECK_SCRIPT" "$base" "$head" 2>&1)"
  status=$?
  set -e
  if [[ "$status" -ne "$expected_status" ]] || [[ "$output" != *"$expected_output"* ]]; then
    printf 'FAIL: %s expected status %d and output %q, got status %d\n%s\n' \
      "$label" "$expected_status" "$expected_output" "$status" "$output" >&2
    exit 1
  fi
}

test_basic_limits() {
  local baseline
  local boundary
  local oversized
  local split_head
  local renamed_head
  local docs_head
  local reduced_head

  initialize_repository basic
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
  assert_fails_with "rejects aggregate additions split across commits" \
    "$baseline" "$split_head" "3000/2000"

  git -C "$test_repository" mv src/first.java src/renamed.java
  renamed_head="$(commit_all "rename code without additions")"
  assert_passes "keeps code-to-code pure renames at zero" "$split_head" "$renamed_head"

  git -C "$test_repository" switch -q -c docs-only "$baseline"
  write_lines 2500 "$test_repository/docs/design.html"
  write_lines 2500 "$test_repository/REFERENCE.RST"
  docs_head="$(commit_all "documentation only")"
  assert_passes "ignores documentation paths and extensions" "$baseline" "$docs_head"

  git -C "$test_repository" switch -q -c final-diff "$baseline"
  write_lines 2500 "$test_repository/src/reduced.java"
  commit_all "add transient oversized content" >/dev/null
  write_lines 1000 "$test_repository/src/reduced.java"
  reduced_head="$(commit_all "reduce content before review")"
  assert_passes "measures the final pull-request diff instead of historical churn" \
    "$baseline" "$reduced_head"
}

test_document_to_code_renames() {
  local baseline
  local renamed_head
  local edited_head

  initialize_repository document-renames
  write_lines 2001 "$test_repository/docs/large.java"
  baseline="$(commit_all "documentation baseline")"

  mkdir -p "$test_repository/src"
  git -C "$test_repository" mv docs/large.java src/large.java
  renamed_head="$(commit_all "move documentation into code")"
  assert_fails_with "counts full content moved from documentation to code" \
    "$baseline" "$renamed_head" "2001/2000"

  git -C "$test_repository" switch -q -c edited-rename "$baseline"
  mkdir -p "$test_repository/src"
  git -C "$test_repository" mv docs/large.java src/large.java
  printf 'edited line\n' >> "$test_repository/src/large.java"
  edited_head="$(commit_all "move and edit documentation into code")"
  assert_fails_with "counts full edited content moved from documentation to code" \
    "$baseline" "$edited_head" "2002/2000"
}

test_merge_commits() {
  local feature_head
  local main_head
  local clean_merge_head
  local merge_only_head

  initialize_repository merges
  write_lines 1 "$test_repository/base.txt"
  commit_all "baseline" >/dev/null

  git -C "$test_repository" switch -q -c feature
  write_lines 1 "$test_repository/src/feature.java"
  feature_head="$(commit_all "feature change")"
  git -C "$test_repository" switch -q main
  write_lines 1 "$test_repository/src/main.java"
  main_head="$(commit_all "main change")"

  git -C "$test_repository" switch -q feature
  git -C "$test_repository" merge -q --no-edit main
  clean_merge_head="$(git -C "$test_repository" rev-parse HEAD)"
  assert_passes "does not charge target history in a clean merge" "$main_head" "$clean_merge_head"

  git -C "$test_repository" switch -q -c merge-only "$feature_head"
  git -C "$test_repository" merge -q --no-commit main >/dev/null 2>&1
  write_lines 2001 "$test_repository/src/merge-only.java"
  merge_only_head="$(commit_all "merge with additional code")"
  assert_fails_with "counts content unique to a merge commit" \
    "$main_head" "$merge_only_head" "2002/2000"
}

test_conflict_resolution() {
  local main_head
  local merge_status
  local conflict_head

  initialize_repository conflict
  write_lines 1 "$test_repository/src/conflict.java"
  commit_all "baseline" >/dev/null
  git -C "$test_repository" switch -q -c feature
  printf 'feature\n' > "$test_repository/src/conflict.java"
  commit_all "feature conflict" >/dev/null
  git -C "$test_repository" switch -q main
  printf 'main\n' > "$test_repository/src/conflict.java"
  main_head="$(commit_all "main conflict")"
  git -C "$test_repository" switch -q feature

  set +e
  git -C "$test_repository" merge -q --no-commit main >/dev/null 2>&1
  merge_status=$?
  set -e
  if [[ "$merge_status" -eq 0 ]]; then
    echo "FAIL: expected merge conflict" >&2
    exit 1
  fi
  write_lines 2001 "$test_repository/src/conflict.java"
  git -C "$test_repository" add src/conflict.java
  conflict_head="$(commit_all "resolve conflict with oversized code")"
  assert_fails_with "counts oversized conflict resolution" \
    "$main_head" "$conflict_head" "2001/2000"
}

test_git_read_failure() {
  local baseline
  local head
  local tree
  local object_path

  initialize_repository git-failure
  write_lines 1 "$test_repository/src/base.java"
  baseline="$(commit_all "baseline")"
  write_lines 1 "$test_repository/src/head.java"
  head="$(commit_all "head")"
  tree="$(git -C "$test_repository" rev-parse 'HEAD^{tree}')"
  object_path="$test_repository/.git/objects/${tree:0:2}/${tree:2}"
  mv "$object_path" "$object_path.missing"

  assert_exits_with "fails closed when a pull-request tree is unreadable" 2 \
    "$baseline" "$head" "cannot read pull-request diff"
}

test_basic_limits
test_document_to_code_renames
test_merge_commits
test_conflict_resolution
test_git_read_failure

echo "PASS: pull-request addition limit tests"
