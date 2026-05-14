# Autonomous Assistants Widget (Optional)

Second Studio widget: **`craftercms.components.aiassistant.AutonomousAssistants`**. Same **`plugin`** element as the Helper (`id="org.craftercms.aiassistant.studio"`, `type="aiassistant"`, `name="components"`, `file="index.js"`).

| Aspect | Summary |
|--------|---------|
| **Purpose** | Scheduled, in-memory assistant steps, supervisor, human tasks (prototype) |
| **LLM support** | Tools-loop chat family for steps; see [spec.md](../internals/spec.md) *Autonomous assistants* |
| **Full contract** | REST paths, `control` actions, `autonomousAgents` XML fields: [spec.md](../internals/spec.md) |

## Showing the Widget (Opt-in)

Set **`showAutonomousAiAssistantsInSidebar`** to **`true`** in **`config/studio/scripts/aiassistant/config/studio-ui.json`** ([configuration guide §1e](configuration-guide.md#cg-1e), [spec.md — Studio UI flags](../internals/spec.md#studio-ui-flags-studio-uijson)). The default is **`false`** (omit or false keeps the row empty while the **`AutonomousAssistants`** widget remains merged in **`ui.xml`**). Use **Project Tools → AI Assistant → UI** to toggle, or edit the JSON in the site sandbox. Reload Studio if the panel does not refresh immediately.

## Hiding the Widget Without Removing `ui.xml`

Set **`showAutonomousAiAssistantsInSidebar`** to **`false`** or remove the key. The **`AutonomousAssistants`** widget can stay in **`ui.xml`**; the bundle renders no UI while the flag is off.

## If the Widget Never Appears

| Cause | Fix |
|-------|-----|
| **Sidebar flag off (default)** | Set **`showAutonomousAiAssistantsInSidebar`** to **`true`** in **`studio-ui.json`**, or enable **Show Autonomous AI Assistants** in **Project Tools → AI Assistant → UI**. |
| Wrong **`ui.xml` nesting** | **`Helper`** / **`AutonomousAssistants`** must be inside **`ToolsPanel → configuration → widgets`** as sibling `<widget>` rows—not after `</configuration>` |
| Stale merge | Re-run **`./scripts/install-plugin.sh`** from an updated clone, or paste the block from [studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml), commit sandbox, sync in Studio |
| **`<icon>`** | Use real `<![CDATA[...]]>` SVG, not HTML-escaped markup |
