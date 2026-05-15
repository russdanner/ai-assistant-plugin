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
 * <p>{@link #getState} returns a defensive copy; updates go through {@link #putState}, {@link #mergeState}, or
 * task helpers that use atomic {@link java.util.concurrent.ConcurrentHashMap} operations.</p>
 */
final class AutonomousAssistantStateStore {

  static final ConcurrentHashMap<String, Map> BY_AGENT_ID = new ConcurrentHashMap<>()

  private AutonomousAssistantStateStore() {}

  private static Map newAgentShell(String id, Map defaults) {
    Map m = new LinkedHashMap()
    if (defaults) {
      m.putAll(defaults)
    }
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
    return live != null ? new LinkedHashMap(live) : null
  }

  static Map getState(String fullAgentId) {
    Map st = fullAgentId?.trim() ? BY_AGENT_ID.get(fullAgentId.trim()) : null
    return st != null ? new LinkedHashMap(st) : null
  }

  static void putState(String fullAgentId, Map state) {
    if (!fullAgentId?.trim() || state == null) {
      return
    }
    BY_AGENT_ID.put(fullAgentId.trim(), new LinkedHashMap(state))
  }

  /**
   * Shallow-merges {@code patch} into stored state in one atomic step (avoids lost updates vs in-place mutation).
   */
  static void mergeState(String fullAgentId, Map patch) {
    if (!fullAgentId?.trim() || patch == null) {
      return
    }
    String id = fullAgentId.trim()
    BY_AGENT_ID.compute(id) { k, cur ->
      Map base = (cur instanceof Map) ? new LinkedHashMap(cur) : newAgentShell(k, [:])
      base.putAll(patch)
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
   * Shallow copy of all in-memory state maps for this site (keys are full agent ids, {@code siteId + '-'}…).
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
          out.put(k, new LinkedHashMap(st))
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
      Map st = new LinkedHashMap(cur)
      List<Map> tasks = new ArrayList<>()
      Object raw = st.get('humanTasks')
      if (raw instanceof List) {
        for (Object o : (List) raw) {
          if (o instanceof Map) {
            tasks.add(new LinkedHashMap((Map) o))
          }
        }
      }
      for (Map t : tasks) {
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
      if (!hit[0]) {
        return cur
      }
      st.put('humanTasks', tasks)
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
      Map st = new LinkedHashMap(cur)
      List<Map> tasks = new ArrayList<>()
      Object raw = st.get('humanTasks')
      if (raw instanceof List) {
        for (Object o : (List) raw) {
          if (o instanceof Map) {
            tasks.add(new LinkedHashMap((Map) o))
          }
        }
      }
      for (Map t : tasks) {
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
      if (!hit[0]) {
        return cur
      }
      st.put('humanTasks', tasks)
      return st
    }
    hit[0]
  }
}
