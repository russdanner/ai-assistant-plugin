# Using the AI Assistant & Developing Extensions

**Install:** **[`installation.md`](installation.md)**. **Admins (configure the site):** **[`configuration-guide.md`](configuration-guide.md)**. Further site work does not require changes under **`sources/`** unless you are developing the plugin. **Product requirements:** **[`product-requirements.md`](product-requirements.md)**. **Implementation contracts:** **[`../internals/spec.md`](../internals/spec.md)** and **[`studio-plugins-guide.md`](studio-plugins-guide.md)** (build and packaging invariants).

## Guides (Table)

| Document | What It Covers |
|----------|----------------|
| [installation.md](installation.md) | Install from Studio UI (with screenshots), CLI, Marketplace API, **`install-plugin.sh`**, build-before-install |
| [configuration-guide.md](configuration-guide.md) | **Admins — start here** — **Basic:** `ui.xml`, plugin id, agents, keys, form pipeline, autonomous checklist; **TinyMCE last** (**§8**) within **§1–§8**. **`§1e`:** **`studio-ui.json`** (toolbar/sidebar toggles, XB image augmentation scope, bulk form field). **Advanced:** [site scripts](configuration-guide.md#cg-adv) — prompts, `tools.json`, MCP, user tools, script LLM. **Visual:** [Screenshots](configuration-guide.md#cg-screenshots). |
| [helper-widget.md](helper-widget.md) | Helper **`ui.xml`** snippet and “component not found” checklist |
| [autonomous-assistants-widget.md](autonomous-assistants-widget.md) | Optional autonomous widget — placement and links to spec |
| [tinymce-integration.md](tinymce-integration.md) | **Optional RTE** — TinyMCE **`tinymceOptions`**, toolbar ids, **`craftercms_aiassistant`** config |
| [llm-configuration.md](llm-configuration.md) | **`<llm>`** — **tools-loop chat / Claude / `script:` first**; env + `ui.xml`; optional hosted-only adapter; **`script:{id}`** |
| [image-generation.md](image-generation.md) | **Pluggable `GenerateImage`** — wire vs **`script:{id}`**, **`imageGenerator`** / **`imageModel`**, site overrides |
| [studio-aiassistant-jvm-parameters.md](studio-aiassistant-jvm-parameters.md) | **JVM-only** `-D` / `System.getProperty` tuning (timeouts, fetch/MCP caps, key fallbacks) |
| [scripted-tools-and-imagegen.md](scripted-tools-and-imagegen.md) | **Integrators** — **`InvokeSiteUserTool`** + **`imagegen/{id}/generate.groovy`**: bindings, contracts, examples, checklists |
| [studio-plugins-guide.md](studio-plugins-guide.md) | Descriptor, paths, Rollup, auth; **`user-tools/`**; **`imagegen/`**; **`tools.json`**; **`prompts/`**; script LLM paths |
| [product-requirements.md](product-requirements.md) | **Product / review** — obligations the plugin **must** satisfy for authors, admins, and integrators. Engineering contracts: **[`spec.md`](../internals/spec.md)**. |

## Internals

Contracts, streaming design, and server behavior: **[`docs/internals/`](../internals/README.md)**.
