#!/usr/bin/env bash
set -euo pipefail

# Live Studio: plugin REST must return HTTP 200 with JSON that matches the Groovy contracts
# (content-types/list.get.groovy, scripts/index.get.groovy), plus ai/stream rejects broken JSON.
# This is **functional** at the HTTP+payload boundary — not UI/E2E and not LLM behavior.
#
# JSON contracts: prefers Node (same toolchain as `yarn package` / this repo); then python3; then jq.
# Not part of the Studio plugin artifact.
#
# Env: CRAFTER_STUDIO_URL, INTEGRATION_SITE_ID (default aiat-2)
# Auth: CRAFTER_STUDIO_TOKEN or scripts/.studio-token
# Offline: REST_CONTRACTS_SELFTEST=1 — JSON unwrap (.result) + field contracts only (no Studio).

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# functional/ is one level deeper than integration/ — need three .. to reach repo root (not two).
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SITE_ID="${INTEGRATION_SITE_ID:-aiat-2}"
BASE="/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant"

# shellcheck source=../integration/include/reporting.inc.sh
source "${SCRIPT_DIR}/../integration/include/reporting.inc.sh"
# shellcheck source=../integration/include/plugin-stream-probe.inc.sh
source "${SCRIPT_DIR}/../integration/include/plugin-stream-probe.inc.sh"

TEST_NAMES=(
  "Studio connectivity + JWT (GET /studio/api/2/users/me)"
  "Plugin REST: content-types/list — HTTP 200 + JSON contract (siteId, contentTypes[], ok boolean)"
  "Plugin REST: scripts/index — HTTP 200 + JSON contract (tools[], registry, overrides[], …)"
  "Plugin REST: ai/stream rejects invalid JSON (HTTP 400 + error body)"
)

if command -v node >/dev/null 2>&1; then
  _JSON_TOOL=node
elif command -v python3 >/dev/null 2>&1; then
  _JSON_TOOL=python3
elif command -v jq >/dev/null 2>&1; then
  _JSON_TOOL=jq
else
  echo "${AI_FAIL} rest-contracts.sh: need node (recommended — same as yarn package), or python3, or jq on PATH." >&2
  exit 2
fi

if [[ "${REST_CONTRACTS_SELFTEST:-}" == "1" ]]; then
  :
else
  echo ""
  echo "======== ${AI_TEST} AI Assistant plugin — REST JSON contracts ========"
  echo "${AI_INFO} siteId=${SITE_ID}"
  echo "${AI_INFO} JSON validation: ${_JSON_TOOL} (same toolchain family as yarn package in this repo)"
  echo ""
  echo "${AI_LIST} Tests (planned):"
  i=1
  for name in "${TEST_NAMES[@]}"; do
    echo "  ${i}. ${name}"
    i=$((i + 1))
  done
  echo ""

  aiassistant_test_require_auth "${REPO_ROOT}"
fi

declare -a T_OK T_WHY

# Sets PLUGIN_GET_CODE, PLUGIN_GET_BODY, PLUGIN_GET_CURL_EXIT
plugin_rest_get() {
  PLUGIN_GET_CODE=""
  PLUGIN_GET_BODY=""
  PLUGIN_GET_CURL_EXIT=""
  local path_only="$1"
  local base_url="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
  local tmp code ce
  tmp="$(mktemp)"
  set +e
  code="$(
    curl --silent --show-error --location --max-time 30 \
      -o "${tmp}" -w '%{http_code}' \
      --request GET "${base_url}${path_only}" \
      --header "Authorization: Bearer ${CRAFTER_STUDIO_TOKEN}" \
      --header "Accept: application/json"
  )"
  ce=$?
  set -e
  PLUGIN_GET_CURL_EXIT="${ce}"
  PLUGIN_GET_CODE="${code}"
  PLUGIN_GET_BODY="$(cat "${tmp}" 2>/dev/null || true)"
  rm -f "${tmp}"
}

contract_fail_reason_for_plugin_get() {
  local label="$1"
  if [[ "${PLUGIN_GET_CURL_EXIT}" -ne 0 ]]; then
    AIASSISTANT_TEST_FAIL_REASON="${label}: curl exit ${PLUGIN_GET_CURL_EXIT}. Check CRAFTER_STUDIO_URL and network."
    return 0
  fi
  if [[ "${PLUGIN_GET_CODE}" != "200" ]]; then
    local body
    body="$(printf '%s' "${PLUGIN_GET_BODY}" | tr '\n' ' ' | head -c 700)"
    if echo "${PLUGIN_GET_BODY}" | grep -qE '"code"[[:space:]]*:[[:space:]]*1000|Internal system failure'; then
      AIASSISTANT_TEST_FAIL_REASON="${label}: HTTP ${PLUGIN_GET_CODE} with Studio code 1000 (see authoring logs). Try: ./scripts/install-plugin.sh ${SITE_ID}. ${body}"
    else
      AIASSISTANT_TEST_FAIL_REASON="${label}: expected HTTP 200, got HTTP ${PLUGIN_GET_CODE}. ${body}"
    fi
    return 0
  fi
  AIASSISTANT_TEST_FAIL_REASON=""
  return 0
}

# Args: response body, siteId. Validates JSON via piped body; prints errors to stderr; exit 0 = OK.
contract_content_types_list_node() {
  local body="$1" site="$2"
  printf '%s' "${body}" | node -e '
const fs = require("fs");
const site = process.argv[1];
let d;
try {
  d = JSON.parse(fs.readFileSync(0, "utf8"));
} catch (e) {
  console.error("invalid JSON:", e.message);
  process.exit(1);
}
function unwrapPluginBody(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return raw;
  const inner = raw.result;
  if (inner && typeof inner === "object" && !Array.isArray(inner)) return inner;
  return raw;
}
d = unwrapPluginBody(d);
const errs = [];
if (d.siteId !== site) errs.push("siteId want " + JSON.stringify(site) + " got " + JSON.stringify(d.siteId));
if (!Array.isArray(d.contentTypes)) errs.push("contentTypes is not an array");
if (typeof d.ok !== "boolean") errs.push("ok is not a boolean");
if (errs.length) {
  console.error(errs.join("; "));
  process.exit(1);
}
' "${site}"
}

contract_scripts_index_node() {
  local body="$1" site="$2"
  printf '%s' "${body}" | node -e '
const fs = require("fs");
const site = process.argv[1];
let d;
try {
  d = JSON.parse(fs.readFileSync(0, "utf8"));
} catch (e) {
  console.error("invalid JSON:", e.message);
  process.exit(1);
}
function unwrapPluginBody(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return raw;
  const inner = raw.result;
  if (inner && typeof inner === "object" && !Array.isArray(inner)) return inner;
  return raw;
}
d = unwrapPluginBody(d);
const errs = [];
if (d.ok !== true) errs.push("ok is not true: " + JSON.stringify(d.ok));
if (d.siteId !== site) errs.push("siteId want " + JSON.stringify(site) + " got " + JSON.stringify(d.siteId));
for (const k of ["tools", "imageGenerators", "llmScripts", "toolPromptOverrides"]) {
  if (!Array.isArray(d[k])) errs.push(k + " is not an array");
}
if (typeof d.registryStudioPath !== "string") errs.push("registryStudioPath is not a string");
if (typeof d.registryText !== "string") errs.push("registryText is not a string");
if (typeof d.registryTextTruncated !== "boolean") errs.push("registryTextTruncated is not a boolean");
if (errs.length) {
  console.error(errs.join("; "));
  process.exit(1);
}
' "${site}"
}

# Args: response body, siteId. Validates JSON from stdin (caller pipes body); prints errors to stdout; exit 0 = OK.
contract_content_types_list_py() {
  local body="$1" site="$2"
  printf '%s' "${body}" | python3 -c '
import json, sys
site = sys.argv[1]
try:
    d = json.load(sys.stdin)
except json.JSONDecodeError as e:
    print("invalid JSON:", e)
    sys.exit(1)

def unwrap_plugin_body(b):
    if not isinstance(b, dict):
        return b
    inner = b.get("result")
    if isinstance(inner, dict):
        return inner
    return b

d = unwrap_plugin_body(d)
errs = []
if d.get("siteId") != site:
    errs.append("siteId want %r got %r" % (site, d.get("siteId")))
if not isinstance(d.get("contentTypes"), list):
    errs.append("contentTypes is not a list")
if not isinstance(d.get("ok"), bool):
    errs.append("ok is not a boolean")
if errs:
    print("; ".join(errs))
    sys.exit(1)
' "${site}"
}

contract_scripts_index_py() {
  local body="$1" site="$2"
  printf '%s' "${body}" | python3 -c '
import json, sys
site = sys.argv[1]
try:
    d = json.load(sys.stdin)
except json.JSONDecodeError as e:
    print("invalid JSON:", e)
    sys.exit(1)

def unwrap_plugin_body(b):
    if not isinstance(b, dict):
        return b
    inner = b.get("result")
    if isinstance(inner, dict):
        return inner
    return b

d = unwrap_plugin_body(d)
errs = []
if d.get("ok") is not True:
    errs.append("ok is not true: %r" % (d.get("ok"),))
if d.get("siteId") != site:
    errs.append("siteId want %r got %r" % (site, d.get("siteId")))
for k in ("tools", "imageGenerators", "llmScripts", "toolPromptOverrides"):
    if not isinstance(d.get(k), list):
        errs.append("%s is not a list" % k)
if not isinstance(d.get("registryStudioPath"), str):
    errs.append("registryStudioPath is not a string")
if not isinstance(d.get("registryText"), str):
    errs.append("registryText is not a string")
if not isinstance(d.get("registryTextTruncated"), bool):
    errs.append("registryTextTruncated is not a boolean")
if errs:
    print("; ".join(errs))
    sys.exit(1)
' "${site}"
}

# Set REST_CONTRACTS_SELFTEST=1 to verify JSON unwrap + contracts without Studio (no JWT).
if [[ "${REST_CONTRACTS_SELFTEST:-}" == "1" ]]; then
  echo "======== ${AI_TEST} REST_CONTRACTS_SELFTEST (wrapped .result + bare body) ========"
  contract_content_types_list_node '{"result":{"siteId":"aiat-2","contentTypes":[],"ok":true}}' "aiat-2" \
    && echo "${AI_OK} content-types (Studio-wrapped)"
  contract_scripts_index_node '{"result":{"ok":true,"siteId":"aiat-2","tools":[],"imageGenerators":[],"llmScripts":[],"toolPromptOverrides":[],"registryStudioPath":"/scripts/aiassistant/user-tools/registry.yaml","registryText":"","registryTextTruncated":false}}' "aiat-2" \
    && echo "${AI_OK} scripts/index (Studio-wrapped)"
  contract_content_types_list_node '{"siteId":"aiat-2","contentTypes":[],"ok":true}' "aiat-2" \
    && echo "${AI_OK} content-types (bare plugin map)"
  echo "======== ${AI_OK} REST_CONTRACTS_SELFTEST: all passed ========"
  exit 0
fi

# --- 1: preflight ---
if aiassistant_studio_preflight_try; then
  T_OK+=("OK")
  T_WHY+=("")
else
  T_OK+=("FAIL")
  T_WHY+=("${AIASSISTANT_TEST_FAIL_REASON:-Unknown failure.}")
fi

# --- 2: content-types/list ---
AIASSISTANT_TEST_FAIL_REASON=""
plugin_rest_get "${BASE}/content-types/list?siteId=${SITE_ID}"
contract_fail_reason_for_plugin_get "content-types/list"
if [[ -n "${AIASSISTANT_TEST_FAIL_REASON}" ]]; then
  T_OK+=("FAIL")
  T_WHY+=("${AIASSISTANT_TEST_FAIL_REASON}")
else
  set +e
  case "${_JSON_TOOL}" in
  node)
    contract_err="$(contract_content_types_list_node "${PLUGIN_GET_BODY}" "${SITE_ID}" 2>&1)"
    ;;
  python3)
    contract_err="$(contract_content_types_list_py "${PLUGIN_GET_BODY}" "${SITE_ID}" 2>&1)"
    ;;
  jq)
    contract_err="$(printf '%s' "${PLUGIN_GET_BODY}" | jq -e --arg SITE "${SITE_ID}" '
      (if (.result | type) == "object" then .result else . end)
      | (.siteId == $SITE)
      and (.contentTypes | type == "array")
      and (.ok | type == "boolean")
    ' 2>&1)"
    ;;
  esac
  contract_ec=$?
  set -e
  if [[ "${contract_ec}" -ne 0 ]]; then
    T_OK+=("FAIL")
    T_WHY+=("content-types/list JSON contract failed (${_JSON_TOOL}): ${contract_err}")
  else
    T_OK+=("OK")
    T_WHY+=("")
  fi
fi

# --- 3: scripts/index (must match sources/src/aiAssistantScriptsApi.ts: …/scripts/index?siteId=…) ---
AIASSISTANT_TEST_FAIL_REASON=""
plugin_rest_get "${BASE}/scripts/index?siteId=${SITE_ID}"
contract_fail_reason_for_plugin_get "scripts/index"
if [[ -n "${AIASSISTANT_TEST_FAIL_REASON}" ]]; then
  T_OK+=("FAIL")
  T_WHY+=("${AIASSISTANT_TEST_FAIL_REASON}")
else
  set +e
  case "${_JSON_TOOL}" in
  node)
    contract_err="$(contract_scripts_index_node "${PLUGIN_GET_BODY}" "${SITE_ID}" 2>&1)"
    ;;
  python3)
    contract_err="$(contract_scripts_index_py "${PLUGIN_GET_BODY}" "${SITE_ID}" 2>&1)"
    ;;
  jq)
    contract_err="$(printf '%s' "${PLUGIN_GET_BODY}" | jq -e --arg SITE "${SITE_ID}" '
      (if (.result | type) == "object" then .result else . end)
      | (.ok == true)
      and (.siteId == $SITE)
      and (.tools | type == "array")
      and (.imageGenerators | type == "array")
      and (.llmScripts | type == "array")
      and (.toolPromptOverrides | type == "array")
      and (.registryStudioPath | type == "string")
      and (.registryText | type == "string")
      and (.registryTextTruncated | type == "boolean")
    ' 2>&1)"
    ;;
  esac
  contract_ec=$?
  set -e
  if [[ "${contract_ec}" -ne 0 ]]; then
    T_OK+=("FAIL")
    T_WHY+=("scripts/index JSON contract failed (${_JSON_TOOL}): ${contract_err}")
  else
    T_OK+=("OK")
    T_WHY+=("")
  fi
fi

# --- 4: stream invalid JSON ---
if plugin_aiassistant_stream_invalid_json_try "${SITE_ID}"; then
  T_OK+=("OK")
  T_WHY+=("")
else
  T_OK+=("FAIL")
  T_WHY+=("${AIASSISTANT_TEST_FAIL_REASON:-Unknown failure.}")
fi

echo "${AI_CHART} Results:"
failed=0
idx=0
for name in "${TEST_NAMES[@]}"; do
  n=$((idx + 1))
  st="${T_OK[idx]:-FAIL}"
  if [[ "${st}" == "OK" ]]; then
    echo "  ${AI_OK} ${n}. ${name}"
  else
    echo "  ${AI_FAIL} ${n}. ${name}"
    echo "       ${AI_HINT} ${T_WHY[idx]:-Unknown failure.}"
    failed=$((failed + 1))
  fi
  idx=$((idx + 1))
done
echo ""

if [[ "${failed}" -gt 0 ]]; then
  echo "======== ${AI_FAIL} SUMMARY: ${failed} test(s) failed ========"
  exit 1
fi
echo "======== ${AI_OK} SUMMARY: all tests passed ========"
exit 0
