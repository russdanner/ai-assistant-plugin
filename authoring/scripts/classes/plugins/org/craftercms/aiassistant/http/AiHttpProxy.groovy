package plugins.org.craftercms.aiassistant.http

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Shared HTTP helpers for plugin REST scripts (JSON body parsing, log elision).
 */
class AiHttpProxy {
  private static final Logger logger = LoggerFactory.getLogger(AiHttpProxy.class)

  /** Truncate long strings for logs. */
  static String elideForLog(String s, int maxChars = 8000) {
    if (s == null) return ''
    if (s.length() <= maxChars) return s
    int head = (int) (maxChars * 0.45)
    int tail = (int) (maxChars * 0.45)
    return s.substring(0, head) + "\n... [elided ${s.length() - head - tail} chars] ...\n" + s.substring(s.length() - tail)
  }

  /**
   * Reads the servlet POST body as JSON. Never throws: invalid JSON or oversize body returns a map with
   * {@code __aiassistantInvalidJson} so REST scripts can return 400 instead of an unhandled 500/HTML error page.
   * <p>Default max characters read from the servlet reader: {@code 1_048_576} (1 MiB-ish). Pass a larger
   * {@code maxBodyChars} for endpoints that accept embedded {@code data:image/...;base64,...} (e.g. import-from-url).
   * Override globally with JVM property {@code aiassistant.maxJsonBodyChars} when {@code maxBodyChars} is null.</p>
   *
   * @param maxBodyChars when non-null and {@code > 4096}, used as the read cap for this request only (ignores JVM default cap)
   */
  static Map parseJsonBody(def request, Integer maxBodyChars = null) {
    try {
      def reader = request?.getReader()
      if (!reader) {
        return [:]
      }
      int maxChars = 1_048_576
      if (maxBodyChars != null && maxBodyChars > 4096) {
        maxChars = maxBodyChars
      } else {
        try {
          Integer prop = Integer.getInteger('aiassistant.maxJsonBodyChars')
          if (prop != null && prop > 4096) {
            maxChars = prop
          }
        } catch (Throwable ignoredProp) {
        }
      }
      StringBuilder sb = new StringBuilder(Math.min(maxChars, 131072))
      char[] buf = new char[8192]
      int total = 0
      int n
      while ((n = reader.read(buf)) != -1) {
        if (total + n > maxChars) {
          try {
            while (reader.read(buf) != -1) {
              // drain connection so client sees full upload accepted; we discard
            }
          } catch (Throwable ignoredDrain) {
          }
          return [
            __aiassistantInvalidJson      : true,
            __aiassistantInvalidJsonDetail: "Request body too large (>${maxChars} chars); raise aiassistant.maxJsonBodyChars if needed."
          ]
        }
        sb.append(buf, 0, n)
        total += n
      }
      String text = sb.toString()
      if (!text?.trim()) {
        return [:]
      }
      try {
        def parsed = new JsonSlurper().parseText(text)
        return (parsed instanceof Map) ? (Map) parsed : [value: parsed]
      } catch (Throwable t) {
        logger.warn(
          'parseJsonBody: invalid JSON ({} chars); exceptionType={}',
          total,
          t?.class?.name ?: 'unknown'
        )
        return [
          __aiassistantInvalidJson      : true,
          __aiassistantInvalidJsonDetail: 'Request body is not valid JSON.'
        ]
      }
    } catch (Throwable t) {
      logger.warn(
        'parseJsonBody: I/O or unexpected read failure; exceptionType={}',
        t?.class?.name ?: 'unknown'
      )
      return [
        __aiassistantInvalidJson      : true,
        __aiassistantInvalidJsonDetail: 'Could not read request body as JSON.'
      ]
    }
  }
}
