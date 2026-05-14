package plugins.org.craftercms.aiassistant.content

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Builds a single nested XML bundle of a page (or component) plus all reachable {@code /site/.../*.xml} references
 * discovered via {@code <key>} node-selector links, for fewer LLM round-trips on full-page translate/update work.
 * <p>Round-trip: {@link #build} emits {@code <document path="...">} blocks with CDATA bodies; {@link #apply} parses
 * the same shape and calls {@link StudioToolOperations#writeContent} per path.</p>
 */
final class ContentSubgraphAggregator {

  private static final Logger log = LoggerFactory.getLogger(ContentSubgraphAggregator.class)

  private ContentSubgraphAggregator() {}

  /** Root element name (not a Crafter content-type element). */
  static final String SUBGRAPH_ROOT = 'crafterq-content-subgraph'

  static final String DOCUMENT_EL = 'document'

  private static final Pattern SITE_XML_KEY = Pattern.compile(
    '(?is)<key>\\s*(/site/[\\w./\\-]+\\.xml)\\s*</key>'
  )

  /** {@code <document path="..." content-type="..."> ... </document>} with CDATA body. */
  private static final Pattern DOCUMENT_BLOCK = Pattern.compile(
    '(?is)<' + DOCUMENT_EL + '\\s+path="([^"]+)"(?:\\s+content-type="([^"]*)")?[^>]*>\\s*<!\\[CDATA\\[(.*?)\\]\\]>\\s*</' + DOCUMENT_EL + '>'
  )

  /**
   * Same as {@link #DOCUMENT_BLOCK} but allows attributes in any order on {@code <document>} (models often put
   * {@code content-type} before {@code path}). Used only to salvage {@code TranslateContentItem} inner replies.
   */
  private static final Pattern DOCUMENT_BLOCK_RELAXED = Pattern.compile(
    '(?is)<' + DOCUMENT_EL + '\\s+([^>]+)>\\s*<!\\[CDATA\\[(.*?)\\]\\]>\\s*</' + DOCUMENT_EL + '>'
  )

  /**
   * {@code <document attrs>...</document>} where inner may be CDATA **or** raw {@code <page>/<component>} XML (models
   * often omit CDATA). Inner match is non-greedy to the first closing {@code </document>}.
   */
  private static final Pattern DOCUMENT_BLOCK_RELAXED_RAW = Pattern.compile(
    '(?is)<' + DOCUMENT_EL + '\\s+([^>]+)>([\\s\\S]*?)</' + DOCUMENT_EL + '>'
  )

  private static final Pattern SUBGRAPH_ROOT_ATTR = Pattern.compile(
    '(?is)<' + Pattern.quote(SUBGRAPH_ROOT) + '\\s+[^>]*\\broot="([^"]*)"'
  )

  private static final int DEFAULT_MAX_ITEMS = 300

  private static final int DEFAULT_MAX_DEPTH = 40

  static String escapeXmlAttr(String s) {
    if (s == null) {
      return ''
    }
    return s.toString()
      .replace('&', '&amp;')
      .replace('"', '&quot;')
      .replace('<', '&lt;')
      .replace('>', '&gt;')
  }

  /** CDATA-safe wrapper (splits on {@code ]]>} if present in source XML). */
  static String wrapCdataBody(String raw) {
    if (raw == null) {
      return '<![CDATA[]]>'
    }
    String s = raw.toString()
    return '<![CDATA[' + s.replace(']]>', ']]]]><![CDATA[>') + ']]>'
  }

  private static String extractXmlDoubleQuotedAttr(String attrsFragment, String attrLocalName) {
    if (!attrsFragment?.trim() || !attrLocalName?.trim()) {
      return ''
    }
    Matcher m =
      Pattern
        .compile('(?is)\\b' + Pattern.quote(attrLocalName.trim()) + '\\s*=\\s*"([^"]*)"')
        .matcher(attrsFragment)
    return m.find() ? m.group(1).trim() : ''
  }

  private static String extractDocumentInnerBody(String innerFragment) {
    if (!innerFragment?.trim()) {
      return ''
    }
    String t = innerFragment.trim()
    if (t.startsWith('<![CDATA[')) {
      int idx = t.indexOf(']]>')
      if (idx >= 9) {
        return t.substring(9, idx)
      }
    }
    return t
  }

  private static List<Map> parseDocumentsRelaxedCdataOnly(String subgraphXml) {
    List<Map> out = new ArrayList<>()
    Matcher dm = DOCUMENT_BLOCK_RELAXED.matcher(subgraphXml ?: '')
    while (dm.find()) {
      String attrs = dm.group(1)
      String body = dm.group(2) ?: ''
      String path = extractXmlDoubleQuotedAttr(attrs, 'path')
      if (!path?.trim()) {
        continue
      }
      String ct = extractXmlDoubleQuotedAttr(attrs, 'content-type')
      out.add([path: path.trim(), contentType: ct.trim(), body: body])
    }
    out
  }

  private static List<Map> parseDocumentsRelaxedRawInner(String subgraphXml) {
    List<Map> out = new ArrayList<>()
    Matcher dm = DOCUMENT_BLOCK_RELAXED_RAW.matcher(subgraphXml ?: '')
    while (dm.find()) {
      String attrs = dm.group(1)
      String inner = (dm.group(2) ?: '').trim()
      String body = extractDocumentInnerBody(inner)
      String path = extractXmlDoubleQuotedAttr(attrs, 'path')
      if (!path?.trim()) {
        continue
      }
      String ct = extractXmlDoubleQuotedAttr(attrs, 'content-type')
      out.add([path: path.trim(), contentType: ct.trim(), body: body])
    }
    out
  }

  /**
   * Parses {@code <document>} blocks when {@link #DOCUMENT_BLOCK} fails (attribute order, extra spaces, missing CDATA).
   */
  static List<Map> parseDocumentsRelaxed(String subgraphXml) {
    List<Map> out = parseDocumentsRelaxedCdataOnly(subgraphXml)
    if (!out.isEmpty()) {
      return out
    }
    return parseDocumentsRelaxedRawInner(subgraphXml)
  }

  /**
   * When the inner model returns only the item root (no {@code crafterq-content-subgraph} / {@code document} wrapper).
   * Public for {@code TranslateContentItem} raw inner completion path.
   */
  static String extractLikelySingleItemRootXml(String raw) {
    if (!raw?.trim()) {
      return null
    }
    String t = raw.trim()
    String[] roots = ['page', 'component']
    for (String root : roots) {
      Pattern openRe = Pattern.compile('(?is)<(?:[\\w.-]+:)?' + Pattern.quote(root) + '\\b[^>]*>')
      Matcher mo = openRe.matcher(t)
      if (!mo.find()) {
        continue
      }
      int start = mo.start()
      Pattern closeRe = Pattern.compile('(?is)</(?:[\\w.-]+:)?' + Pattern.quote(root) + '\\s*>')
      Matcher mc = closeRe.matcher(t)
      int endExclusive = -1
      while (mc.find()) {
        if (mc.start() >= start) {
          endExclusive = mc.end()
        }
      }
      if (endExclusive > start) {
        return t.substring(start, endExclusive)
      }
    }
    null
  }

  /**
   * Builds a minimal subgraph XML with {@code path} first on {@code <document>} so {@link #DOCUMENT_BLOCK} and
   * {@link #apply} accept it.
   */
  static String buildMinimalSingleDocumentSubgraph(String rootAndDocPath, String contentType, String bodyXml) {
    if (!rootAndDocPath?.trim()) {
      return ''
    }
    String root = rootAndDocPath.trim()
    StringBuilder sb = new StringBuilder()
    sb.append('<').append(SUBGRAPH_ROOT).append(' root="').append(escapeXmlAttr(root)).append('" version="1">\n')
    sb.append('  <').append(DOCUMENT_EL).append(' path="').append(escapeXmlAttr(root)).append('"')
    if (contentType?.trim()) {
      sb.append(' content-type="').append(escapeXmlAttr(contentType.trim())).append('"')
    }
    sb.append('>\n    ').append(wrapCdataBody(bodyXml)).append('\n  </').append(DOCUMENT_EL).append('>\n')
    sb.append('</').append(SUBGRAPH_ROOT).append('>\n')
    return sb.toString()
  }

  /**
   * Salvages {@code TranslateContentItem} inner output: forces {@code path=""} to the requested repo path, collapses
   * accidental multi-{@code document} echoes, and re-serializes with attribute order the strict parser expects.
   *
   * @return normalized subgraph XML, or {@code null} if nothing usable was parsed
   */
  static String coerceAssistantBundleToSingleExpectedPath(String assistantCleaned, String expectedRepoPath) {
    if (!assistantCleaned?.trim() || !expectedRepoPath?.trim()) {
      return null
    }
    String canonical = expectedRepoPath.trim()
    if (!isSafeSiteContentPath(canonical)) {
      return null
    }
    List<Map> docs = parseDocumentsForTests(assistantCleaned)
    if (docs.isEmpty()) {
      docs = parseDocumentsRelaxed(assistantCleaned)
    }
    if (docs.isEmpty()) {
      String bare = extractLikelySingleItemRootXml(assistantCleaned)
      if (bare?.trim()) {
        return buildMinimalSingleDocumentSubgraph(canonical, '', bare.trim())
      }
      return null
    }
    if (docs.size() == 1) {
      Map d = docs.get(0)
      String body = (d.body ?: '').toString()
      if (!body.trim()) {
        return null
      }
      String ct = (d.contentType ?: '').toString().trim()
      return buildMinimalSingleDocumentSubgraph(canonical, ct, body)
    }
    Map chosen = null
    for (Map d : docs) {
      if (d != null && AuthoringPreviewContext.sameRepoPath(d.path, canonical)) {
        chosen = d
        break
      }
    }
    if (chosen == null) {
      log.warn(
        'ContentSubgraphAggregator: inner model returned {} <document> blocks; none matched path {}; picking longest CDATA body',
        docs.size(),
        canonical
      )
      int bestLen = -1
      for (Map d : docs) {
        if (d == null) {
          continue
        }
        int len = ((d.body ?: '').toString()).trim().length()
        if (len > bestLen) {
          bestLen = len
          chosen = d
        }
      }
    }
    if (chosen == null) {
      return null
    }
    String body = (chosen.body ?: '').toString()
    if (!body.trim()) {
      return null
    }
    String ct = (chosen.contentType ?: '').toString().trim()
    return buildMinimalSingleDocumentSubgraph(canonical, ct, body)
  }

  static boolean isSafeSiteContentPath(String path) {
    if (path == null) {
      return false
    }
    String p = path.trim()
    if (!p.startsWith('/site/')) {
      return false
    }
    if (p.contains('..') || p.contains('//')) {
      return false
    }
    if (!p.toLowerCase(Locale.ROOT).endsWith('.xml')) {
      return false
    }
    return true
  }

  static List<String> extractReferencedSiteXmlPaths(String xml) {
    if (!xml?.trim()) {
      return []
    }
    LinkedHashSet<String> out = new LinkedHashSet<>()
    Matcher m = SITE_XML_KEY.matcher(xml.toString())
    while (m.find()) {
      String p = m.group(1).trim()
      if (isSafeSiteContentPath(p)) {
        out.add(p)
      }
    }
    return new ArrayList<>(out)
  }

  private static String readContentTypeId(String xml) {
    if (!xml?.trim()) {
      return null
    }
    def relaxed = (xml =~ /(?is)<(?:[\w.-]+:)?content-type\s*>\s*([^<]+?)\s*<\/(?:[\w.-]+:)?content-type\s*>/)
    if (relaxed.find()) {
      def v = relaxed.group(1).trim()
      return v ?: null
    }
    return null
  }

  private static String readInternalName(String xml) {
    if (!xml?.trim()) {
      return null
    }
    def relaxed = (xml =~ /(?is)<(?:[\w.-]+:)?internal-name\s*>\s*([^<]+?)\s*<\/(?:[\w.-]+:)?internal-name\s*>/)
    if (relaxed.find()) {
      def v = relaxed.group(1).trim()
      return v ?: null
    }
    return null
  }

  private static List<List<String>> suggestPathChunks(List<String> ordered, int chunkSize) {
    int cs = chunkSize > 0 ? Math.min(chunkSize, 100) : 1
    if (cs < 1) {
      cs = 1
    }
    List<List<String>> out = new ArrayList<>()
    for (int i = 0; i < ordered.size(); i += cs) {
      int end = Math.min(i + cs, ordered.size())
      out.add(new ArrayList<>(ordered.subList(i, end)))
    }
    return out
  }

  /**
   * Walks the same {@code <key>} reference closure as {@link #build} but returns only a nested tree plus ordered path lists — **no XML bodies**.
   * Intended for full-page translate/copy work: the model calls {@code GetContent}/{@code WriteContent} per {@code pathChunks} (default chunk size **1** — one repository path per batch) to stay under LLM context limits.
   *
   * @return map with {@code action}, {@code tree}, {@code paths}, {@code pathChunks}, {@code suggestedChunkSize}, {@code documentCount}, flags, {@code nextStep}
   */
  static Map buildTranslationScopeTree(
    StudioToolOperations ops,
    String siteId,
    String rootPath,
    Integer maxItems = null,
    Integer maxDepth = null,
    Integer chunkSize = null
  ) {
    String site = ops.resolveEffectiveSiteId(siteId)
    if (!site?.trim()) {
      throw new IllegalArgumentException('Missing or unresolved siteId')
    }
    String root = (rootPath ?: '').toString().trim()
    if (!isSafeSiteContentPath(root)) {
      throw new IllegalArgumentException("contentPath must be a sandbox XML path under /site/... ending in .xml: ${root}")
    }
    int cap = maxItems != null && maxItems > 0 ? Math.min(maxItems, 2000) : DEFAULT_MAX_ITEMS
    int depthCap = maxDepth != null && maxDepth > 0 ? Math.min(maxDepth, 100) : DEFAULT_MAX_DEPTH
    int cs =
      chunkSize != null && chunkSize > 0 ? Math.min(chunkSize.intValue(), 50) : 1

    LinkedHashSet<String> ordered = new LinkedHashSet<>()
    LinkedHashSet<String> queuedOrDone = new LinkedHashSet<>()
    LinkedHashSet<String> missingReferencedPaths = new LinkedHashSet<>()
    Map<String, Map> nodeByPath = new LinkedHashMap<>()
    Map treeRoot = null
    ArrayDeque<Map> queue = new ArrayDeque<>()
    queue.add([path: root, depth: 0, parentPath: null])
    queuedOrDone.add(root)
    boolean truncated = false
    boolean depthHit = false

    while (!queue.isEmpty() && ordered.size() < cap) {
      Map job = queue.removeFirst()
      String path = job.path.toString()
      int depth = (job.depth instanceof Number) ? ((Number) job.depth).intValue() : 0
      String parentPath = job.parentPath != null ? job.parentPath.toString().trim() : null

      if (depth > depthCap) {
        depthHit = true
        continue
      }

      Map got
      try {
        got = ops.getContent(site, path) as Map
      } catch (Throwable t) {
        if (root == path) {
          throw new IllegalStateException("GetContent failed for root '${path}' in scope walk: ${t.message}", t)
        }
        log.warn('ContentTranslationScopeTree: skip missing/unreadable path {} (referenced from site XML): {}', path, t.message)
        missingReferencedPaths.add(path)
        continue
      }
      String xml = got?.contentXml?.toString()
      if (!xml?.trim()) {
        if (root == path) {
          throw new IllegalStateException("Empty content for root path '${path}'")
        }
        log.warn('ContentTranslationScopeTree: skip empty body for path {}', path)
        missingReferencedPaths.add(path)
        continue
      }

      ordered.add(path)
      Map node = [
        path         : path,
        depth        : depth,
        contentType  : readContentTypeId(xml),
        internalName : readInternalName(xml),
        children     : new ArrayList<Map>(),
      ]
      nodeByPath.put(path, node)
      if (parentPath == null) {
        treeRoot = node
      } else {
        Map parentNode = nodeByPath.get(parentPath)
        if (parentNode != null && parentNode.children instanceof List) {
          ((List) parentNode.children).add(node)
        }
      }

      if (ordered.size() >= cap) {
        truncated = true
        break
      }

      for (String child : extractReferencedSiteXmlPaths(xml)) {
        if (!queuedOrDone.contains(child)) {
          queuedOrDone.add(child)
          queue.add([path: child, depth: depth + 1, parentPath: path])
        }
      }
    }

    List<String> pathsList = new ArrayList<>(ordered)
    List<List<String>> pathChunks = suggestPathChunks(pathsList, cs)

    String nextStep =
      'When **every** path shares the **same instructions**, call **TranslateContentBatch** with **paths** or flattened **pathChunks** (parallel inner completions + **WriteContent** per path; preview updates as paths finish). Otherwise process **pathChunks** in order: **TranslateContentItem** per path with **contentPath** + instructions. Alternatively **GetContent** → edit with main model → **WriteContent**. Preserve well-formed XML (CDATA for HTML; if raw text contains `]]>`, split CDATA or escape). End with **GetPreviewHtml** when a preview URL exists.'
    if (!missingReferencedPaths.isEmpty()) {
      nextStep +=
        ' Some `<key>` targets were skipped — see **missingReferencedPaths**; restore or fix references before claiming full-page coverage.'
    }

    Map out = [
      action               : 'list_content_translation_scope',
      siteId               : site,
      root                 : root,
      tree                 : treeRoot,
      paths                : pathsList,
      pathChunks           : pathChunks,
      suggestedChunkSize     : cs,
      documentCount        : pathsList.size(),
      truncated            : truncated,
      maxDepthReached      : depthHit,
      nextStep             : nextStep,
    ]
    if (!missingReferencedPaths.isEmpty()) {
      out.missingReferencedPaths = new ArrayList<>(missingReferencedPaths)
      out.warning =
        'One or more referenced paths could not be read (see missingReferencedPaths). Visible preview copy may still come from those items.'
    }
    return out
  }

  /**
   * @return map with {@code action}, {@code siteId}, {@code root}, {@code paths}, {@code documentCount},
   * {@code truncated}, {@code maxDepthReached}, {@code subgraphXml}, {@code nextStep}
   */
  static Map build(StudioToolOperations ops, String siteId, String rootPath, Integer maxItems = null, Integer maxDepth = null) {
    String site = ops.resolveEffectiveSiteId(siteId)
    if (!site?.trim()) {
      throw new IllegalArgumentException('Missing or unresolved siteId')
    }
    String root = (rootPath ?: '').toString().trim()
    if (!isSafeSiteContentPath(root)) {
      throw new IllegalArgumentException("contentPath must be a sandbox XML path under /site/... ending in .xml: ${root}")
    }
    int cap = maxItems != null && maxItems > 0 ? Math.min(maxItems, 2000) : DEFAULT_MAX_ITEMS
    int depthCap = maxDepth != null && maxDepth > 0 ? Math.min(maxDepth, 100) : DEFAULT_MAX_DEPTH

    LinkedHashSet<String> ordered = new LinkedHashSet<>()
    LinkedHashSet<String> queuedOrDone = new LinkedHashSet<>()
    LinkedHashSet<String> missingReferencedPaths = new LinkedHashSet<>()
    ArrayDeque<Map> queue = new ArrayDeque<>()
    queue.add([path: root, depth: 0])
    queuedOrDone.add(root)
    Map<String, String> bodiesByPath = new LinkedHashMap<>()
    boolean truncated = false
    boolean depthHit = false

    while (!queue.isEmpty() && ordered.size() < cap) {
      Map job = queue.removeFirst()
      String path = job.path.toString()
      int depth = (job.depth instanceof Number) ? ((Number) job.depth).intValue() : 0
      if (depth > depthCap) {
        depthHit = true
        continue
      }
      Map got
      try {
        got = ops.getContent(site, path) as Map
      } catch (Throwable t) {
        if (root == path) {
          throw new IllegalStateException("GetContent failed for root '${path}' in subgraph walk: ${t.message}", t)
        }
        log.warn('ContentSubgraphAggregator: skip missing/unreadable path {} (referenced from site XML): {}', path, t.message)
        missingReferencedPaths.add(path)
        continue
      }
      String xml = got?.contentXml?.toString()
      if (!xml?.trim()) {
        if (root == path) {
          throw new IllegalStateException("Empty content for root path '${path}'")
        }
        log.warn('ContentSubgraphAggregator: skip empty body for path {}', path)
        missingReferencedPaths.add(path)
        continue
      }
      ordered.add(path)
      bodiesByPath.put(path, xml)
      if (ordered.size() >= cap) {
        truncated = true
        break
      }
      for (String child : extractReferencedSiteXmlPaths(xml)) {
        if (!queuedOrDone.contains(child)) {
          queuedOrDone.add(child)
          queue.add([path: child, depth: depth + 1])
        }
      }
    }

    int est = 256
    for (String x : bodiesByPath.values()) {
      est += x != null ? x.length() : 0
    }
    StringBuilder sb = new StringBuilder(est)
    sb.append('<').append(SUBGRAPH_ROOT)
      .append(' root="').append(escapeXmlAttr(root)).append('"')
      .append(' version="1"')
    if (truncated) {
      sb.append(' truncated="true"')
    }
    if (depthHit) {
      sb.append(' maxDepthReached="true"')
    }
    sb.append('>\n')
    for (String p : ordered) {
      String body = bodiesByPath.get(p)
      String ct = readContentTypeId(body)
      sb.append('  <').append(DOCUMENT_EL).append(' path="').append(escapeXmlAttr(p)).append('"')
      if (ct) {
        sb.append(' content-type="').append(escapeXmlAttr(ct)).append('"')
      }
      sb.append('>\n    ').append(wrapCdataBody(body)).append('\n  </').append(DOCUMENT_EL).append('>\n')
    }
    sb.append('</').append(SUBGRAPH_ROOT).append('>\n')

    String nextStep =
      'Edit field values inside each <document> CDATA (keep <page>/<component> roots and field element names). Persist with **WriteContent** once per **path** (this bundle shape is for internal/server use — chat agents use **ListContentTranslationScope** + per-path **GetContent**/**WriteContent**).'
    if (!missingReferencedPaths.isEmpty()) {
      nextStep +=
        ' **Some `<key>` targets were skipped** (missing in git, empty, or unreadable) — see `missingReferencedPaths`. For a full-page translate or copy change, fix broken references or restore those files in Studio/git, then **GetContent** per path; **do not** claim the whole preview was updated if those items were never loaded.'
    }

    Map out = [
      action        : 'get_content_subgraph',
      siteId        : site,
      root          : root,
      paths         : new ArrayList<>(ordered),
      documentCount : ordered.size(),
      truncated     : truncated,
      maxDepthReached: depthHit,
      subgraphXml   : sb.toString(),
      nextStep      : nextStep,
    ]
    if (!missingReferencedPaths.isEmpty()) {
      out.missingReferencedPaths = new ArrayList<>(missingReferencedPaths)
      out.warning =
        'Subgraph omits one or more referenced paths that could not be read from the repository (see missingReferencedPaths). Preview text may still come from those items untranslated or broken until they are fixed.'
    }
    out
  }

  /**
   * Writes each {@code <document>} from a bundle produced by {@link #build} (same CDATA shape).
   * @param protectedPath when {@code pathProtect} is true, skips this normalized path (form client-apply).
   */
  static Map apply(
    StudioToolOperations ops,
    String siteId,
    String subgraphXml,
    String unlock = 'true',
    String protectedPath = null,
    boolean pathProtect = false
  ) {
    String site = ops.resolveEffectiveSiteId(siteId)
    if (!site?.trim()) {
      throw new IllegalArgumentException('Missing or unresolved siteId')
    }
    if (!subgraphXml?.trim()) {
      throw new IllegalArgumentException('Missing subgraphXml')
    }
    String normProt = pathProtect ? AuthoringPreviewContext.normalizeRepoPath(protectedPath) : ''

    Matcher rm = SUBGRAPH_ROOT_ATTR.matcher(subgraphXml)
    String declaredRoot = rm.find() ? rm.group(1).trim() : ''

    List<Map> writes = new ArrayList<>()
    Matcher dm = DOCUMENT_BLOCK.matcher(subgraphXml)
    while (dm.find()) {
      String path = dm.group(1).trim()
      String body = dm.group(3) ?: ''
      if (!isSafeSiteContentPath(path)) {
        writes.add([path: path, ok: false, message: "Rejected path (must be /site/.../*.xml): ${path}"])
        continue
      }
      if (pathProtect && normProt && AuthoringPreviewContext.sameRepoPath(path, normProt)) {
        writes.add([
          path   : path,
          ok     : false,
          message:
            'Skipped: Studio form client-apply item — put field edits in crafterqFormFieldUpdates or use WriteContent for other paths.',
        ])
        continue
      }
      if (!body.toString().trim()) {
        writes.add([
          path   : path,
          ok     : false,
          message:
            'Empty <document> CDATA body — refusing to wipe this file. Each <document> must wrap the full item XML (non-empty CDATA). Re-fetch bodies with **GetContent** per path before editing.',
        ])
        continue
      }
      try {
        Map w = ops.writeContent(site, path, body, unlock) as Map
        writes.add([path: path, ok: w?.ok != false, message: (w?.message ?: w?.result ?: 'written').toString()])
      } catch (Throwable t) {
        writes.add([path: path, ok: false, message: t.message ?: t.toString()])
      }
    }

    if (writes.isEmpty()) {
      return [
        action : 'write_content_subgraph',
        siteId : site,
        ok     : false,
        message:
          'No <document path="..."> CDATA blocks found. Expected one <document> per path with CDATA wrapping each full file body.',
        results: [],
      ]
    }

    boolean allOk = writes.every { it.ok != false }
    [
      action        : 'write_content_subgraph',
      siteId        : site,
      declaredRoot  : declaredRoot,
      ok            : allOk,
      writtenCount  : writes.count { it.ok != false },
      results       : writes,
      nextStep      : allOk ? 'Verify with GetPreviewHtml when a preview URL is available.' : 'Fix failed paths from results[].message and retry WriteContent per path.',
    ]
  }

  /**
   * When the inner model returns exactly one {@code <document>} but a wrong {@code path="…"} attribute, rewrite the
   * first document tag to the expected repository path so validation and {@link #apply} target the correct file.
   */
  static String rewriteFirstDocumentPathInBundle(String bundleXml, String expectedRepoPath) {
    if (!bundleXml?.trim() || !expectedRepoPath?.trim()) {
      return bundleXml
    }
    String esc = escapeXmlAttr(expectedRepoPath.trim())
    Matcher m =
      Pattern
        .compile('(?is)(<' + DOCUMENT_EL + '\\s+path=")([^"]+)(")')
        .matcher(bundleXml)
    if (!m.find()) {
      return bundleXml
    }
    return m.replaceFirst(Matcher.quoteReplacement(m.group(1) + esc + m.group(3)))
  }

  /** Test hook: parse documents without writing. */
  static List<Map> parseDocumentsForTests(String subgraphXml) {
    List<Map> out = new ArrayList<>()
    Matcher dm = DOCUMENT_BLOCK.matcher(subgraphXml ?: '')
    while (dm.find()) {
      out.add([path: dm.group(1).trim(), contentType: (dm.group(2) ?: '').trim(), body: dm.group(3)])
    }
    out
  }
}
