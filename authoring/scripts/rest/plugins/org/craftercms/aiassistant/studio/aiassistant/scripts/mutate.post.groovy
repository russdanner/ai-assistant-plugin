import jakarta.servlet.http.HttpServletResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.security.core.context.SecurityContextHolder
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsLoader
import plugins.org.craftercms.aiassistant.tools.StudioAiUserSiteTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Mutate site sandbox AI Assistant scripts / registry.
 * Query: {@code siteId}. Body JSON:
 * <pre>
 * { "action": "writeStudioUtf8", "studioPath": "/scripts/aiassistant/...", "utf8": "..." }
 * { "action": "deleteStudioRepo", "repoPath": "/config/studio/scripts/aiassistant/..." }
 * { "action": "removeUserTool", "toolId": "mytool" }
 * { "action": "invalidateToolPrompts" } — clears {@code ToolPromptsLoader} cache after editing {@code prompts/*.md} via {@code writeConfiguration}
 * </pre>
 */
def body = AiHttpProxy.parseJsonBody(request)
if (Boolean.TRUE.equals(body?.get('__aiassistantInvalidJson'))) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Invalid JSON request body', detail: body?.get('__aiassistantInvalidJsonDetail')?.toString() ?: '']
}
Map reqBody = (body instanceof Map) ? (Map) body : [:]
String siteId = (params?.siteId ?: reqBody.get('siteId') ?: request.getParameter('siteId'))?.toString()?.trim()
if (!siteId) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Missing siteId']
}

String action = reqBody.action?.toString()?.trim()?.toLowerCase() ?: ''
def ops = new StudioToolOperations(request, applicationContext, params)
String approver = ''
try {
  approver = SecurityContextHolder.context?.authentication?.name?.toString()?.trim() ?: ''
} catch (Throwable ignored) {
}

try {
  if ('refreshsync' == action || 'refresh' == action) {
    ops.publishConfigChangeRefresh(siteId)
    return [ok: true, message: 'Published sync refresh']
  }
  if ('invalidatetoolprompts' == action) {
    ToolPromptsLoader.invalidateCachesAfterSitePromptMutation(siteId)
    ops.publishConfigChangeRefresh(siteId)
    return [ok: true, message: 'Tool prompt cache invalidated']
  }
  if ('writestudioutf8' == action || 'write' == action) {
    String sp = reqBody.studioPath?.toString()?.trim() ?: ''
    String utf8 = reqBody.utf8 != null ? reqBody.utf8.toString() : ''
    if (!sp.startsWith('/')) {
      sp = "/${sp}"
    }
    if (!sp.startsWith('/scripts/aiassistant/')) {
      response.status = HttpServletResponse.SC_BAD_REQUEST
      return [ok: false, message: 'studioPath must be under /scripts/aiassistant/']
    }
    ops.writeStudioConfiguration(siteId, sp, utf8.getBytes('UTF-8'))
    if (sp.startsWith('/scripts/aiassistant/prompts/') && sp.endsWith('.md')) {
      ToolPromptsLoader.invalidateCachesAfterSitePromptMutation(siteId)
    }
    ops.publishConfigChangeRefresh(siteId)
    return [ok: true, message: 'Written', studioPath: sp]
  }
  if ('deletestudiorepo' == action || 'delete' == action) {
    String rp = reqBody.repoPath?.toString()?.trim() ?: ''
    if (!rp.startsWith('/')) {
      rp = "/${rp}"
    }
    if (!rp.startsWith('/config/studio/scripts/aiassistant/')) {
      response.status = HttpServletResponse.SC_BAD_REQUEST
      return [ok: false, message: 'repoPath must be under /config/studio/scripts/aiassistant/']
    }
    ops.deleteStudioSandboxItem(siteId, rp, approver)
    if (rp.contains('/scripts/aiassistant/prompts/') && rp.endsWith('.md')) {
      ToolPromptsLoader.invalidateCachesAfterSitePromptMutation(siteId)
    }
    ops.publishConfigChangeRefresh(siteId)
    return [ok: true, message: 'Deleted', repoPath: rp]
  }
  if ('removeusertool' == action) {
    String tid = reqBody.toolId?.toString()?.trim() ?: ''
    if (!tid) {
      response.status = HttpServletResponse.SC_BAD_REQUEST
      return [ok: false, message: 'Missing toolId']
    }
    List<Map> entries = StudioAiUserSiteTools.loadRegistryEntries(ops)
    Map found = null
    for (Map e : entries) {
      if (tid == e.id?.toString()?.trim()) {
        found = e
        break
      }
    }
    if (found == null) {
      response.status = HttpServletResponse.SC_NOT_FOUND
      return [ok: false, message: "Tool id '${tid}' not in registry"]
    }
    String scriptName = found.script?.toString()?.trim()
    String raw = ops.readStudioConfigurationUtf8(siteId, StudioAiUserSiteTools.USER_TOOLS_REGISTRY_PATH) ?: ''
    String trimmed = raw.trim()
    if (!trimmed) {
      trimmed = '{"tools":[]}'
    }
    Object parsed = new JsonSlurper().parseText(trimmed)
    List outRows = []
    if (parsed instanceof List) {
      for (Object o : (List) parsed) {
        if (o instanceof Map && tid != ((Map) o).id?.toString()?.trim()) {
          outRows.add(o)
        }
      }
    } else if (parsed instanceof Map) {
      Map pm = (Map) parsed
      Object tools = pm.get('tools')
      if (tools instanceof List) {
        for (Object o : (List) tools) {
          if (o instanceof Map && tid != ((Map) o).id?.toString()?.trim()) {
            outRows.add(o)
          }
        }
      }
    }
    String newJson
    if (parsed instanceof List) {
      newJson = JsonOutput.prettyPrint(outRows)
    } else if (parsed instanceof Map) {
      Map pm = [:]
      pm.putAll((Map) parsed)
      pm.put('tools', outRows)
      newJson = JsonOutput.prettyPrint(pm)
    } else {
      newJson = JsonOutput.prettyPrint([tools: outRows])
    }
    ops.writeStudioConfiguration(siteId, StudioAiUserSiteTools.USER_TOOLS_REGISTRY_PATH, newJson.getBytes('UTF-8'))
    if (scriptName) {
      String studioRel = "${StudioAiUserSiteTools.USER_TOOLS_DIR_PREFIX}${scriptName}"
      String existing = ops.readStudioConfigurationUtf8(siteId, studioRel)
      if (existing?.trim()) {
        String fullScript = "/config/studio${studioRel}"
        ops.deleteStudioSandboxItem(siteId, fullScript, approver)
      }
    }
    ops.publishConfigChangeRefresh(siteId)
    return [ok: true, message: 'Removed user tool', toolId: tid]
  }
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: "Unknown action '${action}'"]
} catch (Throwable t) {
  response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
  return [ok: false, message: t.message ?: t.toString()]
}
