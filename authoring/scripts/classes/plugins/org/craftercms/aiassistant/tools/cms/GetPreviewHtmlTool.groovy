package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class GetPreviewHtmlTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'GetPreviewHtml' }

  @Override
  String description() { ToolPrompts.getDESC_GET_PREVIEW_HTML() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GET_PREVIEW_HTML }

  @Override
  String pipelineStage() { 'verification' }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def m = (Map) (input ?: [:])
    def abs = m.url?.toString()?.trim() ?: m.previewUrl?.toString()?.trim()
    if (!abs) {
      throw new IllegalArgumentException('Missing required field: url (absolute preview http(s) URL, or previewUrl alias)')
    }
    def tok = m.previewToken?.toString()?.trim()
    def sid = m.siteId?.toString()?.trim()
    return ctx.ops.fetchPreviewRenderedHtml(abs, tok, sid) as Map
  }
}
