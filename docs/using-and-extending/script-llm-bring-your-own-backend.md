# Script LLM: Bring Your Own Backend (Full `StudioAiLlmRuntime`)

Site script LLMs (**`<llm>script:{id}</llm>`**) are **not** required to call built-in **`OpenAiSpringAiLlmRuntime`**, **`AnthropicSpringAiLlmRuntime`**, or **`ExpertApiLlmRuntime`**. They are **complete replacements**: your Groovy constructs the same **session bundle** those classes return — **`chatClient`**, **`chatModel`**, **`tools`**, **`useTools`**, **`studioOps`**, plus transport hints the orchestration reads.

**Related:** [llm-configuration.md](llm-configuration.md) (`script:{id}`), [studio-plugins-guide.md](studio-plugins-guide.md), [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md) (bindings / `llmId`). The small **Map** proxy demo in **`docs/examples/aiassistant-llm/demo/runtime.groovy`** only shows wiring; it is **not** the product contract for how you must implement scripts.

**Spring AI** is **not** tied to the OpenAI vendor: it is a multi-provider integration layer (Anthropic, Ollama, Azure, OpenAI, and others). This plugin sometimes uses types from the **`spring-ai-openai`** artifact because that module implements a **widely reused chat-completions JSON API** many hosts expose — the **`OpenAi*`** Java names reflect that **HTTP wire**, not “your stack must be OpenAI.”

**Tools-compatible LLM:** A chat host whose HTTP API matches what Studio’s **native CMS tools** path expects (the same request/response shape the built-in **`openAI`** row uses through Spring **`OpenAiApi`**). Groq, xAI, and others can be **different vendors**; env names such as **`SCRIPT_LLM_OPENAI_COMPAT_BASE_URL`** are fixed plugin conventions, not a claim your backend is OpenAI’s product.

## What You Must Return

Implement **`StudioAiLlmRuntime`** (or a **Map** with **`buildSessionBundle`** — see **`StudioAiScriptLlmLoader`**) so **`buildSessionBundle(StudioAiRuntimeBuildRequest req)`** returns a **non-null** `Map` aligned with **`StudioAiLlmRuntime`** Javadoc and the built-in runtimes:

| Key | Role |
|-----|------|
| **`chatClient`** | Spring AI **`ChatClient`** (typically **`DefaultChatClientBuilder`** around your **`ChatModel`**). |
| **`chatModel`** | Spring AI **`ChatModel`** for your provider. |
| **`tools`** | Built with **`AiOrchestrationTools.build(...)`** when CMS tools should be available (same arguments pattern as **`OpenAiSpringAiLlmRuntime`** / **`AnthropicSpringAiLlmRuntime`**). |
| **`useTools`** | Mirrors **`req.enableTools`**. |
| **`studioOps`** | Pass through **`req.studioOps`**. |
| **`toolsLoopChatApiKey`** | API key the **native tools REST loop** uses toward **`/v1/chat/completions`** on your chat host. |
| **`toolsLoopChatBaseUrl`** + **`resolvedChatModel`** | When both are set on a **script** session, orchestration uses the **same tools-loop** as the built-in **`openAI`** row (see **`StudioAiLlmKind#useToolsLoopChatRestClient`**). |
| **`nativeToolTransport`** | Optional override: **`toolsLoopWire`** or **`anthropic`** (see **`StudioAiLlmKind`**). |
| **`toolsLoopChatPreferMaxCompletionTokens`** | Optional **`boolean`**: when true, tools-loop and simple wire completions send **`max_completion_tokens`** instead of **`max_tokens`** on **`/v1/chat/completions`**. Hosts that only accept the newer field (for example some OpenAI-compatible APIs) should set this from script. |
| **`toolsLoopChatMaxCompletionOutTokens`** | Optional positive **`int`**: caps the completion output budget for that session’s tools-loop and simple wire completions toward your chat host. |
| **`toolsLoopChatMaxWirePayloadChars`** | Optional non-negative **`int`**: when **`> 0`**, serialized tools-loop JSON (messages + tools) is shrunk before each POST until under this character budget (helps strict TPM / payload limits). **`0`** = disabled. |

**Note:** **`StudioAiScriptLlmContainerRuntime`** overwrites **`bundle.llm`** with **`scriptLlm:{id}`** after your script returns — do not rely on **`llm`** inside the map for transport detection; use **`nativeToolTransport`** / wire fields as needed.

## Example: BYO Tools-compatible Chat Host

**Source:** [`docs/examples/aiassistant-llm/byo-openai-compat/runtime.groovy`](../examples/aiassistant-llm/byo-openai-compat/runtime.groovy)

Copy to **`config/studio/scripts/aiassistant/llm/byo-openai-compat/runtime.groovy`** and set **`<llm>script:byo-openai-compat</llm>`**.

Secrets and base URL are **yours** (any **tools-loop** chat vendor), not necessarily **`OPENAI_API_KEY`** / OpenAI’s default host:

| Variable | Purpose |
|----------|---------|
| **`SCRIPT_LLM_OPENAI_COMPAT_BASE_URL`** | Host-only API base (no trailing **`/v1`**). |
| **`SCRIPT_LLM_API_KEY`** | Bearer/API key for that host. |
| **`<llmModel>`** / POST **`llmModel`** | Chat model id forwarded as **`req.openAiModelParam`**. |

**GenerateImage** and expert embeddings still use the Studio **`OPENAI_API_KEY`** path where the built-in tool stack expects it — configure that separately if authors need images or expert-vector tools.

Add the optional **`toolsLoopChat*`** session-bundle keys from the table above when your host requires **`max_completion_tokens`** or a serialized tools-loop size cap; the sample’s header comments note the same.

## Example: Groq (Alternative Vendor)

**Source:** [`docs/examples/aiassistant-llm/groq/runtime.groovy`](../examples/aiassistant-llm/groq/runtime.groovy)

Copy to **`config/studio/scripts/aiassistant/llm/groq/runtime.groovy`** and set **`<llm>script:groq</llm>`** (folder name under **`llm/`** must match the id after **`script:`**).

| Variable | Required | Purpose |
|----------|----------|---------|
| **`GROQ_API_KEY`** | Yes (typical) | [Groq API key](https://console.groq.com/keys) (`gsk_…`). |
| **`GROQ_OPENAI_COMPAT_BASE_URL`** | No | Defaults to **`https://api.groq.com/openai`** (host only, no trailing **`/v1`**). |
| **`SCRIPT_LLM_*`** | No | Same overrides as **`byo-openai-compat`** if you prefer generic env names. |
| **`GROQ_TOOLS_LOOP_MAX_COMPLETION_TOKENS`** | No | **Read only in the Groq sample script** (not by core): mapped into **`toolsLoopChatMaxCompletionOutTokens`** and used together with **`toolsLoopChatPreferMaxCompletionTokens: true`** (default cap **8192** when unset). Tunes Groq **`max_completion_tokens`** for tools-loop and simple wire completions. |
| **`GROQ_TOOLS_LOOP_MAX_WIRE_CHARS`** | No | **Read only in the Groq sample script**: mapped into **`toolsLoopChatMaxWirePayloadChars`** (default **56000** when unset). Triggers generic wire shrink when the serialized tools-loop JSON exceeds the budget. |
| **`<llmModel>`** / POST **`llmModel`** | Yes (unless you set **`GROQ_LLM_MODEL`** / **`SCRIPT_LLM_MODEL`**) | **Groq** chat model id (this sample does not hardcode a default — Groq rotates model ids). See [Groq models](https://console.groq.com/docs/models). |

Same **`OpenAiApi` + `OpenAiChatModel`** types from Spring AI’s **`spring-ai-openai`** module as **`byo-openai-compat`** (one HTTP client implementation, usable against **any** compatible base URL — here Groq’s). **`GROQ_API_KEY`** and **`llmModel`** are **Groq** credentials and **Groq** model strings.

**Example `llmModel`:** `meta-llama/llama-4-scout-17b-16e-instruct` — set on the agent, or export **`GROQ_LLM_MODEL`** / **`SCRIPT_LLM_MODEL`** with the same value for a site-wide default. Confirm the id is still listed under [Groq models](https://console.groq.com/docs/models) before relying on it in production.

When **`script:groq`** runs **native CMS tools**, the sample returns **`toolsLoopChatPreferMaxCompletionTokens: true`** plus **`toolsLoopChatMaxCompletionOutTokens`** / **`toolsLoopChatMaxWirePayloadChars`** (from optional **`GROQ_TOOLS_LOOP_*`** env in the script, or your own values) so orchestration matches Groq’s **`max_completion_tokens`** expectations and can shrink oversized tool-loop payloads. Any other script LLM can set the same bundle keys for a non-Groq host without core reading vendor-specific environment variables.

## Anthropic-style Session

To replace **`<llm>claude</llm>`** entirely in a script, build **`AnthropicApi`** + **`AnthropicChatModel`** + **`DefaultChatClientBuilder`** the same way **`AnthropicSpringAiLlmRuntime`** does, return **`nativeToolTransport: 'anthropic'`**, and omit **`toolsLoopChatBaseUrl`** (or leave it null). Do not call **`AnthropicSpringAiLlmRuntime.INSTANCE`** unless you intentionally want that coupling.

## Other Vendors (Same Tools-loop)

Any host that exposes the same **`/v1/chat/completions`** (and related streaming) surface Studio expects for the **tools loop** can follow the **`byo-openai-compat`** sample (fully custom base URL + key; **script id** only) or adapt the **`groq`** sample as a template.
