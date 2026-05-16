package plugins.org.craftercms.aiassistant.recipes

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.content.ContentSubgraphAggregator
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Deterministic **read-only** prefetch: runs {@code engineSteps} from a matched recipe on the Studio JVM
 * (same request as the intent router), then formats a compact block for the main tools-loop user message.
 */
final class AuthoringIntentRecipeEngine {

  private static final Logger log = LoggerFactory.getLogger(AuthoringIntentRecipeEngine.class)

  private static final Pattern STEP_REF = Pattern.compile('^\\$step(\\d+)\\.(.+)$')

  private AuthoringIntentRecipeEngine() {}

  private static final Set<String> READ_ONLY_TOOLS = [
    'GetContent',
    'GetContentTypeFormDefinition',
    'ListContentTranslationScope',
    'ListStudioContentTypes',
    'GetContentVersionHistory',
    'GetPreviewHtml'
  ] as Set

  /**
   * @return map keys: {@code markdown}, {@code prefetchSteps}, {@code prefetchEnvelopeTruncated}
   */
  static Map runPrefetchBlock(StudioToolOperations ops, Map recipe, Map projectCfg) {
    Map empty = [
      markdown                   : '',
      prefetchSteps                : [],
      prefetchEnvelopeTruncated    : false
    ]
    if (ops == null || recipe == null || projectCfg == null) {
      return empty
    }
    if (!StudioAiAssistantProjectConfig.intentRecipeEngineEnabled(projectCfg)) {
      return empty
    }
    List<Map> steps = AuthoringIntentRecipeCatalog.collectEngineSteps(recipe)
    if (steps.isEmpty()) {
      return empty
    }
    int maxSteps = StudioAiAssistantProjectConfig.intentRecipeEngineMaxSteps(projectCfg)
    int maxTotal = StudioAiAssistantProjectConfig.intentRecipeEngineMaxTotalChars(projectCfg)
    int maxField = StudioAiAssistantProjectConfig.intentRecipeEngineMaxFieldChars(projectCfg)
    if (steps.size() > maxSteps) {
      steps = new ArrayList<>(steps.subList(0, maxSteps))
    }
    Map bindings = ops.recipeEngineAuthoringBindings()
    List<Map> stepSummaries = new ArrayList<>()
    List<Map> stepResults = new ArrayList<>()
    int index = 0
    for (Object stepObj : steps) {
      if (!(stepObj instanceof Map)) {
        index++
        continue
      }
      Map step = (Map) stepObj
      String tool = step.get('tool')?.toString()?.trim()
      if (!tool || !READ_ONLY_TOOLS.contains(tool)) {
        stepSummaries.add([
          index: index,
          tool : tool ?: '(missing)',
          ok   : false,
          error: 'tool not allowlisted for recipe engine (read-only built-ins only)'
        ])
        stepResults.add([:] as Map)
        index++
        continue
      }
      Object argsObj = step.get('args')
      Map argsTemplate = argsObj instanceof Map ? (Map) argsObj : [:]
      Map resolvedArgs
      try {
        resolvedArgs = resolveArgsMap(argsTemplate, bindings, stepResults)
      } catch (Throwable te) {
        stepSummaries.add([index: index, tool: tool, ok: false, error: 'arg resolution: ' + te.message])
        stepResults.add([:] as Map)
        index++
        continue
      }
      Map summary = [index: index, tool: tool, ok: true]
      Map resultPayload = [:] as Map
      try {
        resultPayload = executeReadOnlyTool(ops, tool, resolvedArgs)
        summary.put('ok', true)
      } catch (Throwable tex) {
        summary.put('ok', false)
        summary.put('error', tex.message ?: tex.toString())
        log.debug('AuthoringIntentRecipeEngine step {} {} failed: {}', index, tool, tex.message)
      }
      if (Boolean.TRUE.equals(summary.get('ok'))) {
        summary.put('result', shrinkToolResultForPrefetch(resultPayload, maxField))
      }
      stepSummaries.add(summary)
      stepResults.add(resultPayload instanceof Map ? (Map) resultPayload : [:] as Map)
      index++
    }
    Map envelope = [
      recipeId: recipe.get('id')?.toString(),
      steps   : stepSummaries
    ]
    String json = JsonOutput.toJson(envelope)
    boolean truncated = false
    if (json.length() > maxTotal) {
      json = json.substring(0, Math.max(0, maxTotal - 80)) + '\n…[recipe engine prefetch truncated to engineMaxTotalChars]'
      truncated = true
    }
    String markdown = '[Studio — recipe engine prefetch]\n\n```json\n' + json + '\n```\n\n'
    return [
      markdown                : markdown,
      prefetchSteps             : stepSummaries,
      prefetchEnvelopeTruncated : truncated
    ]
  }

  private static Map resolveArgsMap(Map template, Map bindings, List<Map> priorResults) {
    Map out = new LinkedHashMap<>()
    for (Map.Entry e : template.entrySet()) {
      out.put(e.key, resolveArgValue(e.value, bindings, priorResults))
    }
    out
  }

  private static Object resolveArgValue(Object v, Map bindings, List<Map> priorResults) {
    if (!(v instanceof String)) {
      return v
    }
    String s = ((String) v).trim()
    if ('$siteId'.equals(s)) {
      return bindings.get('siteId') ?: ''
    }
    if ('$contentPath'.equals(s)) {
      return bindings.get('contentPath') ?: ''
    }
    if ('$contentTypeId'.equals(s)) {
      return bindings.get('contentTypeId') ?: ''
    }
    if ('$previewUrl'.equals(s)) {
      return bindings.get('previewUrl') ?: ''
    }
    Matcher m = STEP_REF.matcher(s)
    if (m.matches()) {
      int si = Integer.parseInt(m.group(1), 10)
      String path = m.group(2)
      if (si < 0 || si >= priorResults.size()) {
        return ''
      }
      return navigateMapPath(priorResults.get(si), path)
    }
    return s
  }

  private static Object navigateMapPath(Map root, String dotPath) {
    if (root == null || !dotPath?.trim()) {
      return ''
    }
    Object cur = root
    for (String part : dotPath.split('\\.')) {
      String p = part?.trim()
      if (!p || !(cur instanceof Map)) {
        return cur instanceof Map ? '' : cur
      }
      cur = ((Map) cur).get(p)
      if (cur == null) {
        return ''
      }
    }
    cur
  }

  private static Map executeReadOnlyTool(StudioToolOperations ops, String tool, Map input) {
    String siteId = ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: '')
    switch (tool) {
      case 'GetContent':
        String path = AiOrchestrationTools.repoPathFromToolInput(input)
        if (!path) {
          throw new IllegalArgumentException('Missing path/contentPath for GetContent')
        }
        String commitRef = input?.commitId?.toString()?.trim() ?: input?.commitRef?.toString()?.trim()
        return ops.getContent(siteId, path, commitRef)
      case 'GetContentTypeFormDefinition':
        if (!siteId) {
          throw new IllegalArgumentException('Missing siteId')
        }
        String contentPath = input?.contentPath?.toString()?.trim()
        String contentTypeId = input?.contentTypeId?.toString()?.trim()
        if (contentPath) {
          Map item = ops.getContent(siteId, contentPath)
          String xml = item?.contentXml?.toString()
          String fromXml = AiOrchestrationTools.extractContentTypeIdFromItemXml(xml)
          if (fromXml) {
            contentTypeId = fromXml
          } else if (!contentTypeId) {
            throw new IllegalArgumentException("No <content-type> in XML at '${contentPath}'")
          }
        }
        if (!contentTypeId) {
          throw new IllegalArgumentException('Provide contentPath or contentTypeId for GetContentTypeFormDefinition')
        }
        return ops.getContentTypeFormDefinition(siteId, contentTypeId)
      case 'ListContentTranslationScope':
        String cp = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
        if (!siteId || !cp) {
          throw new IllegalArgumentException('Missing siteId or contentPath for ListContentTranslationScope')
        }
        Integer maxItems = parsePositiveIntOrNull(input?.maxItems)
        Integer maxDepth = parsePositiveIntOrNull(input?.maxDepth)
        Integer chunkSize = parsePositiveIntOrNull(input?.chunkSize)
        return ContentSubgraphAggregator.buildTranslationScopeTree(ops, siteId, cp, maxItems, maxDepth, chunkSize)
      case 'ListStudioContentTypes':
        if (!siteId) {
          throw new IllegalArgumentException('Missing siteId')
        }
        boolean searchable = plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext.isTruthy(input?.searchable)
        String ctp = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
        return ops.listStudioContentTypes(siteId, searchable, ctp)
      case 'GetContentVersionHistory':
        String vp = AiOrchestrationTools.repoPathFromToolInput(input)
        if (!siteId || !vp) {
          throw new IllegalArgumentException('Missing siteId or path for GetContentVersionHistory')
        }
        List<Map> versions = ops.getContentVersionHistory(siteId, vp)
        return [
          action  : 'get_content_version_history',
          siteId  : siteId,
          path    : vp,
          versions: versions
        ]
      case 'GetPreviewHtml':
        String abs = input?.url?.toString()?.trim() ?: input?.previewUrl?.toString()?.trim()
        if (!abs) {
          throw new IllegalArgumentException('Missing url/previewUrl for GetPreviewHtml')
        }
        String tok = input?.previewToken?.toString()?.trim()
        String sid = input?.siteId?.toString()?.trim()
        return ops.fetchPreviewRenderedHtml(abs, tok, sid)
      default:
        throw new IllegalArgumentException('Unsupported tool: ' + tool)
    }
  }

  private static Integer parsePositiveIntOrNull(Object v) {
    if (v == null) {
      return null
    }
    try {
      int n = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim())
      return n > 0 ? Integer.valueOf(n) : null
    } catch (Throwable ignored) {
      return null
    }
  }

  private static Map shrinkToolResultForPrefetch(Map raw, int maxField) {
    if (raw == null) {
      return [:]
    }
    Map copy = new LinkedHashMap<>(raw)
    for (String heavy : ['contentXml', 'formDefinitionXml', 'html', 'body', 'versions']) {
      if (!copy.containsKey(heavy)) {
        continue
      }
      Object v = copy.get(heavy)
      if (v instanceof List && 'versions'.equals(heavy)) {
        List lst = (List) v
        int cap = Math.min(lst.size(), 50)
        copy.put(heavy, lst.subList(0, cap))
        copy.put(heavy + '_truncated', lst.size() > cap)
        continue
      }
      if (!(v instanceof String)) {
        continue
      }
      String s = (String) v
      if (s.length() > maxField) {
        copy.put(heavy, s.substring(0, maxField) + '\n…[truncated]')
        copy.put(heavy + 'Chars', s.length())
      }
    }
    copy
  }

  private static boolean prefetchFieldLooksTruncated(String fieldValue) {
    if (fieldValue == null) {
      return false
    }
    return fieldValue.contains('…[truncated]') || fieldValue.contains('...[truncated]')
  }

  private static Object parsePrefetchJsonEnvelope(String prefetchBlock) {
    int fence = prefetchBlock.indexOf('```json')
    if (fence < 0) {
      return null
    }
    int jsonStart = prefetchBlock.indexOf('\n', fence)
    if (jsonStart < 0) {
      return null
    }
    jsonStart++
    int jsonEnd = prefetchBlock.indexOf('\n```', jsonStart)
    if (jsonEnd <= jsonStart) {
      return null
    }
    String jsonStr = prefetchBlock.substring(jsonStart, jsonEnd).trim()
    if (!jsonStr) {
      return null
    }
    try {
      return new JsonSlurper().parseText(jsonStr)
    } catch (Throwable ignored) {
      return null
    }
  }

  private static String extractPrefetchFormDefinitionXml(String prefetchBlock) {
    if (!(prefetchBlock instanceof CharSequence) || !prefetchBlock.toString().trim()) {
      return ''
    }
    Object parsed = parsePrefetchJsonEnvelope(prefetchBlock.toString())
    if (!(parsed instanceof Map)) {
      return ''
    }
    Object stepsObj = ((Map) parsed).get('steps')
    if (!(stepsObj instanceof List)) {
      return ''
    }
    for (Object stepObj : (List) stepsObj) {
      if (!(stepObj instanceof Map)) {
        continue
      }
      Map step = (Map) stepObj
      if (!'GetContentTypeFormDefinition'.equalsIgnoreCase(step.get('tool')?.toString())) {
        continue
      }
      if (!Boolean.TRUE.equals(step.get('ok'))) {
        continue
      }
      Object resObj = step.get('result')
      if (!(resObj instanceof Map)) {
        continue
      }
      Object fx = ((Map) resObj).get('formDefinitionXml')
      if (!(fx instanceof String)) {
        continue
      }
      String xml = ((String) fx).trim()
      if (!xml || prefetchFieldLooksTruncated(xml)) {
        continue
      }
      return xml
    }
    return ''
  }

  static Map buildPrefetchHotpathDirective(StudioToolOperations ops, String prefetchBlock) {
    Map out = new LinkedHashMap()
    out.put('directive', '')
    out.put('duplicateGetContentBanned', Boolean.FALSE)
    out.put('anchorPath', '')
    if (ops == null || !(prefetchBlock instanceof CharSequence) || prefetchBlock.toString().trim().isEmpty()) {
      return out
    }
    String block = prefetchBlock.toString()
    if (block.contains('…[recipe engine prefetch truncated to engineMaxTotalChars]')) {
      return out
    }
    Map bindings = ops.recipeEngineAuthoringBindings()
    String anchorPath = (bindings?.get('contentPath') ?: '').toString().trim()
    out.put('anchorPath', anchorPath)
    Object parsed = parsePrefetchJsonEnvelope(block)
    if (!(parsed instanceof Map)) {
      return out
    }
    Object stepsObj = ((Map) parsed).get('steps')
    if (!(stepsObj instanceof List)) {
      return out
    }
    for (Object stepObj : (List) stepsObj) {
      if (!(stepObj instanceof Map)) {
        continue
      }
      Map step = (Map) stepObj
      if (!'GetContent'.equalsIgnoreCase(step.get('tool')?.toString())) {
        continue
      }
      if (!Boolean.TRUE.equals(step.get('ok'))) {
        continue
      }
      Object resObj = step.get('result')
      if (!(resObj instanceof Map)) {
        continue
      }
      Map res = (Map) resObj
      Object cxObj = res.get('contentXml')
      if (!(cxObj instanceof String)) {
        continue
      }
      String cx = ((String) cxObj).trim()
      if (!cx || prefetchFieldLooksTruncated(cx)) {
        continue
      }
      String p = (res.get('path') ?: res.get('contentPath') ?: '')?.toString()?.trim() ?: ''
      String pathForMsg = anchorPath ?: p
      if (!pathForMsg) {
        continue
      }
      if (anchorPath && p && !anchorPath.equalsIgnoreCase(p)) {
        continue
      }
      out.put('duplicateGetContentBanned', Boolean.TRUE)
      out.put(
        'directive',
        '[Studio — duplicate GetContent BANNED for this turn]\n' +
          'Repository path: ' + pathForMsg + '\n' +
          'Recipe-engine prefetch already includes successful **GetContent** with non-truncated **contentXml** for this path. ' +
          'Do **not** call **GetContent** again on this path; continue with **WriteContent** or **update_content**.\n\n'
      )
      break
    }
    return out
  }

  static Map buildSimpleFieldEditHotpathExtras(String prefetchBlock, String authorFieldLabelPhrase) {
    Map out = new LinkedHashMap()
    out.put('directive', '')
    out.put('resolvedFieldId', '')
    out.put('resolvedFieldLabel', '')
    String label = (authorFieldLabelPhrase ?: '').toString().trim()
    if (!label) {
      return out
    }
    String formXml = extractPrefetchFormDefinitionXml(prefetchBlock)
    if (!formXml) {
      return out
    }
    String fieldId = AiOrchestrationTools.resolveFieldIdFromFormDefinitionByAuthorLabel(formXml, label)
    if (!fieldId) {
      return out
    }
    out.put('resolvedFieldId', fieldId)
    out.put('resolvedFieldLabel', label)
    out.put(
      'directive',
      '[Studio — simple field edit]\n' +
        'Author field **"' + label + '"** maps to XML element **`' + fieldId + '`**.\n' +
        'Prefetch is on the wire: **round 0** call **WriteContent** on the anchor path — update only **`' +
        fieldId +
        '`** in **contentXml**.\n\n'
    )
    return out
  }

  static Map extractPrefetchSuccessfulGetContent(String prefetchOrUserText) {
    Map out = new LinkedHashMap()
    out.put('path', '')
    out.put('contentXml', '')
    if (!(prefetchOrUserText instanceof CharSequence) || !prefetchOrUserText.toString().trim()) {
      return out
    }
    Object parsed = parsePrefetchJsonEnvelope(prefetchOrUserText.toString())
    if (!(parsed instanceof Map)) {
      return out
    }
    Object stepsObj = ((Map) parsed).get('steps')
    if (!(stepsObj instanceof List)) {
      return out
    }
    for (Object stepObj : (List) stepsObj) {
      if (!(stepObj instanceof Map)) {
        continue
      }
      Map step = (Map) stepObj
      if (!'GetContent'.equalsIgnoreCase(step.get('tool')?.toString())) {
        continue
      }
      if (!Boolean.TRUE.equals(step.get('ok'))) {
        continue
      }
      Object resObj = step.get('result')
      if (!(resObj instanceof Map)) {
        continue
      }
      Map res = (Map) resObj
      Object cxObj = res.get('contentXml')
      if (!(cxObj instanceof String)) {
        continue
      }
      String cx = ((String) cxObj).trim()
      if (!cx || prefetchFieldLooksTruncated(cx)) {
        continue
      }
      String p = (res.get('path') ?: res.get('contentPath') ?: '')?.toString()?.trim() ?: ''
      if (!p) {
        continue
      }
      out.put('path', p)
      out.put('contentXml', cx)
      break
    }
    return out
  }

  static String patchContentXmlFieldValue(String contentXml, String fieldId, String newPlainText) {
    if (!contentXml?.trim() || !fieldId?.trim() || newPlainText == null) {
      return ''
    }
    String tag = fieldId.trim()
    String inner = formatContentFieldInnerXml(tag, newPlainText.toString())
    Pattern pat = Pattern.compile(
      '(?s)(<' + Pattern.quote(tag) + '>)(.*?)(</' + Pattern.quote(tag) + '>)'
    )
    Matcher m = pat.matcher(contentXml)
    if (!m.find()) {
      return ''
    }
    return m.replaceFirst('<' + tag + '>' + inner + '</' + tag + '>')
  }

  private static String formatContentFieldInnerXml(String fieldId, String plain) {
    String t = escapeXmlElementText((plain ?: '').trim())
    if (fieldId.endsWith('_html')) {
      return '<![CDATA[<p>' + t + '</p>]]>'
    }
    return t
  }

  private static String escapeXmlElementText(String s) {
    if (!s) {
      return ''
    }
    return s
      .replace('&', '&amp;')
      .replace('<', '&lt;')
      .replace('>', '&gt;')
  }
}
