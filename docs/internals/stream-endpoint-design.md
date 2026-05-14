# AI Streaming Endpoint Design

Companion to **[`spec.md`](spec.md)** for SSE/stream wire behavior. When stream URLs, body fields, or server-side stream semantics change, update **this file** and the relevant sections of **`spec.md`**.

**Internals** — SSE contract and server-side stream behavior. **LLM matrix & keys:** [llm-configuration.md](../using-and-extending/llm-configuration.md). **Doc index:** [README.md](../README.md).

## Goal

One endpoint: **agent ID + full prompt in, streamed response out**. The UI does not know or care about tools; all tool execution (getContentType, getContent, writeContent, etc.) and Spring AI orchestration happen server-side inside the endpoint.

## Contract

| | |
|--|--|
| **Method** | `POST` |
| **URL** | Plugin script path: `/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=...` (script at `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream.post.groovy`; path follows Trello pattern: plugin id path + extra segment so Studio resolves pluginId and classpath). |
| **Request body** | JSON: `{ "agentId": "<uuid>", "prompt": "<full prompt>", "chatId": "<optional>", "llm": "crafterQ \| openAI", "llmModel": "<optional>", "imageModel": "<optional; required for GenerateImage when no agent imageModel — GPT Image id, e.g. gpt-image-1>", "openAiApiKey": "<optional testing>" }` |
| **Request headers** | Most inbound headers on the Studio→plugin request are forwarded to CrafterQ (`AiHttpProxy.applyCrafterQForwardedHeaders`). Excluded: hop-by-hop (`Connection`, `Transfer-Encoding`, …), `Host`, `Content-Length` / `Content-Type` / `Accept` (the plugin sets these for the outbound JSON or SSE), and **`authorization`** (Studio JWT must not be sent upstream). **`X-CrafterQ-Chat-User`** and other allowlisted values are copied when present. Optional **JSON body** fields **`crafterQBearerTokenEnv`** / **`crafterQBearerToken`** (per-agent ui.xml) cause the plugin to set **`Authorization: Bearer …`** on outbound CrafterQ calls using the host env or literal CrafterQ JWT — see [llm-configuration.md](../using-and-extending/llm-configuration.md). |
| **Response** | `Content-Type: text/event-stream` — same SSE shape as CrafterQ so the existing UI can consume it unchanged. |

## Server-Side Behavior

- **LLM selection**: Per-agent **`&lt;llm&gt;`** in `ui.xml` (or widget JSON). The client may omit **`llm`** when the agent has no **`<llm>`**; the server then **400**s unless **`siteId`** + **`agentId`** allow copying **`llm`** from **`/ui.xml`** before **`StudioAiLlmKind.normalize`** — see **[llm-configuration.md](../using-and-extending/llm-configuration.md)** (table + **Omitted `<llm>` and POST body**). See also **`llmModel`** / **`imageModel`** on each request.
- **CrafterQ (`llm=crafterQ` after explicit normalize)**: Spring AI **`ExpertChatModel`** POSTs a **single text `prompt`** to CrafterQ’s `/v1/chats` API. **No CMS tools** on this path — content/RAG style chat only. Prompt = short system text + `Human:` / `Assistant:` transcript. **DEBUG logs** (when enabled): `CrafterQ HTTP TX/RX` previews, `CrafterQ call start/parsed` (see **README.md** server logging).
- **OpenAI (`llm=openAI`)**: Spring AI **`OpenAiChatModel`** with native **tool** callbacks (`AiOrchestrationTools`). Requires server **`OPENAI_API_KEY`** (JVM key fallbacks: **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**). System message: `ToolPrompts.getOPENAI_AUTHORING_INSTRUCTIONS()` (override key `GENERAL_OPENAI_AUTHORING_INSTRUCTIONS.md`). Verbose tool/payload traces are **DEBUG** (e.g. `TOOL INVOKED` with args preview).
- **Prompt length**: CrafterQ may cap `prompt` around **~1000 characters**. Default `maxPromptChars` is **1000**. If the full instructions + transcript exceed that, the plugin **compacts**: shorter system text (`CRAFTERQ_COMPACT_INSTRUCTIONS`), keeps the **first** `Human:` author line (truncated), then fills remaining space with the **newest** transcript blocks from the end (each block truncated). A **WARN** `CrafterQ prompt compacted` is logged when this runs. To raise the cap when your CrafterQ tier allows it, use JVM properties documented in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)** (CrafterCMS plugin descriptors only allow specific parameter names; a custom descriptor field caused marketplace install failures).
- **HTTP 5xx / `InternalError` from api.crafterq.ai**: If Studio logs (at **DEBUG**) show **`CrafterQ prompt metrics`** with a **small** `chars` / `utf8Bytes` value (well under `maxPromptChars`), the failure is **not** prompt truncation—treat it as a **CrafterQ API or agent** issue (misconfigured `agentId`, upstream outage, etc.). Inbound headers are forwarded except the denylist above (**`authorization`** is always stripped).
- **Note**: The REST scripts depend on `authoring/scripts/classes` (`AiOrchestration`, `AiHttpProxy`). Marketplace/copy may not copy the classes folder to the site; if the stream fails with “unable to resolve class”, copy `authoring/scripts/classes` to the site’s `config/studio/scripts/classes` manually after install.
- **Studio plugin classpath**: Classes under `scripts/classes` compile in a **restricted** Groovy environment. **`groovy.util.XmlSlurper`** is not available there — use **JDK** `javax.xml.parsers.DocumentBuilderFactory` / `org.w3c.dom` for XML parsing (see `AiOrchestrationTools.extractFormFieldIdsFromFormDefinitionXml`).

## CrafterQ vs Tool-capable LLMs

**CrafterQ** (`llm=crafterQ`) is used **only** for **content / RAG** chat in this plugin (no CMS tool bridging in `ExpertChatModel`). **Tools-loop chat** agents (`openAI`, `deepSeek`, `gemini`, `llama`, `xAI`, …) and **Claude** run the **function-tool** loop (CMS tools, HTTP helpers, optional CrafterQ API tools when **`crafterQAgentId`** is set, etc.) — see **[llm-configuration.md](../using-and-extending/llm-configuration.md)**. On **tools-loop** paths with **`crafterQAgentId`**, the server registers **CrafterQ API tools**: **`ConsultCrafterQExpert`** (streaming SME consult), **`ListCrafterQAgentChats`**, and **`GetCrafterQAgentChat`** (read-only listing / conversation GETs for analysis).

## UI

- The Studio plugin (React) continues to send one prompt and display streamed chunks.
- No tool list, no tool parameters, no tool results in the client — everything is encapsulated in the streamed reply.

## Files

- **LLM / keys**: **[llm-configuration.md](../using-and-extending/llm-configuration.md)**
- **Stream endpoint**: `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream.post.groovy`
- **Chat endpoint**: `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/agent/chat.post.groovy`
- **Classes** (required for Spring AI + tools): `authoring/scripts/classes/plugins/org/craftercms/aiassistant/` — `AiOrchestration.groovy`, `AiHttpProxy.groovy`. If marketplace/copy does not deploy these, copy this folder to the site’s `config/studio/scripts/classes` manually after install.
