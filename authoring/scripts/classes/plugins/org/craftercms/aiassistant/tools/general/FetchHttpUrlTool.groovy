package plugins.org.craftercms.aiassistant.tools.general

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class FetchHttpUrlTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'FetchHttpUrl' }

  @Override
  String description() { ToolPrompts.getDESC_FETCH_HTTP_URL() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.FETCH_HTTP_URL }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def url = input?.url?.toString()?.trim()
    if (!url) throw new IllegalArgumentException('Missing required field: url')
    Integer maxChars = null
    if (input?.maxChars != null) {
      try {
        maxChars = (input.maxChars instanceof Number) ? ((Number) input.maxChars).intValue() : Integer.parseInt(input.maxChars.toString().trim())
      } catch (Throwable ignored) {
        maxChars = null
      }
    }
    return ctx.ops.fetchHttpUrl(url, maxChars) as Map
  }
}
