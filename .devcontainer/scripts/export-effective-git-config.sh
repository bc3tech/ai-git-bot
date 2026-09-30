#!/usr/bin/env bash
################################################################################
# Export effective git config for a repository into a portable file.
#
# IMPORTANT: Run this on the HOST (not inside container) before dev container starts.
#
# Usage:
#   ./export-effective-git-config.sh [REPO_PATH] [OUTPUT_FILE]
#
# Defaults:
#   REPO_PATH   = current directory
#   OUTPUT_FILE = .devcontainer/gitconfig.effective
#
# Output format:
#   key<TAB>base64(value)
#
# Notes:
# - Exports the effective config that applies to REPO_PATH.
# - Sensitive keys are intentionally excluded (credential.*, gpg.*, user.signingkey, include.*).
# - Output file is used as a bind mount in devcontainer.json.
# - The mounted file is applied globally in container via apply-global-git-config.sh on startup.
################################################################################

set -euo pipefail

REPO_PATH="${1:-$(pwd)}"
OUTPUT_FILE="${2:-${REPO_PATH}/.devcontainer/gitconfig.effective}"

if ! command -v git >/dev/null 2>&1; then
    echo "Error: git is required but not found in PATH." >&2
    exit 1
fi

if ! command -v base64 >/dev/null 2>&1; then
    echo "Error: base64 is required but not found in PATH." >&2
    exit 1
fi

if [ ! -d "${REPO_PATH}" ]; then
    echo "Error: repository path does not exist: ${REPO_PATH}" >&2
    exit 1
fi

if ! git -C "${REPO_PATH}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Error: path is not a git work tree: ${REPO_PATH}" >&2
    exit 1
fi

mkdir -p "$(dirname "${OUTPUT_FILE}")"

# Start fresh for deterministic output.
: > "${OUTPUT_FILE}"

is_sensitive_key() {
    local key="$1"

    case "${key}" in
        credential.*) return 0 ;;
        http.extraheader) return 0 ;;
        http.*.extraheader) return 0 ;;
        user.signingkey) return 0 ;;
        gpg.*) return 0 ;;
        include.path) return 0 ;;
        includeIf.*) return 0 ;;
    esac

    return 1
}

skipped_count=0
exported_count=0

while IFS= read -r -d '' entry; do
    key="${entry%%=*}"
    value="${entry#*=}"

    # Guard against malformed rows.
    if [ -z "${key}" ] || [ "${key}" = "${entry}" ]; then
        continue
    fi

    if is_sensitive_key "${key}"; then
        skipped_count=$((skipped_count + 1))
        continue
    fi

    value_b64="$(printf '%s' "${value}" | base64 | tr -d '\r\n')"
    printf '%s\t%s\n' "${key}" "${value_b64}" >> "${OUTPUT_FILE}"
    exported_count=$((exported_count + 1))
done < <(git -C "${REPO_PATH}" config --null --list)

echo "Export complete."
echo "  Repo:      ${REPO_PATH}"
echo "  Output:    ${OUTPUT_FILE}"
echo "  Exported:  ${exported_count}"
echo "  Skipped:   ${skipped_count} sensitive entries"
