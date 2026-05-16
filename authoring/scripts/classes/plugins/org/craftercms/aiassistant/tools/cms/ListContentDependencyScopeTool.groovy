package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.content.ContentSubgraphAggregator
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class ListContentDependencyScopeTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'ListContentDependencyScope' }

  @Override
  String description() { ToolPrompts.getDESC_LIST_CONTENT_DEPENDENCY_SCOPE() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.LIST_CONTENT_DEPENDENCY_SCOPE }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    def contentPath = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
    Integer maxItems = parseOptionalInt(input?.maxItems)
    Integer maxDepth = parseOptionalInt(input?.maxDepth)
    Integer chunkSize = parseOptionalInt(input?.chunkSize)
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    if (!contentPath) throw new IllegalArgumentException('Missing required field: contentPath (or path)')
    return ContentSubgraphAggregator.buildTranslationScopeTree(ctx.ops, siteId, contentPath, maxItems, maxDepth, chunkSize)
  }

  private static Integer parseOptionalInt(Object v) {
    if (v == null) return null
    try {
      return (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim())
    } catch (Throwable ignored) {
      return null
    }
  }
}
