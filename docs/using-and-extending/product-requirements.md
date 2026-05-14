# Product Requirements

This document states **what the AI Assistant Studio plugin is required to deliver**—obligations and acceptance criteria.

Wire formats, REST field lists, `ui.xml` grammar, file paths, and build steps are documented in **[`spec.md`](../internals/spec.md)** and the linked guides. Implementation **must** satisfy this document and those technical specifications.

---

## Author-facing Requirements

1. **Chat access** — Authors who have Studio access to a site where the plugin is configured **must** be able to open an AI chat from each surface the site enables: at minimum, the plugin **must** support opening chat from the form-engine control, from the Helper (preview toolbar and/or Tools Panel, per site configuration), and from **TinyMCE** only when that optional RTE integration is configured.

2. **Multiple agents** — When the site defines more than one agent, the plugin **must** let the author choose which agent to use (or follow the documented single-agent shortcut) without editing code.

3. **Editing context** — Where a surface provides editor or field context (for example selection or current field), the plugin **must** pass that context into the assistant workflow as documented, so replies can align with what the author is editing.

4. **Image generation** — When the site configures a supported image backend, the plugin **must** expose image generation to authors through the same assistant flows documented for that configuration.

5. **Apply replies in TinyMCE** — When the author uses the **TinyMCE** integration, the plugin **must** offer a supported path to insert or apply model output into the editor, subject to editor permissions.

---

## Admin Requirements

6. **Per-site configuration** — Admins **must** be able to enable, disable, or tune assistant behavior per site using Studio-supported configuration (for example `ui.xml` widget definitions and documented sandbox files)—without changing plugin source in the repository. **Examples** include **`ui.xml`**, **`config/studio/ai-assistant/agents.json`**, **`config/studio/scripts/aiassistant/config/studio-ui.json`** (runtime toolbar/sidebar visibility, scoped Experience Builder image-picker augmentation, bulk form-control edits — see the configuration guide and **`spec.md`**), **`scripts/aiassistant/…`** script trees, and other paths named in **`spec.md`**.

7. **Agents** — Admins **must** be able to define one or more agents with distinct display metadata, instructions, model choice (`llm` / model identifiers as documented), and tool options where the product supports them.

8. **Secrets and keys** — Admins **must** be able to supply credentials and endpoints through documented mechanisms (environment, Studio configuration, or site sandbox files as applicable), without embedding secrets in client-only bundles in violation of the security model documented in **`spec.md`**.

9. **Tool governance** — Where the product advertises tool allow/deny or MCP attachment, admins **must** be able to apply that governance through documented configuration so that authors cannot invoke disallowed tools solely by UI manipulation.

---

## Integrator Requirements

10. **Scripted extensions** — Integrators **must** be able to add sandbox Groovy tools, script-backed LLM identifiers, and script-backed image generators in the repository paths and registration shapes documented in the [Studio plugins guide](studio-plugins-guide.md) and [Scripted tools & imagegen](scripted-tools-and-imagegen.md), and have Studio load them without rebuilding the core TypeScript bundle for those scripts alone.

11. **Overrides** — Where **`spec.md`** promises site-level overrides (for example prompts or `tools.json` policy), integrators **must** be able to supply those overrides from site `config/studio` content as documented.

---

## Optional / Experimental Requirements (Autonomous Widget)

12. **Autonomous mode** — If the Autonomous assistants widget is installed and configured, the plugin **must** enforce the documented scheduling, scope, and in-memory semantics so admins can predict lifecycle (including loss of state on JVM restart) as described in **[`spec.md`](../internals/spec.md#autonomous-assistants-widget-tools-panel)** and the [Autonomous assistants widget](autonomous-assistants-widget.md) guide. This area remains **experimental**; it **must not** be documented as a production-grade job scheduler.

#### Each Autonomous Agent (Minimum Behaviors)

For **each** autonomous agent the site defines, the product **must** make the following available and consistent with **`spec.md`** (field names and REST actions are in the spec and widget guide):

- **Schedule** — Each agent **must** run on a documented cadence (cron-style schedule mapped to a minimum time between steps). The supervisor tick and the agent’s schedule **must** interact as documented so admins know it is **not** “every tick = one run.”
- **Prompt** — Each agent **must** carry configurable base instructions (what the run is for); the server **must** append its own strict reply contract on top for structured outcomes.
- **State** — Each agent **must** have visible **in-memory** status and history for users in scope (waiting, running, stopped, error, and similar values as documented). State **must** be lost on JVM restart and when the store is destroyed, unless **`spec.md`** explicitly documents otherwise.
- **Human tasks** — Runs **must** be able to surface **human tasks** (titles and prompts for people to act on) in the widget; humans **must** be able to complete, dismiss, or reopen tasks per the documented controls. The model **may** return task lists and updates in its structured reply as documented.
- **Lifecycle controls** — Admins (and other users allowed by **scope**) **must** be able to **start** and **stop** individual agents, **enable** or **disable** the supervisor, and use other documented **control** actions (for example run now, clear error, destroy in-memory store) without redeploying code.
- **Scope** — Each agent **must** respect a documented **scope** (for example project vs user vs role) so only the right signed-in users see that agent’s status, tasks, and controls.
- **Failure behavior** — When a step fails, behavior **must** follow the agent’s **stop-on-failure** (or retry) setting as documented, including moving an agent to **error** and surfacing **last error** detail in the widget when applicable.

---

## Technical and Release Requirements

13. **Spec alignment** — Any change that adds, removes, or materially alters author-visible behavior, configuration contracts, or security boundaries **must** update **[`spec.md`](../internals/spec.md)** (and companions where applicable) in the same release train, per **[`CONTRIBUTING.md`](../../CONTRIBUTING.md)**.

14. **Studio baseline** — The plugin **must** target the Crafter Studio branch documented for this repository (see **`spec.md`** and the Studio plugins guide); breaking Studio API changes **must** be accompanied by version and migration notes.

---

## Related Documents

| Need | Document |
|------|----------|
| Install the plugin | [Installation](installation.md) |
| Configure a site | [Configuration guide](configuration-guide.md) |
| Model identifiers and provider matrix | [LLM configuration](llm-configuration.md) |
| Engineering contracts and behavior | [`spec.md`](../internals/spec.md) |
