package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class UpdateContentTypeTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'update_content_type' }

  @Override
  String description() { ToolPrompts.DESC_UPDATE_CONTENT_TYPE }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    def instructions = input?.instructions?.toString()
    if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def contentType = input?.contentType?.toString()?.trim()
    if (!contentType) throw new IllegalArgumentException('Missing required field: contentType')
    Map formRes = ctx.ops.getContentTypeFormDefinition(siteId, contentType) as Map
    def xml = formRes?.formDefinitionXml?.toString() ?: ''
    def cfgPath = formRes?.path?.toString()
    Map payloadCt = [
      action: 'update_content_type',
      siteId: siteId,
      instructions: instructions,
      promptGuidance: ctx.fullSuppressRepoWrites ? ToolPrompts.UPDATE_CONTENT_TYPE_FORM_ENGINE : ToolPrompts.UPDATE_CONTENT_TYPE,
      contentType: contentType,
      formDefinitionPath: cfgPath,
      formDefinitionXml: xml,
      nextStep: ctx.fullSuppressRepoWrites ? ToolPrompts.nextStepUpdateContentTypeFormForward(cfgPath) : ToolPrompts.nextStepUpdateContentType(cfgPath)
    ]
    ['xmlWellFormed', 'xmlParseError', 'xmlRepairReminder'].each { String k ->
      if (formRes != null && formRes.containsKey(k)) {
        payloadCt[k] = formRes[k]
      }
    }
    return payloadCt
  }
}
