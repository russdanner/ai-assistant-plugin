package plugins.org.craftercms.aiassistant.http

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP helpers for the <strong>external</strong> CrafterQ API only.
 * <p>Studio CMS operations ({@code GetContent}, {@code WriteContent}, etc.) use in-process Spring beans
 * in {@link StudioToolOperations} — this class does not call Studio REST from Groovy.</p>
 */
class AiHttpProxy {
  private static final Logger logger = LoggerFactory.getLogger(AiHttpProxy.class)

  /** Request attribute set by {@link #installCrafterQBearerFromChatBody} — same name used when applying outbound Authorization. */
  static final String CRAFTERRQ_API_BEARER_TOKEN_ATTR = 'crafterq.crafterQApiBearerToken'

  /**
   * For logs only: short head/tail of a JWT or secret (never log the full value).
   */
  static String crafterQBearerLogPreview(String token) {
    String t = (token ?: '').toString().trim()
    if (!t) {
      return '(empty)'
    }
    int n = t.length()
    if (n <= 14) {
      return t.substring(0, 1) + '…' + (n > 1 ? t.substring(n - 1) : '')
    }
    return t.substring(0, 8) + '…' + t.substring(n - 6)
  }

  /**
   * Do not forward hop-by-hop headers, wrong host, or headers we must set for the CrafterQ JSON/SSE body.
   */
  private static final Set<String> CRAFTERQ_FORWARD_HEADER_DENYLIST = [
    'host', 'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
    /** Studio JWT must not be sent to CrafterQ — auth is {@code X-CrafterQ-Chat-User} (browser localStorage). */
    'authorization',
    'te', 'trailer', 'transfer-encoding', 'upgrade', 'expect',
    'content-length', 'content-type', 'accept'
  ] as Set

  /** Truncate long strings for logs (full payload still sent to CrafterQ). */
  static String elideForLog(String s, int maxChars = 8000) {
    if (s == null) return ''
    if (s.length() <= maxChars) return s
    int head = (int) (maxChars * 0.45)
    int tail = (int) (maxChars * 0.45)
    return s.substring(0, head) + "\n... [elided ${s.length() - head - tail} chars] ...\n" + s.substring(s.length() - tail)
  }

  /**
   * Resolves CrafterQ {@code Authorization: Bearer …} from the chat/stream JSON body (per-agent ui.xml mirrored in the widget)
   * and stores it on the servlet request for {@link #applyCrafterQConfiguredBearerAuthorization}.
   * <p><strong>Precedence:</strong> if {@code crafterQBearerTokenEnv} is set and non-empty, {@code System.getenv(that name)}
   * is used when it returns a non-blank value; otherwise {@code crafterQBearerToken} (literal JWT) is used.
   * A leading {@code Bearer } prefix on the literal is stripped. Studio's inbound {@code Authorization} header is still
   * never forwarded to CrafterQ — this is a separate CrafterQ-only credential.</p>
   * <p>When {@code llm} is not the hosted CrafterQ adapter and neither bearer field is set on the body, this is a
   * no-op with no logs (tools-loop chat / Claude / script paths do not use this token).</p>
   */
  static void installCrafterQBearerFromChatBody(def servletRequest, Map body, String llmRaw) {
    if (!servletRequest || !(body instanceof Map)) {
      return
    }
    Map b = (Map) body
    String envKey =
      (b.crafterQBearerTokenEnv ?: b.get('crafterQ-bearer-token-env') ?: b.crafter_q_bearer_token_env)?.toString()?.trim() ?: ''
    String literal =
      (b.crafterQBearerToken ?: b.get('crafterQ-bearer-token') ?: b.crafter_q_bearer_token)?.toString()?.trim() ?: ''
    if (!envKey && !literal) {
      boolean crafterQChat = false
      String rawLlm = (llmRaw ?: '').toString().trim()
      if (rawLlm) {
        try {
          crafterQChat = StudioAiLlmKind.isCrafterQRemoteApi(StudioAiLlmKind.normalize(rawLlm))
        } catch (Throwable ignoredNorm) {
        }
      }
      if (!crafterQChat) {
        return
      }
    }
    boolean literalPresent = literal != null && literal.length() > 0
    boolean getenvResolved = false
    if (envKey) {
      try {
        String gv = System.getenv(envKey)
        getenvResolved = gv != null && gv.trim().length() > 0
      } catch (Throwable ignored0) {
      }
    }
    logger.info(
      'CrafterQ bearer install (POST body after optional ui.xml merge): crafterQBearerTokenEnv name={} getenvNonBlankForThatName={} crafterQBearerToken literal present={} literalChars={} literalPreview={}',
      envKey ? envKey : '(omitted)',
      getenvResolved,
      literalPresent,
      literalPresent ? literal.length() : 0,
      literalPresent ? crafterQBearerLogPreview(literal) : '(none)'
    )
    String token = ''
    String source = ''
    if (envKey) {
      try {
        String v = System.getenv(envKey)
        if (v?.trim()) {
          token = v.trim()
          source = "env:${envKey}"
        } else {
          logger.warn(
            'CrafterQ bearer: crafterQBearerTokenEnv="{}" is set on the POST body but System.getenv returned blank (check Studio JVM env / spelling / restart). Literal crafterQBearerToken {}.',
            envKey,
            literal ? 'will be tried' : 'not provided'
          )
        }
      } catch (Throwable ignored) {
      }
    }
    if (!token && literal) {
      token = literal
      if (token.regionMatches(true, 0, 'Bearer ', 0, 7)) {
        token = token.substring(7).trim()
      }
      source = literal.regionMatches(true, 0, 'Bearer ', 0, 7) ? 'literal:POST(Bearer stripped)' : 'literal:POST'
    }
    if (!token) {
      logger.warn(
        'CrafterQ bearer NOT installed on this request: outbound api.crafterq.ai calls have no Authorization bearer. ' +
          'crafterQBearerTokenEnv name was={} getenvNonBlank={} literalInBody={} literalChars={} literalPreview={}. ' +
          'Fix: set crafterQBearerTokenEnv + Studio host env, or crafterQBearerToken in ui.xml for this agent, or send them on the stream/chat JSON body; or sign into CrafterQ in the widget (X-CrafterQ-Chat-User).',
        envKey ? envKey : '(omitted)',
        getenvResolved,
        literalPresent,
        literalPresent ? literal.length() : 0,
        literalPresent ? crafterQBearerLogPreview(literal) : '(none)'
      )
      return
    }
    try {
      servletRequest.setAttribute(CRAFTERRQ_API_BEARER_TOKEN_ATTR, token)
    } catch (Throwable ignored) {
    }
    logger.info(
      'CrafterQ API bearer installed for this Studio request: source={} chars={} preview={} (full token is never logged)',
      source,
      token.length(),
      crafterQBearerLogPreview(token)
    )
  }

  /**
   * Sets {@code Authorization: Bearer …} on the outbound CrafterQ connection when
   * {@link #installCrafterQBearerFromChatBody} (or equivalent) stored the token on the request attribute {@link #CRAFTERRQ_API_BEARER_TOKEN_ATTR}.
   */
  static void applyCrafterQConfiguredBearerAuthorization(HttpURLConnection conn, def request) {
    if (!conn || !request) {
      return
    }
    try {
      String t = request.getAttribute(CRAFTERRQ_API_BEARER_TOKEN_ATTR)?.toString()?.trim()
      if (!t) {
        return
      }
      if (t.regionMatches(true, 0, 'Bearer ', 0, 7)) {
        t = t.substring(7).trim()
      }
      if (t) {
        conn.setRequestProperty('Authorization', 'Bearer ' + t)
        if (logger.isDebugEnabled()) {
          logger.debug(
            'CrafterQ outbound Authorization: Bearer chars={} preview={}',
            t.length(),
            crafterQBearerLogPreview(t)
          )
        }
      }
    } catch (Throwable ignored) {
    }
  }

  private static void logCrafterQAuthFailure(String method, String url, int status, def request) {
    if (!(status == 401 || status == 403)) {
      return
    }
    boolean bearerAttr = false
    String bearerPreview = '(request attribute not set — installCrafterQBearerFromChatBody did not run or found no token)'
    try {
      String t = request?.getAttribute(CRAFTERRQ_API_BEARER_TOKEN_ATTR)?.toString()?.trim()
      if (t) {
        bearerAttr = true
        bearerPreview = crafterQBearerLogPreview(t)
      }
    } catch (Throwable ignored) {
    }
    String chatUser = 'absent'
    try {
      String cq = request?.getHeader('X-CrafterQ-Chat-User')?.toString()?.trim()
      if (cq) {
        chatUser = "present(chars=${cq.length()},preview=${crafterQBearerLogPreview(cq)})"
      }
    } catch (Throwable ignored2) {
    }
    logger.warn(
      'CrafterQ {} HTTP {} — url={} — bearerFromPostInstalled={} bearerPreview={} — X-CrafterQ-Chat-User={}',
      method,
      status,
      url,
      bearerAttr,
      bearerPreview,
      chatUser
    )
  }

  /**
   * Copies inbound Studio/plugin request headers onto the outbound CrafterQ {@link HttpURLConnection},
   * except hop-by-hop and headers that must match the new request (see {@link #CRAFTERQ_FORWARD_HEADER_DENYLIST}).
   * After this, callers should {@code setRequestProperty} for {@code Content-Type} and {@code Accept}.
   * Uses {@code addRequestProperty} so multiple values for the same name are preserved.
   */
  static void applyCrafterQForwardedHeaders(HttpURLConnection conn, def request) {
    if (!conn) {
      return
    }
    if (request) {
      try {
        def names = request.getHeaderNames()
        if (names) {
          int forwardedValues = 0
          while (names.hasMoreElements()) {
            String name = names.nextElement() as String
            if (!name?.trim()) {
              continue
            }
            String ln = name.toLowerCase(Locale.ROOT)
            if (CRAFTERQ_FORWARD_HEADER_DENYLIST.contains(ln)) {
              continue
            }
            def vals = request.getHeaders(name)
            if (!vals) {
              continue
            }
            while (vals.hasMoreElements()) {
              def v = vals.nextElement()
              if (v != null) {
                conn.addRequestProperty(name, v.toString())
                forwardedValues++
              }
            }
          }
          if (logger.isDebugEnabled()) {
            logger.debug('CrafterQ forwarded {} inbound header value(s) to upstream (denylist excluded)', forwardedValues)
          }
        }
      } catch (Throwable ignored) {
      }
      try {
        String cq = request.getHeader('X-CrafterQ-Chat-User')?.toString()?.trim()
        if (cq) {
          conn.setRequestProperty('X-CrafterQ-Chat-User', cq)
        }
      } catch (Throwable ignored) {
      }
    }
    applyCrafterQConfiguredBearerAuthorization(conn, request)
  }

  /**
   * Extract assistant-visible text from one CrafterQ SSE JSON event (same shapes as {@code ExpertChatModel}).
   */
  static String extractTextFromCrafterQEvent(def obj) {
    if (obj == null) return ''
    if (obj instanceof String) return obj
    if (obj instanceof Map) {
      def m = (Map) obj
      def t = m?.text ?: m?.content ?: m?.delta
      if (t != null && t.toString().trim().length() > 0) return t.toString()
      t = m?.message
      if (t instanceof Map) return (t?.content ?: t?.text ?: t?.delta ?: '').toString()
      if (t instanceof String) return t
      t = m?.response
      if (t instanceof Map) return (t?.message ?: t?.content ?: t?.text ?: '').toString()
      if (t instanceof String) return t
      def choices = m?.choices
      if (choices instanceof List && !choices.isEmpty()) {
        def first = choices.get(0)
        if (first instanceof Map) {
          def delta = first?.delta
          if (delta instanceof Map) return (delta?.content ?: delta?.text ?: '').toString()
          if (delta instanceof String) return delta
        }
      }
      def data = m?.data
      if (data instanceof Map) return (data?.text ?: data?.content ?: '').toString()
      if (data instanceof String) return data
    }
    return ''
  }

  /**
   * POST {@code /v1/chats?stream=true&agentId=…} with JSON body, read SSE until {@code metadata.completed}, return
   * concatenated assistant text. Matches browser/widget assistant calls (see {@code aiAssistantApi.streamChat}).
   */
  static String postCrafterQStreamChat(String agentId, Map payload, def studioRequest = null) {
    String agent = (agentId ?: '').toString().trim()
    if (!agent) throw new IllegalArgumentException('Missing agentId for CrafterQ stream chat')
    String streamUrl = "https://api.crafterq.ai/v1/chats?stream=true&agentId=${URLEncoder.encode(agent, 'UTF-8')}"
    byte[] payloadBytes = JsonOutput.toJson(payload != null ? payload : [:]).getBytes(StandardCharsets.UTF_8)
    int maxAttempts = 2
    int attempt = 0
    while (attempt < maxAttempts) {
      attempt++
      HttpURLConnection conn = null
      InputStream inputStream = null
      try {
        conn = (HttpURLConnection) new URL(streamUrl).openConnection()
        conn.setRequestMethod('POST')
        conn.setDoOutput(true)
        applyCrafterQForwardedHeaders(conn, studioRequest)
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.setRequestProperty('Accept', 'text/event-stream')
        conn.setChunkedStreamingMode(0)
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(120_000)
        if (logger.isDebugEnabled()) {
          logger.debug('CrafterQ SSE TX: url={} jsonBodyChars={} bodyPreview=\n{}', streamUrl, payloadBytes.length,
            elideForLog(new String(payloadBytes, StandardCharsets.UTF_8), 6000))
        }
        conn.getOutputStream().withCloseable { os ->
          os.write(payloadBytes)
        }
        int status = conn.getResponseCode()
        if (status >= 500 && attempt < maxAttempts) {
          logger.warn('POST {} got HTTP {} (attempt {}/{}), retrying once', streamUrl, status, attempt, maxAttempts)
          sleep(400)
          continue
        }
        if (status < 200 || status >= 300) {
          InputStream err = conn.getErrorStream()
          String errText = ''
          if (err != null) {
            err.withCloseable { errText = it.getText('UTF-8') ?: '' }
          }
          logCrafterQAuthFailure('POST(SSE)', streamUrl, status, studioRequest)
          throw new RuntimeException("HTTP ${status} calling ${streamUrl}: ${errText ?: conn.getResponseMessage()}")
        }
        inputStream = conn.getInputStream()
        StringBuilder acc = new StringBuilder()
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
          def slurper = new JsonSlurper()
          String line
          while ((line = reader.readLine()) != null) {
            if (!line.startsWith('data:')) continue
            def data = line.substring(5).trim()
            if (!data) continue
            try {
              def obj = slurper.parseText(data)
              def metaObj = obj?.metadata ?: [:]
              def meta = (metaObj instanceof Map) ? (metaObj as Map) : [:]
              boolean completed = (meta?.completed != null) ? meta.completed.asBoolean() : false
              String chunk = extractTextFromCrafterQEvent(obj)
              if (chunk != null && chunk.length() > 0) {
                acc.append(chunk)
              }
              if (completed) break
            } catch (Exception ex) {
              logger.warn('CrafterQ SSE parse failed: {}', ex.toString())
            }
          }
        }
        inputStream = null
        String out = acc.toString()
        if (logger.isDebugEnabled()) {
          logger.debug('CrafterQ SSE RX: url={} assistantChars={} preview=\n{}', streamUrl, out.length(), elideForLog(out, 4000))
        }
        return out
      } catch (SocketTimeoutException ste) {
        logger.error('POST {} timed out (attempt {}/{}): {}', streamUrl, attempt, maxAttempts, ste.toString())
        if (attempt >= maxAttempts) throw new RuntimeException("Request timed out calling ${streamUrl}", ste)
      } catch (Exception e) {
        if (attempt >= maxAttempts) throw e
        logger.warn('POST {} failed (attempt {}/{}): {}', streamUrl, attempt, maxAttempts, e.toString())
      } finally {
        try {
          inputStream?.close()
        } catch (Throwable ignored) {
        }
        try {
          conn?.disconnect()
        } catch (Throwable ignored2) {
        }
        inputStream = null
      }
    }
    throw new RuntimeException("POST failed after retries calling ${streamUrl}")
  }

  /**
   * POST JSON to CrafterQ. Forwards almost all headers from {@code studioRequest} (inbound plugin call),
   * then forces {@code Content-Type} / {@code Accept} for this JSON body.
   */
  static Object postJson(String url, Object body, def studioRequest = null) {
    int maxAttempts = 2
    int attempt = 0
    while (attempt < maxAttempts) {
      attempt++
      HttpURLConnection conn = null
      try {
        conn = (HttpURLConnection) new URL(url).openConnection()
        conn.setRequestMethod('POST')
        conn.setDoOutput(true)
        applyCrafterQForwardedHeaders(conn, studioRequest)
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.setRequestProperty('Accept', 'application/json')
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(45000)

        def payload = JsonOutput.toJson(body != null ? body : [:])
        if (logger.isDebugEnabled()) {
          logger.debug("CrafterQ HTTP TX: url={} jsonBodyChars={} bodyPreview=\n{}", url, payload.length(), elideForLog(payload, 6000))
        }
        conn.getOutputStream().withCloseable { os ->
          os.write(payload.getBytes(StandardCharsets.UTF_8))
        }

        int status = conn.getResponseCode()
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()
        String text = ''
        if (is != null) {
          is.withCloseable { stream ->
            text = stream.getText('UTF-8') ?: ''
          }
        }

        if (status >= 500 && attempt < maxAttempts) {
          logger.warn("POST {} got HTTP {} (attempt {}/{}), retrying once", url, status, attempt, maxAttempts)
          sleep(400)
          continue
        }

        if (logger.isDebugEnabled()) {
          logger.debug("CrafterQ HTTP RX: url={} status={} responseChars={} responsePreview=\n{}", url, status, text.length(), elideForLog(text, 6000))
        }

        if (status < 200 || status >= 300) {
          logCrafterQAuthFailure('POST', url, status, studioRequest)
          throw new RuntimeException("HTTP ${status} calling ${url}: ${text ?: conn.getResponseMessage()}")
        }

        if (!text?.trim()) return [:]
        try {
          return new JsonSlurper().parseText(text)
        } catch (ignored) {
          return [text: text]
        }
      } catch (SocketTimeoutException ste) {
        logger.error("POST {} timed out (attempt {}/{}): {}", url, attempt, maxAttempts, ste.toString())
        if (attempt >= maxAttempts) throw new RuntimeException("Request timed out calling ${url}")
      } catch (Exception e) {
        logger.error("POST {} failed (attempt {}/{}): {}", url, attempt, maxAttempts, e.toString())
        if (attempt >= maxAttempts) throw e
      } finally {
        try { conn?.disconnect() } catch (ignored) {}
      }
    }
    throw new RuntimeException("POST failed after retries calling ${url}")
  }

  /**
   * GET JSON from CrafterQ (e.g. {@code /v1/agents/{id}/chats}). Forwards the same headers as
   * {@link #postCrafterQStreamChat} via {@link #applyCrafterQForwardedHeaders}; sets {@code Accept: application/json}.
   * Returns a {@link Map} or {@link List} from {@link JsonSlurper}, or {@code [:]} for empty body.
   */
  static Object getJson(String url, def studioRequest = null) {
    int maxAttempts = 2
    int attempt = 0
    while (attempt < maxAttempts) {
      attempt++
      HttpURLConnection conn = null
      try {
        conn = (HttpURLConnection) new URL(url).openConnection()
        conn.setRequestMethod('GET')
        conn.setDoOutput(false)
        applyCrafterQForwardedHeaders(conn, studioRequest)
        conn.setRequestProperty('Accept', 'application/json')
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(60_000)

        if (logger.isDebugEnabled()) {
          logger.debug('CrafterQ HTTP GET: url={}', url)
        }

        int status = conn.getResponseCode()
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()
        String text = ''
        if (is != null) {
          is.withCloseable { stream ->
            text = stream.getText('UTF-8') ?: ''
          }
        }

        if (status >= 500 && attempt < maxAttempts) {
          logger.warn('GET {} got HTTP {} (attempt {}/{}), retrying once', url, status, attempt, maxAttempts)
          sleep(400)
          continue
        }

        if (logger.isDebugEnabled()) {
          logger.debug('CrafterQ HTTP GET RX: url={} status={} responseChars={} responsePreview=\n{}', url, status, text.length(), elideForLog(text, 6000))
        }

        if (status < 200 || status >= 300) {
          logCrafterQAuthFailure('GET', url, status, studioRequest)
          throw new RuntimeException("HTTP ${status} calling ${url}: ${text ?: conn.getResponseMessage()}")
        }

        if (!text?.trim()) {
          return [:]
        }
        try {
          return new JsonSlurper().parseText(text)
        } catch (ignored) {
          return [text: text]
        }
      } catch (SocketTimeoutException ste) {
        logger.error('GET {} timed out (attempt {}/{}): {}', url, attempt, maxAttempts, ste.toString())
        if (attempt >= maxAttempts) {
          throw new RuntimeException("Request timed out calling ${url}", ste)
        }
      } catch (Exception e) {
        if (attempt >= maxAttempts) {
          throw e
        }
        logger.warn('GET {} failed (attempt {}/{}): {}', url, attempt, maxAttempts, e.toString())
      } finally {
        try {
          conn?.disconnect()
        } catch (ignored) {
        }
      }
    }
    throw new RuntimeException("GET failed after retries calling ${url}")
  }

  /**
   * Reads the servlet POST body as JSON. Never throws: invalid JSON returns a map with
   * {@code __crafterqInvalidJson} so REST scripts can return 400 instead of an unhandled 500/HTML error page.
   */
  static Map parseJsonBody(def request) {
    def reader = request?.getReader()
    if (!reader) return [:]
    def sb = new StringBuilder()
    String line
    while ((line = reader.readLine()) != null) {
      sb.append(line)
    }
    def text = sb.toString()
    if (!text?.trim()) return [:]
    try {
      def parsed = new JsonSlurper().parseText(text)
      return (parsed instanceof Map) ? (Map) parsed : [value: parsed]
    } catch (Throwable t) {
      logger.warn('parseJsonBody: invalid JSON ({} chars): {}', text.length(), t.message)
      return [
        __crafterqInvalidJson      : true,
        __crafterqInvalidJsonDetail: (t.message ?: t.toString())
      ]
    }
  }
}
