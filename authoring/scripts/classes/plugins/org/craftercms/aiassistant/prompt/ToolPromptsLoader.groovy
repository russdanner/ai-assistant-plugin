package plugins.org.craftercms.aiassistant.prompt

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import plugins.org.craftercms.aiassistant.config.StudioAiSiteModuleText

import java.util.concurrent.ConcurrentHashMap

/**
 * <strong>Override mechanism</strong> for {@link ToolPrompts}: built-in Groovy strings remain the defaults; a
 * non-blank {@code KEY.md} replaces that key only. Omit the file (or leave it blank) to keep the shipped default —
 * no merge, no partial patch.
 * <p>Lookup order for each key (e.g. {@code GENERAL_LLM_AUTHORING_INSTRUCTIONS}):</p>
 * <ol>
 *   <li>When {@link ToolPromptsSiteContext} is active: site sandbox {@code /scripts/aiassistant/prompts/&lt;KEY&gt;.md}.</li>
 *   <li>Classpath resource {@link #CLASSPATH_PREFIX}{@code <KEY>.md} (e.g.
 *   {@code authoring/scripts/classes/plugins/org/craftercms/aiassistant/prompts/} in this repo).</li>
 *   <li>Peer resource next to this class, then classloaders, then {@code prompts/} next to compiled classes when
 *   the plugin is deployed as an expanded directory (typical development).</li>
 * </ol>
 * <p>If no override is found, {@link #resolve} returns the {@code defaultText} from {@link ToolPrompts}.</p>
 * <p><strong>Token parity:</strong> Blank or whitespace-only override files are ignored (same as missing), so the
 * prompt matches the built-in default unless the file contains real replacement text.</p>
 */
final class ToolPromptsLoader {

  private static final Logger log = LoggerFactory.getLogger(ToolPromptsLoader.class)
  private static final Map<String, String> CACHE = new ConcurrentHashMap<>()
  private static final String PACKAGE_DIR = 'plugins/org/craftercms/aiassistant/'

  /** Classpath prefix for resources (trailing slash). */
  static final String CLASSPATH_PREFIX = "${PACKAGE_DIR}prompts/"

  private ToolPromptsLoader() {}

  /**
   * @param key  stable id matching {@link ToolPromptsOverrideCatalog}, e.g. {@code GENERAL_LLM_AUTHORING_INSTRUCTIONS}
   * @param defaultText  built-in string when no override is present
   */
  static String resolve(String key, String defaultText) {
    if (key == null) {
      return defaultText
    }
    String cacheKey = cacheKeyFor(key)
    if (CACHE.containsKey(cacheKey)) {
      return CACHE.get(cacheKey)
    }
    String siteFirst = tryLoadFromSiteProject(key)
    if (siteFirst != null) {
      CACHE.put(cacheKey, siteFirst)
      return siteFirst
    }
    String s = tryLoadFromClasspathOrExpanded(key)
    if (s == null) {
      s = defaultText
    }
    CACHE.put(cacheKey, s)
    s
  }

  private static String cacheKeyFor(String key) {
    def ctx = ToolPromptsSiteContext.current()
    String site = ctx?.get('siteId')?.toString()?.trim()
    if (site) {
      return "site:${site}:${key}"
    }
    return key
  }

  private static String tryLoadFromSiteProject(String key) {
    def ctx = ToolPromptsSiteContext.current()
    if (ctx == null) {
      return null
    }
    Object app = ctx.get('applicationContext')
    String siteId = ctx.get('siteId')?.toString()?.trim()
    if (app == null || !siteId) {
      return null
    }
    String rel = "/scripts/aiassistant/prompts/${key}.md"
    try {
      String body = StudioAiSiteModuleText.readUtf8IfPresent(app, siteId, rel)
      def t = meaningfulOverrideOrNull(body)
      if (t != null) {
        log.debug('Tool prompt from site sandbox: {} ({} chars) siteId={}', key, t.length(), siteId)
      }
      return t
    } catch (Throwable ignored) {
      return null
    }
  }

  /**
   * Classpath / expanded-plugin {@code KEY.md} only (no site sandbox). For Studio “default” preview next to site overrides.
   */
  static String readClasspathPromptMarkdownOrNull(String key) {
    meaningfulOverrideOrNull(tryLoadFromClasspathOrExpanded(key))
  }

  /**
   * Text that applies when the site has no meaningful {@code /scripts/aiassistant/prompts/KEY.md}: classpath
   * {@code prompts/KEY.md} if present, else the Groovy literal registered via {@link ToolPromptsBuiltinDefaults}.
   */
  static String previewBaseTextWithoutSiteFile(String key) {
    String cp = readClasspathPromptMarkdownOrNull(key)
    if (cp != null) {
      return cp
    }
    String g = ToolPromptsBuiltinDefaults.getBuiltin(key)
    return g != null ? g : ''
  }

  /**
   * Runs the {@link ToolPrompts} static getter whose {@code p(key, …)} side effect registers the built-in literal for
   * {@code key}. Catalog keys are not always {@code get&lt;KEY&gt;()} (e.g. {@code CMS_CONTENT_DESC_GENERATE_IMAGE} →
   * {@code getDESC_GENERATE_IMAGE}).
   */
  static void ensureBuiltinRegisteredForCatalogKey(String key) {
    if (key == null || !key) {
      return
    }
    if (ToolPromptsBuiltinDefaults.getBuiltin(key) != null) {
      return
    }
    String mn = resolveBootstrapGetterNameForCatalogKey(key)
    if (!mn) {
      return
    }
    try {
      ToolPrompts.class.getMethod(mn).invoke(null)
    } catch (Throwable t) {
      log.debug('ToolPrompts bootstrap for key {} via {} failed: {}', key, mn, t.toString())
    }
  }

  private static String resolveBootstrapGetterNameForCatalogKey(String key) {
    if (key.startsWith('CMS_CONTENT_DESC_')) {
      return 'getDESC_' + key.substring('CMS_CONTENT_DESC_'.length())
    }
    if (key.startsWith('CMS_DEVELOPMENT_DESC_')) {
      return 'getDESC_' + key.substring('CMS_DEVELOPMENT_DESC_'.length())
    }
    if (key.startsWith('GENERAL_DESC_')) {
      return 'getDESC_' + key.substring('GENERAL_DESC_'.length())
    }
    if ('CRAFTERQ_DESC_GET_AGENT_CHAT' == key) {
      return 'getDESC_GET_CRAFTERQ_AGENT_CHAT'
    }
    if ('CRAFTERQ_DESC_LIST_AGENT_CHATS' == key) {
      return 'getDESC_LIST_CRAFTERQ_AGENT_CHATS'
    }
    if (key.startsWith('CRAFTERQ_DESC_')) {
      return 'getDESC_' + key.substring('CRAFTERQ_DESC_'.length())
    }
    if (key.startsWith('GENERAL_LLM_')) {
      return 'getLlm_' + key.substring('GENERAL_LLM_'.length())
    }
    if ('GENERAL_XML_REPAIR_REMINDER_AFTER_BAD_READ' == key) {
      return 'getXML_REPAIR_REMINDER_AFTER_BAD_READ'
    }
    if ('CMS_CONTENT_UPDATE_CONTENT' == key) {
      return 'getUPDATE_CONTENT'
    }
    if ('CMS_CONTENT_UPDATE_CONTENT_FORM_ENGINE' == key) {
      return 'getUPDATE_CONTENT_FORM_ENGINE'
    }
    if ('CMS_DEVELOPMENT_ANALYZE_TEMPLATE' == key) {
      return 'getANALYZE_TEMPLATE'
    }
    if ('CMS_DEVELOPMENT_UPDATE_TEMPLATE' == key) {
      return 'getUPDATE_TEMPLATE'
    }
    if ('CMS_DEVELOPMENT_UPDATE_TEMPLATE_FORM_ENGINE' == key) {
      return 'getUPDATE_TEMPLATE_FORM_ENGINE'
    }
    if ('CMS_DEVELOPMENT_UPDATE_CONTENT_TYPE' == key) {
      return 'getUPDATE_CONTENT_TYPE'
    }
    if ('CMS_DEVELOPMENT_UPDATE_CONTENT_TYPE_FORM_ENGINE' == key) {
      return 'getUPDATE_CONTENT_TYPE_FORM_ENGINE'
    }
    if ('CMS_CONTENT_TRANSFORM_SUBGRAPH_SYSTEM' == key) {
      return 'getTRANSFORM_CONTENT_SUBGRAPH_SYSTEM'
    }
    if ('CMS_CONTENT_TRANSLATE_ITEM_INNER_SYSTEM' == key) {
      return 'getTRANSLATE_CONTENT_ITEM_INNER_SYSTEM'
    }
    if ('CMS_CONTENT_TRANSLATE_ITEM_INNER_SYSTEM_RAW' == key) {
      return 'getTRANSLATE_CONTENT_ITEM_INNER_SYSTEM_RAW'
    }
    if ('CMS_CONTENT_TRANSLATE_ITEM_INNER_USER_APPENDIX' == key) {
      return 'getTRANSLATE_CONTENT_ITEM_INNER_USER_APPENDIX'
    }
    if ('CMS_CONTENT_TRANSLATE_ITEM_INNER_USER_APPENDIX_RAW' == key) {
      return 'getTRANSLATE_CONTENT_ITEM_INNER_USER_APPENDIX_RAW'
    }
    return 'get' + key
  }

  /** Non-blank text only; blank files must not replace a large built-in default with an empty string. */
  private static String meaningfulOverrideOrNull(String t) {
    if (t == null) {
      return null
    }
    if (!t.trim()) {
      return null
    }
    return t
  }

  /** For tests or hot-reload: clear cache so the next {@link #resolve} re-reads files. */
  static void clearCacheForTests() {
    CACHE.clear()
  }

  /**
   * After site sandbox edits to {@code /scripts/aiassistant/prompts/*.md}, drop cached prompt text so the next
   * {@link #resolve} re-reads from disk (covers {@code site:…:key} entries and bare {@code key} cache keys).
   */
  static void invalidateCachesAfterSitePromptMutation(String siteId) {
    String sid = (siteId ?: '').toString().trim()
    if (sid) {
      String prefix = "site:${sid}:"
      Iterator<String> it = CACHE.keySet().iterator()
      while (it.hasNext()) {
        String k = it.next()
        if (k != null && k.startsWith(prefix)) {
          it.remove()
        }
      }
    }
    for (String k : ToolPromptsOverrideCatalog.KEYS) {
      CACHE.remove(k)
    }
    log.debug('Tool prompt cache invalidated after site sandbox prompt edit siteId={}', sid ?: '(none)')
  }

  /**
   * @return override text, or {@code null} to use caller's {@code defaultText}
   */
  private static String tryLoadFromClasspathOrExpanded(String key) {
    // 1) Classpath: plugins/.../prompts/KEY.md
    def fromCp = meaningfulOverrideOrNull(readUtf8FromClasspath(CLASSPATH_PREFIX + key + '.md', ToolPromptsLoader))
    if (fromCp != null) {
      log.debug('Tool prompt from classpath: {} ({} chars)', key, fromCp.length())
      return fromCp
    }

    // 2) Peer to this class
    fromCp = meaningfulOverrideOrNull(readUtf8FromClasspath(key + '.md', ToolPromptsLoader))
    if (fromCp != null) {
      return fromCp
    }

    // 3) Classloader package path
    fromCp = meaningfulOverrideOrNull(readUtf8FromClassLoader((ToolPromptsLoader.class.classLoader), "${CLASSPATH_PREFIX}${key}.md"))
    if (fromCp != null) {
      return fromCp
    }

    // 4) Thread context classloader
    def cl = Thread.currentThread().contextClassLoader
    fromCp = meaningfulOverrideOrNull(readUtf8FromClassLoader(cl, "${CLASSPATH_PREFIX}${key}.md"))
    if (fromCp != null) {
      return fromCp
    }

    // 5) Expanded plugin: prompts/ next to this class
    try {
      def loc = ToolPromptsLoader.class.protectionDomain?.codeSource?.location
      if (loc != null) {
        def file = new File(loc.toURI())
        if (file.isDirectory()) {
          def promptsDir = new File(file, 'prompts')
          def candidate = new File(promptsDir, key + '.md')
          if (candidate.isFile()) {
            def t = meaningfulOverrideOrNull(candidate.getText('UTF-8'))
            if (t != null) {
              return t
            }
            log.warn('Tool prompt file is blank; ignoring: key={} path={}', key, candidate.absolutePath)
          }
        }
      }
    } catch (Throwable ignored) {
    }

    null
  }

  private static String readUtf8FromClasspath(String path, Class<?> anchor) {
    try {
      def is = anchor.getResourceAsStream(path)
      if (is != null) {
        try {
          return is.getText('UTF-8')
        } finally {
          try { is.close() } catch (Throwable ignored) { }
        }
      }
    } catch (Throwable ignored) {
    }
    null
  }

  private static String readUtf8FromClassLoader(ClassLoader cl, String path) {
    if (cl == null) {
      return null
    }
    try {
      def is = cl.getResourceAsStream(path)
      if (is != null) {
        try {
          return is.getText('UTF-8')
        } finally {
          try { is.close() } catch (Throwable ignored) { }
        }
      }
    } catch (Throwable ignored) {
    }
    null
  }
}
