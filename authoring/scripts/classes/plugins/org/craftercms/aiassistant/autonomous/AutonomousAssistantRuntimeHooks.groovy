package plugins.org.craftercms.aiassistant.autonomous

import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Captures Studio {@code ApplicationContext} and a {@link SecurityContext} snapshot from an HTTP script thread
 * so autonomous worker threads can call beans (e.g. OpenSearch) with the same authentication.
 */
final class AutonomousAssistantRuntimeHooks {

  private static volatile Object applicationContextRef
  private static volatile SecurityContext securityContextRef

  private AutonomousAssistantRuntimeHooks() {}

  /**
   * Call from plugin REST scripts ({@code sync}, {@code status}, etc.) that run on a Studio servlet thread.
   */
  static void register(def applicationContext) {
    applicationContextRef = applicationContext
    try {
      SecurityContext cur = SecurityContextHolder.getContext()
      if (cur == null) {
        securityContextRef = null
        return
      }
      SecurityContext copy = SecurityContextHolder.createEmptyContext()
      copy.setAuthentication(cur.getAuthentication())
      securityContextRef = copy
    } catch (Throwable ignored) {
      securityContextRef = null
    }
  }

  static Object applicationContext() {
    applicationContextRef
  }

  static void runWithCapturedSecurity(Closure work) {
    SecurityContext previous = SecurityContextHolder.getContext()
    try {
      if (securityContextRef != null) {
        SecurityContextHolder.setContext(securityContextRef)
      }
      work.call()
    } finally {
      SecurityContextHolder.setContext(previous)
    }
  }

  static void clear() {
    applicationContextRef = null
    securityContextRef = null
  }
}
