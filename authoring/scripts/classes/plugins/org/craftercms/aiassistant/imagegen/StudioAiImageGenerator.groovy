package plugins.org.craftercms.aiassistant.imagegen

import java.util.Map

/**
 * Pluggable image generation for the {@code GenerateImage} tool (built-in Images HTTP wire, site Groovy, etc.).
 * Implementations should return the same map shape as historical {@code GenerateImage}: {@code ok}, {@code tool},
 * {@code model}, {@code url} or {@code b64_json}, optional {@code revised_prompt}, {@code error}, {@code message}.
 */
interface StudioAiImageGenerator {

  Map generate(Map input, StudioAiImageGenContext ctx)
}
