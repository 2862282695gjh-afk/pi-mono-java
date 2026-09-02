#!/usr/bin/env bash
#
# Syncs the in-tree campusclaw/ module from modules/{ai,agent-core,cron,
# coding-agent-cli}, applying the package rename
# com.campusclaw -> com.huawei.hicampus.claw, then verifies
# the result by compiling campusclaw/.
#
# Two phases:
#   1. STAGE  — generate build/campusclaw/ as a clean canonical tree.
#   2. APPLY  — rsync staged Java sources into campusclaw/ with --delete
#               (so renames/removals propagate), preserving paths listed in
#               scripts/sync-campusclaw-exclude.txt (mirror-only files).
#               Resources are handled with a small whitelist (database release
#               scripts, MyBatis mapper XML, schema.sql, i18n message bundles, and
#               AutoConfiguration.imports). Application config (.yml/.properties)
#               is hand-tuned and never touched except for the message bundles.
#
# Workflow:
#   ./mvnw -DskipTests package          # ensure modules/* compile first
#   ./scripts/sync-campusclaw.sh        # stage + apply + verify
#
# Flags:
#   --no-apply         stop after STAGE; don't touch campusclaw/
#   --no-verify        skip the mvn compile verification of campusclaw/
#   --dry-run          show what APPLY would change (rsync -n) without writing
#   --skip-resources   don't sync resources at all
#   --no-tests         don't sync src/test/java

set -euo pipefail

SRC_PKG="com.campusclaw"
DST_PKG="com.huawei.hicampus.claw"
SRC_PATH="com/campusclaw"
DST_PATH="com/huawei/hicampus/claw"

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/campusclaw"
MIRROR="$ROOT/campusclaw"
EXCLUDE_FILE="$ROOT/scripts/sync-campusclaw-exclude.txt"
MODULES=(ai agent-core cron coding-agent-cli)

# Resources we DO want to keep in sync from modules/* — anything else under
# src/main/resources/ on the mirror side is hand-tuned and skipped.
SYNCED_RESOURCES=(
  "db/gaussdb"
  "mapper"
  "schema.sql"
  "i18n"
  "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
)

# Legacy resources removed from the canonical module and therefore from the mirror.
REMOVED_RESOURCES=(
  "messages.properties"
  "messages_zh_CN.properties"
)

APPLY=true
VERIFY=true
SYNC_RESOURCES=true
SYNC_TESTS=true
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --no-apply)       APPLY=false ;;
    --no-verify)      VERIFY=false ;;
    --skip-resources) SYNC_RESOURCES=false ;;
    --no-tests)       SYNC_TESTS=false ;;
    --dry-run)        DRY_RUN=true ;;
    -h|--help)        sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

note()  { printf '\033[36m[sync]\033[0m %s\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }

validate_staged_tree() {
  local java_root unexpected_file

  if grep -R -I -q -F "$SRC_PKG" "$OUT/src"; then
    echo "source package remains in staged content: $SRC_PKG" >&2
    grep -R -I -n -F "$SRC_PKG" "$OUT/src" | head -10 >&2
    return 1
  fi

  for java_root in "$OUT/src/main/java" "$OUT/src/test/java"; do
    [ -d "$java_root" ] || continue
    unexpected_file="$(find "$java_root" -type f -name '*.java' \
      ! -path "$java_root/$DST_PATH/*" -print -quit)"
    if [ -n "$unexpected_file" ]; then
      echo "Java source is outside the target package tree: $unexpected_file" >&2
      return 1
    fi
  done
}

# The project requires JDK 21, but JAVA_HOME may point to another version.
detect_jdk21() {
    if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then return 0; fi
    if [ -d "/opt/homebrew/Cellar/openjdk@21" ]; then
        JAVA_HOME="$(find /opt/homebrew/Cellar/openjdk@21 -maxdepth 1 -mindepth 1 -type d | head -1)/libexec/openjdk.jdk/Contents/Home"
        return 0
    fi
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        local jh; jh="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        [ -n "$jh" ] && { JAVA_HOME="$jh"; return 0; }
    fi
    if [ -d "${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java" ]; then
        local jdk21; jdk21="$(find "${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java" -maxdepth 1 -name '21*' -type d | head -1)"
        [ -n "$jdk21" ] && { JAVA_HOME="$jdk21"; return 0; }
    fi
    for dir in /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/temurin-21* /usr/lib/jvm/java-21*; do
        [ -d "$dir" ] && { JAVA_HOME="$dir"; return 0; }
    done
    return 1
}

verify_native_parent() {
    note "Checking NativeParent resolution in the configured Maven repositories"
    if ( cd "$MIRROR" \
        && "$ROOT/mvnw" -q -DskipTests \
             help:evaluate -Dexpression=project.parent.id -DforceStdout >/dev/null ); then
        return 0
    fi
    echo "cannot resolve com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT" >&2
    echo "configure the company Maven repository, or rerun explicitly with --no-verify" >&2
    return 1
}

if $VERIFY && $APPLY && ! $DRY_RUN; then
  if ! detect_jdk21; then
    echo "JDK 21 not found — install it or set JAVA_HOME, or rerun with --no-verify." >&2
    exit 1
  fi
  export JAVA_HOME
  verify_native_parent
fi

# ============================================================
# Phase 1: STAGE — regenerate $OUT from modules/*
# ============================================================

case "$OUT" in
  "$ROOT/build/"*) ;;
  *) echo "refusing to clean $OUT (must be under \$ROOT/build)"; exit 1 ;;
esac

note "Cleaning $OUT"
rm -rf "$OUT"
mkdir -p "$OUT/src/main/java/$DST_PATH" "$OUT/src/main/resources"
$SYNC_TESTS && mkdir -p "$OUT/src/test/java/$DST_PATH" "$OUT/src/test/resources"

note "Staging Java sources from modules/*"
for m in "${MODULES[@]}"; do
  src_main="$ROOT/modules/$m/src/main/java/$SRC_PATH"
  [ -d "$src_main" ] && cp -R "$src_main/." "$OUT/src/main/java/$DST_PATH/"
  if $SYNC_TESTS; then
    src_test="$ROOT/modules/$m/src/test/java/$SRC_PATH"
    [ -d "$src_test" ] && cp -R "$src_test/." "$OUT/src/test/java/$DST_PATH/"
  fi
done

if $SYNC_RESOURCES; then
  note "Staging resources from modules/*"
  for m in "${MODULES[@]}"; do
    src_main_res="$ROOT/modules/$m/src/main/resources"
    [ -d "$src_main_res" ] && cp -R "$src_main_res/." "$OUT/src/main/resources/"
    if $SYNC_TESTS; then
      src_test_res="$ROOT/modules/$m/src/test/resources"
      [ -d "$src_test_res" ] && cp -R "$src_test_res/." "$OUT/src/test/resources/"
    fi
  done
fi

note "Renaming package: $SRC_PKG -> $DST_PKG"
find "$OUT/src" -type f \( \
    -name '*.java' -o -name '*.yml' -o -name '*.yaml' -o -name '*.properties' \
    -o -name '*.imports' -o -name '*.factories' -o -name '*.xml' \
    -o -name '*.json' -o -name '*.sql' -o -name '*.txt' \
  \) -print0 | xargs -0 perl -pi -e "s|\\Q${SRC_PKG}\\E|${DST_PKG}|g"

validate_staged_tree

green "Staged: $OUT"

if ! $APPLY; then
  note "Stopping after stage (--no-apply). campusclaw/ untouched."
  exit 0
fi

# ============================================================
# Phase 2: APPLY — rsync staged tree into campusclaw/
# ============================================================

if [ ! -d "$MIRROR" ]; then
  echo "missing target: $MIRROR (the in-tree campusclaw module)" >&2
  exit 1
fi

RSYNC_FLAGS=(-a --delete --itemize-changes --exclude-from="$EXCLUDE_FILE")
$DRY_RUN && RSYNC_FLAGS+=(-n)

note "Applying Java main sources -> $MIRROR/src/main/java/"
rsync "${RSYNC_FLAGS[@]}" "$OUT/src/main/java/" "$MIRROR/src/main/java/"

if $SYNC_TESTS; then
  note "Applying Java test sources -> $MIRROR/src/test/java/"
  rsync "${RSYNC_FLAGS[@]}" "$OUT/src/test/java/" "$MIRROR/src/test/java/"
fi

if $SYNC_RESOURCES; then
  note "Applying whitelisted resources (db/gaussdb, mapper, schema.sql, i18n, AutoConfiguration.imports)"
  for f in "${SYNCED_RESOURCES[@]}"; do
    src="$OUT/src/main/resources/$f"
    dst="$MIRROR/src/main/resources/$f"
    if [ -d "$src" ]; then
      if $DRY_RUN; then
        rsync -an --delete --itemize-changes "$src/" "$dst/"
      else
        mkdir -p "$dst"
        rsync -a --delete "$src/" "$dst/"
      fi
    elif [ -f "$src" ]; then
      if $DRY_RUN; then
        if ! cmp -s "$src" "$dst" 2>/dev/null; then
          echo "  [would update] src/main/resources/$f"
        fi
      else
        mkdir -p "$(dirname "$dst")"
        cp "$src" "$dst"
      fi
    fi
  done
  for removed_resource in "${REMOVED_RESOURCES[@]}"; do
    legacy_target="$MIRROR/src/main/resources/$removed_resource"
    if $DRY_RUN; then
      if [ -f "$legacy_target" ]; then
        echo "  [would delete] src/main/resources/$removed_resource"
      fi
    else
      rm -f "$legacy_target"
    fi
  done
fi

if $DRY_RUN; then
  green "Dry run complete. Re-run without --dry-run to apply."
  exit 0
fi

# ============================================================
# Phase 3: VERIFY — compile campusclaw/ in place
# ============================================================

if $VERIFY; then
  note "Verifying via mvn compile in $MIRROR (JAVA_HOME=$JAVA_HOME)"
  ( cd "$MIRROR" \
    && "$ROOT/mvnw" -q -DskipTests \
         -Dcheckstyle.skip=true -Dspotless.check.skip=true \
         compile )
  green "OK: campusclaw/ compiles cleanly."
else
  note "Skipped verification (--no-verify)"
fi

cat <<EOF

Done. campusclaw/ is now in sync with modules/*.

Review:
  git diff -- campusclaw/src
  git status campusclaw

If new files appear under campusclaw/ that you wrote directly (not from
modules/), add their paths to scripts/sync-campusclaw-exclude.txt so future syncs
preserve them.
EOF
