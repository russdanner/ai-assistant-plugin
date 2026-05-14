package plugins.org.craftercms.aiassistant.autonomous

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of agent definitions keyed by site id then full agent id.
 */
final class AutonomousAssistantRegistry {

  private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Map>> BY_SITE =
    new ConcurrentHashMap<>()

  private AutonomousAssistantRegistry() {}

  static void putAgent(String siteId, String fullAgentId, Map definition) {
    if (!siteId?.trim() || !fullAgentId?.trim() || definition == null) {
      return
    }
    String sid = siteId.trim()
    ConcurrentHashMap<String, Map> inner = BY_SITE.computeIfAbsent(sid, { k -> new ConcurrentHashMap<>() })
    inner.put(fullAgentId.trim(), new LinkedHashMap(definition))
  }

  static void removeSite(String siteId) {
    if (!siteId?.trim()) {
      return
    }
    BY_SITE.remove(siteId.trim())
  }

  static ConcurrentHashMap<String, Map> agentsForSite(String siteId) {
    if (!siteId?.trim()) {
      return new ConcurrentHashMap<>()
    }
    ConcurrentHashMap<String, Map> inner = BY_SITE.get(siteId.trim())
    return inner != null ? inner : new ConcurrentHashMap<>()
  }

  static Map snapshotAllSites() {
    Map out = new LinkedHashMap()
    for (String site : BY_SITE.keySet()) {
      out.put(site, new LinkedHashMap(BY_SITE.get(site)))
    }
    out
  }

  static void clearAll() {
    BY_SITE.clear()
  }
}
