package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GetContentTypeFormDefinitionTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(GetContentTypeFormDefinitionTool)

  @Override
  String wireName() { 'GetContentTypeFormDefinition' }

  @Override
  String description() { ToolPrompts.getDESC_GET_CONTENT_TYPE_FORM_DEFINITION() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GET_CONTENT_TYPE }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def contentPath = input?.contentPath?.toString()?.trim()
    def contentTypeId = input?.contentTypeId?.toString()?.trim()
    if (contentPath) {
      def item = ctx.ops.getContent(siteId, contentPath)
      def xml = item?.contentXml?.toString()
      def fromXml = StudioAiToolSupport.extractContentTypeIdFromItemXml(xml)
      if (fromXml) {
        if (contentTypeId && !contentTypeId.equals(fromXml)) {
          log.warn(
            'GetContentTypeFormDefinition: ignoring contentTypeId={} (differs from <content-type> in {}); using {}',
            contentTypeId, contentPath, fromXml
          )
        }
        contentTypeId = fromXml
      } else if (!contentTypeId) {
        throw new IllegalArgumentException(
          "No <content-type> element found in XML at '${contentPath}'. Open the item in Studio or use GetContent; pass contentTypeId only if you copy the exact value from that element."
        )
      }
    }
    if (!contentTypeId) {
      throw new IllegalArgumentException(
        'Provide contentPath (page/component XML path — server reads <content-type>) or contentTypeId (exact value from that element, never inferred from filename).'
      )
    }
    return ctx.ops.getContentTypeFormDefinition(siteId, contentTypeId) as Map
  }
}
