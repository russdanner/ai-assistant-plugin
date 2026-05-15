# Supported LLMs (`<llm>`) — IDs, Configuration, and Behavior

Defines **`<llm>`** identifiers, env/XML keys, merge rules, and the provider capability matrix. Keep this file and **[`spec.md`](../internals/spec.md)** aligned when those contracts change.

**For site admins:** [configuration-guide.md](configuration-guide.md)  
**For CMS tools, SSE, REST bodies, expert skills, MCP, and troubleshooting:** [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md)  
**For script LLMs and `user-tools/`:** [studio-plugins-guide.md](studio-plugins-guide.md) · **Script LLM — full session bundle (BYO backend):** [script-llm-bring-your-own-backend.md](script-llm-bring-your-own-backend.md)  
**For pluggable image backends (`imageGenerator`, `imagegen/` scripts, site overrides):** [image-generation.md](image-generation.md) · **Integrators:** [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md) (Groovy closure, `context` map, return shape)  
**For `ui.xml` contracts, macros, and REST:** [spec.md](../internals/spec.md) · **Doc index:** [README.md](../README.md)

---

## Summary Table

Rows list **supported** backends. **Hosted-only** SaaS adapters (**`crafterQ`**, **`aiassistant`**, **`hostedchat`**, …) are **not** supported — **`StudioAiLlmKind.normalize`** throws (**HTTP 400**). **`ai-assistant`** is **not** a valid `<llm>` value either (that string names the Studio plugin / form control path, not a model provider); use **`openAI`**, **`claude`**, etc.

| `<llm>` wire value | Aliases (normalized) | Required configuration | Optional `ui.xml` / env | What you get |
|--------------------|----------------------|-------------------------|-------------------------|--------------|
| **`openAI`** | `openai`, `open-ai` | **API key:** host env **`OPENAI_API_KEY`** (recommended). | **`<llmModel>`** (chat model id). **`<imageModel>`** when the model should call **GenerateImage**. **`<openAiApiKey>`** — *testing only* when no env key. | **CMS tools**, **GenerateImage** (when `imageModel` + key allow), **`<expertSkill>`** → **QueryExpertGuidance**. |
| **`xAI`** | `x-ai`, `grok` | **`XAI_API_KEY`** | **`XAI_OPENAI_BASE_URL`** (tools-loop chat base URL). **`<llmModel>`**. Same stack as **`openAI`**. | Same tool surface as **OpenAI** row. |
| **`deepSeek`** | `deep-seek` | **`DEEPSEEK_API_KEY`** | **`DEEPSEEK_OPENAI_BASE_URL`** (optional). **`<llmModel>`**. | Same tool surface as **OpenAI** row. |
| **`llama`** | `ollama`, `meta-llama`, `meta_llama` | Often **`LLAMA_API_KEY`** (Ollama may accept a placeholder). | **`LLAMA_OPENAI_BASE_URL`** or **`OLLAMA_OPENAI_BASE_URL`**. **`<llmModel>`**. | Same tool surface as **OpenAI** row. |
| **`genesis`** / **`gemini`** | `gemini`, `google`, `google-genai`, `google_genai` | **`GEMINI_API_KEY`** or **`GOOGLE_API_KEY`** | **`GEMINI_OPENAI_BASE_URL`** / **`GOOGLE_GENAI_OPENAI_BASE_URL`**. **`<llmModel>`**. | Same tool surface as **OpenAI** row. |
| **`claude`** | `anthropic` | **`ANTHROPIC_API_KEY`** | **`<llmModel>`**. **`<openAiApiKey>`** — *testing only* for Anthropic when no **`ANTHROPIC_API_KEY`** (see runtime doc). | **CMS tools** via Spring AI **Anthropic** (not the OpenAI RestClient loop). **GenerateImage** / embeddings that still use OpenAI key material are described in the runtime doc. **Expert skills** when configured. |
| **`script:{id}`** | — | Site Groovy under **`config/studio/scripts/aiassistant/llm/{id}/runtime.groovy`** (or `llm.groovy`) implementing **`StudioAiLlmRuntime`** or the documented **Map** bundle contract. | Bundle chooses **tools-loop** vs Anthropic-style transport. | **Configurable** by the script (CMS tools, custom behavior). |

---

## Configuration Examples (`ui.xml`)

Paths are under site configuration (commonly **`/config/studio/ui.xml`**). The widget registers **`craftercms.components.aiassistant.Helper`** with a **`configuration`** → **`agents`** → **`agent`** tree.

**Always pick an `<llm>` from the supported table above.** Configure provider keys on the Studio host per vendor column.

### Recommended: OpenAI with CMS Tools (+ Optional Image)

```xml
<widget id="craftercms.components.aiassistant.Helper">
  <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
  <configuration>
    <agents>
      <agent>
        <!-- Stable id for stream/chat agentId + ui.xml merge (XML tag name is legacy: crafterQAgentId) -->
        <crafterQAgentId>00000000-0000-4000-8000-000000000002</crafterQAgentId>
        <label>OpenAI authoring</label>
        <llm>openAI</llm>
        <llmModel>gpt-4o-mini</llmModel>
        <imageModel>gpt-image-1-mini</imageModel>
      </agent>
    </agents>
  </configuration>
</widget>
```

- Set **`OPENAI_API_KEY`** on the Studio host (recommended). Do **not** commit keys to Git.
- **`imageModel`** is required for **GenerateImage** on the default wire; there is no silent default in site config.

### Claude (Anthropic)

```xml
<agent>
  <crafterQAgentId>00000000-0000-4000-8000-000000000003</crafterQAgentId>
  <label>Claude authoring</label>
  <llm>claude</llm>
  <llmModel>claude-3-5-sonnet-20241022</llmModel>
</agent>
```

Set **`ANTHROPIC_API_KEY`** on the Studio host.

### xAI (Grok) — Env for Key and Optional Base URL

```xml
<agent>
  <crafterQAgentId>00000000-0000-4000-8000-000000000004</crafterQAgentId>
  <label>xAI</label>
  <llm>xAI</llm>
  <llmModel>grok-2-latest</llmModel>
</agent>
```

Set **`XAI_API_KEY`**. Optionally set **`XAI_OPENAI_BASE_URL`** if your deployment uses a non-default tools-loop chat base.

### Ollama / Local Llama Tools-loop Endpoint

```xml
<agent>
  <crafterQAgentId>00000000-0000-4000-8000-000000000005</crafterQAgentId>
  <label>Local Ollama</label>
  <llm>llama</llm>
  <llmModel>llama3.2</llmModel>
</agent>
```

Set **`LLAMA_OPENAI_BASE_URL`** or **`OLLAMA_OPENAI_BASE_URL`** (e.g. `http://127.0.0.1:11434`). **`LLAMA_API_KEY`** may be a placeholder for local Ollama.

### DeepSeek

```xml
<agent>
  <crafterQAgentId>00000000-0000-4000-8000-000000000006</crafterQAgentId>
  <label>DeepSeek</label>
  <llm>deepSeek</llm>
  <llmModel>deepseek-chat</llmModel>
</agent>
```

Set **`DEEPSEEK_API_KEY`**. Optional **`DEEPSEEK_OPENAI_BASE_URL`**.

### Gemini / Google GenAI (Tools-loop Wire)

```xml
<agent>
  <crafterQAgentId>00000000-0000-4000-8000-000000000007</crafterQAgentId>
  <label>Gemini</label>
  <llm>gemini</llm>
  <llmModel>gemini-2.0-flash</llmModel>
</agent>
```

Set **`GEMINI_API_KEY`** or **`GOOGLE_API_KEY`**. Optional **`GEMINI_OPENAI_BASE_URL`** / **`GOOGLE_GENAI_OPENAI_BASE_URL`**.

### Script LLM

```xml
<agent>
  <crafterQAgentId>00000000-0000-4000-8000-000000000008</crafterQAgentId>
  <label>Custom backend</label>
  <llm>script:mybackend</llm>
</agent>
```

Wire normalizes to **`scriptLlm:mybackend`**. Implement **`config/studio/scripts/aiassistant/llm/mybackend/runtime.groovy`** per **[studio-plugins-guide.md](studio-plugins-guide.md)**.

---

## Omitted `<llm>` And POST Body

The React client **does not** send **`llm`** on the stream/chat JSON when the agent has no **`<llm>`** in `ui.xml`. The server **does not** infer a default adapter: after optional **ui.xml merge** (below), **`StudioAiLlmKind.normalize`** requires a **non-blank**, **recognized** `llm` string. Missing, blank-only, invalid **`script:…`** ids, unknown values, or legacy hosted-only ids produce **`IllegalArgumentException`** → **HTTP 400** (JSON **`message`**) on **`/ai/stream`** and **`/ai/agent/chat`**.

When **`siteId`** + **`agentId`** are present and the matching **`<agent>`** defines **`<llm>`**, the server may **copy `llm` into the POST body** before normalize (same merge pass as image / model fields). If there is still no effective `llm`, the request fails until you set **`<llm>`** in `ui.xml` or send **`llm`** on the body.

**Always set `<llm>` explicitly** — for almost all Studio authoring sites, use **`openAI`**, **`claude`**, or another **tool-capable** value from the summary table so routing matches intent.

---

## Per-provider Notes

### Tools-loop Chat Family (`openAI`, `xAI`, `deepSeek`, `llama`, `gemini` / `genesis`)

- **Transport:** Spring AI **`OpenAiChatModel`** + **RestClient** **`/v1/chat/completions`** native tool loop (`AiOrchestrationTools`).
- **Image generation:** **`<imageModel>`** for the default **GenerateImage** wire (e.g. **`gpt-image-1`**). **`<imageGenerator>`** selects **`script:{id}`**, **`none`**, or default wire.

### `claude`

- **Transport:** Spring AI **`AnthropicChatModel`** — tools run inside Spring AI’s Anthropic integration, not the OpenAI RestClient loop.

### `script:{id}` (Site Groovy LLM)

- **Wire:** **`<llm>script:mybackend</llm>`** → **`scriptLlm:mybackend`**.
- **Id pattern:** `{id}` = `a-z`, `0-9`, `_`, `-`, max **64** chars.
- Full contract: [studio-plugins-guide.md](studio-plugins-guide.md) and **`docs/examples/aiassistant-llm/demo/runtime.groovy`**. **Full vendor replacement (Groovy class, no built-in runtime delegation):** [script-llm-bring-your-own-backend.md](script-llm-bring-your-own-backend.md). **Groq (tools-loop):** **`docs/examples/aiassistant-llm/groq/runtime.groovy`**.

---

## Agent XML Fields (cross-LLM)

| Field | Applies to | Purpose |
|-------|------------|---------|
| **`<llm>`** | All | Selects backend; see summary table. Legacy hosted ids (**`crafterQ`**, **`aiassistant`**, …) **fail normalize**. **`ai-assistant`** is invalid (plugin name, not a provider). |
| **`<llmModel>`** | Tool-capable rows | Provider chat model id when the provider uses it. |
| **`<imageGenerator>`** | **GenerateImage** | Blank = default wire when configured; **`none`**/**`off`**/**`disabled`**; **`script:{id}`** for site Groovy under **`/scripts/aiassistant/imagegen/{id}/`**. |
| **`<imageModel>`** | **GenerateImage** (wire path) | Required when the model should call **GenerateImage** on the default wire. |
| **`<crafterQAgentId>`** | All agents that participate in stream **`agentId`** / dedupe | Stable UUID string sent as **`agentId`** on **`/ai/stream`** and **`/ai/agent/chat`**; used to match the correct **`<agent>`** row when merging missing **`llm`** / model fields from **`/ui.xml`**. Name is historical — **not** a CrafterQ SaaS requirement. |
| **`<openAiApiKey>`** | Testing | Per-agent key when no **env** key for the **target** provider; discouraged in production. |
| **`<enableTools>`** | Tool-capable | When **`false`**, CMS tools are off for that agent (subject to per-request **`omitTools`**). |
| **`<expertSkill>`** | Tools-loop + Claude (tools on) | Markdown URL skills → **QueryExpertGuidance**. |

---

## REST / Stream Body Keys (Reference)

The widget mirrors **`ui.xml`** onto **`POST …/ai/stream`** and **`…/ai/agent/chat`**. Common keys: **`llm`**, **`llmModel`**, **`imageModel`**, **`imageGenerator`**, **`openAiApiKey`**, **`agentId`**, **`expertSkills`**, preview **`contentPath`** / **`contentTypeId`**, **`omitTools`**, **`enableTools`**. Full list: [chat-and-tools-runtime.md § REST body](../internals/chat-and-tools-runtime.md#rest-body-advanced).

When **`siteId`** + **`agentId`** are present, the server may **merge** missing **`llm`**, **`llmModel`**, **`imageModel`**, and **`imageGenerator`** from the matching **`<agent>`** in site **`/ui.xml`** before orchestration.

---

## Autonomous Widget (`<llm>` There)

**`AutonomousAssistants`** agents use **`openAI`**, **`xAI`**, **`deepSeek`**, **`llama`**, **`genesis`** / **`gemini`** for steps (**tools-loop** stack). **`claude`** is **not** supported for autonomous runs. Default **`llm`** for new autonomous definitions in code is **`openAI`**. Details: [chat-and-tools-runtime.md § Autonomous](../internals/chat-and-tools-runtime.md#autonomous-assistants) and [spec.md](../internals/spec.md).
