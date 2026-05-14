package plugins.org.craftercms.aiassistant.llm.remote

import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spring AI {@link ChatModel} adapter for the <strong>remote CrafterQ HTTP API</strong> (one optional LLM backend for the
 * Studio AI Assistant plugin). <strong>Content / RAG chat only</strong> — no CMS tool execution through this adapter
 * (use the OpenAI-native {@link StudioAiLlmKind#OPENAI_NATIVE} path for tools).
 */
class ExpertChatModel implements ChatModel {
  private static final Logger log = LoggerFactory.getLogger(ExpertChatModel.class)

  /** Avoid Groovy {@code Math.max/min} + {@link BigDecimal} ambiguity (use only primitive int). */
  private static int imax(int a, int b) { return a > b ? a : b }

  private static int imin(int a, int b) { return a < b ? a : b }

  /**
   * Stay under CrafterQ limits: some APIs count UTF-8 bytes or JSON differently — leave headroom below {@link #maxPromptChars}.
   */
  private static final int CRAFTERQ_PROMPT_CHAR_MARGIN = 120

  private final String agentId
  private final String chatId
  private final def requestRef
  /** CrafterQ commonly enforces a small max (e.g. ~1000 chars); conversation is compacted to fit. */
  private final int maxPromptChars
  /** Increments each time this model calls CrafterQ (each Spring AI tool loop step is a new round). */
  private final AtomicInteger providerRound = new AtomicInteger(0)

  ExpertChatModel(String agentId, String chatId, def requestRef, int maxPromptChars = 1000) {
    this.agentId = agentId
    this.chatId = chatId
    this.requestRef = requestRef
    this.maxPromptChars = (maxPromptChars >= 256) ? maxPromptChars : 256
  }

  private int crafterQEffectiveCharCap() {
    return imax(200, maxPromptChars - CRAFTERQ_PROMPT_CHAR_MARGIN)
  }

  private static String enforceCharCap(String s, int cap) {
    if (s == null) return ''
    if (s.length() <= cap) return s
    return s.substring(0, imax(1, cap - 12)) + '...[trim]'
  }

  /**
   * If the API enforces max <strong>bytes</strong> (UTF-8), trim from the end until it fits.
   */
  private String enforceUtf8ByteCap(String s, int maxBytes) {
    if (s == null || s.isEmpty()) return ''
    byte[] raw = s.getBytes(StandardCharsets.UTF_8)
    if (raw.length <= maxBytes) return s
    String t = s
    while (t.length() > 1) {
      t = t.substring(0, t.length() - 1)
      if (t.getBytes(StandardCharsets.UTF_8).length <= maxBytes - 15) break
    }
    return t + '...[utf8-cap]'
  }

  /** Final send payload: char headroom + optional UTF-8 byte cap at configured {@link #maxPromptChars}. */
  private String sanitizePromptForCrafterQApi(String prompt) {
    int charCap = crafterQEffectiveCharCap()
    String t = enforceCharCap(prompt?.trim() ?: '', charCap)
    t = enforceUtf8ByteCap(t, maxPromptChars)
    return t
  }

  /**
   * Spring AI user text may include a long {@code [TOOL-GUARD]…[/TOOL-GUARD]} prefix — strip it for CrafterQ so
   * compaction preserves the author's actual request.
   */
  private static String compactToolGuardForCrafterQ(String body) {
    if (body == null || !body.contains('[TOOL-GUARD]')) return body ?: ''
    int close = body.indexOf('[/TOOL-GUARD]')
    if (close < 0) return body
    String after = body.substring(close + '[/TOOL-GUARD]'.length()).trim()
    return '[TOOLS_REQUIRED] ' + after
  }

  private static String resolveMessageRole(def msg) {
    try {
      def mt = msg?.messageType
      if (mt != null) {
        if (mt instanceof Enum) return ((Enum) mt).name()
        return mt.toString()
      }
    } catch (Throwable ignored) {}
    return msg?.class?.simpleName ?: 'MESSAGE'
  }

  private static String extractRawMessageContent(def msg) {
    if (msg == null) return ''
    try {
      if (msg.metaClass?.respondsTo(msg, 'getText')) {
        return (msg.getText() ?: '').toString()
      }
      if (msg.metaClass?.respondsTo(msg, 'getContent')) {
        return (msg.getContent() ?: '').toString()
      }
      if (msg.metaClass?.respondsTo(msg, 'getResponses')) {
        def responses = msg.getResponses()
        return responses != null ? JsonOutput.toJson(responses) : ''
      }
    } catch (Throwable ignored) {}
    return msg.toString()
  }

  private static boolean isToolResponseShape(def msg, String roleUpper) {
    def sn = msg?.class?.simpleName ?: ''
    if (sn.contains('ToolResponse')) return true
    if (roleUpper.contains('TOOL')) return true
    return false
  }

  /**
   * Format Spring AI tool results like a human pasting CMS output into chat (per product expectation).
   */
  private static String formatToolResultTranscript(def msg) {
    try {
      if (msg?.metaClass?.respondsTo(msg, 'getResponses')) {
        def responses = msg.getResponses()
        if (responses instanceof List && !responses.isEmpty()) {
          return responses.collect { r ->
            def nm = 'tool'
            try {
              nm = (r?.name ?: r?.toolName ?: r?.toolCallName ?: r?.id ?: 'tool')?.toString()
            } catch (ignored) {}
            def data = null
            try { data = r?.responseData } catch (ignored) {}
            if (data == null) try { data = r?.content } catch (ignored) {}
            if (data == null) try { data = r?.data } catch (ignored) {}
            if (data == null) data = JsonOutput.toJson(r)
            "Human (tool result — ${nm}):\n${data}"
          }.join('\n\n')
        }
      }
    } catch (Throwable ignored) {}
    def body = extractRawMessageContent(msg)
    return "Human (tool result):\n${body}"
  }

  private static String formatTranscriptLine(def msg) {
    if (msg == null) return ''
    String role = resolveMessageRole(msg)
    String ru = role.toUpperCase()

    if (isToolResponseShape(msg, ru)) {
      return formatToolResultTranscript(msg)
    }

    def body = extractRawMessageContent(msg)?.trim()
    if (!body) return ''

    switch (ru) {
      case 'USER':
        return "Human: ${compactToolGuardForCrafterQ(body)}"
      case 'ASSISTANT':
        return "Assistant: ${body}"
      case 'SYSTEM':
        return "System: ${body}"
      default:
        return "(${role}): ${body}"
    }
  }

  /** Full transcript (no length cap) for comparison / logging when compacting. */
  private static String buildFullTranscript(List<String> blocks) {
    def sb = new StringBuilder()
    sb.append(ToolPrompts.CRAFTERQ_CONTENT_SYSTEM).append('\n\n')
    sb.append(ToolPrompts.CRAFTERQ_TRANSCRIPT_CONTEXT).append('\n\n')
    sb.append('--- Conversation ---\n\n')
    if (blocks) sb.append(blocks.join('\n\n'))
    return sb.toString().trim()
  }

  /**
   * Fit transcript into {@link #maxPromptChars}: prefer full instructions when short enough; otherwise compact header
   * + original Human ask (truncated) + newest turns from the end (truncated per block).
   */
  private String compactTranscriptForCrafterQ(List<String> blocks, int charCap) {
    String header = ToolPrompts.CRAFTERQ_COMPACT_INSTRUCTIONS + '\n\n--- Conversation ---\n\n'
    int budgetBody = charCap - header.length()
    if (budgetBody < 120) {
      header = ToolPrompts.CRAFTERQ_MINIMAL_HEADER
      budgetBody = charCap - header.length()
    }
    if (budgetBody < 80) {
      def last = (blocks && !blocks.isEmpty()) ? blocks.last().toString() : ''
      def s = (header + last).trim()
      return enforceCharCap(s, charCap)
    }

    final String sep = '\n\n⋯ earlier turns omitted ⋯\n\n'
    int fuIdx = blocks.findIndexOf { it.startsWith('Human:') && !it.startsWith('Human (tool result') }

    List<String> bodyParts = []
    int used = 0

    if (fuIdx >= 0) {
      // floorDiv(int,int) avoids Groovy BigDecimal from * 0.38 or intdiv quirks vs Math.max overloads.
      int scaledForAuthor = Math.floorDiv(budgetBody * 38, 100)
      int fuBudget = imin(280, imax(80, scaledForAuthor))
      String fu = blocks[fuIdx]
      if (fu.length() > fuBudget) {
        fu = fu.substring(0, fuBudget - 20) + "...[trunc ${fu.length()}c]"
      }
      bodyParts.add(fu)
      used += fu.length()
    }

    boolean needSep = (blocks.size() > 1) && (fuIdx >= 0)
    int tailBudget = budgetBody - used - (needSep ? sep.length() : 0)
    if (tailBudget < 50) {
      tailBudget = imax(50, budgetBody - used)
      needSep = false
    }

    List<String> tail = []
    int tailUsed = 0
    for (int i = blocks.size() - 1; i >= 0 && tailUsed < tailBudget; i--) {
      if (i == fuIdx) continue
      String b = blocks[i]
      int room = tailBudget - tailUsed - 2
      if (room < 28) break
      String piece
      if (b.length() <= room) {
        piece = b
      } else {
        piece = b.substring(0, room - 28) + "...[trunc ${b.length()}c]"
      }
      tail.add(0, piece)
      tailUsed += piece.length() + 2
    }

    String body = bodyParts.join('\n\n')
    if (needSep && tail) body += sep
    body += tail.join('\n\n')
    return enforceCharCap((header + body).trim(), charCap)
  }

  /**
   * Single string sent as CrafterQ {@code prompt}: content-assistant instructions + Human/Assistant transcript.
   */
  private String buildCrafterQPrompt(Prompt prompt) {
    int charCap = crafterQEffectiveCharCap()
    List<String> blocks = []
    def instructions = prompt?.instructions
    if (instructions instanceof List && !instructions.isEmpty()) {
      instructions.each { msg ->
        def line = formatTranscriptLine(msg)
        if (line?.trim()) blocks.add(line.trim())
      }
    } else {
      def fallback = prompt?.contents?.toString()?.trim()
      def humanBody = fallback ? compactToolGuardForCrafterQ(fallback) : ''
      blocks.add(humanBody ? ('Human: ' + humanBody) : 'Human: ')
    }

    String full = buildFullTranscript(blocks)
    String toSend
    if (full.length() <= charCap) {
      toSend = full
    } else {
      toSend = compactTranscriptForCrafterQ(blocks, charCap)
      if (log.isWarnEnabled()) {
        log.warn("CrafterQ prompt compacted: configuredMax={} effectiveCharCap={} fullTranscriptWouldBe={} compactChars={}",
          maxPromptChars, charCap, full.length(), toSend.length())
      }
    }
    toSend = sanitizePromptForCrafterQApi(toSend)
    if (log.isDebugEnabled()) {
      int utf8 = (toSend ?: '').getBytes(StandardCharsets.UTF_8).length
      log.debug("CrafterQ prompt metrics: chars={} utf8Bytes={} configuredMax={} effectiveCharCap={}",
        (toSend ?: '').length(), utf8, maxPromptChars, charCap)
    }
    return toSend
  }

  @Override
  ChatResponse call(Prompt prompt) {
    int round = providerRound.incrementAndGet()
    String userText = buildCrafterQPrompt(prompt)
    if (log.isDebugEnabled()) {
      log.debug("CrafterQ call start: round={} agentId={} chatIdPresent={} maxPromptChars={} mergedPromptChars={}\n{}",
        round, agentId, (chatId != null && chatId.toString().trim().length() > 0), maxPromptChars, userText?.length() ?: 0,
        AiHttpProxy.elideForLog(userText, 12000))
    }

    def payload = chatId ? [prompt: userText, chatId: chatId] : [prompt: userText]

    def url = "https://api.crafterq.ai/v1/chats?agentId=${URLEncoder.encode(agentId, 'UTF-8')}"
    def result = AiHttpProxy.postJson(url, payload, requestRef)
    if (log.isDebugEnabled()) {
      log.debug("CrafterQ call done: round={} resultKeys={}", round, (result instanceof Map) ? (result as Map).keySet() : [])
    }

    String assistantText = result?.response?.message ?: result?.response?.content ?: result?.content ?: result?.text ?: ''
    if (assistantText == null) assistantText = ''
    def metaObj = result?.metadata ?: result?.response?.metadata ?: [:]
    def meta = (metaObj instanceof Map) ? (metaObj as Map) : [:]
    if (log.isDebugEnabled()) {
      log.debug("CrafterQ call parsed: round={} assistantChars={} metaKeys={}\n{}",
        round, assistantText.length(), (meta != null ? meta.keySet() : []),
        AiHttpProxy.elideForLog(assistantText, 12000))
    }
    def assistant = new AssistantMessage(assistantText, meta)
    def gen = new Generation(assistant)
    return new ChatResponse([gen])
  }

  @Override
  Flux<ChatResponse> stream(Prompt prompt) {
    int round = providerRound.incrementAndGet()
    String userText = buildCrafterQPrompt(prompt)
    if (log.isDebugEnabled()) {
      log.debug("CrafterQ stream start: round={} agentId={} maxPromptChars={} mergedPromptChars={}\n{}",
        round, agentId, maxPromptChars, userText?.length() ?: 0, AiHttpProxy.elideForLog(userText, 12000))
    }

    def streamUrl = "https://api.crafterq.ai/v1/chats?agentId=${URLEncoder.encode(agentId, 'UTF-8')}&stream=true"
    def payload = chatId ? [prompt: userText, chatId: chatId] : [prompt: userText]
    def payloadBytes = JsonOutput.toJson(payload).getBytes(StandardCharsets.UTF_8)

    return Flux.create({ FluxSink<ChatResponse> sink ->
      Thread.start {
        HttpURLConnection conn = null
        InputStream inputStream = null
        try {
          conn = (HttpURLConnection) new URL(streamUrl).openConnection()
          conn.setRequestMethod('POST')
          conn.setDoOutput(true)
          AiHttpProxy.applyCrafterQForwardedHeaders(conn, requestRef)
          conn.setRequestProperty('Content-Type', 'application/json')
          conn.setRequestProperty('Accept', 'text/event-stream')
          conn.setChunkedStreamingMode(0)
          def os = conn.getOutputStream()
          try { os.write(payloadBytes) } finally { os.close() }

          int status = conn.getResponseCode()
          if (status < 200 || status >= 300) {
            sink.error(new RuntimeException("CrafterQ stream error: ${status}"))
            return
          }

          inputStream = conn.getInputStream()
          def reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
          def slurper = new JsonSlurper()
          String line
          while ((line = reader.readLine()) != null) {
            if (!line.startsWith('data:')) continue
            def data = line.substring(5).trim()
            if (!data) continue
            if (log.isDebugEnabled()) log.debug("CrafterQ SSE data: {}", data.length() > 500 ? data.substring(0, 500) + "..." : data)
            try {
              def obj = slurper.parseText(data)
              def metaObj = obj?.metadata ?: [:]
              def meta = (metaObj instanceof Map) ? (metaObj as Map) : [:]
              def completed = (meta?.completed != null) ? meta.completed.asBoolean() : false
              def text = AiHttpProxy.extractTextFromCrafterQEvent(obj)
              if (log.isDebugEnabled()) {
                log.debug("CrafterQ parsed: completed={} textLen={}", completed, (text != null ? text.length() : -1))
              }
              String assistantChunkText = (text != null) ? text : ''
              if ((assistantChunkText != null && !assistantChunkText.isEmpty()) || completed) {
                def assistant = new AssistantMessage(assistantChunkText ?: '', meta)
                sink.next(new ChatResponse([new Generation(assistant)]))
              }
              if (completed) break
            } catch (Exception ex) {
              log.warn("CrafterQ SSE parse/emit failed: {}", ex.toString())
            }
          }
          sink.complete()
        } catch (Exception e) {
          sink.error(e)
        } finally {
          try { inputStream?.close() } catch (ignored) {}
          try { conn?.disconnect() } catch (ignored) {}
        }
      }
    })
  }
}

