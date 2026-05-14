# Pluggable Image Generation (`GenerateImage`)

The **GenerateImage** tool is not hard-wired to a single vendor. The server picks a backend from the agent / request and site scripts.

**Integrator guide** (script `generate.groovy` closure, `context` map, return shape, **`InvokeSiteUserTool`**): [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md)

## Options (summary)

| Mechanism | When it applies |
|-----------|------------------|
| **Default wire** | Blank **`imageGenerator`** + default **GenerateImage** HTTP URL + API key + **`imageModel`** (e.g. GPT Image ids) |
| **`none` / `off` / `disabled`** | Tool is not registered |
| **`script:{id}`** | Site Groovy: **`config/studio/scripts/aiassistant/imagegen/{id}/generate.groovy`** |

Configure per agent in **`/config/studio/ui.xml`**: **`<imageModel>`** (default model on the wire path), **`<imageGenerator>`** (backend selector). The stream/chat POST body may send **`imageModel`** and **`imageGenerator`**; when **`siteId`** + **`agentId`** are present, missing values can be merged from the matching **`<agent>`** row (see server merge behavior in [llm-configuration.md](llm-configuration.md)).

**Reference script (Nano Banana 2 / Gemini 3.1 Flash Image):** copy [`docs/examples/aiassistant-imagegen/nano-banana-2/generate.groovy`](../examples/aiassistant-imagegen/nano-banana-2/generate.groovy) to **`/scripts/aiassistant/imagegen/nano-banana-2/generate.groovy`** and set **`script:nano-banana-2`** — see [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md) (**§3.9**).

## Related Site Configuration

| Path / file | Purpose |
|-------------|---------|
| **`/scripts/aiassistant/imagegen/{id}/generate.groovy`** | Custom backend implementation |
| **`/scripts/aiassistant/config/tools.json`** | Optional allow/deny list for **built-in** tools (including hiding **GenerateImage**) |
| **`/scripts/aiassistant/prompts/*.md`** | Override tool-related prompt snippets per site |

Full conventions (paths, env for Images base URL, classpath vs sandbox): **[studio-plugins-guide.md](studio-plugins-guide.md)** (sections on **imagegen**, **tools.json**, **prompts**).
