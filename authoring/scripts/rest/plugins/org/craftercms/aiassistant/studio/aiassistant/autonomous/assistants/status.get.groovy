import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantRegistry
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantRuntimeHooks
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantStateStore
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantSupervisor
import plugins.org.craftercms.aiassistant.autonomous.AutonomousScopeGuard

String siteId = (params?.siteId ?: request.getParameter('siteId'))?.toString()?.trim()
if (!siteId) {
  response.setStatus(400)
  return [ok: false, message: 'Missing siteId']
}

Authentication auth = SecurityContextHolder.context?.authentication

try {
  AutonomousAssistantRuntimeHooks.register(applicationContext)
} catch (Throwable ignored) {}

AutonomousAssistantSupervisor.ensureStarted()

def agents = AutonomousAssistantRegistry.agentsForSite(siteId)
List rows = []
int openHumanTaskCount = 0
List agentsInError = []
for (Map.Entry<String, Map> e : agents.entrySet()) {
  String aid = e.key
  if (!AutonomousScopeGuard.viewerMaySee(siteId, aid, auth)) {
    continue
  }
  // Not named `def` — Groovy 4 treats that as the dynamic-type keyword and fails to parse this script.
  Map agentDef = e.value ?: [:]
  Map st = AutonomousAssistantStateStore.getState(aid) ?: [:]
  Object ht = st.get('humanTasks')
  if (ht instanceof List) {
    for (Object t : (List) ht) {
      if (t instanceof Map) {
        Map tm = (Map) t
        String owner = tm.get('ownerAgentId')?.toString()?.trim()
        if (owner && !aid.equals(owner)) {
          continue
        }
        String ts = tm.get('status')?.toString()?.trim()?.toLowerCase()
        if (!'dismissed'.equals(ts) && !'done'.equals(ts)) {
          openHumanTaskCount++
        }
      }
    }
  }
  if ('error'.equals(st.get('status')?.toString())) {
    agentsInError.add([
      agentId  : aid,
      name     : agentDef.get('name')?.toString(),
      lastError: st.get('lastError')
    ])
  }
  rows.add([
    agentId          : aid,
    definition       : agentDef,
    state            : st,
    pastRunReports   : st.get('pastRunReports'),
    executionHistory : st.get('executionHistory')
  ])
}

response.setContentType('application/json')
return [
  ok                 : true,
  siteId             : siteId,
  supervisor         : AutonomousAssistantSupervisor.statusSnapshot(),
  agents             : rows,
  openHumanTaskCount : openHumanTaskCount,
  agentsInError      : agentsInError,
  hasAgentError      : !agentsInError.isEmpty()
]
