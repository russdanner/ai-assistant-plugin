#!/usr/bin/env bash
# shellcheck shell=bash
# Sourced by install-plugin.sh, studio-api.sh, and scripts/test/integration/* helpers.
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/emoji.inc.sh"
# Resolves the repo's scripts/ directory and loads CRAFTER_STUDIO_TOKEN from env or scripts/.studio-token.
# (scripts/test is maintainer-only — not part of the Studio plugin artifact; see scripts/README.md.)

studio_scripts_dir() {
  # Directory containing this file is scripts/lib → parent is scripts/.
  cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd
}

studio_load_token() {
  local scripts_root
  scripts_root="$(studio_scripts_dir)"
  if [[ -z "${CRAFTER_STUDIO_TOKEN:-}" ]] && [[ -f "${scripts_root}/.studio-token" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "${scripts_root}/.studio-token"
    set +a
  fi
}

studio_require_token() {
  studio_load_token
  if [[ -z "${CRAFTER_STUDIO_TOKEN:-}" ]]; then
    echo "${AI_FAIL} Missing CRAFTER_STUDIO_TOKEN. Set env var or create scripts/.studio-token (gitignored). See docs/using-and-extending/studio-plugins-guide.md" >&2
    return 2
  fi
  return 0
}
