#!/usr/bin/env bash
################################################################################
# Validate devcontainer.json configuration for completeness and correctness.
#
# IMPORTANT: Run this on the HOST after merging template with cache config.
# Verifies JSON validity, mount paths, scripts, and required settings.
#
# Usage:
#   ./validate-devcontainer-config.sh [DEVCONTAINER_FILE]
#
# Default:
#   DEVCONTAINER_FILE = .devcontainer/devcontainer.json
#
# Exit codes:
#   0  = all checks passed
#   1  = validation failed (see output for details)
################################################################################

set -euo pipefail

DEVCONTAINER_FILE="${1:-.devcontainer/devcontainer.json}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAIL_COUNT=0

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

check_pass() {
    echo -e "${GREEN}✓${NC} $1"
}

check_fail() {
    echo -e "${RED}✗${NC} $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
}

image_exists() {
    local image_ref="$1"

    if command -v docker >/dev/null 2>&1; then
        if docker manifest inspect "$image_ref" >/dev/null 2>&1; then
            return 0
        fi
    fi

    if command -v podman >/dev/null 2>&1; then
        if podman manifest inspect "docker://$image_ref" >/dev/null 2>&1; then
            return 0
        fi
    fi

    if [[ "$image_ref" != mcr.microsoft.com/*:* ]]; then
        return 2
    fi

    local repository="${image_ref#mcr.microsoft.com/}"
    local tag="${repository##*:}"
    repository="${repository%:*}"

    curl -sf --max-time 10 "https://mcr.microsoft.com/v2/${repository}/tags/list" \
        | jq -e --arg tag "$tag" '.tags | index($tag) != null' >/dev/null
}

echo "Validating devcontainer configuration: ${DEVCONTAINER_FILE}"
echo

# 1. File existence
if [ ! -f "${DEVCONTAINER_FILE}" ]; then
    check_fail "devcontainer.json not found at ${DEVCONTAINER_FILE}"
    exit 1
fi
check_pass "devcontainer.json exists"

# 2. JSON validity
if ! jq empty "${DEVCONTAINER_FILE}" 2>/dev/null; then
    check_fail "devcontainer.json is not valid JSON"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    # Don't exit; continue with other checks
fi
check_pass "devcontainer.json is valid JSON"

# 3. Image field
image=$(jq -r '.image // empty' "${DEVCONTAINER_FILE}")
if [ -z "$image" ]; then
    check_fail "missing required field: image"
else
    check_pass "image field present: $image"

    if image_exists "$image"; then
        check_pass "image exists in registry: $image"
    else
        image_check_exit_code=$?
        if [ "$image_check_exit_code" -eq 2 ]; then
            check_fail "cannot verify image existence: install docker or podman on host"
        else
            check_fail "image not found or inaccessible in registry: $image"
        fi
    fi
fi

declare -A GHCR_TOKEN_CACHE

feature_oci_path() {
    # "ghcr.io/foo/bar:1" -> "foo/bar"  (strip host and version tag)
    local ref="${1#ghcr.io/}"
    echo "${ref%%:*}"
}

feature_version_tag() {
    # "ghcr.io/foo/bar:1" -> "1"
    local ref="$1"
    echo "${ref##*:}"
}

# Get or fetch a cached bearer token for the given OCI path
get_ghcr_token() {
    local oci_path="$1"
    
    if [ -n "${GHCR_TOKEN_CACHE[$oci_path]:-}" ]; then
        echo "${GHCR_TOKEN_CACHE[$oci_path]}"
        return 0
    fi
    
    local token
    token=$(curl -sf --max-time 10 \
        "https://ghcr.io/token?scope=repository:${oci_path}:pull" \
        | jq -r '.token // empty' 2>/dev/null) || true

    if [ -z "$token" ]; then
        return 1
    fi
    
    GHCR_TOKEN_CACHE[$oci_path]="$token"
    echo "$token"
    return 0
}

# Verify a ghcr.io OCI feature actually exists by querying the registry manifest API.
# Returns:
#   0  = exists
#   1  = not found (likely hallucinated)
#   2  = registry unreachable / non-ghcr host (skip, warn only)
verify_feature_in_registry() {
    local feature_ref="$1"

    # Only attempt verification for ghcr.io references
    if [[ "$feature_ref" != ghcr.io/* ]]; then
        return 2
    fi

    local oci_path tag
    oci_path=$(feature_oci_path "$feature_ref")
    tag=$(feature_version_tag "$feature_ref")

    local token
    token=$(get_ghcr_token "$oci_path")
    if [ $? -ne 0 ] || [ -z "$token" ]; then
        return 2  # Can't get token — treat as unreachable
    fi

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 \
        -H "Authorization: Bearer $token" \
        -H "Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json" \
        "https://ghcr.io/v2/${oci_path}/manifests/${tag}" 2>/dev/null) || true

    case "$http_code" in
        200|302) return 0 ;;
        404)     return 1 ;;
        *)       return 2 ;;
    esac
}

# 4. Features section — validate presence and verify each entry exists in registry
if ! jq -e '.features' "${DEVCONTAINER_FILE}" >/dev/null 2>&1; then
    check_warn "features section missing or invalid"
elif ! jq -e '(.features|type) == "object"' "${DEVCONTAINER_FILE}" >/dev/null 2>&1; then
    check_fail "features must be an object, not $(jq -r '.features|type' "${DEVCONTAINER_FILE}")"
else
    features_count=$(jq '.features | length' "${DEVCONTAINER_FILE}")
    check_pass "features section present ($features_count features)"

    while IFS= read -r feature_key; do
        verify_feature_in_registry "$feature_key"
        result=$?
        case $result in
            0) check_pass "feature verified in registry: $feature_key" ;;
            1) check_fail "FEATURE NOT FOUND IN REGISTRY: $feature_key
  → This reference may be hallucinated. Verify it exists before using it." ;;
            2) check_warn "feature registry check skipped (non-ghcr or unreachable): $feature_key" ;;
        esac
    done < <(jq -r '.features | keys[]' "${DEVCONTAINER_FILE}")
fi

# 5. GitHub CLI feature (any version tag)
if jq -r '.features | keys[]' "${DEVCONTAINER_FILE}" 2>/dev/null \
        | grep -q "^ghcr.io/devcontainers/features/github-cli:"; then
    check_pass "GitHub CLI feature present"
else
    check_fail "GitHub CLI feature missing (required)"
fi

# 6. PowerShell Core feature (any version tag)
if jq -r '.features | keys[]' "${DEVCONTAINER_FILE}" 2>/dev/null \
        | grep -q "^ghcr.io/devcontainers/features/powershell:"; then
    check_pass "PowerShell Core feature present"
else
    check_fail "PowerShell Core feature missing (required)"
fi

# 7. Extensions present
extensions=$(jq '.customizations.vscode.extensions // []' "${DEVCONTAINER_FILE}")
if [ "$(echo "$extensions" | jq 'length')" -eq 0 ]; then
    check_warn "no VS Code extensions configured (may be intentional)"
else
    ext_count=$(echo "$extensions" | jq 'length')
    check_pass "VS Code extensions present ($ext_count extensions)"
fi

# 8. Mounts validation
if ! jq '.mounts' "${DEVCONTAINER_FILE}" >/dev/null 2>&1; then
    check_fail "mounts field missing or invalid"
else
    mounts_count=$(jq '.mounts | length' "${DEVCONTAINER_FILE}")
    check_pass "mounts section present ($mounts_count mounts)"
    
    # Check for gitconfig.effective mount
    if jq '.mounts[] | select(contains("gitconfig.effective"))' "${DEVCONTAINER_FILE}" | grep -q gitconfig; then
        check_pass "gitconfig.effective mount present"
    else
        check_fail "gitconfig.effective mount missing (required for git config replication)"
    fi

    # Check for ~/.copilot mount into /home/vscode/.copilot
    if jq '.mounts[] | select(contains("target=/home/vscode/.copilot"))' "${DEVCONTAINER_FILE}" | grep -q copilot; then
        check_pass "~/.copilot mount present"
    else
        check_fail "~/.copilot mount missing (required)"
    fi
    
    # Check for cache mounts (at least one beyond gitconfig)
    cache_mount_count=$(jq '.mounts | map(select(contains("opt/host-caches"))) | length' "${DEVCONTAINER_FILE}")
    if [ "$cache_mount_count" -gt 0 ]; then
        check_pass "cache mounts present ($cache_mount_count cache mount(s))"
    else
        check_warn "no cache mounts detected (cache sharing disabled)"
    fi
fi

# 9. containerEnv validation
if ! jq '.containerEnv' "${DEVCONTAINER_FILE}" >/dev/null 2>&1; then
    check_fail "containerEnv field missing or invalid"
else
    env_count=$(jq '.containerEnv | length' "${DEVCONTAINER_FILE}")
    check_pass "containerEnv section present ($env_count env vars)"
    
    if jq '.containerEnv | has("POWERSHELL_DISTRIBUTION_CHANNEL")' "${DEVCONTAINER_FILE}" | grep -q true; then
        check_pass "POWERSHELL_DISTRIBUTION_CHANNEL set"
    else
        check_fail "POWERSHELL_DISTRIBUTION_CHANNEL missing"
    fi
fi

# 10. Terminal default profile
if jq '.customizations.vscode.settings | has("terminal.integrated.defaultProfile.linux")' "${DEVCONTAINER_FILE}" | grep -q true; then
    profile=$(jq -r '.customizations.vscode.settings."terminal.integrated.defaultProfile.linux"' "${DEVCONTAINER_FILE}")
    if [ "$profile" = "pwsh" ]; then
        check_pass "terminal default profile set to pwsh"
    else
        check_fail "terminal default profile is $profile (should be pwsh)"
    fi
else
    check_warn "terminal.integrated.defaultProfile.linux not explicitly set (defaults may apply)"
fi

# 11. postCreateCommand
if jq '.postCreateCommand' "${DEVCONTAINER_FILE}" >/dev/null 2>&1; then
    post_create=$(jq -r '.postCreateCommand // empty' "${DEVCONTAINER_FILE}")
    if [ -n "$post_create" ]; then
        check_pass "postCreateCommand present: $post_create"

        if echo "$post_create" | grep -q "ensure-gh-copilot-cli.sh" || echo "$post_create" | grep -q "bootstrap-ghcp-digital-twin.sh"; then
            check_pass "postCreateCommand includes Copilot bootstrap path"
        else
            check_fail "postCreateCommand does not include Copilot CLI bootstrap script"
        fi

        if echo "$post_create" | grep -q "ensure-shell-aliases.sh" || echo "$post_create" | grep -q "bootstrap-ghcp-digital-twin.sh"; then
            check_pass "postCreateCommand includes shell alias bootstrap path"
        else
            check_fail "postCreateCommand does not include shell alias bootstrap script"
        fi
        
        # Verify script exists if it's a bash call
        if echo "$post_create" | grep -q "bootstrap-ghcp-digital-twin.sh"; then
            if [ -f "${SCRIPT_DIR}/bootstrap-ghcp-digital-twin.sh" ]; then
                check_pass "bootstrap-ghcp-digital-twin.sh exists in scripts directory"
            else
                check_fail "bootstrap-ghcp-digital-twin.sh referenced but not found"
            fi
        fi
    fi
else
    check_warn "postCreateCommand not defined"
fi

# 12. postStartCommand
if jq '.postStartCommand' "${DEVCONTAINER_FILE}" >/dev/null 2>&1; then
    post_start=$(jq -r '.postStartCommand // empty' "${DEVCONTAINER_FILE}")
    if [ -n "$post_start" ]; then
        check_pass "postStartCommand present: $post_start"
        
        if echo "$post_start" | grep -q "apply-global-git-config.sh"; then
            if [ -f "${SCRIPT_DIR}/apply-global-git-config.sh" ]; then
                check_pass "apply-global-git-config.sh exists in scripts directory"
            else
                check_fail "apply-global-git-config.sh referenced but not found"
            fi
        fi
    fi
else
    check_warn "postStartCommand not defined (git config may not replicate)"
fi

# 13. All required scripts present
required_scripts=(
    "export-effective-git-config.sh"
    "apply-global-git-config.sh"
    "ensure-gh-copilot-cli.sh"
    "ensure-digital-twin-plugin.sh"
    "ensure-shell-aliases.sh"
    "bootstrap-ghcp-digital-twin.sh"
    "generate-cache-mount-config.sh"
    "merge-devcontainer-config.sh"
)

all_scripts_present=true
for script in "${required_scripts[@]}"; do
    if [ -f "${SCRIPT_DIR}/${script}" ]; then
        check_pass "script present: $script"
    else
        check_fail "script missing: $script"
        all_scripts_present=false
    fi
done

# 14. Scripts are executable
if [ "$all_scripts_present" = true ]; then
    for script in "${required_scripts[@]}"; do
        if [ -x "${SCRIPT_DIR}/${script}" ]; then
            check_pass "script is executable: $script"
        else
            check_fail "script is not executable: $script"
        fi
    done
fi

# 15. gitconfig.effective exists
git_config_file="${SCRIPT_DIR%/*}/gitconfig.effective"
if [ -f "$git_config_file" ]; then
    check_pass "gitconfig.effective exists"
else
    check_fail "gitconfig.effective not found (run export-effective-git-config.sh on host)"
fi

# Summary
echo
echo "================================"
if [ "$FAIL_COUNT" -eq 0 ]; then
    echo -e "${GREEN}✓ All validation checks passed!${NC}"
    exit 0
else
    echo -e "${RED}✗ Validation failed with $FAIL_COUNT error(s)${NC}"
    exit 1
fi
