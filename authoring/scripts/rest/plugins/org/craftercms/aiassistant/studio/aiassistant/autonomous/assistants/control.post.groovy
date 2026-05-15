import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantRegistry
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantStateStore
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantStatus
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantSupervisor
import plugins.org.craftercms.aiassistant.autonomous.AutonomousScopeGuard
import plugins.org.craftercms.aiassistant.http.AiHttpProxy

def body = AiHttpProxy.parseJsonBody(request)
if (Boolean.TRUE.equals(body?.get('__aiassistantInvalidJson'))) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: 'Invalid JSON']
}
String siteId = body?.siteId?.toString()?.trim()
String action = body?.action?.toString()?.trim()?.toLowerCase()
String agentId = body?.agentId?.toString()?.trim()
String taskId = body?.taskId?.toString()?.trim()
String assignedUsername = body?.assignedUsername?.toString()?.trim()
String assignedName = body?.assignedName?.toString()?.trim()
if (!siteId || !action) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: 'Missing siteId or action']
}

Authentication auth = SecurityContextHolder.context?.authentication

if (agentId && !AutonomousScopeGuard.viewerMaySee(siteId, agentId, auth)) {
  response.setStatus(HttpServletResponse.SC_FORBIDDEN)
  return [ok: false, message: 'Forbidden for this agent']
}

boolean manageOtherAgentsHumanTasks = false
if (agentId) {
  try {
    Map innerDef = AutonomousAssistantRegistry.agentsForSite(siteId)?.get(agentId)
    if (innerDef) {
      manageOtherAgentsHumanTasks =
        Boolean.TRUE.equals(innerDef.get('manageOtherAgentsHumanTasks')) ||
        'true'.equalsIgnoreCase(innerDef.get('manageOtherAgentsHumanTasks')?.toString())
    }
  } catch (Throwable ignored) {}
}

/** Mirrors sync.post.groovy startAutomatically parsing (incl. {@code automaticallyStart} alias). */
def cqParseStartAutomatically = { Map defm ->
  boolean startAutomatically = true
  if (defm == null) {
    return startAutomatically
  }
  try {
    Object sa = defm.get('startAutomatically') ?: defm.get('start_automatically') ?:
      defm.get('automaticallyStart') ?: defm.get('automatically_start')
    if (sa != null) {
      if (sa instanceof Boolean) {
        startAutomatically = (Boolean) sa
      } else {
        String s = sa.toString().trim().toLowerCase()
        if (s == 'false' || s == '0' || s == 'no') {
          startAutomatically = false
        }
      }
    }
  } catch (Throwable ignored) {
    startAutomatically = true
  }
  return startAutomatically
}

switch (action) {
  case 'disable_supervisor':
    AutonomousAssistantSupervisor.disableSupervisor()
    try {
      def siteAgents = AutonomousAssistantRegistry.agentsForSite(siteId)
      for (def e : siteAgents.entrySet()) {
        String aid = e.key?.toString()
        if (!aid?.trim()) {
          continue
        }
        Map st = AutonomousAssistantStateStore.getState(aid) ?: [:]
        boolean preserveManual = Boolean.TRUE.equals(st.get('manualStop')) ||
          'true'.equalsIgnoreCase(st.get('manualStop')?.toString())
        AutonomousAssistantStateStore.mergeState(aid, [
          status          : AutonomousAssistantStatus.STOPPED,
          nextStepRequired: Boolean.FALSE,
          manualStop      : preserveManual ? Boolean.TRUE : Boolean.FALSE
        ])
      }
    } catch (Throwable ignored2) {}
    return [ok: true, action: action]
  case 'enable_supervisor':
    AutonomousAssistantSupervisor.enableSupervisor()
    try {
      def siteAgents2 = AutonomousAssistantRegistry.agentsForSite(siteId)
      for (def e : siteAgents2.entrySet()) {
        String aid = e.key?.toString()
        if (!aid?.trim()) {
          continue
        }
        Map agentDef = (e.value instanceof Map) ? (Map) e.value : [:]
        Map st = AutonomousAssistantStateStore.getState(aid) ?: [:]
        String pst = st.get('status')?.toString()
        if (AutonomousAssistantStatus.DISABLED.equals(pst) || AutonomousAssistantStatus.ERROR.equals(pst)) {
          continue
        }
        boolean sa = cqParseStartAutomatically(agentDef)
        boolean manualStop = Boolean.TRUE.equals(st.get('manualStop')) ||
          'true'.equalsIgnoreCase(st.get('manualStop')?.toString())
        if (!sa) {
          AutonomousAssistantStateStore.mergeState(aid, [
            status          : AutonomousAssistantStatus.STOPPED,
            nextStepRequired: Boolean.FALSE,
            manualStop      : Boolean.FALSE
          ])
        } else if (manualStop) {
          AutonomousAssistantStateStore.mergeState(aid, [
            status          : AutonomousAssistantStatus.STOPPED,
            nextStepRequired: Boolean.FALSE,
            manualStop      : Boolean.TRUE
          ])
        } else {
          AutonomousAssistantStateStore.mergeState(aid, [
            status          : AutonomousAssistantStatus.WAITING,
            nextStepRequired: Boolean.FALSE,
            manualStop      : Boolean.FALSE,
            lastError       : null
          ])
        }
      }
    } catch (Throwable ignored2) {}
    return [ok: true, action: action]
  case 'shutdown_pools':
    AutonomousAssistantSupervisor.shutdownPools()
    return [ok: true, action: action]
  case 'destroy_store':
    AutonomousAssistantSupervisor.destroyInMemoryStore()
    return [ok: true, action: action]
  case 'start_agent':
    if (!agentId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId']
    }
    AutonomousAssistantStateStore.mergeState(agentId, [
      status          : AutonomousAssistantStatus.WAITING,
      nextStepRequired: Boolean.FALSE,
      lastError       : null,
      manualStop      : Boolean.FALSE
    ])
    return [ok: true, action: action, agentId: agentId]
  case 'clear_agent_error':
    if (!agentId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId']
    }
    AutonomousAssistantStateStore.mergeState(agentId, [
      status          : AutonomousAssistantStatus.WAITING,
      nextStepRequired: Boolean.FALSE,
      lastError       : null,
      manualStop      : Boolean.FALSE
    ])
    return [ok: true, action: action, agentId: agentId]
  case 'stop_agent':
    if (!agentId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId']
    }
    AutonomousAssistantStateStore.mergeState(agentId, [
      status          : AutonomousAssistantStatus.STOPPED,
      nextStepRequired: Boolean.FALSE,
      manualStop      : Boolean.TRUE
    ])
    return [ok: true, action: action, agentId: agentId]
  case 'execute_now':
    if (!agentId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId']
    }
    AutonomousAssistantStateStore.mergeState(agentId, [
      status          : AutonomousAssistantStatus.WAITING,
      nextStepRequired: Boolean.TRUE,
      manualStop      : Boolean.FALSE
    ])
    return [ok: true, action: action, agentId: agentId]
  case 'disable_agent':
    if (!agentId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId']
    }
    AutonomousAssistantStateStore.mergeState(agentId, [status: AutonomousAssistantStatus.DISABLED, nextStepRequired: Boolean.FALSE])
    return [ok: true, action: action, agentId: agentId]
  case 'enable_agent':
    if (!agentId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId']
    }
    AutonomousAssistantStateStore.mergeState(agentId, [
      status          : AutonomousAssistantStatus.WAITING,
      nextStepRequired: Boolean.FALSE,
      lastError       : null,
      manualStop      : Boolean.FALSE
    ])
    return [ok: true, action: action, agentId: agentId]
  case 'complete_human_task':
    if (!agentId || !taskId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId or taskId']
    }
    if (!AutonomousAssistantStateStore.updateHumanTaskStatus(agentId, taskId, 'done', manageOtherAgentsHumanTasks)) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND)
      return [ok: false, message: 'Task not found or not owned by this agent']
    }
    return [ok: true, action: action, agentId: agentId, taskId: taskId]
  case 'dismiss_human_task':
    if (!agentId || !taskId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId or taskId']
    }
    if (!AutonomousAssistantStateStore.updateHumanTaskStatus(agentId, taskId, 'dismissed', manageOtherAgentsHumanTasks)) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND)
      return [ok: false, message: 'Task not found or not owned by this agent']
    }
    return [ok: true, action: action, agentId: agentId, taskId: taskId]
  case 'reopen_human_task':
    if (!agentId || !taskId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId or taskId']
    }
    if (!AutonomousAssistantStateStore.updateHumanTaskStatus(agentId, taskId, 'open', manageOtherAgentsHumanTasks)) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND)
      return [ok: false, message: 'Task not found or not owned by this agent']
    }
    return [ok: true, action: action, agentId: agentId, taskId: taskId]
  case 'assign_human_task':
    if (!agentId || !taskId) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      return [ok: false, message: 'Missing agentId or taskId']
    }
    if (!AutonomousAssistantStateStore.updateHumanTaskAssignment(
      agentId,
      taskId,
      assignedUsername ?: '',
      assignedName ?: '',
      manageOtherAgentsHumanTasks
    )) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND)
      return [ok: false, message: 'Task not found or not owned by this agent']
    }
    return [ok: true, action: action, agentId: agentId, taskId: taskId]
  default:
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
    return [ok: false, message: "Unknown action: ${action}"]
}
