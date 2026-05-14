import plugins.org.craftercms.aiassistant.imagegen.StudioAiScriptImageGenLoader
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsOverrideCatalog
import plugins.org.craftercms.aiassistant.tools.StudioAiUserSiteTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Site sandbox index for AI Assistant scripted user tools, image generators, and script LLMs.
 * Query: {@code siteId} (required).
 */
String siteId = (params?.siteId ?: request.getParameter('siteId'))?.toString()?.trim()
if (!siteId) {
  response.status = 400
  return [ok: false, message: 'Missing siteId']
}

def ops = new StudioToolOperations(request, applicationContext, params)

String registryRel = StudioAiUserSiteTools.USER_TOOLS_REGISTRY_PATH
String registryText = ops.readStudioConfigurationUtf8(siteId, registryRel) ?: ''

List<Map> toolRows = StudioAiUserSiteTools.loadRegistryEntries(ops)
List<Map> toolsOut = []
for (Map e : toolRows) {
  String id = e.id?.toString()
  String script = e.script?.toString()
  String desc = e.description?.toString() ?: ''
  String scriptPath = "${StudioAiUserSiteTools.USER_TOOLS_DIR_PREFIX}${script}"
  String src = ops.readStudioConfigurationUtf8(siteId, scriptPath) ?: ''
  toolsOut.add([
    id          : id,
    script      : script,
    description : desc,
    studioPath  : scriptPath,
    hasSource   : (src?.trim() ? true : false),
    byteLength  : src?.getBytes('UTF-8')?.length ?: 0
  ] as Map)
}

List<String> imageIds = []
try {
  imageIds = ops.listStudioSandboxChildFolderNames(siteId, "${StudioAiScriptImageGenLoader.IMAGEGEN_DIR_PREFIX}".toString())
} catch (Throwable ignored) {
}
List<Map> imageOut = []
for (String gid : imageIds) {
  if (!gid) {
    continue
  }
  String rel = "${StudioAiScriptImageGenLoader.IMAGEGEN_DIR_PREFIX}${gid}/generate.groovy"
  String src = ops.readStudioConfigurationUtf8(siteId, rel) ?: ''
  imageOut.add([
    id         : gid,
    studioPath : rel,
    hasSource  : (src?.trim() ? true : false),
    byteLength : src?.getBytes('UTF-8')?.length ?: 0
  ] as Map)
}

List<String> llmIds = []
try {
  llmIds = ops.listStudioSandboxChildFolderNames(siteId, '/scripts/aiassistant/llm/')
} catch (Throwable ignored2) {
}
List<Map> llmOut = []
List<String> llmEntryFiles = ['runtime.groovy', 'llm.groovy']
for (String lid : llmIds) {
  if (!lid) {
    continue
  }
  String body = null
  String used = null
  for (String fn : llmEntryFiles) {
    String rel = "/scripts/aiassistant/llm/${lid}/${fn}"
    String b = ops.readStudioConfigurationUtf8(siteId, rel)
    if (b?.trim()) {
      body = b.toString()
      used = rel
      break
    }
  }
  llmOut.add([
    id         : lid,
    studioPath : used,
    hasSource  : (body?.trim() ? true : false),
    byteLength : body?.getBytes('UTF-8')?.length ?: 0
  ] as Map)
}

List<Map> promptRows = []
for (String pk : ToolPromptsOverrideCatalog.KEYS) {
  if (!pk) {
    continue
  }
  String rel = "/scripts/aiassistant/prompts/${pk}.md"
  String src = ops.readStudioConfigurationUtf8(siteId, rel) ?: ''
  promptRows.add([
    key        : pk,
    studioPath : rel,
    hasOverride: (src?.trim() ? true : false),
    byteLength : src?.getBytes('UTF-8')?.length ?: 0
  ] as Map)
}

response.setContentType('application/json')
return [
  ok               : true,
  siteId           : siteId,
  registryStudioPath: registryRel,
  registryText     : registryText,
  tools            : toolsOut,
  imageGenerators  : imageOut,
  llmScripts       : llmOut,
  toolPromptOverrides: promptRows
]
