# Helper Widget (Experience Builder & Tools Panel)

Embeds the **Studio AI assistant** so authors open chat on demand. Each agent’s **`<llm>`** selects the backend; see [llm-configuration.md](llm-configuration.md).

## Minimal `ui.xml` Snippet

The **`plugin` id must be the full descriptor id** `org.craftercms.aiassistant.studio`. A shortened id loads the wrong path and Studio cannot resolve the bundle.

```xml
<widget id="craftercms.components.ToolsPanel">
  <configuration>
    <widgets>
      <widget id="craftercms.components.aiassistant.Helper">
        <plugin id="org.craftercms.aiassistant.studio" type="aiassistant" name="components" file="index.js"/>
        <configuration ui="IconButton" />
      </widget>
    </widgets>
  </configuration>
</widget>
```

## “Component craftercms.components.aiassistant.Helper Not Found”

| Check | Action |
|-------|--------|
| **`plugin` element** | `id="org.craftercms.aiassistant.studio"` (matches `craftercms-plugin.yaml` and `sources/index.tsx` `PluginDescriptor.id`) |
| **Install** | From `sources/`: `yarn package`, then `./scripts/install-plugin.sh` (or Marketplace) so **`…/studio/aiassistant/components/index.js`** exists in the site sandbox |
| **Cache** | Hard refresh Studio after deploy |

Deeper wiring: [spec.md](../internals/spec.md), paths: [studio-plugins-guide.md](studio-plugins-guide.md). For **optional** Tools Panel + **Autonomous** + preview-toolbar fragments, see [examples/studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml).

## Hiding the Preview Toolbar Icon Without Editing `ui.xml`

When the Helper stays merged under **`PreviewToolbar`** with **`configuration ui="IconButton"`**, admins can set **`showAiAssistantsInTopNavigation`** to **`false`** in **`config/studio/scripts/aiassistant/config/studio-ui.json`** (see [configuration guide §1e](configuration-guide.md#cg-1e) and [spec.md — Studio UI flags](../internals/spec.md#studio-ui-flags-studio-uijson)). That hides **only** the toolbar **icon**; a **Tools Panel** Helper row added manually in **`ui.xml`** is unchanged. Reload Studio if the icon still appears until configuration cache refreshes.

## Preview Toolbar Icon Missing (Next to the URL Bar)

The plugin descriptor merges a **second** Helper under **`craftercms.components.PreviewToolbar` → `configuration` → `rightSection` → `widgets`** (reliable marketplace / `copy-plugin` wiring). For an icon **next to the URL bar**, move or copy that **`<widget id="craftercms.components.aiassistant.Helper">…</widget>`** under **`…/middleSection/widgets`** in `config/studio/ui.xml` (see [studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml)).

| Cause | What to do |
|-------|------------|
| **Wrong XPath (older installs)** | A **global** `//widget[@id='…Helper']` could match the **Tools Panel** Helper and skip the preview row. Current **`craftercms-plugin.yaml`** scopes **`elementXpath`** to **`PreviewToolbar/.../rightSection/widgets/...`**. |
| **Merge target** | Descriptor **`parentXpath`** targets **`…/PreviewToolbar/configuration/rightSection/widgets`** for **`copy-plugin`** / marketplace install. For URL-bar placement, edit **`ui.xml`** after install. **Reinstall** from current `craftercms-plugin.yaml` or paste the preview block from [studio-ui-aiassistant-fragments.xml](../examples/studio-ui-aiassistant-fragments.xml). |
| **No `middleSection` / `rightSection`** | Confirm your Studio `ui.xml` still uses **`PreviewToolbar`** with **`middleSection`** / **`rightSection`** / **`widgets`** (same shape as [Crafter’s UI config](https://docs.craftercms.org/en/4.2/reference/modules/studio/configuration/user-interface-configuration.html)). |
| **Merge did not run** | Confirm marketplace/copy completed without error; open the site sandbox **`config/studio/ui.xml`** and search for **`PreviewToolbar`** and **`craftercms.components.aiassistant.Helper`**. |
