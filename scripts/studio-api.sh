#!/usr/bin/env bash
set -euo pipefail

# Maintainer curl helper for Studio REST (see scripts/README.md). Not under scripts/test/.

# Authenticated curl to Crafter Studio REST (same Bearer JWT as install-plugin.sh).
# Prefer scripts/test/functional/rest-contracts.sh for AI Assistant plugin REST + JSON contract checks (or integration/smoke.sh, which runs the same script).
# Usage:
#   ./scripts/studio-api.sh GET  /studio/api/2/users/me
#   ./scripts/studio-api.sh GET  "/studio/api/2/configuration/get_configuration?siteId=new-demo&module=studio&path=ui.xml"
#   ./scripts/studio-api.sh POST /studio/api/2/marketplace/copy '{"siteId":"new-demo","path":"/abs/path/to/plugin","parameters":{}}'
#   ./scripts/studio-api.sh POST /studio/api/2/sites/create_site_from_marketplace @/tmp/create-site.json
#
# Env:
#   CRAFTER_STUDIO_TOKEN  (required) or scripts/.studio-token
#   CRAFTER_STUDIO_URL    base URL, default http://localhost:8080
#
# Log tailing (outside this script): e.g. Docker authoring service logs, or
#   tail -f "$CRAFTER_DATA/logs/tomcat/catalina.out"  (paths vary by install)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/studio-auth.sh
source "${SCRIPT_DIR}/lib/studio-auth.sh"

STUDIO_URL="${CRAFTER_STUDIO_URL:-http://localhost:8080}"

usage() {
  cat >&2 <<'EOF'
Authenticated curl to Crafter Studio (Bearer JWT: CRAFTER_STUDIO_TOKEN or scripts/.studio-token).

Usage:
  studio-api.sh <METHOD> <path> [body]
  studio-api.sh --help

  METHOD: GET | POST | PUT | PATCH | DELETE
  path:   must start with / (e.g. /studio/api/2/users/me)
  body:   for POST/PUT/PATCH — JSON string or @file (passed to curl --data-binary)

Env:
  CRAFTER_STUDIO_URL  base URL (default http://localhost:8080)

EOF
  echo "Examples (GET):" >&2
  echo "  $0 GET /studio/api/2/users/me" >&2
  echo "  $0 GET /studio/api/2/users/me/sites" >&2
  echo "  $0 GET '/studio/api/2/configuration/get_configuration?siteId=new-demo&module=studio&path=ui.xml'" >&2
  echo "  $0 GET /studio/api/2/sites/available_blueprints" >&2
  echo >&2
  echo "Examples (POST):" >&2
  echo "  $0 POST /studio/api/2/marketplace/copy '{\"siteId\":\"new-demo\",\"path\":\"/path/to/ai-assistant-plugin\",\"parameters\":{}}'" >&2
  echo "  $0 POST /studio/api/2/configuration/write_configuration '{\"siteId\":\"new-demo\",\"module\":\"studio\",\"path\":\"ui.xml\",\"environment\":\"\",\"content\":\"<configuration>...</configuration>\"}'" >&2
  echo "  $0 POST /studio/api/2/sites/create_site_from_marketplace @./create-site.json" >&2
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 2 ]]; then
  usage
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    exit 0
  fi
  exit 2
fi

if ! studio_require_token; then
  exit 2
fi

METHOD="$(echo "$1" | tr '[:lower:]' '[:upper:]')"
PATH_ONLY="$2"
shift 2

if [[ "${PATH_ONLY}" != /* ]]; then
  echo "${AI_FAIL} Path must start with / (e.g. /studio/api/2/...), got: ${PATH_ONLY}" >&2
  exit 2
fi

URL="${STUDIO_URL}${PATH_ONLY}"

curl_args=(
  --fail-with-body
  --silent
  --show-error
  --location
  --request "${METHOD}"
  --header "Authorization: Bearer ${CRAFTER_STUDIO_TOKEN}"
  --header "Accept: application/json"
)

if [[ "${METHOD}" == "POST" || "${METHOD}" == "PUT" || "${METHOD}" == "PATCH" ]]; then
  curl_args+=(--header "Content-Type: application/json")
  if [[ $# -ge 1 ]]; then
    body="$1"
    if [[ "${body}" == @* ]]; then
      curl_args+=(--data-binary "${body}")
    else
      curl_args+=(--data-raw "${body}")
    fi
  else
    echo "${AI_FAIL} POST/PUT/PATCH requires a JSON body argument or @file (see --help)." >&2
    exit 2
  fi
elif [[ $# -ge 1 ]]; then
  echo "${AI_FAIL} Extra arguments not allowed for ${METHOD}." >&2
  exit 2
fi

exec curl "${curl_args[@]}" "${URL}"
