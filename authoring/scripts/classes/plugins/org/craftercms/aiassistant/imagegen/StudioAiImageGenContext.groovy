package plugins.org.craftercms.aiassistant.imagegen

import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Immutable context passed to {@link StudioAiImageGenerator#generate} and site Groovy image backends.
 */
final class StudioAiImageGenContext {

  final StudioToolOperations studioOps
  final String siteId
  final String llmNormalized
  final String defaultImageModel
  /** API key for the built-in Images wire when applicable; may be blank for pure script backends. */
  final String imagesApiKey
  /** Absolute POST URL for {@code /v1/images/generations} on the built-in wire when applicable. */
  final String imagesGenerationsHttpUrl
  /** Raw agent/request {@code imageGenerator} spec (e.g. blank, {@code openAiWire}, {@code script:mygen}). */
  final String generatorSpec

  StudioAiImageGenContext(
    StudioToolOperations studioOps,
    String siteId,
    String llmNormalized,
    String defaultImageModel,
    String imagesApiKey,
    String imagesGenerationsHttpUrl,
    String generatorSpec
  ) {
    this.studioOps = studioOps
    this.siteId = (siteId ?: '').toString()
    this.llmNormalized = (llmNormalized ?: '').toString()
    this.defaultImageModel = (defaultImageModel ?: '').toString()
    this.imagesApiKey = (imagesApiKey ?: '').toString()
    this.imagesGenerationsHttpUrl = (imagesGenerationsHttpUrl ?: '').toString()
    this.generatorSpec = (generatorSpec ?: '').toString()
  }

  Map asMap() {
    Map m = new LinkedHashMap()
    m.put('siteId', siteId)
    m.put('llmNormalized', llmNormalized)
    m.put('defaultImageModel', defaultImageModel)
    m.put('imagesApiKey', imagesApiKey)
    m.put('imagesGenerationsHttpUrl', imagesGenerationsHttpUrl)
    m.put('generatorSpec', generatorSpec)
    m
  }
}
