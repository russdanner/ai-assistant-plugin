# shellcheck shell=bash
# Shared status glyphs for maintainer shell scripts (install-plugin, studio-auth, tests).
# Safe to source multiple times.

[[ -n "${_AI_ASSISTANT_EMOJI_LOADED:-}" ]] && return 0

AI_OK="✅"
AI_FAIL="❌"
AI_HINT="💡"
AI_LIST="📋"
AI_CHART="📊"
AI_TEST="🧪"
AI_SKIP="⏭️"
AI_WARN="⚠️"
AI_INFO="ℹ️"
AI_PKG="📦"

_AI_ASSISTANT_EMOJI_LOADED=1
