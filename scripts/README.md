# Repo `scripts/` (maintainer + CI)

This directory is **for people working on the plugin repo** (local install, smoke checks, build verification). It is **not** the same tree as Studio site sandbox scripts under `config/studio/scripts/…`.

| Path | Role |
|------|------|
| **`test/run-all.sh`** | **Single “all tests” script:** `bash -n`, offline **`REST_CONTRACTS_SELFTEST=1`** on **`rest-contracts.sh`**, **`yarn package`** in `sources/` (same packaging gate as `install-plugin.sh`; ESLint only if `RUN_ALL_WITH_LINT=1`), then live **`rest-contracts.sh`** (JWT + JSON contracts via **`node`** / python3 / jq; `RUN_ALL_SKIP_STUDIO=1` skips Studio). Back-compat: **`test/integration/smoke.sh`** runs the same live script. |
| **`test/`** | **Automated testing harness only** — shell integration tests against a real Studio. **Not** packaged into the Crafter plugin zip; **does not** install or start any server-side “test services” inside Studio. See `test/README.md` and `test/NOT_SHIPPED_WITH_PLUGIN`. |
| **`install-plugin.sh`** | Copies plugin into a site via Studio API + optional local `authoring/scripts/classes` sync. Edit `CRAFTER_DATA` inside the script for your install. |
| **`studio-api.sh`** | Bearer `curl` helper for ad hoc Studio REST calls (same JWT as install). |
| **`lib/studio-auth.sh`** | Shared token loading (`CRAFTER_STUDIO_TOKEN` or `scripts/.studio-token`). |
| **`verify-aiassistant-form-pipeline.mjs`** | Invoked by `sources/yarn package` (Rollup build); validates form pipeline wiring. **Build-time**, not a Studio runtime service. |

**Plugin artifact (what authors install):** defined by `craftercms-plugin.yaml` and the Rollup output from `sources/` (plus `authoring/` paths that the descriptor references). Nothing under **`scripts/test/`** is part of that install.

If we ever add optional test infrastructure (e.g. Docker Compose for a fake LLM), it will live under **`scripts/test/`** (or `scripts/test/support/`) with its own README and will remain **out of** the plugin package unless explicitly documented elsewhere.
