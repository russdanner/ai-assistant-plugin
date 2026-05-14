package plugins.org.craftercms.aiassistant.autonomous

import java.util.Collection
import java.util.Locale
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority

/**
 * Whether the current viewer may see an autonomous agent row (by parsed id scope).
 */
final class AutonomousScopeGuard {

  private AutonomousScopeGuard() {}

  static boolean viewerMaySee(String siteId, String fullAgentId, Authentication auth) {
    if (!fullAgentId?.trim()) {
      return false
    }
    String fid = fullAgentId.trim()
    String sidTrim = (siteId ?: '').trim()
    String username = auth?.name?.toString()?.trim() ?: 'anonymous'

    Map parts = AutonomousAgentIdBuilder.parseAgentId(fid)
    if (parts == null) {
      return false
    }
    // parseAgentId splits on the first three hyphens; Crafter site ids may contain hyphens, so
    // projectId can parse wrong while the key is still a valid agent for this site. Recover
    // common id shapes before rejecting the row (otherwise /status omits agents and the UI
    // shows a synthetic "pending" row while the worker still runs).
    if (sidTrim && parts.projectId && !sidTrim.equals(parts.projectId.toString())) {
      if (fid.startsWith(sidTrim + '-project-' + sidTrim + '-')) {
        return true
      }
      if (fid.startsWith(sidTrim + '-user-' + username + '-')) {
        return true
      }
      if (fid.startsWith(sidTrim + '-role-') && auth) {
        Collection<? extends GrantedAuthority> auths = auth.getAuthorities()
        if (auths != null) {
          String rolePrefix = sidTrim + '-role-'
          String afterRole = fid.length() > rolePrefix.length() ? fid.substring(rolePrefix.length()) : ''
          int dash = afterRole.indexOf('-')
          if (dash > 0) {
            String want = afterRole.substring(0, dash).replaceFirst('^ROLE_', '')
            for (GrantedAuthority ga : auths) {
              String a = ga?.authority?.toString() ?: ''
              String n = a.replaceFirst('^ROLE_', '')
              if (want.equalsIgnoreCase(n)) {
                return true
              }
            }
          }
        }
      }
      return false
    }
    String scope = (parts.scope ?: 'project').toString().toLowerCase(Locale.ROOT)
    String scopeId = (parts.scopeId ?: '').toString()

    if ('user'.equals(scope)) {
      return username.equalsIgnoreCase(scopeId)
    }
    if ('role'.equals(scope)) {
      if (!auth) {
        return false
      }
      Collection<? extends GrantedAuthority> auths = auth.getAuthorities()
      if (auths == null) {
        return false
      }
      String want = scopeId.replaceFirst('^ROLE_', '')
      for (GrantedAuthority ga : auths) {
        String a = ga?.authority?.toString() ?: ''
        String n = a.replaceFirst('^ROLE_', '')
        if (want.equalsIgnoreCase(n)) {
          return true
        }
      }
      return false
    }
    // project scope: any authenticated user in the site tools context
    'project'.equals(scope)
  }
}
