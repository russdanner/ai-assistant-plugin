# Crafter Studio Plugin Build & Install — Developer's Guide

Part of the **official build specification** for this plugin, alongside **[`docs/internals/spec.md`](../internals/spec.md)** (product requirements and mechanics). Invariants here (paths, `yarn package`, descriptor/plugin id rules, what must not be hand-edited under `authoring/`) are **required**; when the build or packaging story changes, update **this file** and, if author-visible or install semantics change, **`spec.md`** as well.

**Scope:** Build, install, and site-level extensions such as **`user-tools/`** and script LLMs. For behavior contracts and autonomous REST, see [spec.md](../internals/spec.md). Doc index: [README.md](../README.md).

This guide captures what you need to build, package, and install Crafter Studio (site) plugins so they load correctly in Studio and in the preview toolbar. Use it as a reference when creating or debugging plugins. For **Crafter Studio 4.x**-specific UI integration (preview **`reloadRequest`**, host/guest buses) and **in-process services** (v1 vs v2 content APIs, **`DeploymentService.deploy`**, **`revertContentItem`**, security context on async threads), see **§7** and **§8**.

---

## Reference: Crafter Studio UI (Support/4.x)

When implementing features or matching Studio behavior, use the **Crafter Studio UI** source as the canonical reference:

- **Repo (branch):** [craftercms/studio-ui — `support/4.x`](https://github.com/craftercms/studio-ui/tree/support/4.x)

Use it to:

- **See how Studio does things** — Widget patterns, config parsing, Redux state shape.
- **Reuse hooks and APIs** — e.g. `useActiveSiteId`, `useCurrentPreviewItem`, `useActiveUser` (same hooks Studio and other plugins use).
- **Keep code consistent** — Follow the same patterns for UI components, config, and plugin integration.

Your plugin depends on `@craftercms/studio-ui`; inspecting this branch helps when adding features or debugging integration.

---

## 1. Plugin Identity and Path Resolution

### Plugin ID

- The **plugin ID** is set in the root descriptor: `craftercms-plugin.yaml` → `plugin.id` (e.g. `org.craftercms.aiassistant.studio`).
- **Every place** that references the plugin for loading JS must use this **exact** ID:
  - `config/studio/ui.xml` → `<plugin id="...">` for Helper and autonomous (scheduled) widget entries
  - TinyMCE `external_plugins` URL → `pluginId=...` query param
  - Runtime `importPlugin(site, type, name, file, pluginId)` → `pluginId` argument

If the ID in `ui.xml` (or in the TinyMCE URL) does not match the ID used at install time, Studio will look for files under a different path and return **404** for the plugin script.

### How Studio Resolves Plugin File Paths

When Studio serves a plugin file, it uses:

- **Plugin ID** → converted to a path segment (e.g. `org.craftercms.aiassistant.studio` → `org/craftercms/aiassistant/studio` under the plugins root).
- **Type** and **name** → additional path segments (e.g. `type=aiassistant`, `name=components` → `aiassistant/components`).
- **File** → filename (e.g. `index.js`).

So the installed path for a site is:

```text
{siteRepo}/config/studio/static-assets/plugins/<pluginId-path>/<type>/<name>/<file>
```

Example for plugin id `org.craftercms.aiassistant.studio`, type `crafterq`, name `components`, file `index.js`:

```text
config/studio/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/components/index.js
```

The **plugin ID path** is the plugin id with dots replaced by slashes. The installer copies from the plugin repo’s `authoring/static-assets/` tree into `config/studio/static-assets/plugins/<pluginId-path>/`. Your build must output into a folder structure that, when copied, matches what Studio expects for that plugin id + type + name.

### Build Output Must Match the Installed Path

In your plugin repo, the **marketplace/copy** (or **copy-plugin**) install step copies:

- From: `authoring/static-assets/*`
- To: `{siteRepo}/config/studio/static-assets/plugins/<pluginId-path>/*`

So you must build your JS (and other assets) into a directory that mirrors the target path under `authoring/static-assets/`. For example, if the plugin id is `org.craftercms.aiassistant.studio` and you use `type=aiassistant` and `name=components`:

- Build the main bundle to: `authoring/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/components/index.js`
- And e.g. TinyMCE script to: `authoring/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/tinymce/craftercms_aiassistant.js`

Then, after install, Studio will find them at the same relative path under `config/studio/static-assets/plugins/org/craftercms/aiassistant/studio/`.

**Rule:** The `plugin.id` in the descriptor, the paths in `ui.xml` and TinyMCE config, and the build output paths must all be consistent. Changing the plugin id (e.g. from `org.craftercms` to `org.craftercms.aiassistant.studio`) changes the installed path; update build and all references together.

---

## 2. Descriptor: `craftercms-plugin.yaml`

### Location and Role

- File: **`craftercms-plugin.yaml`** at the **plugin project root** (next to `authoring/`).
- It defines plugin metadata and **installation**: what gets merged into the site’s `config/studio/ui.xml` when the plugin is installed (e.g. via marketplace/copy or copy-plugin).

### Important Fields

- **`plugin.id`** — Unique ID; must match everywhere the plugin is referenced (see above).
- **`plugin.type`** — e.g. `site` for a site-level plugin.
- **`installation`** — List of entries that merge into site Studio config (e.g. **preview-app** for `ui.xml`, **form-datasource** and **form-control** for `administration/site-config-tools.xml`).

### Installation Entries

**preview-app:** each entry describes **where** to merge **what** in the site’s UI config:

- **`parentXpath`** — XPath to the parent widget (e.g. `//widget[@id='craftercms.components.ToolsPanel']` or `//widget[@id='craftercms.components.PreviewToolbar']`).
- **`elementXpath`** — XPath used to detect an **existing** plugin widget so reinstall does not duplicate. **Scope it under the same parent as `parentXpath`** (e.g. `//widget[@id='craftercms.components.PreviewToolbar']/configuration/rightSection/widgets/widget[@id='…Helper'][.//plugin[@id='…']]`), not a global `//widget[@id='…Helper']`, or the first match may be the **Tools Panel** row and the **preview toolbar** merge never inserts. See [helper-widget.md](helper-widget.md#preview-toolbar-icon-missing-next-to-the-url-bar).
- **`element`** — The XML fragment to merge (configuration + nested widgets + plugin element).

The **element** is merged under the parent. For example, to add a widget to the **Tools Panel** sidebar, the element is typically:

- `configuration` → `widgets` → `widget` (with `id`, and child `plugin` with `id`, `type`, `name`, `file`). **Do not** use a bare `widget` as the root of `element` for Tools Panel installs — Studio expects the same `configuration` → `widgets` → `widget` nesting as the [sidebar plugin example](https://craftercms.com/docs/current/by-role/developer/composable/extensions/resources/plugin-sidebar-example.html); otherwise the widget may not show up in the left tools list. (**Preview toolbar** list-append in **this** descriptor is different: see below.)

**This plugin’s `craftercms-plugin.yaml`** intentionally omits **Tools Panel** merges (no marketplace auto-install of **Helper** or **AutonomousAssistants** in the left rail). Add those widgets under **`ToolsPanel` → `configuration` → `widgets`** in **`ui.xml`** only if your deployment policy allows it (see [studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml)).

To add a widget to the **Preview Toolbar** (e.g. top bar), add a second installation entry. Crafter Studio’s marketplace wiring (`performConfigurationWiring`) walks the installation element tree in lockstep with `ui.xml` whenever a node has **exactly one** child with the same name as the next descriptor node, then takes **`getChildren().get(0)`**. If that walk reaches a descriptor node whose **`children`** list is **empty** or **missing**, install throws (**`IndexOutOfBoundsException`** or **`NullPointerException`**). **`middleSection/widgets`** often has **zero or one** `widget` children, which makes that walk unsafe; **`rightSection/widgets`** usually has **several** toolbar widgets, so the walk stops early and **`buildXml`** emits the full **`<widget>`** fragment. **This plugin’s descriptor** therefore merges the preview Helper under **`PreviewToolbar` → `configuration` → `rightSection` → `widgets`**. For an icon **next to the URL bar**, move the merged **`<widget id="craftercms.components.aiassistant.Helper">…</widget>`** to **`middleSection` → `widgets`** after install (same idea as Crafter’s [toolbar plugin example](https://docs.craftercms.org/en/4.2/by-role/developer/composable/extensions/resources/plugin-toolbar-example.html)); see [studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml).

**This plugin’s descriptor** merges the preview Helper under **`PreviewToolbar` → `configuration` → `rightSection` → `widgets`**. The default merged agent uses **`openAI`** with **`llmModel`** / **`imageModel`** and **`enableTools`**: supply **`OPENAI_API_KEY`** (or JVM **`crafter.openai.apiKey`**) on the Studio host; do not commit API keys in `ui.xml`. See **[llm-configuration.md](llm-configuration.md)**.

The **plugin** element inside the widget must have:

- **id** — Same as `plugin.id` in the descriptor.
- **type** — e.g. `aiassistant` (must match the folder under `studio/` in the static-assets tree).
- **name** — e.g. `components` (must match the folder name under the plugin path where `index.js` lives).
- **file** — e.g. `index.js`.

So after install, `ui.xml` will contain a widget that points at your plugin script via this `plugin` element; Studio then uses plugin id + type + name + file to resolve the URL.

**form-datasource:** registers the image-from-URL datasource in **`config/studio/administration/site-config-tools.xml`** (Project Tools → Configuration → Project Config Tools). Descriptor entry: `type: form-datasource`, `elementXpath` that uniquely matches this plugin’s `<datasource>`, and `element` with `name: datasource` plus `plugin` children (`pluginId`, `type: datasource`, `name` matching the datasource folder / `getName()`, `filename: main.js`). Reinstall or upgrade runs the same merge logic Studio uses for marketplace installs.

**Central agent catalog (Project Tools):** The descriptor merges **one** Project Tools row — **AI Assistant** (`craftercms.components.aiassistant.ProjectToolsConfiguration`, URL slug **`ai-assistant-config`**) — a tabbed shell, **opened in a large modal dialog** when the tool is selected, with tabs: **UI** (`studio-ui.json` + bulk form-control), **Agents** (`config/studio/ai-assistant/agents.json`), **Tools and MCP** (`scripts/aiassistant/config/tools.json` for built-in + MCP policy, then **`user-tools/registry.json`** + site Groovy tools), **Scripts** (image generators + script LLMs under **`scripts/aiassistant/imagegen/`** and **`scripts/aiassistant/llm/`**), **Prompts and Context** (markdown overrides under **`scripts/aiassistant/prompts/`**). Each **`agents.json`** array entry has **`mode`**: **`chat`** (toolbar / form / preview assistants) or **`autonomous`** (scheduled agents; requires **name**, **schedule**, **prompt**, **scope**, **llm**, **llmModel**, plus optional image and behavior fields). When that file exists and **`agents`** has at least one row, **chat** definitions are taken **only** from **`mode: chat`** rows (or omitted mode, treated as chat), not from **`ui.xml` `<agents>`** on the Helper. When the file has at least one **`mode: autonomous`** row, the Autonomous Assistants widget uses those definitions instead of inline **`autonomousAgents`** in **`ui.xml`**. If the file is missing or unusable, the plugin keeps the previous **`ui.xml`** merge behavior. Example: **`docs/examples/ai-assistant-agents.json`**.

**Screenshots:** [configuration-guide.md — Project Tools and AI Assistant Configuration](configuration-guide.md#cg-screenshots).

**Scripts + Studio UI (same Project Tools panel):** The **UI** tab edits **`config/studio/scripts/aiassistant/config/studio-ui.json`** (toolbar/sidebar visibility, Experience Builder image-augmentation scope, bulk add/remove of the form-engine AI Assistant field). **Tools and MCP**, **Scripts**, and **Prompts and Context** tabs slice the same scripts sandbox UI. Server-side listing uses plugin REST **`GET /studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/content-types/list`** with **`siteId`**. The bundle still registers legacy widget ids **`CentralAgentsConfiguration`**, **`ScriptsSandboxConfiguration`**, and **`StudioUiSettings`** so older **`site-config-tools.xml`** merges keep working (**ScriptsSandboxConfiguration** opens the **Tools and MCP** tab). See **[configuration-guide.md §1e](configuration-guide.md#cg-1e)** and **[spec.md — Studio UI flags](../internals/spec.md#studio-ui-flags-studio-uijson)**.

Studio loads that script via `type=datasource` (not `type=aiassistant`), so the file must live under **`config/studio/static-assets/plugins/org/craftercms/aiassistant/studio/datasource/aiassistant-img-from-url/main.js`** in the site sandbox — not under the `aiassistant/components` tree used for the React bundle.

In the datasource JS, **`CStudioAuthoring.Module.moduleLoaded(...)`’s first argument must equal `getName()`** (e.g. `aiassistant-img-from-url`). If it uses a `cstudio-forms-controls-…` style string that does not match, the script loads but Studio never binds the constructor, so the datasource does not appear in the content type builder and `getLabel()` is never called.

**form-control:** registers a **form engine control** in **`config/studio/administration/site-config-tools.xml`** so it appears in the **Content Types** form builder palette (alongside Input, RTE, etc.). Descriptor entry: `type: form-control`, `elementXpath` such as `//control/plugin[pluginId='…' and name='…']`, and `element` with `name: control` plus `plugin` children (`pluginId`, `type: control`, `name` matching the control folder / `getName()`, `filename: main.js`). The script must implement `CStudioForms`’s control interface and call `CStudioAuthoring.Module.moduleLoaded(<same as getName>, …)`. The file lives under **`…/plugins/<pluginId path>/control/<control-name>/main.js`** (e.g. `studio/control/ai-assistant/main.js`). React-only widgets exported from `index.tsx` are **not** auto-listed in the palette until this wiring exists; this plugin’s **AI Assistant** control loads `craftercms.components.aiassistant.FormControl` from the components bundle inside that legacy shell. Per-agent visibility uses **`getSupportedProperties()`** in `main.js`: each agent from site **`ui.xml`** or from **`config/studio/ai-assistant/agents.json`** (when that catalog is authoritative for chat) becomes one **boolean** row in the Content Type field **Properties**, using the same property object shape as built-in datasource checkboxes (`label`, `name`, `type: 'boolean'`, `defaultValue: 'true'` — see `datasource/aiassistant-img-from-url/main.js`). Agents are collected from every **`craftercms.components.aiassistant.Helper`** widget and from any **`<widget>`** that contains **`<plugin id="org.craftercms.aiassistant.studio">`**, so toolbar-only installs still define the list. Studio Redux **`uiConfig.xml`** is preferred; otherwise a sync XHR to **`/studio/api/2/configuration/get_configuration`** fills the list when the property sheet opens.

**Form assistant panel (LOCKED):** The right-hand panel must list **every** enabled agent as its **own** expandable row (accordion-style UX). **Exactly one** row is expanded; **chat lives inside that expanded section only** — not in one shared strip under all rows. Legacy code passes **`cqVisibleAgentsFromProperties`** into React as **`agents`** (see `docs/internals/spec.md` § Form engine control). **Do not change this UX without explicit maintainer approval.** Property `name` values are **`cqShow_` + sanitized `agentStableKey`** — must match **`agentFormPropertyName()`** in `sources/src/agentConfig.ts` and **`cqAgentPropName`** in `main.js`.

---

## 3. UI Configuration: `config/studio/ui.xml`

### Where the Plugin Is Referenced

- **Tools Panel (sidebar):** Under `craftercms.components.ToolsPanel` → `configuration` → `widgets` → your widget.
- **Preview Toolbar (top bar):** Under `craftercms.components.PreviewToolbar` → `configuration` → `middleSection` or `rightSection` (or left) → `widgets` → your widget.

### Plugin Element in the Widget

Each reference to your plugin’s JS must look like:

```xml
<widget id="craftercms.components.aiassistant.Helper">
   <plugin id="org.craftercms.aiassistant.studio"
           type="aiassistant"
           name="components"
           file="index.js"/>
   <configuration ui="IconButton"/>   <!-- optional, e.g. for toolbar icon -->
</widget>
```

- **`plugin id`** must be the **full** plugin id (e.g. `org.craftercms.aiassistant.studio`). If you shorten it (e.g. to `org.craftercms`), Studio will resolve a different path and your script will 404.
- **type** and **name** must match the path segments under the plugin id path where you built the file (e.g. `aiassistant` / `components` for `.../aiassistant/components/index.js`).

### Second Widget: Studio AI Assistant — Autonomous

**Left rail order (Autonomous vs Project Tools):** The plugin descriptor **does not** auto-merge **AutonomousAssistants** (or **Helper**) into **Tools Panel**. If you add **`AutonomousAssistants`** under **`ToolsPanel` → `configuration` → `widgets`** manually and Studio lists it in an order you do not want relative to **Project Tools**, edit **`config/studio/ui.xml`**: move the **`<widget id="craftercms.components.aiassistant.AutonomousAssistants">…</widget>`** node so it appears **before** the **`reference`** / block that lists **Project Tools** site tools (exact parent depends on your Studio version—keep both under the same sidebar tree your build uses).

This plugin also registers **`craftercms.components.aiassistant.AutonomousAssistants`** on the **same** `components/index.js` bundle. Use it for scheduled, in-memory assistant runs and human-in-the-loop tasks. It is part of the same Studio AI assistant product as interactive chat. The **`plugin`** child must match the Helper exactly (`org.craftercms.aiassistant.studio` / `aiassistant` / `components` / `index.js`).

```xml
<widget id="craftercms.components.aiassistant.AutonomousAssistants">
  <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
  <configuration>
    <title>Autonomous Agents</title>
    <!-- Optional: MUI icon id, or &lt;icon&gt;&lt;![CDATA[&lt;svg ...&gt;]]&gt;&lt;/icon&gt; (see plugin sample / sources/src/autonomousAgentsMarkIcon.tsx) -->
    <autonomousAgents>
      <agent>
        <name>Example agent</name>
        <schedule>0 * * * * ?</schedule>
        <prompt>You are an autonomous assistant. Reply with JSON only as instructed by the server.</prompt>
        <scope>project</scope>
        <llm>openAI</llm>
        <llmModel>gpt-4o-mini</llmModel>
        <!-- Optional: default true. false = stopped after sync until Start in the widget. -->
        <!-- <startAutomatically>false</startAutomatically> -->
      </agent>
    </autonomousAgents>
  </configuration>
</widget>
```

Field reference, REST endpoints, `control` actions, and human-task behavior: **`docs/internals/spec.md`** § *Autonomous assistants widget (Tools Panel)*. The marketplace/descriptor sample merges this under **Tools Panel**; custom sites can paste the block manually into **`config/studio/ui.xml`**.

### Toolbar vs Sidebar

- **Sidebar:** Widget is listed under Tools Panel; often rendered as a list item or panel button.
- **Toolbar:** Same widget in `PreviewToolbar` with e.g. `<configuration ui="IconButton"/>` so it appears as an icon in the top bar (e.g. next to the address bar). Users can have both: one in the sidebar and one in the toolbar.

### Common Gotcha: You May Have Two Copies Configured

It’s common (and often desirable) to configure the same plugin widget in **both** places:

- `craftercms.components.ToolsPanel` → `configuration` → `widgets`
- `craftercms.components.PreviewToolbar` → `configuration` → `rightSection` → `widgets` (descriptor default for marketplace install); move to `middleSection` → `widgets` for URL-bar placement

If you later change the widget’s `<configuration>` (e.g. agent labels, prompts, icon), make sure you update **both** widget entries or you’ll see “old” values depending on which UI surface you’re clicking.

### How `<configuration>` Is Passed to React Widgets (Important for Parsing)

Studio’s UI layer deserializes `ui.xml` into JS objects and passes widget config into the React component. In practice, you can see two patterns:

- **Nested config**: `props.configuration` contains the `<configuration>` object
- **Spread config** (very common): the contents of `<configuration>` are spread onto props as **top-level keys**
  - Example: `<configuration><agents>...</agents></configuration>` may appear as `props.agents`
  - Example: `<configuration ui="IconButton">` may appear as `props.ui` or as an attribute key like `props['@_ui']`
  - Example (default remote chat / legacy popup): per-agent **`<openAsPopup>true</openAsPopup>`** keeps the legacy floating dialog; omitted or **`false`** opens chat in the Experience Builder **ICE** (right) tools panel and turns **edit mode** on.

**Recommendation:** When reading config in widgets, check both:

- `props.<field>` (spread)
- `props.configuration?.<field>` (nested)

### Repeated XML Elements May Deserialize as Arrays *or* Numeric-keyed Objects

Depending on the parser and transform layer, repeated XML elements can show up as:

- Arrays:
  - `<agents><agent>...</agent><agent>...</agent></agents>` → `{ agents: { agent: [ {...}, {...} ] } }`
- Objects keyed by numeric strings:
  - `<agents><agent>...</agent><agent>...</agent></agents>` → `{ agents: { agent: { "0": {...}, "1": {...} } } }`

This is why some working plugins iterate with `Object.keys(...)` / `Object.values(...)` instead of assuming arrays.

**Recommendation:** When normalizing lists, handle both:

- If it’s an array → use it
- If it’s a plain object → use `Object.values(obj)`

---

## 4. Plugin File URL and Authentication

### How Studio Serves Plugin files

Studio serves plugin assets from an endpoint like:

```text
/studio/1/plugin/file?siteId=<siteId>&pluginId=<pluginId>&type=<type>&name=<name>&file=<filename>
```

Example:

```text
/studio/1/plugin/file?siteId=new-demo&pluginId=org.craftercms.aiassistant.studio&type=aiassistant&name=components&file=index.js
```

### Authentication Required

- Plugin file requests use the **same authentication** as the rest of Studio (and preview).
- The browser sends the **same cookies** (e.g. `JSESSIONID`, `XSRF-TOKEN`, `crafterSite`, `crafterPreview`) when loading the page; so when you’re logged in, the plugin script loads.
- If you call the plugin URL without auth (e.g. bare `curl` without cookies), Studio may redirect to login or return an error, which can look like a 404 or “file not found” in the UI.

For scripted or API tests, pass the same session (cookies or JWT) you use for Studio/preview.

---

### Calling Plugin APIs from Plugin UI (REST Scripts)

Studio plugins commonly ship **Groovy REST scripts** (e.g. `*.get.groovy`, `*.post.groovy`) that the plugin UI calls.

#### Endpoint Shape (Studio Proxy to Plugin Scripts)

- **Base endpoint**: Studio executes plugin Groovy scripts via:

```text
/studio/api/2/plugin/script/<scriptPath>?siteId=<siteId>
```

- **`siteId` is required**. If you omit it, Studio returns an error like:
  - `MissingServletRequestParameterException: Required request parameter 'siteId' ... is not present`

#### Mapping from URL → Repo Path

- `<scriptPath>` maps to the installed site sandbox under:

```text
{siteRepo}/config/studio/scripts/rest/<scriptPath>.<method>.groovy
```

Example (this plugin’s streaming endpoint):

- **UI calls**:

```text
POST /studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=new-demo
```

- **Script lives in plugin repo** (and must be copied into the site sandbox at install time):
  - `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream.post.groovy`

#### Important: Include Plugin Id in the Script Path (Trello Pattern)

When your REST scripts import classes from `config/studio/scripts/classes`, the script path must be shaped so Studio can resolve the **pluginId** and classpath correctly.

- **Working pattern** (used by Trello + this plugin):
  - `/plugins/<pluginId-path>/<plugin-id-suffix>/<your-feature>/...`
  - Example: `plugins/org/craftercms/aiassistant/studio/aiassistant/...`
- **Common failure mode** if the path is “too short” / missing the plugin id segments:
  - `pluginId is null` (NPE) during script execution
  - or `unable to resolve class plugins.org...` when importing your Groovy classes

#### Authentication: What the UI Must Do

- Plugin UI runs in Studio, so auth is the **Studio session** (cookies) + **XSRF**.
- For `fetch()` calls:
  - Use a **relative** Studio URL (starts with `/studio/...`) so it’s same-origin.
  - Ensure cookies are sent (default `credentials: 'same-origin'` is fine; set it explicitly to be safe).
  - Include the XSRF header for POST/PUT/DELETE when required by Studio:
    - Header: `X-XSRF-TOKEN: <value from XSRF-TOKEN cookie>`

Copy/paste reference (TypeScript):

```ts
function getCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name.replace(/([.$?*|{}()\\[\\]\\\\\\/\\+^])/g, '\\$1') + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

function buildStudioXsrfHeaders(): Record<string, string> {
  const token = getCookie('XSRF-TOKEN');
  return token ? { 'X-XSRF-TOKEN': token } : {};
}

async function callPluginScriptJson<T>(siteId: string, scriptPath: string, body: unknown): Promise<T> {
  const url = '/studio/api/2/plugin/script/' + scriptPath + '?siteId=' + encodeURIComponent(siteId);
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...buildStudioXsrfHeaders()
    },
    body: JSON.stringify(body)
  });

  if (!res.ok) {
    const text = await res.text();
    throw new Error('Plugin script failed (' + res.status + '): ' + text);
  }

  return (await res.json()) as T;
}
```

#### Response Types (JSON vs SSE)

- If your plugin endpoint returns **JSON**, set `Accept: application/json` and parse with `res.json()`.
- If your plugin endpoint is **SSE streaming**:
  - Response must be `text/event-stream`
  - Client must use `EventSource` (GET-only) or `fetch()` + `ReadableStream` parsing
  - Server-side Groovy script must **not** return a Map/object after writing SSE bytes (Studio will attempt content negotiation and can throw `HttpMediaTypeNotAcceptableException`)

#### Debug Checklist for UI → Plugin API Issues

- **401 Unauthenticated**:
  - The UI call is not same-origin (wrong base URL) or cookies aren’t being sent.
  - Ensure the URL starts with `/studio/...` and `credentials: 'same-origin'` is set.
  - If POST, ensure `X-XSRF-TOKEN` header is present (value from `XSRF-TOKEN` cookie).
- **400 Invalid parameter(s)**:
  - Missing `siteId` query param (required by Studio’s plugin script controller).
- **500 pluginId null / unable to resolve class**:
  - Script path doesn’t include plugin id segments (use Trello pattern).
  - `authoring/scripts/classes` wasn’t copied + committed into `{siteRepo}/config/studio/scripts/classes`.

#### User-authored **Tools** (Site Groovy, Survives Plugin Reinstall)

**Convention (AI Assistant plugin):** Site-specific **tool code** (Groovy the model or plugin may invoke—not prompt/RAG “skills”) lives under the sandbox at:

```text
{siteRepo}/config/studio/scripts/aiassistant/user-tools/
```

- **Why here:** Under `config/studio/scripts/` with an `aiassistant/` segment, it is clearly **Studio script territory**, not `static-assets` (which plugin installs often refresh). Keep **plugin-shipped** REST scripts under `config/studio/scripts/rest/plugins/...` as today; keep **author-maintained** tool implementations in `user-tools/` so reinstall/copy-plugin does not replace them (`scripts/install-plugin.sh` only copies `authoring/scripts/classes` → `config/studio/scripts/classes` and does not remove sibling directories under `config/studio/scripts/`).
- **Naming:** Use **tools** for executable code; reserve **skills** for prompt- or retrieval-oriented behavior (e.g. expert markdown / embeddings) so docs and `ui.xml` stay unambiguous.

**Convention (AI Assistant — custom LLM):** `config/studio/scripts/aiassistant/llm/{id}/runtime.groovy` (or `llm.groovy`) implements **`StudioAiLlmRuntime`** or a **Map** with **`buildSessionBundle`** for **`&lt;llm&gt;script:{id}&lt;/llm&gt;`**. Same install survivability as **user-tools/** (sibling under `config/studio/scripts/aiassistant/`). See **`docs/using-and-extending/llm-configuration.md`**, **`docs/using-and-extending/script-llm-bring-your-own-backend.md`**, and examples under **`docs/examples/aiassistant-llm/`** (`demo/`, **`byo-openai-compat/`** as the sample id for a **tools-loop** custom host, `groq/`).

**Convention (AI Assistant — custom image generation):** `config/studio/scripts/aiassistant/imagegen/{id}/generate.groovy` — backend selected per agent or request **`imageGenerator`**: blank uses the **built-in GenerateImage HTTP** path when credentials and **`imageModel`** allow it; **`none`** / **`off`** / **`disabled`** omits **GenerateImage**; **`script:{id}`** runs that Groovy script (same “script your own” idea as LLMs). Optional host env **`OPENAI_IMAGES_OPENAI_BASE_URL`** adjusts the wire URL; JVM mirror **`crafter.openai.imagesOpenAiBaseUrl`** is documented in **[studio-aiassistant-jvm-parameters.md](studio-aiassistant-jvm-parameters.md)**.

**Per-site built-in tool policy, MCP, and prompts:** `config/studio/scripts/aiassistant/config/tools.json` may list **`disabledBuiltInTools`** (tool names to hide) or a non-empty **`enabledBuiltInTools`** whitelist (built-in CMS tools only — **`InvokeSiteUserTool`** and **`mcp_*`** MCP tools are exempt from that whitelist). **MCP is off by default:** set JSON **`mcpEnabled`** to **`true`** (same file) before **`mcpServers`** is read; then **`mcpServers`** registers **Streamable HTTP** MCP servers whose tools merge into the native catalog (see **[chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md#mcp-client-tools-streamable-http)**). Optional **`disabledMcpTools`** hides specific **`mcp_`** wire tools. Tool prompt text can be overridden by dropping Markdown files under **`config/studio/scripts/aiassistant/prompts/`** using the **prefixed keys** from **`ToolPromptsOverrideCatalog`** (e.g. **`GENERAL_OPENAI_AUTHORING_INSTRUCTIONS.md`**, **`CMS_CONTENT_DESC_GET_CONTENT.md`**, **`CRAFTERQ_DESC_LIST_AGENT_CHATS.md`** — see **[configuration-guide.md §9.1](configuration-guide.md#cg-9-1)** for prefixes and upgrades). See **`plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig`**, **`plugins.org.craftercms.aiassistant.mcp.StudioAiMcpClient`**, and **`ToolPromptsLoader`** in the plugin sources.

**Runtime wiring (shipped in plugin classes):** When `registry.json` exists and lists at least one tool, the Spring AI tool list includes **`InvokeSiteUserTool`**. The model calls it with:

- **`toolId`** — must match an `id` from `registry.json`.
- **`args`** — optional JSON object; passed to the script as binding variable **`args`** (a `Map`).

**Manifest — `registry.json`** (Studio configuration path `/scripts/aiassistant/user-tools/registry.json`):

```json
{
  "tools": [
    {
      "id": "hello",
      "script": "hello.groovy",
      "description": "Example: returns a greeting; optional args.name"
    }
  ]
}
```

Each entry needs **`id`** (letters, digits, `_`, `-`, max 64 chars) and **`script`** (or **`file`**) — a filename matching `^[A-Za-z0-9][A-Za-z0-9_.-]*\\.groovy$` in the same folder. Optional **`description`** is shown in the tool description sent to the model.

**Script bindings** (site Groovy body evaluated by `GroovyShell`):

| Variable | Meaning |
|----------|---------|
| `studio` | `StudioToolOperations` — same CMS helpers as built-in tools (`getContent`, `writeContent`, …). |
| `args` | Map from the `InvokeSiteUserTool` call (may be empty). |
| `toolId` | Registered id string. |
| `siteId` | Effective Studio site id (`studio.resolveEffectiveSiteId('')`). |
| `log` | SLF4J logger for the user-tool runner. |

**Return value:** The script’s **last expression** should be a **Map** (e.g. `ok`, `message`, custom fields). Non-Map results are wrapped as `{ ok: true, result: … }`.

**Example files** (copy into your site sandbox):

```text
docs/examples/aiassistant-user-tools/
```

Copy `registry.json` and `hello.groovy` into the site sandbox folder above, commit, and refresh Studio configuration if needed. After that, **`InvokeSiteUserTool`** appears for agents with tools enabled; the model can call `toolId: "hello"` and optional `args: { "name": "Team" }`.

**Security note:** Anyone who can commit to `config/studio/scripts/aiassistant/user-tools/` can run arbitrary Groovy in the Studio JVM with the author’s security context. Treat that path like production code access.

**Consolidated integrator guide** (user-tool bindings vs script **`generate.groovy`**, `GenerateImage` return maps, checklists): **[scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md)**.


### Calling Crafter Studio Services In-process from Groovy (Preferred for “Tools”)

If your Groovy REST scripts (or Groovy classes under `config/studio/scripts/classes`) need to call Studio capabilities (read/write content, query search, etc.), prefer using **Studio Spring beans in-process** instead of calling Studio REST endpoints.

Benefits:

- Faster (no HTTP hop)
- No auth/XSRF forwarding needed
- Easier to compose multi-step “tools” for agents

#### How to Obtain Beans

From a plugin REST script you typically have `applicationContext` available:

```groovy
def contentService = applicationContext.get('cstudioContentService')
```

If you don’t have `applicationContext`, you can use the engine accessor (note: in this runtime it’s `get(...)`, not `getBean(...)`):

```groovy
import org.craftercms.engine.util.spring.ApplicationContextAccessor

def contentService = ApplicationContextAccessor.get('cstudioContentService')
```

#### Bean Names (Common)

- **v1 content (writes, revert item):** `cstudioContentService` — `org.craftercms.studio.api.v1.service.content.ContentService`
- **v2 content (reads, version history):** `contentService` — `org.craftercms.studio.api.v2.service.content.ContentService`
- **v1 deployment (publish to environment):** `cstudioDeploymentService` — `org.craftercms.studio.api.v1.service.deployment.DeploymentService`
- Search: `authoringSearchService`
- Configuration: varies by version; this plugin resolves **`configurationService`** for `getConfigurationAsString`-style calls.

See **§7–8** below for preview refresh, v1/v2 services, and publish/revert pitfalls.

#### Quick Method Discovery (When Version Differences Exist)

Studio service interfaces can differ by version. For quick experimentation:

```groovy
def methods = contentService.getClass().methods*.name.unique().sort()
logger.info("ContentService methods: {}", methods)
```

Keep experiments non-fatal (log + continue) so streaming endpoints aren’t broken by a missing bean/method.

---

## 5. Build and Packaging

### Typical Layout

- **Source:** e.g. `sources/src/` (React/TypeScript).
- **Build:** Vite or similar for dev; **Rollup** (or equivalent) for the **plugin bundle** that gets copied into `authoring/static-assets/...`.
- **Output:** Paths under `authoring/static-assets/plugins/...` must match the installed path (see section 1).

### Rollup (or Similar) Configuration

- Set the **output directory** to the path that, after copy, becomes `config/studio/static-assets/plugins/<pluginId-path>/<type>/<name>/` (and optionally a sibling like `tinymce`).
- Example for plugin id `org.craftercms.aiassistant.studio`, type `aiassistant`, name `components`:

  - Main bundle: `../authoring/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/components/index.js`
  - TinyMCE: `../authoring/static-assets/plugins/org/craftercms/aiassistant/studio/aiassistant/tinymce/craftercms_aiassistant.js`

- Use **externals** and/or **replace** so the bundle uses Studio’s shared libs (e.g. `craftercms.libs.React`, `craftercms.components`, etc.) instead of bundling React/MUI/studio-ui.

### TinyMCE Plugin

- Built as a separate bundle (e.g. IIFE) and placed under the same plugin id path, e.g. `tinymce/craftercms_aiassistant.js`.
- In `ui.xml`, TinyMCE’s `external_plugins` must point at the **plugin file URL** with the **correct pluginId**:

  ```text
  /studio/1/plugin/file?siteId=new-demo&pluginId=org.craftercms.aiassistant.studio&type=aiassistant&name=tinymce&file=craftercms_aiassistant.js
  ```

  Substitute `new-demo` with your Studio site id (e.g. `qtest`) when different.

- Use `file=...` (not `filename=...`) in the query string if that’s what Studio expects.

### Packaging Command

- From the **plugin repo**, run the command that produces the artifact under `authoring/static-assets/...` (e.g. `yarn package`). That tree is what gets copied into the site on install.

### AI Assistant Plugin Repo: Canonical Sources vs Generated files (Avoid “Disappearing” Fixes)

Edits belong in **`sources/`**. Most paths under **`authoring/static-assets/`** are **build outputs or copies** produced by `yarn package` (`sources/rollup.config.cjs`). If you change only `authoring/...` and someone runs `yarn package`, Rollup **overwrites** those files from `sources/` and your change **vanishes**.

| What | Canonical location (edit here) | Produced under `authoring/static-assets/...` |
|------|-------------------------------|-----------------------------------------------|
| React plugin bundle (Helper, FormControl, chat, ICE, etc.) | `sources/index.tsx`, `sources/src/**/*.tsx`, `sources/src/**/*.ts` | `plugins/org/craftercms/aiassistant/studio/aiassistant/components/index.js` (**bundled**). Also **copied** to `org/craftercms/aiassistant/components/index.js` for legacy `ui.xml` paths. **Do not hand-edit those `index.js` files.** |
| Form engine control (assistant panel, agent list, `cqLoadAgentsForSite`, etc.) | **`sources/control/ai-assistant/main.js`** | **Copied** (not compiled) to `plugins/.../studio/control/ai-assistant/main.js` at end of `yarn package`. |
| Image-from-URL datasource | `sources/datasource/aiassistant-img-from-url/main.js` | Copied to `plugins/.../studio/datasource/aiassistant-img-from-url/` and legacy `org/craftercms/aiassistant/datasource/aiassistant-img-from-url/`. |
| TinyMCE plugin | Built from `sources/src/craftercms_aiassistant.tsx` → `sources/public/craftercms_aiassistant.js` | Rolled into `plugins/.../studio/aiassistant/tinymce/` and legacy `org/craftercms/aiassistant/tinymce/`. |

**Workflow:** Change **`sources/`** → run **`yarn package`** from **`sources/`** → install or copy `authoring/static-assets/` to the site. Treat **`authoring/static-assets/plugins/.../aiassistant/components/index.js`** and the duplicate under **`org/craftercms/aiassistant/components/`** as **generated**.

---

## 6. Installation

### From Local Plugin Repo

- **API:** `POST /studio/api/2/marketplace/copy` with body e.g. `{ "siteId": "new-demo", "path": "/absolute/path/to/plugin/repo" }` (substitute your site id). Use the same auth (e.g. Bearer token) as for Studio.
- **CLI:** e.g. `crafter-cli copy-plugin -e <env> -s <siteId> --path /path/to/plugin/repo`.

Installation copies `authoring/static-assets/*` into the site’s `config/studio/static-assets/plugins/<pluginId-path>/` and runs the descriptor’s **installation** steps to merge into `config/studio/ui.xml`. The **scripts** that Studio runs for plugin REST endpoints are typically copied from `authoring/scripts/rest`. The **`authoring/scripts/classes`** folder may not be copied by marketplace/copy; this plugin requires it for Spring AI and tools. If after install the stream fails with “unable to resolve class”, copy `authoring/scripts/classes` to the site’s `config/studio/scripts/classes` manually.

### Where Studio Reads `ui.xml` From (and Why Commits Matter)

Studio reads UI configuration from the site repository under:

```text
{crafter-install}/crafter-authoring/data/repos/sites/<siteId>/sandbox/config/studio/ui.xml
```

**Concrete example (this maintainer’s local Authoring 4.4.xE install, site `new-demo`):**

```text
/home/russdanner/crafter-installs/4-4-xE/crafter-authoring/data/repos/sites/new-demo/sandbox/config/studio/ui.xml
```

Same install, alternate site id **`qtest`**: `/home/russdanner/crafter-installs/4-4-xE/crafter-authoring/data/repos/sites/qtest/sandbox/config/studio/ui.xml` (install with `./scripts/install-plugin.sh qtest`).

In practice, changes to `ui.xml` are most reliable in Studio after they are **committed** to the site’s `sandbox` git repository (this is also consistent with other Studio behaviors like indexing).

**Troubleshooting tip:** If you edited labels/prompts in `ui.xml` but Studio still shows old values:

- Verify you edited the correct site’s sandbox `ui.xml`
- Verify you updated both widget entries (Tools Panel and Preview Toolbar) if both are present
- `git status` in the sandbox repo and commit the changes
- Refresh the browser (hard refresh if needed)

### Local Development: Package + Install Script

This repo provides **`scripts/install-plugin.sh`** to package and install in one step so you can test changes quickly:

```bash
./scripts/install-plugin.sh [siteId=new-demo] [studioUrl] [pluginRepoPath]
```

Examples:

```bash
./scripts/install-plugin.sh
# same as:
./scripts/install-plugin.sh new-demo
# Another site (e.g. qtest) on the same CRAFTER_DATA install:
./scripts/install-plugin.sh qtest http://localhost:8080
```

The script (edit `CRAFTER_DATA` at the top for your machine):

1. Runs `yarn package` in `sources/` to build the plugin into `authoring/static-assets/...`.
2. Calls `POST /studio/api/2/marketplace/copy` with the repo path and site id.
3. Copies `authoring/scripts/classes` into the site’s `config/studio/scripts/classes` and commits in the site sandbox.

**Run this after every plugin code change** so the installed site has the latest bundle. Refresh the browser (and clear cache if needed) to load the new JS.

### Storing the Studio Bearer Token

The install script needs a **Bearer token** (JWT) for Studio’s API. Two options:

1. **Environment variable:** `export CRAFTER_STUDIO_TOKEN='your-jwt'` before running the script.
2. **Gitignored file (recommended for local dev):** Create `scripts/.studio-token` with:
   ```bash
   export CRAFTER_STUDIO_TOKEN='your-jwt-here'
   ```
   The script sources this file if `CRAFTER_STUDIO_TOKEN` is not already set. **Do not commit** this file; `scripts/.studio-token` is listed in `.gitignore`.

Get the token by logging into Studio, opening DevTools → Application → Cookies (or network tab on an API request), or from your auth flow. Rotate it if it expires.

The install script **copies and commits** `authoring/scripts/classes` into the site’s `config/studio/scripts/classes`. Edit the hardcoded `CRAFTER_DATA` at the top of `scripts/install-plugin.sh` to match your Crafter authoring data path (e.g. `.../crafter-authoring/data`).

### After Install

- Ensure **plugin id** in `ui.xml` matches the descriptor. If the site had an older version of the plugin with a different id, fix the plugin id in `ui.xml` (and in TinyMCE config if present) so it matches the descriptor and the installed path.
- If you added a **toolbar** entry in the descriptor, the toolbar widget should appear after install (default: **`rightSection/widgets`**). For an icon next to the address bar, move the widget under `PreviewToolbar` → `middleSection` → `widgets` (see section 3 and examples).

### Groovy Scripting Sandbox

Plugin REST scripts and Groovy classes run inside Crafter Studio’s **scripting sandbox**. The AI Assistant plugin avoids a **compile-time** reference to `org.springframework.ai.tool.execution.ToolCallResultConverter` (it is often absent from the **site Groovy script compile classpath** even when Spring AI is present at runtime). `AiOrchestrationTools` passes tool wire converters through `invokeMethod('toolCallResultConverter', …)` on `FunctionToolCallback` builders so site scripts compile; runtime still uses Spring AI as usual.

Some operations (e.g. Spring AI types invoked via Groovy `invokeMethod` or closures) can be blocked with an error like:

```text
Insecure call to 'method groovy.lang.GroovyObject invokeMethod ...' you can tweak the security sandbox to allow it.
```

To disable the sandbox for plugin scripting (e.g. for local development or when your plugin needs full Groovy/Java reflection), set in **`studio-config-override.yaml`** (in your Crafter Authoring config):

```yaml
studio.scripting.sandbox.enable: false
```

Revert to `true` (or remove the override) in production if you rely on sandbox security.

---

## 7. Preview Iframe Refresh from Plugin React UI (Studio 4.x)

Authors expect the **preview** (guest iframe) to reflect repository changes made through your widget (tools, writes, revert, new static assets). Studio’s own refresh control does **not** run automatically when plugin code mutates the sandbox from the server; the **host** preview shell must be told to reload.

### Redux Action and Event Buses

Studio 4.x preview uses a Redux action and RxJS subjects to coordinate host ↔ guest:

1. Import **`reloadRequest`** from **`@craftercms/studio-ui/state/actions/preview`** (same as Studio’s `PreviewAddressBar` refresh).
2. Dispatch it on **both** buses so the host chrome and the guest iframe stay in sync:

```ts
import { reloadRequest } from '@craftercms/studio-ui/state/actions/preview';
import { getHostToGuestBus, getHostToHostBus } from '@craftercms/studio-ui/utils/subjects';

function triggerStudioPreviewReload(): void {
  const action = reloadRequest();
  getHostToGuestBus().next(action);
  getHostToHostBus().next(action);
}
```

Reference implementation: [craftercms/studio-ui `support/4.x`](https://github.com/craftercms/studio-ui/tree/support/4.x) — search for `reloadRequest` / preview refresh patterns.

### When to Call It from Plugin UI

Call **`triggerStudioPreviewReload()`** after you know the **sandbox** changed in a way the preview should show:

- After a successful **`WriteContent`**-style persistence (templates `.ftl`, scripts `.groovy`, `config/studio/**`, content-type XML under `config/studio/content-types/**`, page/component XML, etc.).
- After a successful **item revert** that does **not** go through your own write tool (Studio v1 **`revertContentItem`** updates the file server-side).
- After tools that write **static assets** (e.g. generated images under `/static-assets/`) if the current page references them.

**Do not** rely on refresh for “guidance-only” tool steps that only return text for the LLM (e.g. `update_template` / `update_content` that fetch current files but do not save) — nothing changed on disk until **`WriteContent`** (or revert) completes.

In this plugin’s chat, SSE tool progress events expose **`metadata.status: "tool-progress"`** and **`metadata.phase`**: `start` | `done` | `warn` | `error`. Injected **`text`** lines start with **🛠️** plus a category (**🔍** read, **✏️** write/revert/publish/update/GenerateImage, **📈** analyze, **🔄** other). **Expert** tools **QueryExpertGuidance**, **GetCrafterizingPlaybook**, and **ConsultCrafterQExpert** use **🛠️🤓** before the category emoji so authors can spot instruction/research/SME work. The React client sets a flag when **`phase === "done"`** for selected tool names (`WriteContent`, `revert_change`, `GenerateImage`), then calls **`triggerStudioPreviewReload()`** once after the stream finishes (and skips this path for the **form-engine** client-JSON-apply surface where the open item is intentionally not written server-side from tools).

### `writeContentAndNotify` Vs Client Reload

Studio’s v1 **`writeContentAndNotify`** (when available) emits content events that may update some Studio UI surfaces. **Still** trigger **`reloadRequest`** if authors report a stale preview after plugin-driven writes — guest iframe caching and timing vary; the buses are the supported “hard refresh” signal.

---

## 8. In-process Studio Services: V1 vs V2 (4.x Lessons)

Crafter Studio 4.x exposes **both** legacy **v1** and **v2** service facades as Spring beans. Method names and parameter meanings differ; **do not** assume one interface’s `revert` or `publish` matches another.

### Content: Reads and History (V2)

- Bean: **`contentService`** → `org.craftercms.studio.api.v2.service.content.ContentService`.
- Use for **read-by-commit** / descriptor-style APIs your version exposes (e.g. `getContentByCommitId`, `getItemDescriptor`).
- **Version history:** `getContentVersionHistory(String siteId, String path)` → `List<ItemVersion>` (or equivalent). Each entry exposes **`versionNumber`**, **`revertible`**, **`modifiedDate`**, etc. Use this list to show history or to pick a target version for revert.

Confirm signatures against [craftercms/studio `support/4.x`](https://github.com/craftercms/studio/tree/support/4.x) `ContentService` v2.

### Content: Writes and Item Revert (V1)

- Bean: **`cstudioContentService`** → v1 `org.craftercms.studio.api.v1.service.content.ContentService`.
- **Writes:** prefer **`writeContentAndNotify(site, path, InputStream)`** when the bean supports it and you want Studio’s normal post-write notifications; otherwise use the **8-arg `writeContent`** overload your version documents, plus explicit **`notifyContentEvent`** if required.
- **Revert a sandbox item to a historical Studio version:** use v1 **`revertContentItem(String site, String path, String version, boolean major, String comment)`** with **`version`** taken from **`ItemVersion.getVersionNumber()`** (from v2 history). **Do not** pass semantic strings like `"content"` / `"template"` as the version argument, and **do not** assume v2’s **`revert(...)`** overloads accept the same parameters as a Git commit id — mismatches produce “no signature of method … revert” style errors at runtime.

### Publishing (V1 `DeploymentService`)

- Bean: **`cstudioDeploymentService`** → `org.craftercms.studio.api.v1.service.deployment.DeploymentService`.
- For **submitting a new publish** of one or more paths to an environment (e.g. `live`), use **`deploy(site, environment, paths, scheduledDate, approver, submissionComment, scheduleDateNow)`** (parameter order and types per your Studio version — see [DeploymentService.java](https://github.com/craftercms/studio/blob/support/4.x/src/main/java/org/craftercms/studio/api/v1/service/deployment/DeploymentService.java)).
- **`approveAndDeploy`** is for **existing** workflow submissions; using it when you meant “start a new deployment” leads to wrong behavior or signature mismatch.
- Pass the **current authenticated Studio user** as **approver** (from `SecurityContextHolder` / your `Authentication`), not a hardcoded service account string, so auditing and permissions match the author.

### Spring Security on Worker Threads

Plugin code that calls Studio services from **async** paths (e.g. OpenAI tool execution on Reactor/HTTP client threads) must **restore** the HTTP request’s **`SecurityContext`** around bean calls. **`@HasPermission`** on v1/v2 services resolves the current user from **`SecurityContextHolder`**; an empty context yields **`SubjectNotFoundException`** / permission failures.

Pattern: capture **`SecurityContextHolder.getContext()`** on the Studio servlet thread, then **`SecurityContextHolder.setContext(copy)`** in a `try/finally` around tool I/O. See `StudioToolOperations#withStudioRequestSecurity` in this repo.

### Configuration Reads

- Bean **`configurationService`** (v2-style) is used for **`getConfigurationAsString(siteId, module, path, environment)`**-style reads in this plugin. Names vary by minor version — resolve the bean your Studio registers and align with [studio support/4.x](https://github.com/craftercms/studio/tree/support/4.x).

---

## 9. Checklist: Plugin Loads Without 404

- [ ] **Descriptor** `plugin.id` is set and consistent (e.g. `org.craftercms.aiassistant.studio`).
- [ ] **Build** writes to `authoring/static-assets/plugins/<pluginId-path>/<type>/<name>/...` (and tinymce if used).
- [ ] **Install** copies that tree into the site’s `config/studio/static-assets/plugins/...`.
- [ ] **ui.xml** (and TinyMCE config) use the **same** plugin id in every `<plugin id="...">` and in every plugin file URL.
- [ ] **Preview toolbar:** **`craftercms-plugin.yaml`** merges the Helper with **`parentXpath`** = **`…/PreviewToolbar/configuration/rightSection/widgets`**. After install, confirm **`craftercms.components.aiassistant.Helper`** under **`PreviewToolbar`**; optionally move the widget to **`middleSection/widgets`** for URL-bar placement. Set **`OPENAI_API_KEY`** on Studio for the default OpenAI agent.
- [ ] **Auth:** Browser (or client) is logged in to Studio so plugin file requests send the same session (cookies/JWT).

---

## 10. Quick Reference: Plugin Id and Paths

| What | Value / path |
|------|-----------------------------|
| Descriptor plugin id | `org.craftercms.aiassistant.studio` |
| Plugin id path (dots → slashes) | `org/craftercms/aiassistant/studio` |
| Main bundle (type/name) | `aiassistant` / `components` → `.../studio/aiassistant/components/index.js` |
| TinyMCE bundle | `aiassistant` / `tinymce` → `.../studio/aiassistant/tinymce/craftercms_aiassistant.js` |
| ui.xml plugin element | `<plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>` |
| importPlugin (runtime) | `importPlugin(site, 'aiassistant', 'components', 'index.js', 'org.craftercms.aiassistant.studio')` |

Use this table when adding a new plugin or when debugging 404s: keep plugin id, type, name, and file names in sync across descriptor, build, ui.xml, and runtime.

---

*This guide is based on the AI Assistant Studio plugin (`plugin-studio-crafterq`) and CrafterCMS 4.x. Paths and endpoint details may vary slightly by Studio version.*
