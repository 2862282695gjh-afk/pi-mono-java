#!/usr/bin/env bash
set -euo pipefail

# ──────────────────────────────────────────────────────────────
# CampusClaw Installer (macOS / Linux)
#
# Creates a global `campusclaw` command that points back to this
# source tree. Every run auto-detects source changes and rebuilds.
#
# Layout after install:
#   ~/file/.campusclaw/bin/campusclaw   (shell wrapper → this repo; base overridable via $CAMPUSCLAW_HOME)
# ──────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN_DIR="${CAMPUSCLAW_HOME:-$HOME/file/.campusclaw}/bin"

require_supported_os() {
    case "$(uname -s 2>/dev/null || true)" in
        Darwin|Linux) ;;
        *)
            echo "Error: CampusClaw supports macOS and Linux only." >&2
            exit 1
            ;;
    esac
}

require_supported_os

# ── Verify JDK 21 exists ──────────────────────────────────────
detect_jdk21() {
    if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then
        return 0
    fi
    if [ -d "/opt/homebrew/Cellar/openjdk@21" ]; then
        JAVA_HOME="$(find /opt/homebrew/Cellar/openjdk@21 -maxdepth 1 -mindepth 1 -type d | head -1)/libexec/openjdk.jdk/Contents/Home"
        return 0
    fi
    if command -v /usr/libexec/java_home &>/dev/null; then
        local jh
        jh="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [ -n "$jh" ]; then
            JAVA_HOME="$jh"
            return 0
        fi
    fi
    if [ -d "${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java" ]; then
        local sdk_dir="${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java"
        local jdk21
        jdk21="$(find "$sdk_dir" -maxdepth 1 -name '21*' -type d | head -1)"
        if [ -n "$jdk21" ]; then
            JAVA_HOME="$jdk21"
            return 0
        fi
    fi
    for dir in /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/temurin-21* /usr/lib/jvm/java-21*; do
        if [ -d "$dir" ]; then
            JAVA_HOME="$dir"
            return 0
        fi
    done
    if command -v java &>/dev/null && java -version 2>&1 | grep -q '"21\.'; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
        return 0
    fi
    return 1
}

if ! detect_jdk21; then
    echo "Error: JDK 21 not found." >&2
    echo "Install options:" >&2
    echo "  macOS:  brew install openjdk@21" >&2
    echo "  Linux:  sudo apt install openjdk-21-jdk" >&2
    echo "  Any:    sdk install java 21-tem  (via SDKMAN)" >&2
    exit 1
fi

echo "Using JDK: $JAVA_HOME"
echo "Source dir: $SCRIPT_DIR"

# ── Create wrapper script ─────────────────────────────────────
mkdir -p "$BIN_DIR"

# Write wrapper — embed SCRIPT_DIR as a hardcoded path
cat > "$BIN_DIR/campusclaw" << WRAPPER
#!/usr/bin/env bash
set -euo pipefail

# ── Source repo (set by install.sh) ───────────────────────────
REPO_DIR="$SCRIPT_DIR"

if [ ! -x "\$REPO_DIR/campusclaw.sh" ]; then
    echo "Error: campusclaw.sh not found or not executable: \$REPO_DIR/campusclaw.sh" >&2
    exit 1
fi

exec "\$REPO_DIR/campusclaw.sh" "\$@"
WRAPPER

chmod +x "$BIN_DIR/campusclaw"
echo "Created command at $BIN_DIR/campusclaw"

# ── Add to PATH ────────────────────────────────────────────────
add_to_path() {
    local line="export PATH=\"\${CAMPUSCLAW_HOME:-\$HOME/file/.campusclaw}/bin:\$PATH\""

    if echo "$PATH" | tr ':' '\n' | grep -qx "$BIN_DIR"; then
        echo "PATH already configured."
        return 0
    fi

    local added=false
    for rc in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile"; do
        if [ -f "$rc" ]; then
            if ! grep -qF '# CampusClaw' "$rc"; then
                printf '\n# CampusClaw\n%s\n' "$line" >> "$rc"
                echo "Added to PATH in $rc"
                added=true
            fi
        fi
    done

    if [ "$added" = false ]; then
        printf '\n# CampusClaw\n%s\n' "$line" >> "$HOME/.profile"
        echo "Added to PATH in $HOME/.profile"
    fi
}

add_to_path

echo ""
echo "Installation complete!"
echo "Restart your terminal or run:"
echo "  export PATH=\"\${CAMPUSCLAW_HOME:-\$HOME/file/.campusclaw}/bin:\$PATH\""
echo ""
echo "Then try:"
echo "  campusclaw --help"
echo "  campusclaw skill list"
