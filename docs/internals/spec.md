## AI Assistant — Crafter Studio Plugin Specification

**`docs/internals/spec.md`** is the **official product requirements and mechanics specification** for this repository: surfaces, `ui.xml` contracts, form pipeline, stream semantics, autonomous behavior, and REST field shapes. Implementations in **`sources/`**, **`sources/control/`**, **`authoring/scripts/`**, and install descriptors **must** match this document. Product changes that alter documented behavior require updating **this file** (or the owning companion below) in the same merge as the code, or in an immediately following PR linked from the code PR description.

**Companion specifications** (official for their topics; keep them aligned when you touch the same behavior):

| Document | Owns |
|----------|------|
| [stream-endpoint-design.md](stream-endpoint-design.md) | SSE/stream wire behavior and related server contracts |
| [chat-and-tools-runtime.md](chat-and-tools-runtime.md) | Tool catalog, REST request/response fields, CrafterQ/SaaS HTTP, MCP client, operational troubleshooting contracts |
| [../using-and-extending/studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md) | **Build & install**: `yarn package`, Rollup outputs, canonical source paths vs generated `authoring/` paths, plugin id / descriptor invariants |
| [../using-and-extending/llm-configuration.md](../using-and-extending/llm-configuration.md) | **`<llm>`** identifiers, env + XML configuration, provider capability matrix, merge rules |
| [../using-and-extending/product-requirements.md](../using-and-extending/product-requirements.md) | Obligations for authors, admins, and integrators; wire-level and build contracts live in **spec.md** and the guides linked from this repository |

**Scope:** Site procedures — **[configuration-guide.md](../using-and-extending/configuration-guide.md)**. Product obligations — **[product-requirements.md](../using-and-extending/product-requirements.md)**. JVM **`-D`** flags — **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**. **`spec.md`** must record any **new** author-visible, wire-level, or cross-surface contract when it ships, including when the same material appears in a companion document.

**Review rule:** Code changes that alter documented behavior without updating **`spec.md`** / the relevant companion should be **blocked in review** unless the PR states a doc-only follow-up with a tracked issue (use sparingly—prefer same-merge updates).

**Audience:** Maintainers and advanced integrators. **Configuration & LLM keys:** [llm-configuration.md](../using-and-extending/llm-configuration.md). **Doc index:** [README.md](../README.md).

### Terminology (Product vs Integrations)

- **Studio AI assistant** — The authoring-facing assistant in Crafter Studio that this plugin provides: the form-engine control, the Helper widget on the Tools Panel or preview toolbar, optional autonomous scheduled runs, and **TinyMCE** when sites wire the RTE integration. Use this name for the product experience authors see.
- **CrafterQ** — A **backend integration** (CrafterQ API / SaaS chat) selected per agent when **`llm` is `crafterQ`**. It is **not** a synonym for the whole Studio assistant; **`openAI`** and other options are separate tools on the same assistant.

### Overview

This repository is a Crafter Studio plugin with **two main surfaces**:

- **Interactive chat agent** — The Studio AI assistant surfaces above. Agents are configured per site; each agent selects an **LLM** and may enable **function tools**. Supported **`<llm>`** values, keys, and capabilities are listed in [llm-configuration.md](../using-and-extending/llm-configuration.md). Tools may include CMS operations, HTTP helpers, CrafterQ APIs where configured, optional **MCP** remote tools when **`tools.json`** sets **`mcpEnabled: true`** (see [chat-and-tools-runtime.md](chat-and-tools-runtime.md#mcp-client-tools-streamable-http)), and site-defined Groovy tools.
- **Experimental autonomous agent framework** — Optional **AutonomousAssistants** widget in the Tools Panel: **scheduled**, **server-side**, **in-memory** runs that reuse the interactive tool catalog for supported LLMs; see § [Autonomous assistants widget](#autonomous-assistants-widget-tools-panel).

It currently focuses on:

- **Studio UI Helper widget**: Preview toolbar and/or Tools Panel entry points for the assistant (`craftercms.components.aiassistant.Helper`).
- **Autonomous runs (Tools Panel)**: Optional widget for scheduled in-memory assistant steps (prototype); see § [Autonomous assistants widget](#autonomous-assistants-widget-tools-panel).
- **TinyMCE (RTE) integration**: Toolbar controls that open the assistant UI and can insert returned text into the editor.

The UI uses a combination of:

- **Crafter Studio UI** (`@craftercms/studio-ui`) components (e.g., `DialogHeader`, `MinimizedBar`), and
- **Material UI** (`@mui/*`) primitives and icons.

### Code Locations (Source vs Built)

- **Source code**: `sources/src/`
- **Built plugin assets served by Studio**: `authoring/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/` (e.g. `components/index.js`; optional RTE bundle `tinymce/craftercms_aiassistant.js`)

### User-Facing Surfaces

#### Helper Widget (Studio UI)

- **Entry**: `sources/src/AiAssistantHelper.tsx`
- Embedded in Studio UI (e.g., Tools Panel, Preview toolbar) to open the **Studio AI assistant** (chat UI; agents may use **CrafterQ** or **OpenAI** per `llm`).
- Current implementation renders:
  - either an `IconButton` whose glyph is the first agent’s `icon` from `ui.xml` (mapped in `agentIcon.tsx`), falling back to the bundled assistant mark when unset, or
  - a `ToolsPanelListItemButton` (sidebar) with the bundled logo widget id (`logoWidgetId` in `consts.ts`)
  - With **one** configured agent, the toolbar click opens that agent directly (no menu). With **multiple** agents, a `Menu` lists each row.
- Agent rows are merged with site `/ui.xml` when Studio’s widget JSON omits label/icon/prompts (placeholder label `CrafterQ` is reconciled); duplicates are deduped after merge. When any agent has a **real** label, extra rows with the JSON-only placeholder label `CrafterQ` are removed (even if Studio assigns an id). When a **non-sample** agent exists, the plugin-install **default sample agent** row from **`craftercms-plugin.yaml`** (`019c7237-…`) is also dropped so the Helper menu is not doubled.
- Otherwise opens the Experience Builder ICE tools panel (or a floating dialog when `openAsPopup` is set on the agent).

Note: Message-bus wiring to open the assistant via `openCrafterQMessageId` is present but currently commented out in `AiAssistantHelper.tsx`.

#### Autonomous Assistants Widget (Tools Panel)

- **Widget id**: `craftercms.components.aiassistant.AutonomousAssistants` (constant `autonomousAssistantsWidgetId` in `sources/src/consts.ts`).
- **Component**: `sources/src/AiAssistantAutonomousAssistants.tsx` — registered in `sources/index.tsx` with the same **`plugin`** element as the Helper (`org.craftercms.aiassistant.studio` / `aiassistant` / `components` / `index.js`).
- **Purpose (prototype)**: **Studio AI assistant — autonomous** runs: server-side **in-memory** state and a small **supervisor** loop that **polls often** (fixed ~10s tick) and, on each tick, decides per agent whether to dispatch a step. Each agent’s **`schedule`** string is mapped to a **minimum period** between runs (`AutonomousScheduleProbe`); the tick interval is **not** the agent’s run interval — a tick only runs an agent when that period has elapsed since **`lastRunMillis`** (or **`nextStepRequired`** is set). For **`llm`** **`openAI`** (default), each step always uses the same **Studio native `tools[]` catalog** as interactive chat and runs the tool loop on the server, then expects a **final JSON-only** reply per the worker contract. For drafting or transforming text without other CMS tools in that inner model call, the model uses the **`GenerateTextNoTools`** tool (one-shot completion; not a separate orchestrator mode). Legacy **`<enableTools>`** on autonomous agents is **ignored**. State lives in the Studio JVM until restart or **Destroy in-memory store** in the widget’s Advanced section.
- **Where to place it**: Typically under **`craftercms.components.ToolsPanel`** → `configuration` → `widgets`, alongside or below the Helper when a site wants the left rail. The plugin descriptor **does not** auto-merge **AutonomousAssistants** (or **Helper**) into **ToolsPanel**; add the widget manually to **`config/studio/ui.xml`** or paste from **`docs/examples/studio-ui-aiassistant-fragments.xml`** (commit the sandbox for reliable loads).
- **How Studio passes props**: After the plugin registers, Studio’s **`Widget`** spreads the widget’s **`<configuration>`** onto the React component as **root props** (not only `props.configuration`). The autonomous parser reads **full widget props first**, then nested `configuration`, so `autonomousAgents` is found in either shape.

##### Configuration Shape (`autonomousAgents`)

Under `<configuration>`, use **`autonomousAgents`** with one or more **`agent`** entries, **or** define autonomous rows in **`config/studio/ai-assistant/agents.json`** with **`mode: autonomous`** (Project Tools editor). When that JSON file contains at least one autonomous row, the widget uses those definitions instead of **`autonomousAgents`** from **`ui.xml`**. Studio may deserialize repeated `<agent>` elements as an **array** or as a **numeric-keyed object**; the widget normalizer accepts both (same pattern as Helper **`agents`**). A **single** `<agent>` is often a **flat object** (`{ name, schedule, … }`); the parser must **not** treat it like a map with `Object.values()` (that yields string fragments and zero agents).

| Field (XML / JSON) | Required | Description |
|--------------------|----------|-------------|
| **`name`** or **`label`** | yes | Display name; used with site + **scope** to build the internal full agent id. |
| **`schedule`** | no | Quartz **6-field** cron (`sec min hour dom month dow`). Default `0 0 * * * ?` (hourly). `AutonomousScheduleProbe` maps a small set of patterns (e.g. `0 * * * * ?` → **once per minute**; `0/10 * * * * ?` → every **10 seconds**; `0 0 * * * ?` → **hourly**). |
| **`prompt`** | no | Base instructions for the run; the worker appends a strict JSON reply contract (report, next step, notes, optional human tasks). |
| **`scope`** | no | `project` (default), `user`, or `role` — controls which signed-in user may see that agent in status/UI and use agent-scoped control actions (`AutonomousScopeGuard`). |
| **`llm`** | no | Normalized default **`openAI`** for this feature. |
| **`llmModel`** | no | OpenAI model id (e.g. `gpt-4o-mini`). |
| **`imageModel`** | no | Reserved / aligned with other agents; autonomous worker does not call image generation today. |
| **`openAiApiKey`** | no | Same testing-only semantics as Helper agents — only when no server-side key (host env per **[llm-configuration.md](../using-and-extending/llm-configuration.md)**); see that doc. |
| **`startAutomatically`** | no | Default **true**. When **false**, `sync` registers the agent as **stopped** until **Start** on that agent. Aliases: **`start_automatically`**, **`automaticallyStart`**. **`disable_supervisor`** sets every non-disabled agent to **stopped** (preserves **`manualStop`** when the author had used **Stop** or `stopSelf`). **`enable_supervisor`** sets **waiting** only for agents with `startAutomatically` true **and** not **`manualStop`**; others stay **stopped** until **Start**. Re-sync restores **disabled** / **error** always; restores **stopped** only when **`manualStop`** or `startAutomatically` is false. While the supervisor is off, `sync` forces **stopped** for non-disabled / non-error agents. |
| **`stopOnFailure`** | no | Default **true**. When **true**, a failed worker run sets **`state.status`** to **`error`** (other agents keep running). When **false**, failure is stored in **`state.lastError`** and the agent returns to **`waiting`** with **`nextStepRequired`** so the next tick retries. Aliases: **`stop_on_failure`**. |
| **`expertSkills`** | no | Same optional markdown URL skills as Helper **`<expertSkill>`** rows (OpenAI **QueryExpertGuidance**). Sync body may send **`expertSkills`** as a JSON array; **`sync.post.groovy`** stores it on the registry definition for the worker. |
| **`manageOtherAgentsHumanTasks`** | no | Cross-agent human-task ownership; see worker / control script behavior. |

Example (minimal):

```xml
<widget id="craftercms.components.aiassistant.AutonomousAssistants">
  <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
  <configuration>
    <autonomousAgents>
      <agent>
        <name>Site health check</name>
        <schedule>0 * * * * ?</schedule>
        <prompt>You are an autonomous assistant for this Crafter site. Reply with JSON only as instructed by the server.</prompt>
        <scope>project</scope>
        <llm>openAI</llm>
        <llmModel>gpt-4o-mini</llmModel>
      </agent>
    </autonomousAgents>
  </configuration>
</widget>
```

##### Plugin REST Scripts (`/studio/api/2/plugin/script/...`)

All require an authenticated Studio session (same cookies / auth as other plugin scripts).

| Method | Path suffix | Role |
|--------|-------------|------|
| `POST` | `…/autonomous/assistants/sync` | Body: `{ siteId, agents }` — agents mirror parsed definitions from the widget; registers agents, ensures state rows, ensures supervisor **threads** exist; the supervisor **enabled** flag stays **off** until **`enable_supervisor`** / **Start system**. |
| `GET` | `…/autonomous/assistants/status?siteId=…` | Agents the caller may see, supervisor flags/tick, per-agent **`definition`** and **`state`**, plus aggregate fields below. |
| `POST` | `…/autonomous/assistants/control` | Body: `{ siteId, action, agentId?, taskId? }`. |

**`status` aggregate fields** (top-level JSON alongside agents/supervisor)

- **`openHumanTaskCount`**: Count of human tasks with **`status: open`** across all agents visible to the caller (for a badge in the widget header).
- **`hasAgentError`**: `true` if any visible agent has **`state.status === "error"`**.
- **`agentsInError`**: Array of `{ agentId, name, lastError }` for agents in error (`lastError` includes **`message`**, **`at`**, **`exceptionClass`**, optional **`stackTrace`**, and **`stopOnFailure`** as recorded at failure time).
- Supervisor snapshot may include **`supervisorHaltReason`** when **`haltSupervisorAfterAgentFailure`** was used (legacy); worker failures **do not** halt the supervisor—only the failing agent is stopped or retried per **`stopOnFailure`**.

**`control` actions**

- **Supervisor**: `enable_supervisor`, `disable_supervisor`, `shutdown_pools`, `destroy_store` (no `agentId`).
- **Per agent** (require **`agentId`**; scope-checked): `start_agent`, `stop_agent`, `execute_now`, `disable_agent`, `enable_agent`.
- **Human tasks** (require **`agentId`** + **`taskId`**): `complete_human_task`, `dismiss_human_task`, `reopen_human_task`.
- **Recovery**: `clear_agent_error` (requires **`agentId`**; scope-checked) — clears **`state.lastError`**, sets **`state.status`** to **waiting** with **`nextStepRequired: false`** so ticks can run again for that agent (supervisor stays as-is).

##### Human Tasks (Model → UI)

On each successful worker step, the model may return JSON with optional **`humanTasks`**: `[{ "title": string, "prompt": string, "assignedUsername"?: string, "assignedName"?: string }, …]` (aliases `assigneeUsername` / `assigneeName` are accepted). Prompts must be self-contained text a human can execute or paste into another assistant. Optional assignee fields set the Studio user shown in the widget when the agent’s instructions call for routing a task to someone. The server merges new rows into **`state.humanTasks`** (deduped by prompt text against non-dismissed tasks), then **trims to at most 10** rows by removing the **oldest** (`createdAt`) first. Each worker run also appends an **OpenSearch digest** of indexed `/site/website/` pages (paths, types, titles) to the user message as optional site context for whatever mission the agent’s prompt defines (requires `sync`/`status` to have registered `applicationContext` + security on an HTTP thread). Each row has **`id`**, **`title`**, **`prompt`**, **`status`** (`open` \| `done` \| `dismissed`), optional **`assignedUsername`** / **`assignedName`**, and timestamps. The widget lists tasks across agents with filters, assignee controls, toggle done, dismiss, or **copy prompt** to the clipboard.

##### Model JSON “Tools” (Same Reply Object As `humanTasks`)

The worker instructs the model to optionally return **task id arrays** and **`stopSelf`** so a single JSON payload can update human tasks without extra REST calls (applied in memory before the final state write):

- **`dismissHumanTaskIds`**, **`completeHumanTaskIds`**, **`reopenHumanTaskIds`**: string arrays of existing task **`id`** values on that agent’s **`state.humanTasks`**.
- **`stopSelf`**: when `true` after a successful step, the agent is moved to a **stopped** (idle) disposition instead of **waiting** for the next tick.

If the worker throws or the model response cannot be parsed as JSON, **`state.lastError`** is always populated (**`message`**, **`at`**, **`exceptionClass`**, **`stackTrace`** capped on the server). If the agent’s definition has **`stopOnFailure: true`** (default), **`state.status`** becomes **`error`** and that agent is skipped on tick until **`clear_agent_error`** / **Clear error**. If **`stopOnFailure: false`**, status returns to **`waiting`** with **`nextStepRequired: true`** so the next supervisor tick retries; other agents are unaffected and the supervisor is **not** halted.

**Context size:** The worker sends a **summarized** copy of state in the user prompt (recent reports/history, trimmed task prompts). The OpenAI native tool loop **truncates** each tool result wire payload (default cap **36k** characters) so huge **`ListPagesAndComponents`** / **`GetContent`** responses cannot exhaust the model context window. **`GenerateImage`** is a special case: a full **`data:image/...;base64,...`** must **not** be placed on the **`role:tool` wire** (OpenAI rejects the request with **`context_length_exceeded`**). The plugin stores the bitmap server-side by **`tool_call_id`**, sends a **compact** tool JSON (**`crafterqInlineImageRef`** + short instructions), and expands **`crafterq-tool-image://…`** into the real **`data:`** URL only in the **author-facing** assistant text (e.g. final SSE chunk). If the model omits markdown that references those placeholders, the server **appends** minimal **`![](crafterq-tool-image://…)`** lines before expansion so the chat still renders the image.

**Authoring “brain” parity:** Each autonomous OpenAI step prepends the same system stack as interactive **`/ai/stream`** — **`ToolPrompts.getOPENAI_AUTHORING_INSTRUCTIONS()`**, optional **plugin RAG** (`PluginRagVectorRegistry.adjustAuthoringCore`), site-id tool lines, optional **`expertSkills`** appendix + **`QueryExpertGuidance`** registration when **`expertSkills`** are synced on the agent definition, and **`PlanOrchestration.machineInstructionsAddendum()`**, then the agent’s JSON reply contract in a following section.

##### Widget UX (Errors and Open Tasks)

- Header shows a **warning badge** with **`openHumanTaskCount`** when greater than zero.
- When **`hasAgentError`** or a halt reason is present, the panel uses **error styling** (border/background) and lists **agents in error** with **`lastError.message`** (and detail when present); each row may call **`clear_agent_error`**.
- For site-level or custom Studio chrome, the widget sets **`document.body`** attributes **`data-cq-autonomous-open-tasks`** (count) and **`data-cq-autonomous-has-error`** (`"true"` / `"false"`) while mounted so CSS can tint a Tools icon or shell element if desired (Studio’s default Tools list icon is not modified by the plugin).

##### Implementation Pointers (Groovy)

- `authoring/scripts/classes/plugins/org/craftercms/aiassistant/autonomous/` — registry, state store, supervisor, worker, scope guard, id builder, schedule probe, **`AutonomousAssistantRuntimeHooks`** (Spring context + auth for worker threads), **`AutonomousSiteDigestBuilder`** (authoring OpenSearch digest for prompts).
- `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/autonomous/assistants/` — `sync.post`, `status.get`, `control.post`.

#### TinyMCE Plugin (RTE)

- **Entry**: `sources/src/craftercms_aiassistant.tsx`
- **TinyMCE plugin name**: `craftercms_aiassistant`
- **Registered toolbar controls**:
  - `aiAssistantOpen` (button)
  - `crafterqshortcuts` (menu button)
  - `crafterq` (split button)

##### Behavior

When invoked, the plugin:

- Reads either:
  - the current **selection** (preferred) or
  - the editor’s **full text content** (fallback)
- Builds a message array (based on the instance config) and opens the Studio AI assistant
- Provides an **“Insert”** action that inserts returned content into the selection via `editor.selection.setContent(content)`

##### XB vs non-XB Execution

The TinyMCE integration detects Experience Builder:

- **XB**: Uses `xb.post(openCrafterQMessageId, props)` to request opening the assistant in Studio via a message topic.
- **Non-XB**: Dynamically imports the plugin widgets bundle using:
  - `craftercms.services.plugin.importPlugin(site, 'aiassistant', 'components', 'index.js', 'org.craftercms.aiassistant.studio')`
  - then mounts the `AiAssistantPopover` widget inside the Studio React bridge (`CrafterCMSNextBridge`)

### Assistant Popover (Hosted Chat Shell)

- **Component**: `sources/src/AiAssistantPopover.tsx`
- **What it renders today**:
  - A Crafter Studio `DialogHeader`
  - An `<iframe>` pointing at a hosted CrafterQ chat URL (when `llm` is `crafterQ`):
    - `https://chat.crafterq.ai/019aa24e-6abc-7c27-a923-ae83fcaa9bd9`
  - A `MinimizedBar` when minimized
  - An `AlertDialog` scaffold for close/minimize confirmation (some close behavior currently commented out)

### Constants & Identifiers

Defined in `sources/src/consts.ts`:

- **Widget ids**
  - `logoWidgetId`
  - `chatWidgetId`
  - `popoverWidgetId`
  - `helperWidgetId`
  - `autonomousAssistantsWidgetId` (`craftercms.components.aiassistant.AutonomousAssistants`)
  - `projectToolsAiAssistantConfigWidgetId` (`craftercms.components.aiassistant.ProjectToolsConfiguration`) — **Project Tools** single entry (**UI** / **Agents** / **Tools and MCP** / **Scripts** / **Prompts and Context** tabs); the bundle opens this shell in a **large modal dialog** for space and focus (legacy widget ids use the same dialog shell with a different default tab).
  - `projectToolsCentralAgentsWidgetId`, `projectToolsScriptsSandboxWidgetId`, `projectToolsStudioUiSettingsWidgetId` — **legacy** widget ids; bundle still registers them and maps each to the same tabbed shell with the matching default tab (**ScriptsSandboxConfiguration** opens the **Tools and MCP** tab — `tools.json`, registry, and user Groovy — for sites that still have three merged tools until admins remove duplicates)
- **XB message topics**
  - `openCrafterQMessageId`
  - `CrafterQClosedMessageId`

### Plugin ID and Studio File URL

- **Plugin ID**: `org.craftercms.aiassistant.studio` — must be used in `ui.xml` for both the Tools Panel Helper and the Preview Toolbar icon so Studio serves the correct path.
- **Installed path**: Plugin assets are under `config/studio/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/` (e.g. `components/index.js`, `tinymce/craftercms_aiassistant.js`).
- **Plugin file requests**: Studio serves plugin JS from `/studio/1/plugin/file?siteId=...&pluginId=org.craftercms.aiassistant.studio&type=aiassistant&name=components&file=index.js`. These requests require **authenticated session** (same as preview): send the same cookies (e.g. `JSESSIONID`, `XSRF-TOKEN`, `crafterPreview`, `crafterSite`) or JWT/bearer auth that you use for Studio and preview. Unauthenticated requests will redirect to login and can cause 404-like behavior in the UI.
- **Bundle `PluginDescriptor.id`** (`sources/index.tsx`): Must match `plugin.id` in `craftercms-plugin.yaml` (`org.craftercms.aiassistant.studio`). Studio deduplicates `registerPlugin` on that id; a different id can let an earlier registration win and leave **`craftercms.components.aiassistant.Helper`** or **`craftercms.components.aiassistant.AutonomousAssistants`** unregistered (“Component … not found”).

### UI Placement (Toolbar vs Sidebar)

- **Tools Panel**: Optional — the Helper (and **AutonomousAssistants**) can appear in the left rail only if the site merges those widgets under **`ToolsPanel` → `configuration` → `widgets`** in **`ui.xml`**. The plugin descriptor does **not** install them there.
- **Preview Toolbar**: Marketplace install merges the Helper under **`PreviewToolbar` → `configuration` → `rightSection` → `widgets`** (avoids Studio **`performConfigurationWiring`** singleton-descent failures on **`middleSection/widgets`**). For an icon **next to the address bar**, move the merged **`<widget id="craftercms.components.aiassistant.Helper">…</widget>`** to **`middleSection` → `widgets`** in `config/studio/ui.xml` (same **`<configuration ui="IconButton"/>`** shape). The **`element`** root in **`craftercms-plugin.yaml`** is the **`<widget>`**; existing sites can paste from **`docs/examples/studio-ui-aiassistant-fragments.xml`** instead.

#### Common Gotchas

- **Two widget entries**: If you configure the Helper in **both** Tools Panel and Preview Toolbar, update both widget entries when changing agent labels/prompts or you’ll still see old values depending on where you click.
- **Form assistant accordion vs Redux**: Studio’s Redux snapshot of `ui.xml` can expose fewer `<agent>` entries than the site repo file. The form control merges agents from **both** that snapshot and `get_configuration` for `/ui.xml` so each configured agent can appear as its own row (deduped by **crafterQAgentId** + **label**, i.e. the same composite key as stream **`agentId`** + label), **unless** `config/studio/ai-assistant/agents.json` exists with at least one **`mode: chat`** (or omitted mode) row — then chat agents are taken **only** from that JSON file (sync XHR), not from `ui.xml`.
- **Form read-only / view mode**: When the content form is opened read-only (field or whole form), the form AI assistant **does not** load the plugin UI for that field: no portaled panel, no form-shell widen, and no `html.crafterq-form-panel-active` body inset.
- **Commit required**: Studio reads `config/studio/ui.xml` from the site sandbox repo; changes are most reliable after the `ui.xml` edits are **committed** in the site’s `sandbox` git repository.

<a id="studio-ui-flags-studio-uijson"></a>

### Studio UI Flags (`studio-ui.json`)

**Path:** `config/studio/scripts/aiassistant/config/studio-ui.json` (Studio module **`studio`**).

**Purpose:** Per-site **runtime** switches read by the React bundle (sync **`get_configuration`**, per-site cache). They **do not** remove **`ui.xml`** merges; they gate rendering or client-only augmentation.

| Key | Behavior |
|-----|----------|
| **`showAiAssistantsInTopNavigation`** | When **`false`**, **`AiAssistantHelper`** does not render the **`ui="IconButton"`** preview **toolbar** control. Tools Panel **`ListItemButton`** Helper is unchanged. |
| **`showAutonomousAiAssistantsInSidebar`** | When **`true`**, **`AutonomousAssistants`** renders in the Tools Panel sidebar (experimental). Omitted or **`false`** → no UI (widget may stay in **`ui.xml`**). |
| **`contentTypeImageAugmentationScope`** | **`all`** \| **`none`** \| **`selected`** — controls the preview **content-types** bus patch that sets **`allowImagesFromRepo`** on **image-picker** fields using the AI URL datasource (Experience Builder drag targets). |
| **`contentTypeIdsForImageAugmentation`** | Used when scope is **`selected`**: array of content-type ids (normalized with leading **`/`**). |

**Bulk form field:** **Project Tools → AI Assistant** → **UI** tab may insert/remove a marked **`org.craftercms.aiassistant.studio/ai-assistant`** field in **`form-definition.xml`** (implementation: `sources/src/aiAssistantFormControlBulk.ts`).

**Catalog REST:** `GET /studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/content-types/list?siteId=<site>` — Groovy delegates to **`StudioToolOperations.listStudioContentTypes`**.

**Chat composer placeholders:** Example prompt text uses native **`placeholder`** on the main **`TextField`** (grey hint until the author types); central **`agents.json`** editor uses placeholders on quick-prompt and autonomous system-prompt fields.

### Agent Configuration (Ui.xml)

You can configure one or more agents so that the toolbar shows a **dropdown** when multiple agents are defined, or opens chat **directly** when only one agent is configured. By default, chat opens in the **Experience Builder right (ICE) tools panel** and **edit mode** is turned on if it was off. Set **`<openAsPopup>true</openAsPopup>`** on an agent to use the legacy **floating dialog** instead. Each agent has a **label**, optional **icon**, **crafterQAgentId** (CrafterQ SaaS UUID), optional **llmModel** when `llm` is **openAI**, and a **prompts** list (quick message buttons above the chat). Multiple popup dialogs can be open at once when using popup mode.

#### Supported Configuration Structure

The plugin expects this XML shape in `config/studio/ui.xml`. The `<configuration>` block is passed by Studio the same way as in other plugins (e.g. [plugin-studio-uigoodies CopyCurrentPageUrl](https://github.com/russdanner/plugin-studio-uigoodies/blob/master/src/packages/uigoodies-components/src/components/CopyCurrentPageUrl.tsx)); repeated elements (e.g. multiple `<agent>`) may be deserialized as arrays or as objects with numeric keys—both are supported.

```xml
<widget id="craftercms.components.aiassistant.Helper">
  <plugin id="org.craftercms.aiassistant.studio"
          type="aiassistant"
          name="components"
          file="index.js"/>
  <configuration>
    <agents>
      <agent>
        <crafterQAgentId>019c7237-478b-7f98-9a5c-87144c3fb010</crafterQAgentId>
        <label>Authoring Assistant</label>
        <icon id="@mui/icons-material/ChatRounded"/>
        <prompts>
          <prompt>What can you help me with?</prompt>
          <prompt>Improve this text</prompt>
          <prompt>Suggest a headline</prompt>
        </prompts>
      </agent>
      <agent>
        <crafterQAgentId>019c7237-478b-7f98-9a5c-87144c3fb010</crafterQAgentId>
        <label>Content Writer</label>
        <icon id="@mui/icons-material/EditRounded"/>
        <prompts>
          <prompt>Draft a short paragraph</prompt>
          <prompt>Summarize this content</prompt>
        </prompts>
      </agent>
      <agent>
        <crafterQAgentId>019c7237-478b-7f98-9a5c-87144c3fb010</crafterQAgentId>
        <label>Quick Help</label>
        <icon id="@mui/icons-material/SupportAgentRounded"/>
        <prompts>
          <prompt>Explain this in simpler terms</prompt>
          <prompt>Give me bullet points</prompt>
        </prompts>
      </agent>
    </agents>
  </configuration>
</widget>
```

- **configuration** — Optional attribute `ui="IconButton"` shows the toolbar control as an icon; omit for list-style (e.g. in Tools Panel).
- **agents** — One or more **agent** entries.
- **agent.crafterQAgentId** — CrafterQ SaaS agent UUID; sent as **`agentId`** on stream/chat and used with **label** for dedupe / form toggles. ui.xml tag **`<crafterQAgentId>`** (widget JSON **`crafterQAgentId`**). For **OpenAI** without CrafterQ calls, may be omitted (empty `agentId` when allowed).
- **agent.label** — Display name in the dropdown and in the popover header.
- **agent.icon** — Optional. Use **`id`** the same way as elsewhere in Studio `ui.xml`: Studio resolves icons through **`SystemIcon`** (registered UI components). Examples: **`@mui/icons-material/ChatRounded`**, built-in SVG ids like **`craftercms.icons.Component`**, or a **plugin-registered widget id** that renders your glyph — **`craftercms.components.aiassistant.OpenAILogo`** (same SVG as **`AiAssistantLogo.tsx`**, from `logoWidgetId` in `sources/src/consts.ts`). Legacy ids **`craftercms.components.aiassistant.AiAssistantLogo`** and **`craftercms.components.aiassistant.CrafterQLogo`** are registered as the same component for older configs. Alternatively, put an **inline SVG** as the **body** of `<icon>` with CDATA; size with CSS in the SVG or rely on the plugin’s small icon box; use `fill="currentColor"` on paths to follow toolbar color.
- **agent.prompts** — Optional; list of **prompt** elements; each becomes a quick message button above the chat.
- **agent.llm** — Optional in `ui.xml`, but **should be set explicitly** (`crafterQ`, `openAI`, `claude`, …). If omitted, the client may omit `llm` from the stream/chat POST; the server **400**s unless **`siteId`** + **`agentId`** merge copies **`llm`** from **`/ui.xml`**. Missing, blank, or unknown **`llm`** after merge is rejected (`StudioAiLlmKind.normalize`). See **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.
- **agent.llmModel** — Optional model id when `llm` is `openAI` (e.g. `gpt-4o-mini`); server default applies if omitted. ui.xml **`<llmModel>`**; stream/chat JSON **`llmModel`**.
- **agent.imageModel** — Optional OpenAI **Images** model id for the **GenerateImage** tool (e.g. `gpt-image-1` or `gpt-image-1-mini`). ui.xml **`<imageModel>`**; stream/chat JSON **`imageModel`**. If omitted or blank, **`GenerateImage`** fails until configured — **no** server default in site config (JVM-only overrides, if any, are listed in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**).
- **agent.openAiApiKey** — Optional; **testing only** — OpenAI key in ui.xml when server **`OPENAI_API_KEY`** (or other provider env) is unset. See **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.
- **agent.openAsPopup** — Optional boolean (default **false** when omitted). **`true`** opens chat in the floating MUI dialog; **`false`** or omitted opens chat in the **ICE / Experience Builder** right sidebar panel (and enables preview edit mode).
- **agent.translateBatchConcurrency** — Optional integer **1–64** for parallel **`translate_content_batch`** work on the server. ui.xml **`<translateBatchConcurrency>`** (aliases **`translate_batch_concurrency`**). Omitted on the stream request → server default **25** (attribute unset).

### Server-side Chat (AiOrchestration; Spring AI Is Multi-vendor)

When the plugin’s **REST** chat or stream endpoints are used (`ai/agent/chat` or `ai/stream`), the server uses **`AiOrchestration`**, which selects the backend from **`llm`** in the JSON body (mirrors widget `<llm>`). **Spring AI** supplies several **`ChatModel`** integrations (including **`OpenAiChatModel`** for hosts that speak a common chat-completions JSON API, and **`AnthropicChatModel`** for Anthropic); the **`OpenAi*`** type names describe that **wire**, not “Spring AI only supports OpenAI’s product.”

- **`crafterQ`** (when **`llm`** resolves to **`crafterQ`** after merge + normalize): **`ExpertChatModel`** POSTs a **single string `prompt`** (and optional `chatId`) to CrafterQ’s `/v1/chats` API. **No CMS tools** on this path.
- **`openAI`**: **`OpenAiChatModel`** with **`AiOrchestrationTools`** (GetContent, **ListContentTranslationScope** (reference tree + suggested chunks — default one path per chunk, no XML bodies), WriteContent, ListPagesAndComponents, **GenerateImage**, ConsultCrafterQExpert, **ListCrafterQAgentChats**, **GetCrafterQAgentChat** when **`crafterQAgentId`** is set, etc.) and native tool calling.
- **`WriteContent` (site `*.xml`):** For **required** top-level **image-picker** fields that are still **empty**, **`StudioToolOperations`** may set the field text to a **`data:image/png;base64,...`** placeholder generated in-process (same pattern as studio-ui **`generatePlaceholderImageDataUrl`** / Experience Builder). No fixed repository path and no copying of arbitrary form **defaultValue** text into the item. For **required** or **`minSize`‑constrained** top-level **`checkbox-group`** fields backed by a **taxonomy** datasource (datasource **`type`** contains `taxonomy`, e.g. simple taxonomy), **`WriteContent`** may append **`item`** rows (`key` + typed value element such as **`value_smv`**) from the taxonomy list XML under **`/site/...`** until the constraint is satisfied (deterministic order: first unused keys from the taxonomy file).

- **OpenAI — conversation continuity and tool omission (all surfaces):** **`AiAssistantChat`** prepends an abbreviated **prior user/assistant turns** block to the wire prompt on every send (XB/ICE sidebar, floating dialog, and form-engine assistant). Optional POST **`omitTools: true`** or quick prompt **`&lt;omitTools&gt;true&lt;/omitTools&gt;`** drops CMS tools for that single request on **any** surface; otherwise **`enableTools`** / agent defaults apply.

- **Experience Builder / ICE (`embedTarget=icePanel`)** — Chat uses Studio **preview** hooks. The stream request may include **`contentPath`** / **`contentTypeId`**; the server appends **repository** authoring context so tools align with **saved** content in git.
- **Content-type form assistant** (`getAuthoringFormContext` from `control/ai-assistant`) — Authoritative item state is the browser **`form.model`** until the author clicks **Save**. The UI sends **`authoringSurface: "formEngine"`** and **omits** preview `contentPath` / `contentTypeId` so the server does not imply repo == open form. Each send appends a form appendix: **form-definition.xml**, **`CStudioForms.Util.serializeModelToXml(form, false)`** (Save-shaped live XML), optional **model JSON**, plus instructions for a fenced JSON object **`crafterqFormFieldUpdates`** (legacy key name; maps field ids to string values). When the stream completes, the client parses that block and applies updates via **`form.updateModel`**, control **`setValue`**, **`renderValidation`**, and section **`notifyValidation`**. **`AuthoringPreviewContext.appendFormEngineAuthoringNotice`** (only when `authoringSurface: formEngine`) adds a **short** note that tools read/write the repo, not the open form. **Strong** “return `crafterqFormFieldUpdates` JSON for the browser to apply” instructions are appended **only** when the client sends **`formEngineClientJsonApply: true`** (`appendFormEngineClientJsonApplyInstructions`). **Experience Builder / ICE** must **not** send `authoringSurface: formEngine` or that flag — they use **`contentPath`** / **`contentTypeId`** and the normal preview block so Spring AI tools can update the repository.

- **Non-streaming** (`ai/agent/chat`): `AiOrchestration.chatProxy()`.
- **Streaming** (`ai/stream`): `AiOrchestration.chatStreamWithSpringAi()` — SSE shape unchanged for the UI.
- **Tools**: Defined in `AiOrchestrationTools.groovy`; attached when the session supports **native Studio tools** (**tools-loop** **`llm`** values such as **`openAI`** / **`xAI`** / **`deepSeek`** / **`llama`** / **`gemini`**, **Claude**, and **script** bundles that opt into the tools-loop or Anthropic tool transports)—**not** on hosted **`crafterQ`** (`ExpertChatModel`). See **[stream-endpoint-design.md](stream-endpoint-design.md)**, **[llm-configuration.md](../using-and-extending/llm-configuration.md)**, and **[chat-and-tools-runtime.md](chat-and-tools-runtime.md)**.
  - **MCP (Model Context Protocol) client:** **Opt-in** via `config/studio/scripts/aiassistant/config/tools.json`: set JSON boolean **`mcpEnabled`** to **`true`**, then declare **`mcpServers`** (Streamable HTTP endpoints; see **[studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md)** and **[chat-and-tools-runtime.md](chat-and-tools-runtime.md#mcp-client-tools-streamable-http)**). If **`mcpEnabled`** is omitted or not **`true`**, **`mcpServers` is ignored**. On each Studio chat request that builds the tool catalog with MCP on, the plugin **initializes** each server (`initialize` → `notifications/initialized` → `tools/list`), then registers one **native function tool per MCP tool** whose tools-loop wire name is **`mcp_<serverId>_<mcpToolName>`** (sanitized, max 64 characters). Tool calls use **`tools/call`** on the same per-request **MCP session** (including **`Mcp-Session-Id`** when the server returns one). URLs must pass the same **SSRF** gate as **`FetchHttpUrl`**. JVM knobs that can disable outbound HTTP (including MCP) are documented in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**. MCP tools are **not** dropped when **`enabledBuiltInTools`** is a whitelist (they are extension catalog entries alongside **`InvokeSiteUserTool`**). Admins may hide individual MCP wire tools with **`disabledMcpTools`** or **`disabledBuiltInTools`** (name match).
  - **MCP `headers` and `${env:…}`:** Each header value string expands **`${env:VARIABLE_NAME}`** to **`System.getenv(VARIABLE_NAME)`** on the Studio JVM (unset or missing variable → empty string). Multiple placeholders per value are supported. Applied before outbound MCP requests (including Project Tools **List MCP tools** / save preview).
  - Backward-compatible: `<prompt>Text</prompt>`
  - Structured (recommended):
    - `<prompt><userText>...</userText><additionalContext>...</additionalContext><omitTools>true</omitTools></prompt>` — optional **`omitTools`** omits CMS tools for that chip’s request only (XB, ICE, dialog, or form-engine).
  - Macros (expanded at send time):
    - `DATE_TODAY`, `TIME_NOW`, `CURRENT_PAGE`, `CURRENT_USERNAME`
    - `CURRENT_CONTENT_TYPE` — Replaced with the **form definition XML** of the content type of the item currently being previewed (loaded from Studio config). If there is no preview item or no type, a short message is used instead.
    - `CONTENT_TYPE:<contentTypeId>` — Replaced with the **form definition XML** for the given content type (e.g. `CONTENT_TYPE:page/home`, `CONTENT_TYPE:component/hero`). The form is loaded from `/config/studio/content-types/{contentTypeId}/form-definition.xml`. In the chat bubble, these macros are shown as short placeholders (e.g. `[Form: page/home]`) so the full XML does not appear in the log.
    - `CURRENT_CONTENT` — Replaced with the **raw content XML** of the item currently being previewed (loaded via Studio content API). If there is no preview item, a short message is used instead. **Exception:** In the **form-engine AI assistant**, this is replaced with **live XML from `serializeModelToXml`** when available, else **live `model` JSON**, so unsaved edits are included.
    - `CONTENT:<path>` — Replaced with the **raw content XML** of the content item at the given path (e.g. `CONTENT:/site/website/index.xml`, `CONTENT:/site/components/headers/main.xml`). The path must start with `/`. In the chat bubble, content macros are shown as short placeholders (e.g. `[Content: /site/website/index.xml]`). **Exception:** In the form-engine assistant, if `<path>` is the same item as the open form (`form.path`), substitution uses the **same live XML/JSON** rules as `CURRENT_CONTENT`; other paths still load XML from the repository.

Example (single agent — click opens ICE panel by default; add `<openAsPopup>true</openAsPopup>` for a floating dialog):

```xml
<widget id="craftercms.components.aiassistant.Helper">
  <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
  <configuration ui="IconButton">
    <agents>
      <agent>
        <crafterQAgentId>019c7237-478b-7f98-9a5c-87144c3fb010</crafterQAgentId>
        <label>Authoring Assistant</label>
        <llm>crafterQ</llm>
        <icon id="@mui/icons-material/ChatRounded"/>
        <prompts>
          <prompt>
            <userText>What can you help me with?</userText>
          </prompt>
          <prompt>
            <userText>Summarize this content</userText>
            <additionalContext>Summarize the current page content with 3-5 bullet points and a short conclusion.</additionalContext>
          </prompt>
        </prompts>
      </agent>
    </agents>
  </configuration>
</widget>
```

Example (multiple agents — click shows dropdown to choose agent):

```xml
<widget id="craftercms.components.aiassistant.Helper">
  <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
  <configuration ui="IconButton">
    <agents>
      <agent>
        <crafterQAgentId>019c7237-478b-7f98-9a5c-87144c3fb010</crafterQAgentId>
        <label>Authoring Assistant</label>
        <icon id="@mui/icons-material/ChatRounded"/>
        <prompts>
          <prompt>Improve this text</prompt>
          <prompt>Suggest a headline</prompt>
        </prompts>
      </agent>
      <agent>
        <crafterQAgentId>another-agent-uuid</crafterQAgentId>
        <label>Content Writer</label>
        <icon id="@mui/icons-material/EditRounded"/>
        <prompts>
          <prompt>Draft a short paragraph</prompt>
        </prompts>
      </agent>
    </agents>
  </configuration>
</widget>
```


### Build / Packaging

Defined in `sources/package.json`:

- `yarn start`: Vite dev server for local development (`sources/`)
- `yarn build`: TypeScript + Vite build
- `yarn package`: Rollup build used to produce the plugin bundle artifacts
- `yarn build-tinymce-plugin`: Rollup build variant for the TinyMCE plugin bundle

### Current Known Gaps / Limitations (As-Is)

- **Helper message bus open**: The code to open the assistant via `openCrafterQMessageId` in `AiAssistantHelper.tsx` is commented out, so “open from message” is not active.
- **Popover content**: The hosted chat surface may load in an **iframe**; a separate native React chat for that hosted URL is not implemented in this repository.
- **Terminology in docs**: **Studio AI assistant** is the product name; **CrafterQ** refers to the optional hosted API when **`llm` is `crafterQ`**. Older doc phrasing may still mention legacy branding in wire names (`openCrafterQMessageId`, `crafterqFormFieldUpdates`) for compatibility.
- **CrafterQ path**: No CMS tool loop — use **`openAI`** (or another tools-loop **`llm`**, e.g. **`xAI`**, **`deepSeek`**) for repository tools. Tool-capable agents with **`crafterQAgentId`** may call **`ConsultCrafterQExpert`**, **`ListCrafterQAgentChats`**, and **`GetCrafterQAgentChat`** for hosted CrafterQ API access. See **[chat-and-tools-runtime.md](chat-and-tools-runtime.md#crafterq-api-tools-tools-loop)** and **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.
- **Studio AI assistant — autonomous**: Prototype only — in-memory state, no persistence across JVM restarts; not a replacement for scheduled jobs in production. See § Autonomous assistants above.

**CrafterQ prompt size**: The hosted API often limits `prompt` to on the order of **~1000 characters**. The plugin defaults to **`maxPromptChars=1000`** and **compacts** long transcripts (short system text + first author `Human:` + newest turns). To raise the cap when your environment allows a larger payload, see **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**.

**CrafterQ HTTP 500**: When logs show a small merged prompt (e.g. `utf8Bytes` ≪ `maxPromptChars`) but `POST …/v1/chats` still returns 5xx, the problem is **upstream** (agent, service), not local compaction. The plugin forwards almost all inbound Studio headers to CrafterQ—see **[stream-endpoint-design.md](stream-endpoint-design.md)**.

### AI Streaming Endpoint (Server-side)

A single **streaming** endpoint accepts `agentId`, `prompt`, optional `llm` / `llmModel` / `imageModel` / `openAiApiKey` (testing), and streams the response (SSE). See **[stream-endpoint-design.md](stream-endpoint-design.md)** and **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.

### Related Docs

- **[llm-configuration.md](../using-and-extending/llm-configuration.md)** — Supported `<llm>` ids, required configuration, env + XML; autonomous widget allowed `llm` values.
- **[chat-and-tools-runtime.md](chat-and-tools-runtime.md)** — CrafterQ bearer/auth, API tools, SSE, REST body fields, key precedence, troubleshooting.
- **[stream-endpoint-design.md](stream-endpoint-design.md)** — SSE contract; dual-LLM behavior.
- **[studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md)** — Build and install guide for Crafter Studio plugins (plugin ID, paths, ui.xml, auth, Rollup, checklist). Use when creating or debugging plugins.
- **Crafter Studio UI (reference):** [craftercms/studio-ui @ `support/4.x`](https://github.com/craftercms/studio-ui/tree/support/4.x) — Use this branch to see how Studio implements widgets, hooks (e.g. `useActiveSiteId`, `useCurrentPreviewItem`, `useActiveUser`), and config; build features and code consistently with Studio.

### Appendix: Key Files

- `sources/src/AiAssistantPopover.tsx`: Popover shell and iframe
- `sources/src/AiAssistantHelper.tsx`: Helper widget for Studio UI
- `sources/src/AiAssistantAutonomousAssistants.tsx`: Studio AI assistant — autonomous (Tools Panel widget)
- `sources/src/autonomousAssistantsConfig.ts` / `sources/src/autonomousApi.ts`: parse `autonomousAgents`; REST client for sync/status/control
- `sources/src/consts.ts`: ids and message topics
- `sources/src/craftercms_aiassistant.tsx`: optional TinyMCE (RTE) integration & open logic

