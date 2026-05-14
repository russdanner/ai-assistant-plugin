# Configuration Guide — AI Assistant for Crafter Studio

**Audience:** **Crafter Studio admins** responsible for installing and configuring the assistant and its **tools** for authors—`ui.xml` widgets, agents, credentials, form wiring, optional TinyMCE, and optional site-script overrides.

## Table of Contents

**[Basic Configuration](#cg-basic)** — `ui.xml` + forms: Helper / Tools Panel / Preview / Autonomous placement, **`plugin`** line, **`<agents>`**, secrets, form pipeline, checklist; TinyMCE last (**§8**) within **§1–§8**.

| § | Topic |
|---|--------|
| [1](#cg-1) | What you are configuring — goals; [where XML goes](#cg-1-xml) ([A](#cg-1a) Preview toolbar · [B](#cg-1b) Tools Panel · [C](#cg-1c) Form · [D](#cg-1d) Autonomous · [E](#cg-1e) Studio UI flags) |
| [2](#cg-2) | Helper / Autonomous / toolbar — **`plugin`** element |
| [3](#cg-3) | Agents (`<agents>` / `<agent>`) |
| [4](#cg-4) | Secrets and API keys |
| [5](#cg-5) | Form Engine control |
| [6](#cg-6) | Autonomous assistants (overview) |
| [7](#cg-7) | Checklist before support |
| [8](#cg-8) | TinyMCE (rich text editor) |

**[Advanced Configuration](#cg-adv)** — Site Git scripts under `config/studio/scripts/aiassistant/…`: Markdown prompts, **`tools.json`**, user tools, script image backends, script LLMs, MCP.

| § | Topic |
|---|--------|
| [9.1](#cg-9-1) | Override tool / system prompt text (`prompts/*.md`) |
| [9.2](#cg-9-2) | Enable / disable stock (built‑in) tools |
| [9.3](#cg-9-3) | Scripted tools, script LLMs, image generators |
| [9.4](#cg-9-4) | MCP servers (optional remote tools) |

**[Related Documentation](#cg-related)** — Cross-links to **spec.md**, LLM guide, studio plugins guide, scripted tools, runtime doc, advanced overrides, and **Screenshots**.

**[Where to Go Next](#cg-10)** — Links to [llm-configuration.md](llm-configuration.md), [spec.md](../internals/spec.md), and the rest of this doc set.

**[Screenshots](#cg-screenshots)** — Project Tools entry and **AI Assistant Configuration** dialog (all tabs).

---

<a id="cg-screenshots"></a>

## Screenshots — Project Tools and AI Assistant Configuration

These screenshots show **Project Tools** (where you install the plugin and open **AI Assistant**) and the tabbed **AI Assistant Configuration** dialog. Paths below are relative to this file (`docs/using-and-extending/`).

### Project Tools (Sidebar)

![Project Tools sidebar: Plugin Management selected; AI Assistant entry at the bottom of the list](../images/ai-assistant-studio/project-tools-sidebar.png)

*Use **Plugin Management → Search & install** for the marketplace flow; open **AI Assistant** for configuration after install.*

### AI Assistant Configuration — UI Tab

![AI Assistant Configuration modal with the UI tab active](../images/ai-assistant-studio/ai-assistant-configuration-ui-tab.png)

*Toolbar/sidebar toggles, Experience Builder image augmentation scope, and bulk add/remove of the form-engine AI Assistant field.*

### Agents Tab

![AI Assistant Configuration modal with the Agents tab active](../images/ai-assistant-studio/ai-assistant-configuration-agents-tab.png)

*Chat assistants vs autonomous agents; reload, example catalog, and save to site.*

### Edit Agent

![Edit agent dialog for a single catalog entry](../images/ai-assistant-studio/ai-assistant-edit-agent-dialog.png)

*Provider, model, image generator, CMS tools checklist, and optional quick-prompt chips.*

### Tools and MCP Tab

![AI Assistant Configuration modal with the Tools and MCP tab active](../images/ai-assistant-studio/ai-assistant-configuration-tools-tab.png)

*Built-in tool visibility, MCP client toggle, and user-tools registry (table + **Open in editor**).*

<a id="cg-screenshots-mcp-github"></a>

#### GitHub MCP (example in Project Tools)

![Tools and MCP tab with MCP enabled and a GitHub Copilot Streamable HTTP server](../images/ai-assistant-studio/ai-assistant-configuration-tools-mcp-github.png)

*Example **`mcpServers`** row: server id **`Github`**, URL **`https://api.githubcopilot.com/mcp/`**, **`Authorization`** header (use a real token in production; never commit tokens to the repo), **`readTimeoutMs`** **120000**. Same settings persist to **`config/studio/scripts/aiassistant/config/tools.json`** when you click **Save tools & MCP**. Match URL and headers to GitHub’s current **[Remote GitHub MCP Server](https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md)** documentation.*

### Scripts Tab

![AI Assistant Configuration modal with the Scripts tab active](../images/ai-assistant-studio/ai-assistant-configuration-scripts-tab.png)

*Script image generators and script LLM backends under `scripts/aiassistant/…`.*

---

<a id="cg-basic"></a>

## Basic Configuration

Typical authoring setup is **`config/studio/ui.xml`** plus content-type form definitions: register the Helper (and optional Autonomous), use one consistent **`plugin`** line, define **`<agents>`**, supply keys, run the checklist (**§1–§7**), then optionally wire **TinyMCE** (**§8**). **§1–§8** below are the subsections in reading order.

---

<a id="cg-1"></a>

### 1. What You Are Configuring

| Goal | Typical touchpoints |
|------|---------------------|
| Authors use AI **in Experience Builder** while authoring in **preview** | `ui.xml` → **`craftercms.components.aiassistant.Helper`** registers the agent in the **Experience Builder** workflow (preview toolbar control opens the assistant in the XB tools panel by default) + `<agents>` — optional visibility for the **toolbar icon** via **`studio-ui.json`** (**§1e**) |
| Authors use AI on a **content type form** | Content type **form definition** → **AI Assistant** control + `config/studio/ui.xml` **`<agents>`** (merged by stable agent id) |
| **Scheduled** server-side runs (experimental) | `ui.xml` → **`craftercms.components.aiassistant.AutonomousAssistants`** + `<autonomousAgents>` or **`agents.json`** `mode: autonomous` — see [spec.md — Autonomous assistants widget](../internals/spec.md#autonomous-assistants-widget-tools-panel); optional **sidebar show** via **`studio-ui.json`** **`showAutonomousAiAssistantsInSidebar: true`** (**§1e**); default off. |
| Authors use AI from the **rich text editor** (optional) | `config/studio/ui.xml` → **TinyMCE** widget → `tinymceOptions` (external plugin URL + `craftercms_aiassistant` JSON) — **§8** (last) and [tinymce-integration.md](tinymce-integration.md) |

Commit **`config/studio/ui.xml`** (and any content-type changes) to the site sandbox so Studio and other authors load the same configuration.

<a id="cg-1-xml"></a>

### Where to Put XML (File + Parent Elements)

| What | File on disk (site Git sandbox) | Where inside the file |
|------|-----------------------------------|------------------------|
| **Helper** (Experience Builder toolbar, optional Tools Panel) | **`config/studio/ui.xml`** | **A** (Preview toolbar) and/or **B** (Tools Panel) — the `<widget id="craftercms.components.aiassistant.Helper">` block is a **child of an existing `widgets` list**, not a loose sibling of `ToolsPanel`. |
| **Form assistant** | **`config/studio/content-types/<your-type>/form-definition.xml`** | New **field** inside the right **`<section>`** / **`<fields>`** — prefer adding the **Studio AI Assistant** control from the Content Types UI after install (see **C**). |
| **Autonomous** (optional) | **`config/studio/ui.xml`** | **D** — under **`craftercms.components.ToolsPanel`** → **`configuration`** → **`widgets`** (same list as Helper when both are used). Optional **hide** without removing the widget: **`studio-ui.json`** (**§1e**). |
| **TinyMCE** (optional) | **`config/studio/ui.xml`** | Under **`craftercms.components.TinyMCE`** → **`configuration`** → **`setups`** → **`setup`** → **`tinymceOptions`** (JSON). See **§8** (last in basic sequence). |

---

<a id="cg-1a"></a>

#### A) Experience Builder — Preview Toolbar

**Locate in `config/studio/ui.xml`:** the widget **`craftercms.components.PreviewToolbar`** → **`configuration`** → **`rightSection`** or **`middleSection`** → **`widgets`** (descriptor-driven install uses **`rightSection`**; use **`middleSection`** if you want the icon next to the URL bar).

**Add** the block below as **another** `<widget>` sibling next to the other toolbar widgets (indentation may differ in your file):

```xml
        <!-- config/studio/ui.xml — PreviewToolbar / configuration / rightSection or middleSection / widgets -->
        <widget id="craftercms.components.aiassistant.Helper">
          <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
          <configuration ui="IconButton">
            <agents>
              <agent>
                <label>Authoring Assistant</label>
                <llm>openAI</llm>
                <llmModel>gpt-4o-mini</llmModel>
                <imageModel>gpt-image-1-mini</imageModel>
              </agent>
            </agents>
          </configuration>
        </widget>
```

Longer copy-paste blocks (Tools Panel + Preview + Autonomous together): [examples/studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml).

---

<a id="cg-1b"></a>

#### B) Studio Tools Panel (Left Rail)

**Locate:** **`craftercms.components.ToolsPanel`** → **`configuration`** → **`widgets`**.

**Add** the Helper (and optionally **Autonomous**) as `<widget>` children **inside that `widgets` element** — not after `</configuration>` at the wrong level.

```xml
        <!-- config/studio/ui.xml — ToolsPanel / configuration / widgets -->
        <widget id="craftercms.components.aiassistant.Helper">
          <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
          <configuration>
            <agents>
              <agent>
                <label>Authoring Assistant</label>
                <llm>openAI</llm>
                <llmModel>gpt-4o-mini</llmModel>
              </agent>
            </agents>
          </configuration>
        </widget>
```

---

<a id="cg-1c"></a>

#### C) Content Type Form (AI Assistant Field)

**Locate:** `config/studio/content-types/<content-type-id>/form-definition.xml` — inside the **`<fields>`** collection for the section where you want the accordion.

**Recommended:** In Studio, **Project Tools → Content Types →** open the type → **Add field** → choose **Studio AI Assistant** from the palette (the plugin registers that control in **`config/studio/administration/site-config-tools.xml`** on install). That writes the correct control wiring; hand-editing is easy to get wrong.

Agent rows still come from **`config/studio/ui.xml`** **`<agents>`** (same stable ids as the Helper). Do not define agents only in the form field.

---

<a id="cg-1d"></a>

#### D) Autonomous Assistants (Tools Panel Only)

**Locate:** same parent as **B** — **`craftercms.components.ToolsPanel`** → **`configuration`** → **`widgets`**.

**Add** a **second** widget sibling (after or before Helper). Minimal shape:

```xml
        <!-- config/studio/ui.xml — ToolsPanel / configuration / widgets -->
        <widget id="craftercms.components.aiassistant.AutonomousAssistants">
          <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
          <configuration>
            <title>Autonomous Agents</title>
            <autonomousAgents>
              <agent>
                <name>Example agent</name>
                <schedule>0 * * * * ?</schedule>
                <prompt>You are an autonomous assistant. Reply with JSON only as instructed by the server.</prompt>
                <scope>project</scope>
                <llm>openAI</llm>
                <llmModel>gpt-4o-mini</llmModel>
              </agent>
            </autonomousAgents>
          </configuration>
        </widget>
```

Full sample (including optional SVG icon): [examples/studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml).

---

<a id="cg-1e"></a>

#### E) Studio UI Flags & Bulk Tools (`studio-ui.json` + Project Tools)

**File:** **`config/studio/scripts/aiassistant/config/studio-ui.json`** (module **`studio`**). Authors usually create or edit it from **Project Tools → AI Assistant** → **UI** tab (`craftercms.components.aiassistant.ProjectToolsConfiguration`); you can also commit the JSON by hand in the site sandbox. The Project Tools save uses **`write_configuration`** with **`content`** set to **`JSON.stringify(...)`** — the Studio v2 API expects a **string** body for this endpoint, not a raw JSON object.

**Why it exists:** Lets admins **hide** specific surfaces or **scope** optional client-side behavior **without** deleting the merged **`ui.xml`** widget rows. The bundle reads this file via Studio **`get_configuration`** (sync XHR, per-site cache; invalidated when the Project Tools panel saves).

| Field | Meaning |
|-------|--------|
| **`showAiAssistantsInTopNavigation`** | When **`false`**, the Helper **`ui="IconButton"`** preview **toolbar** control does not render. **Tools Panel** Helper entries are **not** affected. Default **`true`** or omit. |
| **`showAutonomousAiAssistantsInSidebar`** | When **`true`**, the **`AutonomousAssistants`** sidebar widget renders (experimental). Default **`false`** or omit. |
| **`contentTypeImageAugmentationScope`** | **`all`** (default) — client patch augments **every** content type for Experience Builder **image-picker** drag targets when the AI **image-from-URL** datasource is referenced. **`none`** — no augmentation. **`selected`** — only ids in **`contentTypeIdsForImageAugmentation`**. |
| **`contentTypeIdsForImageAugmentation`** | String array of normalized ids (e.g. **`"/page/article"`**, **`"/component/hero"`**) used when scope is **`selected`**. |

**Bulk form control:** The same Project Tools screen can insert or remove a **marked** AI Assistant field block in **`config/studio/content-types/.../form-definition.xml`** (first **`sections` → `section` → `fields`** insertion point). Use **Add to all / Remove from all** or pick types then **Add to selected / Remove from selected**. Review diffs in Git before publishing; backup or branch first.

**REST (integrators):** Plugin script **`GET …/aiassistant/content-types/list?siteId=`** returns the Studio content-type catalog (via `StudioToolOperations.listStudioContentTypes`) for the multi-select UI.

**See also:** [Screenshots — Project Tools and AI Assistant Configuration](#cg-screenshots) · [spec.md — Studio UI flags](../internals/spec.md#studio-ui-flags-studio-uijson) · [helper-widget.md](helper-widget.md) · [autonomous-assistants-widget.md](autonomous-assistants-widget.md).

**Upgrades:** If your site still shows **three** separate AI Assistant rows under Project Tools (from an older plugin descriptor), remove the legacy **`<tool>`** entries in **`config/studio/administration/site-config-tools.xml`** (or via Studio’s project tools UI) so only **AI Assistant** (`ai-assistant-config`) remains—each legacy widget id still loads the same tabbed panel with the correct default tab until you do.

---

<a id="cg-2"></a>

### 2. Helper, Autonomous, and Toolbar Widgets: `plugin` Element

Studio resolves the **JavaScript bundle** from the **`plugin`** child on each widget that mounts this plugin (Helper, AutonomousAssistants, and any **Experience Builder preview toolbar** entry that uses the same pattern). Use the same values everywhere so Studio loads **`index.js`** from the installed plugin.

| Attribute / concept | Use this value |
|------------------------|----------------|
| **`id`** (plugin id) | `org.craftercms.aiassistant.studio` — must match **`craftercms-plugin.yaml`** and the plugin’s internal **`PluginDescriptor.id`**. |
| **`type`** | `aiassistant` |
| **`name`** | `components` |
| **`file`** | `index.js` |

Example **`plugin`** line (use **inside** every Helper / Autonomous / toolbar widget — see **§1** for parent paths):

```xml
<plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
```

Full **Experience Builder + Tools Panel + Autonomous** examples: [examples/studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml).

If the id or `file` path is wrong, Studio shows **component not found** or **404** on `index.js`. Install path, classpath, and toolbar wiring are covered in [studio-plugins-guide.md](studio-plugins-guide.md); widget XML contract in [spec.md § Helper widget](../internals/spec.md#helper-widget-studio-ui).

---

<a id="cg-3"></a>

### 3. Agents (`<agents>` / `<agent>`)

Each **agent** is one row in the Helper menu (or one accordion row on the form assistant). Per agent you normally set:

- **`label`** — Display name.
- **`llm`** — Backend for this agent’s chat. **Set `<llm>` explicitly** — for authoring with **GetContent** / **WriteContent** / **GenerateImage**, use **`openAI`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`gemini`/`genesis`**, **`claude`**, or **`script:…`**. **`crafterQ`** is **hosted chat only** (no CMS tool loop on that adapter). If **`<llm>`** is omitted and the POST omits **`llm`**, the stream/chat request **400**s unless **`siteId`** + **`agentId`** allow the server to merge **`llm`** from **`/ui.xml`** — see [llm-configuration.md § Omitted `<llm>` and POST body](llm-configuration.md#omitted-llm-and-post-body). Allowed values: [llm-configuration.md § Summary table](llm-configuration.md#summary-table).
- **`llmModel`** — Provider chat model id (optional; when omitted, some providers use a server default — see **[llm-configuration.md](llm-configuration.md)** and JVM defaults in **[studio-aiassistant-jvm-parameters.md](studio-aiassistant-jvm-parameters.md)** only if you rely on non-XML defaults).
- **`imageModel`** — OpenAI **Images** model id for **`GenerateImage`** (no server fallback if blank). Use **`gpt-image-1`** or **`gpt-image-1-mini`**. See [llm-configuration.md](llm-configuration.md).
- **`crafterQAgentId`** — Hosted SaaS **agent UUID**; sent as `agentId` on stream/chat. **Required** only when **`llm` is `crafterQ`**. On **tool-capable** `llm` values, set it **only** if you want optional **hosted SaaS API tools** on that agent — see [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md#crafterq-api-tools-tools-loop). Otherwise omit or leave empty per [spec.md](../internals/spec.md).
- **`prompts`** — Optional quick chips (`<prompt>` plain or structured with `<userText>` / `<additionalContext>` / `<omitTools>`).

Optional toggles (`openAsPopup`, `enableTools`, expert skills, translation concurrency, etc.) are documented field‑by‑field under [spec.md — Agent configuration (ui.xml)](../internals/spec.md#agent-configuration-uixml).

**Example — multiple `<agent>` rows** (replace or extend the **`<agents>`** block **inside** the Helper `<configuration>` from **§1**; each `<agent>` is one picker row):

```xml
            <agents>
              <agent>
                <label>OpenAI authoring</label>
                <llm>openAI</llm>
                <llmModel>gpt-4o-mini</llmModel>
                <imageModel>gpt-image-1-mini</imageModel>
              </agent>
              <agent>
                <label>Claude</label>
                <llm>claude</llm>
                <llmModel>claude-3-5-sonnet-20241022</llmModel>
              </agent>
            </agents>
```

---

<a id="cg-4"></a>

### 4. Secrets and API Keys (Recommended Order)

1. **Studio host environment variables** — Preferred for production API keys and base URLs. Provider names and variables are listed in [llm-configuration.md](llm-configuration.md).
2. **Per‑agent `ui.xml` / widget JSON** — e.g. `<openAiApiKey>`: **testing only**; discouraged in Git‑tracked sites. Precedence vs host env is described in [chat-and-tools-runtime.md § OpenAI API key](../internals/chat-and-tools-runtime.md#openai-api-key-server-side).
3. **JVM system properties** — Advanced tuning and key fallbacks only; see **[studio-aiassistant-jvm-parameters.md](studio-aiassistant-jvm-parameters.md)** (not alternatives to `ui.xml` fields for typical admin configuration).

**Optional — hosted SaaS HTTP** — If authors use hosted SaaS in the widget (`X-CrafterQ-Chat-User`) and/or you configure **`crafterQBearerTokenEnv`** / **`crafterQBearerToken`** for server‑to‑SaaS `Authorization`, see [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md) when debugging 401s on list/get chat tools.

**Example — read the bearer JWT from a Studio host env var** (set `CRAFTQ_ADMIN_JWT` in the Studio process environment; do not commit secrets in `ui.xml`):

```xml
              <agent>
                <label>Hosted + API tools</label>
                <llm>openAI</llm>
                <llmModel>gpt-4o-mini</llmModel>
                <crafterQAgentId>019c7237-478b-7f98-9a5c-87144c3fb010</crafterQAgentId>
                <crafterQBearerTokenEnv>CRAFTQ_ADMIN_JWT</crafterQBearerTokenEnv>
              </agent>
```

**Testing-only literal** (discouraged in Git): use **`<crafterQBearerToken>`** instead of **`<crafterQBearerTokenEnv>`** — see [llm-configuration.md](llm-configuration.md) and [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md).

---

<a id="cg-5"></a>

### 5. Form Engine Control

The AI Assistant **form control** reads agent definitions from the same **`/ui.xml`** agent collection as the Helper (by stable id). Changing only the Helper widget JSON in Studio UI without updating **`/config/studio/ui.xml`** can leave the form panel out of sync—see the form pipeline and locked panel behavior in [studio-plugins-guide.md](studio-plugins-guide.md) (**Form assistant panel**) and [spec.md](../internals/spec.md) (content-type form assistant).

---

<a id="cg-6"></a>

### 6. Autonomous Assistants (Optional)

Separate widget, separate XML block **`autonomousAgents`**, supervisor and in‑memory state. Not a substitute for interactive chat configuration: you still define **`llm`**, **`llmModel`**, schedules, scopes, and human‑task behavior per [spec.md — Autonomous assistants widget](../internals/spec.md#autonomous-assistants-widget-tools-panel).

---

<a id="cg-7"></a>

### 7. Checklist Before Opening a Support Thread

- [ ] Plugin installed for the **site** (Marketplace or `copy-plugin` / `install-plugin.sh`); **`org.craftercms.aiassistant.studio`** appears in Plugin Management.
- [ ] **`ui.xml`** committed; Studio **Sync** performed if you rely on git‑backed sandbox.
- [ ] Helper / Autonomous / toolbar widgets are **nested under the correct parents** in **`config/studio/ui.xml`** (**§1** A / B / D), and the **`plugin`** line matches **§2**.
- [ ] For **OpenAI‑wire / Claude / …**: host **env** API keys set (per [llm-configuration.md](llm-configuration.md)), or you accept testing‑only keys in `ui.xml`.
- [ ] For **GenerateImage**: **`imageModel`** set on the agent (or body) when that tool is used.
- [ ] If you use **`llm` `crafterQ`**: valid **`crafterQAgentId`** and (if needed) identity / bearer as in [llm-configuration.md](llm-configuration.md).

---

<a id="cg-8"></a>

### 8. TinyMCE (Rich Text Editor)

**File:** **`config/studio/ui.xml`**

**Locate:** widget **`craftercms.components.TinyMCE`** → **`configuration`** → **`setups`** → **`setup`** (the setup your site uses) → **`tinymceOptions`**. That node holds JSON (often as text); merge the plugin URL and toolbar ids there.

Path in the tree (names may differ):

```text
config/studio/ui.xml
  └── widget[@id='craftercms.components.TinyMCE']
        └── configuration
              └── setups
                    └── setup
                          └── tinymceOptions   ← merge here (JSON)
```

**Example JSON** (replace **`YOUR_SITE_ID`**; use **`&amp;`** for `&` when this JSON is inlined inside an XML attribute):

```json
{
  "toolbar1": "... | aiAssistantOpen crafterqshortcuts crafterq",
  "external_plugins": {
    "craftercms_aiassistant": "/studio/1/plugin/file?siteId=YOUR_SITE_ID&pluginId=org.craftercms.aiassistant.studio&type=aiassistant&name=tinymce&file=craftercms_aiassistant.js"
  },
  "craftercms_aiassistant": {}
}
```

Full toolbar list and keys: [tinymce-integration.md](tinymce-integration.md).

---

<a id="cg-adv"></a><a id="cg-9"></a>

## Advanced Configuration (Prompts, Tools, Scripts, MCP)

All paths in this section are under the **site** Git sandbox (`config/studio/scripts/aiassistant/…`). Commit changes and refresh Studio configuration as you do for other site scripts.

<a id="cg-9-1"></a>

### 9.1 Override Tool / System Prompt Text

**Put Markdown here:**

```text
config/studio/scripts/aiassistant/prompts/<KEY>.md
```

**`<KEY>`** is the exact **prompt key** passed to `ToolPrompts.p('KEY', …)` (listed in `ToolPromptsOverrideCatalog.KEYS`). Files use a **purpose prefix**: **`GENERAL_`** (OpenAI policy / cross-cutting), **`CMS_CONTENT_`** (repository content, translate, preview, publish), **`CMS_DEVELOPMENT_`** (templates, content types, analyze), **`CRAFTERQ_`** (CrafterQ SME and hosted-chat prompts). The file on disk is **`<KEY>.md`**.

| Example `<KEY>.md` |
|--------------------|
| `GENERAL_OPENAI_AUTHORING_INSTRUCTIONS.md` |
| `CMS_CONTENT_DESC_GET_CONTENT.md` |
| `GENERAL_OPENAI_CHAT_ONLY_SYSTEM.md` |

| Rule | Detail |
|------|--------|
| **Replace vs merge** | The file **replaces the entire** built‑in string for that key. There is no partial patch. |
| **Blank file** | Treated like **missing** — the shipped default stays. |
| **Order** | Site file is read **before** classpath defaults when a chat request runs (`ToolPromptsLoader`). |

**Finding keys:** Search **`ToolPrompts.groovy`** in this plugin repo for `p('SOME_KEY',` — the first argument is the filename stem (`SOME_KEY.md`). The canonical list is **`ToolPromptsOverrideCatalog.groovy`** (`KEYS`).

**Upgrades:** If your site still has overrides under the **old** names (e.g. `OPENAI_AUTHORING_INSTRUCTIONS.md`, `DESC_GET_CONTENT.md`), **rename** those files to the new prefixed keys (e.g. `GENERAL_OPENAI_AUTHORING_INSTRUCTIONS.md`, `CMS_CONTENT_DESC_GET_CONTENT.md`) or Studio will keep using the built‑in defaults.

**Example — tighten the main OpenAI authoring system prompt** (file on disk: `config/studio/scripts/aiassistant/prompts/GENERAL_OPENAI_AUTHORING_INSTRUCTIONS.md`):

```markdown
## OUR STUDIO POLICY (override)

You are assisting CrafterCMS authors. Use CMS tools when they are on the wire. Prefer small, verifiable edits.
(…your full replacement text; this file replaces the entire shipped default for this key…)
```

---

<a id="cg-9-2"></a>

### 9.2 Enable / Disable Stock (Built‑In) Tools

You can maintain **`tools.json`** in Git or use **Project Tools → AI Assistant → Tools and MCP** in Studio (form for built-ins + MCP; same tab as **`user-tools/registry.json`** and the Groovy tool list).

**Put JSON here:**

```text
config/studio/scripts/aiassistant/config/tools.json
```

| Field | Effect |
|-------|--------|
| **`disabledBuiltInTools`** | JSON array of **tool names to hide** (compared case‑insensitively). Example: `["GenerateImage", "FetchHttpUrl"]` removes those tools from the catalog. |
| **`enabledBuiltInTools`** | If this array is **non‑empty**, it is a **whitelist** of **built‑in CMS** tool names to **keep**; every other built‑in is removed **except** **`InvokeSiteUserTool`** and any **`mcp_*`** tools (unless those appear in **`disabledBuiltInTools`** / **`disabledMcpTools`**). Names must match the registered tool string **exactly** (case‑sensitive). If **omitted** or **empty**, all built‑ins are available minus **`disabledBuiltInTools`**. |

**Registered built-in wire names** — use these strings verbatim in **`disabledBuiltInTools`**, **`enabledBuiltInTools`**, and **`omitTools`**. Canonical source: **`AiOrchestrationTools.groovy`**, `FunctionToolCallback.builder('…')`. When MCP is enabled, the server also registers dynamic **`mcp_<serverId>_<toolName>`** tools (sanitized); those are not listed here.

| Wire name (PascalCase) |
|------------------------|
| `ConsultCrafterQExpert` |
| `FetchHttpUrl` |
| `GenerateImage` |
| `GenerateTextNoTools` |
| `GetContent` |
| `GetContentSubgraph` |
| `GetContentTypeFormDefinition` |
| `GetContentVersionHistory` |
| `GetCrafterQAgentChat` |
| `GetCrafterizingPlaybook` |
| `GetPreviewHtml` |
| `InvokeSiteUserTool` |
| `ListContentTranslationScope` |
| `ListCrafterQAgentChats` |
| `ListPagesAndComponents` |
| `ListStudioContentTypes` |
| `QueryExpertGuidance` |
| `TransformContentSubgraph` |
| `TranslateContentBatch` |
| `TranslateContentItem` |
| `WriteContent` |

| Wire name (snake_case) |
|------------------------|
| `analyze_template` |
| `publish_content` |
| `revert_change` |
| `update_content` |
| `update_content_type` |
| `update_template` |

Per-request **`omitTools`** / agent **`<enableTools>false</enableTools>`** still apply on top of this file.

**Example — hide image + outbound fetch, keep the rest:**

```json
{
  "disabledBuiltInTools": ["GenerateImage", "FetchHttpUrl"]
}
```

**Example — whitelist only read + list tools** (exact names; everything else built‑in is removed except **`InvokeSiteUserTool`** / **`mcp_*`** unless also disabled):

```json
{
  "enabledBuiltInTools": [
    "GetContent",
    "ListContentTranslationScope",
    "ListStudioContentTypes",
    "GetContentTypeFormDefinition",
    "ListPagesAndComponents",
    "GetPreviewHtml"
  ]
}
```

---

<a id="cg-9-3"></a>

### 9.3 Scripted Tools, Script LLMs, and Image Generators

| What | Where you put it | How the model uses it |
|------|------------------|------------------------|
| **Site user tools** (Groovy) | **`config/studio/scripts/aiassistant/user-tools/`** + **`registry.json`** | Model calls **`InvokeSiteUserTool`** with **`toolId`** matching an entry in **`registry.json`**; script name on disk must match **`script`** / **`file`**. |
| **Script LLM** | **`config/studio/scripts/aiassistant/llm/{id}/runtime.groovy`** (or `llm.groovy`) | Agent **`<llm>script:{id}</llm>`** — see [llm-configuration.md](llm-configuration.md) and [studio-plugins-guide.md](studio-plugins-guide.md). |
| **Script image backend** | **`config/studio/scripts/aiassistant/imagegen/{id}/generate.groovy`** | Agent or POST **`imageGenerator`** = **`script:{id}`**. **`none`** / **`off`** / **`disabled`** removes **GenerateImage**. Blank + keys + **`imageModel`** uses the default **built-in Images HTTP** wire (same **`/v1/images/generations`** shape as the chat tools-loop client stack). |

Copy‑paste starter: **`docs/examples/aiassistant-user-tools/`**; **Gemini “Nano Banana 2” image script:** **`docs/examples/aiassistant-imagegen/nano-banana-2/generate.groovy`**. **Interfaces, bindings, return maps, and setup checklists** (integrators): **[scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md)**. When **`GenerateImage`** uses the default HTTP wire vs **`script:{id}`**: [image-generation.md](image-generation.md). Build / classpath / security: [studio-plugins-guide.md](studio-plugins-guide.md) (**user-tools**, **imagegen**, **tools.json**).

**Example — `registry.json` + Groovy file** (same folder: `config/studio/scripts/aiassistant/user-tools/`):

`registry.json`:

```json
{
  "tools": [
    {
      "id": "hello",
      "script": "hello.groovy",
      "description": "Returns a greeting; optional args.name"
    }
  ]
}
```

`hello.groovy` (same directory):

```groovy
[ok: true, message: "Hello ${(args?.name ?: 'author') as String} from ${siteId}"]
```

**Example — script image backend on an agent** (in **`config/studio/ui.xml`**, inside the same `<agent>` as **`imageModel`**):

```xml
        <imageModel>gpt-image-1-mini</imageModel>
        <imageGenerator>script:mygen</imageGenerator>
```

Implement **`config/studio/scripts/aiassistant/imagegen/mygen/generate.groovy`** per **[scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md)** (closure contract, **`context`** map) and [image-generation.md](image-generation.md) (registration rules).

**Example — script LLM agent** (still in **`ui.xml`**):

```xml
        <llm>script:mybackend</llm>
```

Implement **`config/studio/scripts/aiassistant/llm/mybackend/runtime.groovy`** per [llm-configuration.md](llm-configuration.md).

---

<a id="cg-9-4"></a>

### 9.4 MCP Servers (Optional Remote Tools)

Same file: **`config/studio/scripts/aiassistant/config/tools.json`**.

**Studio (Project Tools):** When **MCP** is enabled and **Save tools & MCP** runs with at least one complete server row, Studio calls each server’s **`tools/list`**, opens a checklist so you can **enable or disable** individual **`mcp_*`** wire tools when tools are returned; if none are returned, the same dialog still lists **server status** (errors or empty catalogs) and you can **Save** to persist the rest of **`tools.json`** unchanged. Use **List MCP tools** anytime for a **read-only** preview without saving.

| Field | Purpose |
|-------|---------|
| **`mcpEnabled`** | Must be JSON **`true`** or **`mcpServers`** is **ignored** (default off). |
| **`mcpServers`** | Array of `{ "id": "…", "url": "https://host/…/mcp", "headers": { }, "readTimeoutMs": 120000 }` — **Streamable HTTP** MCP endpoint (`POST` on **`url`**). |
| **`disabledMcpTools`** | Optional array of **wire** tool names to hide, e.g. **`mcp_docs_search`**. You can also list MCP wire names under **`disabledBuiltInTools`**. |

Each MCP tool becomes a function named roughly **`mcp_<serverId>_<toolName>`** (sanitized, length‑capped). SSRF rules match **`FetchHttpUrl`**. In **`mcpServers[].headers`**, each value may use **`${env:VARIABLE_NAME}`**; Studio expands it from **`System.getenv`** on the Studio JVM (unset variable → empty string) before calling the MCP server.

**Example:**

```json
{
  "mcpEnabled": true,
  "mcpServers": [
    {
      "id": "docs",
      "url": "https://mcp.example.com/mcp",
      "headers": { "Authorization": "Bearer ${env:GITHUB_MCP_TOKEN}" },
      "readTimeoutMs": 120000
    }
  ],
  "disabledMcpTools": ["mcp_docs_search"]
}
```

For a hosted **Streamable HTTP** reference (base URL, `/readonly` paths, optional `X-MCP-*` headers, and JSON snippets), see GitHub’s **[Remote GitHub MCP Server](https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md)** — map each recipe’s URL and headers into an `mcpServers[]` row (`id`, `url`, `headers`, optional `readTimeoutMs`) in Project Tools or in Git. **UI example (GitHub Copilot MCP endpoint):** [Screenshots — GitHub MCP](#cg-screenshots-mcp-github).

Full behavior, lifecycle, and limits: [chat-and-tools-runtime.md § MCP client tools](../internals/chat-and-tools-runtime.md#mcp-client-tools-streamable-http). JVM caps / host allowlists: [studio-aiassistant-jvm-parameters.md](studio-aiassistant-jvm-parameters.md).

---

<a id="cg-10"></a>

## 10. Where to Go Next

| Topic | Document |
|-------|-----------|
| Full `<llm>` matrix, env + XML, tool availability | [llm-configuration.md](llm-configuration.md) |
| **`InvokeSiteUserTool`** + **`script:{id}`** image Groovy (integrators; bindings, examples) | [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md) |
| Build, install, classpath, `user-tools/`, script LLM | [studio-plugins-guide.md](studio-plugins-guide.md) |
| Macros, `omitTools`, ICE vs form engine, REST paths, human tasks | [spec.md](../internals/spec.md) |
| SSE / stream endpoint design | [stream-endpoint-design.md](../internals/stream-endpoint-design.md) |
| Doc map (internals vs using) | [README.md](../README.md) |

---

<a id="cg-related"></a>

### Related Documentation

**[spec.md](../internals/spec.md)** — requirements and mechanics for surfaces, `ui.xml`, form vs preview, macros, autonomous REST. **[llm-configuration.md](llm-configuration.md)** — **`<llm>`** wire ids, env + XML, tool availability by provider. **[studio-plugins-guide.md](studio-plugins-guide.md)** — install, build output paths, **`user-tools/`**, script LLM layout. **[scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md)** — Groovy **`InvokeSiteUserTool`** / **`script:{id}`** image backends (this guide **§9.3**). **[chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md)** — hosted SaaS HTTP, bearer, chat audit tools. **[Advanced configuration](#cg-adv)** — site overrides (prompts, built-in tool policy, scripted tools, image backends, MCP). **[Screenshots — Project Tools and AI Assistant Configuration](#cg-screenshots)**.
