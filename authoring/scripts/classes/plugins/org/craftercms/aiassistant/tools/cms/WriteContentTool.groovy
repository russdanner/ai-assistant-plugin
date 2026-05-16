package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

class WriteContentTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'WriteContent' }

  @Override
  String description() { ToolPrompts.DESC_WRITE_CONTENT }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.WRITE_CONTENT }

  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return !ctx.fullSuppressRepoWrites
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (ctx.pathProtectFormItem) {
      def p = AuthoringPreviewContext.normalizeRepoPath(path)
      if (p && p == ctx.normProtectedFormItemPath) {
        return [
          ok: false,
          blockedForFormClientApply: true,
          path: p,
          message:
            'WriteContent blocked: this path is the Studio form item with client-side apply. Put field edits in aiassistantFormFieldUpdates JSON in your final reply. You may still call WriteContent for other repository paths.',
          nextStep: 'Return aiassistantFormFieldUpdates for this item; use WriteContent only for paths other than this one.'
        ]
      }
    }
    if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    return ctx.ops.writeContent(
      input?.siteId as String,
      path,
      input?.contentXml as String,
      input?.unlock != null ? input.unlock as String : 'true'
    ) as Map
  }
}
