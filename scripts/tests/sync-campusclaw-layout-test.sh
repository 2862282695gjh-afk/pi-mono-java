#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CANONICAL_SCHEMA="$ROOT/modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_schema.sql"
CORPORATE_HEADER="$ROOT/scripts/templates/initdb_gaussdbv5-header.sql"
MIRROR_INSTALL_DIR="$ROOT/campusclaw/scripts/install"
MIRROR_SCRIPT="$MIRROR_INSTALL_DIR/initdb_gaussdbv5.sql"
STAGED_SCRIPT="$ROOT/build/campusclaw/scripts/install/initdb_gaussdbv5.sql"

fail() {
  printf '[layout-test] ERROR: %s\n' "$*" >&2
  exit 1
}

"$ROOT/scripts/sync-campusclaw.sh" --no-apply --no-verify --no-tests >/dev/null

[ ! -e "$ROOT/campusclaw/src/main/resources/db/gaussdb" ] \
  || fail "GaussDB scripts remain under campusclaw classpath resources"
[ ! -e "$ROOT/build/campusclaw/src/main/resources/db/gaussdb" ] \
  || fail "GaussDB scripts remain under staged classpath resources"
[ -f "$MIRROR_SCRIPT" ] || fail "missing corporate database install script"
[ -f "$STAGED_SCRIPT" ] || fail "missing staged corporate database install script"
[ -f "$CORPORATE_HEADER" ] || fail "missing corporate database script header"

unexpected_entry="$(find "$MIRROR_INSTALL_DIR" -mindepth 1 -maxdepth 1 \
  ! -name 'initdb_gaussdbv5.sql' -print -quit)"
[ -z "$unexpected_entry" ] || fail "unexpected install entry: $unexpected_entry"

cmp -s "$MIRROR_SCRIPT" "$STAGED_SCRIPT" \
  || fail "corporate database install script differs from staged output"

header_line_count="$(wc -l < "$CORPORATE_HEADER" | tr -d ' ')"
head -n "$header_line_count" "$MIRROR_SCRIPT" | cmp -s "$CORPORATE_HEADER" - \
  || fail "corporate database install script does not start with the required header"

cmp -s \
  <(awk '$1 == "CREATE" && $2 == "TABLE" { print $3 }' "$CANONICAL_SCHEMA") \
  <(awk '$1 == "CREATE" && $2 == "TABLE" { print $3 }' "$MIRROR_SCRIPT") \
  || fail "corporate database tables differ from canonical schema"

if grep -Eiq '^[[:space:]]*(BEGIN|COMMIT)[[:space:]]*;' "$MIRROR_SCRIPT"; then
  fail "corporate database install script contains BEGIN or COMMIT"
fi

COMMON_SOURCE="$ROOT/modules/common/src/main/java/com/campusclaw/common/constant/ClawConstants.java"
STAGED_COMMON="$ROOT/build/campusclaw/src/main/java/com/huawei/hicampus/claw/common/constant/ClawConstants.java"
[ -f "$STAGED_COMMON" ] || fail "common constants are missing from the staged mirror"
expected_common="$(sed 's/com\.campusclaw/com.huawei.hicampus.claw/g' "$COMMON_SOURCE")" \
  || fail "cannot read canonical common constants"
staged_common="$(cat "$STAGED_COMMON")" || fail "cannot read staged common constants"
[ "$expected_common" = "$staged_common" ] || fail "staged common constants differ from canonical source"

printf '[layout-test] CampusClaw database script and common-module layouts are valid.\n'
