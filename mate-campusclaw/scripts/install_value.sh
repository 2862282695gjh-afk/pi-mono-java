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
#   mate.innerGWSerive: ${MATE_INNERGWSerive:}   <- from CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL
#
set -euo pipefail

PROFILE_FILE="${PROFILE_FILE:-/etc/profile}"

# Load the ops-maintained environment (idempotent for interactive shells that
# already sourced it; grep guard keeps `set -u` safe on re-source).
if [ -f "$PROFILE_FILE" ]; then
    # shellcheck disable=SC1090
    source "$PROFILE_FILE"
fi

# Mate inner gateway address. The value comes from /etc/profile; the
# CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL name follows the mate-service
# deployment convention.
if [ -n "${CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL:-}" ]; then
    export MATE_INNERGWSerive="$CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL"
else
    echo "[install_value] Warning: CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL not set in $PROFILE_FILE;" >&2
    echo "[install_value] mate.innerGWSerive will stay empty and Mate tool calls will fail." >&2
fi

if [ "${1:-}" = "--print" ]; then
    echo "MATE_INNERGWSerive=${MATE_INNERGWSerive:-}"
    exit 0
fi
