package plugins.org.craftercms.aiassistant.playbook

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Loads {@link #PLAYBOOK_FILE_NAME} from the plugin package (classpath or same directory as compiled classes).
 * The markdown file lives beside this class under {@code authoring/scripts/classes/plugins/org/craftercms/aiassistant/}
 * and is copied with the plugin so operators can edit it without recompiling Groovy.
 */
class CrafterizingPlaybookLoader {

  private static final Logger log = LoggerFactory.getLogger(CrafterizingPlaybookLoader.class)

  static final String PLAYBOOK_FILE_NAME = 'CrafterizingPlaybook.md'

  /** Optional env/system property: absolute path to override playbook file (hotfix without redeploying classes dir). */
  static final String SYSPROP_PATH = 'aiassistant.crafterizingPlaybook.path'

  private static final String PACKAGE_RESOURCE_PREFIX = 'plugins/org/craftercms/aiassistant/playbook/'

  /**
   * @return UTF-8 markdown text, or {@code null} if not found
   */
  static String loadMarkdown() {
    def override = System.getProperty(SYSPROP_PATH)?.toString()?.trim()
    if (override) {
      try {
        def f = new File(override)
        if (f.isFile()) {
          log.debug('Crafterizing playbook loaded from system property path: {}', override)
          return f.getText('UTF-8')
        }
        log.warn('aiassistant.crafterizingPlaybook.path set but not a file: {}', override)
      } catch (Throwable t) {
        log.warn('Failed reading crafterizing playbook from {}: {}', override, t.message)
      }
    }

    // 1) Same package as this class (works when .md is next to .class/.groovy on disk or in JAR)
    try {
      def is = CrafterizingPlaybookLoader.class.getResourceAsStream(PLAYBOOK_FILE_NAME)
      if (is != null) {
        try {
          def text = is.getText('UTF-8')
          log.debug('Crafterizing playbook loaded from classpath (peer resource): {} chars', text?.length() ?: 0)
          return text
        } finally {
          try {
            is.close()
          } catch (Throwable ignored) {}
        }
      }
    } catch (Throwable t) {
      log.debug('Peer resource load failed: {}', t.toString())
    }

    // 2) Full path under scripts/classes tree (some class loaders)
    try {
      def is2 = CrafterizingPlaybookLoader.class.classLoader?.getResourceAsStream("${PACKAGE_RESOURCE_PREFIX}${PLAYBOOK_FILE_NAME}")
      if (is2 != null) {
        try {
          def text = is2.getText('UTF-8')
          log.debug('Crafterizing playbook loaded from classpath (package path): {} chars', text?.length() ?: 0)
          return text
        } finally {
          try {
            is2.close()
          } catch (Throwable ignored) {}
        }
      }
    } catch (Throwable t) {
      log.debug('Package resource load failed: {}', t.toString())
    }

    // 3) Thread context class loader
    try {
      def cl = Thread.currentThread().contextClassLoader
      def is3 = cl?.getResourceAsStream("${PACKAGE_RESOURCE_PREFIX}${PLAYBOOK_FILE_NAME}")
      if (is3 != null) {
        try {
          return is3.getText('UTF-8')
        } finally {
          try {
            is3.close()
          } catch (Throwable ignored) {}
        }
      }
    } catch (Throwable t) {
      log.debug('Context classloader resource load failed: {}', t.toString())
    }

    // 4) Filesystem next to this class’s code source (directory deployment)
    try {
      def loc = CrafterizingPlaybookLoader.class.protectionDomain?.codeSource?.location
      if (loc != null) {
        def file = new File(loc.toURI())
        if (file.isDirectory()) {
          def inPlaybook = new File(new File(file, 'playbook'), PLAYBOOK_FILE_NAME)
          def candidate = inPlaybook.isFile() ? inPlaybook : new File(file, PLAYBOOK_FILE_NAME)
          if (candidate.isFile()) {
            log.debug('Crafterizing playbook loaded from code source directory: {}', candidate.absolutePath)
            return candidate.getText('UTF-8')
          }
        }
      }
    } catch (Throwable t) {
      log.debug('Code source directory load failed: {}', t.toString())
    }

    log.warn('Crafterizing playbook file {} not found on classpath or beside plugin classes; using embedded fallback.', PLAYBOOK_FILE_NAME)
    return null
  }

  /** Short inline fallback if the markdown file is missing at runtime. */
  static String embeddedFallbackMarkdown() {
    return '''# Crafterizing playbook (fallback)

The editable file `CrafterizingPlaybook.md` was not found next to the AI Assistant plugin classes.

- Ensure `authoring/scripts/classes/plugins/org/craftercms/aiassistant/playbook/CrafterizingPlaybook.md` is deployed (e.g. copied to `config/studio/scripts/classes/plugins/org/craftercms/aiassistant/playbook/`).
- Or set JVM system property `aiassistant.crafterizingPlaybook.path` to an absolute path of a markdown file.

See plugin docs for full crafterization phases: content types under `/config/studio/content-types/`, pages under `/site/website/`, components under `/site/components/`, templates under `/templates/web/`, populate `sections_o`, use CDATA for `*_html`, and use studio tools (GetContent, WriteContent, GetContentTypeFormDefinition) for edits.
'''
  }

  static String loadMarkdownOrFallback() {
    def s = loadMarkdown()
    return (s != null && s.trim()) ? s : embeddedFallbackMarkdown()
  }
}
