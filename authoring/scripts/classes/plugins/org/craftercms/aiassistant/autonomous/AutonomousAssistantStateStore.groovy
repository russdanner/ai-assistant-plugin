package plugins.org.craftercms.aiassistant.autonomous

import java.time.Instant
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.concurrent.ConcurrentHashMap

/**
 * Static in-memory state for autonomous assistants (prototype — JVM lifetime only).
 * <p>{@link #getState} and related accessors return defensive <strong>deep</strong> copies (maps/lists nested under
 * {@code humanTasks}, {@code executionHistory}, {@code pastRunReports}, etc.); updates go through {@link #putState},
 * {@link #mergeState}, or task helpers that use atomic {@link java.util.concurrent.ConcurrentHashMap} operations.</p>
 */
final class AutonomousAssistantStateStore {

  static final ConcurrentHashMap<String, Map> BY_AGENT_ID = new ConcurrentHashMap<>()

  private AutonomousAssistantStateStore() {}

  private static Object copyValue(Object value) {
    if (value instanceof Map) {
      Map out = new LinkedHashMap<>()
      ((Map) value).each { k, v -> out.put(k, copyValue(v)) }
      return out
    }
    if (value instanceof List) {
      List out = new ArrayList<>()
      for (Object v : (List) value) {
        out.add(copyValue(v))
      }
      return out
    }
    return value
  }

  private static Map copyState(Map state) {
    Map out = new LinkedHashMap<>()
    if (state != null) {
      state.each { k, v -> out.put(k, copyValue(v)) }
    }
    return out
  }

  private static Map newAgentShell(String id, Map defaults) {
    Map m = defaults ? copyState(defaults) : new LinkedHashMap<>()
    m.put('agentId', id)
    if (!m.containsKey('status')) {
      m.put('status', AutonomousAssistantStatus.WAITING)
    }
    if (!m.containsKey('nextStepRequired')) {
      m.put('nextStepRequired', Boolean.FALSE)
    }
    m.put('pastRunReports', m.get('pastRunReports') ?: [])
    m.put('executionHistory', m.get('executionHistory') ?: [])
    m.put('humanTasks', m.get('humanTasks') ?: [])
    m
  }

  /**
   * Ensures a row exists; returns a <strong>copy</strong> of stored state (safe to read; do not expect live map).
   */
  static Map ensureEntry(String fullAgentId, Map defaults) {
    if (!fullAgentId?.trim()) {
      return null
    }
    String id = fullAgentId.trim()
    Map live = BY_AGENT_ID.computeIfAbsent(id, { k -> newAgentShell(k, defaults) })
    return live != null ? copyState(live) : null
  }

  static Map getState(String fullAgentId) {
    Map st = fullAgentId?.trim() ? BY_AGENT_ID.get(fullAgentId.trim()) : null
    return st != null ? copyState(st) : null
  }

  static void putState(String fullAgentId, Map state) {
    if (!fullAgentId?.trim() || state == null) {
      return
    }
    BY_AGENT_ID.put(fullAgentId.trim(), copyState(state))
  }

  /**
   * Merges {@code patch} into stored state in one atomic step (top-level keys from {@code patch}; each value is
   * deep-copied so nested maps/lists are not shared with the caller). Avoids lost updates vs in-place mutation.
   */
  static void mergeState(String fullAgentId, Map patch) {
    if (!fullAgentId?.trim() || patch == null) {
      return
    }
    String id = fullAgentId.trim()
    BY_AGENT_ID.compute(id) { k, cur ->
      Map base = (cur instanceof Map) ? copyState(cur) : newAgentShell(k, [:])
      patch.each { ek, ev -> base.put(ek, copyValue(ev)) }
      return base
    }
  }

  static void clearAll() {
    BY_AGENT_ID.clear()
  }

  /** Removes state rows whose {@code agentId} starts with {@code siteId + '-'}. */
  static void removeKeysForSite(String siteId) {
    if (!siteId?.trim()) {
      return
    }
    String p = siteId.trim() + '-'
    List<String> keys = new ArrayList<>(BY_AGENT_ID.keySet())
    for (String k : keys) {
      if (k != null && k.startsWith(p)) {
        BY_AGENT_ID.remove(k)
      }
    }
  }

  /**
   * Deep copy of all in-memory state maps for this site (keys are full agent ids, {@code siteId + '-'}…).
   * Call before {@link AutonomousAssistantSupervisor#clearSite} so sync can restore terminal statuses.
   */
  static Map<String, Map> snapshotStatesForSite(String siteId) {
    if (!siteId?.trim()) {
      return new LinkedHashMap<>()
    }
    String p = siteId.trim() + '-'
    Map<String, Map> out = new LinkedHashMap<>()
    for (String k : new ArrayList<>(BY_AGENT_ID.keySet())) {
      if (k != null && k.startsWith(p)) {
        Map st = BY_AGENT_ID.get(k)
        if (st != null) {
          out.put(k, copyState(st))
        }
      }
    }
    out
  }

  /**
   * Updates one human task row by id (status: {@code open}, {@code done}, or {@code dismissed}).
   */
  static boolean updateHumanTaskStatus(String fullAgentId, String taskId, String newStatus, boolean manageOtherAgentsHumanTasks) {
    if (!fullAgentId?.trim() || !taskId?.trim() || !newStatus?.trim()) {
      return false
    }
    String id = fullAgentId.trim()
    String tid = taskId.trim()
    String ns = newStatus.trim().toLowerCase(Locale.ROOT)
    boolean[] hit = new boolean[1]
    BY_AGENT_ID.computeIfPresent(id) { k, cur ->
      if (!(cur instanceof Map)) {
        return cur
      }
      Map st = copyState(cur)
      Object raw = st.get('humanTasks')
      if (raw instanceof List) {
        for (Object o : (List) raw) {
          if (o instanceof Map) {
            Map t = (Map) o
            if (tid.equals(t?.get('id')?.toString())) {
              String owner = t?.get('ownerAgentId')?.toString()?.trim()
              if (!manageOtherAgentsHumanTasks && owner && !id.equals(owner)) {
                return cur
              }
              t.put('status', ns)
              t.put('updatedAt', Instant.now().toString())
              hit[0] = true
              break
            }
          }
        }
      }
      if (!hit[0]) {
        return cur
      }
      return st
    }
    hit[0]
  }

  /**
   * Sets or clears Studio assignee on one human task. {@code assignedUsername} null/blank removes assignment.
   * Same ownership rules as {@link #updateHumanTaskStatus}.
   */
  static boolean updateHumanTaskAssignment(
    String fullAgentId,
    String taskId,
    String assignedUsername,
    String assignedDisplayName,
    boolean manageOtherAgentsHumanTasks
  ) {
    if (!fullAgentId?.trim() || !taskId?.trim()) {
      return false
    }
    String id = fullAgentId.trim()
    String tid = taskId.trim()
    boolean[] hit = new boolean[1]
    String au = assignedUsername?.trim()
    String an = assignedDisplayName?.trim()
    BY_AGENT_ID.computeIfPresent(id) { k, cur ->
      if (!(cur instanceof Map)) {
        return cur
      }
      Map st = copyState(cur)
      Object raw = st.get('humanTasks')
      if (raw instanceof List) {
        for (Object o : (List) raw) {
          if (o instanceof Map) {
            Map t = (Map) o
            if (tid.equals(t?.get('id')?.toString())) {
              String owner = t?.get('ownerAgentId')?.toString()?.trim()
              if (!manageOtherAgentsHumanTasks && owner && !id.equals(owner)) {
                return cur
              }
              if (au) {
                t.put('assignedUsername', au)
                t.put('assignedName', an ?: au)
              } else {
                t.remove('assignedUsername')
                t.remove('assignedName')
              }
              t.put('updatedAt', Instant.now().toString())
              hit[0] = true
              break
            }
          }
        }
      }
      if (!hit[0]) {
        return cur
      }
      return st
    }
    hit[0]
  }
}
