package plugins.org.craftercms.aiassistant.autonomous

/**
 * Canonical {@code status} string values for in-memory autonomous assistant state (wire / UI parity).
 */
final class AutonomousAssistantStatus {

  static final String WAITING = 'waiting'
  static final String RUNNING = 'running'
  static final String STOPPED = 'stopped'
  static final String ERROR = 'error'
  static final String DISABLED = 'disabled'

  private AutonomousAssistantStatus() {}
}
