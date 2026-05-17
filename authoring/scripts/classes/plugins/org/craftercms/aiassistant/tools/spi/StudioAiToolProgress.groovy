package plugins.org.craftercms.aiassistant.tools.spi

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.orchestration.chatcompletions.ChatCompletionsToolWire

/**
 * Optional SSE progress wrapper around native tool execution.
 */
final class StudioAiToolProgress {

  private static final Logger log = LoggerFactory.getLogger(StudioAiToolProgress)

  private StudioAiToolProgress() {}

  /**
   * {@code listener} signature: {@code (toolName, phase, inputMap, errorOrNull, toolResultOrNull, elapsedMsOrNull)} —
   * {@code phase} is {@code start}, {@code done}, {@code warn}, or {@code error}.
   */
  static Map runWithToolProgress(String toolName, Map rawInput, Closure listener, Closure work) {
    Map input = (rawInput != null) ? rawInput : [:]
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase("tool_skipped_pipeline_cancelled name=${toolName}")
      log.warn(
        'AI Assistant tool skipped (author Stop / SSE disconnect / pipeline cancel or worker interrupt): tool={}',
        toolName
      )
      return [
        ok       : false,
        error    : true,
        cancelled: true,
        message  : 'Request was stopped; this tool call was not executed (no repository or side-effect work performed).',
        tool     : toolName
      ] as Map
    }
    long t0 = System.nanoTime()
    if (listener) {
      try {
        listener.call(toolName, 'start', input, null, null, null)
      } catch (Throwable ignored) {
      }
    }
    try {
      def result = work.call()
      if ('GenerateImage'.equals(toolName) && result instanceof Map) {
        String wireTcId = ChatCompletionsToolWire.nativeToolCallIdBindingGet()
        if (wireTcId && !((Map) result).inlineImageRef) {
          Map enriched = new LinkedHashMap<>((Map) result)
          enriched.put('inlineImageRef', wireTcId)
          result = enriched
        }
      }
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L
      if (listener) {
        try {
          String phase = isToolResultWarning(result) ? 'warn' : 'done'
          listener.call(toolName, phase, input, null, result, elapsedMs)
        } catch (Throwable ignored2) {
        }
      }
      return (Map) result
    } catch (Throwable t) {
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L
      if (listener) {
        try {
          listener.call(toolName, 'error', input, t, null, elapsedMs)
        } catch (Throwable ignored3) {
        }
      }
      throw t
    }
  }

  private static boolean isToolResultWarning(Object result) {
    if (!(result instanceof Map)) {
      return false
    }
    Map m = (Map) result
    if (Boolean.TRUE.equals(m.error) || 'true'.equalsIgnoreCase(m.error?.toString())) {
      return true
    }
    if (m.skippedReason) {
      return true
    }
    if (m.containsKey('ok')) {
      def ok = m.ok
      if (ok instanceof Boolean && !((Boolean) ok)) {
        return true
      }
      if (ok != null && 'false'.equalsIgnoreCase(ok.toString())) {
        return true
      }
    }
    return false
  }
}
