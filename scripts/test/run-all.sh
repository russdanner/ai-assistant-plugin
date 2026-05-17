#!/usr/bin/env bash
set -euo pipefail

# Single entrypoint for maintainer checks: shell syntax, same `yarn package` as install-plugin.sh
# (Rollup + form-pipeline verify), live Studio REST JSON contracts, and **by default** scripted **ai/stream**
# chat turns (OpenAI or whatever the Studio JVM is configured to use — not chosen by this script).
# Not part of the Studio plugin artifact. Intentionally does NOT run ESLint unless you opt in.
#
# Usage (repo root or any cwd):
#   ./scripts/test/run-all.sh
#
# Env:
#   RUN_ALL_SKIP_STUDIO=1          Skip live Studio steps (rest-contracts + chat scenarios); bash -n + yarn package only.
#   RUN_ALL_SKIP_CHAT_SCENARIOS=1 Skip only the chat scenario runner (still runs live rest-contracts when Studio is on).
#   RUN_ALL_WITH_LINT=1            Also run `yarn lint` in sources/ before `yarn package`.
#   CRAFTER_STUDIO_URL, INTEGRATION_SITE_ID — passed through when Studio checks run; CHAT_SITE_ID defaults to INTEGRATION_SITE_ID.
#   CHAT_AGENT_ID — optional override; otherwise run-chat-scenarios.mjs discovers ui.xml or uses the default agent UUID.
#   CHAT_SCENARIOS_FILE, CHAT_PREVIEW_TOKEN, CHAT_TURN_TIMEOUT_MS — see scripts/test/README.md.
#   CRAFTER_STUDIO_TOKEN or scripts/.studio-token — required unless RUN_ALL_SKIP_STUDIO=1.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../lib/studio-auth.sh
source "${REPO_ROOT}/scripts/lib/studio-auth.sh"

_RUN_TOTAL=3
if [[ "${RUN_ALL_SKIP_STUDIO:-}" != "1" ]]; then
  _RUN_TOTAL=4
fi

step() {
  echo ""
  echo "======== ${AI_TEST} $* ========"
}

fail() {
  echo "${AI_FAIL} run-all: $*" >&2
  exit 1
}

step "1/${_RUN_TOTAL}  bash -n (integration shell) + REST JSON selftest"
bash -n "${REPO_ROOT}/scripts/studio-api.sh"
bash -n "${REPO_ROOT}/scripts/install-plugin.sh"
bash -n "${REPO_ROOT}/scripts/test/integration/smoke.sh"
bash -n "${REPO_ROOT}/scripts/test/functional/rest-contracts.sh"
bash -n "${REPO_ROOT}/scripts/test/integration/e2e-site-lifecycle.sh"
bash -n "${REPO_ROOT}/scripts/test/integration/include/plugin-stream-probe.inc.sh"
bash -n "${REPO_ROOT}/scripts/test/integration/include/reporting.inc.sh"
bash -n "${REPO_ROOT}/scripts/lib/studio-auth.sh"
bash -n "${REPO_ROOT}/scripts/lib/emoji.inc.sh"
bash -n "${REPO_ROOT}/scripts/test/run-all.sh"
if command -v node >/dev/null 2>&1; then
  node --check "${REPO_ROOT}/scripts/test/functional/run-chat-scenarios.mjs"
fi
REST_CONTRACTS_SELFTEST=1 "${REPO_ROOT}/scripts/test/functional/rest-contracts.sh"
echo "${AI_OK} OK"

step "2/${_RUN_TOTAL}  sources: yarn package (same gate as install-plugin.sh packaging)"
if ! command -v yarn >/dev/null 2>&1; then
  fail "yarn not on PATH (need Node toolchain for sources/)."
fi
(
  cd "${REPO_ROOT}/sources"
  if [[ "${RUN_ALL_WITH_LINT:-}" == "1" ]]; then
    yarn lint
  fi
  yarn package
)
echo "${AI_OK} OK"

if [[ "${RUN_ALL_SKIP_STUDIO:-}" == "1" ]]; then
  step "3/${_RUN_TOTAL}  Studio plugin REST contracts (skipped RUN_ALL_SKIP_STUDIO=1)"
  echo "${AI_SKIP} OK (skipped Studio step; chat scenarios need live Studio)"
else
  step "3/${_RUN_TOTAL}  Studio plugin REST contracts (functional/rest-contracts.sh)"
  if ! studio_require_token; then
    fail "No JWT for Studio checks. Set CRAFTER_STUDIO_TOKEN or scripts/.studio-token, or re-run with RUN_ALL_SKIP_STUDIO=1."
  fi
  CRAFTER_STUDIO_URL="${CRAFTER_STUDIO_URL:-}" INTEGRATION_SITE_ID="${INTEGRATION_SITE_ID:-}" \
    "${SCRIPT_DIR}/functional/rest-contracts.sh"
  echo "${AI_OK} OK"

  if [[ "${RUN_ALL_SKIP_CHAT_SCENARIOS:-}" == "1" ]]; then
    step "4/${_RUN_TOTAL}  Plugin chat scenarios (skipped RUN_ALL_SKIP_CHAT_SCENARIOS=1)"
    echo "${AI_SKIP} OK (skipped chat scenarios)"
  else
    step "4/${_RUN_TOTAL}  Plugin chat scenarios (functional/run-chat-scenarios.mjs)"
    if ! command -v node >/dev/null 2>&1; then
      fail "node on PATH is required for chat scenarios (install Node 18+ or skip with RUN_ALL_SKIP_CHAT_SCENARIOS=1)."
    fi
    export CRAFTER_STUDIO_URL="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
    export CHAT_SITE_ID="${CHAT_SITE_ID:-${INTEGRATION_SITE_ID:-}}"
    scen="${CHAT_SCENARIOS_FILE:-}"
    if [[ -z "${scen}" ]]; then
      if [[ -f "${REPO_ROOT}/scripts/test/scenarios/chat-scenarios.json" ]]; then
        scen="${REPO_ROOT}/scripts/test/scenarios/chat-scenarios.json"
      else
        scen="${REPO_ROOT}/scripts/test/scenarios/chat-scenarios.example.json"
      fi
    fi
    node "${REPO_ROOT}/scripts/test/functional/run-chat-scenarios.mjs" "${scen}"
    echo "${AI_OK} OK"
  fi
fi

echo ""
echo "======== ${AI_OK} run-all: finished ========"
