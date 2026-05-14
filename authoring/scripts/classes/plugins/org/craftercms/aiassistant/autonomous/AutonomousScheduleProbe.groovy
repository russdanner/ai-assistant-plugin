package plugins.org.craftercms.aiassistant.autonomous

import java.util.Locale

/**
 * Prototype schedule interpretation (Studio plugin classpath has no bundled Quartz dependency).
 * Maps common Quartz-style patterns (6-field: sec min hour dom month dow) to a repeat period; defaults to one hour.
 * <p>The supervisor wakes every few seconds but only runs an agent when {@code now - lastRun >= inferPeriodMillis(schedule)}.
 */
final class AutonomousScheduleProbe {

  private AutonomousScheduleProbe() {}

  static long inferPeriodMillis(String scheduleExpression) {
    if (!scheduleExpression?.trim()) {
      return 3_600_000L
    }
    // Normalize whitespace so "0  *  * * * ?" matches the same probes as a tight cron string.
    String n = scheduleExpression.trim().toLowerCase(Locale.ROOT).replaceAll(/\s+/, ' ')
    // Every 10 seconds (Quartz seconds field 0/10 or */10)
    if (n.startsWith('0/10') || n.contains(' 0/10 ') || n.contains('*/10')) {
      return 10_000L
    }
    if (n.startsWith('0/30') || n.contains(' 0/30 ')) {
      return 30_000L
    }
    if (n.startsWith('0/60') || n.contains(' 0/60 ')) {
      return 60_000L
    }
    // Every minute at second 0: 0 * * * * ?  (minute is * — NOT the same as hourly 0 0 * * * ?)
    if (n ==~ /(?i)^0 \* \* \* \*(\s\?)?$/) {
      return 60_000L
    }
    // Every minute (minute step): 0 0/1 * * * ?  or 0 */1 * * * ?
    if (n ==~ /(?i)^0 0\/1 \* \* \*(\s\?)?$/ || n ==~ /(?i)^0 \*\/1 \* \* \*(\s\?)?$/) {
      return 60_000L
    }
    // Every hour at minute 0, second 0: 0 0 * * * ?
    if (n ==~ /(?i)^0 0 \* \* \*(\s\?)?$/) {
      return 3_600_000L
    }
    3_600_000L
  }
}
