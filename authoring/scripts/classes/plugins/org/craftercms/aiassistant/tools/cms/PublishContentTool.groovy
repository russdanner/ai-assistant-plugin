package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PublishContentTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(PublishContentTool)

  @Override
  String wireName() { 'publish_content' }

  @Override
  String description() { ToolPrompts.DESC_PUBLISH_CONTENT }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return !ctx.fullSuppressRepoWrites
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    if (ctx.pathProtectFormItem && AuthoringPreviewContext.sameRepoPath(path, ctx.normProtectedFormItemPath)) {
      return [
        ok: false,
        blockedForFormClientApply: true,
        path: AuthoringPreviewContext.normalizeRepoPath(path),
        message:
          'publish_content blocked for the form item path (client-side apply). Save/publish from Studio after applying form updates.',
        action: 'publish_content'
      ]
    }
    def date = input?.date?.toString()?.trim()
    def target = input?.publishingTarget?.toString()?.trim() ?: 'live'
    Long packageId = null
    String err = null
    try {
      packageId = ctx.ops.submitPublishPackage(siteId, path, target, date) as Long
    } catch (Throwable t) {
      err = (t.message ?: t.toString())
      log.warn('publish_content failed: {}', err)
    }
    return [
      action            : 'publish_content',
      siteId            : siteId,
      path              : path,
      date              : date,
      publishingTarget  : target,
      publishPackageId  : packageId,
      ok                : err == null,
      message           : err ?: 'Publish submitted.',
      result            : packageId
    ]
  }
}
