package plugins.org.craftercms.aiassistant.config

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Reads optional Studio {@code studio} module text from the site sandbox (same paths as
 * {@link plugins.org.craftercms.aiassistant.tools.StudioToolOperations#readStudioConfigurationUtf8})
 * without a full {@link plugins.org.craftercms.aiassistant.tools.StudioToolOperations} instance.
 * Used for project-local AI Assistant overrides (prompts, tool policy JSON, etc.).
 */
final class StudioAiSiteModuleText {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiSiteModuleText.class)

  private StudioAiSiteModuleText() {}

  private static String toSandboxConfigStudioRepoPath(String studioModuleRelativePath) {
    String p = (studioModuleRelativePath ?: '').toString().trim()
    if (!p.startsWith('/')) {
      p = "/${p}"
    }
    return "/config/studio${p}"
  }

  /**
   * @param studioModulePath path under module {@code studio}, e.g. {@code /scripts/aiassistant/config/tools.json}
   * @return file UTF-8 text, or {@code null} when missing / empty / unreadable
   */
  static String readUtf8IfPresent(Object applicationContext, String siteId, String studioModulePath) {
    if (applicationContext == null || siteId == null || !siteId.toString().trim()) {
      return null
    }
    String site = siteId.toString().trim()
    String path = (studioModulePath ?: '').toString().trim()
    if (!path.startsWith('/')) {
      path = "/${path}"
    }
    Object configurationServiceBean = null
    Object cstudioContentServiceBean = null
    try {
      configurationServiceBean = applicationContext.get('configurationService')
    } catch (Throwable ignored) {
    }
    try {
      cstudioContentServiceBean = applicationContext.get('cstudioContentService')
    } catch (Throwable ignored2) {
    }
    if (configurationServiceBean == null) {
      return null
    }
    String sandboxRepoPath = toSandboxConfigStudioRepoPath(path)
    try {
      Object v1 = cstudioContentServiceBean
      if (v1 != null && v1.metaClass.respondsTo(v1, 'contentExists', String, String)) {
        Object exists = v1.contentExists(site, sandboxRepoPath)
        if (!(exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists))) {
          return null
        }
      } else if (v1 != null && v1.metaClass.respondsTo(v1, 'shallowContentExists', String, String)) {
        Object exists = v1.shallowContentExists(site, sandboxRepoPath)
        if (!(exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists))) {
          return null
        }
      }
    } catch (Throwable probeIgnored) {
    }
    try {
      String text = configurationServiceBean.getConfigurationAsString(site, 'studio', path, '')
      if (text == null || !text.toString().trim()) {
        return null
      }
      return text.toString()
    } catch (Throwable t) {
      LOG.debug('StudioAiSiteModuleText.readUtf8IfPresent failed siteId={} path={}: {}', site, path, t.message)
      return null
    }
  }
}
