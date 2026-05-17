package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class UpdateContentTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(UpdateContentTool)

  @Override
  String wireName() { 'update_content' }

  @Override
  String description() { ToolPrompts.DESC_UPDATE_CONTENT }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    def instructions = input?.instructions?.toString()
    if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def contentPath = input?.contentPath?.toString()?.trim()
    if (!contentPath) throw new IllegalArgumentException('Missing required field: contentPath')
    Map gotItem = ctx.ops.getContent(siteId, contentPath) as Map
    def contentXml = gotItem?.contentXml?.toString() ?: ''
    def contentTypeId = StudioAiToolSupport.extractContentTypeIdFromItemXml(contentXml)
    def formDefXml = null
    def formFieldIds = []
    if (contentTypeId) {
      try {
        def formRes = ctx.ops.getContentTypeFormDefinition(siteId, contentTypeId)
        def raw = formRes?.formDefinitionXml?.toString()
        if (raw?.trim()) {
          formFieldIds = StudioAiToolSupport.extractFormFieldIdsFromFormDefinitionXml(raw)
          formDefXml = raw
        }
      } catch (Throwable t) {
        log.debug('update_content: form definition for {} failed: {}', contentTypeId, t.toString())
      }
    }
    boolean formForwardContent = ctx.fullSuppressRepoWrites ||
      (ctx.pathProtectFormItem && AuthoringPreviewContext.sameRepoPath(contentPath, ctx.normProtectedFormItemPath))
    def payload = [
      action        : 'update_content',
      siteId        : siteId,
      instructions  : instructions,
      promptGuidance: formForwardContent ? ToolPrompts.UPDATE_CONTENT_FORM_ENGINE : ToolPrompts.UPDATE_CONTENT,
      contentPath   : contentPath,
      contentXml    : contentXml,
      nextStep      : formForwardContent ? ToolPrompts.nextStepUpdateContentFormForward(contentPath) : ToolPrompts.nextStepUpdateContent(contentPath)
    ]
    if (contentTypeId) {
      payload.contentTypeId = contentTypeId
    }
    if (formFieldIds) {
      payload.formFieldIds = formFieldIds
    }
    if (formDefXml) {
      payload.formDefinitionForContentType = formDefXml
    }
    ['xmlWellFormed', 'xmlParseError', 'xmlRepairReminder'].each { String k ->
      if (gotItem != null && gotItem.containsKey(k)) {
        payload[k] = gotItem[k]
      }
    }
    return payload
  }
}
