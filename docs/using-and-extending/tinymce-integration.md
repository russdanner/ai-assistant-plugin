# TinyMCE (RTE) Integration

Wire the AI Assistant into **TinyMCE** via your site **`ui.xml`** on the **`craftercms.components.TinyMCE`** widget: open **`configuration → setups → setup → tinymceOptions`**, then merge the options below into that JSON.

## Toolbar and External Plugin

Replace **`YOUR_SITE_ID`** with the Studio site id (e.g. `new-demo`).

```json
{
  "toolbar1": "... | aiAssistantOpen crafterqshortcuts crafterq",
  "external_plugins": {
    "craftercms_aiassistant": "/studio/1/plugin/file?siteId=YOUR_SITE_ID&pluginId=org.craftercms.aiassistant.studio&type=aiassistant&name=tinymce&file=craftercms_aiassistant.js"
  },
  "craftercms_aiassistant": {}
}
```

Use **`&amp;`** for `&` inside XML attribute values if you inline JSON in XML.

## Exported Buttons

| Toolbar id | Behavior |
|--------------|----------|
| **`aiAssistantOpen`** | Opens the AI Assistant for free-form authoring chat |
| **`crafterqshortcuts`** | Shortcut list (same `shortcuts` config as below) |
| **`crafterq`** | Split: shortcuts + direct chat |

Legacy toolbar id **`CrafterQdialog`** was removed; update toolbar strings if you still reference it.

## `craftercms_aiassistant` Config Object

Add a **`craftercms_aiassistant`** object next to **`external_plugins`** (sibling under `tinymceOptions`).

| Key | Purpose |
|-----|---------|
| **`shortcuts`** | Drives **`crafterqshortcuts`** and **`crafterq`** |
| **`strings.openAiAssistant`** / **`strings.aiAssistantShortcuts`** | Tooltip labels |
| **`onOpenAiAssistant`** / **`onShortcutClick`** | Optional custom handlers (defaults exist) |
| Aliases **`strings.crafterqDialog`**, **`strings.crafterqShortcuts`**, **`oncrafterqDialog`** | Still accepted for older configs |

For agent **`llm`**, keys, and capabilities, see [llm-configuration.md](llm-configuration.md). For plugin paths and descriptor id, see [studio-plugins-guide.md](studio-plugins-guide.md).
