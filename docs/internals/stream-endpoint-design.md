# AI Streaming Endpoint Design

Companion to **[`spec.md`](spec.md)** for SSE/stream wire behavior. When stream URLs, body fields, or server-side stream semantics change, update **this file** and the relevant sections of **`spec.md`.

**Internals** — SSE contract and server-side stream behavior. **LLM matrix & keys:** [llm-configuration.md](../using-and-extending/llm-configuration.md). **Doc index:** [README.md](../README.md).

## Goal

One endpoint: **agent ID + full prompt in, streamed response out**. The UI does not know or care about tools; all tool execution (getContentType, getContent, writeContent, etc.) and Spring AI orchestration happen server-side inside the endpoint.

## Contract

| | |
|--|--|
| **Method** | `POST` |
| **URL** | Plugin script path: `/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=...` (script at `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream.post.groovy`; path follows Trello pattern: plugin id path + extra segment so Studio resolves pluginId and classpath). |
| **Request body** | JSON: `{ "agentId": "<uuid>", "prompt": "<full prompt>", "chatId": "<optional>", "llm": "openAI \| claude \| …", "llmModel": "<optional>", "imageModel": "<optional; required for GenerateImage when no agent imageModel — GPT Image id, e.g. gpt-image-1>", "openAiApiKey": "<optional testing>" }` — see **[chat-and-tools-runtime.md § REST body](chat-and-tools-runtime.md#rest-body-advanced)**. |
| **Request headers** | Standard Studio session cookies / auth for the plugin REST call. Outbound chat traffic goes to **your configured LLM provider** (OpenAI-compatible **`/v1/chat/completions`**, Anthropic, or site **`script:{id}`**), not to a separate hosted SaaS chat stack. |
| **Response** | `Content-Type: text/event-stream` — SSE chunks consumed by **`AiAssistantChat`**. |

## Server-Side Behavior

- **LLM selection**: Per-agent **`&lt;llm&gt;`** in `ui.xml` (or widget JSON). **`StudioAiLlmKind.normalize`** accepts tool-loop vendors (**`openAI`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`gemini`** / **`genesis`**, **`claude`**, **`script:{id}`**) and **rejects** blank values and legacy hosted-only ids (**`crafterQ`**, **`aiassistant`**, **`hostedchat`**, …). The client may omit **`llm`** when the agent has no **`<llm>`**; the server then **400**s unless **`siteId`** + **`agentId`** allow copying **`llm`** from **`/ui.xml`** — see **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.
- **Tools-loop (`openAI`, `xAI`, `deepSeek`, `llama`, `gemini`, …):** Spring AI **`OpenAiChatModel`** + **RestClient** with **`AiOrchestrationTools`** (native function calling). Requires provider API keys per **[llm-configuration.md](../using-and-extending/llm-configuration.md)**.
- **`claude`:** Spring AI **`AnthropicChatModel`** tool loop (not the OpenAI RestClient path).
- **`script:{id}`:** Site Groovy under **`config/studio/scripts/aiassistant/llm/{id}/`** returns a **`StudioAiLlmRuntime`** bundle; capabilities depend on the script.
- **Prompt length:** Bounded only by **your** chat host / provider limits and orchestration timeouts (**[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**). There is **no** separate hosted SaaS prompt compaction path.
- **Note**: The REST scripts depend on `authoring/scripts/classes` (`AiOrchestration`, …). Marketplace/copy may not copy the classes folder to the site; if the stream fails with “unable to resolve class”, copy `authoring/scripts/classes` to the site’s `config/studio/scripts/classes` manually after install.
- **Studio plugin classpath**: Classes under `scripts/classes` compile in a **restricted** Groovy environment. **`groovy.util.XmlSlurper`** is not available there — use **JDK** `javax.xml.parsers.DocumentBuilderFactory` / `org.w3c.dom` for XML parsing (see `AiOrchestrationTools.extractFormFieldIdsFromFormDefinitionXml`).

## UI

- The Studio plugin (React) sends one prompt and displays streamed chunks.
- No tool list, no tool parameters, no tool results in the client — everything is encapsulated in the streamed reply.

## Files

- **LLM / keys**: **[llm-configuration.md](../using-and-extending/llm-configuration.md)**
- **Stream endpoint**: `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream.post.groovy`
- **Chat endpoint**: `authoring/scripts/rest/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/agent/chat.post.groovy`
- **Classes** (required for Spring AI + tools): `authoring/scripts/classes/plugins/org/craftercms/aiassistant/` — `AiOrchestration.groovy`, etc. If marketplace/copy does not deploy these, copy this folder to the site’s `config/studio/scripts/classes` manually after install.
