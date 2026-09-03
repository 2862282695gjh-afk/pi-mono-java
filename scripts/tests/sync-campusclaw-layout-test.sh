#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CANONICAL_SCHEMA="$ROOT/modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_schema.sql"
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

unexpected_entry="$(find "$MIRROR_INSTALL_DIR" -mindepth 1 -maxdepth 1 \
  ! -name 'initdb_gaussdbv5.sql' -print -quit)"
[ -z "$unexpected_entry" ] || fail "unexpected install entry: $unexpected_entry"

cmp -s "$CANONICAL_SCHEMA" "$MIRROR_SCRIPT" \
  || fail "corporate database install script differs from canonical schema"
cmp -s "$CANONICAL_SCHEMA" "$STAGED_SCRIPT" \
  || fail "staged database install script differs from canonical schema"

printf '[layout-test] CampusClaw database script layout is valid.\n'
