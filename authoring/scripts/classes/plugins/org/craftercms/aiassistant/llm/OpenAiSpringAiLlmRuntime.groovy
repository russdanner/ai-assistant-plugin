package plugins.org.craftercms.aiassistant.llm

import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.DefaultChatClientBuilder
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi

/**
 * LLM runtime for the built-in <strong>{@link StudioAiLlmKind#OPENAI_NATIVE}</strong> row (<strong>OpenAI</strong> vendor)
 * and the other built-in <strong>tools-loop</strong> rows (<strong>xAI</strong>, <strong>deepSeek</strong>, <strong>llama</strong>, <strong>gemini</strong>)
 * via Spring AI {@link OpenAiChatModel} plus this plugin’s tools-loop Spring {@code RestClient} native-tool execution in
 * {@link AiOrchestration}.
 */
class OpenAiSpringAiLlmRuntime implements StudioAiLlmRuntime {

  private static final Logger log = LoggerFactory.getLogger(OpenAiSpringAiLlmRuntime.class)

  static final OpenAiSpringAiLlmRuntime INSTANCE = new OpenAiSpringAiLlmRuntime()

  private OpenAiSpringAiLlmRuntime() {}

  @Override
  String normalizedKind() {
    return StudioAiLlmKind.OPENAI_NATIVE
  }

  @Override
  boolean supportsNativeStudioTools() {
    return true
  }

  @Override
  Map buildSessionBundle(StudioAiRuntimeBuildRequest req) {
    def orch = req.orchestration
    String llmNorm = (req.llmNormalized ?: StudioAiLlmKind.OPENAI_NATIVE).toString()
    String apiKey = StudioAiProviderCredentials.resolveApiKey(llmNorm, req.llmApiKeyFromRequest)
    if (!apiKey?.trim()) {
      throw new IllegalStateException(StudioAiProviderCredentials.missingApiKeyMessage(llmNorm))
    }
    if (StudioAiProviderCredentials.isLikelyWidgetOnlyServerKeyMissing(llmNorm, apiKey, req.llmApiKeyFromRequest)) {
      log.warn(
        'API key is taken from widget/request (testing path). llm={} apiKeyPreview={} apiKeyChars={}. Prefer server env/JVM keys for production.',
        llmNorm,
        AiOrchestration.llmApiKeyLogPreview(apiKey),
        apiKey.length()
      )
    }
    String modelName = StudioAiProviderCredentials.resolveChatModelId(llmNorm, req.llmModelParam)
    String wireBase = StudioAiProviderCredentials.wireLlmRestBaseUrl(llmNorm)
    def imageModel = AiOrchestration.imageModelFromRequestOrNull(req.imageModelParam)
    String llmOnlyImageKey = AiOrchestration.resolveLlmApiKey(null)
    def tools
    if (req.enableTools) {
      def expertSpecs = orch.readExpertSkillSpecsFromRequest()
      tools = AiOrchestrationTools.build(
        req.toolResultConverter,
        req.studioOps,
        req.toolProgressListener,
        llmOnlyImageKey,
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
    def llmApi = OpenAiApi.builder().baseUrl(wireBase).apiKey(apiKey).build()
    def options = OpenAiChatOptions.builder()
      .model(modelName)
      .internalToolExecutionEnabled(req.enableTools)
      .build()
    def chatModel = OpenAiChatModel.builder()
      .openAiApi(llmApi)
      .defaultOptions(options)
      .build()
    def chatClient = new DefaultChatClientBuilder(chatModel).build()
    log.debug(
      'Spring AI chat client: provider={} model={} imageModel={} enableTools={} wireBaseUrl={} apiKeySource={} apiKeyPreview={} apiKeyChars={}',
      llmNorm,
      modelName,
      imageModel ?: '(unset)',
      req.enableTools,
      wireBase,
      StudioAiProviderCredentials.apiKeyResolutionSourceForLog(llmNorm),
      AiOrchestration.llmApiKeyLogPreview(apiKey),
      apiKey.length()
    )
    return [
      chatClient              : chatClient,
      chatModel               : chatModel,
      tools                   : tools,
      llm                     : llmNorm,
      useTools                : req.enableTools,
      studioOps               : req.studioOps,
      toolsLoopChatApiKey     : apiKey,
      toolsLoopChatBaseUrl    : wireBase,
      resolvedChatModel       : modelName
    ]
  }
}
