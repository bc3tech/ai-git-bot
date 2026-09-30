#!/usr/bin/env bash
################################################################################
# Ensure GitHub Copilot tooling is available in the container.
#
# Usage:
#   ./ensure-gh-copilot-cli.sh
#
# Notes:
# - Requires GitHub CLI (gh) to be installed.
# - Ensures both:
#   1) GitHub CLI extension: github/gh-copilot
#   2) Standalone Copilot CLI command: copilot or ghcp
# - Safe to run repeatedly.
################################################################################

set -euo pipefail

run_apt_install_npm() {
    if ! command -v apt-get >/dev/null 2>&1; then
        return 1
    fi

    if command -v sudo >/dev/null 2>&1; then
        sudo apt-get update >/dev/null
        sudo DEBIAN_FRONTEND=noninteractive apt-get install -y npm >/dev/null
    else
        apt-get update >/dev/null
        DEBIAN_FRONTEND=noninteractive apt-get install -y npm >/dev/null
    fi

    return 0
}

if ! command -v gh >/dev/null 2>&1; then
    echo "Error: gh is required but not found in PATH." >&2
    echo "Install GitHub CLI first (prefer devcontainer feature github-cli)." >&2
    exit 1
fi

if gh extension list | awk '{print $1}' | grep -Fxq "github/gh-copilot"; then
    echo "GitHub Copilot CLI extension already installed. Upgrading to latest..."
    gh extension upgrade github/gh-copilot
else
    echo "Installing GitHub Copilot CLI extension..."
    gh extension install github/gh-copilot
fi

if gh copilot --help >/dev/null 2>&1; then
    echo "GitHub CLI Copilot subcommand is ready (gh copilot)."
else
    echo "Error: github/gh-copilot extension is installed but 'gh copilot' is not executable." >&2
    exit 1
fi

if command -v copilot >/dev/null 2>&1 || command -v ghcp >/dev/null 2>&1; then
    echo "Standalone Copilot CLI command already available."
    exit 0
fi

if command -v npm >/dev/null 2>&1; then
    echo "Standalone Copilot CLI command not found. Attempting npm-based installation..."
else
    echo "npm not found. Attempting to install npm via apt-get..."
    if run_apt_install_npm; then
        echo "Installed npm via apt-get."
    else
        echo "Could not install npm automatically (apt-get unavailable)." >&2
    fi
fi

if command -v npm >/dev/null 2>&1; then
    if npm install --global @github/copilot >/dev/null 2>&1; then
        echo "Installed @github/copilot."
    elif npm install --global @github/copilot-cli >/dev/null 2>&1; then
        echo "Installed @github/copilot-cli."
    else
        echo "npm-based Copilot CLI installation attempts failed." >&2
    fi
fi

if command -v copilot >/dev/null 2>&1 || command -v ghcp >/dev/null 2>&1; then
    echo "GitHub Copilot tooling is ready (gh extension + standalone command)."
    exit 0
fi

echo "Error: standalone Copilot CLI command not found after setup." >&2
echo "Expected one of: 'copilot' or 'ghcp'." >&2
echo "Install the standalone Copilot CLI in the container image or feature set, then rerun bootstrap." >&2
exit 1
