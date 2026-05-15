# shellcheck shell=bash
# Sourced by scripts/test/integration/* and scripts/test/functional/* — not part of the Studio plugin artifact (see scripts/test/README.md).

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/scripts/lib/emoji.inc.sh"

# Args: repo root (directory that contains scripts/.studio-token, same layout as install-plugin.sh).
aiassistant_test_source_studio_token_file() {
  local repo_root="$1"
  if [[ -n "${CRAFTER_STUDIO_TOKEN:-}" ]]; then
    return 0
  fi
  local f="${repo_root}/scripts/.studio-token"
  if [[ -f "${f}" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "${f}"
    set +a
  fi
}

# Args: repo root (directory that contains scripts/lib/studio-auth.sh)
aiassistant_test_require_auth() {
  local repo_root="$1"
  # shellcheck source=/dev/null
  source "${repo_root}/scripts/lib/studio-auth.sh"
  studio_load_token
  aiassistant_test_source_studio_token_file "${repo_root}"
  if [[ -z "${CRAFTER_STUDIO_TOKEN:-}" ]]; then
    echo "" >&2
    echo "${AI_FAIL} No Studio JWT loaded." >&2
    echo "    ${AI_HINT} Put the same export as install-plugin uses in: ${repo_root}/scripts/.studio-token (gitignored)" >&2
    echo "    ${AI_HINT} Example line: export CRAFTER_STUDIO_TOKEN='...'" >&2
    exit 2
  fi
}

# GET /studio/api/2/users/me — proves host/port + JWT; not a plugin test.
aiassistant_studio_preflight() {
  local studio_url="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
  local url="${studio_url}/studio/api/2/users/me"
  local tmp code ce
  tmp="$(mktemp)"
  echo ""
  echo "──────── Studio preflight (URL + JWT, not the plugin) ────────"
  echo "CRAFTER_STUDIO_URL=${studio_url}"
  echo "GET ${url}"
  set +e
  code="$(
    curl --silent --show-error --location --max-time 30 \
      -o "${tmp}" -w '%{http_code}' \
      --request GET "${url}" \
      --header "Authorization: Bearer ${CRAFTER_STUDIO_TOKEN}" \
      --header "Accept: application/json"
  )"
  ce=$?
  set -e
  if [[ "${ce}" -ne 0 ]]; then
    echo "" >&2
    echo "${AI_FAIL} Could not talk to Studio (curl exit ${ce})." >&2
    case "${ce}" in
      6) echo "  ${AI_HINT} Likely cause: bad host in CRAFTER_STUDIO_URL (DNS could not resolve)." >&2 ;;
      7) echo "  ${AI_HINT} Likely cause: connection refused — Studio not running, wrong port, or URL not the Studio host." >&2 ;;
      28) echo "  ${AI_HINT} Likely cause: operation timed out — Studio hung, firewall, or wrong network." >&2 ;;
      *) echo "  ${AI_HINT} See: curl exit code ${ce} in curl(1) manual." >&2 ;;
    esac
    echo "  Body (if any):" >&2
    head -c 2000 "${tmp}" 2>/dev/null | cat >&2 || true
    echo "" >&2
    rm -f "${tmp}"
    exit 2
  fi
  if [[ "${code}" == "401" || "${code}" == "403" ]]; then
    echo "" >&2
    echo "${AI_FAIL} Studio rejected the JWT (HTTP ${code})." >&2
    echo "  ${AI_HINT} Likely cause: expired token, wrong token, or token not for this Studio." >&2
    echo "    ${AI_HINT} Fix: grab a fresh JWT from the browser (same as install-plugin) and update scripts/.studio-token or CRAFTER_STUDIO_TOKEN." >&2
    echo "  Response (trimmed):" >&2
    head -c 2000 "${tmp}" 2>/dev/null | cat >&2 || true
    echo "" >&2
    rm -f "${tmp}"
    exit 2
  fi
  if [[ "${code}" != "200" ]]; then
    echo "" >&2
    echo "${AI_FAIL} Studio returned HTTP ${code} for /users/me (expected 200)." >&2
    echo "  Response (trimmed):" >&2
    head -c 2000 "${tmp}" 2>/dev/null | cat >&2 || true
    echo "" >&2
    rm -f "${tmp}"
    exit 2
  fi
  rm -f "${tmp}"
  echo "${AI_OK} Studio reachable and JWT accepted (HTTP ${code})."
}

# Args: studio_api_script, human label, GET path (must start with /studio/), optional siteId for install hint
aiassistant_plugin_get_or_fail() {
  local studio_api="$1"
  local human_label="$2"
  local path_only="$3"
  local site_hint="${4:-${SITE_ID:-${INTEGRATION_SITE_ID:-}}}"
  local out st
  echo ""
  echo "──────── Plugin check: ${human_label} ────────"
  echo "GET ${path_only}"
  set +e
  out="$("${studio_api}" GET "${path_only}" 2>&1)"
  st=$?
  set -e
  if [[ "${st}" -ne 0 ]]; then
    echo "" >&2
    echo "${AI_FAIL} ${human_label}" >&2
    echo "  This calls the AI Assistant plugin REST script (not generic Studio CRUD)." >&2
    echo "  Studio/JWT were already OK in the preflight step; a failure here usually means:" >&2
    echo "    - plugin REST scripts/classes not installed or out of date for this site, OR" >&2
    echo "    - server exception inside the script (Studio often wraps as HTTP 500 + code 1000)." >&2
    echo "  Next step: open Crafter Studio authoring logs and search for errors at the time of this request." >&2
    echo "" >&2
    echo "  Raw output (stdout+stderr):" >&2
    echo "${out}" >&2
    if echo "${out}" | grep -qE '"code"[[:space:]]*:[[:space:]]*1000|Internal system failure'; then
      echo "" >&2
      echo "  ---" >&2
      echo "  Interpretation: preflight was HTTP 200, so JWT and CRAFTER_STUDIO_URL are fine." >&2
      echo "  HTTP 500 + code 1000 here is Studio's generic shell around a plugin-side error; see authoring logs for the stack trace." >&2
      if [[ -n "${site_hint}" ]]; then
        echo "  Common fix for a stale site: from repo root run  ./scripts/install-plugin.sh ${site_hint}" >&2
      fi
    fi
    exit 1
  fi
  echo "${out}"
  echo "${AI_OK} ${human_label}"
}

# --- Try variants: set AIASSISTANT_TEST_FAIL_REASON on failure; return 0/1 (no stderr spam). Smoke summary uses these. ---

aiassistant_studio_preflight_try() {
  AIASSISTANT_TEST_FAIL_REASON=""
  local studio_url="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
  local url="${studio_url}/studio/api/2/users/me"
  local tmp code ce body
  tmp="$(mktemp)"
  set +e
  code="$(
    curl --silent --show-error --location --max-time 30 \
      -o "${tmp}" -w '%{http_code}' \
      --request GET "${url}" \
      --header "Authorization: Bearer ${CRAFTER_STUDIO_TOKEN}" \
      --header "Accept: application/json"
  )"
  ce=$?
  set -e
  body="$(head -c 1200 "${tmp}" 2>/dev/null | tr '\n' ' ' || true)"
  rm -f "${tmp}"
  if [[ "${ce}" -ne 0 ]]; then
    case "${ce}" in
      6) AIASSISTANT_TEST_FAIL_REASON="Cannot reach Studio (curl 6: DNS). Check CRAFTER_STUDIO_URL host." ;;
      7) AIASSISTANT_TEST_FAIL_REASON="Cannot reach Studio (curl 7: connection refused). Studio down, wrong port, or wrong URL." ;;
      28) AIASSISTANT_TEST_FAIL_REASON="Cannot reach Studio (curl 28: timeout). Network or Studio hung." ;;
      *) AIASSISTANT_TEST_FAIL_REASON="Cannot reach Studio (curl exit ${ce}). ${body}" ;;
    esac
    return 1
  fi
  if [[ "${code}" == "401" || "${code}" == "403" ]]; then
    AIASSISTANT_TEST_FAIL_REASON="JWT rejected (HTTP ${code}). Refresh scripts/.studio-token or CRAFTER_STUDIO_TOKEN. ${body}"
    return 1
  fi
  if [[ "${code}" != "200" ]]; then
    AIASSISTANT_TEST_FAIL_REASON="GET /users/me returned HTTP ${code} (expected 200). ${body}"
    return 1
  fi
  return 0
}

# Args: studio_api path_only site_hint(optional)
aiassistant_plugin_get_try() {
  AIASSISTANT_TEST_FAIL_REASON=""
  local studio_api="$1"
  local path_only="$2"
  local site_hint="${3:-}"
  local out st body
  set +e
  out="$("${studio_api}" GET "${path_only}" 2>&1)"
  st=$?
  set -e
  if [[ "${st}" -eq 0 ]]; then
    return 0
  fi
  body="$(printf '%s' "${out}" | tr '\n' ' ' | head -c 700)"
  if echo "${out}" | grep -qE '"code"[[:space:]]*:[[:space:]]*1000|Internal system failure'; then
    AIASSISTANT_TEST_FAIL_REASON="Plugin REST failed with Studio generic 500/code 1000 (JWT OK if test 1 passed). Check authoring logs. ${body}"
    if [[ -n "${site_hint}" ]]; then
      AIASSISTANT_TEST_FAIL_REASON="${AIASSISTANT_TEST_FAIL_REASON} Try: ./scripts/install-plugin.sh ${site_hint}"
    fi
    if [[ "${path_only}" == *"/aiassistant/scripts"* ]]; then
      AIASSISTANT_TEST_FAIL_REASON="${AIASSISTANT_TEST_FAIL_REASON} Hint: GET scripts/index walks user-tools registry + sandbox paths; a bad registry entry or huge script file often causes this—tail catalina.out for the Groovy stack trace."
    fi
  else
    AIASSISTANT_TEST_FAIL_REASON="Plugin REST request failed (curl exit ${st}). ${body}"
  fi
  return 1
}
