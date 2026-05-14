package plugins.org.craftercms.aiassistant.autonomous

import java.time.Instant
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Map
import java.util.concurrent.ConcurrentHashMap

/**
 * Static in-memory state for autonomous assistants (prototype — JVM lifetime only).
 */
final class AutonomousAssistantStateStore {

  static final ConcurrentHashMap<String, Map> BY_AGENT_ID = new ConcurrentHashMap<>()

  private AutonomousAssistantStateStore() {}

  static Map ensureEntry(String fullAgentId, Map defaults) {
    if (!fullAgentId?.trim()) {
      return null
    }
    String id = fullAgentId.trim()
    BY_AGENT_ID.computeIfAbsent(id, {
      Map m = new LinkedHashMap()
      if (defaults) {
        m.putAll(defaults)
      }
      m.put('agentId', id)
      if (!m.containsKey('status')) {
        m.put('status', 'waiting')
      }
      if (!m.containsKey('nextStepRequired')) {
        m.put('nextStepRequired', Boolean.FALSE)
      }
      m.put('pastRunReports', m.get('pastRunReports') ?: [])
      m.put('executionHistory', m.get('executionHistory') ?: [])
      m.put('humanTasks', m.get('humanTasks') ?: [])
      m
    })
  }

  static Map getState(String fullAgentId) {
    fullAgentId?.trim() ? BY_AGENT_ID.get(fullAgentId.trim()) : null
  }

  static void putState(String fullAgentId, Map state) {
    if (!fullAgentId?.trim() || state == null) {
      return
    }
    BY_AGENT_ID.put(fullAgentId.trim(), new LinkedHashMap(state))
  }

  static void mergeState(String fullAgentId, Map patch) {
    Map cur = ensureEntry(fullAgentId, [:])
    if (patch) {
      cur.putAll(patch)
    }
    putState(fullAgentId, cur)
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
    Map st = getState(fullAgentId.trim())
    if (st == null) {
      return false
    }
    List<Map> tasks = new ArrayList<>()
    Object raw = st.get('humanTasks')
    if (raw instanceof List) {
      for (Object o : (List) raw) {
        if (o instanceof Map) {
          tasks.add(new LinkedHashMap((Map) o))
        }
      }
    }
    boolean hit = false
    String ns = newStatus.trim().toLowerCase()
    for (Map t : tasks) {
      if (taskId.trim().equals(t?.get('id')?.toString())) {
        String owner = t?.get('ownerAgentId')?.toString()?.trim()
        if (!manageOtherAgentsHumanTasks && owner && !fullAgentId.trim().equals(owner)) {
          return false
        }
        t.put('status', ns)
        t.put('updatedAt', Instant.now().toString())
        hit = true
        break
      }
    }
    if (!hit) {
      return false
    }
    st.put('humanTasks', tasks)
    putState(fullAgentId.trim(), st)
    true
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
    Map st = getState(fullAgentId.trim())
    if (st == null) {
      return false
    }
    List<Map> tasks = new ArrayList<>()
    Object raw = st.get('humanTasks')
    if (raw instanceof List) {
      for (Object o : (List) raw) {
        if (o instanceof Map) {
          tasks.add(new LinkedHashMap((Map) o))
        }
      }
    }
    boolean hit = false
    String au = assignedUsername?.trim()
    String an = assignedDisplayName?.trim()
    for (Map t : tasks) {
      if (taskId.trim().equals(t?.get('id')?.toString())) {
        String owner = t?.get('ownerAgentId')?.toString()?.trim()
        if (!manageOtherAgentsHumanTasks && owner && !fullAgentId.trim().equals(owner)) {
          return false
        }
        if (au) {
          t.put('assignedUsername', au)
          t.put('assignedName', an ?: au)
        } else {
          t.remove('assignedUsername')
          t.remove('assignedName')
        }
        t.put('updatedAt', Instant.now().toString())
        hit = true
        break
      }
    }
    if (!hit) {
      return false
    }
    st.put('humanTasks', tasks)
    putState(fullAgentId.trim(), st)
    true
  }
}
