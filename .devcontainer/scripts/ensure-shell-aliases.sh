#!/usr/bin/env bash
################################################################################
# Ensure preferred shell aliases exist for both bash and PowerShell.
#
# Usage:
#   ./ensure-shell-aliases.sh
#
# Notes:
# - Runs inside the dev container as part of bootstrap.
# - Idempotent: rewrites only the managed block in each profile file.
################################################################################

set -euo pipefail

resolve_user_home() {
    if [ -n "${HOME:-}" ] && [ -d "${HOME}" ]; then
        printf '%s' "${HOME}"
        return 0
    fi

    local user_name="${DEVCONTAINER_REMOTE_USER:-vscode}"
    local user_home
    user_home="$(getent passwd "${user_name}" | cut -d: -f6 || true)"
    if [ -n "${user_home}" ] && [ -d "${user_home}" ]; then
        printf '%s' "${user_home}"
        return 0
    fi

    return 1
}

upsert_managed_block() {
    local file_path="$1"
    local begin_marker="$2"
    local end_marker="$3"
    local block_content="$4"

    mkdir -p "$(dirname "${file_path}")"
    touch "${file_path}"

    awk -v begin="${begin_marker}" -v end="${end_marker}" '
        $0 == begin { in_block=1; next }
        $0 == end { in_block=0; next }
        !in_block { print }
    ' "${file_path}" > "${file_path}.tmp"

    {
        cat "${file_path}.tmp"
        printf '\n%s\n' "${begin_marker}"
        printf '%s\n' "${block_content}"
        printf '%s\n' "${end_marker}"
    } > "${file_path}"

    rm -f "${file_path}.tmp"
}

USER_HOME="$(resolve_user_home)"
BASHRC_FILE="${USER_HOME}/.bashrc"
POWERSHELL_PROFILE_FILE="${USER_HOME}/.config/powershell/Microsoft.PowerShell_profile.ps1"

GHCP_TARGET='copilot'
if ! command -v copilot >/dev/null 2>&1; then
    GHCP_TARGET='gh copilot'
fi

BASH_BLOCK="alias g='git'
alias ga='git add'
alias gc='git commit'
alias gp='git push'
alias gst='git status -sb'
alias gl='git log --oneline --decorate --graph -20'
alias tf='terraform'
alias ghcp='${GHCP_TARGET}'"

POWERSHELL_BLOCK="function g { git @Args }
function ga { git add @Args }
function gc { git commit @Args }
function gp { git push @Args }
function gst { git status -sb @Args }
function gl { git log --oneline --decorate --graph -20 @Args }
function tf { terraform @Args }
function ghcp {
    if (Get-Command copilot -ErrorAction SilentlyContinue) {
        copilot @Args
    }
    else {
        gh copilot @Args
    }
}"

upsert_managed_block \
    "${BASHRC_FILE}" \
    "# BEGIN DIGITAL_TWIN_MANAGED_ALIASES" \
    "# END DIGITAL_TWIN_MANAGED_ALIASES" \
    "${BASH_BLOCK}"

upsert_managed_block \
    "${POWERSHELL_PROFILE_FILE}" \
    "# BEGIN DIGITAL_TWIN_MANAGED_ALIASES" \
    "# END DIGITAL_TWIN_MANAGED_ALIASES" \
    "${POWERSHELL_BLOCK}"

echo "Configured managed aliases in ${BASHRC_FILE} and ${POWERSHELL_PROFILE_FILE}."
