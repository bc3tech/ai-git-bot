#!/usr/bin/env bash
################################################################################
# Generate devcontainer cache mount configuration from host environment variables.
#
# IMPORTANT: Run this on the HOST (not inside container). Requires 8 host env vars.
#
# Usage:
#   ./generate-cache-mount-config.sh [OUTPUT_FILE]
#   ./generate-cache-mount-config.sh --allow-missing [OUTPUT_FILE]
#
# Defaults:
#   OUTPUT_FILE = .devcontainer/cache-mounts.generated.json
#
# Required host environment variables (all must be set unless --allow-missing used):
#   CARGO_HOME                      - Rust cargo cache directory
#   COPILOT_HOME                    - Copilot CLI home directory
#   COPILOT_CACHE_HOME              - Copilot cache (optional; if under COPILOT_HOME, mapped as subpath)
#   npm_config_cache                - npm package cache
#   NUGET_PACKAGES                  - .NET NuGet package cache
#   PIP_CACHE_DIR                   - Python pip package cache
#   VCPKG_DEFAULT_BINARY_CACHE      - vcpkg binary cache
#   XDG_DATA_HOME                   - XDG data directory
#
# Output JSON shape:
# {
#   "mounts": ["source=...,target=...,type=bind,consistency=cached", ...],
#   "containerEnv": {"CARGO_HOME":"/opt/host-caches/CARGO_HOME", ...}
# }
#
# Notes:
# - If any required var is unset, fails with error. Use --allow-missing to skip.
# - Output is merged into final devcontainer.json by merge-devcontainer-config.sh.
################################################################################

set -euo pipefail

ALLOW_MISSING=false
OUTPUT_FILE=".devcontainer/cache-mounts.generated.json"

if [ "${1:-}" = "--allow-missing" ]; then
    ALLOW_MISSING=true
    shift
fi

if [ -n "${1:-}" ]; then
    OUTPUT_FILE="$1"
fi

REQUIRED_VARS=(
    CARGO_HOME
    COPILOT_HOME
    COPILOT_CACHE_HOME
    npm_config_cache
    NUGET_PACKAGES
    PIP_CACHE_DIR
    VCPKG_DEFAULT_BINARY_CACHE
    XDG_DATA_HOME
)

json_escape() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\n'/\\n}"
    s="${s//$'\r'/\\r}"
    s="${s//$'\t'/\\t}"
    printf '%s' "$s"
}

starts_with_path() {
    local child="$1"
    local parent="$2"
    case "$child" in
        "$parent"|"$parent"/*) return 0 ;;
        *) return 1 ;;
    esac
}

missing=()
for var_name in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!var_name:-}" ]; then
        missing+=("$var_name")
    fi
done

if [ ${#missing[@]} -gt 0 ] && [ "$ALLOW_MISSING" != "true" ]; then
    echo "Error: required host environment variables are missing:" >&2
    printf '  - %s\n' "${missing[@]}" >&2
    echo "Set them first, or run with --allow-missing to generate a partial config." >&2
    exit 1
fi

mount_entries=()
env_entries=()

add_mapping() {
    local var_name="$1"
    local host_path="$2"
    local target_path="/opt/host-caches/${var_name}"

    mount_entries+=("source=${host_path},target=${target_path},type=bind,consistency=cached")
    env_entries+=("${var_name}=${target_path}")
}

for var_name in CARGO_HOME COPILOT_HOME npm_config_cache NUGET_PACKAGES PIP_CACHE_DIR VCPKG_DEFAULT_BINARY_CACHE XDG_DATA_HOME; do
    host_path="${!var_name:-}"
    if [ -z "$host_path" ]; then
        continue
    fi

    add_mapping "$var_name" "$host_path"
done

copilot_cache_home="${COPILOT_CACHE_HOME:-}"
copilot_home="${COPILOT_HOME:-}"

if [ -n "$copilot_cache_home" ]; then
    normalized_copilot_cache_home="$(printf '%s' "$copilot_cache_home" | sed 's#\\#/#g')"
    normalized_copilot_home="$(printf '%s' "$copilot_home" | sed 's#\\#/#g')"
    if [ -n "$copilot_home" ] && starts_with_path "$normalized_copilot_cache_home" "$normalized_copilot_home"; then
        suffix="${normalized_copilot_cache_home#"$normalized_copilot_home"}"
        env_entries+=("COPILOT_CACHE_HOME=/opt/host-caches/COPILOT_HOME${suffix}")
    else
        add_mapping "COPILOT_CACHE_HOME" "$copilot_cache_home"
    fi
fi

mkdir -p "$(dirname "$OUTPUT_FILE")"

{
    echo "{"
    echo "  \"mounts\": ["
    for i in "${!mount_entries[@]}"; do
        escaped="$(json_escape "${mount_entries[$i]}")"
        if [ "$i" -lt $((${#mount_entries[@]} - 1)) ]; then
            echo "    \"${escaped}\"," 
        else
            echo "    \"${escaped}\""
        fi
    done
    echo "  ],"
    echo "  \"containerEnv\": {"
    for i in "${!env_entries[@]}"; do
        key="${env_entries[$i]%%=*}"
        value="${env_entries[$i]#*=}"
        escaped_key="$(json_escape "$key")"
        escaped_value="$(json_escape "$value")"
        if [ "$i" -lt $((${#env_entries[@]} - 1)) ]; then
            echo "    \"${escaped_key}\": \"${escaped_value}\"," 
        else
            echo "    \"${escaped_key}\": \"${escaped_value}\""
        fi
    done
    echo "  }"
    echo "}"
} > "$OUTPUT_FILE"

echo "Generated cache mount config at ${OUTPUT_FILE}"
echo "Mount entries: ${#mount_entries[@]}"
echo "Container env entries: ${#env_entries[@]}"

if [ ${#missing[@]} -gt 0 ]; then
    echo "Skipped missing host env vars (partial config):"
    printf '  - %s\n' "${missing[@]}"
fi
