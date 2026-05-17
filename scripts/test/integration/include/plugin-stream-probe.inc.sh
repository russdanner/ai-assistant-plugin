# shellcheck shell=bash
# Sourced by scripts/test/integration/* and scripts/test/functional/* — not part of the Studio plugin artifact (see scripts/test/README.md).

# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/scripts/lib/emoji.inc.sh"

# Encode siteId for query string (Node first — same toolchain as yarn package; then python3).
aiassistant_urlencode_site_id_for_query() {
  local sid="$1"
  if command -v node >/dev/null 2>&1; then
    node -e "process.stdout.write(encodeURIComponent(process.argv[1]))" "${sid}"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "${sid}"
    return 0
  fi
  printf '%s' "${sid}"
}

plugin_aiassistant_stream_invalid_json_probe() {
  local sid="$1"
  local tmp code out ce
  tmp="$(mktemp)"
  set +e
  code="$(
    curl --silent --show-error --location --max-time 30 \
      --request POST "${CRAFTER_STUDIO_URL:-http://localhost:8080}/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=$(aiassistant_urlencode_site_id_for_query "${sid}")" \
      --header "Authorization: Bearer ${CRAFTER_STUDIO_TOKEN}" \
      --header "Content-Type: application/json" \
      --header "Accept: application/json" \
      --data-raw '{not-json' \
      --write-out '%{http_code}' \
      --output "${tmp}"
  )"
  ce=$?
  set -e
  out="$(cat "${tmp}" 2>/dev/null || true)"
  rm -f "${tmp}"
  if [[ "${ce}" -ne 0 ]]; then
    echo "" >&2
    echo "${AI_FAIL} could not POST to ai/stream (curl exit ${ce}). Wrong CRAFTER_STUDIO_URL or Studio down?" >&2
    echo "  Body (if any): ${out:0:800}" >&2
    return 1
  fi
  if [[ "${code}" != "400" ]]; then
    echo "" >&2
    echo "${AI_FAIL} ai/stream probe expected HTTP 400 for broken JSON, got HTTP ${code}." >&2
    echo "  If this is 500 with code 1000, Studio often hides the script error; check authoring logs." >&2
    echo "  Body (trimmed): ${out:0:1200}" >&2
    return 1
  fi
  if ! echo "${out}" | grep -qi 'invalid'; then
    echo "" >&2
    echo "${AI_FAIL} ai/stream returned 400 but body did not mention invalid JSON (unexpected)." >&2
    echo "  Body (trimmed): ${out:0:1200}" >&2
    return 1
  fi
  echo "${AI_OK} ai/stream rejected invalid JSON (HTTP 400) as expected."
  return 0
}

# Same check as plugin_aiassistant_stream_invalid_json_probe but silent: sets AIASSISTANT_TEST_FAIL_REASON; returns 0/1.
plugin_aiassistant_stream_invalid_json_try() {
  local sid="$1"
  AIASSISTANT_TEST_FAIL_REASON=""
  local tmp code out ce body
  tmp="$(mktemp)"
  set +e
  code="$(
    curl --silent --show-error --location --max-time 30 \
      --request POST "${CRAFTER_STUDIO_URL:-http://localhost:8080}/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=$(aiassistant_urlencode_site_id_for_query "${sid}")" \
      --header "Authorization: Bearer ${CRAFTER_STUDIO_TOKEN}" \
      --header "Content-Type: application/json" \
      --header "Accept: application/json" \
      --data-raw '{not-json' \
      --write-out '%{http_code}' \
      --output "${tmp}"
  )"
  ce=$?
  set -e
  out="$(cat "${tmp}" 2>/dev/null || true)"
  rm -f "${tmp}"
  if [[ "${ce}" -ne 0 ]]; then
    AIASSISTANT_TEST_FAIL_REASON="Could not POST ai/stream (curl exit ${ce}). Wrong CRAFTER_STUDIO_URL or Studio down. $(printf '%s' "${out}" | tr '\n' ' ' | head -c 500)"
    return 1
  fi
  if [[ "${code}" != "400" ]]; then
    body="$(printf '%s' "${out}" | tr '\n' ' ' | head -c 700)"
    AIASSISTANT_TEST_FAIL_REASON="ai/stream expected HTTP 400 for broken JSON, got HTTP ${code}. ${body}"
    return 1
  fi
  if ! printf '%s' "${out}" | grep -qi 'invalid'; then
    body="$(printf '%s' "${out}" | tr '\n' ' ' | head -c 700)"
    AIASSISTANT_TEST_FAIL_REASON="ai/stream returned 400 but body did not mention invalid JSON. ${body}"
    return 1
  fi
  return 0
}
