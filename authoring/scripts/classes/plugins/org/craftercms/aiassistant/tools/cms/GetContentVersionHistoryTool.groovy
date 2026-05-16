package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

class GetContentVersionHistoryTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'GetContentVersionHistory' }

  @Override
  String description() { ToolPrompts.getDESC_GET_CONTENT_VERSION_HISTORY() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GET_CONTENT_VERSION_HISTORY }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    def versions = ctx.ops.getContentVersionHistory(siteId, path)
    return [
      action  : 'get_content_version_history',
      siteId  : siteId,
      path    : path,
      versions: versions
    ]
  }
}
