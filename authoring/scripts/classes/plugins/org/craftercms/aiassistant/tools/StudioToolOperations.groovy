package plugins.org.craftercms.aiassistant.tools

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts

import org.craftercms.studio.api.v2.event.site.SyncFromRepoEvent
import org.dom4j.Document
import org.dom4j.DocumentException
import org.dom4j.Element
import org.dom4j.io.SAXReader
import org.opensearch.client.opensearch.core.SearchRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.support.WebApplicationContextUtils

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Collections
import java.util.Base64
import java.util.List
import java.util.Locale
import java.util.Set
import java.util.TimeZone

import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Studio-facing helper operations used by AI tools.
 * Keeps bean lookup + Studio API access out of orchestration classes.
 * <p><strong>Crafter Studio {@code support/4.x}</strong> (<a href="https://github.com/craftercms/studio/tree/support/4.x">studio support/4.x</a>):
 * bean {@code contentService} (v2 {@code org.craftercms.studio.api.v2.service.content.ContentService}) supports reads such as
 * {@code getContentByCommitId(siteId, path, commitId)} (use {@code HEAD} for sandbox tip), {@code getContentVersionHistory(siteId, path)}, etc.
 * <strong>That interface has no {@code write} on 4.x</strong> — repository writes (including all {@code .xml}) must use v1 bean
 * {@code cstudioContentService}. For {@code /site/...} paths use 8-arg {@code writeContent} (form/asset {@code processContent} pipeline in
 * {@code ContentServiceImpl#doWriteContent} on {@code support/4.x}) plus {@code notifyContentEvent} and {@link org.craftercms.studio.api.v2.event.site.SyncFromRepoEvent}.
 * Do <strong>not</strong> use {@code writeContentAndNotify} for {@code /site/...}: it delegates to 3-arg {@code writeContent}, which skips {@code doWriteContent}
 * (git commits but item/sidebar state may not match Studio UI). Outside {@code /site/}, prefer {@code writeContentAndNotify} when available.
 * (Newer Studio branches may add v2 {@code write}; do not assume it here.)
 * Publish: {@code cstudioDeploymentService} ({@link org.craftercms.studio.api.v1.service.deployment.DeploymentService})
 * {@code deploy(site, environment, paths, scheduledDate, approver, submissionComment, scheduleDateNow)}.</p>
 * <p>Tools-loop / CMS tool callbacks run on Reactor/HTTP client worker threads where {@link SecurityContextHolder} is empty.
 * Studio resolves the current user from that holder ({@code SecurityServiceImpl#getCurrentUser}), so we install a
 * <strong>copy</strong> of the servlet thread's context around bean calls.</p>
 */
class StudioToolOperations {
  private static final Logger log = LoggerFactory.getLogger(StudioToolOperations.class)
  private static volatile boolean LOGGED_MISSING_SECURITY_CONTEXT = false

  /**
   * Cached {@code data:image/png;base64,...} string aligned with studio-ui {@code generatePlaceholderImageDataUrl}
   * (gray canvas + “Sample Image”) — same shape Experience Builder uses for required empty image-picker fields.
   */
  private static volatile String XB_REQUIRED_EMPTY_IMAGE_DATA_URL_CACHE

  /** Git ref for “current” sandbox content when using {@code getContentByCommitId}. */
  static final String CONTENT_REF_HEAD = 'HEAD'

  /**
   * Removes characters that are illegal in XML 1.0 (e.g. U+0000 NUL) or that break Xerces when lone.
   * LLM outputs occasionally embed NULs or other C0 controls; Studio's {@code processContent} pipeline parses
   * the document with dom4j/SAX and fails with {@code SAXParseException} otherwise.
   * <p>Allowed: TAB ({@code #x9}), LF ({@code #xA}), CR ({@code #xD}), {@code #x20}-{@code #xD7FF},
   * {@code #xE000}-{@code #xFFFD}, supplementary planes {@code #x10000}-{@code #x10FFFF}.</p>
   */
  private static String sanitizeUtf8BodyForXml10(String input) {
    if (input == null) return null
    StringBuilder sb = new StringBuilder(input.length())
    for (int i = 0; i < input.length(); ) {
      int cp = input.codePointAt(i)
      i += Character.charCount(cp)
      if (cp == 0x9 || cp == 0xA || cp == 0xD) {
        sb.appendCodePoint(cp)
      } else if (cp >= 0x20 && cp <= 0xD7FF) {
        sb.appendCodePoint(cp)
      } else if (cp >= 0xE000 && cp <= 0xFFFD) {
        sb.appendCodePoint(cp)
      } else if (cp >= 0x10000 && cp <= 0x10FFFF) {
        sb.appendCodePoint(cp)
      }
      // else drop: NUL, other C0 controls, lone surrogates, U+FFFE/U+FFFF
    }
    return sb.toString()
  }

  private static boolean isLikelyXmlRepositoryPath(String fullPath) {
    if (!fullPath) return false
    fullPath.toLowerCase(Locale.ROOT).endsWith('.xml')
  }

  /**
   * Fixes common LLM misspellings in Crafter node-selector {@code <item>} markup before SAX parse.
   * A frequent failure mode is {@code </disableFlattenening>} instead of {@code </disableFlattening>},
   * which makes the entire {@code contentXml} non-well-formed and rejects {@link #writeContent}.
   */
  private static String normalizeCommonLlmXmlTagTypos(String xml) {
    if (xml == null) {
      return null
    }
    String s = xml.toString()
    s = s.replace('</disableFlattenening>', '</disableFlattening>')
    s = s.replace('<disableFlattenening>', '<disableFlattening>')
    s = s.replace('<disableFlattenening/>', '<disableFlattening/>')
    s = s.replace('<disableFlattenening ', '<disableFlattening ')
    return s
  }

  private static SAXReader newHardenedSaxReader() {
    SAXReader reader = new SAXReader()
    reader.setValidation(false)
    try {
      reader.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://xml.org/sax/features/external-general-entities', false)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
    } catch (Throwable ignored) {
    }
    reader
  }

  /**
   * Parses {@code xmlUtf8} with dom4j (same family Studio uses in the content pipeline) so we fail fast
   * with a clear tool error instead of a partial Studio pipeline failure after IO.
   * Skipped for non-{@code .xml} paths (e.g. {@code .ftl}) where the body is not XML.
   */
  private static void assertWellFormedUtf8Xml(String pathLabel, String xmlUtf8) {
    if (xmlUtf8 == null || !xmlUtf8.toString().trim()) {
      throw new IllegalArgumentException(
        "contentXml is empty or whitespace-only for path '${pathLabel}'. Refusing to write an empty .xml file " +
          '(Engine/Studio fail with Premature end of file). Re-fetch with GetContent or update_content, then WriteContent the full document.'
      )
    }
    try {
      newHardenedSaxReader().read(new StringReader(xmlUtf8))
    } catch (DocumentException e) {
      throw new IllegalArgumentException("contentXml is not well-formed XML for path '${pathLabel}': ${e.message}", e)
    }
  }

  /**
   * When reading {@code .xml} from git, attach {@code xmlWellFormed} / {@code xmlParseError} / {@code xmlRepairReminder}
   * so the LLM can fix structure on the next {@link #writeContent} call.
   */
  private static void attachXmlReadDiagnostics(String normalizedPath, String xmlBody, Map sink) {
    if (!sink || !isLikelyXmlRepositoryPath(normalizedPath) || xmlBody == null) return
    if (!xmlBody.toString().trim()) {
      sink.put('xmlWellFormed', false)
      sink.put('xmlParseError', 'Empty or whitespace-only repository file body')
      sink.put('xmlRepairReminder', ToolPrompts.XML_REPAIR_REMINDER_AFTER_BAD_READ)
      log.warn('attachXmlReadDiagnostics: path {} has empty body', normalizedPath)
      return
    }
    try {
      newHardenedSaxReader().read(new StringReader(xmlBody))
      sink.put('xmlWellFormed', true)
    } catch (DocumentException e) {
      String msg = e.message ?: e.toString()
      sink.put('xmlWellFormed', false)
      sink.put('xmlParseError', msg)
      sink.put('xmlRepairReminder', ToolPrompts.XML_REPAIR_REMINDER_AFTER_BAD_READ)
      log.warn('attachXmlReadDiagnostics: path {} failed XML parse on read: {}', normalizedPath, msg)
    }
  }

  /**
   * Decodes only {@code %HH} percent-escapes as UTF-8 bytes. Does not treat {@code +} as space
   * (unlike {@link URLDecoder#decode(String, String)}), so Experience Builder {@code crafterPreview} tickets keep
   * literal plus signs — matching browser {@code curl -b} behavior.
   */
  private static String decodePercentEscapesUtf8PreservePlus(String s) {
    if (s == null || s.isEmpty()) return s
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, s.length()))
    for (int i = 0; i < s.length();) {
      char c = s.charAt(i)
      if (c == '%' && i + 2 < s.length()) {
        int d1 = Character.digit(s.charAt(i + 1), 16)
        int d2 = Character.digit(s.charAt(i + 2), 16)
        if (d1 >= 0 && d2 >= 0) {
          out.write((d1 << 4) | d2)
          i += 3
          continue
        }
      }
      out.write(s.substring(i, i + 1).getBytes(StandardCharsets.UTF_8))
      i++
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8)
  }

  /**
   * Reads {@code crafterPreview} from the servlet request: first {@code aiassistant.previewToken} attribute
   * (POST body from the UI), then the {@code crafterPreview} cookie. HttpOnly cookies are invisible to
   * {@code document.cookie} but are still sent on same-origin POSTs and appear here.
   */
  static String readCrafterPreviewTokenFromServletRequest(def request) {
    if (!request) return ''
    try {
      def a = request.getAttribute('aiassistant.previewToken')
      if (a != null) {
        def s = a.toString()?.trim()
        if (s) return s
      }
      def legacyPreview = request.getAttribute('crafterq.previewToken')
      if (legacyPreview != null) {
        def s2 = legacyPreview.toString()?.trim()
        if (s2) return s2
      }
    } catch (Throwable ignored) {}
    try {
      def cookies = request.getCookies()
      if (cookies) {
        for (def c : cookies) {
          if (c?.name && 'crafterPreview'.equalsIgnoreCase(c.name as String)) {
            def v = c.value?.toString()?.trim()
            if (v) {
              try {
                return decodePercentEscapesUtf8PreservePlus(v)
              } catch (Throwable ignored2) {
                return v
              }
            }
          }
        }
      }
    } catch (Throwable ignored) {}
    try {
      def raw = request?.getHeader('Cookie')?.toString()
      if (raw?.trim()) {
        for (String part : raw.split(';')) {
          def p = part.trim()
          if (!p) continue
          int eq = p.indexOf('=')
          if (eq <= 0) continue
          def name = p.substring(0, eq).trim()
          if (!name || !'crafterPreview'.equalsIgnoreCase(name)) continue
          def v = eq + 1 < p.length() ? p.substring(eq + 1).trim() : ''
          if (v.startsWith('"') && v.endsWith('"') && v.length() >= 2) {
            v = v.substring(1, v.length() - 1).replace('\\"', '"').replace('\\\\', '\\')
          }
          if (v) {
            try {
              return decodePercentEscapesUtf8PreservePlus(v)
            } catch (Throwable ignored2) {
              return v
            }
          }
        }
      }
    } catch (Throwable ignored) {}
    return ''
  }


  /**
   * RFC 6265-style attribute value: quote when needed for {@code Cookie} request header.
   */
  private static String formatCookieAttributeValue(String val) {
    if (val == null) return ''
    boolean needQuotes = false
    for (int i = 0; i < val.length(); i++) {
      char c = val.charAt(i)
      int cp = (int) c
      if (c == ';' || c == '"' || c == '\\' || c == '#' || Character.isWhitespace(c) || cp < 0x21 || cp == 0x7f) {
        needQuotes = true
        break
      }
    }
    if (!needQuotes) return val
    return '"' + val.replace('\\', '\\\\').replace('"', '\\"') + '"'
  }

  /**
   * Studio session cookies on the same host can be sent with the plugin POST; forwarding {@code JSESSIONID} to
   * Engine often makes Engine treat the request as an invalid app session and return **401** before validating
   * {@code crafterPreview}. Strip those; keep preview/site and optional JVM-configured names.
   */
  private static boolean stripCookieNameForPreviewEngineFetch(String cookieName) {
    if (!cookieName) return false
    String n = cookieName.trim().toLowerCase(Locale.ROOT)
    if ('jsessionid'.equals(n)) return true
    if ('refresh_token'.equals(n)) return true
    try {
      def extra = System.getProperty('aiassistant.preview.fetch.stripCookieNames')?.toString()?.trim()
      if (extra) {
        for (String part : extra.split(',')) {
          def p = part.trim().toLowerCase(Locale.ROOT)
          if (p && p == n) return true
        }
      }
    } catch (Throwable ignored) {}
    return false
  }

  /**
   * Cookie header for Engine preview GET: start from the frozen {@code Cookie} snapshot (servlet thread), drop
   * Studio-only session cookies, strip {@code crafterPreview} / {@code crafterSite}, then append resolved preview token
   * and site. Engine also accepts {@code crafterPreview} as a query param — see {@link #fetchPreviewRenderedHtml}.
   */
  private String buildPreviewFetchCookieHeader(String crafterPreviewTokenResolved, String siteIdForCookie) {
    def tok = (crafterPreviewTokenResolved ?: '').toString().trim()
    if (!tok) return ''
    String site = (siteIdForCookie ?: '').toString().trim()
    String raw = (crafterqFrozenCookieHeader ?: '').trim()
    if (!raw) {
      try {
        raw = request?.getHeader('Cookie')?.toString()?.trim() ?: ''
      } catch (Throwable ignored) {}
    }
    List<String> segments = []
    if (raw) {
      for (String part : raw.split(';')) {
        def p = part.trim()
        if (!p) continue
        int eq = p.indexOf('=')
        String name = eq > 0 ? p.substring(0, eq).trim() : ''
        if (name && stripCookieNameForPreviewEngineFetch(name)) continue
        if (name && 'crafterPreview'.equalsIgnoreCase(name)) continue
        if (site && 'crafterSite'.equalsIgnoreCase(name)) continue
        segments.add(p)
      }
    }
    segments.add('crafterPreview=' + formatCookieAttributeValue(tok))
    if (site) {
      segments.add('crafterSite=' + formatCookieAttributeValue(site))
    }
    return segments.join('; ')
  }

  /**
   * Studio XB address bar often looks like {@code …/studio/preview/#/?page=/&site=my-site}; HTTP GETs must not use
   * that URL (the fragment is never sent to the server). Rewrites to the Engine origin + {@code page} + {@code crafterSite=}.
   */
  static String rewriteStudioPreviewShellUrlForEngineFetch(String fullUrl, String siteIdFallback) {
    def u = (fullUrl ?: '').toString().trim()
    if (!u || !u.contains('#')) return u
    int hash = u.indexOf('#')
    String before = u.substring(0, hash)
    String frag = u.substring(hash + 1)
    if (!before.toLowerCase(Locale.ROOT).contains('/studio/preview')) return u
    String q = frag
    if (q.startsWith('/?')) {
      q = q.substring(2)
    } else if (q.startsWith('?')) {
      q = q.substring(1)
    } else {
      return u
    }
    Map<String, String> qp = parseAmpQueryString(q)
    String pageVal = (qp.page ?: qp.path ?: '/').toString().trim()
    if (!pageVal.startsWith('/')) pageVal = '/' + pageVal
    String siteVal = (qp.site ?: qp.crafterSite ?: siteIdFallback ?: '').toString().trim()
    try {
      URI bu = new URI(before)
      String scheme = bu.scheme ?: 'http'
      String host = bu.host
      if (!host) return u
      int p = bu.port
      boolean defP = p < 0 ||
        (scheme.equalsIgnoreCase('https') && p == 443) ||
        (scheme.equalsIgnoreCase('http') && p == 80)
      String origin = scheme + '://' + host + (defP ? '' : ":${p}")
      if (!siteVal) return u
      String encSite = URLEncoder.encode(siteVal, 'UTF-8')
      if (pageVal == '/' || pageVal.isEmpty()) {
        return "${origin}/?crafterSite=${encSite}"
      }
      return "${origin}${pageVal}?crafterSite=${encSite}"
    } catch (Throwable ignored) {
      return u
    }
  }

  private static Map<String, String> parseAmpQueryString(String q) {
    Map<String, String> m = [:]
    if (!q) return m
    for (String part : q.split('&')) {
      int eq = part.indexOf('=')
      if (eq < 0) continue
      String k = part.substring(0, eq).trim()
      String v = eq + 1 < part.length() ? part.substring(eq + 1) : ''
      try {
        k = URLDecoder.decode(k, 'UTF-8')
        v = URLDecoder.decode(v, 'UTF-8')
      } catch (Throwable ignored) {}
      if (k) m[k] = v
    }
    return m
  }

  /**
   * Removes query parameters matching {@code paramNames} (case-insensitive keys). Preserves URL fragment ({@code #…})
   * and only rewrites the query segment before it.
   */
  private static String removeQueryParamsCaseInsensitive(String url, Collection<String> paramNames) {
    def full = (url ?: '').toString()
    if (!full) return full
    int hash = full.indexOf('#')
    String beforeHash = hash >= 0 ? full.substring(0, hash) : full
    String frag = hash >= 0 ? full.substring(hash) : ''
    int q = beforeHash.indexOf('?')
    if (q < 0) return full
    String base = beforeHash.substring(0, q)
    String query = beforeHash.substring(q + 1)
    if (!query) return base + frag
    Set<String> drop = [] as Set
    for (String n : paramNames) {
      if (n) drop.add(n.toLowerCase(Locale.ROOT))
    }
    List<String> kept = []
    for (String part : query.split('&')) {
      int eq = part.indexOf('=')
      String key = (eq >= 0 ? part.substring(0, eq) : part).trim()
      String keyLc = key.toLowerCase(Locale.ROOT)
      if (!drop.contains(keyLc)) {
        kept.add(part)
      }
    }
    if (kept.isEmpty()) {
      return base + frag
    }
    return base + '?' + String.join('&', kept) + frag
  }

  private final def request
  private final def applicationContext
  /** Plugin script params (may include siteId from query string). */
  private final def params
  /** Copy of {@link org.springframework.security.core.context.SecurityContext} from the Studio HTTP request thread. */
  private final def securityContextForTools
  /**
   * Studio Experience Builder preview cookie value ({@code crafterPreview}), set on the HTTP request as attribute
   * {@code aiassistant.previewToken} by the chat/stream REST scripts when the UI POSTs it.
   */
  private final String crafterqPreviewToken
  /**
   * Snapshot of {@code Cookie} from the Studio chat/stream servlet request at {@code StudioToolOperations} construction
   * (servlet thread). Tool callbacks run on worker threads where {@code request.getHeader("Cookie")} can be empty; Engine
   * preview GET still needs forwarded non-session cookies (e.g. other Studio cookies), plus {@code crafterPreview} / {@code crafterSite}.
   */
  private final String crafterqFrozenCookieHeader
  /** Resolved once: v2 {@code contentService} (reads/history only on studio {@code support/4.x} — no {@code write} there). */
  final Object contentServiceBean
  /** Resolved once for support/4.x direct calls. */
  final Object configurationServiceBean
  /** v1 deployment service bean {@code cstudioDeploymentService}. */
  final Object deploymentServiceBean
  /** v1 {@code org.craftercms.studio.api.v1.service.content.ContentService} (bean {@code cstudioContentService}) for repository writes. */
  final Object cstudioContentServiceBean
  /**
   * Optional v1 {@code org.craftercms.studio.api.v1.service.content.ContentTypeService} — bean {@code cstudioContentTypeService}
   * (or {@code contentTypeService}) when Studio registers it. Used by {@link #listStudioContentTypes}; may be null in some deployments.
   */
  final Object contentTypeServiceBean

  StudioToolOperations(
    def request,
    def applicationContext,
    def params = null,
    def securityContextForTools = null
  ) {
    this.request = request
    this.applicationContext = applicationContext
    this.params = params
    this.securityContextForTools = securityContextForTools
    String frozenCookie = ''
    try {
      frozenCookie = request?.getHeader('Cookie')?.toString()?.trim() ?: ''
    } catch (Throwable ignored) {}
    this.crafterqFrozenCookieHeader = frozenCookie
    this.crafterqPreviewToken = readCrafterPreviewTokenFromServletRequest(request)
    this.contentServiceBean = resolveRequiredBean('contentService',
      'Studio bean contentService not found. CrafterQ expects the same in-process content service Crafter Studio registers (support/4.x).')
    this.configurationServiceBean = resolveRequiredBean('configurationService',
      'Studio configurationService bean not found (configurationService). CrafterQ tools use the Studio JVM only.')
    this.deploymentServiceBean = resolveRequiredBean('cstudioDeploymentService',
      'Studio cstudioDeploymentService bean not found (DeploymentService).')
    this.cstudioContentServiceBean = resolveRequiredBean('cstudioContentService',
      'Studio cstudioContentService bean not found (v1 ContentService). CrafterQ writeContent uses this bean.')
    Object cts = null
    try {
      cts = applicationContext?.get('cstudioContentTypeService')
    } catch (Throwable ignored) {}
    if (cts == null) {
      try {
        cts = applicationContext?.get('contentTypeService')
      } catch (Throwable ignored2) {}
    }
    this.contentTypeServiceBean = cts
  }

  /**
   * Exposed for site-authored Groovy under {@code config/studio/scripts/aiassistant/user-tools/} (see {@code StudioAiUserSiteTools}).
   * Prefer {@link StudioToolOperations} methods for repository work; use the context only when you need additional Spring beans.
   */
  Object crafterqStudioApplicationContext() {
    applicationContext
  }

  private Object resolveRequiredBean(String name, String errorMessage) {
    def s = null
    try {
      s = applicationContext?.get(name)
    } catch (Throwable ignored) {}
    if (s == null) {
      throw new IllegalStateException(errorMessage)
    }
    return s
  }

  /** Username of the authenticated Studio user (same context as permission checks). */
  private static String currentAuthenticatedUsername() {
    Authentication auth = SecurityContextHolder.getContext()?.getAuthentication()
    if (auth == null || !auth.isAuthenticated()) {
      throw new IllegalStateException('No authenticated Studio user; publish_content requires a logged-in Studio user.')
    }
    String name = auth.getName()?.toString()?.trim()
    if (!name) {
      throw new IllegalStateException('Authenticated user has no principal name; cannot set deployment approver.')
    }
    return name
  }

  /**
   * Default parallel workers for {@code TranslateContentBatch} when the model does not pass {@code maxConcurrency}.
   * Set per request from stream POST {@code translateBatchConcurrency} (ui.xml per-agent); clamped {@code 1..64};
   * falls back to {@code 25}.
   */
  int resolveTranslateBatchDefaultMaxConcurrency() {
    try {
      def v = request?.getAttribute('aiassistant.translateBatchConcurrency')
      if (v == null) {
        v = request?.getAttribute('crafterq.translateBatchConcurrency')
      }
      if (v instanceof Number) {
        int n = ((Number) v).intValue()
        return Math.max(1, Math.min(64, n))
      }
      if (v != null) {
        int n = Integer.parseInt(v.toString().trim())
        return Math.max(1, Math.min(64, n))
      }
    } catch (Throwable ignored) {}
    return 25
  }

  /** Crafter permission checks use authenticated user from {@link SecurityContextHolder}. */
  private <T> T withStudioRequestSecurity(Closure<T> work) {
    if (securityContextForTools == null) {
      if (!LOGGED_MISSING_SECURITY_CONTEXT) {
        LOGGED_MISSING_SECURITY_CONTEXT = true
        log.warn(
          'StudioToolOperations: SecurityContext was not captured on the HTTP thread; tools-loop / CMS tool callbacks may fail with SubjectNotFoundException. Ensure AiOrchestration builds the chat client on an authenticated servlet thread (or anonymous is not treated as authenticated in your Spring Security setup).'
        )
      }
      return work.call()
    }
    def previous = SecurityContextHolder.getContext()
    try {
      SecurityContextHolder.setContext(securityContextForTools)
      return work.call()
    } finally {
      SecurityContextHolder.setContext(previous)
    }
  }

  /** LLMs often pass siteId "default". Prefer request site from body/query/params. */
  String resolveEffectiveSiteId(String fromTool) {
    def tool = (fromTool ?: '').toString().trim()
    def reqSite = ''
    try {
      reqSite = request?.getAttribute('aiassistant.siteId')?.toString()?.trim() ?: ''
      if (!reqSite) reqSite = request?.getAttribute('crafterq.siteId')?.toString()?.trim() ?: ''
      if (!reqSite) reqSite = request?.getParameter('siteId')?.toString()?.trim() ?: ''
      if (!reqSite) reqSite = request?.getParameter('crafterSite')?.toString()?.trim() ?: ''
      if (!reqSite && params != null) {
        try {
          reqSite = params['siteId']?.toString()?.trim() ?: ''
        } catch (Throwable e) {
          try {
            reqSite = params.siteId?.toString()?.trim() ?: ''
          } catch (Throwable e2) {}
        }
      }
    } catch (Throwable ignored) {}

    if (tool && !tool.equalsIgnoreCase('default')) return tool
    if (reqSite) return reqSite
    return tool
  }

  /**
   * Reads an entire stream as UTF-8. Parameter is {@link Object} so Groovy resolves the call for JGit
   * {@code ObjectStream.SmallStream} and other {@link InputStream} implementations returned by Studio resources.
   */
  private static String slurpInputStreamUtf8(Object streamLike) {
    if (streamLike == null) return null
    InputStream is = streamLike as InputStream
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(32, is.available() > 0 ? is.available() : 8192))
      byte[] buf = new byte[8192]
      int n
      while ((n = is.read(buf)) != -1) {
        baos.write(buf, 0, n)
      }
      return new String(baos.toByteArray(), StandardCharsets.UTF_8)
    } finally {
      try {
        is.close()
      } catch (Throwable ignored) {}
    }
  }

  private static String extractFirstTagValue(String xml, String tagName) {
    if (!xml || !tagName) return null
    try {
      def re = ~/(?s)<${java.util.regex.Pattern.quote(tagName)}>(.*?)<\/${java.util.regex.Pattern.quote(tagName)}>/
      def m = re.matcher(xml)
      if (m.find()) {
        return m.group(1)?.toString()?.trim()
      }
    } catch (Throwable ignored) {}
    return null
  }

  /**
   * For `/site/.../*.xml` items, surfaces the first {@code <content-type>} value so the model does not guess
   * (e.g. {@code /page/page_generic} after reading a {@code /site/components/...} file).
   */
  private static void attachSiteItemContentTypeFromXml(String normalizedPath, String xmlBody, Map sink) {
    if (!sink || !normalizedPath || xmlBody == null) return
    String p = normalizedPath.toString()
    if (!p.startsWith('/site/') || !p.toLowerCase(Locale.ROOT).endsWith('.xml')) return
    String ct = extractFirstTagValue(xmlBody.toString(), 'content-type')
    if (!ct) return
    ct = ct.trim()
    if (!ct.startsWith('/')) return
    sink.put('contentTypeIdFromXml', ct)
    sink.put(
      'contentTypeCatalogHint',
      "This file's <content-type> is **${ct}**. For **GetContentTypeFormDefinition** targeting **this same repository file**, pass **contentTypeId='${ct}'** or **contentPath='${p}'**. Do **not** substitute **/page/page_generic** unless **${ct}** is literally **/page/page_generic**."
    )
  }

  String resolveTemplatePathFromContent(String siteId, String contentPath) {
    if (!siteId || !contentPath) return null
    def content = getContent(siteId, contentPath)
    def xml = content?.contentXml?.toString()
    def tpl = extractFirstTagValue(xml, 'display-template')
    return (tpl && tpl.startsWith('/')) ? tpl : null
  }

  /**
   * Reads file bytes at {@code path} for {@code commitOrRef} (default {@code HEAD}).
   * Use a concrete Git commit id only when inspecting history; normal editing uses {@code HEAD}.
   */
  Map getContent(String siteId, String path, String commitOrRef = null) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def normalized = normalizeLeadingSlash(path, 'path')
      def ref = (commitOrRef ?: '').toString().trim()
      if (!ref) ref = CONTENT_REF_HEAD
      String xml
      try {
        def optional = contentServiceBean.getContentByCommitId(siteId, normalized, ref)
        if (optional == null || !optional.isPresent()) {
          throw new IllegalStateException(
            "No content at site '${siteId}' path '${normalized}' for ref '${ref}' (getContentByCommitId empty)."
          )
        }
        def resource = optional.get()
        xml = slurpInputStreamUtf8(resource.getInputStream())
      } catch (IllegalStateException e) {
        throw e
      } catch (Throwable t) {
        log.error('getContent failed for site {} path {} ref {}', siteId, normalized, ref, t)
        throw new IllegalStateException("contentService.getContentByCommitId failed for path '${normalized}' ref '${ref}': ${t.message}", t)
      }
      if (xml == null || !xml.toString().trim()) {
        throw new IllegalStateException(
          "No content returned for site '${siteId}' path '${normalized}' ref '${ref}'."
        )
      }
      Map out = [siteId: siteId, path: normalized, commitRef: ref, contentXml: xml]
      attachXmlReadDiagnostics(normalized, xml, out)
      attachSiteItemContentTypeFromXml(normalized, xml, out)
      out
    }
  }

  /**
   * Lists Studio **content types** for the site (ids, labels, thumbnails) via v1 {@code ContentTypeService} when available.
   * <p>For **create** flows, call this before guessing types from OpenSearch page hits. Optional {@code contentPath}
   * scopes to types **allowed** under that folder (parent of the path when it points at an {@code index.xml}).</p>
   *
   * @param searchable when {@code true}, some Studio builds return only searchable types from {@code getAllContentTypes}
   * @param contentPath optional repository path under {@code /site/...} used with {@code getAllowedContentTypesForPath}
   */
  Map listStudioContentTypes(String siteId, boolean searchable, String contentPath) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      if (contentTypeServiceBean == null) {
        return [
          ok           : false,
          siteId       : siteId,
          contentTypes : [],
          message      :
            'Studio ContentTypeService bean not found (expected cstudioContentTypeService). Use GetContentTypeFormDefinition with contentPath or exact contentTypeId.',
          hint         :
            'If this persists, ask the platform team to confirm the ContentTypeService Spring bean id for this Studio line.'
        ]
      }
      String mode = 'all'
      List raw = []
      String rel = ''
      String cp = (contentPath ?: '').toString().trim()
      if (cp) {
        rel = studioRelativePathForContentTypeListing(cp)
        try {
          if (contentTypeServiceBean.metaClass.respondsTo(contentTypeServiceBean, 'getAllowedContentTypesForPath', String, String)) {
            def allowed = contentTypeServiceBean.getAllowedContentTypesForPath(siteId, rel)
            raw = (allowed instanceof List) ? (List) allowed : []
            mode = 'allowedForPath'
          }
        } catch (Throwable t) {
          log.warn('listStudioContentTypes getAllowedContentTypesForPath site={} rel={}: {}', siteId, rel, t.message)
          raw = []
          mode = 'allowedForPath_error'
        }
        if (raw.isEmpty()) {
          try {
            def all = contentTypeServiceBean.getAllContentTypes(siteId, searchable)
            raw = (all instanceof List) ? (List) all : []
            mode = 'all_fallback_no_allowed'
          } catch (Throwable t2) {
            throw new IllegalStateException("listStudioContentTypes getAllContentTypes failed: ${t2.message}", t2)
          }
        }
      } else {
        try {
          def all = contentTypeServiceBean.getAllContentTypes(siteId, searchable)
          raw = (all instanceof List) ? (List) all : []
          mode = 'all'
        } catch (Throwable t) {
          throw new IllegalStateException("listStudioContentTypes getAllContentTypes failed: ${t.message}", t)
        }
      }
      def rows = []
      for (Object ct : raw) {
        def row = briefContentTypeConfigRow(ct)
        if (!row.isEmpty()) {
          rows.add(row)
        }
      }
      // `/page/...` before `/component/...` so page kinds are easier to scan; no hard-coded content-type keywords here.
      rows.sort { Map a, Map b ->
        String na = (a.name ?: '').toString()
        String nb = (b.name ?: '').toString()
        int pa = na.startsWith('/page/') ? 0 : (na.startsWith('/component/') ? 1 : 2)
        int pb = nb.startsWith('/page/') ? 0 : (nb.startsWith('/component/') ? 1 : 2)
        int c = pa <=> pb
        if (c != 0) {
          return c
        }
        return na <=> nb
      }
      return [
        ok           : true,
        siteId       : siteId,
        mode         : mode,
        searchable   : searchable,
        contentPath  : cp,
        relativePath : rel,
        count        : rows.size(),
        contentTypes : rows,
        hint         :
          '**Response `mode`:** **`all`** = full-site catalog (**`contentPath` was omitted** — **preferred** first call so authors see every type). **`allowedForPath`** / **`all_fallback_no_allowed`** = **`contentPath` was set**; Studio scoped types to that path’s parent folder (subset or fallback to all). **Do not** default the **first** **ListStudioContentTypes** call to **Current preview** `contentPath` unless you **only** need folder-scoped types — hub **`index.xml`** paths often confuse the subset vs the full catalog. After listing, you may **paste a short markdown table** of **`label`** + **`name`** in chat so the author sees what Studio offers. Each row has **name** (repository content-type id) and **label** (Studio UI). **Exact match only** (see system **Exact catalog match beats guessing**): normalize the author’s **type phrase** and each row’s **`label`**, **`name`**, and **`name`** tail after the final **`/`** (trim, Unicode lowercase, collapse spaces, **`/`** → space in **`label`** and phrase; **`-`**/**`_`** → space in **`name`** / tail). Use **`contentTypeId` = that row’s `name`** **only** when **exactly one** row **equals** the phrase — **do not** pick a catch-all type (e.g. **/page/page_generic**) when that single match exists. **Real sites:** a **section hub** `…/foo/index.xml` may still show `<content-type>` **/page/page_generic** (or another shell) while **child** `…/foo/<slug>/index.xml` items use a **narrower** `/page/…` — do **not** treat the hub’s type as the **new child** type when they differ. For **new** items, call **GetContentTypeFormDefinition** with the **resolved** **`contentTypeId`**; do **not** pass **contentPath** of that hub `index.xml` when creating a **different** type. For XML field shape, **one** **GetContent** on an **existing sibling** of the **same** `name` type. **`/site/components/…`** items are **`/component/…`** — use **GetContent**’s **`contentTypeIdFromXml`** for the next form-def on **that** file, not **`/page/page_generic`**. **Do not** call **GetContentTypeFormDefinition** for many unrelated **`/component/...`** types when the task is **one** new item. After **GetContentTypeFormDefinition** for a **create** target, **do not** call **ListPagesAndComponents** at large **size** — use **one** **GetContent** on a **sibling** of the **same** type instead. Do not use **ListPagesAndComponents** to discover content types.'
      ]
    }
  }

  /**
   * Studio {@code getAllowedContentTypesForPath} expects a path relative to site sandbox root (e.g. {@code website/blog}).
   */
  private static String studioRelativePathForContentTypeListing(String repoPath) {
    String p = (repoPath ?: '').trim()
    if (!p.startsWith('/')) {
      p = '/' + p
    }
    if (p.startsWith('/site/')) {
      p = p.substring('/site/'.length())
    }
    if (p.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      int s = p.lastIndexOf('/')
      if (s > 0) {
        p = p.substring(0, s)
      } else {
        p = ''
      }
    }
    p = p.replaceAll('/+$', '')
    return p
  }

  private static Map briefContentTypeConfigRow(Object ct) {
    Map m = new LinkedHashMap<>()
    if (ct == null) {
      return m
    }
    try {
      def n = ct.name
      if (n) m.name = n.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def l = ct.label
      if (l) m.label = l.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def u = ct.uri
      if (u) m.uri = u.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def ty = ct.type
      if (ty) m.type = ty.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def f = ct.form
      if (f) m.form = f.toString().trim()
    } catch (Throwable ignored) {}
    try {
      def im = ct.imageThumbnail
      if (im) m.imageThumbnail = im.toString().trim()
    } catch (Throwable ignored) {}
    return m
  }

  Map getContentTypeFormDefinition(String siteId, String contentTypeId) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def normalized = (contentTypeId ?: '').toString().trim()
      if (!normalized) throw new IllegalArgumentException('Missing required parameter: contentTypeId')
      if (!normalized.startsWith('/')) normalized = "/${normalized}"
      def cfgPath = "/content-types${normalized}/form-definition.xml"
      String xml

      try {
        xml = configurationServiceBean.getConfigurationAsString(siteId, 'studio', cfgPath, '')
      } catch (Throwable t) {
        log.error('getContentTypeFormDefinition failed for site {} path {}', siteId, cfgPath, t)
        throw new IllegalStateException("configurationService.getConfigurationAsString failed for '${cfgPath}': ${t.message}", t)
      }

      if (xml == null || !xml.toString().trim()) {
        throw new IllegalStateException(
          "No form definition returned for site '${siteId}' content type '${normalized}' at '${cfgPath}'."
        )
      }
      Map out = [siteId: siteId, contentTypeId: normalized, path: cfgPath, formDefinitionXml: xml]
      attachXmlReadDiagnostics(cfgPath, xml, out)
      out
    }
  }

  private static String extractContentTypeIdFromItemXml(String xmlUtf8) {
    if (!xmlUtf8) {
      return null
    }
    def m = (xmlUtf8 =~ /(?is)<(?:[A-Za-z0-9_.-]+:)?content-type\s*>\s*([^<]+?)\s*<\/(?:[A-Za-z0-9_.-]+:)?content-type\s*>/)
    if (m.find()) {
      String s = m.group(1)?.trim()
      return s ? s : null
    }
    null
  }

  private static boolean formFieldImagePickerReadOnly(Element fieldEl) {
    if (fieldEl == null) {
      return false
    }
    try {
      Element props = fieldEl.element('properties')
      if (!props) {
        return false
      }
      for (Iterator it = props.elementIterator('property'); it.hasNext();) {
        Element p = (Element) it.next()
        if ('readonly'.equalsIgnoreCase(p.elementTextTrim('name') ?: '')) {
          return 'true'.equalsIgnoreCase(p.elementTextTrim('value') ?: '')
        }
      }
    } catch (Throwable ignored) {
    }
    false
  }

  private static boolean formFieldHasRequiredConstraint(Element fieldEl) {
    if (fieldEl == null) {
      return false
    }
    Element constraints = fieldEl.element('constraints')
    if (!constraints) {
      return false
    }
    for (Iterator it = constraints.elementIterator('constraint'); it.hasNext();) {
      Element c = (Element) it.next()
      if (!'required'.equalsIgnoreCase(c.elementTextTrim('name') ?: '')) {
        continue
      }
      String v = c.elementText('value')
      if (v == null) {
        v = ''
      }
      v = v.trim()
      if ('true'.equalsIgnoreCase(v)) {
        return true
      }
      if (v.contains('true')) {
        return true
      }
    }
    false
  }

  private static boolean formFieldCheckboxGroupReadOnly(Element fieldEl) {
    if (fieldEl == null) {
      return false
    }
    try {
      Element props = fieldEl.element('properties')
      if (!props) {
        return false
      }
      for (Iterator it = props.elementIterator('property'); it.hasNext();) {
        Element p = (Element) it.next()
        if ('readonly'.equalsIgnoreCase(p.elementTextTrim('name') ?: '')) {
          return 'true'.equalsIgnoreCase(p.elementTextTrim('value') ?: '')
        }
      }
    } catch (Throwable ignored) {
    }
    false
  }

  /** Minimum selections from {@code <constraint><name>minSize</name>…} (grouped checkboxes). */
  private static int checkboxGroupMinSizeConstraint(Element fieldEl) {
    if (fieldEl == null) {
      return 0
    }
    Element constraints = fieldEl.element('constraints')
    if (!constraints) {
      return 0
    }
    for (Iterator it = constraints.elementIterator('constraint'); it.hasNext();) {
      Element c = (Element) it.next()
      if (!'minSize'.equalsIgnoreCase(c.elementTextTrim('name') ?: '')) {
        continue
      }
      String v = c.elementTextTrim('value')
      if (!v) {
        return 0
      }
      try {
        int n = Integer.parseInt(v.trim())
        return n > 0 ? n : 0
      } catch (Throwable ignored) {
        return 0
      }
    }
    0
  }

  /**
   * Selections required so Studio validation passes: {@code max(minSize, required ? 1 : 0)}.
   */
  private static int checkboxGroupNeededSelectionCount(Element fieldEl) {
    if (fieldEl == null) {
      return 0
    }
    int minSz = checkboxGroupMinSizeConstraint(fieldEl)
    int req = formFieldHasRequiredConstraint(fieldEl) ? 1 : 0
    Math.max(minSz, req)
  }

  private static String checkboxGroupDatasourceId(Element fieldEl) {
    if (fieldEl == null) {
      return null
    }
    Element props = fieldEl.element('properties')
    if (!props) {
      return null
    }
    for (Iterator it = props.elementIterator('property'); it.hasNext();) {
      Element p = (Element) it.next()
      if (!'datasource'.equalsIgnoreCase(p.elementTextTrim('name') ?: '')) {
        continue
      }
      String raw = p.elementText('value')
      if (raw == null) {
        raw = ''
      }
      raw = raw.trim()
      if (!raw) {
        return null
      }
      raw = raw.replace('[', '').replace(']', '').replace('"', '').replace("'", '').trim()
      return raw ?: null
    }
    null
  }

  private static Element findFormDatasourceById(Element formRoot, String datasourceId) {
    if (formRoot == null || !datasourceId) {
      return null
    }
    Element dss = formRoot.element('datasources')
    if (!dss) {
      return null
    }
    for (Iterator it = dss.elementIterator('datasource'); it.hasNext();) {
      Element ds = (Element) it.next()
      if (datasourceId.equals(ds.elementTextTrim('id'))) {
        return ds
      }
    }
    null
  }

  private static String formDatasourcePropertyTrim(Element dsEl, String propName) {
    if (dsEl == null || !propName) {
      return null
    }
    Element props = dsEl.element('properties')
    if (!props) {
      return null
    }
    for (Iterator it = props.elementIterator('property'); it.hasNext();) {
      Element p = (Element) it.next()
      if (propName.equalsIgnoreCase(p.elementTextTrim('name') ?: '')) {
        String v = p.elementTextTrim('value')
        return v ?: null
      }
    }
    null
  }

  private static boolean isTaxonomyBackedDatasource(Element dsEl) {
    if (dsEl == null) {
      return false
    }
    String t = (dsEl.elementTextTrim('type') ?: '').toLowerCase(Locale.ROOT)
    t.contains('taxonomy')
  }

  /**
   * Child element name for each selected checkbox value (Engine / GraphQL convention), from datasource {@code dataType}.
   */
  private static String checkboxValueElementNameForDataType(String dataTypeRaw) {
    if (!dataTypeRaw) {
      return 'value_smv'
    }
    String dt = dataTypeRaw.trim().toLowerCase(Locale.ROOT)
    if ('string'.equals(dt)) {
      return 'value_smv'
    }
    if ('value'.equals(dt)) {
      return 'value'
    }
    if ('float'.equals(dt) || 'double'.equals(dt)) {
      return 'value_fmv'
    }
    if ('integer'.equals(dt) || 'int'.equals(dt) || 'long'.equals(dt)) {
      return 'value_imv'
    }
    if ('html'.equals(dt)) {
      return 'value_htmlmv'
    }
    if ('date'.equals(dt) || 'datetime'.equals(dt)) {
      return 'value_dtmv'
    }
    'value_smv'
  }

  private static String resolveTaxonomyRepoPathFromDatasource(Element dsEl) {
    if (dsEl == null) {
      return null
    }
    String[] keys = ['componentPath', 'repoPath', 'rootPath', 'baseRepositoryPath'] as String[]
    for (String k : keys) {
      String v = formDatasourcePropertyTrim(dsEl, k)
      if (v?.trim()) {
        return v.trim()
      }
    }
    null
  }

  private static List<String> expandSiteTaxonomyPathCandidates(String rawPath) {
    if (!rawPath?.trim()) {
      return []
    }
    String p = rawPath.trim()
    if (!p.startsWith('/')) {
      p = "/${p}"
    }
    if (!p.startsWith('/site/')) {
      return []
    }
    LinkedHashSet<String> out = new LinkedHashSet<>()
    out.add(p)
    if (!p.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      out.add(p.endsWith('/') ? "${p}index.xml" : "${p}/index.xml")
      out.add("${p}.xml")
    }
    out.toList()
  }

  private static Element findFirstDescendantByLocalName(Element root, String local) {
    if (root == null || !local) {
      return null
    }
    if (local == root.getQName().getName()) {
      return root
    }
    for (Iterator it = root.elementIterator(); it.hasNext();) {
      Element child = (Element) it.next()
      Element found = findFirstDescendantByLocalName(child, local)
      if (found != null) {
        return found
      }
    }
    null
  }

  /**
   * Parses simple taxonomy / KVP list XML: first {@code <items>} block with {@code <item><key/>…<value/>…} children.
   */
  private static List<Map> parseTaxonomyKeyLabelPairs(String taxonomyXml) {
    List<Map> pairs = []
    if (!taxonomyXml?.trim()) {
      return pairs
    }
    Document doc
    try {
      doc = newHardenedSaxReader().read(new StringReader(taxonomyXml.toString()))
    } catch (Throwable t) {
      return pairs
    }
    Element items = findFirstDescendantByLocalName(doc.getRootElement(), 'items')
    if (items == null) {
      return pairs
    }
    for (Iterator it = items.elementIterator('item'); it.hasNext();) {
      Element item = (Element) it.next()
      String key = item.elementTextTrim('key')
      if (!key) {
        continue
      }
      String label = item.elementTextTrim('value')
      if (!label) {
        label =
          item.elementTextTrim('value_s') ?:
            item.elementTextTrim('value_smv') ?:
              item.elementTextTrim('label') ?:
                key
      }
      pairs.add([key: key, label: label])
    }
    pairs
  }

  private static Set<String> existingCheckboxGroupKeys(Element fieldRoot) {
    Set<String> keys = new LinkedHashSet<>()
    if (fieldRoot == null) {
      return keys
    }
    for (Iterator it = fieldRoot.elementIterator('item'); it.hasNext();) {
      Element row = (Element) it.next()
      String k = row.elementTextTrim('key')
      if (k) {
        keys.add(k)
      }
    }
    keys
  }

  /**
   * Collects top-level {@code checkbox-group} fields that need at least one taxonomy-backed selection for save validation.
   */
  private static void collectTopLevelCheckboxGroupTaxonomyFillTargets(Element el, boolean insideRepeat, List<Map> sink) {
    if (el == null || sink == null) {
      return
    }
    boolean isField = 'field'.equals(el.getQName().getName())
    if (!isField) {
      el.elements().each { collectTopLevelCheckboxGroupTaxonomyFillTargets(it, insideRepeat, sink) }
      return
    }
    String t = el.elementTextTrim('type')
    if ('repeat'.equals(t)) {
      Element fields = el.element('fields')
      if (fields != null) {
        fields.elements().each { collectTopLevelCheckboxGroupTaxonomyFillTargets(it, true, sink) }
      }
      return
    }
    if (!insideRepeat && 'checkbox-group'.equals(t) && !formFieldCheckboxGroupReadOnly(el)) {
      int needed = checkboxGroupNeededSelectionCount(el)
      if (needed > 0) {
        String fid = el.elementTextTrim('id')
        String dsId = checkboxGroupDatasourceId(el)
        if (fid && dsId) {
          sink.add([id: fid, needed: needed, datasourceId: dsId])
        }
      }
    }
    el.elements().each { collectTopLevelCheckboxGroupTaxonomyFillTargets(it, insideRepeat, sink) }
  }

  /**
   * Best-effort read of a {@code /site/...} XML file for taxonomy / KVP lists (never throws).
   */
  private String tryReadSiteContentUtf8(String siteId, String repoPath, String ref) {
    if (!siteId || !repoPath?.trim()) {
      return null
    }
    try {
      String normalized = normalizeLeadingSlash(repoPath, 'path')
      def r = (ref ?: CONTENT_REF_HEAD).toString().trim() ?: CONTENT_REF_HEAD
      def optional = contentServiceBean.getContentByCommitId(siteId, normalized, r)
      if (optional == null || !optional.isPresent()) {
        return null
      }
      def resource = optional.get()
      String xml = slurpInputStreamUtf8(resource.getInputStream())
      return xml?.trim() ? xml.toString() : null
    } catch (Throwable t) {
      log.debug('tryReadSiteContentUtf8 failed siteId={} path={}: {}', siteId, repoPath, t.message)
      return null
    }
  }

  /**
   * Fills missing selections for required / {@code minSize} top-level {@code checkbox-group} fields whose datasource
   * type references taxonomy (e.g. {@code simple-taxonomy}), using keys/labels from the datasource’s site XML list.
   */
  private String applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded(String siteId, String normalizedRepoPath, String xmlUtf8) {
    if (!xmlUtf8 || !normalizedRepoPath?.startsWith('/site/')) {
      return xmlUtf8
    }
    if (!normalizedRepoPath.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return xmlUtf8
    }
    String ct = extractContentTypeIdFromItemXml(xmlUtf8)
    if (!ct?.trim()) {
      log.debug('applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: no content-type in path={}', normalizedRepoPath)
      return xmlUtf8
    }
    ct = ct.trim()
    if (!ct.startsWith('/')) {
      ct = "/${ct}"
    }
    String cfgPath = "/content-types${ct}/form-definition.xml"
    String formXml
    try {
      formXml = configurationServiceBean.getConfigurationAsString(siteId, 'studio', cfgPath, '')
    } catch (Throwable t) {
      log.debug('applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: could not load form {}: {}', cfgPath, t.message)
      return xmlUtf8
    }
    if (!formXml?.trim()) {
      return xmlUtf8
    }
    Document formDoc
    Document itemDoc
    try {
      formDoc = newHardenedSaxReader().read(new StringReader(formXml.toString()))
    } catch (Throwable t) {
      log.debug('applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: form parse failed {}: {}', cfgPath, t.message)
      return xmlUtf8
    }
    try {
      itemDoc = newHardenedSaxReader().read(new StringReader(xmlUtf8.toString()))
    } catch (Throwable t) {
      log.debug('applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: item parse failed path={}: {}', normalizedRepoPath, t.message)
      return xmlUtf8
    }
    Element formRoot = formDoc.getRootElement()
    List<Map> targets = []
    collectTopLevelCheckboxGroupTaxonomyFillTargets(formRoot, false, targets)
    if (targets.isEmpty()) {
      return xmlUtf8
    }
    Element root = itemDoc.getRootElement()
    if (root == null) {
      return xmlUtf8
    }
    boolean anyCheckboxFill = false
    for (Map spec : targets) {
      String fieldId = spec.id?.toString()?.trim()
      int needed = (spec.needed instanceof Number) ? ((Number) spec.needed).intValue() : 0
      String dsId = spec.datasourceId?.toString()?.trim()
      if (!fieldId || needed <= 0 || !dsId) {
        continue
      }
      Element dsEl = findFormDatasourceById(formRoot, dsId)
      if (dsEl == null || !isTaxonomyBackedDatasource(dsEl)) {
        continue
      }
      String taxPath = resolveTaxonomyRepoPathFromDatasource(dsEl)
      if (!taxPath) {
        log.debug(
          'applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: no component/repo path on taxonomy datasource id={} field={}',
          dsId, fieldId
        )
        continue
      }
      String taxXml = null
      for (String cand : expandSiteTaxonomyPathCandidates(taxPath)) {
        taxXml = tryReadSiteContentUtf8(siteId, cand, CONTENT_REF_HEAD)
        if (taxXml) {
          break
        }
      }
      if (!taxXml) {
        log.debug(
          'applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: could not read taxonomy XML for field={} ds={} pathHint={}',
          fieldId, dsId, taxPath
        )
        continue
      }
      List<Map> pairs = parseTaxonomyKeyLabelPairs(taxXml)
      if (pairs.isEmpty()) {
        continue
      }
      String dataTypeProp = formDatasourcePropertyTrim(dsEl, 'dataType')
      String valueTag = checkboxValueElementNameForDataType(dataTypeProp)

      Element fieldRoot = findDirectChildByLocalName(root, fieldId)
      Set<String> haveKeys = fieldRoot != null ? existingCheckboxGroupKeys(fieldRoot) : new LinkedHashSet<>()
      int deficit = needed - haveKeys.size()
      if (deficit <= 0) {
        continue
      }
      if (fieldRoot == null) {
        fieldRoot = root.addElement(fieldId)
        fieldRoot.addAttribute('item-list', 'true')
      } else {
        String il = fieldRoot.attributeValue('item-list')
        if (il == null || !'true'.equalsIgnoreCase(il)) {
          fieldRoot.addAttribute('item-list', 'true')
        }
      }
      int added = 0
      for (Map pair : pairs) {
        if (added >= deficit) {
          break
        }
        String k = pair.get('key')?.toString()
        if (!k) {
          continue
        }
        if (haveKeys.contains(k)) {
          continue
        }
        Element row = fieldRoot.addElement('item')
        row.addElement('key').setText(k)
        String lab = pair.get('label') != null ? pair.get('label').toString() : k
        row.addElement(valueTag).setText(lab)
        haveKeys.add(k)
        added++
      }
      if (added > 0) {
        anyCheckboxFill = true
        log.info(
          'applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded: added {} checkbox item(s) for field={} (needed≈{}) siteId={} path={}',
          added, fieldId, needed, siteId, normalizedRepoPath
        )
      }
    }
    anyCheckboxFill ? itemDoc.asXML() : xmlUtf8
  }

  /**
   * Collects required {@code image-picker} fields not nested under a {@code repeat} (same flat root shape as page/component XML).
   */
  private static void collectTopLevelRequiredImagePickers(Element el, boolean insideRepeat, List<Map> sink) {
    if (el == null || sink == null) {
      return
    }
    boolean isField = 'field'.equals(el.getQName().getName())
    if (!isField) {
      el.elements().each { collectTopLevelRequiredImagePickers(it, insideRepeat, sink) }
      return
    }
    String t = el.elementTextTrim('type')
    if ('repeat'.equals(t)) {
      Element fields = el.element('fields')
      if (fields != null) {
        fields.elements().each { collectTopLevelRequiredImagePickers(it, true, sink) }
      }
      return
    }
    if (!insideRepeat && 'image-picker'.equals(t) && formFieldHasRequiredConstraint(el) && !formFieldImagePickerReadOnly(el)) {
      String fid = el.elementTextTrim('id')
      if (fid) {
        sink.add([id: fid])
      }
    }
    el.elements().each { collectTopLevelRequiredImagePickers(it, insideRepeat, sink) }
  }

  private static Element findDirectChildByLocalName(Element root, String localName) {
    if (root == null || !localName) {
      return null
    }
    for (Iterator it = root.elementIterator(); it.hasNext();) {
      Element e = (Element) it.next()
      if (localName == e.getQName().getName()) {
        return e
      }
    }
    null
  }

  /** Same visual defaults as studio-ui {@code generatePlaceholderImageDataUrl} (300×150, {@code #f0f0f0}, “Sample Image”). */
  private static byte[] renderXbStyleSampleImagePngBytes() {
    try {
      final int w = 300
      final int h = 150
      BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
      Graphics2D g = img.createGraphics()
      try {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setColor(new Color(0xf0, 0xf0, 0xf0))
        g.fillRect(0, 0, w, h)
        String text = 'Sample Image'
        Font font = new Font('SansSerif', Font.PLAIN, 30)
        g.setFont(font)
        g.setColor(Color.BLACK)
        FontMetrics fm = g.getFontMetrics()
        int tw = fm.stringWidth(text)
        int x = Math.max(0, (w - tw) / 2)
        int y = (h + fm.getAscent() - fm.getDescent()) / 2
        g.drawString(text, x, y)
      } finally {
        g.dispose()
      }
      ByteArrayOutputStream bos = new ByteArrayOutputStream()
      ImageIO.write(img, 'png', bos)
      return bos.toByteArray()
    } catch (Throwable t) {
      log.warn('renderXbStyleSampleImagePngBytes: AWT/ImageIO failed ({}), using minimal PNG', t.message)
      return Base64.getDecoder().decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
      )
    }
  }

  /**
   * {@code data:image/png;base64,...} in the same format Experience Builder uses for required empty image-picker values
   * (see studio-ui {@code generatePlaceholderImageDataUrl}).
   */
  private static String xbRequiredEmptyImagePickerDataUrl() {
    String cached = XB_REQUIRED_EMPTY_IMAGE_DATA_URL_CACHE
    if (cached != null) {
      return cached
    }
    synchronized (StudioToolOperations.class) {
      cached = XB_REQUIRED_EMPTY_IMAGE_DATA_URL_CACHE
      if (cached != null) {
        return cached
      }
      byte[] png = renderXbStyleSampleImagePngBytes()
      String s = 'data:image/png;base64,' + Base64.getEncoder().encodeToString(png)
      XB_REQUIRED_EMPTY_IMAGE_DATA_URL_CACHE = s
      return s
    }
  }

  /**
   * Fills missing or empty required top-level {@code image-picker} elements with an XB-style {@code data:image/png;base64,...}
   * placeholder (generated in-process — no repository path, no copying arbitrary form defaults).
   */
  private String applyRequiredImagePickerDataUrlDefaultsIfNeeded(String siteId, String normalizedRepoPath, String xmlUtf8) {
    if (!xmlUtf8 || !normalizedRepoPath?.startsWith('/site/')) {
      return xmlUtf8
    }
    if (!normalizedRepoPath.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return xmlUtf8
    }
    String ct = extractContentTypeIdFromItemXml(xmlUtf8)
    if (!ct?.trim()) {
      log.debug('applyRequiredImagePickerDataUrlDefaultsIfNeeded: no content-type in path={}', normalizedRepoPath)
      return xmlUtf8
    }
    ct = ct.trim()
    if (!ct.startsWith('/')) {
      ct = "/${ct}"
    }
    String cfgPath = "/content-types${ct}/form-definition.xml"
    String formXml
    try {
      formXml = configurationServiceBean.getConfigurationAsString(siteId, 'studio', cfgPath, '')
    } catch (Throwable t) {
      log.debug('applyRequiredImagePickerDataUrlDefaultsIfNeeded: could not load form {}: {}', cfgPath, t.message)
      return xmlUtf8
    }
    if (!formXml?.trim()) {
      return xmlUtf8
    }
    Document formDoc
    Document itemDoc
    try {
      formDoc = newHardenedSaxReader().read(new StringReader(formXml.toString()))
    } catch (Throwable t) {
      log.debug('applyRequiredImagePickerDataUrlDefaultsIfNeeded: form parse failed {}: {}', cfgPath, t.message)
      return xmlUtf8
    }
    try {
      itemDoc = newHardenedSaxReader().read(new StringReader(xmlUtf8.toString()))
    } catch (Throwable t) {
      log.debug('applyRequiredImagePickerDataUrlDefaultsIfNeeded: item parse failed path={}: {}', normalizedRepoPath, t.message)
      return xmlUtf8
    }
    Element formRoot = formDoc.getRootElement()
    List<Map> targets = []
    collectTopLevelRequiredImagePickers(formRoot, false, targets)
    if (targets.isEmpty()) {
      return xmlUtf8
    }
    Element root = itemDoc.getRootElement()
    if (root == null) {
      return xmlUtf8
    }
    String dataUrl = xbRequiredEmptyImagePickerDataUrl()
    int filled = 0
    for (Map spec : targets) {
      String id = spec.id?.toString()?.trim()
      if (!id) {
        continue
      }
      Element child = findDirectChildByLocalName(root, id)
      if (child != null) {
        String existing = child.getTextTrim()
        if (existing) {
          continue
        }
        child.setText(dataUrl)
        filled++
      } else {
        root.addElement(id).setText(dataUrl)
        filled++
      }
    }
    if (filled == 0) {
      return xmlUtf8
    }
    log.info(
      'applyRequiredImagePickerDataUrlDefaultsIfNeeded: filled {} required empty image-picker field(s) with data URL siteId={} path={} contentType={}',
      filled, siteId, normalizedRepoPath, ct
    )
    itemDoc.asXML()
  }

  /**
   * Reads Studio module configuration text (same API family as browser {@code get_configuration}, module {@code studio}).
   * Returns {@code null} or blank when the path does not exist or is empty.
   * <p>When the target is absent, uses {@code cstudioContentService.contentExists} first so Studio does not log
   * {@code ContentNotFoundException} at ERROR from {@code getConfigurationAsString} (e.g. optional {@code user-tools/registry.json}).</p>
   */
  String readStudioConfigurationUtf8(String siteId, String relativePath) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def path = (relativePath ?: '').toString().trim()
      if (!path.startsWith('/')) {
        path = "/${path}"
      }
      String sandboxRepoPath = toSandboxConfigStudioRepoPath(path)
      try {
        Object v1 = cstudioContentServiceBean
        if (v1 != null && v1.metaClass.respondsTo(v1, 'contentExists', String, String)) {
          Object exists = v1.contentExists(siteId, sandboxRepoPath)
          if (!(exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists))) {
            log.trace('readStudioConfigurationUtf8: skip read (missing) siteId={} modulePath={} repoPath={}', siteId, path, sandboxRepoPath)
            return null
          }
        } else if (v1 != null && v1.metaClass.respondsTo(v1, 'shallowContentExists', String, String)) {
          Object exists = v1.shallowContentExists(siteId, sandboxRepoPath)
          if (!(exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists))) {
            log.trace('readStudioConfigurationUtf8: skip read (shallow missing) siteId={} modulePath={} repoPath={}', siteId, path, sandboxRepoPath)
            return null
          }
        }
      } catch (Throwable probeIgnored) {
        // If exists probe fails, fall through to configuration read (legacy behavior).
      }
      try {
        String xml = configurationServiceBean.getConfigurationAsString(siteId, 'studio', path, '')
        return xml
      } catch (Throwable t) {
        log.debug('readStudioConfigurationUtf8 failed siteId={} path={}: {}', siteId, path, t.message)
        return null
      }
    }
  }

  /** Site sandbox path for a Studio {@code studio} module path (e.g. {@code /scripts/...} → {@code /config/studio/scripts/...}). */
  private static String toSandboxConfigStudioRepoPath(String studioModuleRelativePath) {
    String p = (studioModuleRelativePath ?: '').toString().trim()
    if (!p.startsWith('/')) {
      p = "/${p}"
    }
    return "/config/studio${p}"
  }

  /**
   * Writes Studio module configuration bytes via {@link org.craftercms.studio.api.v2.service.config.ConfigurationService#writeConfiguration}.
   */
  void writeStudioConfiguration(String siteId, String relativePath, byte[] bytes) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def path = (relativePath ?: '').toString().trim()
      if (!path.startsWith('/')) {
        path = "/${path}"
      }
      if (bytes == null) {
        bytes = new byte[0]
      }
      if (!configurationServiceBean.metaClass.respondsTo(configurationServiceBean, 'writeConfiguration', String, String, String, String, InputStream)) {
        throw new IllegalStateException('configurationService.writeConfiguration(String,String,String,String,InputStream) not available')
      }
      configurationServiceBean.writeConfiguration(siteId, 'studio', path, '', new ByteArrayInputStream(bytes))
    }
  }

  /**
   * Persists bytes at {@code fullPath} via v1 {@code cstudioContentService} (studio {@code support/4.x} has no v2 {@code ContentService#write}).
   * <p>Paths under {@code /site/} always use 8-arg {@code writeContent} so {@code processContent} runs (see
   * {@code ContentServiceImpl#doWriteContent} on {@code support/4.x}). {@code writeContentAndNotify} uses 3-arg {@code writeContent},
   * which skips that pipeline — fine for config/assets, wrong for site content that must appear in the Studio sidebar.</p>
   */
  private Map writeRepoFile(String siteId, String fullPath, byte[] bytes, boolean unlockAfterWrite = true) {
    log.info('writeRepoFile start: siteId={} path={} bytes={} unlockAfterWrite={}', siteId, fullPath, (bytes?.length ?: 0), unlockAfterWrite)
    boolean siteSandboxPath = (fullPath ?: '').startsWith('/site/')
    boolean usedEightArgWrite = false

    if (siteSandboxPath) {
      def parts = splitRepoPath(fullPath)
      String unlockStr = unlockAfterWrite ? 'true' : 'false'
      cstudioContentServiceBean.writeContent(
        siteId,
        parts.dir,
        parts.file,
        mimeTypeForPath(fullPath),
        new ByteArrayInputStream(bytes),
        'true',
        'true',
        unlockStr
      )
      usedEightArgWrite = true
      log.debug('writeRepoFile wrote via 8-arg writeContent (site pipeline): siteId={} path={} dir={} file={} unlock={}',
        siteId, fullPath, parts.dir, parts.file, unlockStr)
    } else if (unlockAfterWrite && cstudioContentServiceBean.metaClass.respondsTo(cstudioContentServiceBean, 'writeContentAndNotify', String, String, InputStream)) {
      cstudioContentServiceBean.writeContentAndNotify(siteId, fullPath, new ByteArrayInputStream(bytes))
      log.debug('writeRepoFile wrote via writeContentAndNotify: siteId={} path={}', siteId, fullPath)
    } else {
      def parts = splitRepoPath(fullPath)
      String unlockStr = unlockAfterWrite ? 'true' : 'false'
      cstudioContentServiceBean.writeContent(
        siteId,
        parts.dir,
        parts.file,
        mimeTypeForPath(fullPath),
        new ByteArrayInputStream(bytes),
        'true',
        'true',
        unlockStr
      )
      usedEightArgWrite = true
      log.debug('writeRepoFile wrote via 8-arg writeContent: siteId={} path={} dir={} file={} unlock={}',
        siteId, fullPath, parts.dir, parts.file, unlockStr)
    }
    boolean notified = notifyContentEventWithDebug(siteId, fullPath, 'writeRepoFile')
    if (!notified) {
      throw new IllegalStateException("Content saved but preview refresh event failed for '${fullPath}'")
    }
    if (usedEightArgWrite) {
      publishSyncFromRepoForSite(siteId)
    }
    [ok: true, siteId: siteId, path: fullPath, notified: notified, result: 'written']
  }

  /** Persists UTF-8 text at {@code path} via {@link #writeRepoFile} (v1 {@code cstudioContentService} on studio {@code support/4.x}). */
  Map writeContent(String siteId, String path, String contentXml, String unlock = 'true') {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def normalized = normalizeLeadingSlash(path, 'path')
      if (!contentXml?.toString()?.trim()) throw new IllegalArgumentException('Missing required field: contentXml')
      String rawBody = contentXml.toString()
      String safeBody = sanitizeUtf8BodyForXml10(rawBody)
      if (!safeBody.equals(rawBody)) {
        log.warn(
          'writeContent: removed illegal XML 1.0 character(s) (e.g. U+0000) from tool body before Studio parse: siteId={} path={}',
          siteId, normalized
        )
      }
      if (isLikelyXmlRepositoryPath(normalized)) {
        String typoFixed = normalizeCommonLlmXmlTagTypos(safeBody)
        if (!typoFixed.equals(safeBody)) {
          log.warn(
            'writeContent: normalized common LLM XML tag typo(s) before well-formed check: siteId={} path={}',
            siteId, normalized
          )
          safeBody = typoFixed
        }
        try {
          String withDataUrl = applyRequiredImagePickerDataUrlDefaultsIfNeeded(siteId, normalized, safeBody)
          if (withDataUrl != null && !withDataUrl.equals(safeBody)) {
            log.info('writeContent: applied XB-style data URL for required empty image-picker(s) siteId={} path={}', siteId, normalized)
            safeBody = withDataUrl
          }
        } catch (Throwable t) {
          log.warn('writeContent: applyRequiredImagePickerDataUrlDefaultsIfNeeded skipped (non-fatal): {}', t.message)
        }
        try {
          String withCb = applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded(siteId, normalized, safeBody)
          if (withCb != null && !withCb.equals(safeBody)) {
            log.info('writeContent: applied taxonomy defaults for required checkbox-group field(s) siteId={} path={}', siteId, normalized)
            safeBody = withCb
          }
        } catch (Throwable t) {
          log.warn('writeContent: applyRequiredCheckboxGroupTaxonomyDefaultsIfNeeded skipped (non-fatal): {}', t.message)
        }
      }
      if (!safeBody.trim()) {
        throw new IllegalArgumentException(
          'contentXml became empty after removing illegal XML 1.0 characters (e.g. U+0000 and other disallowed controls). ' +
            'Refusing to write an empty file. Re-send a full UTF-8 document with real element markup (use GetContent / update_content as the base).'
        )
      }
      if (isLikelyXmlRepositoryPath(normalized)) {
        assertWellFormedUtf8Xml(normalized, safeBody)
      }
      byte[] bytes = safeBody.getBytes(StandardCharsets.UTF_8)
      boolean unlockAfterWrite = !(unlock != null && unlock.toString().equalsIgnoreCase('false'))
      def result = writeRepoFile(siteId, normalized, bytes, unlockAfterWrite)
      result.unlock = (unlock != null && unlock.toString().equalsIgnoreCase('true'))
      result
    }
  }

  /**
   * Publishes one repository path via {@code cstudioDeploymentService.deploy}
   * ({@code DeploymentService#deploy} — same 7-arg signature as {@code approveAndDeploy}, but creates new workflow entries).
   *
   * @param optionalScheduleIso optional ISO-8601 instant; unparsable values are ignored (deploy immediately)
   * @return deployment id is not provided by this API; returns {@code null}
   */
  Long submitPublishPackage(String siteId, String path, String publishingTarget, String optionalScheduleIso = null) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def normalized = normalizeLeadingSlash(path, 'path')
      def target = (publishingTarget ?: 'live').toString().trim()
      if (!target) target = 'live'
      Instant schedule = null
      def raw = (optionalScheduleIso ?: '').toString().trim()
      if (raw) {
        try {
          schedule = Instant.parse(raw)
        } catch (Throwable ignored) {
          try {
            schedule = ZonedDateTime.parse(raw).toInstant()
          } catch (Throwable ignored2) {
            log.warn('submitPublishPackage: could not parse schedule "{}", publishing immediately', raw)
          }
        }
      }
      ZonedDateTime scheduledDate = schedule != null ? ZonedDateTime.ofInstant(schedule, ZoneId.systemDefault()) : null
      boolean scheduleDateNow = (scheduledDate == null)
      deploymentServiceBean.deploy(
        siteId,
        target,
        Collections.singletonList(normalized),
        scheduledDate,
        currentAuthenticatedUsername(),
        'publish_content tool',
        scheduleDateNow
      )
      null
    }
  }

  /**
   * v2 history for an item (same service as reads). Returns maps safe for tool JSON (versionNumber, modifiedDate, revertible, …).
   */
  List<Map> getContentVersionHistory(String siteId, String path) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def normalized = normalizeLeadingSlash(path, 'path')
      def versions = contentServiceBean.getContentVersionHistory(siteId, normalized)
      if (versions == null) return []
      def out = []
      for (def v : versions) {
        if (v == null) continue
        def md = null
        try {
          md = v.getModifiedDate()?.toString()
        } catch (Throwable ignored) {}
        out.add([
          versionNumber: v.getVersionNumber()?.toString(),
          modifiedDate : md,
          revertible   : v.isRevertible(),
          comment      : v.getComment()?.toString(),
          committer    : v.getCommitter()?.toString(),
          path         : v.getPath()?.toString()
        ])
      }
      out
    }
  }

  /**
   * Reverts {@code path} to a Studio {@code ItemVersion} version string via v1
   * {@code revertContentItem(site, path, version, major, comment)} — not v2 Git {@code revert}.
   */
  void revertContentItem(String siteId, String path, String version, boolean major = false, String comment = null) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def normalized = normalizeLeadingSlash(path, 'path')
      def ver = (version ?: '').toString().trim()
      if (!ver) throw new IllegalArgumentException('Missing required field: version (Studio item version from getContentVersionHistory)')
      String c = (comment ?: 'revert_change tool').toString().trim() ?: 'revert_change tool'
      cstudioContentServiceBean.revertContentItem(siteId, normalized, ver, major, c)
    }
  }

  /**
   * Picks the immediate prior revertible version from v2 history (index {@code 1} when index {@code 0} is current head).
   */
  String resolvePreviousRevertibleVersionNumber(String siteId, String path) {
    def history = getContentVersionHistory(siteId, path)
    if (history == null || history.isEmpty()) {
      throw new IllegalStateException('No version history for path')
    }
    if (history.size() < 2) {
      throw new IllegalStateException('No previous version to revert to (history has only the current entry)')
    }
    def prev = history[1]
    if (prev.revertible == false) {
      for (int i = 2; i < history.size(); i++) {
        def e = history[i]
        if (e.revertible != false && e.versionNumber) {
          return e.versionNumber.toString()
        }
      }
      throw new IllegalStateException('No revertible older version found in history')
    }
    def vn = prev.versionNumber?.toString()?.trim()
    if (!vn) throw new IllegalStateException('Previous history entry has no versionNumber')
    return vn
  }

  private static final long MAX_REMOTE_IMAGE_BYTES = 25L * 1024 * 1024

  /**
   * Parses a raster {@code data:image/...;base64,...} URL into bytes and a normalized MIME type.
   * SVG and non-image data URLs are rejected.
   */
  private static Map parseRasterDataImageUrl(String dataUrl) {
    int comma = dataUrl.indexOf(',')
    if (comma < 5 || !dataUrl.regionMatches(true, 0, 'data:', 0, 5)) {
      throw new IllegalArgumentException('Malformed data URL')
    }
    String meta = dataUrl.substring(5, comma).trim()
    String metaLower = meta.toLowerCase(Locale.ROOT)
    if (!metaLower.startsWith('image/')) {
      throw new IllegalArgumentException('data URL must use an image/* mediatype')
    }
    if (metaLower.contains('image/svg')) {
      throw new IllegalArgumentException('SVG data URLs are not supported for import')
    }
    if (!metaLower.contains(';base64')) {
      throw new IllegalArgumentException('data URL must be base64-encoded')
    }
    int b64Idx = metaLower.indexOf(';base64')
    String mime = (b64Idx > 0 ? meta.substring(0, b64Idx) : meta).trim().toLowerCase(Locale.ROOT)
    if (!mime.startsWith('image/')) {
      throw new IllegalArgumentException('Invalid image mediatype in data URL')
    }
    String b64payload = dataUrl.substring(comma + 1).trim().replaceAll(/\s+/, '')
    byte[] bytes
    try {
      bytes = Base64.decoder.decode(b64payload)
    } catch (Throwable t) {
      throw new IllegalArgumentException("Invalid base64 in data URL: ${t.message}")
    }
    if (!bytes || bytes.length == 0) {
      throw new IllegalStateException('data URL image is empty')
    }
    if (bytes.length > MAX_REMOTE_IMAGE_BYTES) {
      throw new IllegalStateException("Image exceeds maximum size (${MAX_REMOTE_IMAGE_BYTES} bytes)")
    }
    [bytes: bytes, contentType: mime]
  }

  /**
   * Downloads an image from a remote {@code https} URL (SSRF-hardened), or decodes a raster
   * {@code data:image/...;base64,...} URL, writes bytes under {@code /static-assets/...} using the same
   * content service as desktop upload, and returns the repository path for image-picker fields.
   * <p>{@code repoPath} supports the same macros as the desktop image datasource: {@code {yyyy}}, {@code {mm}},
   * {@code {dd}}, {@code {objectId}}, {@code {objectGroupId}}.</p>
   */
  Map importImageFromRemoteUrl(
    String siteId,
    String imageUrl,
    String repoPathRaw,
    String optionalFileName = null,
    String optionalObjectId = null,
    String optionalObjectGroupId = null
  ) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      def normalizedUrl = (imageUrl ?: '').toString().trim()
      if (!normalizedUrl) {
        throw new IllegalArgumentException('Missing required field: imageUrl')
      }
      URI parsed
      try {
        parsed = URI.create(normalizedUrl)
      } catch (Throwable t) {
        throw new IllegalArgumentException("Invalid imageUrl: ${t.message}")
      }
      String scheme = parsed.scheme?.toLowerCase(Locale.ROOT)

      String baseDir = expandImageImportRepoMacros(
        (repoPathRaw ?: '/static-assets/item/images/{yyyy}/{mm}/{dd}/').toString().trim(),
        optionalObjectId?.toString(),
        optionalObjectGroupId?.toString()
      )
      if (!baseDir.startsWith('/static-assets/')) {
        throw new IllegalArgumentException("repoPath must be under /static-assets/: ${baseDir}")
      }
      if (!baseDir.endsWith('/')) {
        baseDir = baseDir + '/'
      }

      byte[] bytes
      String contentType = ''

      if (scheme == 'data') {
        def decoded = parseRasterDataImageUrl(normalizedUrl)
        bytes = decoded.bytes as byte[]
        contentType = (decoded.contentType ?: 'image/png') as String
      } else if (scheme == 'https' || scheme == 'http') {
        String host = parsed.host
        if (!host) {
          throw new IllegalArgumentException('imageUrl must include a host')
        }
        if (scheme == 'http') {
          String h = host.toLowerCase()
          if (!(h == 'localhost' || h == '127.0.0.1' || h == '[::1]')) {
            throw new IllegalArgumentException('Only https URLs are allowed (http is limited to localhost).')
          }
        }
        InetAddress addr = InetAddress.getByName(host)
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() ||
          addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
          throw new IllegalArgumentException('imageUrl host resolves to a non-public address (blocked).')
        }

        URL url = parsed.toURL()
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        conn.setInstanceFollowRedirects(true)
        conn.setConnectTimeout(15_000)
        conn.setReadTimeout(120_000)
        conn.setRequestProperty('Accept', 'image/*,*/*;q=0.8')
        int status = conn.responseCode
        if (status < 200 || status >= 300) {
          throw new IllegalStateException("Failed to download image: HTTP ${status}")
        }
        contentType = (conn.contentType ?: '').split(';')[0]?.trim()?.toLowerCase() ?: ''
        if (contentType && !contentType.startsWith('image/')) {
          throw new IllegalStateException("URL did not return an image (Content-Type: ${contentType})")
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream()
        byte[] buf = new byte[16384]
        long total = 0
        InputStream inStream = conn.inputStream
        try {
          int n
          while ((n = inStream.read(buf)) != -1) {
            total += n
            if (total > MAX_REMOTE_IMAGE_BYTES) {
              throw new IllegalStateException("Image exceeds maximum size (${MAX_REMOTE_IMAGE_BYTES} bytes)")
            }
            bos.write(buf, 0, n)
          }
        } finally {
          try {
            inStream?.close()
          } catch (Throwable ignored) {
          }
        }
        bytes = bos.toByteArray()
        if (bytes.length == 0) {
          throw new IllegalStateException('Downloaded image is empty')
        }
      } else {
        throw new IllegalArgumentException('imageUrl must use http, https, or a raster data:image URL')
      }

      String ext = extensionForImageContentType(contentType)
      String nameFromUrl = scheme == 'data' ? '' : suggestedFileNameFromUrlPath(parsed.path ?: '')
      String baseName = (optionalFileName ?: '').toString().trim()
      if (!baseName) {
        baseName = nameFromUrl ?: "aiassistant-import${ext}"
      }
      baseName = sanitizeImageFileName(baseName, ext)
      if (!baseName.contains('.')) {
        baseName = baseName + ext
      }
      /** Uniquify to avoid overwriting prior imports in the same folder. */
      String uniqueName = insertUniqueSuffixBeforeExtension(baseName)
      String fullPath = baseDir + uniqueName

      writeRepoFile(siteId, fullPath, bytes)
      return [
        ok          : true,
        siteId      : siteId,
        relativeUrl : fullPath,
        fileName    : uniqueName,
        byteLength  : bytes.length,
        contentType : contentType ?: 'application/octet-stream'
      ]
    }
  }

  private static String expandImageImportRepoMacros(String path, String objectId, String objectGroupId) {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
    String y = String.format('%04d', cal.get(Calendar.YEAR))
    String m = String.format('%02d', cal.get(Calendar.MONTH) + 1)
    String d = String.format('%02d', cal.get(Calendar.DAY_OF_MONTH))
    String oid = (objectId ?: '').replaceAll(/[^a-zA-Z0-9_-]/, '_')
    String ogid = (objectGroupId ?: '').replaceAll(/[^a-zA-Z0-9_-]/, '_')
    return path.replace('{yyyy}', y).replace('{mm}', m).replace('{dd}', d)
      .replace('{objectId}', oid).replace('{objectGroupId}', ogid)
  }

  private static String suggestedFileNameFromUrlPath(String path) {
    if (!path) return ''
    int q = path.indexOf('?')
    if (q >= 0) path = path.substring(0, q)
    def parts = path.split('/') as List
    if (parts.isEmpty()) return ''
    String last = parts[parts.size() - 1]
    return last?.trim() ?: ''
  }

  private static String sanitizeImageFileName(String name, String defaultExt) {
    String s = (name ?: 'image').replaceAll(/[^a-zA-Z0-9._-]/, '_')
    if (s.length() > 180) {
      int dot = s.lastIndexOf('.')
      String ext = dot > 0 ? s.substring(dot) : defaultExt
      s = s.substring(0, Math.min(160, s.length())) + ext
    }
    return s ?: 'image' + defaultExt
  }

  private static String insertUniqueSuffixBeforeExtension(String fileName) {
    int dot = fileName.lastIndexOf('.')
    String base = dot > 0 ? fileName.substring(0, dot) : fileName
    String ext = dot > 0 ? fileName.substring(dot) : ''
    long ts = System.currentTimeMillis()
    return "${base}-${ts}${ext}"
  }

  private static String extensionForImageContentType(String contentType) {
    if (!contentType) return '.png'
    if (contentType.contains('jpeg') || contentType.contains('jpg')) return '.jpg'
    if (contentType.contains('png')) return '.png'
    if (contentType.contains('gif')) return '.gif'
    if (contentType.contains('webp')) return '.webp'
    if (contentType.contains('svg')) return '.svg'
    return '.bin'
  }

  Map listPagesAndComponents(String siteId, int size = 1000) {
    def effectiveSite = resolveEffectiveSiteId(siteId)
    if (!effectiveSite?.trim()) {
      return [
        error       : true,
        message     : 'No siteId could be resolved. The chat client must send siteId in the plugin request (query or JSON body) matching the open site.',
        siteId      : '',
        items       : []
      ]
    }

    def req = SearchRequest.of { r ->
      r.query { q ->
        q.bool { b ->
          b.should { s -> s.prefix { p -> p.field('content-type').value('/page') } }
          b.should { s -> s.prefix { p -> p.field('content-type').value('/component') } }
        }
      }.from(0).size(size)
    }

    def authoringSearchService = null
    try {
      authoringSearchService = applicationContext?.get('authoringSearchService')
    } catch (Throwable ignored) {}
    if (authoringSearchService == null) {
      log.warn('listPagesAndComponents: authoringSearchService bean not found')
      return [
        error  : true,
        message: 'Search service is not available in this Studio context. Use GetContent with a known repository path instead.',
        siteId : effectiveSite,
        items  : []
      ]
    }

    try {
      return withStudioRequestSecurity {
        def result = authoringSearchService.search(effectiveSite, req, Map)
        if (!result) return [siteId: effectiveSite, items: []]
        def items = result.hits().hits()*.source()
        [siteId: effectiveSite, items: items]
      }
    } catch (Throwable t) {
      def msg = t.message ?: t.toString()
      log.warn('listPagesAndComponents OpenSearch failed for site {}: {}', effectiveSite, msg)
      return [
        error  : true,
        message: """OpenSearch is not reachable from Studio (${t.class.simpleName}: ${msg}). ListPagesAndComponents requires OpenSearch (same as Studio search) to be running and configured for authoring—start the search stack or fix connection settings. Until then, use GetContent with a full path (e.g. /site/website/...) if the user knows it.""",
        siteId : effectiveSite,
        items  : []
      ]
    }
  }

  private static String normalizeLeadingSlash(def value, String fieldName) {
    def normalized = (value ?: '').toString().trim()
    if (!normalized) throw new IllegalArgumentException("Missing required parameter: ${fieldName}")
    if (!normalized.startsWith('/')) throw new IllegalArgumentException("${fieldName} must start with '/': ${normalized}")
    return normalized
  }

  /** Parent path and file name for v1 8-arg {@code writeContent} (path = directory, fileName separate). */
  private static Map splitRepoPath(String fullPath) {
    String normalized = fullPath
    int slash = normalized.lastIndexOf('/')
    if (slash < 0 || slash == normalized.length() - 1) {
      throw new IllegalArgumentException("Invalid repository path: ${normalized}")
    }
    String dir
    String file
    if (slash == 0) {
      dir = '/'
      file = normalized.substring(1)
    } else {
      dir = normalized.substring(0, slash)
      file = normalized.substring(slash + 1)
    }
    if (!file) throw new IllegalArgumentException("Invalid repository path (no file name): ${normalized}")
    [dir: dir, file: file]
  }

  private int previewFetchMaxChars() {
    try {
      def p = System.getProperty('aiassistant.preview.fetch.maxChars')?.toString()?.trim()
      if (p) {
        int n = Integer.parseInt(p)
        if (n >= 4096 && n <= 2_000_000) return n
      }
    } catch (Throwable ignored) {}
    return 400_000
  }

  private boolean previewFetchHostAllowed(String host) {
    if (!host) return false
    String h = host.toLowerCase(Locale.ROOT)
    String srv = ''
    try {
      srv = request?.getServerName()?.toString()?.trim()?.toLowerCase(Locale.ROOT) ?: ''
    } catch (Throwable ignored) {}
    if (srv && h == srv) return true
    if (h == 'localhost' || h == '127.0.0.1' || h == '[::1]') return true
    def extra = System.getProperty('aiassistant.preview.fetch.allowedHosts')?.toString()?.trim()
    if (extra) {
      for (String part : extra.split(',')) {
        def p = part.trim().toLowerCase(Locale.ROOT)
        if (p && h == p) return true
      }
    }
    return false
  }

  /**
   * GET an absolute preview URL with the {@code crafterPreview} cookie so Engine returns rendered markup.
   * Host must match the Studio request server name, {@code localhost}, {@code 127.0.0.1}, {@code [::1]}, or
   * {@code aiassistant.preview.fetch.allowedHosts} (comma-separated). Redirects are not followed.
   */
  Map fetchPreviewRenderedHtml(String absoluteUrl, String toolPreviewToken, String siteIdOpt) {
    def urlStr = (absoluteUrl ?: '').toString().trim()
    if (!urlStr) {
      throw new IllegalArgumentException('Missing required field: url (absolute http(s) preview URL, or previewUrl alias)')
    }
    String siteForQuery = (siteIdOpt ?: '').toString().trim()
    if (!siteForQuery) {
      try {
        siteForQuery = resolveEffectiveSiteId('') ?: ''
      } catch (Throwable ignored) {
        siteForQuery = ''
      }
    }
    urlStr = rewriteStudioPreviewShellUrlForEngineFetch(urlStr, siteForQuery)
    String token = (toolPreviewToken ?: '').toString().trim()
    if (!token) token = crafterqPreviewToken ?: ''
    if (!token) token = readCrafterPreviewTokenFromServletRequest(request) ?: ''
    if (!token) {
      return [
        ok     : false,
        action : 'get_preview_html',
        message:
          'Missing preview token: pass previewToken in tool arguments, send previewToken in the CrafterQ chat POST body, or ensure the browser sends the crafterPreview cookie on the chat request (HttpOnly cookies are read server-side).'
      ]
    }
    // Tool/UI may echo crafterPreview in the URL with literal '+' (base64); form-style query parsing treats '+' as
    // space and corrupts the ticket → HTTP 401. Always drop caller-supplied crafterPreview and append URLEncoder output.
    String u = removeQueryParamsCaseInsensitive(urlStr, ['crafterPreview'])
    if (siteForQuery && !u.toLowerCase(Locale.ROOT).contains('craftersite=')) {
      u += (u.contains('?') ? '&' : '?') + 'crafterSite=' + URLEncoder.encode(siteForQuery, 'UTF-8')
    }
    u += (u.contains('?') ? '&' : '?') + 'crafterPreview=' + URLEncoder.encode(token, 'UTF-8')
    URI uri
    try {
      uri = new URI(u)
    } catch (Throwable t) {
      return [ok: false, action: 'get_preview_html', message: "Invalid URL: ${t.message}"]
    }
    if (!uri.scheme || (!'http'.equalsIgnoreCase(uri.scheme) && !'https'.equalsIgnoreCase(uri.scheme))) {
      return [ok: false, action: 'get_preview_html', message: 'url must use http or https']
    }
    String host = uri.host
    if (!host) {
      return [ok: false, action: 'get_preview_html', message: 'url must include a host name']
    }
    if (!previewFetchHostAllowed(host)) {
      return [
        ok     : false,
        action : 'get_preview_html',
        message:
          "Host '${host}' is not allowed for preview fetch. Allowed: this Studio server name (${request?.getServerName()}), localhost, 127.0.0.1, [::1], or JVM aiassistant.preview.fetch.allowedHosts (comma-separated)."
      ]
    }
    URL connUrl
    try {
      connUrl = uri.toURL()
    } catch (Throwable t) {
      return [ok: false, action: 'get_preview_html', message: "Invalid URL: ${t.message}"]
    }
    HttpURLConnection conn = null
    InputStream inStream = null
    try {
      conn = (HttpURLConnection) connUrl.openConnection()
      conn.setRequestMethod('GET')
      conn.setInstanceFollowRedirects(false)
      conn.setConnectTimeout(15000)
      conn.setReadTimeout(45000)
      conn.setRequestProperty('Accept', 'text/html,application/xhtml+xml;q=0.9,*/*;q=0.8')
      // Engine accepts the Experience Builder preview ticket via cookie crafterPreview, query crafterPreview, and/or
      // the x-crafter-preview header (same value). Server-side fetches must send the header — cookie-only paths can
      // still 401 depending on servlet / security filter order.
      conn.setRequestProperty('x-crafter-preview', token)
      String cookieHeader = buildPreviewFetchCookieHeader(token, siteForQuery)
      conn.setRequestProperty('Cookie', cookieHeader)
      try {
        def ua = request?.getHeader('User-Agent')?.toString()?.trim()
        if (ua) {
          conn.setRequestProperty('User-Agent', ua)
        }
        // Studio Bearer JWT is not valid Engine auth; forwarding it often yields 401 on preview GET.
        if (Boolean.parseBoolean(System.getProperty('aiassistant.preview.fetch.forwardAuthorization', 'false'))) {
          def authz = request?.getHeader('Authorization')?.toString()?.trim()
          if (authz) {
            conn.setRequestProperty('Authorization', authz)
          }
        }
        def ref = request?.getHeader('Referer')?.toString()?.trim()
        if (!ref) {
          def ru = request?.getRequestURL()
          if (ru != null) {
            ref = ru.toString().trim()
          }
        }
        if (ref) {
          conn.setRequestProperty('Referer', ref)
        }
      } catch (Throwable ignored) {
      }
      int status = conn.getResponseCode()
      if (status == 401) {
        log.warn('fetchPreviewRenderedHtml HTTP 401 url={} crafterPreviewTokenChars={} outgoingCookieHeaderChars={}',
          u, token.length(), cookieHeader.length())
      }
      String ct = (conn.getContentType() ?: '').toString()
      if (status >= 300 && status < 400) {
        return [
          ok        : false,
          action    : 'get_preview_html',
          statusCode: status,
          message   : 'HTTP redirect — use the final preview URL (redirects are disabled for safety).',
          location  : conn.getHeaderField('Location')
        ]
      }
      inStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream()
      int maxChars = previewFetchMaxChars()
      StringBuilder sb = new StringBuilder(Math.min(maxChars + 16, 65536))
      boolean truncated = false
      int total = 0
      if (inStream != null) {
        def reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))
        char[] cbuf = new char[8192]
        while (true) {
          int n = reader.read(cbuf)
          if (n < 0) break
          if (total + n <= maxChars) {
            sb.append(cbuf, 0, n)
            total += n
          } else {
            int take = maxChars - total
            if (take > 0) {
              sb.append(cbuf, 0, take)
              total = maxChars
            }
            truncated = true
            break
          }
        }
      }
      String body = sb.toString()
      return [
        action    : 'get_preview_html',
        ok        : status >= 200 && status < 300,
        statusCode: status,
        contentType: ct,
        charCount : body.length(),
        truncated : truncated,
        html      : body,
        message   : status >= 200 && status < 300
          ? 'Fetched preview HTML.'
          : (status == 401
            ? 'HTTP 401 — Engine rejected preview fetch. The plugin sends x-crafter-preview, crafterPreview cookie/query (re-encoded), and crafterSite. Authorization is not forwarded unless JVM aiassistant.preview.fetch.forwardAuthorization=true. Ensure previewToken is in the AI Assistant chat/stream POST body and the author has an active XB preview session.'
            : "HTTP ${status} — body may be an error page.")
      ]
    } catch (Throwable t) {
      log.warn('fetchPreviewRenderedHtml failed url={}: {}', u, t.toString())
      return [
        ok     : false,
        action : 'get_preview_html',
        message: (t.message ?: t.toString())
      ]
    } finally {
      try {
        inStream?.close()
      } catch (Throwable ignored) {}
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {}
    }
  }

  /**
   * When {@code false} (JVM {@code aiassistant.httpFetch.enabled=false}), {@link #fetchHttpUrl} refuses all requests.
   */
  boolean httpFetchGloballyEnabled() {
    !'false'.equalsIgnoreCase(System.getProperty('aiassistant.httpFetch.enabled', 'true')?.toString()?.trim())
  }

  private static int httpFetchMaxChars(Integer toolRequested) {
    int cap = 400_000
    try {
      def p = System.getProperty('aiassistant.httpFetch.maxChars')?.toString()?.trim()
      if (p) {
        cap = Integer.parseInt(p)
      }
    } catch (Throwable ignored) {}
    if (cap < 4096) {
      cap = 4096
    }
    if (cap > 2_000_000) {
      cap = 2_000_000
    }
    if (toolRequested != null) {
      try {
        int tr = toolRequested.intValue()
        if (tr > 0) {
          cap = Math.min(cap, Math.min(tr, 2_000_000))
        }
      } catch (Throwable ignored) {}
    }
    return cap
  }

  private static boolean httpFetchInetBlocked(InetAddress ia) {
    if (ia == null) {
      return true
    }
    if (ia.isAnyLocalAddress() || ia.isLoopbackAddress()) {
      return true
    }
    if (ia.isLinkLocalAddress() || ia.isSiteLocalAddress()) {
      return true
    }
    if (ia.isMulticastAddress()) {
      return true
    }
    byte[] a = ia.getAddress()
    if (a.length == 4) {
      int b0 = a[0] & 0xff
      int b1 = a[1] & 0xff
      if (b0 == 0) {
        return true
      }
      if (b0 == 100 && b1 >= 64 && b1 <= 127) {
        return true
      }
      return false
    }
    if (a.length == 16) {
      int b0 = a[0] & 0xff
      int b1 = a[1] & 0xff
      if (b0 == 0xfe && (b1 & 0xc0) == 0x80) {
        return true
      }
      if ((b0 & 0xfe) == 0xfc) {
        return true
      }
      return false
    }
    return true
  }

  private static boolean httpFetchHostnameBlocked(String host) {
    if (!host) {
      return true
    }
    String h = host.toLowerCase(Locale.ROOT)
    if ('localhost' == h || '0.0.0.0' == h || '::1' == h || '[::1]' == h) {
      return true
    }
    if (h.endsWith('.local')) {
      return true
    }
    if ('169.254.169.254' == h) {
      return true
    }
    if ('metadata.google.internal' == h || 'metadata.google.internal.' == h) {
      return true
    }
    return false
  }

  /**
   * When JVM {@code aiassistant.httpFetch.allowedHostSuffixes} is set (comma-separated), host must equal a suffix or be a subdomain of it.
   * @return empty string if allowed, otherwise an error message
   */
  private static String httpFetchAllowedSuffixesViolation(String host) {
    def prop = System.getProperty('aiassistant.httpFetch.allowedHostSuffixes')?.toString()?.trim()
    if (!prop) {
      return ''
    }
    List<String> parts = []
    for (String part : prop.split(',')) {
      def p = part.trim().toLowerCase(Locale.ROOT)
      if (p) {
        parts.add(p)
      }
    }
    if (parts.isEmpty()) {
      return ''
    }
    String h = host.toLowerCase(Locale.ROOT)
    for (String suf : parts) {
      if (h == suf || h.endsWith('.' + suf)) {
        return ''
      }
    }
    return "Host '${host}' is not in aiassistant.httpFetch.allowedHostSuffixes (${prop})."
  }

  /**
   * Same SSRF and scheme checks as the first hop of {@link #fetchHttpUrl} (without performing the GET).
   * Used by MCP and other outbound HTTP clients in the plugin.
   *
   * @return {@code null} if the URL is allowed, otherwise a short error message suitable for logs or tool JSON
   */
  static String validateOutboundHttpUrlForSsrf(String absoluteUrl) {
    if ('false'.equalsIgnoreCase(System.getProperty('aiassistant.httpFetch.enabled', 'true')?.toString()?.trim())) {
      return 'HTTP outbound is disabled (JVM aiassistant.httpFetch.enabled=false).'
    }
    URI start
    try {
      start = new URI((absoluteUrl ?: '').toString().trim())
    } catch (Throwable t) {
      return "Invalid URL: ${t.message}"
    }
    return httpFetchSsrfErrorForUri(start)
  }

  /**
   * Validates scheme, userinfo, hostname blocklist, optional suffix allowlist, and that all resolved IPs are public.
   * @return {@code null} if OK, otherwise an error message
   */
  private static String httpFetchSsrfErrorForUri(URI u) {
    if (!u.scheme || (!'http'.equalsIgnoreCase(u.scheme) && !'https'.equalsIgnoreCase(u.scheme))) {
      return 'url must use http or https'
    }
    String rawUi = ''
    try {
      rawUi = u.getRawUserInfo() ?: ''
    } catch (Throwable ignored) {
      rawUi = ''
    }
    if (rawUi?.trim()) {
      return 'URLs with userinfo (user:password@) are not allowed'
    }
    String host = u.host
    if (!host) {
      return 'url must include a host'
    }
    if (httpFetchHostnameBlocked(host)) {
      return "Host '${host}' is blocked for SSRF safety."
    }
    String suf = httpFetchAllowedSuffixesViolation(host)
    if (suf) {
      return suf
    }
    InetAddress[] resolved
    try {
      resolved = InetAddress.getAllByName(host)
    } catch (Throwable t) {
      return "DNS resolution failed: ${t.message}"
    }
    for (InetAddress ia : resolved) {
      if (httpFetchInetBlocked(ia)) {
        return "Host '${host}' resolves to a non-public address (${ia.hostAddress}) — fetch denied."
      }
    }
    return null
  }

  /**
   * Reads a single {@code <link …>} attribute value (quoted). Attribute name match is ASCII case-insensitive.
   */
  private static String httpFetchExtractLinkAttrInsensitive(String attrs, String name) {
    if (!attrs || !name) {
      return null
    }
    String q = java.util.regex.Pattern.quote(name.toString())
    def m = (attrs =~ /(?is)(?<![\w-])${q}\s*=\s*["']([^"']*)["']/)
    return m.find() ? m.group(1)?.toString()?.trim() : null
  }

  /** True when {@code rel} is a space-separated token list that includes {@code stylesheet} (HTML allows multiple link types). */
  private static boolean httpFetchRelContainsStylesheetToken(String rel) {
    if (!rel) {
      return false
    }
    for (String tok : rel.toLowerCase(Locale.ROOT).trim().split(/\s+/)) {
      if ('stylesheet'.equals(tok)) {
        return true
      }
    }
    return false
  }

  /**
   * Collects {@code rel} stylesheet {@code <link href="…">} targets from an HTML prefix (any attribute order;
   * {@code rel} may list multiple space-separated tokens such as {@code alternate stylesheet}).
   * Used so {@link #fetchHttpUrl} tool JSON lists real CSS entry points even when {@code body} is truncated later on the wire.
   */
  private static List<String> httpFetchCollectStylesheetHrefs(String html, int max) {
    if (html == null || max < 1) {
      return []
    }
    int cap = Math.min(html.length(), 400_000)
    String s = html.substring(0, cap)
    Set<String> seen = new LinkedHashSet<>()
    List<String> out = []
    def push = { String href ->
      if (!href) {
        return
      }
      String t = href.trim()
      if (!t || seen.contains(t)) {
        return
      }
      seen.add(t)
      out.add(t)
    }
    def linkMatcher = (s =~ /(?is)<link\s([^>]+)>/)
    while (linkMatcher.find() && out.size() < max) {
      String attrs = linkMatcher.group(1)?.toString() ?: ''
      String href = httpFetchExtractLinkAttrInsensitive(attrs, 'href')
      String rel = httpFetchExtractLinkAttrInsensitive(attrs, 'rel')
      if (href && httpFetchRelContainsStylesheetToken(rel)) {
        push(href)
      }
    }
    return out
  }

  /**
   * GET an http(s) URL and return response body as text (HTML, CSS, JSON, etc.) for reference / redesign workflows.
   * <p>SSRF: only public hosts; blocks loopback, link-local, site-local, CGNAT, ULA; optional
   * {@code aiassistant.httpFetch.allowedHostSuffixes}. Redirects are followed manually (max 5) with validation each hop.
   * Does not forward Studio cookies or Authorization.</p>
   *
   * @param absoluteUrl absolute http(s) URL
   * @param maxCharsOpt optional cap on returned characters (still bounded by JVM {@code aiassistant.httpFetch.maxChars})
   */
  Map fetchHttpUrl(String absoluteUrl, Integer maxCharsOpt) {
    if (!httpFetchGloballyEnabled()) {
      return [
        ok    : false,
        action: 'fetch_http_url',
        message: 'HTTP URL fetch is disabled (JVM aiassistant.httpFetch.enabled=false).'
      ]
    }
    def urlStr = (absoluteUrl ?: '').toString().trim()
    if (!urlStr) {
      throw new IllegalArgumentException('Missing required field: url (absolute http(s) URL)')
    }
    URI start
    try {
      start = new URI(urlStr)
    } catch (Throwable t) {
      return [ok: false, action: 'fetch_http_url', message: "Invalid URL: ${t.message}"]
    }
    String err0 = httpFetchSsrfErrorForUri(start)
    if (err0) {
      return [ok: false, action: 'fetch_http_url', message: err0]
    }
    int maxChars = httpFetchMaxChars(maxCharsOpt)
    final int maxRedirects = 5
    int redirectCount = 0
    URI currentUri = start
    HttpURLConnection conn = null
    InputStream inStream = null
    try {
      while (true) {
        String hopErr = httpFetchSsrfErrorForUri(currentUri)
        if (hopErr) {
          return [ok: false, action: 'fetch_http_url', message: hopErr]
        }
        URL url = currentUri.toURL()
        conn = (HttpURLConnection) url.openConnection()
        conn.setRequestMethod('GET')
        conn.setInstanceFollowRedirects(false)
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(60_000)
        conn.setRequestProperty(
          'Accept',
          'text/html,text/css,text/plain,text/javascript,application/javascript,application/json,application/xhtml+xml,*/*;q=0.5'
        )
        conn.setRequestProperty('Accept-Encoding', 'identity')
        conn.setRequestProperty('User-Agent', 'CrafterQ-Studio-Plugin/1.0 (+https://craftercms.org)')
        int status = conn.getResponseCode()
        if (status >= 300 && status < 400 && redirectCount < maxRedirects) {
          String loc = conn.getHeaderField('Location')?.toString()?.trim()
          try {
            conn.disconnect()
          } catch (Throwable ignored) {}
          conn = null
          if (!loc) {
            return [
              ok        : false,
              action    : 'fetch_http_url',
              statusCode: status,
              message   : "HTTP ${status} redirect without Location header."
            ]
          }
          try {
            currentUri = currentUri.resolve(new URI(loc))
          } catch (Throwable t) {
            return [ok: false, action: 'fetch_http_url', message: "Invalid redirect Location: ${t.message}"]
          }
          redirectCount++
          continue
        }
        String ct = (conn.getContentType() ?: '').toString()
        if (status >= 300 && status < 400) {
          return [
            ok        : false,
            action    : 'fetch_http_url',
            statusCode: status,
            message   : "Exceeded maximum of ${maxRedirects} redirect hops (or redirect could not be applied).",
            location  : conn.getHeaderField('Location')
          ]
        }
        inStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream()
        StringBuilder sb = new StringBuilder(Math.min(maxChars + 16, 65536))
        boolean truncated = false
        int total = 0
        if (inStream != null) {
          def reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))
          char[] cbuf = new char[8192]
          while (true) {
            int n = reader.read(cbuf)
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
                total = maxChars
              }
              truncated = true
              break
            }
          }
        }
        String body = sb.toString()
        String finalUrl = ''
        try {
          finalUrl = conn.getURL()?.toString() ?: currentUri.toString()
        } catch (Throwable ignored) {
          finalUrl = currentUri.toString()
        }
        def ctLower = (ct ?: '').toString().toLowerCase(Locale.ROOT)
        boolean looksHtml =
          ctLower.contains('html') ||
            body.contains('<html') ||
            body.contains('<HTML') ||
            body.contains('<head') ||
            body.contains('<HEAD') ||
            body.contains('<link') ||
            body.contains('<LINK')
        List stylesheetHrefs = looksHtml ? httpFetchCollectStylesheetHrefs(body, 48) : []
        return [
          action    : 'fetch_http_url',
          ok        : status >= 200 && status < 300,
          statusCode: status,
          finalUrl  : finalUrl,
          contentType: ct,
          charCount : body.length(),
          truncated : truncated,
          body      : body,
          stylesheetHrefs: stylesheetHrefs,
          redirects : redirectCount,
          message   : status >= 200 && status < 300
            ? 'Fetched URL body as UTF-8 text.'
            : "HTTP ${status} — body may be an error page or non-HTML."
        ]
      }
    } catch (Throwable t) {
      log.warn('fetchHttpUrl failed url={}: {}', absoluteUrl, t.toString())
      return [
        ok     : false,
        action : 'fetch_http_url',
        message: (t.message ?: t.toString())
      ]
    } finally {
      try {
        inStream?.close()
      } catch (Throwable ignored) {}
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {}
    }
  }

  private static String mimeTypeForPath(String fullPath) {
    def p = (fullPath ?: '').toLowerCase()
    if (p.endsWith('.xml')) return 'application/xml'
    if (p.endsWith('.ftl')) return 'text/plain'
    if (p.endsWith('.json')) return 'application/json'
    if (p.endsWith('.css')) return 'text/css'
    if (p.endsWith('.js')) return 'application/javascript'
    if (p.endsWith('.html') || p.endsWith('.htm')) return 'text/html'
    if (p.endsWith('.png')) return 'image/png'
    if (p.endsWith('.jpg') || p.endsWith('.jpeg')) return 'image/jpeg'
    if (p.endsWith('.gif')) return 'image/gif'
    if (p.endsWith('.webp')) return 'image/webp'
    if (p.endsWith('.svg')) return 'image/svg+xml'
    if (p.endsWith('.ico')) return 'image/x-icon'
    if (p.endsWith('.properties')) return 'text/plain'
    if (p.endsWith('.yaml') || p.endsWith('.yml')) return 'application/yaml'
    if (p.endsWith('.md') || p.endsWith('.txt')) return 'text/plain'
    if (p.endsWith('.groovy')) return 'text/plain'
    'application/octet-stream'
  }

  private boolean notifyContentEventWithDebug(String siteId, String fullPath, String source) {
    try {
      log.info('notifyContentEvent start: source={} siteId={} path={}', source, siteId, fullPath)
      cstudioContentServiceBean.notifyContentEvent(siteId, fullPath)
      log.info('notifyContentEvent success: source={} siteId={} path={}', source, siteId, fullPath)
      return true
    } catch (Throwable t) {
      log.warn('notifyContentEvent failed: source={} siteId={} path={} reason={}',
        source, siteId, fullPath, (t.message ?: t.toString()), t)
      return false
    }
  }

  /**
   * Publishes {@link SyncFromRepoEvent} so Studio reconciles Git → item DB / sidebar (same event 3-arg
   * {@code ContentServiceImpl#writeContent} emits on {@code support/4.x}). 8-arg {@code writeContent} does not publish it.
   */
  private void publishSyncFromRepoForSite(String siteId) {
    if (!siteId) {
      return
    }
    try {
      ApplicationContext ctx = null
      try {
        def sc = request?.getServletContext()
        if (sc != null) {
          ctx = WebApplicationContextUtils.getWebApplicationContext(sc)
        }
      } catch (Throwable ignored) {
      }
      if (ctx == null && applicationContext instanceof ApplicationContext) {
        ctx = (ApplicationContext) applicationContext
      }
      if (ctx == null) {
        log.debug('publishSyncFromRepoForSite: no ApplicationContext for publishEvent; siteId={}', siteId)
        return
      }
      ctx.publishEvent(new SyncFromRepoEvent(siteId))
      log.info('publishSyncFromRepoForSite: siteId={}', siteId)
    } catch (Throwable t) {
      log.warn('publishSyncFromRepoForSite failed (non-fatal): siteId={} reason={}', siteId, (t.message ?: t.toString()))
    }
  }

  /**
   * First-level child folder names under a Studio sandbox directory (e.g. {@code /scripts/aiassistant/imagegen}).
   * Uses v1 {@code getContentItemTree} when available; returns an empty list on failure.
   */
  List<String> listStudioSandboxChildFolderNames(String siteId, String studioModuleParentDir) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      String dir = (studioModuleParentDir ?: '').toString().trim()
      if (!dir.startsWith('/')) {
        dir = "/${dir}"
      }
      String fullPath = toSandboxConfigStudioRepoPath(dir)
      try {
        if (cstudioContentServiceBean != null &&
          cstudioContentServiceBean.metaClass.respondsTo(cstudioContentServiceBean, 'getContentItemTree', String, String, int)) {
          Object root = cstudioContentServiceBean.getContentItemTree(siteId, fullPath, 2)
          return extractFirstLevelFolderNamesFromContentItemTree(root)
        }
      } catch (Throwable t) {
        log.debug('listStudioSandboxChildFolderNames failed siteId={} path={}: {}', siteId, fullPath, t.message)
      }
      []
    }
  }

  private static List<String> extractFirstLevelFolderNamesFromContentItemTree(Object root) {
    if (root == null) {
      return []
    }
    Object children = null
    try {
      children = root.children
    } catch (Throwable ignored) {
    }
    if (children == null) {
      try {
        if (root.metaClass.respondsTo(root, 'getChildren')) {
          children = root.getChildren()
        }
      } catch (Throwable ignored2) {
      }
    }
    if (!(children instanceof Iterable)) {
      return []
    }
    List<String> out = []
    for (Object c : (Iterable) children) {
      if (c == null) {
        continue
      }
      String uri = ''
      String nm = ''
      try {
        uri = c.uri?.toString() ?: ''
      } catch (Throwable ignored) {
      }
      if (!uri) {
        try {
          uri = c.browserUri?.toString() ?: ''
        } catch (Throwable ignored) {
        }
      }
      try {
        nm = c.name?.toString() ?: ''
      } catch (Throwable ignored) {
      }
      if (!nm) {
        try {
          nm = c.internalName?.toString() ?: ''
        } catch (Throwable ignored) {
        }
      }
      if (!nm && uri) {
        int slash = uri.lastIndexOf('/')
        nm = slash >= 0 ? uri.substring(slash + 1) : uri
      }
      nm = (nm ?: '').trim()
      if (!nm) {
        continue
      }
      boolean looksLikeFile = uri && (uri.endsWith('.groovy') || uri.endsWith('.xml') || uri.endsWith('.json'))
      if (looksLikeFile) {
        continue
      }
      boolean isFolder = true
      try {
        Object f = c.folder
        if (f != null) {
          isFolder = Boolean.TRUE.equals(f) || 'true'.equalsIgnoreCase(f.toString())
        }
      } catch (Throwable ignored) {
      }
      if (isFolder) {
        out.add(nm)
      }
    }
    return out.unique()
  }

  /**
   * Deletes a sandbox item (file or folder) using v1 {@code deleteContent(String site, String path, String approver)}.
   */
  void deleteStudioSandboxItem(String siteId, String fullRepoPath, String approver) {
    withStudioRequestSecurity {
      siteId = resolveEffectiveSiteId(siteId)
      String path = (fullRepoPath ?: '').toString().trim()
      if (!path.startsWith('/')) {
        path = "/${path}"
      }
      String who = (approver ?: '').toString().trim()
      if (!who) {
        who = 'studio-aiassistant-plugin'
      }
      if (cstudioContentServiceBean == null) {
        throw new IllegalStateException('cstudioContentServiceBean unavailable')
      }
      if (!cstudioContentServiceBean.metaClass.respondsTo(cstudioContentServiceBean, 'deleteContent', String, String, String)) {
        throw new IllegalStateException('deleteContent(site,path,approver) not available on ContentService')
      }
      cstudioContentServiceBean.deleteContent(siteId, path, who)
    }
  }

  /** After writing/deleting Studio sandbox config files, notify Studio to reconcile (same as post-write tool path). */
  void publishConfigChangeRefresh(String siteId) {
    publishSyncFromRepoForSite(siteId)
  }
}

