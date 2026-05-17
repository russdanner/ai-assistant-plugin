#!/usr/bin/env bash
set -euo pipefail

# Local maintainer install helper (not the same as scripts/test/* — see scripts/README.md).
# Runs `yarn package` from sources/ when Node is OK (same Rollup + form-pipeline verify as CI merge rules); does not run ESLint.

# --- Hardcoded for local install: edit CRAFTER_DATA if your authoring data lives elsewhere ---
CRAFTER_DATA="/home/russdanner/crafter-installs/4-4-xE/crafter-authoring/data"
# Example site sandbox (default siteId new-demo):
#   /home/russdanner/crafter-installs/4-4-xE/crafter-authoring/data/repos/sites/new-demo/sandbox
# Example when SITE_ID=qtest (first arg):
#   /home/russdanner/crafter-installs/4-4-xE/crafter-authoring/data/repos/sites/qtest/sandbox
# -------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGIN_PATH="${3:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
# Default maintainer test site — pass a different first arg to target another site (e.g. qtest).
SITE_ID="${1:-new-demo}"
STUDIO_URL="${2:-http://localhost:8080}"
SITE_REPO_PATH="${CRAFTER_DATA}/repos/sites/${SITE_ID}/sandbox"

# shellcheck source=lib/studio-auth.sh
source "${SCRIPT_DIR}/lib/studio-auth.sh"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  echo "Usage: $0 [siteId=new-demo] [studioUrl=http://localhost:8080] [pluginRepoPath]" >&2
  echo "  siteId defaults to new-demo when omitted. Edit CRAFTER_DATA at top for your install path." >&2
  echo "  Token: CRAFTER_STUDIO_TOKEN env or scripts/.studio-token (gitignored)." >&2
  exit 0
fi

if ! studio_require_token; then
  exit 2
fi

# Rollup/Yarn 4 need Node 18+. Use Docker when the host Node is too old (override image with PLUGIN_NODE_IMAGE).
package_plugin_sources() {
  local src="${PLUGIN_PATH}/sources"
  if [[ "${SKIP_YARN_PACKAGE:-}" == "1" ]]; then
    echo "${AI_SKIP} Skipping yarn package (SKIP_YARN_PACKAGE=1)."
    return 0
  fi
  local major=0
  major="$(node -p "parseInt(process.versions.node,10)||0" 2>/dev/null || echo 0)"
  if [[ "${major}" -ge 18 ]]; then
    echo "${AI_PKG} Packaging plugin (local Node ${major})..."
    (cd "${src}" && yarn package)
    return 0
  fi
  if command -v docker >/dev/null 2>&1; then
    local img="${PLUGIN_NODE_IMAGE:-node:20-bookworm}"
    echo "${AI_PKG} Packaging plugin via Docker (${img}; host Node is ${major:-unknown})..."
    docker run --rm \
      -v "${PLUGIN_PATH}:/work" \
      -w /work/sources \
      "${img}" \
      bash -lc 'corepack enable && yarn install && yarn package'
    return 0
  fi
  echo "${AI_FAIL} Need Node 18+ to run 'yarn package', or install Docker. Host Node major=${major}." >&2
  exit 1
}

package_plugin_sources

echo "${AI_PKG} Installing plugin into site '${SITE_ID}' via marketplace/copy..."
curl --fail-with-body --silent --show-error \
  --location \
  --request POST "${STUDIO_URL}/studio/api/2/marketplace/copy" \
  --header "Authorization: Bearer ${CRAFTER_STUDIO_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw "$(cat <<EOF
{
  "siteId": "${SITE_ID}",
  "path": "${PLUGIN_PATH}",
  "parameters": {}
}
EOF
)"

CLASSES_SRC="${PLUGIN_PATH}/authoring/scripts/classes"
CLASSES_DEST="${SITE_REPO_PATH}/config/studio/scripts/classes"
if [[ ! -d "${CLASSES_SRC}" ]]; then
  echo "${AI_WARN} Warning: classes source not found at ${CLASSES_SRC}, skipping copy." >&2
elif [[ ! -d "${SITE_REPO_PATH}" ]]; then
  echo "${AI_WARN} Warning: site sandbox not found at ${SITE_REPO_PATH}, skipping copy." >&2
else
  mkdir -p "${CLASSES_DEST}"
  echo "${AI_INFO} Copying authoring/scripts/classes to ${CLASSES_DEST}..."
  cp -r "${CLASSES_SRC}"/* "${CLASSES_DEST}/"
  # Site-maintained Groovy under config/studio/scripts/aiassistant/user-tools/ and aiassistant/llm/ is NOT copied
  # by this script; Studio reads those paths via configurationService when present.
  if git -C "${SITE_REPO_PATH}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "${SITE_REPO_PATH}" add config/studio/scripts/classes
    if git -C "${SITE_REPO_PATH}" diff --staged --quiet; then
      echo "${AI_OK} No changes to commit (classes already up to date)."
    else
      git -C "${SITE_REPO_PATH}" commit -m "Update CrafterQ plugin classes (Spring AI)"
      echo "${AI_OK} Committed plugin classes in site sandbox."
    fi
  else
    echo "${AI_WARN} Warning: ${SITE_REPO_PATH} is not a git repo; skipping commit."
  fi
fi

echo
echo "${AI_OK} Done."
