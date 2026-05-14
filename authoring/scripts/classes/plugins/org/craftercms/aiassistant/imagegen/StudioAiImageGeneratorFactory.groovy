package plugins.org.craftercms.aiassistant.imagegen

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.llm.StudioAiProviderCredentials
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.util.Locale
import java.util.Map

/**
 * Resolves which {@link StudioAiImageGenerator} backs the {@code GenerateImage} tool for a session.
 */
final class StudioAiImageGeneratorFactory {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiImageGeneratorFactory.class)

  private StudioAiImageGeneratorFactory() {}

  /**
   * @param imageGeneratorSpec agent / request {@code imageGenerator}: blank (default), {@code openAiWire},
   *        {@code none}|{@code off}|{@code disabled}, or {@code script:id}
   * @param openAiImagesApiKey key used for the built-in Images API wire (historically {@link AiOrchestration#resolveOpenAiApiKey})
   * @param defaultImageModel agent/request image model id when applicable
   */
  static StudioAiImageGenerator resolve(
    StudioToolOperations ops,
    String llmNormalized,
    String imageGeneratorSpec,
    String openAiImagesApiKey,
    String defaultImageModel
  ) {
    String spec = (imageGeneratorSpec ?: '').toString().trim()
    String sl = spec.toLowerCase(Locale.US)
    if ('none' == sl || 'off' == sl || 'disabled' == sl) {
      return null
    }
    if (sl.startsWith('script:')) {
      String id = sl.substring('script:'.length()).trim().toLowerCase(Locale.US)
      return new ScriptImageGenerator(ops, id)
    }
    if (spec && !('openaiwire' == sl || 'open_ai_wire' == sl || 'openai' == sl || 'toolsloopwire' == sl || 'tools_loop_wire' == sl)) {
      LOG.warn(
        'StudioAiImageGeneratorFactory: unrecognized imageGenerator="{}" — falling back to built-in images wire when an images API key and imageModel are configured.',
        spec
      )
    }
    String key = (openAiImagesApiKey ?: '').toString().trim()
    if (!key) {
      return null
    }
    if (!AiOrchestration.imageModelFromRequestOrNull(defaultImageModel)) {
      return null
    }
    return new OpenAiCompatibleImageGenerator()
  }

  static StudioAiImageGenContext buildContext(
    StudioToolOperations ops,
    String llmNormalized,
    String imageGeneratorSpec,
    String openAiImagesApiKey,
    String defaultImageModel
  ) {
    String siteId = ''
    try {
      siteId = ops != null ? ops.resolveEffectiveSiteId('') : ''
    } catch (Throwable ignored) {
    }
    String key = (openAiImagesApiKey ?: '').toString().trim()
    String postUrl = StudioAiProviderCredentials.httpOpenAiImagesGenerationsUrl()
    return new StudioAiImageGenContext(
      ops,
      siteId,
      (llmNormalized ?: '').toString(),
      defaultImageModel ?: '',
      key,
      postUrl,
      (imageGeneratorSpec ?: '').toString()
    )
  }

  private static final class ScriptImageGenerator implements StudioAiImageGenerator {

    private final StudioToolOperations ops
    private final String id

    ScriptImageGenerator(StudioToolOperations ops, String id) {
      this.ops = ops
      this.id = id
    }

    @Override
    Map generate(Map input, StudioAiImageGenContext ctx) {
      def cl = StudioAiScriptImageGenLoader.loadGenerateClosure(ops, id)
      Map ctxMap = ctx != null ? ctx.asMap() : [:]
      Object r = cl.call((Map) (input ?: [:]), ctxMap)
      if (r instanceof Map) {
        return (Map) r
      }
      return [error: true, message: "Script image generator '${id}' returned non-Map: ${r?.class?.name}"]
    }
  }
}
