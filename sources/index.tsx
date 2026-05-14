import { PluginDescriptor } from '@craftercms/studio-ui';
import AiAssistantPopover from './src/AiAssistantPopover';
import AiAssistantDialogContent from './src/AiAssistantDialogContent';
import {
  autonomousAgentsMarkWidgetId,
  autonomousAssistantsWidgetId,
  aiAssistantStudioPluginId,
  dialogContentWidgetId,
  formControlWidgetId,
  helperWidgetId,
  logoWidgetId,
  popoverWidgetId,
  projectToolsAiAssistantConfigWidgetId,
  projectToolsCentralAgentsWidgetId,
  projectToolsScriptsSandboxWidgetId,
  projectToolsStudioUiSettingsWidgetId
} from './src/consts.ts';
import AiAssistantLogo from './src/AiAssistantLogo.tsx';
import AutonomousAgentsMarkIcon from './src/autonomousAgentsMarkIcon.tsx';
import AiAssistantHelper from './src/AiAssistantHelper.tsx';
import AiAssistantAutonomousAssistants from './src/AiAssistantAutonomousAssistants.tsx';
import AiAssistantFormControl from './src/AiAssistantFormControl';
import AiAssistantProjectToolsConfiguration, {
  AiAssistantProjectToolsConfigurationAgentsTab,
  AiAssistantProjectToolsConfigurationScriptsTab,
  AiAssistantProjectToolsConfigurationUiTab
} from './src/AiAssistantProjectToolsConfiguration';
import { installAiAssistantContentTypesHighlightPatch } from './src/aiAssistantContentTypesHighlightPatch';
import { installRemoteImageDropImportBridge } from './src/aiAssistantRemoteImageDropBridge';

installRemoteImageDropImportBridge();
installAiAssistantContentTypesHighlightPatch();

const plugin: PluginDescriptor = {
  locales: undefined,
  scripts: undefined,
  stylesheets: undefined,
  /**
   * Must match `craftercms-plugin.yaml` → `plugin.id`. Studio `registerPlugin` dedupes on this string; using a
   * different id than `craftercms-plugin.yaml` can cause a prior/empty registration to skip this bundle so
   * `craftercms.components.aiassistant.Helper` never reaches the component registry.
   */
  id: aiAssistantStudioPluginId,
  widgets: {
    [helperWidgetId]: AiAssistantHelper,
    [autonomousAssistantsWidgetId]: AiAssistantAutonomousAssistants,
    [autonomousAgentsMarkWidgetId]: AutonomousAgentsMarkIcon,
    [formControlWidgetId]: AiAssistantFormControl,
    [logoWidgetId]: AiAssistantLogo,
    /** Legacy SystemIcon ids (older ui.xml / bundles); same component as logoWidgetId (OpenAILogo). */
    'craftercms.components.aiassistant.AiAssistantLogo': AiAssistantLogo,
    'craftercms.components.aiassistant.CrafterQLogo': AiAssistantLogo,
    [popoverWidgetId]: AiAssistantPopover,
    [dialogContentWidgetId]: AiAssistantDialogContent,
    [projectToolsAiAssistantConfigWidgetId]: AiAssistantProjectToolsConfiguration,
    [projectToolsCentralAgentsWidgetId]: AiAssistantProjectToolsConfigurationAgentsTab,
    [projectToolsScriptsSandboxWidgetId]: AiAssistantProjectToolsConfigurationScriptsTab,
    [projectToolsStudioUiSettingsWidgetId]: AiAssistantProjectToolsConfigurationUiTab
  }
};

export default plugin;
/** Named exports for `import()` interop: Studio uses `module.plugin ?? module.default`. */
export { AiAssistantPopover, plugin };
