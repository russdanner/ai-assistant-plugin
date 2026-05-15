#!/usr/bin/env bash
set -euo pipefail

# Historical path: "smoke" now runs the same **REST JSON contract** checks as
# scripts/test/functional/rest-contracts.sh (JWT + JSON contracts via node / python3 / jq).

DIR="$(cd "$(dirname "$0")" && pwd)"
exec "${DIR}/../functional/rest-contracts.sh"
