#!/bin/bash
#
# install_value.sh — load deployment values from the environment and export
# them for the mate-campusclaw launch.
#
# Values live in /etc/profile on the deployment host (maintained by ops), e.g.:
#   export CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL="http://10.1.2.3:8080"
#
# Usage:
#   source scripts/install_value.sh              # load into current shell
#   ./scripts/install_value.sh --print           # print resolved values only
#
# Consumed by application.yml / application.properties:
#   campusmate.base-url=${CAMPUSMATE_BASE_URL}
#
set -euo pipefail

PROFILE_FILE="${PROFILE_FILE:-/etc/profile}"

# Load the ops-maintained environment (idempotent for interactive shells that
# already sourced it; grep guard keeps `set -u` safe on re-source).
if [ -f "$PROFILE_FILE" ]; then
    # shellcheck disable=SC1090
    source "$PROFILE_FILE"
fi

# CampusMate shared service address. The legacy deployment variable remains an
# installation-boundary input, but the application only consumes CAMPUSMATE_BASE_URL.
if [ -n "${CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL:-}" ]; then
    if [ -n "${CAMPUSMATE_BASE_URL:-}" ] \
        && [ "$CAMPUSMATE_BASE_URL" != "$CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL" ]; then
        echo "[install_value] CAMPUSMATE_BASE_URL conflicts with CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL." >&2
        if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
            return 1
        fi
        exit 1
    fi
    export CAMPUSMATE_BASE_URL="${CAMPUSMATE_BASE_URL:-$CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL}"
fi

if [ -z "${CAMPUSMATE_BASE_URL:-}" ]; then
    echo "[install_value] Warning: CAMPUSMATE_BASE_URL is not configured in $PROFILE_FILE." >&2
fi

if [ "${1:-}" = "--print" ]; then
    echo "CAMPUSMATE_BASE_URL=${CAMPUSMATE_BASE_URL:-}"
    exit 0
fi
