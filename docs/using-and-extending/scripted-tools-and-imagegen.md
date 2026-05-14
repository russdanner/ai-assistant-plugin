# Scripted Site Tools & Script Image Backends

**Audience:** **Integrators** — teams extending Studio with **site sandbox Groovy**: **`InvokeSiteUserTool`** (`user-tools/` + **`registry.json`**) and/or **`script:{id}`** **`GenerateImage`** backends (`imagegen/{id}/generate.groovy`). Covers interfaces, bindings, return shapes, and configuration checklists.

**Related docs:** [configuration-guide.md](configuration-guide.md) (Advanced §9.3) · [studio-plugins-guide.md](studio-plugins-guide.md) (paths, security, beans) · [image-generation.md](image-generation.md) (selector summary) · [llm-configuration.md](llm-configuration.md) (`<imageGenerator>`, merge rules) · Copy-paste examples: [`docs/examples/aiassistant-user-tools/`](../examples/aiassistant-user-tools/).

---

## 1. At a Glance

| Capability | Studio path (site Git sandbox) | How the model calls it |
|------------|--------------------------------|-------------------------|
| **Site user tool** | `config/studio/scripts/aiassistant/user-tools/` + **`registry.json`** | Built-in **`InvokeSiteUserTool`** with **`toolId`** + optional **`args`** |
| **Script image backend** | `config/studio/scripts/aiassistant/imagegen/{id}/generate.groovy` | Built-in **`GenerateImage`** (same tool as the OpenAI Images wire); backend chosen by **`imageGenerator`** = **`script:{id}`** on the agent or chat body |

Both features:

- Run **Groovy in the Studio JVM** with the same effective site and author context as other assistant tools (treat these directories like production code access).
- Live under **`config/studio/scripts/aiassistant/…`** so normal **`install-plugin` / copy-plugin** flows do not wipe them (unlike many `static-assets` paths).

---

## 2. Site User Tools (`InvokeSiteUserTool`)

### 2.1 Layout

| File / directory | Purpose |
|------------------|---------|
| `config/studio/scripts/aiassistant/user-tools/registry.json` | Manifest of tool ids → Groovy filenames |
| `config/studio/scripts/aiassistant/user-tools/*.groovy` | One script per registered tool (filename must match **`script`** / **`file`**) |

Studio reads the registry at configuration path **`/scripts/aiassistant/user-tools/registry.json`** (same folder on disk as above).

### 2.2 `registry.json` Shape

The parser accepts either:

| Root JSON | Parsed as |
|-----------|-----------|
| `{ "tools": [ … ] }` | Preferred — array of tool entries |
| `{ "entries": [ … ] }` | Alternate key for the same array |
| `[ … ]` | Bare array of entries |

Each **entry** (object):

| Field | Required | Description |
|-------|----------|-------------|
| **`id`** | Yes | Wire id the model passes as **`toolId`**. Pattern: `^[a-zA-Z0-9_-]{1,64}$` |
| **`script`** or **`file`** | Yes | Filename under `user-tools/`. Pattern: `^[A-Za-z0-9][A-Za-z0-9_.-]*\.groovy$` |
| **`description`** or **`desc`** | No | Shown in the **`InvokeSiteUserTool`** description sent to the model (truncated in catalog text) |

**Example** (also in-repo under [`examples/aiassistant-user-tools/registry.json`](../examples/aiassistant-user-tools/registry.json)):

```json
{
  "tools": [
    {
      "id": "hello",
      "script": "hello.groovy",
      "description": "Returns a greeting; optional args.name (string)."
    }
  ]
}
```

### 2.3 Model-facing Tool contract (`InvokeSiteUserTool`)

The Spring AI JSON schema requires:

| Argument | Type | Required | Meaning |
|----------|------|----------|---------|
| **`toolId`** | string | Yes | Must equal a **`registry.json`** **`id`** |
| **`args`** | object | No | Arbitrary keys; becomes the Groovy binding **`args`** (`Map`) |

### 2.4 Groovy Script: Compile-time Bindings

The **entire script file** is evaluated once per invocation with **`GroovyShell`**. These variables exist **during evaluation** (same as `StudioAiUserSiteTools.invokeRegisteredTool`):

| Binding | Type | Description |
|---------|------|-------------|
| **`studio`** | `StudioToolOperations` | Same helper surface as built-in CMS tools (content read/write, config reads, etc.). |
| **`args`** | `Map` | Copy of the **`args`** object from the tool call (never null; may be empty). |
| **`toolId`** | `String` | Registered id for this run. |
| **`siteId`** | `String` | Effective site id (`studio.resolveEffectiveSiteId('')`). |
| **`log`** | `org.slf4j.Logger` | Logger for **`StudioAiUserSiteTools`**. |

### 2.5 Groovy Script: Return Value

| Last expression | Server behavior |
|-----------------|-----------------|
| **`Map`** | Returned as-is; if neither **`ok`** nor **`error`** is set, **`ok: true`** is added. Fields **`toolId`**, **`siteUserTool: true`** are added. |
| **Any other type** | Wrapped as `{ ok: true, result: <value>, resultType: "<class>", toolId, siteUserTool: true }`. |

Prefer explicit maps such as **`[ ok: true, message: '…', data: … ]`** or **`[ ok: false, error: true, message: '…' ]`** on failure.

### 2.6 Minimal Example Script

Source: [`docs/examples/aiassistant-user-tools/hello.groovy`](../examples/aiassistant-user-tools/hello.groovy).

```groovy
def name = args?.name?.toString()?.trim() ?: 'author'
[
  ok     : true,
  message: "Hello, ${name}!",
  siteId : siteId,
  hint   : 'Use studio.* helpers like built-in tools.'
]
```

### 2.7 Enabling in the Tool Catalog

- **`InvokeSiteUserTool`** is registered only when **`registry.json`** parses to **at least one** valid entry.
- Built-in allow/deny lists in **`config/studio/scripts/aiassistant/config/tools.json`** apply to other tools; **`InvokeSiteUserTool`** is **exempt from `enabledBuiltInTools` whitelists** but can still be listed under **`disabledBuiltInTools`** if you need to hide it.
- If you have **no** user tools yet, Studio may log a benign **`ContentNotFoundException`** when probing for **`registry.json`**; add an empty manifest (`{ "tools": [] }`) to silence that — see [chat-and-tools-runtime.md](../internals/chat-and-tools-runtime.md).

### 2.8 Security

Anyone who can commit under **`user-tools/`** can run **arbitrary Groovy** as the Studio server. Lock down Git permissions and review changes like application code.

---

## 3. Script Image Backend (`script:{id}`)

### 3.1 When to Use It

Use a script backend when **`GenerateImage`** must call a **non-default** pipeline (internal HTTP service, on-prem model, deterministic placeholder, save to blob storage + return URL, etc.). When **`imageGenerator`** is blank and keys + **`imageModel`** are set, the server uses the **built-in GenerateImage HTTP** path instead — see [image-generation.md](image-generation.md).

### 3.2 Selector & Path

| Agent / POST field | Example | Resolved script path |
|--------------------|---------|-------------------------|
| **`<imageGenerator>`** or **`imageGenerator`** | `script:mygen` | `config/studio/scripts/aiassistant/imagegen/mygen/generate.groovy` |

The **`{id}`** segment after **`script:`** is **normalized to lowercase** for lookup. **`{id}`** must match `^[a-z0-9_-]{1,64}$` (lowercase letters, digits, `_`, `-`).

### 3.3 Script contract

`generate.groovy` must **evaluate to** one of:

| Evaluated value | Meaning |
|-----------------|---------|
| **`Closure`** | Invoked as **`closure.call(inputMap, contextMap)`** and must return a **`Map`**. |
| **Object with `generate(Map, Map)`** | Wrapped as a closure internally. |

**Not supported:** a bare **`Map`** as the top-level script result (must be closure or typed object).

### 3.4 Compile-time Bindings (While the Script Is Evaluated)

These exist when **`GroovyShell.evaluate`** runs **`generate.groovy`** (`StudioAiScriptImageGenLoader`):

| Binding | Description |
|---------|-------------|
| **`log`** | SLF4J logger for the loader. |
| **`siteId`** | Effective site id. |
| **`imageGenId`** | Normalized **`{id}`** (lowercase). |
| **`scriptPath`** | Studio-relative path, e.g. **`/scripts/aiassistant/imagegen/mygen/generate.groovy`**. |
| **`studio`** | `StudioToolOperations`. |

### 3.5 Runtime Arguments to the Closure

Each **`GenerateImage`** tool call invokes your closure with:

| Parameter | Type | Content |
|-----------|------|---------|
| **First (`input`)** | `Map` | Tool arguments from the model (see §3.6). |
| **Second (`context`)** | `Map` | Snapshot from **`StudioAiImageGenContext.asMap()`** — keys below. |

**`context` map keys:**

| Key | Description |
|-----|-------------|
| **`siteId`** | Effective site. |
| **`llmNormalized`** | Normalized chat LLM id for the session (image wire may ignore). |
| **`defaultImageModel`** | Agent/request default image model id. |
| **`imagesApiKey`** | OpenAI Images API key when the wire would use it — may be **empty** for pure script backends. |
| **`imagesGenerationsHttpUrl`** | Resolved **`POST …/v1/images/generations`** URL when applicable. |
| **`generatorSpec`** | Raw **`imageGenerator`** string (e.g. **`script:mygen`**). |

### 3.6 `GenerateImage` Tool Input (`input` Map)

Aligned with the built-in JSON schema ( **`prompt`** required):

| Field | Required | Description |
|-------|----------|-------------|
| **`prompt`** | Yes | Image description. |
| **`size`** | No | Aspect / size preset (meaningful mainly on the OpenAI wire). |
| **`quality`** | No | GPT Image quality when on the wire. |
| **`model`** | No | Per-call model override when using the OpenAI Images wire; script backends may read it or ignore it. |

### 3.7 Return `Map` Shape (Tool Result)

Match the historical **`GenerateImage`** tool result so the UI and orchestration behave correctly. The built-in HTTP implementation sets at minimum:

| Field | When |
|-------|------|
| **`ok`: true** | Success path. |
| **`tool`** | e.g. **`GenerateImage`**. |
| **`model`** | Model id used (or a logical label for your backend). |
| **`url`** | Public or `data:image/...;base64,...` URL the author UI can render. |
| **`b64_json`** | Optional; if you only have base64, you may follow the built-in pattern or set **`url`** to a data URL (see the built-in wire in **`OpenAiCompatibleImageGenerator`** in plugin sources). |
| **`revised_prompt`** | Optional provider echo. |
| **`error`: true** + **`message`** | Failure path. |

See **`StudioAiImageGenerator`** in the plugin sources (`imagegen/StudioAiImageGenerator.groovy`) for the interface contract comment.

### 3.8 Minimal Stub (Returns Error Until Implemented)

```groovy
// evaluate to a Closure: (Map input, Map ctx) -> Map
{ Map input, Map ctx ->
  def p = input?.prompt?.toString()?.trim()
  if (!p) {
    return [error: true, message: 'Missing prompt']
  }
  [
    ok     : true,
    tool   : 'GenerateImage',
    model  : ctx.defaultImageModel ?: 'script-stub',
    message: "Script backend '${ctx.generatorSpec}' received prompt chars=${p.length()} (replace with real generation).",
    url    : 'https://www.craftercms.org/static/images/craftercms-v3-blue.png'
  ]
}
```

Replace the placeholder **`url`** with your pipeline’s real URL or data URL.

### 3.9 Example: Nano Banana 2 (Gemini 3.1 Flash Image)

**Nano Banana 2** is the nickname used in the press for **Google Gemini 3.1 Flash Image** (typical preview model id **`gemini-3.1-flash-image-preview`** — confirm in Google AI Studio).

| Item | Value |
|------|--------|
| **Source** | [`docs/examples/aiassistant-imagegen/nano-banana-2/generate.groovy`](../examples/aiassistant-imagegen/nano-banana-2/generate.groovy) |
| **Copy to** | **`config/studio/scripts/aiassistant/imagegen/nano-banana-2/generate.groovy`** |
| **Agent / POST** | **`<imageGenerator>script:nano-banana-2</imageGenerator>`** |
| **Keys** | **`GEMINI_API_KEY`** or **`GOOGLE_API_KEY`** (or JVM **`crafter.gemini.apiKey`** / **`crafter.google.apiKey`**) — same as Studio’s **`llm=gemini`** path |
| **Default model** | Tool **`model`** → **`<imageModel>`** → env **`GEMINI_NANO_BANANA_MODEL`** → **`gemini-3.1-flash-image-preview`** |

The script POSTs **`generateContent`** with **`responseModalities`** including **IMAGE** and returns a **`data:`** **`url`** for preview. Details and env overrides are in the script header comments.

### 3.10 Caching

Compiled closures are cached **per site + id** with a SHA-256 of the script text. Editing **`generate.groovy`** on disk invalidates the cache when the file content hash changes.

---

## 4. Related: Script LLM (`llm/{id}/runtime.groovy`)

**Chat** backends live under **`config/studio/scripts/aiassistant/llm/{id}/`** (not `user-tools/` or `imagegen/`). They implement **`StudioAiLlmRuntime`** or the documented **Map** bundle contract — see **[llm-configuration.md](llm-configuration.md)** and the plugin **`docs/examples/aiassistant-llm/`** tree.

For a **real-world Groovy `StudioAiLlmRuntime` class** that builds the **full** Spring AI session (your vendor’s base URL + API key for Studio’s **tools-loop** chat, **no** delegation to built-in vendor runtimes), see **[script-llm-bring-your-own-backend.md](script-llm-bring-your-own-backend.md)** and the sample **`docs/examples/aiassistant-llm/byo-openai-compat/runtime.groovy`** (folder name is the example **script id**, not a vendor claim). For **Groq**, see **`docs/examples/aiassistant-llm/groq/runtime.groovy`** in the same doc.

---

## 5. Side-by-side Comparison

| Topic | User tool (`user-tools/`) | Script image (`imagegen/{id}/`) |
|-------|---------------------------|----------------------------------|
| **Manifest** | **`registry.json`** listing ids | None — presence of **`generate.groovy`** under **`{id}/`** |
| **Id charset** | `id`: letters, digits, `_`, `-` (case-sensitive match to **`toolId`**) | Directory name: **lowercase** `a-z`, digits, `_`, `-` |
| **Entrypoint** | Whole **`.groovy`** file evaluated; last value = result | File must evaluate to **closure** or **`generate` method** |
| **Primary bindings** | `studio`, `args`, `toolId`, `siteId`, `log` | At compile: `studio`, `log`, `siteId`, `imageGenId`, `scriptPath`; at run: **`(input, context)`** maps |
| **Model tool** | **`InvokeSiteUserTool`** | **`GenerateImage`** (unchanged wire name) |

---

## 6. Configuration Checklist

| Step | User tools | Script image |
|------|------------|----------------|
| 1 | Add **`registry.json`** + scripts under **`user-tools/`** | Add **`imagegen/{id}/generate.groovy`** |
| 2 | Commit to site sandbox; refresh Studio config if your process requires it | Same |
| 3 | Ensure agents have **tools enabled** so **`InvokeSiteUserTool`** appears | Set **`<imageGenerator>script:{id}</imageGenerator>`** (and **`imageModel`** if you still use wire defaults elsewhere) |
| 4 | Smoke-test with a chat prompt that asks the model to call the tool | Ask for an image; confirm **`GenerateImage`** hits your script |

---

## 7. Source References (Maintainers)

| Concern | Class |
|---------|--------|
| User tool registry + invoke | `plugins.org.craftercms.aiassistant.tools.StudioAiUserSiteTools` |
| Image script load / cache | `plugins.org.craftercms.aiassistant.imagegen.StudioAiScriptImageGenLoader` |
| Backend resolution | `plugins.org.craftercms.aiassistant.imagegen.StudioAiImageGeneratorFactory` |
| Tool schemas (`InvokeSiteUserTool`, `GenerateImage`) | `plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools` |
