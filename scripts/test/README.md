# `scripts/test/` — **testing harness only**

> **This folder is not part of the Studio plugin install.**  
> It exists so integration checks are **obviously separate** from `authoring/` (runtime Groovy) and `sources/` (UI bundle). See **`NOT_SHIPPED_WITH_PLUGIN`** in this directory and **`scripts/README.md`** one level up.

What lives here:

- **`run-all.sh`** — **Single entrypoint:** `bash -n` + **`node --check`** on **`functional/run-chat-scenarios.mjs`** (when `node` is on PATH), **`REST_CONTRACTS_SELFTEST=1`** on **`functional/rest-contracts.sh`**, **`yarn package`** in `sources/`, then live **`functional/rest-contracts.sh`**, then by default **`functional/run-chat-scenarios.mjs`** (four **`ai/stream`** turns: OpenAI or whatever the Studio JVM is wired to — this repo does not start a mock LLM). Skip live Studio entirely with **`RUN_ALL_SKIP_STUDIO=1`**. Skip only chat with **`RUN_ALL_SKIP_CHAT_SCENARIOS=1`**. **`integration/smoke.sh`** is a thin wrapper for **`rest-contracts.sh`** (historical path).
- **`functional/rest-contracts.sh`** — **Plugin REST contracts:** JWT preflight, validates **`content-types/list`** and **`scripts/index`** payloads (unwraps Studio’s **`{ "result": { … } }`** envelope like `unwrapPluginScriptBody` in `sources/src/aiAssistant*Api.ts`), plus **`ai/stream`** invalid JSON → HTTP 400. Uses **`node`** for JSON when available (else **`python3`** / **`jq`**). **`REST_CONTRACTS_SELFTEST=1`** runs unwrap + field checks only (no Studio); **`run-all.sh`** runs that after `bash -n` in step 1. Default site **`aiat-2`** (`INTEGRATION_SITE_ID`). Helpers in **`integration/include/`** (stream probe URL-encodes `siteId` with **node** then **python3**).
- **`integration/e2e-site-lifecycle.sh`** — Optional disposable site: create → `install-plugin.sh` → **`functional/rest-contracts.sh`** → delete.
- **`integration/create-site.json.example`** — Template for marketplace create-site body (`create-site.json` is gitignored).
- **`scenarios/chat-scenarios.example.json`** — Default scripted turns for **`run-all.sh`** step 4 / **`run-chat-scenarios.mjs`**. **`agentId`** defaults to the same UUID as **`AI_ASSISTANT_DEFAULT_AGENT_ID`** in `sources/src/agentConfig.ts`; override with **`CHAT_AGENT_ID`** or the first **`<crafterQAgentId>`** under **`<agents>`** in site **ui.xml** (fetched via **`get_configuration`**). Copy to **`chat-scenarios.json`** to customize paths per site (gitignored).

**No services are installed into Studio from this tree** — only `curl`/bash against your existing authoring URL. If we add optional local test stacks (Docker, mock LLM, etc.), they will live under `scripts/test/` with a dedicated README and still **not** ship in the plugin artifact unless explicitly called out in product docs.

See **`docs/using-and-extending/studio-plugins-guide.md`** for JWT + scripted Studio API examples.

## Run everything you have today

**Preferred — one command** (from repo root):

```bash
./scripts/test/run-all.sh
```

Runs: **`bash -n`**, **`node --check`** on **`run-chat-scenarios.mjs`** (if `node` exists), **`REST_CONTRACTS_SELFTEST=1`**, **`yarn package`**, live **`rest-contracts.sh`**, then **by default** **`run-chat-scenarios.mjs`** (same JWT; **`node` 18+** required for that step). **ESLint:** `RUN_ALL_WITH_LINT=1`. **Skip live Studio + chat:** `RUN_ALL_SKIP_STUDIO=1`. **Skip only the four chat turns:** `RUN_ALL_SKIP_CHAT_SCENARIOS=1`. Optional **`CHAT_PREVIEW_TOKEN`**, **`CHAT_SCENARIOS_FILE`**, **`CHAT_AGENT_ID`** — see chat section below.

- **CI / headless / no Studio:** `RUN_ALL_SKIP_STUDIO=1 ./scripts/test/run-all.sh`
- **Optional disposable site test** (not part of `run-all.sh`): `./scripts/test/integration/e2e-site-lifecycle.sh` — requires `scripts/test/integration/create-site.json`.

There is **no** `yarn test` / Vitest suite in `sources/package.json`. If you prefer not to use `run-all.sh`, run the same steps by hand: `bash -n` on the shell scripts listed in `.github/workflows/scripts-test-integration.yml`, then `( cd sources && yarn package )`, then `./scripts/test/functional/rest-contracts.sh` (add `yarn lint` first if you want ESLint).

## Chat scenario harness (functionality + basic performance)

**`run-all.sh`** runs this **by default** as step **4/4** after live REST contracts (unless **`RUN_ALL_SKIP_STUDIO=1`** or **`RUN_ALL_SKIP_CHAT_SCENARIOS=1`**). The harness calls Studio **`ai/stream`**; the LLM backend is whatever Studio is configured for (OpenAI, a compatible proxy, etc.) — this repo does not embed a mock server.

1. Optional: copy **`scenarios/chat-scenarios.example.json`** → **`scenarios/chat-scenarios.json`** and edit **`formEngineItemPath`** / **`contentPath`** / **`contentTypeId`** for your site.
2. **`CHAT_AGENT_ID`** is optional: the runner resolves **`<crafterQAgentId>`** from site **ui.xml** via **`get_configuration`**, else uses the default UUID (`AI_ASSISTANT_DEFAULT_AGENT_ID` in `sources/src/agentConfig.ts`).
3. Standalone (Node 18+):

```bash
export CRAFTER_STUDIO_URL=http://localhost:8080
export CRAFTER_STUDIO_TOKEN='…'   # same as install-plugin / rest-contracts
# Optional: CHAT_AGENT_ID CHAT_SITE_ID CHAT_PREVIEW_TOKEN CHAT_TURN_TIMEOUT_MS
node scripts/test/functional/run-chat-scenarios.mjs scripts/test/scenarios/chat-scenarios.example.json
```

The four turns are: **hello**, **field-edit** (form-engine context), **translate-page** (preview; use **`CHAT_PREVIEW_TOKEN`** / **`crafterPreview`** for preview tools), **generate-image** (needs image tool + keys on the server). To skip chat when using **`run-all.sh`**: **`RUN_ALL_SKIP_CHAT_SCENARIOS=1 ./scripts/test/run-all.sh`**.
