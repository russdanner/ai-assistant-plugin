package plugins.org.craftercms.aiassistant.tools.development

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class AnalyzeTemplateTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'analyze_template' }

  @Override
  String description() { ToolPrompts.DESC_ANALYZE_TEMPLATE }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  @Override
  String pipelineStage() { 'verification' }

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
    return [
      action: 'analyze_template',
      siteId: siteId,
      instructions: instructions,
      promptGuidance: ToolPrompts.ANALYZE_TEMPLATE,
      templatePath: templatePath,
      template: templateText,
      contentPath: contentPath
    ]
  }
}
