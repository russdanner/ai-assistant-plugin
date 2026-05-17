package plugins.org.craftercms.aiassistant.tools.spi

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Shared build-time and execute-time state for {@link StudioAiOrchestrationTool} implementations.
 */
class StudioAiToolContext {

  final Object converter
  final StudioToolOperations ops
  final Closure toolProgressListener
  final String apiKeyForImages
  final String imageModel
  final boolean fullSuppressRepoWrites
  final String normProtectedFormItemPath
  final boolean pathProtectFormItem
  final Map aiProjectToolCfg
  final List<Map> expertSkillSpecs
  final String textModel
  final String llmNormalized
  final String imageGeneratorParam
  final Collection agentEnabledBuiltInTools

  StudioAiToolContext(
    Object converter,
    StudioToolOperations ops,
    Closure toolProgressListener,
    String apiKeyForImages,
    String imageModel,
    boolean fullSuppressRepoWrites,
    String normProtectedFormItemPath,
    boolean pathProtectFormItem,
    Map aiProjectToolCfg,
    List<Map> expertSkillSpecs,
    String textModel,
    String llmNormalized,
    String imageGeneratorParam,
    Collection agentEnabledBuiltInTools
  ) {
    this.converter = converter
    this.ops = ops
    this.toolProgressListener = toolProgressListener
    this.apiKeyForImages = apiKeyForImages
    this.imageModel = imageModel
    this.fullSuppressRepoWrites = fullSuppressRepoWrites
    this.normProtectedFormItemPath = normProtectedFormItemPath
    this.pathProtectFormItem = pathProtectFormItem
    this.aiProjectToolCfg = aiProjectToolCfg
    this.expertSkillSpecs = expertSkillSpecs
    this.textModel = textModel
    this.llmNormalized = llmNormalized
    this.imageGeneratorParam = imageGeneratorParam
    this.agentEnabledBuiltInTools = agentEnabledBuiltInTools
  }

  static StudioAiToolContext fromBuildParams(
    Object converter,
    StudioToolOperations ops,
    Closure toolProgressListener = null,
    String apiKeyForImages = null,
    String imageModel = null,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPath = null,
    List<Map> expertSkillSpecs = null,
    String textModel = null,
    String llmNormalized = null,
    String imageGeneratorParam = null,
    Collection agentEnabledBuiltInTools = null
  ) {
    Map cfg = StudioAiAssistantProjectConfig.load(ops)
    String normProtected = AuthoringPreviewContext.normalizeRepoPath(protectedFormItemPath)
    boolean pathProtect = (normProtected?.length() ?: 0) > 0
    List<Map> experts = []
    if (expertSkillSpecs instanceof List) {
      for (Object o : (List) expertSkillSpecs) {
        if (o instanceof Map) {
          experts.add((Map) o)
        }
      }
    }
    return new StudioAiToolContext(
      converter,
      ops,
      toolProgressListener,
      apiKeyForImages,
      imageModel,
      fullSuppressRepoWrites,
      normProtected,
      pathProtect,
      cfg,
      experts,
      textModel,
      llmNormalized,
      imageGeneratorParam,
      agentEnabledBuiltInTools
    )
  }
}
