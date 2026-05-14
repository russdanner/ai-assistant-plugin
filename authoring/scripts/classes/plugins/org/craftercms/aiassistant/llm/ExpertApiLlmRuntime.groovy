package plugins.org.craftercms.aiassistant.llm

import plugins.org.craftercms.aiassistant.llm.remote.ExpertChatModel
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.DefaultChatClientBuilder

/**
 * LLM runtime: <strong>hosted expert / RAG API</strong> (e.g. api.crafterq.ai) via {@link ExpertChatModel} — content/RAG chat only;
 * CMS tools are not registered on this adapter.
 */
class ExpertApiLlmRuntime implements StudioAiLlmRuntime {

  private static final Logger log = LoggerFactory.getLogger(ExpertApiLlmRuntime.class)

  static final ExpertApiLlmRuntime INSTANCE = new ExpertApiLlmRuntime()

  private ExpertApiLlmRuntime() {}

  @Override
  String normalizedKind() {
    return StudioAiLlmKind.CRAFTERRQ_REMOTE_API
  }

  @Override
  boolean supportsNativeStudioTools() {
    return false
  }

  @Override
  Map buildSessionBundle(StudioAiRuntimeBuildRequest req) {
    def tools = AiOrchestrationTools.build(
      req.toolResultConverter,
      req.studioOps,
      req.toolProgressListener,
      null,
      null,
      false,
      null,
      null,
      null,
      null,
      null,
      null
    )
    int maxQ = req.orchestration.resolveMaxCrafterQPromptChars()
    def chatModel = new ExpertChatModel(req.agentId, req.chatId, req.crafterQServletRequest, maxQ)
    def chatClient = new DefaultChatClientBuilder(chatModel).build()
    log.debug('Spring AI chat client: provider=CrafterQ remote API agentId={}', req.agentId)
    return [
      chatClient              : chatClient,
      chatModel               : chatModel,
      tools                   : tools,
      llm                     : StudioAiLlmKind.CRAFTERRQ_REMOTE_API,
      useTools                : false,
      studioOps               : req.studioOps,
      toolsLoopChatApiKey     : null,
      toolsLoopChatBaseUrl    : null,
      resolvedChatModel       : null
    ]
  }
}
