package plugins.org.craftercms.aiassistant.tools.catalog

import plugins.org.craftercms.aiassistant.tools.cms.GetContentTool
import plugins.org.craftercms.aiassistant.tools.cms.GetContentTypeFormDefinitionTool
import plugins.org.craftercms.aiassistant.tools.cms.GetContentVersionHistoryTool
import plugins.org.craftercms.aiassistant.tools.cms.GetPreviewHtmlTool
import plugins.org.craftercms.aiassistant.tools.cms.ListContentDependencyScopeTool
import plugins.org.craftercms.aiassistant.tools.cms.ListPagesAndComponentsTool
import plugins.org.craftercms.aiassistant.tools.cms.ListStudioContentTypesTool
import plugins.org.craftercms.aiassistant.tools.cms.PublishContentTool
import plugins.org.craftercms.aiassistant.tools.cms.RevertChangeTool
import plugins.org.craftercms.aiassistant.tools.cms.UpdateContentTool
import plugins.org.craftercms.aiassistant.tools.cms.UpdateContentTypeTool
import plugins.org.craftercms.aiassistant.tools.cms.WriteContentTool
import plugins.org.craftercms.aiassistant.tools.development.AnalyzeTemplateTool
import plugins.org.craftercms.aiassistant.tools.development.GetCrafterizingPlaybookTool
import plugins.org.craftercms.aiassistant.tools.development.UpdateTemplateTool
import plugins.org.craftercms.aiassistant.tools.general.FetchHttpUrlTool
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext

/**
 * Composes core {@link plugins.org.craftercms.aiassistant.tools.spi.StudioAiOrchestrationTool} classes into Spring AI callbacks.
 */
final class StudioAiToolRegistry {

  private static final List<Class<? extends AbstractStudioAiTool>> CORE_TOOL_CLASSES = [
    GetContentTool,
    ListContentDependencyScopeTool,
    ListStudioContentTypesTool,
    GetContentTypeFormDefinitionTool,
    GetContentVersionHistoryTool,
    GetPreviewHtmlTool,
    FetchHttpUrlTool,
    WriteContentTool,
    ListPagesAndComponentsTool,
    UpdateTemplateTool,
    UpdateContentTool,
    UpdateContentTypeTool,
    AnalyzeTemplateTool,
    PublishContentTool,
    GetCrafterizingPlaybookTool,
    RevertChangeTool,
  ].asImmutable()

  private StudioAiToolRegistry() {}

  static List buildCoreToolCallbacks(StudioAiToolContext ctx) {
    List out = []
    for (Class<? extends AbstractStudioAiTool> clazz : CORE_TOOL_CLASSES) {
      AbstractStudioAiTool tool = clazz.getDeclaredConstructor().newInstance()
      if (tool.enabled(ctx)) {
        out.add(tool.toFunctionToolCallback(ctx))
      }
    }
    return out
  }
}
