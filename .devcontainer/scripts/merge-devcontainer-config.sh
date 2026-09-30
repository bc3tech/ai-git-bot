#!/usr/bin/env bash
################################################################################
# Merge devcontainer template with generated cache mount configuration.
#
# IMPORTANT: Run this on the HOST (not inside container) after running generate-cache-mount-config.sh.
#
# Usage:
#   ./merge-devcontainer-config.sh [TEMPLATE_FILE] [CACHE_CONFIG_FILE] [OUTPUT_FILE]
#
# Defaults:
#   TEMPLATE_FILE    = .devcontainer/devcontainer.template.jsonc
#   CACHE_CONFIG_FILE = .devcontainer/cache-mounts.generated.json
#   OUTPUT_FILE      = .devcontainer/devcontainer.json
#
# Notes:
# - Requires jq to be installed and available in PATH
# - Strips JSONC comments from template before merging
# - Merges mounts array and containerEnv object from cache config into template
# - Outputs valid JSON (not JSONC) to OUTPUT_FILE
# - This is the final step before editing devcontainer.json to add project-specific extensions
################################################################################

set -euo pipefail

TEMPLATE_FILE="${1:-.devcontainer/devcontainer.template.jsonc}"
CACHE_CONFIG_FILE="${2:-.devcontainer/cache-mounts.generated.json}"
OUTPUT_FILE="${3:-.devcontainer/devcontainer.json}"

if ! command -v jq >/dev/null 2>&1; then
    echo "Error: jq is required but not found in PATH." >&2
    exit 1
fi

if [ ! -f "$TEMPLATE_FILE" ]; then
    echo "Error: template file not found: $TEMPLATE_FILE" >&2
    exit 1
fi

if [ ! -f "$CACHE_CONFIG_FILE" ]; then
    echo "Error: cache config file not found: $CACHE_CONFIG_FILE" >&2
    exit 1
fi

strip_jsonc_comments() {
    local input="$1"
    printf '%s' "$input" | sed '
        s|//.*$||g
        s|/\*.*\*/||g
    '
}

template_content=$(cat "$TEMPLATE_FILE")
template_json=$(strip_jsonc_comments "$template_content")

cache_config=$(cat "$CACHE_CONFIG_FILE")

merged=$(
    jq --argjson cache_config "$cache_config" \
        '
        .mounts = (.mounts | map(select(startswith("source=")))) + $cache_config.mounts |
        .containerEnv |= . + $cache_config.containerEnv
        ' <<< "$template_json"
)

mkdir -p "$(dirname "$OUTPUT_FILE")"
jq '.' <<< "$merged" > "$OUTPUT_FILE"

echo "Merged devcontainer configuration written to ${OUTPUT_FILE}"
echo "Template: ${TEMPLATE_FILE}"
echo "Cache config: ${CACHE_CONFIG_FILE}"
