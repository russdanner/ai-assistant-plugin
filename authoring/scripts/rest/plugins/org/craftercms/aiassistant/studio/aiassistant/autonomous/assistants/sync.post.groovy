import jakarta.servlet.http.HttpServletResponse
import java.util.ArrayList
import java.util.Collection
import java.util.LinkedHashMap
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAgentIdBuilder
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantRegistry
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantStateStore
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantRuntimeHooks
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantStatus
import plugins.org.craftercms.aiassistant.autonomous.AutonomousAssistantSupervisor
import plugins.org.craftercms.aiassistant.http.AiHttpProxy

def body = AiHttpProxy.parseJsonBody(request)
if (Boolean.TRUE.equals(body?.get('__aiassistantInvalidJson'))) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: 'Invalid JSON', detail: body?.get('__aiassistantInvalidJsonDetail')?.toString() ?: '']
}
String siteId = (body?.siteId ?: params?.siteId)?.toString()?.trim()
if (!siteId) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: 'Missing siteId']
}

Authentication auth = SecurityContextHolder.context?.authentication
String username = auth?.name?.toString()?.trim() ?: 'anonymous'
String roleScopeId = username
try {
  Collection<? extends GrantedAuthority> auths = auth?.authorities
  if (auths != null) {
    for (GrantedAuthority ga : auths) {
      String a = ga?.authority?.toString()
      if (a?.startsWith('ROLE_')) {
        roleScopeId = a.substring('ROLE_'.length())
        break
      }
    }
  }
} catch (Throwable ignored) {}

def agents = body?.agents
if (!(agents instanceof List)) {
  response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
  return [ok: false, message: 'Missing agents array']
}

Map priorByAgentId = AutonomousAssistantStateStore.snapshotStatesForSite(siteId)
AutonomousAssistantSupervisor.clearSite(siteId)

List outIds = []
/** Full agent id → {@code startAutomatically} from this sync payload (for restore rules). */
Map<String, Boolean> startAutoByFullId = new LinkedHashMap<>()
for (Object raw : (List) agents) {
  if (!(raw instanceof Map)) {
    continue
  }
  Map a = (Map) raw
  String name = a.name?.toString()?.trim() ?: 'agent'
  String schedule = a.schedule?.toString()?.trim() ?: '0 0 * * * ?'
  String prompt = a.prompt?.toString() ?: ''
  String scope = (a.scope ?: 'project').toString().trim().toLowerCase()
  if (!('user'.equals(scope) || 'role'.equals(scope) || 'project'.equals(scope))) {
    scope = 'project'
  }
  String scopeId = 'user'.equals(scope) ? username : ('role'.equals(scope) ? roleScopeId : siteId)
  String llm = (a.llm ?: 'openAI').toString()
  String llmModel = a.llmModel?.toString()?.trim() ?: 'gpt-4o-mini'
  String imageModel = a.imageModel?.toString()?.trim() ?: ''
  String imageGenerator = a.imageGenerator?.toString()?.trim() ?: ''
  String llmApiKey = a.llmApiKey?.toString()
  boolean manageOtherAgentsHumanTasks =
    Boolean.TRUE.equals(a.get('manageOtherAgentsHumanTasks')) ||
    'true'.equalsIgnoreCase(a.get('manageOtherAgentsHumanTasks')?.toString())

  boolean startAutomatically = true
  try {
    Object sa = a.get('startAutomatically') ?: a.get('start_automatically') ?:
      a.get('automaticallyStart') ?: a.get('automatically_start')
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

  boolean stopOnFailure = true
  try {
    Object sof = a.get('stopOnFailure') ?: a.get('stop_on_failure')
    if (sof != null) {
      if (sof instanceof Boolean) {
        stopOnFailure = (Boolean) sof
      } else {
        String s = sof.toString().trim().toLowerCase()
        if (s == 'false' || s == '0' || s == 'no') {
          stopOnFailure = false
        }
      }
    }
  } catch (Throwable ignored2) {
    stopOnFailure = true
  }

  boolean supervisorOn = AutonomousAssistantSupervisor.isSupervisorEnabled()
  String initialStatus = (startAutomatically && supervisorOn) ? AutonomousAssistantStatus.WAITING : AutonomousAssistantStatus.STOPPED

  String fullId = AutonomousAgentIdBuilder.buildAgentId(siteId, scope, scopeId, name)
  startAutoByFullId.put(fullId, startAutomatically)
  Map agentDef = [
    siteId                      : siteId,
    name                        : name,
    schedule                    : schedule,
    prompt                      : prompt,
    scope                       : scope,
    scopeId                     : scopeId,
    llm                         : llm,
    llmModel                    : llmModel,
    imageModel                  : imageModel,
    imageGenerator              : imageGenerator,
    llmApiKey                : llmApiKey,
    manageOtherAgentsHumanTasks : manageOtherAgentsHumanTasks,
    startAutomatically          : startAutomatically,
    stopOnFailure               : stopOnFailure
  ]
  if (a.get('expertSkills') instanceof List && !((List) a.get('expertSkills')).isEmpty()) {
    agentDef.put('expertSkills', new ArrayList((List) a.get('expertSkills')))
  }
  AutonomousAssistantRegistry.putAgent(siteId, fullId, agentDef)
  AutonomousAssistantStateStore.ensureEntry(fullId, [
    agentId          : fullId,
    displayName      : name,
    status           : initialStatus,
    nextStepRequired : Boolean.FALSE,
    lastRunMillis    : 0L,
    manualStop       : Boolean.FALSE
  ])
  outIds.add(fullId)
}

for (Object fidObj : outIds) {
  String fid = fidObj?.toString()
  if (!fid?.trim()) {
    continue
  }
  Map prev = priorByAgentId.get(fid)
  if (prev == null) {
    continue
  }
  String ps = prev.get('status')?.toString()
  boolean sa = startAutoByFullId.containsKey(fid) ? Boolean.TRUE.equals(startAutoByFullId.get(fid)) : true

  if (AutonomousAssistantStatus.DISABLED == ps) {
    AutonomousAssistantStateStore.mergeState(fid, prev)
    continue
  }
  if (AutonomousAssistantStatus.ERROR == ps) {
    AutonomousAssistantStateStore.mergeState(fid, prev)
    continue
  }
  if (AutonomousAssistantStatus.STOPPED == ps) {
    boolean manualStop = Boolean.TRUE.equals(prev.get('manualStop')) ||
      'true'.equalsIgnoreCase(prev.get('manualStop')?.toString())
    // manualStop or !startAutomatically: restore. Else plain stopped + auto-start: keep ensureEntry waiting (system stop / re-sync).
    if (manualStop || !sa) {
      AutonomousAssistantStateStore.mergeState(fid, prev)
    }
  }
}

if (!AutonomousAssistantSupervisor.isSupervisorEnabled()) {
  for (Object fidObj : outIds) {
    String fid = fidObj?.toString()
    if (!fid?.trim()) {
      continue
    }
    Map cur = AutonomousAssistantStateStore.getState(fid)
    if (cur == null) {
      continue
    }
    String cst = cur.get('status')?.toString()
    if (AutonomousAssistantStatus.DISABLED == cst || AutonomousAssistantStatus.ERROR == cst) {
      continue
    }
    boolean preserveManual = Boolean.TRUE.equals(cur.get('manualStop')) ||
      'true'.equalsIgnoreCase(cur.get('manualStop')?.toString())
    AutonomousAssistantStateStore.mergeState(fid, [
      status          : AutonomousAssistantStatus.STOPPED,
      nextStepRequired: Boolean.FALSE,
      manualStop      : preserveManual ? Boolean.TRUE : Boolean.FALSE
    ])
  }
}

AutonomousAssistantSupervisor.ensureStarted()
try {
  AutonomousAssistantRuntimeHooks.register(applicationContext)
} catch (Throwable ignored) {}

response.setContentType('application/json')
return [
  ok     : true,
  siteId : siteId,
  agentIds: outIds
]
