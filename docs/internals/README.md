# Plugin Internals

Documentation here is aimed at **maintainers**, **integrators**, and anyone debugging **server-side behavior**, contracts, and orchestration—not at day-to-day site configuration (see **[`docs/using-and-extending/`](../using-and-extending/README.md)** for that).

## Documents

| Document | What It Covers |
|----------|----------------|
| [**spec.md**](spec.md) | **Official requirements & mechanics specification** — product surfaces, `ui.xml` / agent contracts, form engine, macros, autonomous widget, REST contracts, **`studio-ui.json`** runtime flags (**[#studio-ui-flags-studio-uijson](spec.md#studio-ui-flags-studio-uijson)**); must stay aligned with code. **Product requirements:** [**product-requirements.md**](../using-and-extending/product-requirements.md) |
| [**stream-endpoint-design.md**](stream-endpoint-design.md) | SSE stream (and related) contract, CrafterQ vs tool-capable LLMs on the wire, classpath notes |
| [**chat-and-tools-runtime.md**](chat-and-tools-runtime.md) | CMS tool wiring, CrafterQ bearer/auth, API tools, expert skills, SSE/stream behavior, REST body fields, troubleshooting, **MCP Streamable HTTP client** |
| [**studio-aiassistant-jvm-parameters.md**](../using-and-extending/studio-aiassistant-jvm-parameters.md) | JVM **`-D`** / `System.getProperty` tuning (timeouts, HTTP/MCP caps, optional key/model defaults) |
| [**reference-spring-ai-completions-with-tools.md**](reference-spring-ai-completions-with-tools.md) | Archived Spring AI pattern reference (may diverge from current wiring) |
| [**maintainer-review-checklist.md**](maintainer-review-checklist.md) | **Lessons learned** — React/TS (localStorage + SSE, bounded logs, Studio user identity, import caches, markdown/sandbox, a11y) and Groovy (try/finally, thread pools, SSE terminal CAS, deep state copies) |

## Debug Logging

Logger categories for Studio when troubleshooting server-side behavior. **JVM-only flags** (for example Spring AI HTTP trace) are documented in **[studio-aiassistant-jvm-parameters.md](../using-and-extending/studio-aiassistant-jvm-parameters.md)**.

| What | How |
|------|-----|
| Plugin orchestration / payload previews | Logger **DEBUG** on `plugins.org.craftercms.aiassistant.*` |

## User-facing Configuration

**Admins:** **[`docs/using-and-extending/installation.md`](../using-and-extending/installation.md)** (install the plugin), then **[`docs/using-and-extending/configuration-guide.md`](../using-and-extending/configuration-guide.md)** (configure the site). **`<llm>` ids, env + XML (tools-loop chat / Claude first):** **[`llm-configuration.md`](../using-and-extending/llm-configuration.md)**. **JVM-only tuning:** **[`studio-aiassistant-jvm-parameters.md`](../using-and-extending/studio-aiassistant-jvm-parameters.md)**. **CMS tools, SSE, optional hosted SaaS auth, troubleshooting:** **[`chat-and-tools-runtime.md`](chat-and-tools-runtime.md)**.
