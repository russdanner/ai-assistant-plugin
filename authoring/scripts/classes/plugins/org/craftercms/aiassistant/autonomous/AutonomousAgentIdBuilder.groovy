package plugins.org.craftercms.aiassistant.autonomous

import java.util.Locale

/**
 * Builds stable agent keys: {@code projectId-SCOPE-scopeId-normalized-name}
 * (normalized name: lower case, spaces to hyphens, alphanumerics + hyphens only).
 */
final class AutonomousAgentIdBuilder {

  private AutonomousAgentIdBuilder() {}

  static String normalizeAgentName(String raw) {
    if (raw == null) {
      return 'agent'
    }
    String s = raw.trim().toLowerCase(Locale.ROOT)
    s = s.replaceAll('\\s+', '-')
    s = s.replaceAll('[^a-z0-9-]+', '-')
    s = s.replaceAll('-+', '-')
    s = s.replaceAll('^-+|-+$', '')
    s ?: 'agent'
  }

  static String buildAgentId(String projectId, String scope, String scopeId, String agentName) {
    String p = (projectId ?: 'default').trim()
    String sc = (scope ?: 'project').trim().toLowerCase(Locale.ROOT)
    if (!('user'.equals(sc) || 'role'.equals(sc) || 'project'.equals(sc))) {
      sc = 'project'
    }
    String sid = (scopeId ?: p).trim()
    sid = sid.replaceAll('[^a-zA-Z0-9._-]+', '-').replaceAll('-+', '-')
    String n = normalizeAgentName(agentName)
    "${p}-${sc}-${sid}-${n}"
  }

  /**
   * Parses {@link #buildAgentId} when {@code normalizedName} contains hyphens: first three segments fixed.
   */
  static Map parseAgentId(String agentId) {
    if (!agentId?.trim()) {
      return null
    }
    String s = agentId.trim()
    int i1 = s.indexOf('-')
    int i2 = i1 >= 0 ? s.indexOf('-', i1 + 1) : -1
    int i3 = i2 >= 0 ? s.indexOf('-', i2 + 1) : -1
    if (i1 < 0 || i2 < 0 || i3 < 0) {
      return null
    }
    [
      projectId      : s.substring(0, i1),
      scope          : s.substring(i1 + 1, i2),
      scopeId        : s.substring(i2 + 1, i3),
      normalizedName : s.substring(i3 + 1)
    ]
  }
}
