package plugins.org.craftercms.aiassistant.mcp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal **MCP client** for the Streamable HTTP transport (JSON-RPC over HTTP POST).
 * <p>Registers extra function tools from site {@code tools.json} when {@code mcpEnabled} is JSON {@code true};
 * see {@link plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig#mcpClientEnabled}.
 * URLs use the same SSRF gate as {@link StudioToolOperations#fetchHttpUrl}.</p>
 */
final class StudioAiMcpClient {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiMcpClient.class)

  private static final int DEFAULT_READ_TIMEOUT_MS = 120_000

  private static int maxMcpResponseChars() {
    int cap = 500_000
    try {
      def p = System.getProperty('aiassistant.mcp.maxResponseChars')?.toString()?.trim()
      if (!p) {
        p = System.getProperty('crafterq.mcp.maxResponseChars')?.toString()?.trim()
      }
      if (p) {
        cap = Integer.parseInt(p)
      }
    } catch (Throwable ignored) {}
    return Math.min(2_000_000, Math.max(16_384, cap))
  }

  /**
   * One MCP session per server for a single Studio chat request.
   */
  static final class McpConnection {
    final String serverId
    final String baseUrl
    final Map<String, String> extraHeaders
    final int readTimeoutMs
    volatile String sessionId
    volatile String protocolVersion
    private final AtomicInteger rpcSeq = new AtomicInteger(1)

    private McpConnection(String serverId, String baseUrl, Map<String, String> extraHeaders, int readTimeoutMs) {
      this.serverId = serverId
      this.baseUrl = baseUrl
      this.extraHeaders = extraHeaders != null ? new LinkedHashMap<>(extraHeaders) : [:]
      this.readTimeoutMs = readTimeoutMs
      this.protocolVersion = '2024-11-05'
    }

    int allocRpcId() {
      return rpcSeq.getAndIncrement()
    }

    Map toolsCall(String mcpToolName, Map arguments) {
      int id = allocRpcId()
      Map params = new LinkedHashMap<>()
      params.put('name', mcpToolName)
      params.put('arguments', arguments != null ? arguments : [:])
      boolean sess = sessionId != null && sessionId.toString().trim().length() > 0
      Map env = postJsonRpc('tools/call', params, id, sess)
      if (env.error != null) {
        return [
          ok     : false,
          mcp    : true,
          server : serverId,
          tool   : mcpToolName,
          message: (env.error instanceof Map) ? (((Map) env.error).get('message')?.toString() ?: 'MCP error') : 'MCP error',
          error  : env.error
        ]
      }
      return [
        ok    : true,
        mcp   : true,
        server: serverId,
        tool  : mcpToolName,
        result: env.result
      ]
    }

    Map postJsonRpc(String method, Map params, int id, boolean sessionRequired) {
      Map body = new LinkedHashMap<>()
      body.put('jsonrpc', '2.0')
      body.put('method', method)
      body.put('params', params != null ? params : [:])
      body.put('id', id)
      byte[] bytes = JsonOutput.toJson(body).getBytes(StandardCharsets.UTF_8)

      URI uri = new URI(baseUrl)
      String gate2 = StudioToolOperations.validateOutboundHttpUrlForSsrf(uri.toString())
      if (gate2) {
        throw new IllegalStateException("MCP url blocked: ${gate2}")
      }

      HttpURLConnection http = null
      try {
        http = (HttpURLConnection) uri.toURL().openConnection()
        http.setRequestMethod('POST')
        http.setInstanceFollowRedirects(false)
        http.setConnectTimeout(15_000)
        http.setReadTimeout(readTimeoutMs)
        http.setDoOutput(true)
        http.setRequestProperty('Content-Type', 'application/json')
        http.setRequestProperty('Accept', 'application/json, text/event-stream')
        http.setRequestProperty('User-Agent', 'CrafterQ-Studio-Plugin-MCP/1.0 (+https://craftercms.org)')
        String pv = (protocolVersion ?: '2024-11-05').toString().trim()
        http.setRequestProperty('MCP-Protocol-Version', pv ?: '2024-11-05')
        String sidHdr = sessionId?.toString()?.trim()
        if (sidHdr) {
          http.setRequestProperty('Mcp-Session-Id', sidHdr)
        } else if (sessionRequired) {
          throw new IllegalStateException('MCP session id missing for ' + method)
        }
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
          http.setRequestProperty(e.key, e.value)
        }
        http.outputStream.write(bytes)
        http.outputStream.flush()

        int code = http.getResponseCode()
        String ct = (http.getContentType() ?: '').toString().toLowerCase(Locale.ROOT)
        String newSession = http.getHeaderField('Mcp-Session-Id')?.toString()?.trim()
        InputStream raw = code >= 400 ? http.getErrorStream() : http.getInputStream()
        String text = readBodyCapped(raw, maxMcpResponseChars())

        if (ct.contains('text/event-stream')) {
          return parseSseRpc(text, id, newSession)
        }
        if (!text?.trim()) {
          if (code == 202) {
            return [error: null, result: null, sessionId: newSession ?: sidHdr]
          }
          return [error: [message: "HTTP ${code} empty body"], result: null, sessionId: newSession]
        }
        Object parsed
        try {
          parsed = new JsonSlurper().parseText(text.trim())
        } catch (Throwable t) {
          return [error: [message: "Invalid JSON HTTP ${code}: ${t.message}"], result: null, sessionId: newSession]
        }
        if (!(parsed instanceof Map)) {
          return [error: [message: 'Non-object JSON response'], result: null, sessionId: newSession]
        }
        Map m = (Map) parsed
        if (m.containsKey('error') && m.get('error') != null) {
          return [error: m.get('error'), result: null, sessionId: newSession]
        }
        return [error: null, result: m.get('result'), sessionId: newSession]
      } finally {
        try {
          http?.disconnect()
        } catch (Throwable ignored) {}
      }
    }

    void postNotification(String method, Map params) {
      Map body = new LinkedHashMap<>()
      body.put('jsonrpc', '2.0')
      body.put('method', method)
      body.put('params', params != null ? params : [:])
      byte[] bytes = JsonOutput.toJson(body).getBytes(StandardCharsets.UTF_8)

      URI uri = new URI(baseUrl)
      String gate2 = StudioToolOperations.validateOutboundHttpUrlForSsrf(uri.toString())
      if (gate2) {
        throw new IllegalStateException("MCP url blocked: ${gate2}")
      }
      HttpURLConnection http = null
      try {
        http = (HttpURLConnection) uri.toURL().openConnection()
        http.setRequestMethod('POST')
        http.setInstanceFollowRedirects(false)
        http.setConnectTimeout(15_000)
        http.setReadTimeout(readTimeoutMs)
        http.setDoOutput(true)
        http.setRequestProperty('Content-Type', 'application/json')
        http.setRequestProperty('Accept', 'application/json, text/event-stream')
        http.setRequestProperty('MCP-Protocol-Version', (protocolVersion ?: '2024-11-05').toString().trim())
        String sidHdr = sessionId?.toString()?.trim()
        if (sidHdr) {
          http.setRequestProperty('Mcp-Session-Id', sidHdr)
        }
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
          http.setRequestProperty(e.key, e.value)
        }
        http.outputStream.write(bytes)
        http.outputStream.flush()
        int code = http.getResponseCode()
        if (code != 202 && (code < 200 || code >= 300)) {
          InputStream err = code >= 400 ? http.getErrorStream() : http.getInputStream()
          String t = readBodyCapped(err, maxMcpResponseChars())
          LOG.warn('MCP notification {} returned HTTP {} body={}', method, code, t?.take(500))
        }
      } finally {
        try {
          http?.disconnect()
        } catch (Throwable ignored) {}
      }
    }

    private static Map parseSseRpc(String text, int wantId, String sessionFromHttp) {
      if (!text?.trim()) {
        return [error: [message: 'empty SSE body'], result: null, sessionId: sessionFromHttp]
      }
      String lastSession = sessionFromHttp
      Map lastMatch = null
      for (String eventBlock : text.split('\n\n')) {
        if (!eventBlock?.trim()) {
          continue
        }
        StringBuilder dataJoin = new StringBuilder()
        for (String line : eventBlock.split('\n')) {
          if (line.startsWith('data:')) {
            if (dataJoin.length() > 0) {
              dataJoin.append('\n')
            }
            dataJoin.append(line.substring(5).trim())
          }
        }
        String payload = dataJoin.toString().trim()
        if (!payload || '[DONE]'.equalsIgnoreCase(payload)) {
          continue
        }
        try {
          Object parsed = new JsonSlurper().parseText(payload)
          if (parsed instanceof Map) {
            Map m = (Map) parsed
            if (!rpcIdMatches(m.get('id'), wantId)) {
              continue
            }
            lastMatch = m
          }
        } catch (Throwable ignored) {
        }
      }
      if (lastMatch == null) {
        return [error: [message: 'no matching JSON-RPC id in SSE stream'], result: null, sessionId: lastSession]
      }
      if (lastMatch.containsKey('error') && lastMatch.get('error') != null) {
        return [error: lastMatch.get('error'), result: null, sessionId: lastSession]
      }
      return [error: null, result: lastMatch.get('result'), sessionId: lastSession]
    }

    private static boolean rpcIdMatches(Object rid, int wantId) {
      if (rid == null) {
        return false
      }
      if (rid instanceof Number) {
        return ((Number) rid).intValue() == wantId
      }
      try {
        return Integer.parseInt(rid.toString().trim()) == wantId
      } catch (Throwable ignored) {
        return false
      }
    }

    private static String readBodyCapped(InputStream inStream, int maxChars) {
      if (inStream == null) {
        return ''
      }
      StringBuilder sb = new StringBuilder(Math.min(maxChars + 16, 65536))
      BufferedReader br = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))
      try {
        char[] cbuf = new char[8192]
        int total = 0
        while (true) {
          int n = br.read(cbuf)
          if (n < 0) {
            break
          }
          if (total + n <= maxChars) {
            sb.append(cbuf, 0, n)
            total += n
          } else {
            int take = maxChars - total
            if (take > 0) {
              sb.append(cbuf, 0, take)
            }
            break
          }
        }
      } catch (Throwable t) {
        LOG.debug('MCP read body: {}', t.toString())
      } finally {
        try {
          br.close()
        } catch (Throwable ignored) {}
      }
      return sb.toString()
    }
  }

  static Map openSessionAndListTools(StudioToolOperations ops, Map spec) {
    String sid = spec?.id?.toString()?.trim() ?: ''
    String url = spec?.url?.toString()?.trim() ?: ''
    if (!sid || !url) {
      throw new IllegalArgumentException('mcpServers entry requires non-blank id and url')
    }
    String gate = StudioToolOperations.validateOutboundHttpUrlForSsrf(url)
    if (gate) {
      throw new IllegalStateException("MCP url blocked for server '${sid}': ${gate}")
    }
    if (ops != null && !ops.httpFetchGloballyEnabled()) {
      throw new IllegalStateException('MCP disabled: aiassistant.httpFetch.enabled=false')
    }
    int readTimeout = DEFAULT_READ_TIMEOUT_MS
    try {
      def r = spec.readTimeoutMs
      if (r instanceof Number) {
        readTimeout = ((Number) r).intValue()
      } else if (r != null) {
        readTimeout = Integer.parseInt(r.toString().trim())
      }
    } catch (Throwable ignored) {
      readTimeout = DEFAULT_READ_TIMEOUT_MS
    }
    readTimeout = Math.min(600_000, Math.max(10_000, readTimeout))

    Map<String, String> hdrs = normalizeHeaderMap(spec.headers)
    McpConnection conn = new McpConnection(sid, url, hdrs, readTimeout)

    Map initParams = [
      protocolVersion: '2024-11-05',
      capabilities    : [tools: [:]],
      clientInfo      : [name: 'crafter-studio-aiassistant', version: '1.0.0']
    ]
    int initId = conn.allocRpcId()
    Map initEnv = conn.postJsonRpc('initialize', initParams, initId, false)
    if (initEnv.error != null) {
      throw new IllegalStateException("MCP initialize failed (${sid}): ${initEnv.error}")
    }
    Map initResult = initEnv.result instanceof Map ? (Map) initEnv.result : [:]
    String pv = initResult.get('protocolVersion')?.toString()?.trim()
    if (pv) {
      conn.protocolVersion = pv
    }
    if (initEnv.sessionId?.toString()?.trim()) {
      conn.sessionId = initEnv.sessionId.toString().trim()
    }
    conn.postNotification('notifications/initialized', [:])

    int listId = conn.allocRpcId()
    boolean listSess = conn.sessionId != null && conn.sessionId.toString().trim().length() > 0
    Map listEnv = conn.postJsonRpc('tools/list', [:], listId, listSess)
    if (listEnv.error != null) {
      throw new IllegalStateException("MCP tools/list failed (${sid}): ${listEnv.error}")
    }
    Map listResult = listEnv.result instanceof Map ? (Map) listEnv.result : [:]
    Object toolsRaw = listResult.get('tools')
    List<Map> tools = []
    if (toolsRaw instanceof List) {
      for (Object o : (List) toolsRaw) {
        if (o instanceof Map) {
          tools.add((Map) o)
        }
      }
    }
    return [connection: conn, tools: tools]
  }

  private static final java.util.regex.Pattern ENV_MACRO = java.util.regex.Pattern.compile('\\$\\{env:([A-Za-z0-9_.]+)\\}')

  /**
   * Expands {@code ${env:VAR}} placeholders using {@link System#getenv} on the Studio JVM.
   * Unknown or unset variables expand to an empty string. Replacement uses {@link java.util.regex.Matcher#quoteReplacement}
   * so expanded values may contain {@code $} or backslashes.
   */
  static String expandEnvMacrosInString(String input) {
    if (input == null) {
      return ''
    }
    String s = input.toString()
    if (!s.contains('${env:')) {
      return s
    }
    java.util.regex.Matcher m = ENV_MACRO.matcher(s)
    StringBuffer sb = new StringBuffer()
    while (m.find()) {
      String name = m.group(1)
      String val = ''
      try {
        String gv = System.getenv(name)
        if (gv != null) {
          val = gv
        }
      } catch (Throwable ignored) {
      }
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val))
    }
    m.appendTail(sb)
    return sb.toString()
  }

  private static Map<String, String> normalizeHeaderMap(Object headers) {
    if (!(headers instanceof Map)) {
      return [:]
    }
    Map<String, String> out = new LinkedHashMap<>()
    for (Map.Entry e : ((Map) headers).entrySet()) {
      String k = e.key != null ? e.key.toString().trim() : ''
      String v = e.value != null ? e.value.toString() : ''
      if (k) {
        out.put(k, expandEnvMacrosInString(v))
      }
    }
    return out
  }

  /** OpenAI function name: {@code mcp_<serverId>_<mcpToolName>}, max 64 chars. */
  static String wireToolName(String serverId, String mcpToolName) {
    String a = sanitizeToken(serverId)
    String b = sanitizeToken(mcpToolName)
    String base = "mcp_${a}_${b}"
    if (base.length() <= 64) {
      return base
    }
    return base.substring(0, 64)
  }

  private static String sanitizeToken(String s) {
    if (s == null) {
      return 'x'
    }
    String t = s.replaceAll('[^a-zA-Z0-9_]+', '_').replaceAll('_+', '_')
    t = t.replaceAll('^_|_$', '')
    return t.length() > 0 ? t : 'x'
  }
}
