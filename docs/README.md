# Documentation Index

**Install:** **[`using-and-extending/installation.md`](using-and-extending/installation.md)** (Studio **Plugin Management**, CLI, Marketplace **`copy`**, build-before-install). **Admins** configure and extend the plugin on a site via **[`using-and-extending/configuration-guide.md`](using-and-extending/configuration-guide.md)** (`ui.xml`, agents, keys, **Project Tools**, **`studio-ui.json`**, forms, sandbox scripts). **Official specifications** for implementers and packaging: **[`internals/spec.md`](internals/spec.md)** and **[`using-and-extending/studio-plugins-guide.md`](using-and-extending/studio-plugins-guide.md)**. Update those when behavior or packaging changes.

## By Audience

| Audience | Start Here |
|----------|------------|
| **Install the plugin** | [Installation](using-and-extending/installation.md) |
| **Admins** — `ui.xml`, agents, keys, **Project Tools**, **`studio-ui.json`**, surfaces, sandbox scripts, build paths | [Configuration guide](using-and-extending/configuration-guide.md) · [Screenshots](using-and-extending/configuration-guide.md#cg-screenshots) · [Helper](using-and-extending/helper-widget.md) · [Autonomous widget](using-and-extending/autonomous-assistants-widget.md) · [Scripted tools & imagegen](using-and-extending/scripted-tools-and-imagegen.md) · [TinyMCE](using-and-extending/tinymce-integration.md) · [Studio plugins guide](using-and-extending/studio-plugins-guide.md) |
| **Product / review** — mandatory outcomes for authors, admins, and integrators | [Product requirements](using-and-extending/product-requirements.md) |
| **LLM & image backends** — wire ids, secrets, **`script:`** LLM, **pluggable `GenerateImage`** | [LLM configuration](using-and-extending/llm-configuration.md) · [Script LLM — BYO backend](using-and-extending/script-llm-bring-your-own-backend.md) · Groq script sample: `docs/examples/aiassistant-llm/groq/runtime.groovy` · [Image generation](using-and-extending/image-generation.md) · JVM tuning: [studio-aiassistant-jvm-parameters.md](using-and-extending/studio-aiassistant-jvm-parameters.md) |
| **Extension developers** — Rollup, descriptor, classpath, sandbox script layout | [Studio plugins guide](using-and-extending/studio-plugins-guide.md) · [Using & extending index](using-and-extending/README.md) |
| **Maintainers** — **`spec.md`** (requirements & mechanics), streaming, tools runtime, review-style invariants | [Internals](internals/README.md) · [Maintainer review checklist](internals/maintainer-review-checklist.md) |
| **Plugin repo contributors** — clone, `yarn package`, policy, spec | [CONTRIBUTING.md](../CONTRIBUTING.md) |

## Topic → Document

| Topic | Document |
|-------|----------|
| Install / copy-plugin / `install-plugin.sh` | [installation.md](using-and-extending/installation.md) |
| Site configuration (`ui.xml`, agents, keys, **`studio-ui.json`**, forms, Project Tools) | [configuration-guide.md](using-and-extending/configuration-guide.md) · [Screenshots](using-and-extending/configuration-guide.md#cg-screenshots) |
| Runtime UI flags, bulk form control, `studio-ui.json` | [configuration-guide.md §1e](using-and-extending/configuration-guide.md#cg-1e) · [spec.md — Studio UI flags](internals/spec.md#studio-ui-flags-studio-uijson) |
| Helper `ui.xml` & “component not found” | [helper-widget.md](using-and-extending/helper-widget.md) |
| Autonomous widget placement & overview | [autonomous-assistants-widget.md](using-and-extending/autonomous-assistants-widget.md) |
| TinyMCE toolbar & `craftercms_aiassistant` | [tinymce-integration.md](using-and-extending/tinymce-integration.md) |
| Product requirements | [product-requirements.md](using-and-extending/product-requirements.md) |
| **`imageGenerator`**, **`imagegen/`**, tool/prompt overrides | [image-generation.md](using-and-extending/image-generation.md) · [scripted-tools-and-imagegen.md](using-and-extending/scripted-tools-and-imagegen.md) |
| **`InvokeSiteUserTool`**, `user-tools/registry.json` | [scripted-tools-and-imagegen.md](using-and-extending/scripted-tools-and-imagegen.md) |
| JVM / `-D` tuning (timeouts, HTTP caps, key fallbacks) | [studio-aiassistant-jvm-parameters.md](using-and-extending/studio-aiassistant-jvm-parameters.md) |
| Debug logging (loggers, Spring AI HTTP trace JVM flag) | [internals/README.md](internals/README.md#debug-logging) · JVM reference: [studio-aiassistant-jvm-parameters.md](using-and-extending/studio-aiassistant-jvm-parameters.md) |
| MCP client (`mcpEnabled` + `mcpServers` in `tools.json`, Streamable HTTP) | [chat-and-tools-runtime.md](internals/chat-and-tools-runtime.md#mcp-client-tools-streamable-http) |
| REST/stream body, tools, MCP | [chat-and-tools-runtime.md](internals/chat-and-tools-runtime.md) |
| Engineering contracts (`ui.xml` / stream / form / REST) | [internals/spec.md](internals/spec.md) |
| Review-style invariants (perf, memory, SSE, Groovy pitfalls) | [internals/maintainer-review-checklist.md](internals/maintainer-review-checklist.md) |

**Examples:** [`examples/`](examples/).
