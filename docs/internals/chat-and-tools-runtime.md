# Chat, CMS Tools, and Runtime Behavior

Companion to **[`spec.md`](spec.md)** for tools, REST bodies, CrafterQ/SaaS HTTP, MCP, and runtime troubleshooting contracts. When those behaviors change, update **this file** and the relevant **`spec.md`** sections.

**Audience:** Maintainers and advanced integrators working on **tools**, **SSE**, **optional hosted SaaS identity**, or **Studio integration**. For **`<llm>`** selection and keys, see [llm-configuration.md](../using-and-extending/llm-configuration.md).

**LLM ids, keys, and provider behavior:** [llm-configuration.md](../using-and-extending/llm-configuration.md)  
**Admin checklist and `ui.xml` surfaces:** [configuration-guide.md](../using-and-extending/configuration-guide.md)  
**Product requirements & mechanics (parent spec):** [spec.md](spec.md)

---

## Crafter Studio Version (CMS Tools)

**Native function tool** calls that read/write repository content (`GetContent`, `WriteContent`, etc.) are wired to **CrafterCMS 4.5.x** Studio Java APIs:

- **Writes:** Bean **`cstudioContentService`** only (same as Crafter Studio in-process v1 content service, [studio support/4.x](https://github.com/craftercms/studio/tree/support/4.x)). Default path: **`writeContentAndNotify(site, path, stream)`** (publishes `ContentEvent` for UI refresh). If `unlock` is false: **8-arg `writeContent`** + **`notifyContentEvent`**.
- **Reads:** v1 `getContent`-style methods when present; otherwise v2 `getContentAsResource` and `getItemDescriptor` (see `StudioToolOperations.groovy`).
- **Content item XML:** Pages and components are stored as `<page>` / `<component>` XML whose child element names come from the **content type** (form-definition field ids). Prompts and tool descriptions tell the model **not** to invent unrelated tags (e.g. generic `<article>` trees). The **`update_content`** tool loads the item’s **`form-definition.xml`** (when `<content-type>` is present in the file) and returns **`contentTypeId`**, **`formFieldIds`**, and the full **`formDefinitionForContentType`** so the model can edit **in place** before **`WriteContent`**. (Typical Studio forms + page XML are small relative to modern LLM context windows.) On **`WriteContent`**, the server may also append **`checkbox-group`** **`item`** rows for **taxonomy-backed** datasources when the form requires selections but the model omitted them (see **[spec.md](spec.md)**).
- **`ListContentTranslationScope`:** Returns a **nested tree** and **`pathChunks`** of `/site/.../*.xml` paths reachable from a page (or component) via `<key>` references — **metadata only** (no bulk XML). Default **`pathChunks`** use **one path per chunk** so full-page translate/copy uses **`GetContent`** / **`WriteContent`** per item and stays within LLM context.
- **`ConsultCrafterQExpert` (tools-loop chat sessions only):** Calls **`api.crafterq.ai/v1/chats`** with the **same `agentId`** as the widget session so the **hosted expert API** can answer as a **subject-matter / RAG** consult (copy, tone, SEO, IA). Does **not** read or write the repository. Prompt length is capped and long transcripts are compacted (default cap **1000** characters; tunable only via JVM — see **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**).
- **`ListCrafterQAgentChats` / `GetCrafterQAgentChat` (tools-loop chat sessions only, when `<crafterQAgentId>` is set):** Read-only **GET** calls to **`/v1/agents/{agentId}/chats`** (optional **startDate**/**endDate** — omit both for **last 30 days UTC**; session **`agentId`** from config when omitted in args) and **`/v1/agents/{agentId}/chats/{chatId}`** for hosted conversation payloads (e.g. audit dislikes, then **`ConsultCrafterQExpert`** or CMS tools for fixes). Same forwarded-header contract as other CrafterQ calls (**`authorization`** is never forwarded — CrafterQ identity uses headers such as **`X-CrafterQ-Chat-User`** from the widget when the author signed into CrafterQ in Studio).
- **`GetContentTypeFormDefinition`:** Prefer **`contentPath`** (same repository path as the page/component XML). The server reads **`<content-type>`** from that file so the model must not guess types from filenames (e.g. `/site/website/index.xml` → **`/page/index`** is wrong). If **`contentPath`** and **`contentTypeId`** disagree, **`contentPath`** wins.
- **`GenerateImage` (OpenAI only):** Calls **`POST /v1/images/generations`** with the same API key as chat. The image model comes only from **`<imageModel>`** / POST **`imageModel`** (no JVM default). OpenAI’s Images API targets **GPT Image** models. The request does not send **`response_format`** (rejected for GPT image); the tool adds **`output_format`** where appropriate and sets **`url`** to a **`data:`** URL when the API returns **`b64_json`** only (the raw tool map omits **`b64_json`** once **`url`** is populated so the payload is not doubled). Configure **`size`** / **`quality`** per OpenAI’s GPT Image docs; persist assets under **`/static-assets/`** for production. In the **native tools-loop**, **`GenerateImage`** results with a **`data:`** bitmap are **not** sent in full on the **`role:tool` wire** (that would exceed the chat context limit). The server stores the bitmap keyed by **`tool_call_id`**, sends the model a **compact** JSON (**`inlineImageRef`** + instructions), and expands **`studio-ai-inline-image://…`** (and legacy **`crafterq-tool-image://…`**) placeholders into the real image URL in the **final** assistant text delivered to Studio.

**Conversation vs focused generation (native tools path — all AI panel surfaces):** The same rules apply whether the author opens the assistant from **Experience Builder / ICE** (preview sidebar), the **floating dialog**, or the **content-type form assistant** (`authoringSurface: formEngine`). Normal chat turns register CMS **function tools** when the agent / request enables them. **`AiAssistantChat`** prepends an **abbreviated prior-turn block** (last several user/assistant messages, capped in size) on every send so each HTTP request stays single-shot while preserving context. For a **focused copy or generation step**, send **`omitTools: true`** on that POST (or set **`&lt;omitTools&gt;true&lt;/omitTools&gt;`** on a quick **`&lt;prompt&gt;`** in ui.xml); that **one** request omits tool schemas so more context remains for large payloads (e.g. serialized form XML or expanded content macros). **`omitTools`** overrides **`enableTools`** for that round-trip only on **any** surface.

---

## OpenAI Vendor API Key (`OPENAI_API_KEY`, Server-side) and Testing-only Widget Key {#openai-api-key-server-side}

**Recommended:** set on the **Studio host** as an environment variable (never commit real keys to site config):

- **`OPENAI_API_KEY`**

Server-side key fallbacks that use JVM system properties are listed in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**.

### Optional: `<openAiApiKey>` in `ui.xml` (Testing Only)

**Not recommended** for production: the key lives in Studio configuration (often Git-tracked), is visible to anyone who can read/edit that config, and is sent from the browser on each chat request.

Use only for **local testing** when you cannot set **`OPENAI_API_KEY`** on the Studio host. Add **inside the same `<agent>`** that uses `<llm>openAI</llm>`:

```xml
<agent>
  <crafterQAgentId>ANOTHER_AGENT_UUID</crafterQAgentId>
  <label>OpenAI tools</label>
  <llm>openAI</llm>
  <llmModel>gpt-4o-mini</llmModel>
  <openAiApiKey>sk-...</openAiApiKey>
</agent>
```

**Precedence:** if **`OPENAI_API_KEY`** (or another server-side key source for that provider — see **[llm-configuration.md](../using-and-extending/llm-configuration.md)** and **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**) is set, those win and **`<openAiApiKey>` is ignored**. The widget value is used only when no server-side key is configured.

The REST body may also include `openAiApiKey` (same precedence); the React widget sends it when parsed from configuration.

---

## Example Agents (Preview Toolbar Widget)

See `craftercms-plugin.yaml` under `installation` → `configuration` → `agents` → `agent`:

```xml
<agent>
  <crafterQAgentId>ANOTHER_AGENT_UUID</crafterQAgentId>
  <label>OpenAI tools</label>
  <llm>openAI</llm>
  <llmModel>gpt-4o</llmModel>
  <imageModel>gpt-image-1</imageModel>
</agent>
<agent>
  <crafterQAgentId>YOUR_CRAFTERQ_AGENT_UUID</crafterQAgentId>
  <label>Hosted chat only (no repo tools)</label>
  <llm>crafterQ</llm>
</agent>
```

---

## Hosted SaaS API Tools on the Tool-capable Path (`ConsultCrafterQExpert`, `ListCrafterQAgentChats`, `GetCrafterQAgentChat`) {#crafterq-api-tools-tools-loop}

These three tools are registered **only** for agents that use the **Spring AI native tool loop** with the shared **`AiOrchestrationTools`** catalog (e.g. **`openAI`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`genesis`/`gemini`**, **`claude`**). They are **not** registered for **`crafterQ`** hosted chat alone (`ExpertChatModel` — no function tools on that adapter).

**Configure the agent in `ui.xml` (or the equivalent widget JSON):**

1. **`<llm>`** — use a **tool-capable** value from [llm-configuration.md](../using-and-extending/llm-configuration.md) (**not** `crafterQ` if you need these tools).
2. **`<crafterQAgentId>`** — set to your **CrafterQ SaaS agent UUID** (same id you use in the hosted app / API URLs). If this element is missing or empty, **`ConsultCrafterQExpert`**, **`ListCrafterQAgentChats`**, and **`GetCrafterQAgentChat`** are **omitted** from the tool list for that agent.
3. **Tools enabled for the request** — same as other CMS tools: do **not** use **`omitTools: true`** for turns where the model should call them; ensure **`&lt;enableTools&gt;false&lt;/enableTools&gt;`** is **not** set on the agent if you want tools at all (see **[spec.md](spec.md)** / agent XML for `enableTools`).

**Server guard (native tools-loop):** When the user message matches **hosted CrafterQ chat analytics** (e.g. “number one question in CrafterQ”, “what people ask” in chat) and **`ListCrafterQAgentChats`** is registered, **`AiOrchestration`** may **rewrite** a misrouted first-round **`ListContentTranslationScope`** call to **`ListCrafterQAgentChats`** and **block** **`TranslateContentBatch`** / **`TranslateContentItem`** / **`ListContentTranslationScope`** for that same user turn so the model cannot burn translate inner calls or touch repo XML for a non-translation ask.

**Minimal example (OpenAI vendor row + optional hosted SaaS API tools):**

```xml
<agent>
  <label>Authoring with optional SaaS chat audit</label>
  <crafterQAgentId>019a4b75-9cb9-7814-a032-14242950d5bc</crafterQAgentId>
  <llm>openAI</llm>
  <llmModel>gpt-4o-mini</llmModel>
  <!-- Optional: admin JWT via Studio host env (recommended). Literal <crafterQBearerToken> is also supported. -->
  <crafterQBearerTokenEnv>CRAFTQ_ADMIN_JWT</crafterQBearerTokenEnv>
</agent>
```

**Identity / auth:** Server-side CrafterQ HTTP calls **do not** forward the Studio **`Authorization`** header to `api.crafterq.ai` (that value is the Studio session, not CrafterQ). Authors can still authenticate CrafterQ in two ways:

- **Widget / browser session:** **`X-CrafterQ-Chat-User`** (from localStorage after CrafterQ login in the widget), forwarded like other inbound headers.
- **Configured CrafterQ JWT (admin or service token):** per-agent **`&lt;crafterQBearerTokenEnv&gt;`** — element text is the **name of an environment variable on the Studio host** whose value is the JWT (read at request time with `System.getenv`). Optional **`&lt;crafterQBearerToken&gt;`** — literal JWT in config (**discouraged** in Git-tracked repos; use env + `crafterQBearerTokenEnv` in production). The plugin sends **`Authorization: Bearer &lt;token&gt;`** to `api.crafterq.ai` when either resolves to a non-empty string (**env wins** when both are set and the env value is non-blank). Same fields are sent on the stream/chat JSON body from the widget (mirroring ui.xml). JSON keys **`crafter-q-bearer-token-env`** / **`crafter_q_bearer_token_env`** (and token variants) are accepted as aliases.

**Crafter `${env:…}` vs this plugin:** CrafterCMS documents **`${env:ENVIRONMENT_VARIABLE}`** substitution for **Studio server configuration** (for example properties in `studio-config.yaml` and related override files). See [Studio configuration](https://craftercms.com/docs/4.1/reference/modules/studio/configuration/index.html). This plugin **does not** implement or interpret that syntax inside **`&lt;crafterQBearerToken&gt;`** / JSON mirror fields; bearer values there are **literal strings** (after optional `Bearer ` strip). For a JWT from the host environment, use **`&lt;crafterQBearerTokenEnv&gt;`** as above.

**Diagnostics (no full secrets in logs):** When a bearer is installed from the stream/chat POST body, Studio logs **INFO** with **source** (`env:VAR` or `literal:POST`), **character count**, and a **short preview** (first/last characters only). If **`crafterQBearerTokenEnv`** is present but **`System.getenv`** returns blank, Studio logs **WARN** (env name not resolved — check JVM env and Studio restart). On CrafterQ **401/403** from GET/POST to `api.crafterq.ai`, Studio logs **WARN** with whether a bearer was stored on the request, the same preview, and whether **`X-CrafterQ-Chat-User`** was present. **`ListCrafterQAgentChats` / `GetCrafterQAgentChat`** error payloads may include **`crafterQBearerInstalledFromPost`**, **`crafterQBearerPreview`**, and **`xCrafterQChatUserPresent`** for the model.

If listing or chat calls return **401/403**, verify **`X-CrafterQ-Chat-User`** and/or the bearer env/token above and that CrafterQ accepts that identity. On **401**, the tool result JSON also includes **`authHint`** (server-added) with the same checklist so the model can quote it in chat.

**HTTP 401 on `ListCrafterQAgentChats` / `GetCrafterQAgentChat`:** The CrafterQ API requires **either** a valid **`X-CrafterQ-Chat-User`** header on the Studio stream/chat request (after signing into CrafterQ in the widget) **or** a configured **`Authorization: Bearer …`** to `api.crafterq.ai` via **`crafterQBearerTokenEnv`** / **`crafterQBearerToken`** (see above). Having only a Studio session cookie is not enough.

**Optional user-tools registry noise:** If the site has no `config/studio/scripts/aiassistant/user-tools/registry.json`, Studio may log **`ContentNotFoundException`** when the plugin probes for that file during tool catalog build; it is **non-fatal**. To silence it, add an empty registry at that path with body **`[]`** (JSON array) or a valid `{ "tools": [] }` object.

**Tool arguments (reminder):** **`ListCrafterQAgentChats`:** optional **`startDate`** / **`endDate`** (ISO-8601 UTC instants, or date-only `YYYY-MM-DD` treated as UTC midnight). **Omit both** to let the server use the **last 30 days UTC** and the session **`crafterQAgentId`** from the agent row (same as stream **`agentId`**). Optional **`limit`** (1–100, default 20); optional **`agentId`** to override. **`GetCrafterQAgentChat`** requires **`chatId`**; optional **`agentId`** the same way.

---

## MCP Client Tools (Streamable HTTP) {#mcp-client-tools-streamable-http}

Sites can attach **remote MCP servers** so **tools-loop chat** agents (and other **native-tool** agents) gain **extra function tools** beyond the built-in CMS catalog. Configuration lives in **`config/studio/scripts/aiassistant/config/tools.json`**. MCP is **off by default**: set JSON boolean **`mcpEnabled`** to **`true`** in that file to load **`mcpServers`** (site config only — not a JVM env var). The same file continues to hold **`disabledBuiltInTools`** / **`enabledBuiltInTools`** as today.

### `tools.json` Fields

| Key | Purpose |
|-----|---------|
| **`mcpEnabled`** | **Required to turn MCP on:** JSON boolean **`true`**. If omitted or **`false`**, **`mcpServers` is ignored** (no outbound MCP calls, no `mcp_*` tools registered). |
| **`mcpServers`** | JSON array of server objects (processed only when **`mcpEnabled`** is **`true`**): required **`id`**, required **`url`** (MCP **Streamable HTTP** endpoint — single path accepting `POST`), optional **`headers`**, optional **`readTimeoutMs`** (default **120000**). In each **`headers`** value, **`${env:VAR_NAME}`** expands to **`System.getenv(VAR_NAME)`** on the Studio JVM (unset → empty string). |
| **`disabledMcpTools`** | Optional array of **wire** tool names to omit (case-insensitive), e.g. **`mcp_docs_search`**. You can also list those names under **`disabledBuiltInTools`**. |

### Wire Names and Lifecycle

- Each MCP tool from **`tools/list`** becomes a Studio tool whose name is **`mcp_<serverId>_<mcpToolName>`** (non-alphanumeric segments collapsed to `_`, total length capped at **64** characters to match the **tools-loop** wire’s tool-name constraints).
- **Per chat request**, when the plugin builds **`AiOrchestrationTools`**, it runs **`initialize`** → **`notifications/initialized`** → **`tools/list`** for **each** configured server, then keeps a **single session** (including **`Mcp-Session-Id`** when returned) for all **`tools/call`** invocations from that request.
- **Security:** MCP **`url`** values use the **same SSRF policy** as **`FetchHttpUrl`** (`StudioToolOperations.validateOutboundHttpUrlForSsrf`). Host allowlists and disabling outbound fetch (which also blocks MCP) are **JVM-only** — see **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)** (`crafterq.httpFetch.*`).
- **Whitelist:** When **`enabledBuiltInTools`** is a non-empty whitelist, **built-in** CMS tools are filtered to that list, but **`mcp_*`** tools and **`InvokeSiteUserTool`** are **still registered** unless their wire names appear in **`disabledBuiltInTools`** / **`disabledMcpTools`**.
- **Response size:** MCP HTTP bodies are capped server-side (default **500000** characters); JVM override: **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)** (`crafterq.mcp.maxResponseChars`).

---

## Optional: Per-Agent Expert Skills (Markdown RAG, Embeddings + Tools)

Inside an `<agent>` that uses `<llm>openAI</llm>`, add one or more **`<expertSkill>`** children. Each row points to a **public `http(s)` URL** whose response body is treated as **UTF-8 markdown**. On first use, Studio **fetches** that URL (same SSRF rules as **`FetchHttpUrl`**), **chunks** the text, **embeds** it with Spring AI (**`text-embedding-3-small`** by default), and stores vectors in a **per-skill in-memory `SimpleVectorStore`**. The model gets a system appendix with **`skillId`** (stable hash from the URL) and may call **`QueryExpertGuidance`** (`skillId`, `query`, optional `topK`).

```xml
<agent>
  <crafterQAgentId>ANOTHER_AGENT_UUID</crafterQAgentId>
  <label>OpenAI with playbook</label>
  <llm>openAI</llm>
  <expertSkill
    name="Crafterizing skill"
    url="https://example.com/my-team-crafterizing-skill.md"
    description="Use for full HTML-template-to-CrafterCMS migrations and content modeling."
  />
</agent>
```

Element form is also supported: `<expertSkill><name>…</name><url>…</url><description>…</description></expertSkill>`.

**Optional tuning (expert skills only):** Markdown from `<expertSkill>` URLs is chunked and embedded into a per-skill in-memory index on the Studio server; defaults are usually enough. If you hit size or memory limits, optional JVM tuning keys are documented in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)** (section **Expert skills**). This is **not** agent `ui.xml` configuration and is unrelated to CrafterQ bearer tokens.

---

## Chat Widget: Stream Completion

The Studio React client stops reading the SSE body as soon as it sees **`metadata.completed: true`** or **`metadata.error: true`**, then **`cancel()`s** the fetch reader. That avoids waiting for the HTTP connection to close (some servlet/async stacks keep it open), which previously surfaced as **“Timed out waiting for chat response”** after 65s. The safety timeout is now **5 minutes** for long tool runs.

**Server-side (Spring AI flux + native tools-loop RestClient):** `AiOrchestration` waits up to **5 minutes** by default for the `chatResponse()` flux to complete or error, or for the **RestClient** multi-round tool `Future` to finish—then **disposes** / **cancels** so the outbound HTTP call is torn down (the **chat host** may see a **client disconnect**). Each **sync** `POST /v1/chat/completions` uses a read timeout tied to that outer budget so JDK **Read timed out** does not fire first. On timeout it sends an **SSE error** so authors see a reason in chat. **Await/read-timeout tuning** and **optional Spring AI HTTP trace** use JVM system properties documented in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**. Crafter Studio uses **Log4j2** — expect first SSE chunk, `onComplete`, `onError`, and a **WARN** if the await times out.

### Author-visible Progress (Tools-loop + Tools)

- **Prompts** (built-in system text from `ToolPrompts.getLlm_AUTHORING_INSTRUCTIONS()` — site override file `GENERAL_LLM_AUTHORING_INSTRUCTIONS.md` — plus `[TOOL-GUARD]` and optional user prefix): the model must stream a **## Plan** heading and numbered steps **before** the first tool call, follow that plan, **re-post the same checklist after each tool** with **✅** / **❌** / **⚠️** / **⬜** (pending — not the hourglass emoji, to avoid mimicking server logs), and prefix **🛠️** when narrating tool use in its own words (use **🤓** when narrating **QueryExpertGuidance**, **GetCrafterizingPlaybook**, or **ConsultCrafterQExpert**). It must **not** fabricate server-style tool-progress lines; real progress is SSE-injected. The closing message repeats the checklist with the same markers.
- **Tools-loop + tools (RestClient loop):** the model emits **`## Plan`** and **`tool_calls` in the same** `stream:false` **chat.completions** round when the API allows; the plan text is **streamed to the client** (SSE) **before** server-executed tools run. There is **no separate author “approve plan” step** in Studio — the gate only **withholds tools** briefly if the plan is empty/meta, then retries with a nudge (see `ToolPrompts` / `AiOrchestration`).
- **Server SSE:** while tools run, **`AiOrchestration`** injects SSE chunks with **`metadata.status: "tool-progress"`** and **`metadata.phase`**. Each injected line starts with **🛠️** plus a category emoji: **🔍** read tools (including **ListCrafterQAgentChats**, **GetCrafterQAgentChat**), **✏️** write/revert/publish and `update_*` / **GenerateImage**, **📈** **analyze_template** (and **ConsultCrafterQExpert** uses **📈** after **🤓**), **🔄** other tools. **Expert** tools **QueryExpertGuidance**, **GetCrafterizingPlaybook**, and **ConsultCrafterQExpert** use **🛠️🤓** before the category (e.g. `start` → `🛠️🤓🔍 **QueryExpertGuidance** …`, `🛠️🤓📈 **ConsultCrafterQExpert** …`). Other tools stay `🛠️` + category only (e.g. `🛠️🔍 **GetContent** …`). The generic “tools working” hint uses **🛠️🔄**. The chat UI appends **`text`** like normal stream tokens.

---

## SSE Stream Errors (Tool Failures in Stream)

If a tool throws mid-stream (e.g. Spring AI `MessageAggregator` / `UndeclaredThrowableException`), the plugin **does not** switch the HTTP response to JSON — that caused `AsyncRequestNotUsableException` when the body was already `text/event-stream`. Instead, **`AiOrchestration`** emits a final SSE frame with `metadata.error: true`, `metadata.message`, and `metadata.completed: true`. The React chat app surfaces that as **Stream error** in the assistant bubble.

---

## REST Body (Advanced) {#rest-body-advanced}

`POST` … `/ai/stream` and `/ai/agent/chat` accept:

- `llm`: `crafterQ` | `openAI` | `xAI` | `deepSeek` | `llama` | `genesis` | `gemini` | `claude` | `script:{id}` — **required** on the wire after merge: missing, blank, invalid **`script:…`** ids, or unknown strings → **400** (`StudioAiLlmKind.normalize`). When **`siteId`** + **`agentId`** are set, the server may copy **`llm`** from the matching **`<agent>`** in **`/ui.xml`** if the POST omitted it. Matching aliases are normalized server-side (e.g. `grok` → xAI, `ollama` → llama). **`script:myid`** → **`scriptLlm:myid`** and loads site Groovy from `/scripts/aiassistant/llm/myid/runtime.groovy`.
- `llmModel`: optional string
- `imageModel`: optional string — OpenAI **Images** model id for **GenerateImage**; must be set on the agent and/or this body field when the model should call **GenerateImage** (no server default). Prefer **`gpt-image-1`** or **`gpt-image-1-mini`**.
- `openAiApiKey`: optional string — **testing only**; per-provider precedence (OpenAI, xAI, DeepSeek, etc.): ignored when the matching server-side key is set (host **env** vars per **[llm-configuration.md](../using-and-extending/llm-configuration.md)**, plus JVM fallbacks in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**). For **`claude`**, the same field can carry the Anthropic key when no **`ANTHROPIC_API_KEY`** is configured.
- `contentPath`: optional repository path of the item open in Studio preview (e.g. `/site/website/about/index.xml`). When set, the server appends **Studio authoring context** to the user prompt so the model treats phrases like “this page”, “my page”, or “update my content” (with no path) as that item.
- `contentTypeId`: optional preview content type (e.g. `/page/home`); included in that context when present.
- `expertSkills`: optional JSON array of `{ "name", "url", "description" }` — same semantics as ui.xml **`<expertSkill>`**; server normalizes URLs and registers **`QueryExpertGuidance`** when non-empty and tools are on.

The React widget sends `llm` / model / key from the selected agent config and sends `contentPath` / `contentTypeId` from the current preview item when available. When the agent defines expert skills, the widget also sends **`expertSkills`** on stream/chat POST.

---

## Crafterizing Playbook Tool

**Native tools mode** registers **`GetCrafterizingPlaybook`**, which returns markdown from an **editable file** shipped with the plugin classes:

- **Path in repo:** `authoring/scripts/classes/plugins/org/craftercms/aiassistant/CrafterizingPlaybook.md`
- **Typical path in a site sandbox** (after `scripts/install-plugin.sh` copy): `config/studio/scripts/classes/plugins/org/craftercms/aiassistant/CrafterizingPlaybook.md`

Edit that file to change phases, checklists, and team conventions without changing Groovy.

**Override (optional):** absolute path to a markdown file via JVM — see **[studio-aiassistant-jvm-parameters.md § Misc](../using-and-extending/studio-aiassistant-jvm-parameters.md#misc)** (`crafterq.crafterizingPlaybook.path`).

If the file is missing at runtime, the tool still returns a short embedded fallback and sets `loadedFromEditableFile: false` in the JSON result.

---

## Troubleshooting: `400 Bad Request` On `/v1/chat/completions` (Tools-loop)

Often caused by **invalid tool `parameters` JSON Schema**. This plugin registers Spring AI `FunctionToolCallback` tools with explicit `inputSchema` strings so chat hosts that accept OpenAI-shaped `tools[]` accept the request. If you still see 400, check Studio logs for a line **`Tools-loop chat error response body:`** — it includes the upstream JSON error (`error.message`, `param`, etc.).

### Tool / Edit Prompts: `JsonEOFException` or Empty JSON from the Chat Host

If you see **`Unexpected end-of-input`** while parsing `ChatCompletion` during **edit / write / create** style prompts, that was typically caused by a **blocking** `ChatClient.call()` tool path on a worker thread. The plugin uses the **SSE `chatResponse` flux** for all **tools-loop** tool chats (including those prompts) so Spring AI can finish the full tool loop. `extractContentFromCallResult` also prefers **`chatResponse()`** over **`content()`** for non-streaming fallbacks.

### `ListPagesAndComponents` / `java.net.ConnectException: Connection refused` (OpenSearch)

**This is not a chat-host or Spring AI failure.** The model successfully requested the tool; the failure happens when Studio calls **`authoringSearchService`** → OpenSearch (same stack Studio uses for search in the UI).

- **Fix (ops):** Ensure the **authoring OpenSearch** service is running and reachable from the Studio JVM (Docker Compose / Kubernetes / local install — match your Crafter distribution docs). Until search is up, **`GetContent` / `WriteContent` / `GetContentTypeFormDefinition`** still work when you pass a real **`siteId`** and repository **`path`**.
- **Plugin behavior:** If OpenSearch is down, `ListPagesAndComponents` returns a JSON tool result with **`error: true`** and a short message instead of throwing, so the chat stream can continue and the model can fall back to paths the user provides.
- **`siteId`:** The widget and REST body should send the **actual Studio site id** (e.g. `new-demo` for this repo’s default local test site in `install-plugin.sh`). If the model passes `default`, the server substitutes the request’s `siteId` when present (`crafterq.siteId` attribute / query / body).

### `WriteContent` Returns `ok: false` / “No Commit” (Studio Did Not Save)

Crafter’s `writeContentAndNotify` only succeeds when the sandbox creates a **new git commit**. If the body you send is **identical** to the file already in the repo, the commit id is empty and the plugin returns **`ok: false`** with `skippedReason: no_commit` — this is **not** a Spring/stream exception anymore; the model should read the hint, call **GetContent** for that path, and only write when there is a real diff.

### 404 on `/static/...` in Preview

Engine serves static files from **`/static-assets/`**. Templates that use `/static/images/...` will not resolve. Prefer **`/static-assets/images/...`**, existing repo paths, or CSS-only backgrounds until assets exist.

### “No Error” but Content Did Not Change in Studio

`update_content`, `update_template`, and `update_content_type` only **fetch** current text and return guidance. **Nothing is persisted** until the model calls **`WriteContent`** with the full updated XML/FTL. If the assistant stops after `update_content`, the repo is unchanged by design.

The plugin sends preparatory tool results as **JSON** (including `nextStep` and `instructions`) so the model sees that a **`WriteContent`** call is required; `GetContent` alone is still shortened to raw XML for token savings.

### `WriteContent` / `IllegalStateException` (contentService)

CMS tools call Studio **in-process** (`cstudioContentService`, configuration beans, etc.). There is **no HTTP fallback** to Studio REST. If write/read fails, check Studio logs for the wrapped exception and ensure the plugin runs in the **authoring** web app with a full Spring context.

### `PermissionException` / `SubjectNotFoundException: Current subject was not found`

**Tools-loop chat streaming** runs tool callbacks on **Reactor / HTTP client worker threads**, where Spring Security’s **`SecurityContextHolder` is empty** by default. Studio’s `ContentService.writeContent` (and similar) use **`@HasPermission`**, which resolves the current user from that holder — so writes fail with **subject not found** unless the request’s security context is restored on the worker thread.

The plugin **captures** `SecurityContextHolder.getContext()` on the **Studio servlet thread** when building the Spring AI client (`AiOrchestration.buildSpringAiChatClient`) and passes a **copy** into `StudioToolOperations`, which calls `SecurityContextHolder.setContext(...)` around tool I/O (`writeContent`, `getContent`, `DeploymentService.deploy`, v1 `revertContentItem`, OpenSearch-backed listing, etc.). `@HasPermission` checks use that context.

- If you still see this error, confirm the chat/stream REST call is authenticated as a **Studio user** with **write** permission on the path (not an anonymous session with no `Authentication`).
- Custom entry points that construct `StudioToolOperations` without going through `AiOrchestration` must pass the same **security context copy** (4th argument) or tools will log a one-time warning and may fail on worker threads. Optional **5th** = remote hosted **`agentId`** for **`ConsultCrafterQExpert`**; **6th** = max consult prompt chars (defaults match the hosted-prompt cap described in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**).

---

## Studio AI Assistant — Autonomous (Scheduled Steps) {#autonomous-assistants}

The **Tools Panel** widget **`craftercms.components.aiassistant.AutonomousAssistants`** (**Studio AI assistant — autonomous**) uses **`autonomousAgents`** / **`agent`** rows with **`llm`**, **`llmModel`**, optional **`openAiApiKey`**, optional **`startAutomatically`** (default **true**; when **false**, sync registers the agent as **stopped** until **Start** in the widget), optional **`stopOnFailure`** (default **true**; when **false**, a failed run records **`lastError`** and schedules a retry instead of **`error`** status), and optional **`expertSkills`** (same JSON shape as Helper **`<expertSkill>`** for **QueryExpertGuidance**). Each autonomous step uses a **tools-loop** **`llm`** (`openAI`, `xAI`, `deepSeek`, `llama`, `genesis` / `gemini`): the **same authoring system stack** as **`/ai/stream`** where RAG/embeddings still prefer **`OPENAI_API_KEY`**, the **same native `tools[]` catalog** and **RestClient** tool loop, then the agent’s per-step JSON contract. **`claude`** is **not** supported for autonomous runs (use a **tools-loop** provider). **Key precedence** per provider matches interactive chat (server env/JVM first; per-agent **`<openAiApiKey>`** only when no server key for that provider).

---

## Future

Additional hosted-tool contracts or provider rows may be documented in [llm-configuration.md](../using-and-extending/llm-configuration.md) as they ship. **`ConsultCrafterQExpert`** already calls the hosted stack as a **CMS tool** from **tools-loop chat** sessions.
