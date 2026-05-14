package plugins.org.craftercms.aiassistant.llm

/**
 * Selects a {@link StudioAiLlmRuntime} from a normalized kind ({@link StudioAiLlmKind}).
 */
final class StudioAiLlmRuntimeFactory {

  private StudioAiLlmRuntimeFactory() {}

  static StudioAiLlmRuntime runtimeFor(String normalizedKind) {
    if (StudioAiLlmKind.isScriptHostedLlm(normalizedKind)) {
      return new StudioAiScriptLlmContainerRuntime(StudioAiLlmKind.scriptLlmIdFromNormalized(normalizedKind))
    }
    if (StudioAiLlmKind.useToolsLoopChatRestClientBuiltInKinds(normalizedKind)) {
      return OpenAiSpringAiLlmRuntime.INSTANCE
    }
    if (StudioAiLlmKind.isAnthropicClaude(normalizedKind)) {
      return AnthropicSpringAiLlmRuntime.INSTANCE
    }
    return ExpertApiLlmRuntime.INSTANCE
  }
}
