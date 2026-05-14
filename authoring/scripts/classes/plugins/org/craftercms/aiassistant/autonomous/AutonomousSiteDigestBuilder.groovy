package plugins.org.craftercms.aiassistant.autonomous

import java.util.List
import org.opensearch.client.opensearch.core.SearchRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Builds a compact text digest of indexed site content so autonomous agents can avoid duplicating
 * existing pages/topics without loading the full Spring AI tool stack.
 */
final class AutonomousSiteDigestBuilder {

  private static final Logger log = LoggerFactory.getLogger(AutonomousSiteDigestBuilder)

  private AutonomousSiteDigestBuilder() {}

  /**
   * @return human-readable digest or a short reason when unavailable
   */
  static String buildForSite(String siteId) {
    if (!siteId?.trim()) {
      return ''
    }
    String sid = siteId.trim()
    Object app = AutonomousAssistantRuntimeHooks.applicationContext()
    if (app == null) {
      return '(Studio applicationContext not registered yet — load Studio with the Autonomous Assistants widget so sync/status runs once.)'
    }
    Object searchBean = null
    try {
      searchBean = app.get('authoringSearchService')
    } catch (Throwable ignored) {}
    if (searchBean == null) {
      return '(authoringSearchService bean not available in this Studio.)'
    }
    StringBuilder out = new StringBuilder()
    AutonomousAssistantRuntimeHooks.runWithCapturedSecurity {
      try {
        SearchRequest req = SearchRequest.of { r ->
          r.query { q ->
            q.bool { b ->
              b.filter { f ->
                f.prefix { p ->
                  p.field('localId').value('/site/website/')
                }
              }
              b.should { s -> s.prefix { p -> p.field('content-type').value('/page') } }
              b.should { s -> s.prefix { p -> p.field('content-type').value('/component') } }
              b.minimumShouldMatch('1')
            }
          }.from(0).size(120)
        }
        Object result = searchBean.search(sid, req, Map)
        if (result == null) {
          out.append('(OpenSearch returned null.)')
          return
        }
        List srcList = []
        try {
          def hh = result.hits()?.hits()
          if (hh != null) {
            srcList = hh*.source()
          }
        } catch (Throwable ignored) {
          srcList = []
        }
        if (srcList == null || srcList.isEmpty()) {
          out.append('(No indexed items under /site/website/ — index may be empty or still building.)')
          return
        }
        out.append('Indexed site content (paths, types, titles — avoid duplicating these topics):\n')
        int n = 0
        for (Object srcObj : srcList) {
          if (!(srcObj instanceof Map)) {
            continue
          }
          Map m = (Map) srcObj
          String path = m.get('localId')?.toString() ?: ''
          String ctype = m.get('content-type')?.toString() ?: ''
          String title = m.get('title_t')?.toString() ?: m.get('internal-name')?.toString() ?: m.get('navLabel')?.toString() ?: ''
          if (!path && !title) {
            continue
          }
          String line = "- ${path} | ${ctype} | ${title}\n"
          if (out.length() + line.length() > 14_000) {
            break
          }
          out.append(line)
          n++
        }
        out.append("\n(${n} rows; digest is partial — still propose clearly distinct angles.)\n")
      } catch (Throwable t) {
        log.warn('AutonomousSiteDigestBuilder search failed siteId={}: {}', sid, t.message)
        out.append("(OpenSearch digest failed: ${t.message})")
      }
    }
    out.toString()
  }
}
