package plugins.org.craftercms.aiassistant.tools.development

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class UpdateTemplateTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'update_template' }

  @Override
  String description() { ToolPrompts.DESC_UPDATE_TEMPLATE }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    def instructions = input?.instructions?.toString()
    if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def templatePath = input?.templatePath?.toString()?.trim()
    def contentPath = input?.contentPath?.toString()?.trim()
    if (!templatePath && contentPath) {
      templatePath = ctx.ops.resolveTemplatePathFromContent(siteId, contentPath)
    }
    if (!templatePath) {
      throw new IllegalArgumentException('Missing required field: templatePath (or contentPath that resolves a display-template)')
    }
    def templateText = ctx.ops.getContent(siteId, templatePath)?.contentXml?.toString() ?: ''
    def contentType = input?.contentType?.toString()?.trim()
    def formDef = contentType ? ctx.ops.getContentTypeFormDefinition(siteId, contentType)?.formDefinitionXml?.toString() : null
    boolean formForwardTpl = ctx.fullSuppressRepoWrites ||
      (ctx.pathProtectFormItem && contentPath && AuthoringPreviewContext.sameRepoPath(contentPath, ctx.normProtectedFormItemPath))
    return [
      action: 'update_template',
      siteId: siteId,
      instructions: instructions,
      promptGuidance: formForwardTpl ? ToolPrompts.UPDATE_TEMPLATE_FORM_ENGINE : ToolPrompts.UPDATE_TEMPLATE,
      templatePath: templatePath,
      template: templateText,
      contentPath: contentPath,
      contentType: contentType,
      contentTypeFormDefinition: formDef,
      nextStep: formForwardTpl ? ToolPrompts.nextStepUpdateTemplateFormForward(templatePath) : ToolPrompts.nextStepUpdateTemplate(templatePath)
    ]
  }
}
