import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.http.CrafterQBearerUiXmlMerge
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsSiteContext
import plugins.org.craftercms.aiassistant.rag.ExpertSkillVectorRegistry

/**
 * Minimal proxy for assistant chat (non-streaming).
 *
 * Routes through {@link AiOrchestration}: remote hosted chat when {@code llm} resolves to {@link StudioAiLlmKind#CRAFTERRQ_REMOTE_API},
 * or Spring AI (tools-loop chat, Claude, site script LLM, etc.) when configured.
 *
 * Body:
 * {
 *   "agentId": "...",
 *   "prompt": "...",
 *   "llm": "required on the wire (POST body or copied from matching <agent> in /ui.xml when siteId+agentId); missing/blank/unknown → 400",
 *   "chatId": "optional",
 *   "contentPath": "optional Studio preview repo path",
 *   "contentTypeId": "optional",
 *   "contentTypeLabel": "optional Studio UI label for the open item’s type",
 *   "studioPreviewPageUrl": "optional — Studio XB address bar `…/studio/preview#/?page=…&site=…` when available",
 *   "authoringSurface": "optional — formEngine for content-type form assistant",
 *   "formEngineClientJsonApply": "optional boolean — only with formEngine; XB omits",
 *   "formEngineItemPath": "optional repo path of open form item — path-scoped write blocking when using client JSON apply",
 *   "enableTools": "optional — false omits OpenAI function tools; absent defaults true",
 *   "omitTools": "optional — true omits tools for this request only (focused copy/generation); overrides enableTools; same for XB/ICE, dialog, form-engine",
 *   "previewToken": "optional — Studio crafterPreview cookie value for GetPreviewHtml",
 *   "expertSkills": "optional array of { name, url, description } — per-agent markdown RAG for QueryExpertGuidance",
 *   "crafterQBearerTokenEnv": "optional — Studio host env var name for CrafterQ JWT (Authorization: Bearer on api.crafterq.ai)",
 *   "crafterQBearerToken": "optional — literal CrafterQ JWT (discouraged in Git; prefer crafterQBearerTokenEnv)",
 *   "llmModel": "optional — model id for the selected LLM",
 *   "imageModel": "optional — default image model for GenerateImage on the built-in images wire",
 *   "imageGenerator": "optional — GenerateImage backend (blank = default when key+imageModel exist; none; script:{id}); see llm-configuration.md"
 * }
 */

def log = LoggerFactory.getLogger('plugins.org.craftercms.aiassistant.chat')
def body = AiHttpProxy.parseJsonBody(request)
if (Boolean.TRUE.equals(body?.get('__crafterqInvalidJson'))) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [message: 'Invalid JSON request body', detail: body?.get('__crafterqInvalidJsonDetail')?.toString() ?: '']
}
def agentId = body.agentId != null ? body.agentId.toString().trim() : ''
def prompt = body.prompt?.toString()
def siteIdBody = body.siteId?.toString()?.trim()
def promptForOrchestration
if (AuthoringPreviewContext.isFormEngineSurface(body?.authoringSurface)) {
  promptForOrchestration = AuthoringPreviewContext.appendFormEngineAuthoringNotice(prompt)
  if (AuthoringPreviewContext.isTruthy(body?.formEngineClientJsonApply)) {
    promptForOrchestration = AuthoringPreviewContext.appendFormEngineClientJsonApplyInstructions(promptForOrchestration)
  }
} else {
  promptForOrchestration = AuthoringPreviewContext.appendToUserPrompt(prompt, body?.contentPath, body?.contentTypeId, body?.contentTypeLabel)
  promptForOrchestration = AuthoringPreviewContext.appendEnginePreviewHintIfPossible(
    promptForOrchestration, request, siteIdBody ?: params?.siteId, body?.contentPath, body?.studioPreviewPageUrl)
}
def chatId = body.chatId?.toString()
def openAiApiKey = body.openAiApiKey?.toString()
if (siteIdBody) {
  try {
    request.setAttribute('crafterq.siteId', siteIdBody)
  } catch (Throwable ignored) {}
}
def previewTokenBody = body?.previewToken?.toString()?.trim()
if (previewTokenBody) {
  try {
    request.setAttribute('crafterq.previewToken', previewTokenBody)
  } catch (Throwable ignored) {}
}
def expertSkillsNorm = ExpertSkillVectorRegistry.normalizeRequestExpertSkills(body?.expertSkills)
try {
  request.setAttribute('crafterq.expertSkills', expertSkillsNorm)
} catch (Throwable ignored) {}
def siteForBearer = siteIdBody ?: params?.siteId?.toString()?.trim()
if (body instanceof Map && siteForBearer && agentId) {
  try {
    CrafterQBearerUiXmlMerge.mergeStreamAgentFieldsFromSiteUiXmlIfMissing(applicationContext, (Map) body, siteForBearer, agentId)
  } catch (Throwable mergeEx) {
    log.debug('Agent ui.xml merge skipped: {}', mergeEx.message ?: mergeEx.toString())
  }
}
def llm = body.llm?.toString()
if (body instanceof Map) {
  AiHttpProxy.installCrafterQBearerFromChatBody(request, (Map) body, llm ?: '')
}
def openAiModel = body.llmModel?.toString()
def imageModelRaw = body.imageModel?.toString()
def imageModel = null
if (imageModelRaw?.trim()) {
  imageModel = AiOrchestration.normalizeOpenAiImagesApiModelId(imageModelRaw.trim())
  if (body instanceof Map) {
    try {
      body.put('imageModel', imageModel)
    } catch (Throwable ignoredIm) {
    }
  }
}

def imageGenerator = body?.imageGenerator?.toString()?.trim() ?: null

String llmNorm
try {
  llmNorm = AiOrchestration.normalizeLlmProvider(llm)
} catch (IllegalArgumentException iae) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: (iae.message ?: 'Invalid llm').toString()]
}

if ((!agentId && StudioAiLlmKind.isCrafterQRemoteApi(llmNorm)) || !prompt) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [message: (!agentId && StudioAiLlmKind.isCrafterQRemoteApi(llmNorm))
    ? 'Missing required fields: agentId (required for the default remote hosted chat adapter), prompt'
    : 'Missing required fields: prompt']
}

try {
  String siteForPrompts = (siteIdBody ?: params?.siteId?.toString()?.trim() ?: '')
  ToolPromptsSiteContext.enter(applicationContext, siteForPrompts)
  try {
    def formEngineClientForward = AuthoringPreviewContext.isFormEngineSurface(body?.authoringSurface) && AuthoringPreviewContext.isTruthy(body?.formEngineClientJsonApply)
    def formEngineItemPathRaw = body?.formEngineItemPath?.toString()
    def omitTools = AuthoringPreviewContext.isTruthy(body?.omitTools)
    def enableToolsRequested = AuthoringPreviewContext.parseEnableTools(body?.enableTools)
    def enableTools = omitTools ? false : enableToolsRequested
    def orchestration = new AiOrchestration(request, response, applicationContext, params, pluginConfig)
    return orchestration.chatProxy(agentId, promptForOrchestration, chatId, llm, openAiModel, openAiApiKey, imageModel, formEngineClientForward, formEngineItemPathRaw, enableTools, imageGenerator)
  } finally {
    ToolPromptsSiteContext.exit()
  }
} catch (IllegalStateException ise) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: ise.message ?: 'Configuration error']
} catch (Throwable e) {
  response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
  return [message: "Chat request failed: ${e.message ?: e.class.simpleName}"]
}
