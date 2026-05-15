package plugins.org.craftercms.aiassistant.llm

/**
 * Selects a {@link StudioAiLlmRuntime} from a normalized kind ({@link StudioAiLlmKind}).
 */
final class StudioAiLlmRuntimeFactory {

  private StudioAiLlmRuntimeFactory() {}

  static StudioAiLlmRuntime runtimeFor(String normalizedKind) {
    String n = (normalizedKind ?: '').toString()
    if (StudioAiLlmKind.isScriptHostedLlm(n)) {
      return new StudioAiScriptLlmContainerRuntime(StudioAiLlmKind.scriptLlmIdFromNormalized(n))
    }
    if (StudioAiLlmKind.useToolsLoopChatRestClientBuiltInKinds(n)) {
      return OpenAiSpringAiLlmRuntime.INSTANCE
    }
    if (StudioAiLlmKind.isAnthropicClaude(n)) {
      return AnthropicSpringAiLlmRuntime.INSTANCE
    }
    throw new IllegalStateException(
      "Unsupported normalized llm kind '${n}'. Expected a value produced by StudioAiLlmKind.normalize (openAI, claude, script:…, etc.)."
    )
  }
}
