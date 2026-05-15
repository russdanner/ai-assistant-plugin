package plugins.org.craftercms.aiassistant.tools

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.concurrent.ParallelToolExecutor
import plugins.org.craftercms.aiassistant.content.ContentSubgraphAggregator
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.imagegen.StudioAiImageGenContext
import plugins.org.craftercms.aiassistant.imagegen.StudioAiImageGenerator
import plugins.org.craftercms.aiassistant.imagegen.StudioAiImageGeneratorFactory
import plugins.org.craftercms.aiassistant.mcp.StudioAiMcpClient
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.orchestration.chatcompletions.ChatCompletionsToolWire
import plugins.org.craftercms.aiassistant.playbook.CrafterizingPlaybookLoader
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.rag.ExpertSkillVectorRegistry

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.function.FunctionToolCallback
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collection
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.List
import java.util.Iterator
import java.util.Locale
import java.util.Set
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.function.Function
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Builds Spring AI tool callbacks used by AiOrchestration.
 * <p><strong>Every</strong> {@link FunctionToolCallback} must call {@code .inputSchema(...)} — OpenAI rejects
 * bare {@code Map.class} schemas ("object schema missing properties").</p>
 */
class AiOrchestrationTools {
  private static final Logger log = LoggerFactory.getLogger(AiOrchestrationTools.class)

  /** Slashy-string regex cannot use {@code \\s} in all positions; use {@link Pattern} + single-quoted strings for Studio Groovy. */
  private static final Pattern DISPLAY_TEMPLATE_PAGES = Pattern.compile('(?i)/templates/web/pages/([^./\\s]+)\\.ftl')
  private static final Pattern DISPLAY_TEMPLATE_COMPONENTS = Pattern.compile('(?i)/templates/web/components/([^./\\s]+)\\.ftl')
  private static final Pattern DISPLAY_TEMPLATE_FLAT_WEB = Pattern.compile('(?i)^/templates/web/([^./\\s]+)\\.ftl$')

  private static DocumentBuilderFactory newSecureDocumentBuilderFactory() {
    def factory = DocumentBuilderFactory.newInstance()
    try {
      factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    } catch (Throwable ignored) {}
    try {
      factory.setFeature('http://xml.org/sax/features/external-general-entities', false)
      factory.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
    } catch (Throwable ignored) {}
    factory.setXIncludeAware(false)
    factory.setExpandEntityReferences(false)
    return factory
  }

  private static Document parseXmlDocument(String xml) {
    if (!xml?.trim()) return null
    def factory = newSecureDocumentBuilderFactory()
    return factory.newDocumentBuilder().parse(
      new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
  }

  private static String xmlElementLocalName(String nodeName) {
    if (nodeName == null) return null
    int i = nodeName.indexOf(':')
    return (i >= 0) ? nodeName.substring(i + 1) : nodeName
  }

  /**
   * Depth-first: first element whose local name matches (e.g. {@code content-type} with any namespace prefix).
   */
  private static String findFirstElementTextByLocalName(Node node, String wantedLocal, boolean ignoreCase) {
    if (node == null) return null
    if (node.nodeType == Node.ELEMENT_NODE) {
      def ln = xmlElementLocalName(node.nodeName)
      boolean match = ignoreCase ? wantedLocal.equalsIgnoreCase(ln) : wantedLocal.equals(ln)
      if (match) {
        def t = node.textContent?.trim()
        if (t) return t
      }
      Node ch = node.firstChild
      while (ch != null) {
        def r = findFirstElementTextByLocalName(ch, wantedLocal, ignoreCase)
        if (r) return r
        ch = ch.nextSibling
      }
    }
    return null
  }

  /**
   * Infer {@code /page/foo} or {@code /component/bar} from {@code <display-template>} when present.
   */
  private static String extractContentTypeFromDisplayTemplate(String xml) {
    if (!xml?.trim()) return null
    def m = (xml =~ /(?is)<(?:[\w.-]+:)?display-template\s*>\s*([^<]+?)\s*<\/(?:[\w.-]+:)?display-template\s*>/)
    if (!m.find()) return null
    def t = m.group(1).trim()
    def p = (t =~ DISPLAY_TEMPLATE_PAGES)
    if (p.find()) return "/page/${p.group(1)}"
    def c = (t =~ DISPLAY_TEMPLATE_COMPONENTS)
    if (c.find()) return "/component/${c.group(1)}"
    // Blueprints often use /templates/web/<name>.ftl (e.g. entry.ftl) — infer type from root element.
    def flat = (t =~ DISPLAY_TEMPLATE_FLAT_WEB)
    if (flat.find()) {
      def base = flat.group(1)
      if ((xml =~ /(?is)<\s*page(?:\s|>)/).find()) return "/page/${base}"
      if ((xml =~ /(?is)<\s*component(?:\s|>)/).find()) return "/component/${base}"
    }
    return null
  }

  /**
   * Reads content type id from page/component XML: {@code <content-type>} (any prefix / case), else {@code display-template} path heuristic.
   */
  private static String extractContentTypeIdFromItemXml(String xml) {
    if (!xml?.trim()) return null
    def relaxed = (xml =~ /(?is)<(?:[\w.-]+:)?content-type\s*>\s*([^<]+?)\s*<\/(?:[\w.-]+:)?content-type\s*>/)
    if (relaxed.find()) {
      def v = relaxed.group(1).trim()
      if (v) return v
    }
    try {
      def doc = parseXmlDocument(xml)
      def root = doc?.documentElement
      def fromDom = findFirstElementTextByLocalName(root, 'content-type', true)
      if (fromDom) return fromDom
    } catch (Throwable e) {
      log.debug('extractContentTypeIdFromItemXml DOM pass failed: {}', e.toString())
    }
    def fromTemplate = extractContentTypeFromDisplayTemplate(xml)
    if (fromTemplate) {
      log.debug('extractContentTypeIdFromItemXml: using display-template heuristic -> {}', fromTemplate)
      return fromTemplate
    }
    return null
  }

  /**
   * Sandbox repository path from CMS tool arguments. Prefer {@code path}; also accept {@code contentPath}
   * (same key as {@code update_content} / authoring context) and a few other aliases models send.
   */
  private static String repoPathFromToolInput(Map input) {
    if (input == null) return ''
    def s = input.path?.toString()?.trim()
    if (s) return s
    s = input.contentPath?.toString()?.trim()
    if (s) return s
    s = input.repositoryPath?.toString()?.trim()
    if (s) return s
    s = input.repository_path?.toString()?.trim()
    if (s) return s
    s = input.repoPath?.toString()?.trim()
    if (s) return s
    s = input.repo_path?.toString()?.trim()
    if (s) return s
    s = input.filePath?.toString()?.trim()
    if (s) return s
    input.file_path?.toString()?.trim() ?: ''
  }

  /**
   * Collects {@code <field><id>...</id>} values from form-definition.xml for a compact hint to the model.
   * <p>Uses JDK {@link DocumentBuilderFactory} — {@code groovy.util.XmlSlurper} is not on Studio plugin script compile classpath.</p>
   */
  private static List<String> extractFormFieldIdsFromFormDefinitionXml(String formXml) {
    if (!formXml?.trim()) return []
    try {
      def factory = DocumentBuilderFactory.newInstance()
      try {
        factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
      } catch (Throwable ignored) {}
      try {
        factory.setFeature('http://xml.org/sax/features/external-general-entities', false)
        factory.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
      } catch (Throwable ignored) {}
      factory.setXIncludeAware(false)
      factory.setExpandEntityReferences(false)
      def doc = factory.newDocumentBuilder().parse(
        new ByteArrayInputStream(formXml.getBytes(StandardCharsets.UTF_8)))
      def fields = doc.getElementsByTagName('field')
      def ids = new LinkedHashSet<String>()
      for (int i = 0; i < fields.length; i++) {
        def fieldEl = fields.item(i) as Element
        def idNodes = fieldEl.getElementsByTagName('id')
        if (idNodes.length > 0) {
          def id = idNodes.item(0).textContent?.trim()
          if (id) ids.add(id)
        }
      }
      return ids.toList().sort()
    } catch (Throwable t) {
      log.debug('extractFormFieldIdsFromFormDefinitionXml failed: {}', t.toString())
      return []
    }
  }

  /**
   * OpenAI returns 400 if tool {@code parameters} are not valid JSON Schema objects.
   * {@code FunctionToolCallback} with {@code Map.class} alone often produces schemas the API rejects.
   */
  private static final String SCHEMA_GET_CONTENT =
    '{"type":"object","properties":{"siteId":{"type":"string","description":"Studio site id"},"path":{"type":"string","description":"Repository path starting with /"},"contentPath":{"type":"string","description":"Same as path; use whichever matches your context (e.g. after update_content)."},"commitId":{"type":"string","description":"Git commit id or ref to read (optional). Omit or use HEAD for current sandbox file; set only when comparing or inspecting history."}},"required":["siteId"]}'
  private static final String SCHEMA_GET_CONTENT_TYPE =
    '{"type":"object","properties":{"siteId":{"type":"string"},"contentPath":{"type":"string","description":"Repository path to page/component XML (e.g. /site/website/index.xml). Preferred: server reads exact <content-type> from this file — avoids guessing /page/index from filename."},"contentTypeId":{"type":"string","description":"Exact /page/... or /component/... from the item XML <content-type> element only. Never infer from path (index.xml is NOT /page/index)."}},"required":["siteId"]}'
  private static final String SCHEMA_LIST_STUDIO_CONTENT_TYPES =
    '{"type":"object","properties":{"siteId":{"type":"string","description":"Studio site id"},"searchable":{"type":"boolean","description":"If true, pass searchable=true to Studio getAllContentTypes when listing all types (site-dependent). Default false."},"contentPath":{"type":"string","description":"Optional repository path (e.g. open preview item or target folder). When set, lists content types **allowed** for creating items under that path’s folder (via Studio getAllowedContentTypesForPath)."}},"required":["siteId"]}'
  private static final String SCHEMA_GET_CONTENT_VERSION_HISTORY =
    '{"type":"object","properties":{"siteId":{"type":"string","description":"Studio site id"},"path":{"type":"string","description":"Repository path starting with /"},"contentPath":{"type":"string","description":"Same as path"}},"required":["siteId"]}'
  private static final String SCHEMA_GET_PREVIEW_HTML =
    '{"type":"object","properties":{"url":{"type":"string","description":"Absolute http(s) URL of the preview page to fetch (e.g. current preview URL from authoring context)."},"previewUrl":{"type":"string","description":"Alias for url."},"previewToken":{"type":"string","description":"Value of the Studio crafterPreview cookie (often starts with CCE-V1). Omit if the chat request already sent previewToken from the UI."},"siteId":{"type":"string","description":"Optional — when the URL has no crafterSite= query param, it is appended from this value or the active site."}},"required":[]}'
  private static final String SCHEMA_FETCH_HTTP_URL =
    '{"type":"object","properties":{"url":{"type":"string","description":"Absolute http(s) URL to GET (reference HTML/CSS/JSON/text). Private IPs, loopback, and metadata endpoints are blocked; each redirect target is re-validated."},"maxChars":{"type":"integer","description":"Optional cap on returned body size; still bounded by Studio JVM aiassistant.httpFetch.maxChars (default 400000)."}},"required":["url"]}'
  private static final String SCHEMA_QUERY_EXPERT_GUIDANCE =
    '{"type":"object","properties":{"skillId":{"type":"string","description":"Expert skill id from the system message expert skills table (es_ prefix)."},"query":{"type":"string","description":"Question to retrieve relevant markdown chunks for."},"topK":{"type":"integer","description":"Max chunks (1–20, default 8)."}},"required":["skillId","query"]}'
  private static final String SCHEMA_WRITE_CONTENT =
    '{"type":"object","properties":{"siteId":{"type":"string"},"path":{"type":"string","description":"Repository path starting with /"},"contentPath":{"type":"string","description":"Same as path; use either."},"contentXml":{"type":"string","description":"Complete file body. For /site/.../*.xml items: full <page> or <component> document preserving existing field element names from the content type; never replace with an unrelated XML schema."},"unlock":{"type":"string","description":"true or false"}},"required":["siteId","contentXml"]}'
  private static final String SCHEMA_LIST_PAGES =
    '{"type":"object","properties":{"siteId":{"type":"string"},"size":{"type":"integer","description":"max items, default 1000"}},"required":["siteId"]}'
  private static final String SCHEMA_LIST_CONTENT_TRANSLATION_SCOPE =
    '{"type":"object","properties":{"siteId":{"type":"string"},"contentPath":{"type":"string","description":"Root page or component XML under /site/... ending in .xml"},"path":{"type":"string","description":"Alias for contentPath"},"chunkSize":{"type":"integer","description":"Paths per batch for GetContent/WriteContent rounds (default 1 — one item at a time; max 50)"},"maxItems":{"type":"integer","description":"Optional max items in scope (default 300, cap 2000)"},"maxDepth":{"type":"integer","description":"Optional max reference depth from root (default 40, cap 100)"}},"required":["siteId"]}'
  private static final String SCHEMA_CRAFTERIZING_PLAYBOOK =
    '{"type":"object","properties":{"topic":{"type":"string","description":"Optional focus keyword for future use; full playbook is returned regardless."}}}'
  /** Shared shape for authoring helpers (update_*, analyze, publish, revert). */
  private static final String SCHEMA_CMS_LOOSE =
    '{"type":"object","properties":{"siteId":{"type":"string"},"site_id":{"type":"string"},"path":{"type":"string"},"contentPath":{"type":"string"},"templatePath":{"type":"string"},"contentType":{"type":"string"},"contentTypeId":{"type":"string"},"instructions":{"type":"string"},"date":{"type":"string"},"publishingTarget":{"type":"string"},"revertType":{"type":"string"},"version":{"type":"string","description":"Studio ItemVersion versionNumber from GetContentVersionHistory"},"revertToPrevious":{"type":"boolean","description":"If true, revert to the immediate prior revertible version (no version string needed)"}}}'
  private static final String SCHEMA_GENERATE_IMAGE =
    '{"type":"object","additionalProperties":false,"properties":{"prompt":{"type":"string","description":"Description of the image to generate"},"size":{"type":"string","description":"Optional size or aspect preset; see OpenAI Images API (GPT image: 1024x1024, 1536x1024, 1024x1536, auto, etc.)"},"quality":{"type":"string","description":"Optional quality for GPT image models: low, medium, high, auto"},"model":{"type":"string","description":"Optional override of the configured OpenAI image model. Do not pass response_format (rejected by the GPT image Images API)."}},"required":["prompt"]}'
  /** One-shot chat completion (no further function tools on that inner request). Invoked only when the main agent calls this tool. */
  private static final String SCHEMA_GENERATE_TEXT_NO_TOOLS =
    '{"type":"object","properties":{"userPrompt":{"type":"string","description":"Full user/task text for the inner model (what to write, format, constraints)."},"prompt":{"type":"string","description":"Alias for userPrompt."},"systemInstructions":{"type":"string","description":"Optional system message for this inner call only (role, output shape, tone)."},"system":{"type":"string","description":"Alias for systemInstructions."},"maxOutTokens":{"type":"integer","description":"Max completion tokens for this inner call (256–8192; server may clamp per model)."},"model":{"type":"string","description":"Optional OpenAI chat model id for this inner call only; default matches the agent chat model family."},"llmModel":{"type":"string","description":"Alias for model."},"readTimeoutMs":{"type":"integer","description":"HTTP read timeout ms (60000–600000)."}},"required":[]}'
  private static final String SCHEMA_TRANSFORM_CONTENT_SUBGRAPH =
    '{"type":"object","properties":{"siteId":{"type":"string"},"contentPath":{"type":"string","description":"Root page or component XML under /site/... ending in .xml"},"path":{"type":"string","description":"Alias for contentPath"},"instructions":{"type":"string","description":"Task for the worker model (e.g. translate all author-visible copy to Arabic ar-SA; preserve XML structure)"},"writeResults":{"type":"boolean","description":"If true (default), WriteContent each document after a valid LLM bundle"},"maxItems":{"type":"integer","description":"Max documents in walk (default 300, cap 2000)"},"maxDepth":{"type":"integer","description":"Max reference depth (default 40, cap 100)"},"unlock":{"type":"string","description":"Unlock flag for WriteContent (default true)"},"llmModel":{"type":"string","description":"OpenAI chat model for the bundled inner completion only. Omit to use a smaller model in the same family as main chat (e.g. gpt-5-* → gpt-5-nano, gpt-4o → gpt-4o-mini). Pass explicitly to override. Alias: model"},"readTimeoutMs":{"type":"integer","description":"OpenAI HTTP read timeout ms (default 600000)"}},"required":["siteId","instructions"]}'

  /** Single-path inner LLM + write; {@link #ENABLE_TRANSFORM_CONTENT_SUBGRAPH_BULK} keeps the multi-document tool off by default. */
  private static final String SCHEMA_TRANSLATE_CONTENT_ITEM =
    '{"type":"object","properties":{"siteId":{"type":"string"},"contentPath":{"type":"string","description":"One page or component XML under /site/... ending in .xml"},"path":{"type":"string","description":"Alias for contentPath"},"instructions":{"type":"string","description":"Same task for every item when translating a page tree (e.g. translate author-visible copy to Arabic ar-SA; preserve XML)"},"writeResults":{"type":"boolean","description":"If true (default), WriteContent after a valid LLM bundle"},"unlock":{"type":"string","description":"Unlock flag for WriteContent (default true)"},"llmModel":{"type":"string","description":"OpenAI chat model for this item only (inner completion). Omit for server default smaller model (e.g. gpt-4o-mini). Alias: model"},"readTimeoutMs":{"type":"integer","description":"OpenAI HTTP read timeout ms (default 600000)"}},"required":["siteId","instructions"]}'

  private static final String SCHEMA_TRANSLATE_CONTENT_BATCH =
    '{"type":"object","properties":{"siteId":{"type":"string"},"instructions":{"type":"string","description":"Same instruction for every path (e.g. translate author-visible copy to French fr-FR)"},"paths":{"type":"array","items":{"type":"string"},"description":"List of /site/.../*.xml paths"},"contentPaths":{"type":"array","items":{"type":"string"},"description":"Alias for paths"},"pathChunks":{"type":"array","description":"Optional: **pathChunks** from ListContentTranslationScope — same shape as that tool (outer array of chunks; each chunk is an array of path strings). Server flattens to paths.","items":{"type":"array","items":{"type":"string","description":"/site/.../*.xml repository path"}}},"maxConcurrency":{"type":"integer","description":"Parallel workers for this batch only (default from agent ui.xml translateBatchConcurrency, else 25; hard cap 64)"},"writeResults":{"type":"boolean"},"unlock":{"type":"string"},"llmModel":{"type":"string"},"model":{"type":"string"},"readTimeoutMs":{"type":"integer"}},"required":["siteId","instructions"]}'

  /** Site sandbox Groovy under {@code config/studio/scripts/aiassistant/user-tools/} (manifest {@code registry.json}). */
  private static final String SCHEMA_INVOKE_SITE_USER_TOOL =
    '{"type":"object","properties":{"toolId":{"type":"string","description":"Registered id from the site registry.json tools[] array."},"args":{"type":"object","description":"Optional map passed to the script as binding variable args.","additionalProperties":true}},"required":["toolId"]}'

  private static final int TRANSLATE_BATCH_MAX_PATHS = 100

  /** When false, {@code TransformContentSubgraph} / {@code GetContentSubgraph} are not registered — use {@code ListContentTranslationScope} + {@code TranslateContentBatch} or {@code TranslateContentItem} per path. */
  private static final boolean ENABLE_TRANSFORM_CONTENT_SUBGRAPH_BULK = false

  private static final int TRANSFORM_SUBGRAPH_MAX_CHARS = 280_000

  private static final int TRANSFORM_MAX_OUT_TOKENS = 32_768

  /** Strip optional ``` / ```xml fences from model output. */
  static String stripOptionalMarkdownFences(String raw) {
    if (raw == null) {
      return ''
    }
    String t = raw.toString().trim()
    if (!t.startsWith('```')) {
      return t
    }
    int firstNl = t.indexOf('\n')
    if (firstNl > 0) {
      t = t.substring(firstNl + 1)
    } else {
      t = t.substring(3).trim()
      int n2 = t.indexOf('\n')
      if (n2 >= 0) {
        t = t.substring(n2 + 1)
      }
    }
    if (t.endsWith('```')) {
      t = t.substring(0, t.length() - 3).trim()
    }
    int lastFence = t.lastIndexOf('```')
    if (lastFence >= 0) {
      t = t.substring(0, lastFence).trim()
    }
    return t
  }

  /**
   * Logs everything the subgraph tool uses so operators can see payload weight (slow OpenAI vs slow Studio writes).
   * Bundle XML is logged via {@link AiHttpProxy#elideForLog} (head + tail); instructions up to {@code maxInstrLog} chars.
   */
  private static void logTransformContentSubgraphPayload(
    Map input,
    String siteId,
    String contentPath,
    String instructions,
    boolean writeResults,
    String unlock,
    Integer maxItems,
    Integer maxDepth,
    String llmModel,
    int readTimeoutMs,
    Map built,
    String subgraphXml,
    String sys,
    String userBody,
    int maxInstrLog = 50_000,
    int maxBundleLogPreview = 36_000
  ) {
    try {
      def wire = [
        siteId       : siteId,
        contentPath  : contentPath,
        writeResults : writeResults,
        unlock       : unlock,
        maxItems     : maxItems,
        maxDepth     : maxDepth,
        llmModel     : llmModel,
        readTimeoutMs: readTimeoutMs,
      ]
      log.debug(
        'TransformContentSubgraph DIAG resolvedArgs={}',
        JsonOutput.prettyPrint(JsonOutput.toJson(wire))
      )
      def rawJson = ''
      try {
        rawJson = JsonOutput.toJson(input ?: [:])
      } catch (Throwable je) {
        rawJson = '(input not serializable: ' + (je.message ?: je.toString()) + ')'
      }
      log.debug(
        'TransformContentSubgraph DIAG rawToolInputJsonChars={} rawToolInputPreview=\n{}',
        rawJson.length(),
        AiHttpProxy.elideForLog(rawJson, 12_000)
      )
      def ins = (instructions ?: '').toString()
      log.debug(
        'TransformContentSubgraph DIAG instructionsChars={} instructionsText=\n{}',
        ins.length(),
        ins.length() > maxInstrLog ? (ins.substring(0, maxInstrLog) + '\n… [+' + (ins.length() - maxInstrLog) + ' chars truncated for log]') : ins
      )
      def xml = (subgraphXml ?: '').toString()
      int xlen = xml.length()
      log.debug(
        'TransformContentSubgraph DIAG bundleXmlChars={} documentCount={} root={} truncatedFromWalk={} maxDepthReachedWalk={}',
        xlen,
        built?.documentCount,
        built?.root,
        built?.truncated,
        built?.maxDepthReached
      )
      def plist = built?.paths
      if (plist instanceof List) {
        log.debug('TransformContentSubgraph DIAG paths[{}]=\n{}', ((List) plist).size(), JsonOutput.prettyPrint(JsonOutput.toJson(plist)))
      } else {
        log.debug('TransformContentSubgraph DIAG paths={}', String.valueOf(plist))
      }
      log.debug(
        'TransformContentSubgraph DIAG innerChatCompletionsUserMessage: systemPromptChars={} userMessageTotalChars={} (instructions+headers+bundle)',
        (sys ?: '').length(),
        (userBody ?: '').length()
      )
      log.debug(
        'TransformContentSubgraph DIAG bundleXmlPreview (elide max ~{} chars)=\n{}',
        maxBundleLogPreview,
        xlen == 0 ? '(empty)' : AiHttpProxy.elideForLog(xml, maxBundleLogPreview)
      )
    } catch (Throwable t) {
      log.warn('TransformContentSubgraph DIAG logging failed: {}', t.message)
    }
  }

  private static String cqDetectItemRootKind(String xml) {
    if (!xml?.trim()) {
      return null
    }
    Matcher m = Pattern.compile('(?is)<\\s*(?:[\\w.-]+:)?(page|component)\\b').matcher(xml.trim())
    return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null
  }

  /** Worker-thread results must always be {@link Map}s for batch aggregation (classloader / Groovy edge cases). */
  private static Map coerceTranslateBatchFutureRow(Object row, String pathHint) {
    if (row == null) {
      return [
        path : pathHint,
        error: true,
        message: 'Translate cell returned null (internal pipeline error).',
      ]
    }
    if (row instanceof Map) {
      return (Map) row
    }
    return [
      path : pathHint,
      error: true,
      message: 'Translate cell returned unexpected type: ' + row.getClass().name,
    ]
  }

  /**
   * {@link TranslateContentItem} with {@code maxItems=1}: send **raw** repository XML to the inner model and write the
   * reply directly to {@code contentPath} — no {@code <document>} / subgraph round-trip.
   */
  private static Map runTranslateContentItemRawInner(
    StudioToolOperations ops,
    Map built,
    Map input,
    String siteId,
    String contentPath,
    String instructions,
    String apiKey,
    String llmModel,
    int innerMaxOutTokens,
    int readTimeoutMs,
    boolean writeResults,
    String unlock,
    String normProtected,
    boolean pathProtect,
    String actionTag,
    String diag
  ) {
    String normProt = pathProtect ? AuthoringPreviewContext.normalizeRepoPath(normProtected) : ''
    if (pathProtect && normProt && AuthoringPreviewContext.sameRepoPath(contentPath, normProt)) {
      return [
        error      : true,
        action     : actionTag,
        message    :
          'Skipped: Studio form client-apply item — put field edits in aiassistantFormFieldUpdates or use WriteContent for other paths.',
        siteId     : siteId,
        contentPath: contentPath,
        paths      : [contentPath],
      ]
    }
    Map gotItem
    try {
      gotItem = ops.getContent(siteId, contentPath) as Map
    } catch (Throwable t) {
      return [
        error      : true,
        action     : actionTag,
        message    : 'GetContent failed before inner translate: ' + (t.message ?: t.toString()),
        siteId     : siteId,
        contentPath: contentPath,
      ]
    }
    String itemXml = gotItem?.contentXml?.toString() ?: ''
    if (!itemXml.trim()) {
      return [
        error      : true,
        action     : actionTag,
        message    : 'Empty repository XML for path ' + contentPath,
        siteId     : siteId,
        contentPath: contentPath,
      ]
    }
    if (itemXml.length() > TRANSFORM_SUBGRAPH_MAX_CHARS) {
      return [
        error      : true,
        action     : actionTag,
        message    :
          "Item XML is ${itemXml.length()} characters (limit ${TRANSFORM_SUBGRAPH_MAX_CHARS}). Narrow scope or use GetContent/WriteContent.",
        siteId     : siteId,
        contentPath: contentPath,
      ]
    }
    String origKind = cqDetectItemRootKind(itemXml)
    String sys = ToolPrompts.getTRANSLATE_CONTENT_ITEM_INNER_SYSTEM_RAW()
    String userBody =
      '## Instructions\n' +
      instructions +
      '\n\n## Repository path (informational — server writes here)\n' +
      contentPath +
      '\n\n## Item XML\n' +
      itemXml +
      '\n' +
      ToolPrompts.getTRANSLATE_CONTENT_ITEM_INNER_USER_APPENDIX_RAW()
    logTransformContentSubgraphPayload(
      input,
      siteId,
      contentPath,
      instructions,
      writeResults,
      unlock,
      Integer.valueOf(1),
      Integer.valueOf(0),
      llmModel,
      readTimeoutMs,
      built,
      itemXml,
      sys,
      userBody
    )
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped before the inner LLM call.',
        siteId   : siteId,
        paths    : [contentPath],
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_await_inner_openai_raw_item chars=${itemXml.length()}")
    long tOpenAi = System.nanoTime()
    String assistantXml =
      AiOrchestration.openAiSimpleCompletionAssistantText(
        apiKey,
        llmModel,
        sys,
        userBody,
        innerMaxOutTokens,
        readTimeoutMs,
        diag
      )
    log.debug(
      '{} DIAG innerOpenAiRawItem wallMs={} assistantChars={}',
      diag,
      (System.nanoTime() - tOpenAi) / 1_000_000L,
      (assistantXml ?: '').length()
    )
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped after the inner LLM returned.',
        siteId   : siteId,
        paths    : [contentPath],
      ]
    }
    String cleaned = stripOptionalMarkdownFences(assistantXml)
    String outItem = ContentSubgraphAggregator.extractLikelySingleItemRootXml(cleaned)
    if (!outItem?.trim()) {
      String t = cleaned.trim()
      if (t.startsWith('<') && (t.contains('<page') || t.contains('<component'))) {
        outItem = t
      }
    }
    if (!outItem?.trim()) {
      return [
        error           : true,
        action          : actionTag,
        message         :
          'Inner model did not return a full <page> or <component> item. Reply with only the transformed item root element (no wrappers).',
        assistantPreview: AiHttpProxy.elideForLog(cleaned, 4000),
        siteId          : siteId,
        contentPath     : contentPath,
      ]
    }
    String newKind = cqDetectItemRootKind(outItem)
    if (origKind && newKind && !origKind.equals(newKind)) {
      return [
        error           : true,
        action          : actionTag,
        message         :
          'Inner model changed root element from <' + origKind + '> to <' + newKind + '> — refusing to write.',
        assistantPreview: AiHttpProxy.elideForLog(outItem, 4000),
        siteId          : siteId,
        contentPath     : contentPath,
      ]
    }
    Map out = [
      action              : actionTag,
      siteId              : siteId,
      root                : contentPath,
      paths               : [contentPath],
      documentCount       : 1,
      writeResults        : writeResults,
      llmModel            : llmModel,
      truncatedFromWalk   : built?.truncated,
      maxDepthReachedWalk : built?.maxDepthReached,
    ]
    if (!writeResults) {
      out.transformedItemPreview = AiHttpProxy.elideForLog(outItem, 12_000)
      out.nextStep = 'Set writeResults:true to persist.'
      return out
    }
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped before Studio write.',
        siteId   : siteId,
        paths    : [contentPath],
      ]
    }
    Map w
    try {
      w = ops.writeContent(siteId, contentPath, outItem.trim(), unlock) as Map
    } catch (Throwable t) {
      return [
        error      : true,
        action     : actionTag,
        message    : 'WriteContent failed: ' + (t.message ?: t.toString()),
        siteId     : siteId,
        contentPath: contentPath,
      ]
    }
    boolean ok = w?.ok != false
    out.ok = ok
    out.writtenCount = ok ? Integer.valueOf(1) : Integer.valueOf(0)
    out.declaredRoot = contentPath
    out.results =
      [[path: contentPath, ok: ok, message: (w?.message ?: w?.result ?: 'written').toString()]]
    out.nextStep =
      ok ? 'Verify with GetPreviewHtml when a preview URL is available.' : (w?.message ?: 'write failed').toString()
    return out
  }

  /**
   * Loads subgraph via {@link ContentSubgraphAggregator#build}, one non-streaming OpenAI completion (bundle + instructions only), optional {@link ContentSubgraphAggregator#apply}.
   * Inner completion model when {@code llmModel}/{@code model} omitted: {@link AiOrchestration#openAiTransformSubgraphDefaultInnerModel(String)} (smaller model in same family as main chat).
   * @param toolDiagKey prefix for {@link AiOrchestration#aiAssistantToolWorkerDiagPhase} and logs ({@code TransformContentSubgraph} vs {@code TranslateContentItem})
   * @param resultAction {@code action} field in returned maps
   */
  static Map runTransformContentSubgraph(
    StudioToolOperations ops,
    Map rawInput,
    String apiKey,
    String defaultChatModel,
    String normProtected,
    boolean pathProtect,
    String toolDiagKey = 'TransformContentSubgraph',
    String resultAction = 'transform_content_subgraph'
  ) {
    String diag = (toolDiagKey ?: 'TransformContentSubgraph').toString().trim() ?: 'TransformContentSubgraph'
    String actionTag = (resultAction ?: 'transform_content_subgraph').toString().trim() ?: 'transform_content_subgraph'
    def input = (rawInput != null) ? rawInput : [:]
    def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) {
      throw new IllegalArgumentException('Missing required field: siteId')
    }
    def contentPath = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
    if (!contentPath) {
      throw new IllegalArgumentException('Missing required field: contentPath (or path)')
    }
    def instructions = input?.instructions?.toString()?.trim()
    if (!instructions) {
      throw new IllegalArgumentException('Missing required field: instructions')
    }
    boolean writeResults = true
    if (input.containsKey('writeResults')) {
      def wr = input.writeResults
      if (wr instanceof Boolean) {
        writeResults = (Boolean) wr
      } else if (wr != null) {
        writeResults = !('false'.equalsIgnoreCase(wr.toString()) || '0' == wr.toString())
      }
    }
    String unlock = input?.unlock?.toString()?.trim() ?: 'true'
    Integer maxItems = null
    Integer maxDepth = null
    try {
      if (input?.maxItems != null) {
        maxItems =
          (input.maxItems instanceof Number) ? ((Number) input.maxItems).intValue() : Integer.parseInt(
            input.maxItems.toString().trim())
      }
    } catch (Throwable ignored) {
      maxItems = null
    }
    try {
      if (input?.maxDepth != null) {
        maxDepth =
          (input.maxDepth instanceof Number) ? ((Number) input.maxDepth).intValue() : Integer.parseInt(
            input.maxDepth.toString().trim())
      }
    } catch (Throwable ignored) {
      maxDepth = null
    }
    String explicitInnerLlm = input?.llmModel?.toString()?.trim() ?: input?.model?.toString()?.trim()
    String llmModel = explicitInnerLlm ?: AiOrchestration.openAiTransformSubgraphDefaultInnerModel(defaultChatModel)
    int innerMaxOutTokens =
      'TranslateContentItem'.equals(diag)
        ? AiOrchestration.resolveTranslateContentItemMaxOutTokens()
        : TRANSFORM_MAX_OUT_TOKENS
    if (!explicitInnerLlm) {
      log.debug(
        '{}: inner llmModel default {} (same-family inner completion; pass llmModel to override). Main chat model: {} innerMaxOutTokens={}',
        diag,
        llmModel,
        (defaultChatModel ?: '').trim() ?: '(unset)',
        innerMaxOutTokens
      )
    }
    int readTimeoutMs = 600_000
    try {
      def rtm = input?.readTimeoutMs
      if (rtm instanceof Number) {
        readTimeoutMs = Math.max(60_000, ((Number) rtm).intValue())
      } else if (rtm != null) {
        readTimeoutMs = Math.max(60_000, Integer.parseInt(rtm.toString().trim()))
      }
    } catch (Throwable ignored) {
      readTimeoutMs = 600_000
    }
    if (!apiKey?.trim()) {
      return [
        error  : true,
        action : actionTag,
        message: 'OpenAI API key not configured for this agent',
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase(
      "${diag}_ContentSubgraphAggregator_build site=${siteId} path=${contentPath}"
    )
    Map built = ContentSubgraphAggregator.build(ops, siteId, contentPath, maxItems, maxDepth) as Map
    if ('TranslateContentItem'.equals(diag)) {
      log.debug(
        '{} DIAG perRepositoryXmlInnerLlm path={} documentCountInBundle={} truncatedWalk={}',
        diag,
        contentPath,
        built?.documentCount,
        built?.truncated
      )
    }
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped after the subgraph walk; inner LLM and writes were not run.',
        siteId   : siteId,
        contentPath: contentPath,
      ]
    }
    String subgraphXml = built?.subgraphXml?.toString() ?: ''
    if (!subgraphXml.trim()) {
      throw new IllegalStateException('ContentSubgraphAggregator.build returned empty subgraphXml')
    }
    if (subgraphXml.length() > TRANSFORM_SUBGRAPH_MAX_CHARS) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase(
        "${diag}_exit_bundle_too_large chars=${subgraphXml.length()}"
      )
      return [
        error        : true,
        action       : actionTag,
        message      :
          "Subgraph bundle is ${subgraphXml.length()} characters (limit ${TRANSFORM_SUBGRAPH_MAX_CHARS}). Narrow maxItems/maxDepth or use ListContentTranslationScope + per-path GetContent/WriteContent.",
        documentCount: built?.documentCount,
        paths        : built?.paths,
        truncated    : built?.truncated,
      ]
    }
    if ('TranslateContentItem'.equals(diag) && maxItems != null && maxItems.intValue() == 1) {
      Object dcObj = built?.documentCount
      int docCount = (dcObj instanceof Number) ? ((Number) dcObj).intValue() : 0
      if (docCount == 1) {
        return runTranslateContentItemRawInner(
          ops,
          built,
          input,
          siteId,
          contentPath,
          instructions,
          apiKey,
          llmModel,
          innerMaxOutTokens,
          readTimeoutMs,
          writeResults,
          unlock,
          normProtected,
          pathProtect,
          actionTag,
          diag
        )
      }
    }
    List<String> origPaths = (built?.paths instanceof List) ? new ArrayList<>((List) built.paths) : []
    LinkedHashSet<String> origSet = new LinkedHashSet<>()
    for (String op : origPaths) {
      def n = AuthoringPreviewContext.normalizeRepoPath(op)
      if (n) {
        origSet.add(n)
      }
    }
    String sys =
      'TranslateContentItem'.equals(diag)
        ? ToolPrompts.getTRANSLATE_CONTENT_ITEM_INNER_SYSTEM()
        : ToolPrompts.getTRANSFORM_CONTENT_SUBGRAPH_SYSTEM()
    String userBody = '## Instructions\n' + instructions + '\n\n## Content bundle (XML)\n' + subgraphXml
    if ('TranslateContentItem'.equals(diag)) {
      userBody = userBody + '\n' + ToolPrompts.getTRANSLATE_CONTENT_ITEM_INNER_USER_APPENDIX()
      String pathLock = (contentPath ?: '').toString().trim()
      if (pathLock) {
        userBody =
          userBody +
          '\n## Path lock (server)\n' +
          '- The output `<document path="...">` must use **exactly** this path string: `' +
          pathLock +
          '` (same characters as this single-item request).\n'
      }
    }
    logTransformContentSubgraphPayload(
      input,
      siteId,
      contentPath,
      instructions,
      writeResults,
      unlock,
      maxItems,
      maxDepth,
      llmModel,
      readTimeoutMs,
      built,
      subgraphXml,
      sys,
      userBody
    )
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped before the bundled inner LLM call (no OpenAI completion or writes for this transform).',
        siteId   : siteId,
        paths    : built?.paths,
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase(
      "${diag}_await_inner_openai_completion model=${llmModel} bundleChars=${subgraphXml.length()}"
    )
    long tOpenAi = System.nanoTime()
    String assistantXml = AiOrchestration.openAiSimpleCompletionAssistantText(
      apiKey,
      llmModel,
      sys,
      userBody,
      innerMaxOutTokens,
      readTimeoutMs,
      diag
    )
    long openAiMs = (System.nanoTime() - tOpenAi) / 1_000_000L
    log.debug(
      '{} DIAG innerOpenAiSimpleCompletion wallMs={} assistantXmlChars={} maxOutTokens={}',
      diag,
      openAiMs,
      (assistantXml ?: '').length(),
      innerMaxOutTokens
    )
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped after the inner LLM returned; bundle was not validated or written.',
        siteId   : siteId,
        paths    : origPaths,
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase(
      "${diag}_parsing_validating_bundle assistantXmlChars=${(assistantXml ?: '').length()}"
    )
    String cleaned = stripOptionalMarkdownFences(assistantXml)
    List<Map> newDocs = ContentSubgraphAggregator.parseDocumentsForTests(cleaned)
    if ('TranslateContentItem'.equals(diag) && origPaths.size() == 1) {
      String canonicalPath = origPaths.get(0)?.toString()?.trim()
      if (canonicalPath) {
        int strictDocCountBefore = newDocs.size()
        String coerced =
          ContentSubgraphAggregator.coerceAssistantBundleToSingleExpectedPath(cleaned, canonicalPath)
        if (coerced != null) {
          if (strictDocCountBefore != 1) {
            log.warn(
              '{} DIAG coerced inner assistant bundle to strict single-document shape path={} (strictParsedDocsBefore={})',
              diag,
              canonicalPath,
              strictDocCountBefore
            )
          }
          cleaned = coerced
          newDocs = ContentSubgraphAggregator.parseDocumentsForTests(cleaned)
        }
      }
    }
    LinkedHashSet<String> newSet = new LinkedHashSet<>()
    for (Map d : newDocs) {
      String pth = AuthoringPreviewContext.normalizeRepoPath(d?.path?.toString())
      if (pth) {
        newSet.add(pth)
      }
    }
    if (!origSet.equals(newSet)) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_exit_path_mismatch")
      return [
        error           : true,
        action          : actionTag,
        message         :
          'LLM output `<document path="...">` set does not match the input bundle (need the **same** path string(s) as input, **one** `<document>` each, non-empty CDATA). Common causes: markdown fences, extra/missing `<document>` blocks, or renamed `path=` attributes. **Copy `path=` and `content-type=` from the input `<document>` tags exactly**; return only the `<aiassistant-content-subgraph>` XML tree.',
        expectedPaths   : new ArrayList<>(origSet),
        returnedPaths : new ArrayList<>(newSet),
        assistantPreview: AiHttpProxy.elideForLog(cleaned, 4000),
      ]
    }
    for (String p : origPaths) {
      String normP = AuthoringPreviewContext.normalizeRepoPath(p)
      String bodyCheck = ''
      for (Map d : newDocs) {
        String dp = AuthoringPreviewContext.normalizeRepoPath(d?.path?.toString())
        if (normP && normP == dp) {
          bodyCheck = d?.body?.toString() ?: ''
          break
        }
      }
      if (!bodyCheck.trim()) {
        AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_exit_empty_body")
        return [
          error           : true,
          action          : actionTag,
          message         :
            'Empty or missing CDATA inner XML for path ' +
            (p ?: '') +
            '. Return exactly one <document> with the same path= as input and non-empty CDATA containing the full <page> or <component> item (no markdown fences).',
          assistantPreview: AiHttpProxy.elideForLog(cleaned, 4000),
        ]
      }
    }
    Map out = [
      action              : actionTag,
      siteId              : siteId,
      root                : built?.root,
      paths               : origPaths,
      documentCount       : origPaths.size(),
      writeResults        : writeResults,
      llmModel            : llmModel,
      truncatedFromWalk   : built?.truncated,
      maxDepthReachedWalk : built?.maxDepthReached,
    ]
    if (!writeResults) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_preview_only_no_apply")
      out.transformedSubgraphPreview = AiHttpProxy.elideForLog(cleaned, 12_000)
      out.nextStep = 'Set writeResults:true to persist, or use WriteContent per path from the preview bundle.'
      return out
    }
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      return [
        error    : true,
        action   : actionTag,
        cancelled: true,
        message  : 'Request was stopped before Studio writes; validated LLM output was not applied to the repository.',
        siteId   : siteId,
        paths    : origPaths,
      ]
    }
    AiOrchestration.aiAssistantToolWorkerDiagPhase(
      "${diag}_apply_writes_running paths=${origPaths.size()}"
    )
    long tApply = System.nanoTime()
    Map applyRes = ContentSubgraphAggregator.apply(ops, siteId, cleaned, unlock, normProtected, pathProtect) as Map
    long applyMs = (System.nanoTime() - tApply) / 1_000_000L
    log.debug(
      '{} DIAG applyWrites wallMs={} writtenCountApprox={} applyOk={}',
      diag,
      applyMs,
      applyRes?.writtenCount,
      applyRes?.ok
    )
    AiOrchestration.aiAssistantToolWorkerDiagPhase("${diag}_apply_writes_done")
    out.putAll(applyRes)
    out.action = actionTag
    return out
  }

  private static List<String> collectTranslateBatchPaths(Map input) {
    LinkedHashSet<String> ordered = new LinkedHashSet<>()
    if (input == null) {
      return new ArrayList<>()
    }
    Closure addOne = { Object o ->
      if (o == null) {
        return
      }
      String p = o.toString().trim()
      if (!p) {
        return
      }
      if (!ContentSubgraphAggregator.isSafeSiteContentPath(p)) {
        return
      }
      String n = AuthoringPreviewContext.normalizeRepoPath(p)
      if (n) {
        ordered.add(n)
      }
    }
    if (input.paths instanceof List) {
      for (Object o : (List) input.paths) {
        addOne.call(o)
      }
    }
    if (input.contentPaths instanceof List) {
      for (Object o : (List) input.contentPaths) {
        addOne.call(o)
      }
    }
    if (input.pathChunks instanceof List) {
      for (Object chunk : (List) input.pathChunks) {
        if (chunk instanceof List) {
          for (Object o : (List) chunk) {
            addOne.call(o)
          }
        } else {
          addOne.call(chunk)
        }
      }
    }
    return new ArrayList<>(ordered)
  }

  private static int resolveTranslateBatchMaxConcurrency(Map input, StudioToolOperations ops) {
    int d = ops != null ? ops.resolveTranslateBatchDefaultMaxConcurrency() : 25
    try {
      if (input?.maxConcurrency != null) {
        d =
          (input.maxConcurrency instanceof Number)
            ? ((Number) input.maxConcurrency).intValue()
            : Integer.parseInt(input.maxConcurrency.toString().trim())
      }
    } catch (Throwable ignored) {
    }
    return Math.max(1, Math.min(64, d))
  }

  private static boolean translateBatchRowCancelled(Map row) {
    return row instanceof Map && Boolean.TRUE.equals(((Map) row).cancelled)
  }

  /**
   * First-pass failures and most warning-shaped outcomes get <strong>one</strong> automatic server retry
   * (see {@link #runTranslateContentBatchParallel}); cancelled rows are not retried.
   */
  private static boolean translateBatchRowNeedsServerRetry(Map row) {
    if (!(row instanceof Map)) {
      return true
    }
    if (translateBatchRowCancelled((Map) row)) {
      return false
    }
    Map m = (Map) row
    if (Boolean.TRUE.equals(m.error)) {
      return true
    }
    return isToolResultWarning(m)
  }

  private static String translateBatchRowReason(Map row) {
    if (!(row instanceof Map)) {
      return 'non-map result'
    }
    Map m = (Map) row
    String s = (m.message ?: m.hint ?: m.skippedReason ?: '')?.toString()?.trim() ?: 'unknown'
    return s.length() > 600 ? s.substring(0, 597) + '…' : s
  }

  private static Map translateBatchCompactAttempt(Map row) {
    if (!(row instanceof Map)) {
      return [message: 'non-map']
    }
    Map m = (Map) row
    Map c = new LinkedHashMap<>()
    c.error = Boolean.TRUE.equals(m.error)
    c.cancelled = Boolean.TRUE.equals(m.cancelled)
    c.message = translateBatchRowReason(m)
    if (m.action) {
      c.action = m.action
    }
    if (m.skippedReason) {
      c.skippedReason = m.skippedReason.toString()
    }
    return c
  }

  /**
   * One translate cell (inner {@link #runTransformContentSubgraph} for a single path); used for first pass and server retry pass.
   */
  private static Map runTranslateBatchSinglePathCell(
    StudioToolOperations ops,
    Map inputBase,
    String siteId,
    String pathFinal,
    String instructions,
    String apiKey,
    String defaultChatModel,
    String normProtected,
    boolean pathProtect,
    Semaphore translateGate,
    Closure toolProgressListener
  ) {
    translateGate.acquire()
    try {
      long t0 = System.nanoTime()
      if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
        Map cancelled = [
          path     : pathFinal,
          cancelled: true,
          message  : 'Request was stopped before this path ran.',
        ]
        if (toolProgressListener) {
          try {
            toolProgressListener.call(
              'TranslateContentItem',
              'warn',
              [siteId: siteId, contentPath: pathFinal, path: pathFinal],
              null,
              cancelled,
              (System.nanoTime() - t0) / 1_000_000L
            )
          } catch (Throwable ignored) {}
        }
        return cancelled
      }
      Map single = new LinkedHashMap<>(inputBase)
      single.put('siteId', siteId)
      single.put('maxItems', Integer.valueOf(1))
      single.put('maxDepth', Integer.valueOf(0))
      single.put('contentPath', pathFinal)
      single.put('path', pathFinal)
      single.put('instructions', instructions)
      Map result
      try {
        result =
          runTransformContentSubgraph(
            ops,
            single,
            apiKey,
            defaultChatModel,
            normProtected,
            pathProtect,
            'TranslateContentItem',
            'translate_content_item'
          )
      } catch (Throwable t) {
        result = [
          path : pathFinal,
          error: true,
          message: (t.message ?: t.toString()),
        ]
      }
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L
      if (toolProgressListener) {
        try {
          boolean warn =
            result instanceof Map &&
              (Boolean.TRUE.equals(((Map) result).error) ||
                Boolean.TRUE.equals(((Map) result).cancelled) ||
                isToolResultWarning(result))
          toolProgressListener.call(
            'TranslateContentItem',
            warn ? 'warn' : 'done',
            [siteId: siteId, contentPath: pathFinal, path: pathFinal],
            null,
            result,
            elapsedMs
          )
        } catch (Throwable ignored) {}
      }
      return (Map) result
    } finally {
      translateGate.release()
    }
  }

  /**
   * Runs {@link #runTransformContentSubgraph} with {@code maxItems=1} per path on a fixed pool — same instructions,
   * parallel OpenAI inner completions + writes (bounded concurrency). Emits {@code TranslateContentItem} progress per path when a listener is set.
   */
  static Map runTranslateContentBatchParallel(
    StudioToolOperations ops,
    Map rawInput,
    String apiKey,
    String defaultChatModel,
    String normProtected,
    boolean pathProtect,
    Closure toolProgressListener = null
  ) {
    Map input = (rawInput != null) ? new LinkedHashMap<>((Map) rawInput) : new LinkedHashMap<>()
    String siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) {
      throw new IllegalArgumentException('Missing required field: siteId')
    }
    String instructions = input?.instructions?.toString()?.trim()
    if (!instructions) {
      throw new IllegalArgumentException('Missing required field: instructions')
    }
    if (!apiKey?.trim()) {
      return [
        error  : true,
        action : 'translate_content_batch',
        message: 'OpenAI API key not configured for this agent',
      ]
    }
    List<String> paths = collectTranslateBatchPaths(input)
    if (paths.isEmpty()) {
      throw new IllegalArgumentException(
        'Provide non-empty paths: paths or contentPaths (array of /site/.../*.xml), or pathChunks from ListContentTranslationScope'
      )
    }
    if (paths.size() > TRANSLATE_BATCH_MAX_PATHS) {
      return [
        error       : true,
        action      : 'translate_content_batch',
        message     : "At most ${TRANSLATE_BATCH_MAX_PATHS} paths per batch (got ${paths.size()}). Split into multiple batch calls.",
        pathsRequested: paths.size(),
      ]
    }
    int concurrency = resolveTranslateBatchMaxConcurrency(input, ops)
    input.remove('paths')
    input.remove('contentPaths')
    input.remove('pathChunks')
    input.remove('maxConcurrency')

    /** Per-batch cap on concurrent inner LLM+repo work; threads come from {@link ParallelToolExecutor}. */
    Semaphore translateGate = new Semaphore(concurrency)
    List<Future<Map>> trackedFutures = new ArrayList<>()
    try {
      if (toolProgressListener) {
        try {
          StringBuilder sb = new StringBuilder()
          sb.append('**TranslateContentBatch** — **')
            .append(paths.size())
            .append('** content item(s) will be translated ')
          sb.append('(up to **')
            .append(concurrency)
            .append('** OpenAI inner runs **in parallel**). ')
          sb.append('Paths **listed below** are all handed to the worker pool next; watch for a **TranslateContentItem** line as **each** finishes:\n')
          int idx = 0
          for (String p : paths) {
            idx++
            String shown = p != null ? p.toString() : ''
            if (shown.length() > 220) {
              shown = shown.substring(0, 217) + '…'
            }
            sb.append('[').append(idx).append('] ').append(shown).append('\n')
          }
          toolProgressListener.call(
            'TranslateContentBatch',
            'progress',
            [siteId: siteId, progressMessage: sb.toString(), path: ''],
            null,
            null,
            null
          )
        } catch (Throwable ignored) {}
      }

      for (String path : paths) {
        final String pathFinal = path
        trackedFutures.add(
          ParallelToolExecutor.submit({
            return runTranslateBatchSinglePathCell(
              ops,
              input,
              siteId,
              pathFinal,
              instructions,
              apiKey,
              defaultChatModel,
              normProtected,
              pathProtect,
              translateGate,
              toolProgressListener
            )
          } as Callable<Map>))
      }

      int firstBatchFutureEnd = trackedFutures.size()
      List<Map> firstPass = new ArrayList<>(paths.size())
      for (int fi = 0; fi < firstBatchFutureEnd; fi++) {
        Future<Map> f = trackedFutures.get(fi)
        String pathHint = paths.size() > fi ? paths.get(fi)?.toString() : ''
        Map row
        try {
          row = coerceTranslateBatchFutureRow(f.get(), pathHint)
        } catch (Throwable t) {
          row = [path: pathHint, error: true, message: (t.message ?: t.toString())]
        }
        firstPass.add(row)
      }

      List<Map> initialFailureDetails = new ArrayList<>()
      for (int pi = 0; pi < paths.size(); pi++) {
        String p = paths.get(pi)
        Map r = firstPass.get(pi)
        if (translateBatchRowNeedsServerRetry(r)) {
          initialFailureDetails.add([
            path : p,
            index: pi + 1,
            reason: translateBatchRowReason(r),
          ])
        }
      }

      Map pathToSecond = new LinkedHashMap()
      int retryRecovered = 0
      int retryStillBad = 0
      if (!initialFailureDetails.isEmpty()) {
        log.debug(
          'TranslateContentBatch: first pass done pathCount={} serverRetryCandidates={}',
          paths.size(),
          initialFailureDetails.size()
        )
        if (toolProgressListener) {
          try {
            StringBuilder rb = new StringBuilder()
            rb.append('**TranslateContentBatch** — **server retry (one pass only):** ')
              .append(initialFailureDetails.size())
              .append(
                ' path(s) failed or warned on the first attempt; re-running each **once** with the same instructions. **Do not** call TranslateContentBatch again for the same paths expecting more automatic retries—use TranslateContentItem or GetContent/WriteContent per remaining failure.\n'
              )
            for (Map d : initialFailureDetails) {
              rb.append('- `').append(d.path).append('` — ').append(d.reason).append('\n')
            }
            toolProgressListener.call(
              'TranslateContentBatch',
              'progress',
              [siteId: siteId, progressMessage: rb.toString(), path: ''],
              null,
              null,
              null
            )
          } catch (Throwable ignored) {}
        }
        List<Future<Map>> retryFutures = new ArrayList<>(initialFailureDetails.size())
        for (Map d : initialFailureDetails) {
          final String rp = d.path?.toString()?.trim()
          Future<Map> rf =
            ParallelToolExecutor.submit({
              return runTranslateBatchSinglePathCell(
                ops,
                input,
                siteId,
                rp,
                instructions,
                apiKey,
                defaultChatModel,
                normProtected,
                pathProtect,
                translateGate,
                toolProgressListener
              )
            } as Callable<Map>)
          retryFutures.add(rf)
          trackedFutures.add(rf)
        }
        for (int ri = 0; ri < retryFutures.size(); ri++) {
          Map det = initialFailureDetails.get(ri)
          String detPath = det.path?.toString()
          String pKey = AuthoringPreviewContext.normalizeRepoPath(detPath)
          Map rr
          try {
            rr = coerceTranslateBatchFutureRow(retryFutures.get(ri).get(), detPath)
          } catch (Throwable t) {
            rr = [
              path : det.path,
              error: true,
              message: (t.message ?: t.toString()),
            ]
          }
          if (pKey) {
            pathToSecond.put(pKey, rr)
          }
          if (!translateBatchRowNeedsServerRetry(rr) && !translateBatchRowCancelled(rr)) {
            retryRecovered++
          } else if (!translateBatchRowCancelled(rr)) {
            retryStillBad++
          }
        }
      }

      List<Map> results = new ArrayList<>(paths.size())
      for (int pi = 0; pi < paths.size(); pi++) {
        String p = paths.get(pi)
        Map r1 = coerceTranslateBatchFutureRow(firstPass.get(pi), p)
        String pn = AuthoringPreviewContext.normalizeRepoPath(p)
        if (pn && pathToSecond.containsKey(pn)) {
          Map r2 = pathToSecond.get(pn)
          Map merged = new LinkedHashMap<>(r2 instanceof Map ? r2 : [error: true, message: 'missing retry result'])
          merged.put('firstPass', translateBatchCompactAttempt(r1))
          merged.put('serverRetriedOnce', true)
          boolean recovered =
            !translateBatchRowNeedsServerRetry(merged) && !translateBatchRowCancelled(merged)
          merged.put('recoveredOnServerRetry', recovered)
          if (!recovered && !translateBatchRowCancelled(merged)) {
            merged.put(
              'guidanceAfterFailedRetry',
              'This path still failed after one automatic server retry. Do not call TranslateContentBatch again for the same path set. Use TranslateContentItem or GetContent/WriteContent on this path only, adjust instructions, or skip.'
            )
          }
          results.add(merged)
        } else {
          results.add(r1)
        }
      }

      int ok = 0
      int err = 0
      int cancelled = 0
      for (Map row : results) {
        if (row instanceof Map) {
          if (Boolean.TRUE.equals(row.cancelled)) {
            cancelled++
          } else if (Boolean.TRUE.equals(row.error) || isToolResultWarning(row)) {
            err++
          } else {
            ok++
          }
        }
      }

      boolean allOk = err == 0 && cancelled == 0
      StringBuilder summary = new StringBuilder()
      if (allOk) {
        summary.append("Batch finished: ${ok} path(s) OK.")
        if (!initialFailureDetails.isEmpty()) {
          summary
            .append(' Server retry pass: ')
            .append(retryRecovered)
            .append(' recovered, ')
            .append(retryStillBad)
            .append(' still had issues (see per-path results).')
        }
      } else {
        summary
          .append("Batch finished with issues: ok=${ok}, errors/warnings=${err}, cancelled=${cancelled}.")
        if (!initialFailureDetails.isEmpty()) {
          summary
            .append(' After **one** automatic server retry: ')
            .append(retryRecovered)
            .append(' recovered, ')
            .append(retryStillBad)
            .append(' still failing (per-path `firstPass` / `guidanceAfterFailedRetry`). **Stop** re-calling TranslateContentBatch for the same paths.')
        }
      }

      Map out = new LinkedHashMap<>()
      out.action = 'translate_content_batch'
      out.siteId = siteId
      out.paths = paths
      out.pathCount = paths.size()
      out.maxConcurrency = concurrency
      out.results = results
      out.okCount = ok
      out.errorOrWarnCount = err
      out.cancelledCount = cancelled
      out.ok = allOk
      out.message = summary.toString()
      out.initialFailures = initialFailureDetails
      out.serverRetryAttempted = !initialFailureDetails.isEmpty()
      out.serverRetryRecoveredCount = retryRecovered
      out.serverRetryStillFailingCount = retryStillBad
      return out
    } finally {
      for (Future<Map> f : trackedFutures) {
        if (f != null && !f.isDone()) {
          try {
            f.cancel(true)
          } catch (Throwable ignored) {}
        }
      }
    }
  }

  private static void logToolInvocation(String toolName, Map input) {
    try {
      def j = JsonOutput.toJson(input ?: [:])
      log.debug("TOOL INVOKED: {} argsChars={} argsPreview=\n{}", toolName, j.length(), AiHttpProxy.elideForLog(j, 4000))
    } catch (Throwable t) {
      log.debug("TOOL INVOKED: {} (args not serializable: {})", toolName, t.toString())
    }
  }

  /**
   * True when a tool returned without throwing but the payload indicates failure, skip, or partial result (⚠️ in UI).
   */
  static boolean isToolResultWarning(Object result) {
    if (!(result instanceof Map)) {
      return false
    }
    def m = (Map) result
    if (Boolean.TRUE.equals(m.error) || 'true'.equalsIgnoreCase(m.error?.toString())) {
      return true
    }
    if (m.skippedReason) {
      return true
    }
    if (m.containsKey('ok')) {
      def ok = m.ok
      if (ok instanceof Boolean && !((Boolean) ok)) {
        return true
      }
      if (ok != null && 'false'.equalsIgnoreCase(ok.toString())) {
        return true
      }
    }
    return false
  }

  /**
   * Wraps tool execution with optional progress callbacks for SSE UIs.
   * {@code listener} signature: {@code (toolName, phase, inputMap, errorOrNull, toolResultOrNull)} —
   * {@code phase} is {@code start}, {@code done}, {@code warn}, or {@code error}; {@code toolResultOrNull} is set for {@code done}/{@code warn}.
   */
  static Map runWithToolProgress(String toolName, Map rawInput, Closure listener, Closure work) {
    def input = (rawInput != null) ? rawInput : [:]
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase("tool_skipped_pipeline_cancelled name=${toolName}")
      log.warn(
        'AI Assistant tool skipped (author Stop / SSE disconnect / pipeline cancel or worker interrupt): tool={}',
        toolName
      )
      return [
        ok       : false,
        error    : true,
        cancelled: true,
        message  : 'Request was stopped; this tool call was not executed (no repository or side-effect work performed).',
        tool     : toolName
      ] as Map
    }
    long t0 = System.nanoTime()
    if (listener) {
      try {
        listener.call(toolName, 'start', input, null, null, null)
      } catch (Throwable ignored) {}
    }
    try {
      def result = work.call()
      if ('GenerateImage'.equals(toolName) && result instanceof Map) {
        String wireTcId = ChatCompletionsToolWire.nativeToolCallIdBindingGet()
        if (wireTcId && !((Map) result).inlineImageRef) {
          Map enriched = new LinkedHashMap<>((Map) result)
          enriched.put('inlineImageRef', wireTcId)
          result = enriched
        }
      }
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L
      if (listener) {
        try {
          def phase = isToolResultWarning(result) ? 'warn' : 'done'
          listener.call(toolName, phase, input, null, result, elapsedMs)
        } catch (Throwable ignored2) {}
      }
      return (Map) result
    } catch (Throwable t) {
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000L
      if (listener) {
        try {
          listener.call(toolName, 'error', input, t, null, elapsedMs)
        } catch (Throwable ignored3) {}
      }
      throw t
    }
  }

  /**
   * Same as {@link #build} but supplies the standard wire converter closure that delegates to
   * {@link AiOrchestration#toolResultToWireString}. Use from Groovy that must not reference Spring AI's
   * {@code ToolCallResultConverter} type on the Studio script compile classpath (e.g. {@code AutonomousAssistantWorker}).
   */
  static List buildWithDefaultWireConverter(
    StudioToolOperations ops,
    Closure toolProgressListener = null,
    String openAiApiKeyForImages = null,
    String imageModel = null,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPath = null,
    List<Map> expertSkillSpecs = null,
    String openAiTextModel = null,
    String llmNormalized = null,
    String imageGeneratorParam = null,
    Collection agentEnabledBuiltInTools = null
  ) {
    def converter =
      { Object result, java.lang.reflect.Type rt -> AiOrchestration.toolResultToWireString(result, rt) }
    return build(
      converter,
      ops,
      toolProgressListener,
      openAiApiKeyForImages,
      imageModel,
      fullSuppressRepoWrites,
      protectedFormItemPath,
      expertSkillSpecs,
      openAiTextModel,
      llmNormalized,
      imageGeneratorParam,
      agentEnabledBuiltInTools
    )
  }

  /**
   * @param converter Spring AI tool result converter or Groovy closure {@code (Object result, Type returnType) -> String}; passed via {@code invokeMethod} so site Groovy compiles without {@code ToolCallResultConverter} on the script classpath
   * @param ops Studio tool operations
   * @param toolProgressListener optional progress callback for streaming chat (see {@link #runWithToolProgress})
   * @param openAiApiKeyForImages API key for the built-in **image** HTTP wire and for embedding/RAG inner calls when applicable (see {@link StudioAiImageGeneratorFactory})
   * @param imageModel resolved default image model from agent/request for the built-in images wire (e.g. gpt-image-1); optional per-call {@code model} in tool args; ignored for pure {@code script:…} image backends unless the script reads it from context
   * @param fullSuppressRepoWrites when true (form engine + client JSON apply but no item path), omit write/publish/revert tools entirely
   * @param protectedFormItemPath normalized repo path of the open form item — when set (and not full suppress), write/publish/revert stay registered but are rejected only for this path; {@code update_content} for this path steers toward {@code aiassistantFormFieldUpdates}
   * @param expertSkillSpecs normalized maps {@code skillId},{@code name},{@code url},{@code description} from the chat request; when non-empty and an OpenAI API key is available, registers {@code QueryExpertGuidance}
   * @param openAiTextModel resolved OpenAI chat model id for inner completions ({@code TranslateContentItem} / bulk subgraph when enabled) default {@code llmModel}; ignored when no API key
   * @param llmNormalized {@link plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind#normalize} result for the active session (image wire defaults)
   * @param imageGeneratorParam optional {@code openAiWire} (default when blank), {@code none}|{@code off}|{@code disabled}, or {@code script:id} — see site docs
   * <p>Built-in tool visibility may be constrained by site {@code /scripts/aiassistant/config/tools.json} — see {@link StudioAiAssistantProjectConfig}.
 * Optional <strong>MCP</strong> servers register additional {@code mcp_*} tools when {@code mcpEnabled} is JSON {@code true} in the same file — see {@link StudioAiAssistantProjectConfig#mcpClientEnabled} and {@link plugins.org.craftercms.aiassistant.mcp.StudioAiMcpClient}.</p>
   */
  static List build(
    Object converter,
    StudioToolOperations ops,
    Closure toolProgressListener = null,
    String openAiApiKeyForImages = null,
    String imageModel = null,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPath = null,
    List<Map> expertSkillSpecs = null,
    String openAiTextModel = null,
    String llmNormalized = null,
    String imageGeneratorParam = null,
    Collection agentEnabledBuiltInTools = null
  ) {
    Map aiProjectToolCfg = StudioAiAssistantProjectConfig.load(ops)
    def normProtected = AuthoringPreviewContext.normalizeRepoPath(protectedFormItemPath)
    boolean pathProtect = normProtected.length() > 0
    List<Map> expertSpecs = new ArrayList<>()
    if (expertSkillSpecs instanceof List) {
      for (Object o : (List) expertSkillSpecs) {
        if (o instanceof Map) {
          expertSpecs.add((Map) o)
        }
      }
    }
    String embKey = (openAiApiKeyForImages ?: '').toString().trim()
    def expertEmbedModel =
      (!expertSpecs.isEmpty() && embKey) ? ExpertSkillVectorRegistry.buildEmbeddingModel(embKey) : null
    Map<String, String> expertUrlBySkillId = new HashMap<>()
    for (Map m : expertSpecs) {
      String sid = m.skillId?.toString()?.trim()
      String u = m.url?.toString()?.trim()
      if (sid && u) {
        expertUrlBySkillId.put(sid, u)
      }
    }
    String openAiChatModelResolved = (openAiTextModel ?: '').toString().trim() ?: 'gpt-4o-mini'

    def generateTextNoToolsTool = null
    if (embKey) {
      final String apiKeyGen = embKey
      final String defaultModelGen = openAiChatModelResolved
      generateTextNoToolsTool = FunctionToolCallback.builder('GenerateTextNoTools', new Function<Map, Map>() {
        @Override Map apply(Map input) {
          runWithToolProgress('GenerateTextNoTools', input, toolProgressListener, {
            logToolInvocation('GenerateTextNoTools', (Map) (input ?: [:]))
            Map m = new LinkedHashMap<>((Map) (input ?: [:]))
            String userPrompt = m.userPrompt?.toString()?.trim()
            if (!userPrompt) {
              userPrompt = m.prompt?.toString()?.trim()
            }
            if (!userPrompt) {
              throw new IllegalArgumentException('Missing required field: userPrompt or prompt')
            }
            String systemInstructions = m.systemInstructions?.toString()?.trim()
            if (!systemInstructions) {
              systemInstructions = m.system?.toString()?.trim()
            }
            String innerSystem = systemInstructions ?
              systemInstructions :
              'You are a writing assistant invoked as a tool inside Crafter Studio. Follow the user text exactly. Output only what was asked (plain text, Markdown, JSON, etc.) unless instructions say otherwise.'
            int maxOut = 8192
            try {
              if (m.maxOutTokens != null) {
                maxOut =
                  (m.maxOutTokens instanceof Number) ? ((Number) m.maxOutTokens).intValue() : Integer.parseInt(
                    m.maxOutTokens.toString().trim())
              }
            } catch (Throwable ignoredMax) {
              maxOut = 8192
            }
            maxOut = Math.min(8192, Math.max(256, maxOut))
            String modelOverride = m.model?.toString()?.trim()
            if (!modelOverride) {
              modelOverride = m.llmModel?.toString()?.trim()
            }
            String modelUse = modelOverride ?: defaultModelGen
            int readTimeout = 180_000
            try {
              if (m.readTimeoutMs != null) {
                readTimeout =
                  (m.readTimeoutMs instanceof Number) ? ((Number) m.readTimeoutMs).intValue() : Integer.parseInt(
                    m.readTimeoutMs.toString().trim())
              }
            } catch (Throwable ignoredRt) {
              readTimeout = 180_000
            }
            readTimeout = Math.min(600_000, Math.max(60_000, readTimeout))
            String text = AiOrchestration.openAiSimpleCompletionAssistantText(
              apiKeyGen,
              modelUse,
              innerSystem,
              userPrompt,
              maxOut,
              readTimeout,
              'GenerateTextNoTools'
            )
            [
              tool          : 'GenerateTextNoTools',
              assistantText : text,
              model         : modelUse,
              promptChars   : userPrompt.length()
            ]
          })
        }
      })
        .description(ToolPrompts.getDESC_GENERATE_TEXT_NO_TOOLS())
        .inputSchema(SCHEMA_GENERATE_TEXT_NO_TOOLS)
        .inputType(Map.class)
        .invokeMethod('toolCallResultConverter', converter)
        .build()
    }

    def transformContentSubgraphTool = null
    /** Legacy / alternate name models and prompts still emit; same implementation as TransformContentSubgraph. */
    def getContentSubgraphAliasTool = null
    def translateContentItemTool = null
    def translateContentBatchTool = null
    if (embKey && !fullSuppressRepoWrites) {
      final String apiKeyFinal = embKey
      final String defaultModelFinal = openAiChatModelResolved
      final String normProtFinal = normProtected
      final boolean pathProtectFinal = pathProtect
      final Closure progressFinal = toolProgressListener
      translateContentBatchTool = FunctionToolCallback.builder('TranslateContentBatch', new Function<Map, Map>() {
        @Override Map apply(Map input) {
          runWithToolProgress('TranslateContentBatch', input, toolProgressListener, {
            logToolInvocation('TranslateContentBatch', (Map) (input ?: [:]))
            runTranslateContentBatchParallel(
              ops,
              (Map) (input ?: [:]),
              apiKeyFinal,
              defaultModelFinal,
              normProtFinal,
              pathProtectFinal,
              progressFinal
            )
          })
        }
      })
        .description(ToolPrompts.getDESC_TRANSLATE_CONTENT_BATCH())
        .inputSchema(SCHEMA_TRANSLATE_CONTENT_BATCH)
        .inputType(Map.class)
        .invokeMethod('toolCallResultConverter', converter)
        .build()
      translateContentItemTool = FunctionToolCallback.builder('TranslateContentItem', new Function<Map, Map>() {
        @Override Map apply(Map input) {
          runWithToolProgress('TranslateContentItem', input, toolProgressListener, {
            Map m = new LinkedHashMap<>((Map) (input ?: [:]))
            m.put('maxItems', Integer.valueOf(1))
            m.put('maxDepth', Integer.valueOf(0))
            logToolInvocation('TranslateContentItem', m)
            runTransformContentSubgraph(
              ops,
              m,
              apiKeyFinal,
              defaultModelFinal,
              normProtFinal,
              pathProtectFinal,
              'TranslateContentItem',
              'translate_content_item'
            )
          })
        }
      })
        .description(ToolPrompts.getDESC_TRANSLATE_CONTENT_ITEM())
        .inputSchema(SCHEMA_TRANSLATE_CONTENT_ITEM)
        .inputType(Map.class)
        .invokeMethod('toolCallResultConverter', converter)
        .build()
      if (ENABLE_TRANSFORM_CONTENT_SUBGRAPH_BULK) {
        transformContentSubgraphTool = FunctionToolCallback.builder('TransformContentSubgraph', new Function<Map, Map>() {
          @Override Map apply(Map input) {
            runWithToolProgress('TransformContentSubgraph', input, toolProgressListener, {
              logToolInvocation('TransformContentSubgraph', (Map) (input ?: [:]))
              runTransformContentSubgraph(ops, input, apiKeyFinal, defaultModelFinal, normProtFinal, pathProtectFinal)
            })
          }
        })
          .description(ToolPrompts.getDESC_TRANSFORM_CONTENT_SUBGRAPH())
          .inputSchema(SCHEMA_TRANSFORM_CONTENT_SUBGRAPH)
          .inputType(Map.class)
          .invokeMethod('toolCallResultConverter', converter)
          .build()
        getContentSubgraphAliasTool = FunctionToolCallback.builder('GetContentSubgraph', new Function<Map, Map>() {
          @Override Map apply(Map input) {
            runWithToolProgress('GetContentSubgraph', input, toolProgressListener, {
              logToolInvocation('GetContentSubgraph', (Map) (input ?: [:]))
              runTransformContentSubgraph(ops, input, apiKeyFinal, defaultModelFinal, normProtFinal, pathProtectFinal)
            })
          }
        })
          .description(ToolPrompts.getDESC_TRANSFORM_CONTENT_SUBGRAPH())
          .inputSchema(SCHEMA_TRANSFORM_CONTENT_SUBGRAPH)
          .inputType(Map.class)
          .invokeMethod('toolCallResultConverter', converter)
          .build()
      }
    }

    def getContentTool = FunctionToolCallback.builder('GetContent', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('GetContent', input, toolProgressListener, {
          logToolInvocation('GetContent', (Map) (input ?: [:]))
          def commitRef = input?.commitId?.toString()?.trim() ?: input?.commitRef?.toString()?.trim()
          def path = repoPathFromToolInput((Map) (input ?: [:]))
          if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
          ops.getContent(input?.siteId as String, path, commitRef)
        })
      }
    })
      .description(ToolPrompts.DESC_GET_CONTENT)
      .inputSchema(SCHEMA_GET_CONTENT)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def listContentTranslationScopeTool = FunctionToolCallback.builder('ListContentTranslationScope', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('ListContentTranslationScope', input, toolProgressListener, {
          logToolInvocation('ListContentTranslationScope', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
          def contentPath = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
          Integer maxItems = null
          Integer maxDepth = null
          Integer chunkSize = null
          try {
            if (input?.maxItems != null) {
              maxItems =
                (input.maxItems instanceof Number) ? ((Number) input.maxItems).intValue() : Integer.parseInt(
                  input.maxItems.toString().trim())
            }
          } catch (Throwable ignored) {
            maxItems = null
          }
          try {
            if (input?.maxDepth != null) {
              maxDepth =
                (input.maxDepth instanceof Number) ? ((Number) input.maxDepth).intValue() : Integer.parseInt(
                  input.maxDepth.toString().trim())
            }
          } catch (Throwable ignored) {
            maxDepth = null
          }
          try {
            if (input?.chunkSize != null) {
              chunkSize =
                (input.chunkSize instanceof Number) ? ((Number) input.chunkSize).intValue() : Integer.parseInt(
                  input.chunkSize.toString().trim())
            }
          } catch (Throwable ignored) {
            chunkSize = null
          }
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          if (!contentPath) throw new IllegalArgumentException('Missing required field: contentPath (or path)')
          ContentSubgraphAggregator.buildTranslationScopeTree(ops, siteId, contentPath, maxItems, maxDepth, chunkSize)
        })
      }
    })
      .description(ToolPrompts.DESC_LIST_CONTENT_TRANSLATION_SCOPE)
      .inputSchema(SCHEMA_LIST_CONTENT_TRANSLATION_SCOPE)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def listStudioContentTypesTool = FunctionToolCallback.builder('ListStudioContentTypes', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('ListStudioContentTypes', input, toolProgressListener, {
          logToolInvocation('ListStudioContentTypes', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          boolean searchable = AuthoringPreviewContext.isTruthy(input?.searchable)
          def contentPath = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
          ops.listStudioContentTypes(siteId, searchable, contentPath)
        })
      }
    })
      .description(ToolPrompts.DESC_LIST_STUDIO_CONTENT_TYPES)
      .inputSchema(SCHEMA_LIST_STUDIO_CONTENT_TYPES)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def getContentTypeTool = FunctionToolCallback.builder('GetContentTypeFormDefinition', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('GetContentTypeFormDefinition', input, toolProgressListener, {
          logToolInvocation('GetContentTypeFormDefinition', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          def contentPath = input?.contentPath?.toString()?.trim()
          def contentTypeId = input?.contentTypeId?.toString()?.trim()
          if (contentPath) {
            def item = ops.getContent(siteId, contentPath)
            def xml = item?.contentXml?.toString()
            def fromXml = extractContentTypeIdFromItemXml(xml)
            if (fromXml) {
              if (contentTypeId && !contentTypeId.equals(fromXml)) {
                log.warn(
                  'GetContentTypeFormDefinition: ignoring contentTypeId={} (differs from <content-type> in {}); using {}',
                  contentTypeId, contentPath, fromXml
                )
              }
              contentTypeId = fromXml
            } else if (!contentTypeId) {
              throw new IllegalArgumentException(
                "No <content-type> element found in XML at '${contentPath}'. Open the item in Studio or use GetContent; pass contentTypeId only if you copy the exact value from that element."
              )
            }
          }
          if (!contentTypeId) {
            throw new IllegalArgumentException(
              'Provide contentPath (page/component XML path — server reads <content-type>) or contentTypeId (exact value from that element, never inferred from filename).'
            )
          }
          ops.getContentTypeFormDefinition(siteId, contentTypeId)
        })
      }
    })
      .description(ToolPrompts.DESC_GET_CONTENT_TYPE_FORM_DEFINITION)
      .inputSchema(SCHEMA_GET_CONTENT_TYPE)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def getContentVersionHistoryTool = FunctionToolCallback.builder('GetContentVersionHistory', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('GetContentVersionHistory', input, toolProgressListener, {
          logToolInvocation('GetContentVersionHistory', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          def path = repoPathFromToolInput((Map) (input ?: [:]))
          if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
          def versions = ops.getContentVersionHistory(siteId, path)
          [
            action  : 'get_content_version_history',
            siteId  : siteId,
            path    : path,
            versions: versions
          ]
        })
      }
    })
      .description(ToolPrompts.DESC_GET_CONTENT_VERSION_HISTORY)
      .inputSchema(SCHEMA_GET_CONTENT_VERSION_HISTORY)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def getPreviewHtmlTool = FunctionToolCallback.builder('GetPreviewHtml', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('GetPreviewHtml', input, toolProgressListener, {
          logToolInvocation('GetPreviewHtml', (Map) (input ?: [:]))
          def m = (Map) (input ?: [:])
          def abs = m.url?.toString()?.trim() ?: m.previewUrl?.toString()?.trim()
          if (!abs) throw new IllegalArgumentException('Missing required field: url (absolute preview http(s) URL, or previewUrl alias)')
          def tok = m.previewToken?.toString()?.trim()
          def sid = m.siteId?.toString()?.trim()
          ops.fetchPreviewRenderedHtml(abs, tok, sid)
        })
      }
    })
      .description(ToolPrompts.DESC_GET_PREVIEW_HTML)
      .inputSchema(SCHEMA_GET_PREVIEW_HTML)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def fetchHttpUrlTool = FunctionToolCallback.builder('FetchHttpUrl', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('FetchHttpUrl', input, toolProgressListener, {
          logToolInvocation('FetchHttpUrl', (Map) (input ?: [:]))
          def m = (Map) (input ?: [:])
          def abs = m.url?.toString()?.trim()
          if (!abs) throw new IllegalArgumentException('Missing required field: url (absolute http(s) URL)')
          Integer maxC = null
          try {
            def mc = m.maxChars
            if (mc != null) {
              if (mc instanceof Number) {
                maxC = ((Number) mc).intValue()
              } else {
                maxC = Integer.parseInt(mc.toString().trim())
              }
            }
          } catch (Throwable ignored) {
            maxC = null
          }
          ops.fetchHttpUrl(abs, maxC)
        })
      }
    })
      .description(ToolPrompts.DESC_FETCH_HTTP_URL)
      .inputSchema(SCHEMA_FETCH_HTTP_URL)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def queryExpertGuidanceTool = null
    if (expertEmbedModel != null && !expertUrlBySkillId.isEmpty()) {
      final def exEmbedFinal = expertEmbedModel
      final Map<String, String> expertUrlBySkillIdFinal = new HashMap<>(expertUrlBySkillId)
      queryExpertGuidanceTool = FunctionToolCallback.builder('QueryExpertGuidance', new Function<Map, Map>() {
        @Override
        Map apply(Map input) {
          runWithToolProgress('QueryExpertGuidance', input, toolProgressListener, {
            logToolInvocation('QueryExpertGuidance', (Map) (input ?: [:]))
            def m = (Map) (input ?: [:])
            String sid = m.skillId?.toString()?.trim()
            String q = m.query?.toString()?.trim()
            int tk = 8
            try {
              def tkRaw = m.topK
              if (tkRaw instanceof Number) {
                tk = ((Number) tkRaw).intValue()
              } else if (tkRaw != null) {
                tk = Integer.parseInt(tkRaw.toString().trim())
              }
            } catch (Throwable ignored) {
              tk = 8
            }
            ExpertSkillVectorRegistry.queryExpertSkill(sid, q, tk, expertUrlBySkillIdFinal, exEmbedFinal, ops)
          })
        }
      })
        .description(ToolPrompts.DESC_QUERY_EXPERT_GUIDANCE)
        .inputSchema(SCHEMA_QUERY_EXPERT_GUIDANCE)
        .inputType(Map.class)
        .invokeMethod('toolCallResultConverter', converter)
        .build()
    }

    def writeContentTool = FunctionToolCallback.builder('WriteContent', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('WriteContent', input, toolProgressListener, {
          logToolInvocation('WriteContent', (Map) (input ?: [:]))
          def path = repoPathFromToolInput((Map) (input ?: [:]))
          if (pathProtect) {
            def p = AuthoringPreviewContext.normalizeRepoPath(path)
            if (p && p == normProtected) {
              return [
                ok: false,
                blockedForFormClientApply: true,
                path: p,
                message:
                  'WriteContent blocked: this path is the Studio form item with client-side apply. Put field edits in aiassistantFormFieldUpdates JSON in your final reply. You may still call WriteContent for other repository paths.',
                nextStep: 'Return aiassistantFormFieldUpdates for this item; use WriteContent only for paths other than this one.'
              ]
            }
          }
          if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
          ops.writeContent(input?.siteId as String, path, input?.contentXml as String, input?.unlock != null ? input.unlock as String : 'true')
        })
      }
    })
      .description(ToolPrompts.DESC_WRITE_CONTENT)
      .inputSchema(SCHEMA_WRITE_CONTENT)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def listPagesTool = FunctionToolCallback.builder('ListPagesAndComponents', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('ListPagesAndComponents', input, toolProgressListener, {
          logToolInvocation('ListPagesAndComponents', (Map) (input ?: [:]))
          ops.listPagesAndComponents(input?.siteId as String, (input?.size as Integer) ?: 1000)
        })
      }
    })
      .description(ToolPrompts.DESC_LIST_PAGES_AND_COMPONENTS)
      .inputSchema(SCHEMA_LIST_PAGES)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def updateTemplateTool = FunctionToolCallback.builder('update_template', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('update_template', input, toolProgressListener, {
          logToolInvocation('update_template', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
          def instructions = input?.instructions?.toString()
          if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          def templatePath = input?.templatePath?.toString()?.trim()
          def contentPath = input?.contentPath?.toString()?.trim()
          if (!templatePath && contentPath) templatePath = ops.resolveTemplatePathFromContent(siteId, contentPath)
          if (!templatePath) throw new IllegalArgumentException('Missing required field: templatePath (or contentPath that resolves a display-template)')
          def templateText = ops.getContent(siteId, templatePath)?.contentXml?.toString() ?: ''
          def contentType = input?.contentType?.toString()?.trim()
          def formDef = contentType ? ops.getContentTypeFormDefinition(siteId, contentType)?.formDefinitionXml?.toString() : null
          boolean formForwardTpl = fullSuppressRepoWrites || (pathProtect && contentPath && AuthoringPreviewContext.sameRepoPath(contentPath, normProtected))
          [
            action: 'update_template',
            siteId: siteId,
            instructions: instructions,
            promptGuidance: formForwardTpl ? ToolPrompts.UPDATE_TEMPLATE_FORM_ENGINE : ToolPrompts.UPDATE_TEMPLATE,
            templatePath: templatePath,
            template: templateText,
            contentPath: contentPath,
            contentType: contentType,
            contentTypeFormDefinition: formDef,
            nextStep: formForwardTpl ? ToolPrompts.nextStepUpdateTemplateFormForward(templatePath) : ToolPrompts.nextStepUpdateTemplate(templatePath)
          ]
        })
      }
    })
      .description(ToolPrompts.DESC_UPDATE_TEMPLATE)
      .inputSchema(SCHEMA_CMS_LOOSE)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def updateContentTool = FunctionToolCallback.builder('update_content', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('update_content', input, toolProgressListener, {
          logToolInvocation('update_content', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
          def instructions = input?.instructions?.toString()
          if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          def contentPath = input?.contentPath?.toString()?.trim()
          if (!contentPath) throw new IllegalArgumentException('Missing required field: contentPath')
          Map gotItem = ops.getContent(siteId, contentPath) as Map
          def contentXml = gotItem?.contentXml?.toString() ?: ''
          def contentTypeId = extractContentTypeIdFromItemXml(contentXml)
          def formDefXml = null
          def formFieldIds = []
          if (contentTypeId) {
            try {
              def formRes = ops.getContentTypeFormDefinition(siteId, contentTypeId)
              def raw = formRes?.formDefinitionXml?.toString()
              if (raw?.trim()) {
                formFieldIds = extractFormFieldIdsFromFormDefinitionXml(raw)
                formDefXml = raw
              }
            } catch (Throwable t) {
              log.debug('update_content: form definition for {} failed: {}', contentTypeId, t.toString())
            }
          }
          boolean formForwardContent = fullSuppressRepoWrites || (pathProtect && AuthoringPreviewContext.sameRepoPath(contentPath, normProtected))
          def payload = [
            action        : 'update_content',
            siteId        : siteId,
            instructions  : instructions,
            promptGuidance: formForwardContent ? ToolPrompts.UPDATE_CONTENT_FORM_ENGINE : ToolPrompts.UPDATE_CONTENT,
            contentPath   : contentPath,
            contentXml    : contentXml,
            nextStep      : formForwardContent ? ToolPrompts.nextStepUpdateContentFormForward(contentPath) : ToolPrompts.nextStepUpdateContent(contentPath)
          ]
          if (contentTypeId) {
            payload.contentTypeId = contentTypeId
          }
          if (formFieldIds) {
            payload.formFieldIds = formFieldIds
          }
          if (formDefXml) {
            payload.formDefinitionForContentType = formDefXml
          }
          ['xmlWellFormed', 'xmlParseError', 'xmlRepairReminder'].each { String k ->
            if (gotItem != null && gotItem.containsKey(k)) {
              payload[k] = gotItem[k]
            }
          }
          payload
        })
      }
    })
      .description(ToolPrompts.DESC_UPDATE_CONTENT)
      .inputSchema(SCHEMA_CMS_LOOSE)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def updateContentTypeTool = FunctionToolCallback.builder('update_content_type', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('update_content_type', input, toolProgressListener, {
          logToolInvocation('update_content_type', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
          def instructions = input?.instructions?.toString()
          if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          def contentType = input?.contentType?.toString()?.trim()
          if (!contentType) throw new IllegalArgumentException('Missing required field: contentType')
          Map formRes = ops.getContentTypeFormDefinition(siteId, contentType) as Map
          def xml = formRes?.formDefinitionXml?.toString() ?: ''
          def cfgPath = formRes?.path?.toString()
          Map payloadCt = [
            action: 'update_content_type',
            siteId: siteId,
            instructions: instructions,
            promptGuidance: fullSuppressRepoWrites ? ToolPrompts.UPDATE_CONTENT_TYPE_FORM_ENGINE : ToolPrompts.UPDATE_CONTENT_TYPE,
            contentType: contentType,
            formDefinitionPath: cfgPath,
            formDefinitionXml: xml,
            nextStep: fullSuppressRepoWrites ? ToolPrompts.nextStepUpdateContentTypeFormForward(cfgPath) : ToolPrompts.nextStepUpdateContentType(cfgPath)
          ]
          ['xmlWellFormed', 'xmlParseError', 'xmlRepairReminder'].each { String k ->
            if (formRes != null && formRes.containsKey(k)) {
              payloadCt[k] = formRes[k]
            }
          }
          payloadCt
        })
      }
    })
      .description(ToolPrompts.DESC_UPDATE_CONTENT_TYPE)
      .inputSchema(SCHEMA_CMS_LOOSE)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def analyzeTemplateTool = FunctionToolCallback.builder('analyze_template', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('analyze_template', input, toolProgressListener, {
          logToolInvocation('analyze_template', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
          def instructions = input?.instructions?.toString()
          if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          def templatePath = input?.templatePath?.toString()?.trim()
          def contentPath = input?.contentPath?.toString()?.trim()
          if (!templatePath && contentPath) templatePath = ops.resolveTemplatePathFromContent(siteId, contentPath)
          if (!templatePath) throw new IllegalArgumentException('Missing required field: templatePath (or contentPath that resolves a display-template)')
          def templateText = ops.getContent(siteId, templatePath)?.contentXml?.toString() ?: ''
          [
            action: 'analyze_template',
            siteId: siteId,
            instructions: instructions,
            promptGuidance: ToolPrompts.ANALYZE_TEMPLATE,
            templatePath: templatePath,
            template: templateText,
            contentPath: contentPath
          ]
        })
      }
    })
      .description(ToolPrompts.DESC_ANALYZE_TEMPLATE)
      .inputSchema(SCHEMA_CMS_LOOSE)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def publishContentTool = FunctionToolCallback.builder('publish_content', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('publish_content', input, toolProgressListener, {
          logToolInvocation('publish_content', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          def path = repoPathFromToolInput((Map) (input ?: [:]))
          if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
          if (pathProtect && AuthoringPreviewContext.sameRepoPath(path, normProtected)) {
            return [
              ok: false,
              blockedForFormClientApply: true,
              path: AuthoringPreviewContext.normalizeRepoPath(path),
              message:
                'publish_content blocked for the form item path (client-side apply). Save/publish from Studio after applying form updates.',
              action: 'publish_content'
            ]
          }
          def date = input?.date?.toString()?.trim()
          def target = input?.publishingTarget?.toString()?.trim() ?: 'live'
          Long packageId = null
          String err = null
          try {
            packageId = ops.submitPublishPackage(siteId, path, target, date) as Long
          } catch (Throwable t) {
            err = (t.message ?: t.toString())
            log.warn('publish_content failed: {}', err)
          }
          [
            action            : 'publish_content',
            siteId            : siteId,
            path              : path,
            date              : date,
            publishingTarget  : target,
            publishPackageId  : packageId,
            ok                : err == null,
            message           : err ?: 'Publish submitted.',
            result            : packageId
          ]
        })
      }
    })
      .description(ToolPrompts.DESC_PUBLISH_CONTENT)
      .inputSchema(SCHEMA_CMS_LOOSE)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def getCrafterizingPlaybookTool = FunctionToolCallback.builder('GetCrafterizingPlaybook', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('GetCrafterizingPlaybook', input, toolProgressListener, {
          logToolInvocation('GetCrafterizingPlaybook', (Map) (input ?: [:]))
          def raw = CrafterizingPlaybookLoader.loadMarkdown()
          boolean fromEditableFile = raw != null && raw.toString().trim().length() > 0
          def md = fromEditableFile ? raw.toString() : CrafterizingPlaybookLoader.embeddedFallbackMarkdown()
          final int maxChars = 250_000
          if (md.length() > maxChars) {
            md = md.substring(0, maxChars) + "\n\n…[truncated at ${maxChars} characters]\n"
          }
          [
            tool                  : 'GetCrafterizingPlaybook',
            markdown              : md,
            charCount             : md.length(),
            loadedFromEditableFile: fromEditableFile,
            playbookFileName      : CrafterizingPlaybookLoader.PLAYBOOK_FILE_NAME,
            hint                  : 'Use when planning or executing full HTML-template-to-CrafterCMS (crafterization) work; follow phases and critical rules, then use CMS tools to read/write content.'
          ]
        })
      }
    })
      .description(ToolPrompts.DESC_GET_CRAFTERIZING_PLAYBOOK)
      .inputSchema(SCHEMA_CRAFTERIZING_PLAYBOOK)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def revertChangeTool = FunctionToolCallback.builder('revert_change', new Function<Map, Map>() {
      @Override Map apply(Map input) {
        runWithToolProgress('revert_change', input, toolProgressListener, {
          logToolInvocation('revert_change', (Map) (input ?: [:]))
          def siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
          if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
          def path = repoPathFromToolInput((Map) (input ?: [:]))
          if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
          if (pathProtect && AuthoringPreviewContext.sameRepoPath(path, normProtected)) {
            return [
              ok: false,
              blockedForFormClientApply: true,
              path: AuthoringPreviewContext.normalizeRepoPath(path),
              message:
                'revert_change blocked for the form item path (client-side apply). Revert from Studio if needed.',
              action: 'revert_change'
            ]
          }
          boolean revertToPrevious = AuthoringPreviewContext.isTruthy(input?.revertToPrevious)
          def versionArg = input?.version?.toString()?.trim()
          if (!versionArg) versionArg = input?.itemVersion?.toString()?.trim()
          def revertType = input?.revertType?.toString()?.trim()
          def semanticRt = revertType && ['content', 'template', 'contenttype'].contains(revertType.toLowerCase())
          if (!versionArg && revertType && !semanticRt) {
            versionArg = revertType
          }
          String versionToUse = versionArg
          if (!versionToUse) {
            if (revertToPrevious) {
              versionToUse = ops.resolvePreviousRevertibleVersionNumber(siteId, path)
            } else if (semanticRt) {
              throw new IllegalArgumentException(
                'revertType content/template/contentType is not a Studio version id. Call GetContentVersionHistory and pass version=<versionNumber>, or set revertToPrevious:true to go back one revertible step.'
              )
            } else {
              throw new IllegalArgumentException(
                'Missing version: pass version (versionNumber from GetContentVersionHistory) or revertToPrevious:true.'
              )
            }
          }
          String err = null
          try {
            ops.revertContentItem(siteId, path, versionToUse, false, 'revert_change tool')
          } catch (Throwable t) {
            err = (t.message ?: t.toString())
            log.warn('revert_change failed: {}', err)
          }
          [
            action           : 'revert_change',
            siteId           : siteId,
            path             : path,
            version          : versionToUse,
            revertToPrevious : revertToPrevious,
            ok               : err == null,
            message          : err ?: 'Reverted to selected Studio version.',
            result           : err == null ? 'ok' : null
          ]
        })
      }
    })
      .description(ToolPrompts.DESC_REVERT_CHANGE)
      .inputSchema(SCHEMA_CMS_LOOSE)
      .inputType(Map.class)
      .invokeMethod('toolCallResultConverter', converter)
      .build()

    def tools = [
      getContentTool,
      listContentTranslationScopeTool,
    ] as ArrayList
    if (generateTextNoToolsTool != null) {
      tools.add(generateTextNoToolsTool)
    }
    if (translateContentItemTool != null) {
      tools.add(translateContentItemTool)
    }
    if (translateContentBatchTool != null) {
      tools.add(translateContentBatchTool)
    }
    if (transformContentSubgraphTool != null) {
      tools.add(transformContentSubgraphTool)
    }
    if (getContentSubgraphAliasTool != null) {
      tools.add(getContentSubgraphAliasTool)
    }
    tools.addAll([
      listStudioContentTypesTool,
      getContentTypeTool,
      getContentVersionHistoryTool,
      getPreviewHtmlTool,
      fetchHttpUrlTool
    ])
    if (queryExpertGuidanceTool != null) {
      tools.add(queryExpertGuidanceTool)
    }
    if (!fullSuppressRepoWrites) {
      tools.add(writeContentTool)
    }
    tools.add(listPagesTool)
    tools.addAll([
      getCrafterizingPlaybookTool,
      updateTemplateTool,
      updateContentTool,
      updateContentTypeTool,
      analyzeTemplateTool
    ])
    if (!fullSuppressRepoWrites) {
      tools.add(publishContentTool)
      tools.add(revertChangeTool)
    }

    String llmNormForImg = (llmNormalized ?: '').toString()
    String imageGenSpec = (imageGeneratorParam ?: '').toString().trim()
    StudioAiImageGenerator imageGen = StudioAiImageGeneratorFactory.resolve(
      ops,
      llmNormForImg,
      imageGenSpec,
      (openAiApiKeyForImages ?: '').toString().trim(),
      imageModel
    )
    if (imageGen != null) {
      final StudioAiImageGenContext imageCtx = StudioAiImageGeneratorFactory.buildContext(
        ops,
        llmNormForImg,
        imageGenSpec,
        (openAiApiKeyForImages ?: '').toString().trim(),
        imageModel
      )
      def generateImageTool = FunctionToolCallback.builder('GenerateImage', new Function<Map, Map>() {
        @Override Map apply(Map input) {
          runWithToolProgress('GenerateImage', input, toolProgressListener, {
            logToolInvocation('GenerateImage', (Map) (input ?: [:]))
            imageGen.generate((Map) (input ?: [:]), imageCtx)
          })
        }
      })
        .description(ToolPrompts.getDESC_GENERATE_IMAGE())
        .inputSchema(SCHEMA_GENERATE_IMAGE)
        .inputType(Map.class)
        .invokeMethod('toolCallResultConverter', converter)
        .build()
      tools.add(generateImageTool)
    }

    List<Map> siteUserToolEntries = StudioAiUserSiteTools.loadRegistryEntries(ops)
    if (!siteUserToolEntries.isEmpty()) {
      StringBuilder desc = new StringBuilder(512)
      desc.append(
        'Runs a **site-defined** Groovy tool from sandbox `config/studio/scripts/aiassistant/user-tools/` (see `registry.json` in that folder). '
      )
      desc.append('Pass **toolId** exactly as registered. Scripts receive binding variables: **studio** (StudioToolOperations), **args** (map from this call), **toolId**, **siteId**, **log** (SLF4J). Return a Map (e.g. ok, message, data). Registered tools: ')
      int i = 0
      for (Map e : siteUserToolEntries) {
        if (i++ > 0) {
          desc.append('; ')
        }
        desc.append(e.id)
        String d = e.description?.toString()?.trim()
        if (d) {
          desc.append(' — ').append(d.length() > 200 ? d.substring(0, 200) + '…' : d)
        }
      }
      if (desc.length() > 8000) {
        desc.setLength(7997)
        desc.append('…')
      }
      final String invokeSiteUserToolDescription = desc.toString()
      def invokeSiteUserTool = FunctionToolCallback.builder('InvokeSiteUserTool', new Function<Map, Map>() {
        @Override Map apply(Map input) {
          runWithToolProgress('InvokeSiteUserTool', input, toolProgressListener, {
            logToolInvocation('InvokeSiteUserTool', (Map) (input ?: [:]))
            Map m = new LinkedHashMap<>((Map) (input ?: [:]))
            String tid = m.toolId?.toString()?.trim()
            Map args = (m.args instanceof Map) ? (Map) m.args : [:]
            StudioAiUserSiteTools.invokeRegisteredTool(ops, tid, args)
          })
        }
      })
        .description(invokeSiteUserToolDescription)
        .inputSchema(SCHEMA_INVOKE_SITE_USER_TOOL)
        .inputType(Map.class)
        .invokeMethod('toolCallResultConverter', converter)
        .build()
      tools.add(invokeSiteUserTool)
    }

    if (StudioAiAssistantProjectConfig.mcpClientEnabled(aiProjectToolCfg)) {
      Set<String> disabledMcpLower = StudioAiAssistantProjectConfig.disabledMcpToolsLower(aiProjectToolCfg)
      List<Map> mcpSpecs = StudioAiAssistantProjectConfig.mcpServers(aiProjectToolCfg)
      for (Map mcpSpec : mcpSpecs) {
        String serverId = mcpSpec?.id?.toString()?.trim()
        if (!serverId) {
          log.warn('MCP: skipping server entry without id')
          continue
        }
        StudioAiMcpClient.McpConnection mcpConn
        List<Map> mcpDefs
        try {
          def opened = StudioAiMcpClient.openSessionAndListTools(ops, mcpSpec)
          mcpConn = (StudioAiMcpClient.McpConnection) opened.connection
          mcpDefs = (List<Map>) opened.tools
        } catch (Throwable tm) {
          log.warn('MCP server {} not available: {}', serverId, tm.message ?: tm.toString())
          continue
        }
        for (Map tdef : mcpDefs) {
          String mcpNm = tdef?.name?.toString()?.trim()
          if (!mcpNm) {
            continue
          }
          String wname = StudioAiMcpClient.wireToolName(serverId, mcpNm)
          if (StudioAiAssistantProjectConfig.isMcpWireToolDisabled(disabledMcpLower, wname)) {
            continue
          }
          Object isch = tdef.get('inputSchema')
          // Spring AI 1.x FunctionToolCallback.Builder#inputSchema expects JSON text, not a Map.
          String schemaJson
          if (isch instanceof CharSequence && isch.toString().trim()) {
            schemaJson = isch.toString().trim()
          } else {
            Map schema =
              isch instanceof Map ? new LinkedHashMap<>((Map) isch) : [type: 'object', properties: [:]]
            if (!schema.containsKey('type')) {
              schema = new LinkedHashMap<>(schema)
              schema.put('type', 'object')
            }
            schemaJson = JsonOutput.toJson(schema)
          }
          String desc = tdef.get('description')?.toString()?.trim()
          if (!desc) {
            desc =
              "MCP tool '${mcpNm}' on server '${serverId}' (remote). Use CMS repository tools for /site reads and writes."
          }
          if (desc.length() > 8000) {
            desc = desc.substring(0, 7997) + '…'
          }
          final StudioAiMcpClient.McpConnection connF = mcpConn
          final String mcpNmF = mcpNm
          final String wnameF = wname
          def mcpToolCb = FunctionToolCallback.builder(wnameF, new Function<Map, Map>() {
            @Override
            Map apply(Map input) {
              runWithToolProgress(wnameF, input, toolProgressListener, {
                logToolInvocation(wnameF, (Map) (input ?: [:]))
                connF.toolsCall(mcpNmF, (Map) (input ?: [:]))
              })
            }
          })
            .description(desc)
            .inputSchema(schemaJson)
            .inputType(Map.class)
            .invokeMethod('toolCallResultConverter', converter)
            .build()
          tools.add(mcpToolCb)
        }
      }
    }

    applyToolCatalogFilters(tools, aiProjectToolCfg)
    applyAgentEnabledBuiltInToolsSubset(tools, agentEnabledBuiltInTools)
    return tools
  }

  /**
   * After site {@code tools.json} policy, optionally restrict to an agent/request whitelist of wire tool names.
   * Include {@code mcp:*} to retain every dynamic {@code mcp_*} tool still present.
   */
  private static void applyAgentEnabledBuiltInToolsSubset(List tools, Collection agentSubset) {
    if (tools == null || tools.isEmpty()) {
      return
    }
    if (!(agentSubset instanceof Collection) || ((Collection) agentSubset).isEmpty()) {
      return
    }
    Set<String> keep = new LinkedHashSet<>()
    boolean mcpAll = false
    for (Object o : (Collection) agentSubset) {
      if (o == null) {
        continue
      }
      String n = o.toString().trim()
      if (!n) {
        continue
      }
      if ('mcp:*'.equals(n)) {
        mcpAll = true
      } else {
        keep.add(n)
      }
    }
    if (keep.isEmpty() && !mcpAll) {
      return
    }
    for (Iterator it = tools.iterator(); it.hasNext();) {
      Object t = it.next()
      if (!(t instanceof FunctionToolCallback)) {
        continue
      }
      String n = ((FunctionToolCallback) t).getToolDefinition().name()
      boolean allow = keep.contains(n)
      if (!allow && mcpAll && n != null && n.startsWith('mcp_')) {
        allow = true
      }
      if (!allow) {
        it.remove()
      }
    }
  }

  /**
   * {@code InvokeSiteUserTool} and {@code mcp_*} tools are kept when {@code enabledBuiltInTools} is a whitelist.
   */
  private static boolean isExtensionCatalogToolName(String n) {
    if (n == null) {
      return false
    }
    if ('InvokeSiteUserTool'.equals(n)) {
      return true
    }
    return n.startsWith('mcp_')
  }

  /**
   * Applies {@link StudioAiAssistantProjectConfig} whitelist/blacklist to the tool catalog.
   * When {@code enabledBuiltInTools} is set, it filters <strong>built-in</strong> CMS tool names only;
   * {@code InvokeSiteUserTool} and {@code mcp_*} tools are always retained unless listed in {@code disabledBuiltInTools}.
   */
  private static void applyToolCatalogFilters(List tools, Map projectCfg) {
    if (tools == null || tools.isEmpty()) {
      return
    }
    if (!(projectCfg instanceof Map)) {
      return
    }
    Set<String> wl = StudioAiAssistantProjectConfig.enabledBuiltInWhitelist(projectCfg)
    Set<String> bl = StudioAiAssistantProjectConfig.disabledBuiltInSet(projectCfg)
    if (wl == null && (bl == null || bl.isEmpty())) {
      return
    }
    for (Iterator it = tools.iterator(); it.hasNext();) {
      Object t = it.next()
      if (!(t instanceof FunctionToolCallback)) {
        continue
      }
      String n = ((FunctionToolCallback) t).getToolDefinition().name()
      if (wl != null) {
        if (isExtensionCatalogToolName(n)) {
          continue
        }
        if (!wl.contains(n)) {
          it.remove()
        }
      } else if (StudioAiAssistantProjectConfig.isToolNameDisabled(n, bl)) {
        it.remove()
      }
    }
  }
}

