# Cursor Rules, Skills, and AI Policy (This Repo)

This file is the **in-project index** of everything under **`.cursor/`** that guides agents and humans. **Whenever you add, rename, or materially change a rule or project skill, update this document** so it stays the single place that lists “what we store locally” for Cursor.

## Project Rules (`.cursor/rules/`)

| File | `alwaysApply` | Summary |
|------|-----------------|---------|
| `crafterq-form-panel-contract.mdc` | yes | Form Engine control: agent list visibility (`config.properties`), accordion-style panel (`AiAssistantFormControlPanel.tsx`), `sources/control/ai-assistant/main.js` sync, `yarn package`, form context / appendix wiring. |
| `no-unauthorized-ui-changes.mdc` | yes | No UI/UX or interaction changes unless explicitly requested; feature work is functional by default. |

Rules are the **strictest** layer: follow them even if generic advice conflicts.

## Project Skills (`.cursor/skills/`)

| Directory | Purpose |
|-----------|---------|
| `crafterq-studio-plugin/` | `SKILL.md` — AI Assistant plugin workflows, pointers to rules + docs, mechanical checklist (`ai-assistant/main.js` sync, `yarn package`). |

**Skill discovery:** Ensure the **crafterq-studio-plugin** skill (folder name is legacy) is enabled for this project in Cursor if you rely on it for agent behavior (project skills are optional per workspace settings).

## Related Documentation (Keep Aligned With Policy)

- **`docs/using-and-extending/studio-plugins-guide.md`** — Includes the locked **form assistant panel** paragraph; keep it consistent with `crafterq-form-panel-contract.mdc`. See **§ AI Assistant plugin repo: canonical sources vs generated files** for which paths under `authoring/static-assets/` are overwritten by `yarn package`.
- **`docs/internals/spec.md`** — As-is behavior specification; update when author-facing behavior, macros, or configuration contracts change (includes **Helper** `agents` and **autonomous** widget `autonomousAgents` / REST / human tasks). **Terminology:** Studio AI assistant (product); CrafterQ = `crafterQ` tool path.

Full doc layout: **`docs/README.md`** (using & extending vs internals).

## Maintenance Checklist (for Contributors and Agents)

When you change **local** Cursor artifacts or policies:

1. Edit the **`.mdc` rule** or **`SKILL.md`** as needed.
2. Update **this file** (`docs/CURSOR_PROJECT_POLICY.md`) — table rows and summaries.
3. If the change affects author-visible behavior or install/build flow, update **`docs/internals/spec.md`** and/or **`docs/using-and-extending/studio-plugins-guide.md`**.
4. If you only add a **new** rule file, add a row to the table above and mention it in **`SKILL.md`** under “Canonical local policy.”
