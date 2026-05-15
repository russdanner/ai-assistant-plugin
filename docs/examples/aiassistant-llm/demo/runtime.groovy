// Copy to: config/studio/scripts/aiassistant/llm/demo/runtime.groovy
// Agent ui.xml: <llm>script:demo</llm>  (normalized scriptLlm:demo)
//
// Minimal illustration: temporarily re-targets llmNormalized to the built-in OpenAI runtime for one call.
// For production-style script LLMs that fully own keys, base URL, and ChatModel construction, see
// docs/using-and-extending/script-llm-bring-your-own-backend.md and docs/examples/aiassistant-llm/byo-openai-compat/runtime.groovy (tools-loop custom-host sample)
// (StudioAiScriptLlmContainerRuntime overwrites bundle.llm to scriptLlm:demo after this closure returns).

import plugins.org.craftercms.aiassistant.llm.OpenAiSpringAiLlmRuntime
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.llm.StudioAiRuntimeBuildRequest

[
  supportsNativeStudioTools: true,
  normalizedKind          : StudioAiLlmKind.SCRIPT_LLM_PREFIX + llmId,
  buildSessionBundle      : { StudioAiRuntimeBuildRequest r ->
    StudioAiRuntimeBuildRequest sub = new StudioAiRuntimeBuildRequest()
    sub.orchestration = r.orchestration
    sub.toolResultConverter = r.toolResultConverter
    sub.studioOps = r.studioOps
    sub.studioServletRequest = r.studioServletRequest
    sub.agentId = r.agentId
    sub.chatId = r.chatId
    sub.llmNormalized = StudioAiLlmKind.OPENAI_NATIVE
    sub.openAiModelParam = r.openAiModelParam
    sub.openAiApiKeyFromRequest = r.openAiApiKeyFromRequest
    sub.toolProgressListener = r.toolProgressListener
    sub.imageModelParam = r.imageModelParam
    sub.imageGeneratorParam = r.imageGeneratorParam
    sub.fullSuppressRepoWrites = r.fullSuppressRepoWrites
    sub.protectedFormItemPath = r.protectedFormItemPath
    sub.enableTools = r.enableTools
    sub.agentEnabledBuiltInTools = r.agentEnabledBuiltInTools
    OpenAiSpringAiLlmRuntime.INSTANCE.buildSessionBundle(sub)
  }
]
