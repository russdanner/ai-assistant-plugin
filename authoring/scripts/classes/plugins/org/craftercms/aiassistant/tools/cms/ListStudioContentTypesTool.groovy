package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class ListStudioContentTypesTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'ListStudioContentTypes' }

  @Override
  String description() { ToolPrompts.getDESC_LIST_STUDIO_CONTENT_TYPES() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.LIST_STUDIO_CONTENT_TYPES }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    boolean searchable = AuthoringPreviewContext.isTruthy(input?.searchable)
    def contentPath = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
    return ctx.ops.listStudioContentTypes(siteId, searchable, contentPath) as Map
  }
}
