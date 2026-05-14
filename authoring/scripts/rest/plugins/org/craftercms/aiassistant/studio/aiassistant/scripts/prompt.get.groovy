import plugins.org.craftercms.aiassistant.prompt.ToolPromptsBuiltinDefaults
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsLoader
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsOverrideCatalog
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsSiteContext
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * One tool-prompt key: default text (classpath {@code KEY.md} if present, else Groovy literal) vs raw site sandbox file.
 * Query: {@code siteId}, {@code key} (required).
 */
String siteId = (params?.siteId ?: request.getParameter('siteId'))?.toString()?.trim()
String key = (params?.key ?: request.getParameter('key'))?.toString()?.trim()
if (!siteId) {
  response.status = 400
  return [ok: false, message: 'Missing siteId']
}
if (!key) {
  response.status = 400
  return [ok: false, message: 'Missing key']
}
if (!(key in ToolPromptsOverrideCatalog.KEYS)) {
  response.status = 400
  return [ok: false, message: "Unknown prompt key: ${key}"]
}

def ops = new StudioToolOperations(request, applicationContext, params)
String rel = "/scripts/aiassistant/prompts/${key}.md"
String siteFileFull = ops.readStudioConfigurationUtf8(siteId, rel) ?: ''
boolean siteOverrideEffective = (siteFileFull?.trim() ? true : false)

def savedCtx = ToolPromptsSiteContext.current()
try {
  if (savedCtx != null) {
    ToolPromptsSiteContext.exit()
  }
  if (!ToolPromptsBuiltinDefaults.getBuiltin(key)) {
    ToolPromptsLoader.ensureBuiltinRegisteredForCatalogKey(key)
  }

  String defaultFull = ToolPromptsLoader.previewBaseTextWithoutSiteFile(key)
  int maxPreview = 200000
  boolean defaultTrunc = defaultFull.length() > maxPreview
  boolean siteTrunc = siteFileFull.length() > maxPreview
  String defaultText = defaultTrunc ? (defaultFull.substring(0, maxPreview) + "\n\n… truncated for preview (" + defaultFull.length() + " chars total).") : defaultFull
  String siteFileText = siteTrunc ? (siteFileFull.substring(0, maxPreview) + "\n\n… truncated for preview (" + siteFileFull.length() + " chars total).") : siteFileFull

  response.setContentType('application/json')
  return [
    ok                   : true,
    key                  : key,
    siteOverrideEffective: siteOverrideEffective,
    defaultText          : defaultText,
    siteFileText         : siteFileText,
    defaultTextTruncated : defaultTrunc,
    siteFileTruncated    : siteTrunc
  ]
} finally {
  if (savedCtx != null) {
    ToolPromptsSiteContext.enter(savedCtx.get('applicationContext'), savedCtx.get('siteId')?.toString())
  }
}
