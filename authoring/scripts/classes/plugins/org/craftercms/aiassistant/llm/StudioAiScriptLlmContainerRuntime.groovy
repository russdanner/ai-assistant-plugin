package plugins.org.craftercms.aiassistant.llm

/**
 * {@link StudioAiLlmKind#SCRIPT_LLM_PREFIX} runtime: loads the site Groovy delegate via {@link StudioAiScriptLlmLoader}
 * and forwards {@link #buildSessionBundle(StudioAiRuntimeBuildRequest)}. Ensures the returned bundle includes {@code llm}
 * when the script omits it.
 */
final class StudioAiScriptLlmContainerRuntime implements StudioAiLlmRuntime {

  private final String llmId

  StudioAiScriptLlmContainerRuntime(String llmId) {
    this.llmId = (llmId ?: '').toString().trim().toLowerCase(java.util.Locale.US)
  }

  @Override
  String normalizedKind() {
    return StudioAiLlmKind.SCRIPT_LLM_PREFIX + llmId
  }

  @Override
  boolean supportsNativeStudioTools() {
    return true
  }

  @Override
  Map buildSessionBundle(StudioAiRuntimeBuildRequest req) {
    def ops = req?.studioOps
    StudioAiLlmRuntime inner = StudioAiScriptLlmLoader.loadDelegateRuntime(ops, llmId)
    Map bundle = inner.buildSessionBundle(req)
    if (bundle == null) {
      throw new IllegalStateException("Script LLM '${llmId}' buildSessionBundle returned null.")
    }
    LinkedHashMap out = new LinkedHashMap<>((Map) bundle)
    out.put('llm', normalizedKind())
    return out
  }
}
