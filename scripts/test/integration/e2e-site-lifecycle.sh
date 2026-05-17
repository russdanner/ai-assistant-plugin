#!/usr/bin/env bash
set -euo pipefail

# Maintainer-only lifecycle test (not packaged into the Studio plugin). See scripts/test/README.md
# and scripts/test/NOT_SHIPPED_WITH_PLUGIN.

# Integration-style test: create a Studio site, install this plugin, run plugin REST JSON contracts, delete site.
# (Contract script hits only org.craftercms.aiassistant plugin REST paths + JSON payload checks.)
#
# Prerequisites:
#   - CRAFTER_STUDIO_TOKEN or scripts/.studio-token (same as install-plugin.sh)
#   - scripts/install-plugin.sh CRAFTER_DATA must point at the same authoring install
#     where Studio creates sites (so classes copy + git commit targets the new sandbox)
#   - scripts/test/integration/create-site.json — copy from create-site.json.example and set
#     a unique siteId (or pass E2E_CREATE_JSON)
#
# Usage:
#   cp scripts/test/integration/create-site.json.example scripts/test/integration/create-site.json
#   # edit siteId to something unique, blueprintVersion if needed (see GET …/available_blueprints)
#   ./scripts/test/integration/e2e-site-lifecycle.sh
#
#   ./scripts/test/integration/e2e-site-lifecycle.sh --keep-site
#   ./scripts/test/integration/e2e-site-lifecycle.sh --teardown-only my-temp-site
#
# Env:
#   CRAFTER_STUDIO_URL   default http://localhost:8080
#   E2E_CREATE_JSON      path to create-site body (default: scripts/test/integration/create-site.json)
#   E2E_POST_CREATE_SLEEP_SEC  seconds to wait after create (default 5)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=/dev/null
source "${REPO_ROOT}/scripts/lib/studio-auth.sh"
# Propagate status glyphs to Python helpers in this script.
export AI_FAIL AI_OK AI_HINT AI_INFO AI_LIST AI_TEST AI_SKIP AI_WARN AI_CHART AI_PKG
STUDIO_API="${REPO_ROOT}/scripts/studio-api.sh"
STUDIO_URL="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
# shellcheck source=include/reporting.inc.sh
source "${SCRIPT_DIR}/include/reporting.inc.sh"
aiassistant_test_source_studio_token_file "${REPO_ROOT}"
CREATE_JSON="${E2E_CREATE_JSON:-${SCRIPT_DIR}/create-site.json}"
POST_CREATE_SLEEP="${E2E_POST_CREATE_SLEEP_SEC:-5}"

usage() {
  cat >&2 <<'EOF'
e2e-site-lifecycle.sh — create site → install-plugin → functional/rest-contracts.sh → delete site

Options:
  (none)              Full lifecycle using E2E_CREATE_JSON (default: scripts/test/integration/create-site.json)
  --keep-site         Do not delete the site at the end
  --skip-create       Use E2E_SITE_ID (required); site must already exist
  --skip-install      Skip install-plugin.sh (after create or with --skip-create)
  --skip-smoke        Skip scripts/test/functional/rest-contracts.sh (same checks as legacy smoke.sh path)
  --teardown-only ID  DELETE /studio/api/2/sites/ID only, then exit

Env: CRAFTER_STUDIO_TOKEN, CRAFTER_STUDIO_URL, E2E_CREATE_JSON, E2E_SITE_ID, E2E_POST_CREATE_SLEEP_SEC
EOF
}

teardown_only() {
  local sid="$1"
  local del_json
  if [[ -z "${sid}" ]]; then
    echo "${AI_FAIL} --teardown-only requires a site id." >&2
    exit 2
  fi
  if ! studio_require_token; then
    exit 2
  fi
  aiassistant_studio_preflight
  del_json="$(mktemp)"
  echo "${AI_TEST} DELETE site ${sid}"
  "${STUDIO_API}" DELETE "/studio/api/2/sites/${sid}" | tee "${del_json}" >/dev/null
  check_studio_response_file "${del_json}"
  rm -f "${del_json}"
  echo "${AI_OK} Teardown OK."
}

check_studio_response_file() {
  local f="$1"
  python3 - "$f" <<'PY'
import json, os, sys
path = sys.argv[1]
fail = os.environ.get("AI_FAIL", "FAIL:")
with open(path, encoding="utf-8") as fp:
    j = json.load(fp)
r = j.get("response") or {}
code = r.get("code")
if code is None:
    print(f"{fail} Studio API JSON missing response.code (unexpected).", file=sys.stderr)
    print(json.dumps(j)[:800], file=sys.stderr)
    sys.exit(1)
if code != 0:
    print(f"{fail} Studio API returned response.code != 0 (create/delete/marketplace call failed).", file=sys.stderr)
    print(json.dumps(j, indent=2)[:4000], file=sys.stderr)
    sys.exit(1)
PY
}

extract_site_id() {
  python3 - "$1" <<'PY'
import json, os, sys
with open(sys.argv[1], encoding="utf-8") as fp:
    j = json.load(fp)
for key in ("siteId", "site_id"):
    v = j.get(key)
    if v:
        print(str(v).strip())
        sys.exit(0)
fail = os.environ.get("AI_FAIL", "FAIL:")
print(f"{fail} create-site JSON must include siteId (or site_id)", file=sys.stderr)
sys.exit(1)
PY
}

KEEP_SITE=0
SKIP_CREATE=0
SKIP_INSTALL=0
SKIP_SMOKE=0
TEARDOWN_ONLY=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --keep-site)
      KEEP_SITE=1
      shift
      ;;
    --skip-create)
      SKIP_CREATE=1
      shift
      ;;
    --skip-install)
      SKIP_INSTALL=1
      shift
      ;;
    --skip-smoke)
      SKIP_SMOKE=1
      shift
      ;;
    --teardown-only)
      if [[ $# -lt 2 ]]; then
        echo "${AI_FAIL} Missing site id after --teardown-only" >&2
        exit 2
      fi
      TEARDOWN_ONLY="$2"
      shift 2
      ;;
    *)
      echo "${AI_FAIL} Unknown option: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -n "${TEARDOWN_ONLY}" ]]; then
  teardown_only "${TEARDOWN_ONLY}"
  exit 0
fi

if ! studio_require_token; then
  exit 2
fi
aiassistant_studio_preflight

if [[ "${SKIP_CREATE}" == "1" ]]; then
  SITE_ID="${E2E_SITE_ID:-}"
  if [[ -z "${SITE_ID}" ]]; then
    echo "${AI_FAIL} With --skip-create, set E2E_SITE_ID to the existing site id." >&2
    exit 2
  fi
else
  if [[ ! -f "${CREATE_JSON}" ]]; then
    cat >&2 <<EOF
${AI_FAIL} Missing ${CREATE_JSON}
  cp "${SCRIPT_DIR}/create-site.json.example" "${CREATE_JSON}"
Then set a unique siteId (and blueprintVersion if your Studio requires it).
EOF
    exit 2
  fi
  SITE_ID="$(extract_site_id "${CREATE_JSON}")"
  create_json_out="$(mktemp)"
  echo ""
  echo "${AI_TEST} Studio API: create site from blueprint (scaffolding, not the plugin under test)"
  echo "${AI_LIST} POST /studio/api/2/sites/create_site_from_marketplace (siteId=${SITE_ID})"
  "${STUDIO_API}" POST /studio/api/2/sites/create_site_from_marketplace "@${CREATE_JSON}" | tee "${create_json_out}" >/dev/null
  check_studio_response_file "${create_json_out}"
  rm -f "${create_json_out}"
  echo "${AI_OK} Create OK. Sleeping ${POST_CREATE_SLEEP}s for Studio to materialize the sandbox…"
  sleep "${POST_CREATE_SLEEP}"
fi

if [[ "${SKIP_INSTALL}" != "1" ]]; then
  echo "${AI_PKG} install-plugin.sh ${SITE_ID}"
  CRAFTER_STUDIO_URL="${STUDIO_URL}" "${REPO_ROOT}/scripts/install-plugin.sh" "${SITE_ID}" "${STUDIO_URL}"
else
  echo "${AI_SKIP} skip install-plugin.sh"
fi

if [[ "${SKIP_SMOKE}" != "1" ]]; then
  echo "${AI_TEST} scripts/test/functional/rest-contracts.sh (plugin REST JSON contracts)"
  CRAFTER_STUDIO_URL="${STUDIO_URL}" INTEGRATION_SITE_ID="${SITE_ID}" "${SCRIPT_DIR}/../functional/rest-contracts.sh"
else
  echo "${AI_SKIP} skip rest-contracts.sh"
fi

if [[ "${KEEP_SITE}" == "1" ]]; then
  echo "${AI_OK} Done (--keep-site). Site id: ${SITE_ID}"
  exit 0
fi

del_json="$(mktemp)"
echo "${AI_TEST} DELETE site ${SITE_ID}"
"${STUDIO_API}" DELETE "/studio/api/2/sites/${SITE_ID}" | tee "${del_json}" >/dev/null
check_studio_response_file "${del_json}"
rm -f "${del_json}"
echo "${AI_OK} Full lifecycle OK."
