// Copy to: config/studio/scripts/aiassistant/llm/byo-openai-compat/runtime.groovy
// Agent: <llm>script:byo-openai-compat</llm>
// Script id "byo-openai-compat" matches the folder name under `llm/`; this sample is a tools-loop (tools-compatible) custom chat host.
//
// Full vendor replacement: this script builds the entire Spring AI session (library chat types + AiOrchestrationTools)
// for Studio’s tools-loop chat. It does NOT delegate to the plugin’s built-in Spring chat LLM runtimes.
// Spring AI is vendor-neutral; OpenAi* types here are the spring-ai-openai module’s client for one HTTP JSON shape — your
// base URL + model id are whatever vendor you configure (not necessarily OpenAI Inc.).
//
// Configure Studio (host-only base URL, no trailing /v1). Plugin env names:
//   export SCRIPT_LLM_OPENAI_COMPAT_BASE_URL=https://api.example.com
//   export SCRIPT_LLM_API_KEY=...
// Per-agent chat model: <llmModel> or POST llmModel → req.llmModelParam (Studio request field name)
// Testing-only key from widget: optional agent <llmApiKey> → req.llmApiKeyFromRequest
//
// Optional session-bundle tuning (same keys as the Groq sample): `toolsLoopChatPreferMaxCompletionTokens`,
// `toolsLoopChatMaxCompletionOutTokens`, `toolsLoopChatMaxWirePayloadChars` — add to the returned map if your host
// requires `max_completion_tokens` or a serialized tools-loop size cap; see StudioAiLlmKind / script-llm-bring-your-own-backend.md.

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.DefaultChatClientBuilder
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi

import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmRuntime
import plugins.org.craftercms.aiassistant.llm.StudioAiRuntimeBuildRequest
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools

/**
 * Bring-your-own tools-loop chat host: any vendor whose HTTP API matches what Spring {@code OpenAiApi} expects.
 */
class BringYourOwnToolsLoopHostRuntime implements StudioAiLlmRuntime {

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(BringYourOwnToolsLoopHostRuntime.class)

  private final String scriptLlmId

  BringYourOwnToolsLoopHostRuntime(String scriptLlmId) {
    this.scriptLlmId = (scriptLlmId ?: 'byo-openai-compat').toString()
  }

  @Override
  String normalizedKind() {
    return StudioAiLlmKind.SCRIPT_LLM_PREFIX + scriptLlmId
  }

  @Override
  boolean supportsNativeStudioTools() {
    return true
  }

  private static String compatBaseUrl() {
    String u = System.getenv('SCRIPT_LLM_OPENAI_COMPAT_BASE_URL')?.toString()?.trim()
    return u ? u.replaceAll(/\/+$/, '') : ''
  }

  private static String compatApiKey(StudioAiRuntimeBuildRequest req) {
    String k = System.getenv('SCRIPT_LLM_API_KEY')?.toString()?.trim()
    if (!k) {
      k = (req.llmApiKeyFromRequest ?: '').toString().trim()
    }
    return k
  }

  @Override
  Map buildSessionBundle(StudioAiRuntimeBuildRequest req) {
    String base = compatBaseUrl()
    String apiKey = compatApiKey(req)
    if (!base) {
      throw new IllegalStateException(
        'Script LLM byo-openai-compat: set tools-loop chat base URL — SCRIPT_LLM_OPENAI_COMPAT_BASE_URL (host only, no trailing /v1).'
      )
    }
    if (!apiKey) {
      throw new IllegalStateException(
        'Script LLM byo-openai-compat: set SCRIPT_LLM_API_KEY on Studio, or agent <llmApiKey> for local testing only.'
      )
    }
    String modelName = (req.llmModelParam ?: 'gpt-4o-mini').toString().trim()
    def orch = req.orchestration
    def imageModel = AiOrchestration.imageModelFromRequestOrNull(req.imageModelParam)
    String builtInImageAndEmbeddingKey = AiOrchestration.resolveOpenAiApiKey(null)
    def tools
    if (req.enableTools) {
      def expertSpecs = orch.readExpertSkillSpecsFromRequest()
      tools = AiOrchestrationTools.build(
        req.toolResultConverter,
        req.studioOps,
        req.toolProgressListener,
        builtInImageAndEmbeddingKey,
        imageModel,
        req.fullSuppressRepoWrites,
        req.protectedFormItemPath,
        expertSpecs,
        modelName,
        req.llmNormalized,
        req.imageGeneratorParam,
        req.agentEnabledBuiltInTools
      )
    } else {
      tools = []
    }
    def toolsLoopHttpApi = OpenAiApi.builder().baseUrl(base).apiKey(apiKey).build()
    def options = OpenAiChatOptions.builder()
      .model(modelName)
      .internalToolExecutionEnabled(req.enableTools)
      .build()
    def chatModel = OpenAiChatModel.builder()
      .openAiApi(toolsLoopHttpApi)
      .defaultOptions(options)
      .build()
    def chatClient = new DefaultChatClientBuilder(chatModel).build()
    LOG.debug(
      'Script LLM byo-openai-compat: model={} enableTools={} wireBaseUrl={} apiKeyPreview={} apiKeyChars={}',
      modelName,
      req.enableTools,
      base,
      AiOrchestration.llmApiKeyLogPreview(apiKey),
      apiKey.length()
    )
    return [
      chatClient              : chatClient,
      chatModel               : chatModel,
      tools                   : tools,
      llm                     : normalizedKind(),
      useTools                : req.enableTools,
      studioOps               : req.studioOps,
      toolsLoopChatApiKey     : apiKey,
      toolsLoopChatBaseUrl    : base,
      resolvedChatModel       : modelName,
      nativeToolTransport     : 'toolsLoopWire'
    ]
  }
}

new BringYourOwnToolsLoopHostRuntime(llmId as String)
