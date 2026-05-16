package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

class GetContentTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'GetContent' }

  @Override
  String description() { ToolPrompts.getDESC_GET_CONTENT() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GET_CONTENT }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def commitRef = input?.commitId?.toString()?.trim() ?: input?.commitRef?.toString()?.trim()
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (!path) {
      throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    }
    return ctx.ops.getContent(input?.siteId as String, path, commitRef) as Map
  }
}
