# Studio AI Assistant — JVM System Properties (`-D`)

This page lists **JVM-only** knobs the plugin reads via **`System.getProperty`** (typically set on the Studio process as **`-Dname=value`** or in **`JAVA_TOOL_OPTIONS`**). They are **not** `ui.xml` fields and **not** environment variables.

**Primary admin docs:** [configuration-guide.md](configuration-guide.md) · [llm-configuration.md](llm-configuration.md) (env + XML) · [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md)

Use this file when you need to tune timeouts, logging, or defaults that are not exposed in site configuration.

---

## LLM Providers (`crafter.*`)

| Property | Typical use |
|----------|-------------|
| **`crafter.openai.apiKey`** | OpenAI API key when **`OPENAI_API_KEY`** is not set. |
| **`crafter.openai.model`** | Default chat model when **`<llmModel>`** / request body omit it (OpenAI path). |
| **`crafter.openai.imagesOpenAiBaseUrl`** | Override host for default **`/v1/images/generations`** (also env **`OPENAI_IMAGES_OPENAI_BASE_URL`** in code — env is documented in [image-generation.md](image-generation.md) / [studio-plugins-guide.md](studio-plugins-guide.md)). |
| **`crafter.xai.apiKey`** | xAI key when **`XAI_API_KEY`** is unset. |
| **`crafter.xai.openAiBaseUrl`** | Tools-loop chat base URL for xAI (env **`XAI_OPENAI_BASE_URL`** takes precedence when set). |
| **`crafter.xai.model`** | Default xAI chat model. |
| **`crafter.deepseek.apiKey`** | DeepSeek key when **`DEEPSEEK_API_KEY`** is unset. |
| **`crafter.deepseek.openAiBaseUrl`** | DeepSeek tools-loop chat base (env **`DEEPSEEK_OPENAI_BASE_URL`**). |
| **`crafter.deepseek.model`** | Default DeepSeek chat model. |
| **`crafter.llama.apiKey`** | API key for the **llama** tools-loop row when **`LLAMA_API_KEY`** is unset (Ollama often accepts a placeholder). |
| **`crafter.llama.openAiBaseUrl`** | Base URL (env **`LLAMA_OPENAI_BASE_URL`** / **`OLLAMA_OPENAI_BASE_URL`**). |
| **`crafter.llama.model`** | Default chat model id. |
| **`crafter.gemini.apiKey`** / **`crafter.google.apiKey`** | Gemini / Google GenAI key when env keys are unset. |
| **`crafter.gemini.openAiBaseUrl`** | Gemini tools-loop chat base (env **`GEMINI_OPENAI_BASE_URL`** / **`GOOGLE_GENAI_OPENAI_BASE_URL`**). |
| **`crafter.gemini.model`** | Default Gemini chat model. |
| **`crafter.anthropic.apiKey`** | Anthropic key when **`ANTHROPIC_API_KEY`** is unset. |
| **`crafter.anthropic.model`** | Default Claude chat model. |

---

## Chat Orchestration & CrafterQ (`crafterq.*`)

| Property | Default / notes |
|----------|-----------------|
| **`crafterq.maxPromptChars`** | Caps hosted CrafterQ-style prompts (e.g. **`ConsultCrafterQExpert`**, remote **`crafterQ`** adapter compaction). Default **1000**. |
| **`crafterq.chatFluxAwaitMs`** | Max wait for Spring AI chat flux / tool **Future** (ms). Default **300000**; allowed range **120000–600000** in implementation. |
| **`crafterq.openai.restReadTimeoutMs`** | Per-request read timeout for sync OpenAI **RestClient** `chat/completions` (ms). Tied to orchestration budget + margin. |
| **`crafterq.openai.sseWaitHeartbeatMs`** | SSE / wait tuning inside **`AiOrchestration`**. |
| **`crafterq.openai.reviewMaxChars`** | Caps review / analysis payloads in some tool paths. |
| **`crafterq.translateContentItemMaxOutTokens`** | Inner completion token cap for **`TranslateContentItem`**-style paths. |
| **`crafterq.springAiHttpDebug`** | When **`true`**, raises Log4j2 levels for Spring AI HTTP client logging on first use (Studio uses Log4j2). |

---

## Outbound HTTP, Preview Fetch, MCP (`crafterq.*`)

| Property | Default / notes |
|----------|-----------------|
| **`crafterq.httpFetch.enabled`** | **`true`**. Set **`false`** to disable **`FetchHttpUrl`** and MCP HTTP client (SSRF-gated outbound). |
| **`crafterq.httpFetch.maxChars`** | Max characters returned by **`FetchHttpUrl`**. |
| **`crafterq.httpFetch.allowedHostSuffixes`** | Comma-separated host suffix allowlist for fetch/MCP (see **`StudioToolOperations`**). |
| **`crafterq.preview.fetch.maxChars`** | Preview HTML fetch cap. |
| **`crafterq.preview.fetch.allowedHosts`** | Extra allowed hosts for preview fetch. |
| **`crafterq.preview.fetch.stripCookieNames`** | Cookie stripping for preview fetch. |
| **`crafterq.preview.fetch.forwardAuthorization`** | **`false`** — whether to forward **`Authorization`** on preview fetch. |
| **`crafterq.mcp.maxResponseChars`** | Max MCP HTTP response body size (default **500000**). |

---

## Expert Skills (`crafterq.expertSkill.*`)

| Property | Role |
|----------|------|
| **`crafterq.expertSkill.embeddingModel`** | Embedding model id for **`QueryExpertGuidance`**. |
| **`crafterq.expertSkill.maxSkills`** | Max distinct expert skills indexed. |
| **`crafterq.expertSkill.maxChunks`** | Chunk count limits per skill. |
| **`crafterq.expertSkill.maxChunkChars`** | Chunk size limits. |

---

## Plugin RAG (`crafterq.pluginRag.*`)

| Property | Role |
|----------|------|
| **`crafterq.pluginRag.mode`** | **`off`** by default; enables bundled plugin RAG kernel when set. |
| **`crafterq.pluginRag.kernelMaxChars`** | Kernel text size. |
| **`crafterq.pluginRag.topK`** | Retrieval **`topK`**. |
| **`crafterq.pluginRag.maxAppendChars`** | Max appended RAG text to prompts. |
| **`crafterq.pluginRag.maxChunkChars`** / **`crafterq.pluginRag.maxChunks`** | Chunking for plugin RAG. |
| **`crafterq.pluginRag.embedBatchSize`** | Embedding batch size. |
| **`crafterq.pluginRag.pluginBuildId`** | Optional build id marker for caches. |

---

## Misc

| Property | Role |
|----------|------|
| **`crafterq.crafterizingPlaybook.path`** | Absolute path to markdown override for **`GetCrafterizingPlaybook`**. |

---

## Implementation Source

Property names and defaults are defined in plugin Groovy under **`authoring/scripts/classes/plugins/org/craftercms/aiassistant/`** (search for **`System.getProperty`** where applicable). Tools-loop **`max_completion_tokens`** vs **`max_tokens`** and optional serialized-payload shrink use **session bundle** keys returned from **`StudioAiLlmRuntime#buildSessionBundle`** (see **`StudioAiLlmKind`**: **`toolsLoopChatPreferMaxCompletionTokens`**, **`toolsLoopChatMaxCompletionOutTokens`**, **`toolsLoopChatMaxWirePayloadChars`**, and [script-llm-bring-your-own-backend.md](script-llm-bring-your-own-backend.md)); core does **not** read vendor-specific env such as **`GROQ_TOOLS_LOOP_*`** — the Groq sample maps those env vars into the bundle in site script. This document is descriptive; behavior is authoritative in code.
