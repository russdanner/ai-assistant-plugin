package plugins.org.craftercms.aiassistant.tools.spi

import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools

/**
 * Shared helpers for tool implementations (delegates to {@link AiOrchestrationTools}).
 */
final class StudioAiToolSupport {

  private StudioAiToolSupport() {}

  static String repoPathFromToolInput(Map input) {
    return AiOrchestrationTools.repoPathFromToolInput(input)
  }

  static String extractContentTypeIdFromItemXml(String xml) {
    return AiOrchestrationTools.extractContentTypeIdFromItemXml(xml)
  }

  static List<String> extractFormFieldIdsFromFormDefinitionXml(String formXml) {
    return AiOrchestrationTools.extractFormFieldIdsFromFormDefinitionXml(formXml)
  }
}
