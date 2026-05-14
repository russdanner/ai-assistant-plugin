# Contributing

This repo is the **Crafter Studio AI Assistant** plugin. The steps below are for people changing **this** codebase (not for site admins configuring `ui.xml` in a project).

## Specification & docs (required)

- **Product requirements & mechanics** — **[`docs/internals/spec.md`](docs/internals/spec.md)** is the **official** specification. Any change to author-visible behavior, `ui.xml` contracts, form/stream semantics, autonomous behavior, **`studio-ui.json`** / Project Tools UI contracts, or REST contracts described there must update **`spec.md`** in the **same PR** unless you explicitly document a same-release follow-up (avoid drift). **Admins:** **`studio-ui.json`** steps are in **[`docs/using-and-extending/configuration-guide.md`](docs/using-and-extending/configuration-guide.md)** **§1e**.
- **Product requirements** — **[`docs/using-and-extending/product-requirements.md`](docs/using-and-extending/product-requirements.md)** states obligations for authors, admins, and integrators. If you add, remove, or materially change a product obligation, update **that file** in the **same PR** (or justify why the requirement text still holds).
- **Build & install invariants** — **[`docs/using-and-extending/studio-plugins-guide.md`](docs/using-and-extending/studio-plugins-guide.md)** is the **official** specification for packaging (`yarn package`), Rollup outputs, canonical paths, and descriptor/plugin id rules. Update it when the build story changes.
- **Companion specs** — **[`docs/internals/stream-endpoint-design.md`](docs/internals/stream-endpoint-design.md)** and **[`docs/internals/chat-and-tools-runtime.md`](docs/internals/chat-and-tools-runtime.md)** are official for their topics; keep them aligned with **`spec.md`** when you change stream or tools/runtime behavior.
- **`<llm>` matrix & keys** — **[`docs/using-and-extending/llm-configuration.md`](docs/using-and-extending/llm-configuration.md)** when provider ids, env vars, or merge rules change.

## Local dev

- Clone the repo. From **`sources/`**: `yarn install`, then **`yarn package`** before installing the built plugin into a site.
- **`yarn start`** — local development UI at `http://localhost:3000/`.

Debug logging (logger names, JVM flags): **[`docs/internals/README.md`](docs/internals/README.md#debug-logging)**.
