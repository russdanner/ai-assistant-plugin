package plugins.org.craftercms.aiassistant.plan

import groovy.json.JsonSlurper

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Map

/**
 * **Plan orchestration** (always on): the model may append a machine-only {@code <!--CRAFTERRQ_ORCH ... -->} JSON block
 * listing ordered steps and tool names; the server can reorder {@code tool_calls} to match and strip the block from
 * author-visible text and wire history.
 */
final class PlanOrchestration {

  static final String ORCH_BLOCK_START = '<!--CRAFTERRQ_ORCH'
  static final String ORCH_BLOCK_END = '-->'

  /**
   * Appended to the OpenAI authoring system message when native tools are on the API.
   */
  static String machineInstructionsAddendum() {
    return '''

## Machine orchestration block (server-only — do not describe in Markdown to the author)
In the **same** assistant message as your **## Plan** and **`tool_calls`**, append **exactly one** HTML comment at the **very end** of `content` (after all visible text), on its own lines, with **JSON only** between the delimiters:

<!--CRAFTERRQ_ORCH
{"version":1,"steps":[{"id":1,"tools":["ListContentTranslationScope"],"summary":"one line"},{"id":2,"tools":["TranslateContentBatch"],"summary":"one line"},{"id":3,"tools":["GetPreviewHtml"],"summary":"one line"}]}
-->

Rules:
- `steps` lists **execution order**. Each `tools` array contains **function names** exactly as registered in this chat (same spelling as tool `name` in the schema).
- List **every** tool you invoke in this turn **once each**, in the order you intend the server to run them.
- The **`tools` names must match your actual `tool_calls`** for this message (same tools, same order). **Do not** name one tool in JSON while emitting a **different** name in **`tool_calls`** — authors see a broken plan.
- Keep `summary` short (for logs). Authors do not need to read this block; do not narrate it outside the comment.'''
  }

  /** Removes the orchestration comment so authors and downstream OpenAI turns do not see machine JSON. */
  static String stripOrchestrationPlanBlock(String raw) {
    if (raw == null) {
      return ''
    }
    String s = raw.toString()
    int start = s.indexOf(ORCH_BLOCK_START)
    if (start < 0) {
      return s
    }
    int jsonStart = start + ORCH_BLOCK_START.length()
    int end = s.indexOf(ORCH_BLOCK_END, jsonStart)
    if (end < 0) {
      return s
    }
    end += ORCH_BLOCK_END.length()
    String before = s.substring(0, start).trim()
    String after = (end < s.length()) ? s.substring(end).trim() : ''
    if (before && after) {
      return before + '\n\n' + after
    }
    return (before ?: after)?.trim() ?: ''
  }

  static List<Map> parseOrchestrationSteps(String raw) {
    if (!raw?.trim()) {
      return []
    }
    String s = raw.toString()
    int start = s.indexOf(ORCH_BLOCK_START)
    if (start < 0) {
      return []
    }
    int jsonStart = start + ORCH_BLOCK_START.length()
    int end = s.indexOf(ORCH_BLOCK_END, jsonStart)
    if (end < 0) {
      return []
    }
    String json = s.substring(jsonStart, end).trim()
    try {
      def sl = new JsonSlurper().parseText(json)
      if (!(sl instanceof Map)) {
        return []
      }
      def st = ((Map) sl).get('steps')
      if (!(st instanceof List)) {
        return []
      }
      List<Map> out = new ArrayList<>()
      for (def item : (List) st) {
        if (item instanceof Map) {
          out.add((Map) item)
        }
      }
      return out
    } catch (Throwable ignored) {
      return []
    }
  }

  /**
   * Reorders {@code tool_calls} to match the plan when every name matches exactly once; otherwise {@code null}.
   */
  static List reorderToolCallsByPlan(List toolCallsList, String assistantContent) {
    if (toolCallsList == null || toolCallsList.isEmpty()) {
      return null
    }
    List<Map> steps = parseOrchestrationSteps(assistantContent ?: '')
    if (steps.isEmpty()) {
      return null
    }
    List<String> planOrder = new ArrayList<>()
    for (Map st : steps) {
      def tls = st.get('tools')
      if (tls instanceof List) {
        for (def t : (List) tls) {
          if (t != null) {
            planOrder.add(t.toString().trim())
          }
        }
      } else if (st.get('tool') != null) {
        planOrder.add(st.get('tool').toString().trim())
      }
    }
    if (planOrder.isEmpty()) {
      return null
    }
    Map<String, ArrayDeque<Map>> byName = new LinkedHashMap<>()
    for (def tcObj : toolCallsList) {
      if (!(tcObj instanceof Map)) {
        continue
      }
      Map tc = (Map) tcObj
      String fn = extractToolName(tc)
      if (!fn) {
        continue
      }
      byName.computeIfAbsent(fn, { new ArrayDeque<Map>() }).add(tc)
    }
    List ordered = new ArrayList<>()
    for (String want : planOrder) {
      ArrayDeque<Map> q = byName.get(want)
      if (q == null || q.isEmpty()) {
        return null
      }
      ordered.add(q.removeFirst())
    }
    for (ArrayDeque<Map> q : byName.values()) {
      if (!q.isEmpty()) {
        return null
      }
    }
    return ordered.size() == toolCallsList.size() ? ordered : null
  }

  /**
   * Stable reorder: form-definition reads and other non-write tools first, then writes, then preview verification.
   * Preserves relative order within each bucket. Always returns a new list (may equal input order).
   */
  static List reorderToolCallsReadBeforeWritePreview(List toolCallsList) {
    if (toolCallsList == null || toolCallsList.isEmpty()) {
      return toolCallsList
    }
    List lead = new ArrayList<>()
    List formDefs = new ArrayList<>()
    List writes = new ArrayList<>()
    List previews = new ArrayList<>()
    for (def tcObj : toolCallsList) {
      if (!(tcObj instanceof Map)) {
        lead.add(tcObj)
        continue
      }
      String fn = extractToolName((Map) tcObj)
      if ('WriteContent'.equals(fn) || 'update_content'.equals(fn)) {
        writes.add(tcObj)
      } else if ('GetContentTypeFormDefinition'.equals(fn)) {
        formDefs.add(tcObj)
      } else if ('GetPreviewHtml'.equals(fn)) {
        previews.add(tcObj)
      } else {
        lead.add(tcObj)
      }
    }
    List out = new ArrayList<>(toolCallsList.size())
    out.addAll(lead)
    out.addAll(formDefs)
    out.addAll(writes)
    out.addAll(previews)
    return out
  }

  private static String extractToolName(Map tc) {
    def fn = tc.get('function')
    if (!(fn instanceof Map)) {
      return ''
    }
    return fn.get('name')?.toString()?.trim() ?: ''
  }
}
