package plugins.org.craftercms.aiassistant.orchestration.chatcompletions

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.Locale
import java.util.Map
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Chat-completions-shaped native tools wire: compact {@code GenerateImage} payloads and
 * {@code studio-ai-inline-image://…} placeholders keyed by {@code tool_call_id}.
 * OpenAI-compatible POST bodies use these semantics; this type is the single place for that protocol.
 */
final class ChatCompletionsToolWire {

  private static final Logger LOG = LoggerFactory.getLogger(ChatCompletionsToolWire.class)

  /** Markdown placeholder expanded server-side before author-visible SSE (value is the wire {@code tool_call_id}). */
  static final String STUDIO_AI_INLINE_IMAGE_REF_PREFIX = 'studio-ai-inline-image://'

  private static final Pattern INLINE_IMAGE_REF_ID_PATTERN =
    Pattern.compile(Pattern.quote(STUDIO_AI_INLINE_IMAGE_REF_PREFIX) + '([^)\\s<>]+)')

  private static final ThreadLocal<String> NATIVE_TOOL_CALL_ID = new ThreadLocal<>()

  private ChatCompletionsToolWire() {}

  static void nativeToolCallIdBindingSet(String toolCallId) {
    String id = toolCallId != null ? toolCallId.toString().trim() : ''
    if (id) {
      NATIVE_TOOL_CALL_ID.set(id)
    } else {
      NATIVE_TOOL_CALL_ID.remove()
    }
  }

  static void nativeToolCallIdBindingClear() {
    NATIVE_TOOL_CALL_ID.remove()
  }

  static String nativeToolCallIdBindingGet() {
    return NATIVE_TOOL_CALL_ID.get()
  }

  /**
   * Unwrap nested {@code result}/{@code output}/{@code data} shapes from adapters/scripts.
   */
  static Map unwrapGenerateImageToolResultMap(Map root) {
    Map m = root != null ? root : [:]
    int guard = 0
    while (guard++ < 12) {
      if (m.get('result') instanceof Map) {
        m = (Map) m.get('result')
        continue
      }
      if (m.get('output') instanceof Map) {
        m = (Map) m.get('output')
        continue
      }
      if (m.get('data') instanceof Map) {
        Map inner = (Map) m.get('data')
        if (inner.get('url') != null || inner.get('b64_json') != null || inner.get('ok') != null) {
          m = inner
          continue
        }
      }
      break
    }
    return m
  }

  static String generateImageBacklogToolCallId(Map gm) {
    if (gm == null || gm.isEmpty()) {
      return nativeToolCallIdBindingGet() ?: ''
    }
    String tid = gm.inlineImageRef != null ? gm.inlineImageRef.toString().trim() : ''
    if (!tid && gm.toolCallId != null) {
      tid = gm.toolCallId.toString().trim()
    }
    if (!tid) {
      tid = nativeToolCallIdBindingGet() ?: ''
    }
    return tid
  }

  static String generateImageResultUrlString(Map m) {
    if (m == null || m.isEmpty()) {
      return ''
    }
    String url = m.get('url') != null ? m.get('url').toString().trim() : ''
    if (url) {
      return url
    }
    String b64 = m.get('b64_json') != null ? m.get('b64_json').toString().trim() : ''
    if (!b64) {
      return ''
    }
    String of = (m.get('output_format') ?: m.get('outputFormat'))?.toString()?.trim()?.toLowerCase(Locale.ROOT) ?: ''
    String mime = 'image/png'
    if (of == 'jpeg' || of == 'jpg') {
      mime = 'image/jpeg'
    } else if (of == 'webp') {
      mime = 'image/webp'
    } else if (of == 'png') {
      mime = 'image/png'
    }
    return 'data:' + mime + ';base64,' + b64
  }

  /**
   * Stores full image URL under {@code toolCallId}, returns compact JSON for {@code role:tool}, or {@code null}.
   */
  static String compactGenerateImageToolWire(String toolOutJson, String toolCallId, Map<String, String> imageUrlByToolCallId) {
    if (!toolOutJson?.trim() || !toolCallId?.trim() || imageUrlByToolCallId == null) {
      return null
    }
    Object parsed
    try {
      parsed = new JsonSlurper().parseText(toolOutJson)
    } catch (Throwable ignored) {
      return null
    }
    if (!(parsed instanceof Map)) {
      return null
    }
    Map m = unwrapGenerateImageToolResultMap((Map) parsed)
    String url = generateImageResultUrlString(m)
    if (!url) {
      return null
    }
    String tid = toolCallId.trim()
    boolean isDataImage = url.startsWith('data:image')
    boolean isHttpImage = url.startsWith('https://') || url.startsWith('http://')
    if (!isDataImage && !isHttpImage) {
      return null
    }
    if (isDataImage && url.length() > 5_000_000) {
      return null
    }
    imageUrlByToolCallId.put(tid, url)
    Map compact = new LinkedHashMap<>()
    for (String k : ['ok', 'tool', 'model', 'revised_prompt', 'hint']) {
      if (m.containsKey(k) && m.get(k) != null) {
        compact.put(k, m.get(k))
      }
    }
    compact.put('inlineImageRef', tid)
    compact.put(
      'authorMarkdownInstruction',
      'In your next assistant message, include exactly ONE markdown image line using this URL in the parentheses (verbatim): ' +
        STUDIO_AI_INLINE_IMAGE_REF_PREFIX + tid +
        ' Example: ![Generated illustration](' + STUDIO_AI_INLINE_IMAGE_REF_PREFIX + tid +
        ') Do not use a data: URL.'
    )
    LOG.info(
      'Chat completions wire: GenerateImage compact tool wire toolCallId={} storedUrlChars={} data={}',
      tid,
      url.length(),
      isDataImage
    )
    return JsonOutput.toJson(compact)
  }

  private static void mergeBacklogIntoEff(Map<String, String> eff, Map<String, String> backlogByToolCallId) {
    if (eff == null || backlogByToolCallId == null || backlogByToolCallId.isEmpty()) {
      return
    }
    for (Map.Entry<String, String> e : backlogByToolCallId.entrySet()) {
      String id = e.getKey() != null ? e.getKey().toString().trim() : ''
      String u = e.getValue() != null ? e.getValue().toString().trim() : ''
      if (id && u && !eff.containsKey(id)) {
        eff.put(id, u)
      }
    }
  }

  /**
   * When the model omits markdown for {@link #STUDIO_AI_INLINE_IMAGE_REF_PREFIX}&lt;toolCallId&gt;, authors see no
   * bitmap in chat even though {@link #compactGenerateImageToolWire} stored the URL. Append one markdown image line per
   * missing id so {@link #expandInlineImageRefs} can substitute the real URL.
   *
   * @param assistantText assistant markdown **before** expansion (typically tool-loop accumulator text only)
   */
  static String appendMissingInlineImageRefs(
    String assistantText,
    Map<String, String> compactWireUrlByToolCallId,
    Map<String, String> sseBacklogUrlByToolCallId = null
  ) {
    String src = assistantText != null ? assistantText.toString() : ''
    Map<String, String> eff = new LinkedHashMap<>()
    if (compactWireUrlByToolCallId != null && !compactWireUrlByToolCallId.isEmpty()) {
      eff.putAll(compactWireUrlByToolCallId)
    }
    mergeBacklogIntoEff(eff, sseBacklogUrlByToolCallId)
    if (eff.isEmpty()) {
      return src
    }
    StringBuilder sb = new StringBuilder(src)
    boolean needNl = sb.length() > 0 && !sb.toString().endsWith('\n')
    for (Map.Entry<String, String> e : eff.entrySet()) {
      String id = e.getKey() != null ? e.getKey().toString().trim() : ''
      if (!id) {
        continue
      }
      String token = STUDIO_AI_INLINE_IMAGE_REF_PREFIX + id
      if (src.contains(token)) {
        continue
      }
      if (needNl) {
        sb.append('\n')
        needNl = false
      }
      sb.append('\n![Generated image](').append(token).append(')')
    }
    return sb.toString()
  }

  /**
   * Replace {@link #STUDIO_AI_INLINE_IMAGE_REF_PREFIX}&lt;toolCallId&gt; with the stored URL for that id only.
   */
  static String expandInlineImageRefs(
    String text,
    Map<String, String> compactWireUrlByToolCallId,
    Map<String, String> sseBacklogUrlByToolCallId = null
  ) {
    if (text == null) {
      return ''
    }
    String out = text.toString()
    Map<String, String> eff = new LinkedHashMap<>()
    if (compactWireUrlByToolCallId != null && !compactWireUrlByToolCallId.isEmpty()) {
      eff.putAll(compactWireUrlByToolCallId)
    }
    mergeBacklogIntoEff(eff, sseBacklogUrlByToolCallId)
    if (eff.isEmpty()) {
      if (out.contains(STUDIO_AI_INLINE_IMAGE_REF_PREFIX)) {
        LOG.warn(
          'Chat completions wire: assistant text still contains {} but url map is empty.',
          STUDIO_AI_INLINE_IMAGE_REF_PREFIX
        )
      }
      return out
    }
    Matcher mat = INLINE_IMAGE_REF_ID_PATTERN.matcher(out)
    StringBuffer sb = new StringBuffer()
    while (mat.find()) {
      String id = mat.group(1)
      String url = eff.get(id)
      if (url != null && url.length() > 0) {
        mat.appendReplacement(sb, Matcher.quoteReplacement(url))
      } else {
        mat.appendReplacement(sb, Matcher.quoteReplacement(mat.group(0)))
      }
    }
    mat.appendTail(sb)
    return sb.toString()
  }
}
