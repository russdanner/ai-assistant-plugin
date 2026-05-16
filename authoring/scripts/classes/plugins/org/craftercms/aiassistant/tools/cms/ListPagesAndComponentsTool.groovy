package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class ListPagesAndComponentsTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'ListPagesAndComponents' }

  @Override
  String description() { ToolPrompts.getDESC_LIST_PAGES_AND_COMPONENTS() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.LIST_PAGES }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    return ctx.ops.listPagesAndComponents(input?.siteId as String, (input?.size as Integer) ?: 1000) as Map
  }
}
