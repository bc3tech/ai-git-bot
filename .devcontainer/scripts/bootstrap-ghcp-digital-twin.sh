#!/usr/bin/env bash
################################################################################
# Bootstrap GitHub Copilot CLI requirements for Digital Twin dev containers.
#
# IMPORTANT: This script is meant to run INSIDE the container as postCreateCommand.
# It installs Copilot CLI extension and Digital Twin plugin automatically.
#
# Usage:
#   ./bootstrap-ghcp-digital-twin.sh
#
# This script:
# 1) Ensures GitHub Copilot CLI extension is present in GitHub CLI (gh-copilot)
# 2) Ensures Digital Twin plugin is installed in Copilot CLI profile
# 3) Ensures preferred shell aliases are configured (g, tf, ghcp)
#
# Called by: devcontainer.json postCreateCommand
################################################################################

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"${SCRIPT_DIR}/ensure-gh-copilot-cli.sh"
"${SCRIPT_DIR}/ensure-digital-twin-plugin.sh"
"${SCRIPT_DIR}/ensure-shell-aliases.sh"

echo "GitHub Copilot CLI, Digital Twin plugin, and shell alias bootstrap complete."
