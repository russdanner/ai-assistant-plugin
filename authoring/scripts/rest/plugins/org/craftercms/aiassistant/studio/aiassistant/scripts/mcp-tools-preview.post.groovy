import jakarta.servlet.http.HttpServletResponse
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.mcp.StudioAiMcpClient
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Lists MCP tools from the request body (same {@code mcpServers} shape as {@code tools.json}) without persisting.
 * Used by Project Tools before saving so admins can enable/disable individual {@code mcp_*} wire tools.
 *
 * <p>Body JSON: {@code siteId} (optional if query param), {@code mcpEnabled}, {@code mcpServers} (array of maps with
 * {@code id}, {@code url}, optional {@code headers}, optional {@code readTimeoutMs}).</p>
 */
def body = AiHttpProxy.parseJsonBody(request)
if (Boolean.TRUE.equals(body?.get('__crafterqInvalidJson'))) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Invalid JSON request body', detail: body?.get('__crafterqInvalidJsonDetail')?.toString() ?: '']
}
String siteId = (params?.siteId ?: body.siteId ?: request.getParameter('siteId'))?.toString()?.trim()
if (!siteId) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Missing siteId']
}

def ops = new StudioToolOperations(request, applicationContext, params)

if (!ops.httpFetchGloballyEnabled()) {
  return [ok: false, message: 'Outbound HTTP is disabled (crafterq.httpFetch.enabled=false); MCP preview is unavailable.']
}

boolean mcpOn = Boolean.TRUE.equals(body.mcpEnabled)
if (!mcpOn) {
  return [ok: true, mcpEnabled: false, servers: []]
}

Object rawServers = body.mcpServers
if (!(rawServers instanceof List) || ((List) rawServers).isEmpty()) {
  return [ok: true, mcpEnabled: true, servers: []]
}

List<Map> serversOut = []
for (Object row : (List) rawServers) {
  if (!(row instanceof Map)) {
    continue
  }
  Map spec = (Map) row
  String sid = spec.id?.toString()?.trim() ?: ''
  String url = spec.url?.toString()?.trim() ?: ''
  if (!sid || !url) {
    continue
  }
  try {
    Map res = StudioAiMcpClient.openSessionAndListTools(ops, spec)
    List tools = res.tools instanceof List ? (List) res.tools : []
    List<Map> items = []
    for (Object t : tools) {
      if (!(t instanceof Map)) {
        continue
      }
      Map tm = (Map) t
      String mcpName = tm.name?.toString()?.trim() ?: ''
      if (!mcpName) {
        continue
      }
      String desc = ''
      try {
        desc = tm.description?.toString() ?: ''
      } catch (Throwable ignored) {
      }
      String wire = StudioAiMcpClient.wireToolName(sid, mcpName)
      items.add([
        wireName    : wire,
        mcpToolName : mcpName,
        description : desc
      ])
    }
    serversOut.add([serverId: sid, ok: true, tools: items])
  } catch (Throwable e) {
    serversOut.add([
      serverId: sid,
      ok      : false,
      message : e.message ?: e.toString(),
      tools   : []
    ])
  }
}

return [ok: true, mcpEnabled: true, servers: serversOut]
