package plugins.org.craftercms.aiassistant.tools.spi

import org.springframework.ai.tool.function.FunctionToolCallback
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools

import java.util.function.Function

/**
 * Base class for {@link StudioAiOrchestrationTool} Groovy implementations under {@code tools.cms}, etc.
 */
abstract class AbstractStudioAiTool implements StudioAiOrchestrationTool {

  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return true
  }

  @Override
  String pipelineStage() {
    return null
  }

  /** Builds a Spring AI {@link FunctionToolCallback} for this tool. */
  Object toFunctionToolCallback(StudioAiToolContext ctx) {
    final String name = wireName()
    final StudioAiToolContext buildCtx = ctx
    return FunctionToolCallback.builder(name, new Function<Map, Map>() {
      @Override
      Map apply(Map input) {
        return AiOrchestrationTools.runWithToolProgress(name, input, buildCtx.toolProgressListener, {
          execute((Map) (input ?: [:]), buildCtx)
        })
      }
    })
      .description(description())
      .inputSchema(inputSchemaJson())
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', ctx.converter)
      .build()
  }
}
