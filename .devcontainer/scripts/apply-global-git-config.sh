#!/usr/bin/env bash
################################################################################
# Apply exported git config entries as global git settings.
#
# IMPORTANT: This script is meant to run INSIDE the container as postStartCommand.
# It reads the mounted gitconfig.effective file and applies it globally.
#
# Usage:
#   ./apply-global-git-config.sh [INPUT_FILE]
#
# Default:
#   INPUT_FILE = .devcontainer/gitconfig.effective
#
# Input format:
#   key<TAB>base64(value)
#
# Behavior:
# - Idempotent: clears each key once, then re-adds all values in order.
# - Supports multi-valued git keys.
# - Expects INPUT_FILE to be mounted from host via devcontainer.json mount entry.
#
# Called by: devcontainer.json postStartCommand
################################################################################

set -euo pipefail

INPUT_FILE="${1:-.devcontainer/gitconfig.effective}"

if ! command -v git >/dev/null 2>&1; then
    echo "Error: git is required but not found in PATH." >&2
    exit 1
fi

if ! command -v base64 >/dev/null 2>&1; then
    echo "Error: base64 is required but not found in PATH." >&2
    exit 1
fi

if [ ! -f "${INPUT_FILE}" ]; then
    echo "Error: input file not found: ${INPUT_FILE}" >&2
    exit 1
fi

# Track which keys have already been reset.
declare -A RESET_KEYS=()

applied_count=0
while IFS=$'\t' read -r key value_b64; do
    if [ -z "${key}" ] || [ -z "${value_b64}" ]; then
        continue
    fi

    if [ -z "${RESET_KEYS[${key}]:-}" ]; then
        git config --global --unset-all "${key}" >/dev/null 2>&1 || true
        RESET_KEYS["${key}"]=1
    fi

    value="$(printf '%s' "${value_b64}" | base64 --decode)"
    git config --global --add "${key}" "${value}"
    applied_count=$((applied_count + 1))
done < "${INPUT_FILE}"

echo "Applied ${applied_count} git config entries globally from ${INPUT_FILE}."
