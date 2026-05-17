package plugins.org.craftercms.aiassistant.llm

import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.client.DefaultChatClientBuilder

/**
 * LLM runtime: <strong>Anthropic Claude</strong> via Spring AI {@link AnthropicChatModel}.
 * Native CMS tools are executed by Spring AI (not the OpenAI RestClient loop in {@link AiOrchestration}).
 */
class AnthropicSpringAiLlmRuntime implements StudioAiLlmRuntime {

  private static final Logger log = LoggerFactory.getLogger(AnthropicSpringAiLlmRuntime.class)

  static final AnthropicSpringAiLlmRuntime INSTANCE = new AnthropicSpringAiLlmRuntime()

  private AnthropicSpringAiLlmRuntime() {}

  @Override
  String normalizedKind() {
    return StudioAiLlmKind.CLAUDE_NATIVE
  }

  @Override
  boolean supportsNativeStudioTools() {
    return true
  }

  @Override
  Map buildSessionBundle(StudioAiRuntimeBuildRequest req) {
    def orch = req.orchestration
    String apiKey = StudioAiProviderCredentials.resolveAnthropicApiKey(req.llmApiKeyFromRequest)
    if (!apiKey?.trim()) {
      throw new IllegalStateException(
        'LLM is Claude (Anthropic) but no API key was found. Set ANTHROPIC_API_KEY or JVM crafter.anthropic.apiKey on Studio. For local testing only, optional agent <llmApiKey> in ui.xml.'
      )
    }
    String modelName = StudioAiProviderCredentials.resolveAnthropicChatModel(req.llmModelParam)
    String llmOnlyImageKey = AiOrchestration.resolveLlmApiKey(null)
    def tools
    if (req.enableTools) {
      def expertSpecs = orch.readExpertSkillSpecsFromRequest()
      tools = AiOrchestrationTools.build(
        req.toolResultConverter,
        req.studioOps,
        req.toolProgressListener,
        llmOnlyImageKey,
        null,
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
    def anthropicApi = new AnthropicApi(AnthropicApi.DEFAULT_BASE_URL, apiKey)
    def options = AnthropicChatOptions.builder()
      .model(modelName)
      .internalToolExecutionEnabled(req.enableTools)
      .build()
    def chatModel = AnthropicChatModel.builder()
      .anthropicApi(anthropicApi)
      .defaultOptions(options)
      .build()
    def chatClient = new DefaultChatClientBuilder(chatModel).build()
    log.debug(
      'Spring AI chat client: provider=Anthropic/Claude model={} enableTools={} apiKeySource={} apiKeyPreview={} apiKeyChars={}',
      modelName,
      req.enableTools,
      StudioAiProviderCredentials.anthropicApiKeySourceForLog(),
      AiOrchestration.llmApiKeyLogPreview(apiKey),
      apiKey.length()
    )
    return [
      chatClient              : chatClient,
      chatModel               : chatModel,
      tools                   : tools,
      llm                     : StudioAiLlmKind.CLAUDE_NATIVE,
      useTools                : req.enableTools,
      studioOps               : req.studioOps,
      toolsLoopChatApiKey     : apiKey,
      toolsLoopChatBaseUrl    : null,
      resolvedChatModel       : modelName
    ]
  }
}
