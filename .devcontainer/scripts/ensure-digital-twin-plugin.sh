#!/usr/bin/env bash
################################################################################
# Ensure the Digital Twin Copilot plugin is installed in the container profile.
#
# Usage:
#   ./ensure-digital-twin-plugin.sh
#
# Environment overrides:
#   DIGITAL_TWIN_PLUGIN_NAME=brandonh-digital-twin
#   DIGITAL_TWIN_PLUGIN_SOURCE=https://git.bc3.tech/bc3tech/digital-twin.git
#
# Notes:
# - Installs into the Copilot CLI user profile inside the container.
# - Does not modify repository files.
################################################################################

set -euo pipefail

DIGITAL_TWIN_PLUGIN_NAME="${DIGITAL_TWIN_PLUGIN_NAME:-brandonh-digital-twin}"
DIGITAL_TWIN_PLUGIN_SOURCE="${DIGITAL_TWIN_PLUGIN_SOURCE:-https://git.bc3.tech/bc3tech/digital-twin.git}"

COPILOT_CMD=()

if command -v copilot >/dev/null 2>&1; then
    COPILOT_CMD=(copilot)
elif command -v ghcp >/dev/null 2>&1; then
    COPILOT_CMD=(ghcp)
else
    echo "Error: neither 'copilot' nor 'ghcp' was found in PATH." >&2
    echo "Install GitHub Copilot CLI first before installing the Digital Twin plugin." >&2
    exit 1
fi

run_copilot() {
    "${COPILOT_CMD[@]}" "$@"
}

is_installed() {
    run_copilot plugin list 2>/dev/null | grep -Eiq "(^|[[:space:]])${DIGITAL_TWIN_PLUGIN_NAME}([[:space:]]|$)"
}

if is_installed; then
    echo "Digital Twin plugin already installed: ${DIGITAL_TWIN_PLUGIN_NAME}"
    exit 0
fi

echo "Installing Digital Twin plugin from ${DIGITAL_TWIN_PLUGIN_SOURCE} ..."
git ls-remote "${DIGITAL_TWIN_PLUGIN_SOURCE}" HEAD >/dev/null 2>&1 || {
    echo "Error: failed to reach Digital Twin plugin source at ${DIGITAL_TWIN_PLUGIN_SOURCE}" >&2
    exit 1
}
run_copilot plugin install "${DIGITAL_TWIN_PLUGIN_SOURCE}"

if is_installed; then
    echo "Digital Twin plugin is installed: ${DIGITAL_TWIN_PLUGIN_NAME}"
else
    echo "Warning: install command completed, but ${DIGITAL_TWIN_PLUGIN_NAME} was not found in plugin list." >&2
    echo "Run '$(printf '%q ' "${COPILOT_CMD[@]}")plugin list' to verify manually." >&2
fi
