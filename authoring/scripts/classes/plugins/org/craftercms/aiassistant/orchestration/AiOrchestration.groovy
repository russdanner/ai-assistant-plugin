package plugins.org.craftercms.aiassistant.orchestration

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmRuntimeFactory
import plugins.org.craftercms.aiassistant.llm.StudioAiRuntimeBuildRequest
import plugins.org.craftercms.aiassistant.plan.PlanOrchestration
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.rag.PluginRagVectorRegistry
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

@Grab(group='org.springframework.ai', module='spring-ai-core', version='1.0.0-M6', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-openai', version='1.0.0-M6', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-anthropic', version='1.0.0-M6', initClass=false)
@Grab(group='io.projectreactor', module='reactor-core', version='3.6.6', initClass=false)

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.ModelOptionsUtils
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest
import org.springframework.ai.openai.api.common.OpenAiApiConstants
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.reactive.function.client.WebClientResponseException

import java.time.Duration
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.ArrayList
import java.util.Set
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import reactor.core.publisher.Flux

/**
 * Central place for server-side orchestration for the <strong>Studio AI Assistant</strong> plugin.
 *
 * <p><strong>LLM adapters:</strong> Chat sessions are built through {@link StudioAiLlmRuntime} implementations
 * ({@link OpenAiSpringAiLlmRuntime}, {@link ExpertApiLlmRuntime}, {@link StudioAiScriptLlmContainerRuntime} for
 * {@code script:…} site Groovy). The token {@link StudioAiLlmKind#CRAFTERRQ_REMOTE_API} ({@code llm=crafterQ}) selects the
 * <strong>remote hosted chat</strong> adapter; Tools-loop wire, Claude, and script LLMs are separate paths. Additional
 * providers should implement {@link StudioAiLlmRuntime}, register in {@link StudioAiLlmRuntimeFactory}, and extend
 * {@link StudioAiLlmKind}.</p>
 *
 * <p>Provider-specific wire/stream logic (e.g. OpenAI RestClient tool loops) still lives here until split into
 * per-provider transports.</p>
 *
 * REST scripts are thin wrappers: validate input, call this class, return map or null for streaming.
 */
class AiOrchestration {
  private static final Logger log = LoggerFactory.getLogger(AiOrchestration.class)

  /**
   * Max wait for Spring AI {@code chatResponse()} flux to finish (complete or error), and for the OpenAI
   * native-tools RestClient loop ({@code Future#get}) on the worker thread.
   * Default **10 minutes** — aligns with {@code CHAT_STREAM_TIMEOUT_MS} in {@code AiAssistantChat.tsx} (600_000).
   * Override JVM {@code crafterq.chatFluxAwaitMs} (120_000–1_200_000).
   * On expiry we {@code dispose()} the subscription / cancel the future so the outbound HTTP call is torn down
   * (OpenAI sees a client disconnect on that connection).
   */
  private static final long CHAT_FLUX_AWAIT_MS = resolveChatFluxAwaitMs()

  /** Worker throws {@link InterruptedException} with this message when the SSE client disconnects or Stop cancels the pipeline. */
  private static final String CRAFTQ_PIPELINE_CANCELLED = 'crafterq.pipeline.cancelled'

  /**
   * Max characters per {@code role:tool} message in the OpenAI native RestClient loop. Huge tool JSON (e.g.
   * {@code ListPagesAndComponents} with a large {@code size}) must not blow the model context window.
   * <p><strong>{@code GenerateImage}:</strong> bitmaps are <strong>not</strong> sent in {@code role:tool} content — a
   * compact JSON with {@code crafterqInlineImageRef} is wired instead; the full {@code data:image/...;base64,...} is
   * held server-side and expanded into the final assistant text for SSE only (see {@link #CRAFTERRQ_TOOL_IMAGE_REF_PREFIX}).</p>
   */
  private static final int OPENAI_NATIVE_TOOL_WIRE_MAX_CHARS = 36_000

  /**
   * Model-visible placeholder for a generated inline image in the OpenAI native tool loop. Value is the OpenAI
   * {@code tool_call_id} (e.g. {@code call_…}). {@link #openAiExpandCrafterqToolImageRefs} swaps this for the real
   * {@code data:} URL in the author-facing response.
   */
  private static final String CRAFTERRQ_TOOL_IMAGE_REF_PREFIX = 'crafterq-tool-image://'

  /**
   * Latest worker phase for logs and **SSE heartbeats** (Tools-loop+tools worker sets it; servlet thread reads it while
   * awaiting {@code Future#get}). Per-stream {@link #CRAFTERQ_TOOL_WORKER_DIAG_PHASE_BY_SESSION} avoids cross-talk when
   * several authors chat concurrently; {@link #CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF} remains a legacy fallback when no
   * session id is bound on the worker thread.
   */
  private static final AtomicReference<String> CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF = new AtomicReference<>('')

  private static final ConcurrentHashMap<String, String> CRAFTERQ_TOOL_WORKER_DIAG_PHASE_BY_SESSION = new ConcurrentHashMap<>()

  private static final ThreadLocal<String> CRAFTERQ_TOOL_WORKER_DIAG_SESSION_ID = new ThreadLocal<>()

  /**
   * In-flight {@link #openAiSimpleCompletionAssistantText} calls whose {@code workerPhasePrefix} is
   * {@code TranslateContentItem} (parallel {@code TranslateContentBatch} workers). Heartbeats read
   * {@link #CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF}, which pool threads overwrite, so this count gives a truthful
   * “several inner calls may be parallel” hint when {@code inflight > 1}.
   */
  private static final AtomicInteger CRAFTERQ_TRANSLATE_ITEM_INNER_INFLIGHT = new AtomicInteger(0)

  /**
   * Native OpenAI tools worker thread: shared with {@link AiOrchestrationTools#runWithToolProgress} so repository
   * tools skip side effects after author Stop / SSE disconnect / timeout sets {@code cancelRequested}, or after
   * {@link Future#cancel(boolean)} interrupts the worker. Cleared in {@link #openAiExecuteNativeToolsViaRestClientReturnText}
   * {@code finally} so the next chat prompt does not inherit a stale flag.
   */
  private static final ThreadLocal<AtomicBoolean> CRAFTERQ_PIPELINE_CANCEL_REQUESTED = new ThreadLocal<>()

  static void crafterQPipelineCancelBindingSet(AtomicBoolean cancelRequestedRef) {
    try {
      if (cancelRequestedRef != null) {
        CRAFTERQ_PIPELINE_CANCEL_REQUESTED.set(cancelRequestedRef)
      } else {
        CRAFTERQ_PIPELINE_CANCEL_REQUESTED.remove()
      }
    } catch (Throwable ignored) {
    }
  }

  static void crafterQPipelineCancelBindingClear() {
    try {
      CRAFTERQ_PIPELINE_CANCEL_REQUESTED.remove()
    } catch (Throwable ignored) {
    }
  }

  /**
   * True when this thread is running the Tools-loop+tools pipeline and the author cancelled, or the worker was interrupted.
   * Repository tools should treat this as "do not read/write the repo for this call".
   */
  static boolean crafterQPipelineCancelEffective() {
    try {
      AtomicBoolean a = CRAFTERQ_PIPELINE_CANCEL_REQUESTED.get()
      if (a == null) {
        return false
      }
      if (a.get()) {
        return true
      }
      return Thread.currentThread().isInterrupted()
    } catch (Throwable ignored) {
      return false
    }
  }

  /**
   * Binds a unique id for this Tools-loop worker so {@link #crafterQToolWorkerDiagPhaseGet(String)} on the servlet
   * thread reads the correct phase. Call {@link #crafterQToolWorkerDiagSessionEnd()} in a worker {@code finally}.
   */
  static void crafterQToolWorkerDiagSessionBind(String sessionId) {
    try {
      String s = (sessionId ?: '').toString().trim()
      if (s) {
        CRAFTERQ_TOOL_WORKER_DIAG_SESSION_ID.set(s)
        CRAFTERQ_TOOL_WORKER_DIAG_PHASE_BY_SESSION.put(s, '')
      }
    } catch (Throwable ignored) {
    }
  }

  static void crafterQToolWorkerDiagSessionEnd() {
    String sid = null
    try {
      sid = CRAFTERQ_TOOL_WORKER_DIAG_SESSION_ID.get()
      if (sid != null && sid.toString().trim()) {
        CRAFTERQ_TOOL_WORKER_DIAG_PHASE_BY_SESSION.remove(sid.toString().trim())
      }
    } catch (Throwable ignored) {
    }
    try {
      CRAFTERQ_TOOL_WORKER_DIAG_SESSION_ID.remove()
    } catch (Throwable ignored2) {
    }
    // Only reset the legacy global slot if this thread did not bind a session id (it owned the global ref).
    if (sid == null || !sid.toString().trim()) {
      try {
        CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF.set('')
      } catch (Throwable ignored3) {
      }
    }
  }

  /** Set from the worker thread only (tool loop, RestClient POST, TransformContentSubgraph, etc.). */
  static void crafterQToolWorkerDiagPhase(String phase) {
    try {
      String sid = CRAFTERQ_TOOL_WORKER_DIAG_SESSION_ID.get()
      if (sid != null && sid.toString().trim()) {
        String key = sid.toString().trim()
        if (phase != null && phase.toString().trim()) {
          CRAFTERQ_TOOL_WORKER_DIAG_PHASE_BY_SESSION.put(key, phase.toString().trim())
        } else {
          CRAFTERQ_TOOL_WORKER_DIAG_PHASE_BY_SESSION.put(key, '')
        }
        return
      }
    } catch (Throwable ignoredBind) {
    }
    try {
      if (phase != null && phase.toString().trim()) {
        CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF.set(phase.toString().trim())
      } else {
        CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF.set('')
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * @param sessionId when non-blank (same value passed to {@link #crafterQToolWorkerDiagSessionBind}), reads the
   *                    phase for that stream; otherwise falls back to the global ref (legacy inner completions).
   */
  static String crafterQToolWorkerDiagPhaseGet(String sessionId = null) {
    try {
      String key = (sessionId ?: '')?.toString()?.trim()
      if (key) {
        def v = CRAFTERQ_TOOL_WORKER_DIAG_PHASE_BY_SESSION.get(key)
        return v != null ? v.toString() : ''
      }
    } catch (Throwable ignoredMap) {
    }
    try {
      def v = CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF.get()
      return v != null ? v.toString() : ''
    } catch (Throwable ignored) {
      return ''
    }
  }

  static void crafterQToolWorkerDiagPhaseClear() {
    try {
      String sid = CRAFTERQ_TOOL_WORKER_DIAG_SESSION_ID.get()
      if (sid != null && sid.toString().trim()) {
        CRAFTERQ_TOOL_WORKER_DIAG_PHASE_BY_SESSION.put(sid.toString().trim(), '')
        return
      }
    } catch (Throwable ignored) {
    }
    try {
      CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF.set('')
    } catch (Throwable ignored2) {
    }
  }

  /**
   * Short author-facing hint for SSE heartbeats while the Tools-loop+tools worker is busy — derived from
   * {@link #crafterQToolWorkerDiagPhaseGet(String)} (per-stream session on the servlet thread) so we do not imply the main chat POST is slow when
   * {@link AiOrchestrationTools} is inside a bundled inner completion (e.g. {@code TransformContentSubgraph}).
   */
  private static String openAiPipelineWaitHintMarkdown(String workerPhase) {
    int translateInnerInflight = CRAFTERQ_TRANSLATE_ITEM_INNER_INFLIGHT.get()
    if (translateInnerInflight > 0) {
      return translateInnerInflight > 1
        ? 'Applying updates to **several** content files in parallel…'
        : 'Applying updates to **one** content file…'
    }
    String p = (workerPhase ?: '').toString()
    if (!p.trim()) {
      return 'Organizing the next step…'
    }
    if (p.contains('TranslateContentItem_await_inner')) {
      return 'Applying updates to a content file…'
    }
    if (p.contains('TransformContentSubgraph_await_inner')) {
      return 'Processing linked pages together (larger jobs take longer)…'
    }
    if (p.contains('TranslateContentItem_simple_completion_awaiting_chat_upstream_response_body')) {
      return 'Finishing an automated content edit…'
    }
    if (p.contains('TranslateContentItem_simple_completion_HttpURLConnection_POST')) {
      return 'Sending an automated content edit…'
    }
    if (p.contains('TransformContentSubgraph_simple_completion_awaiting_chat_upstream_response_body')) {
      return 'Receiving updates for linked pages…'
    }
    if (p.contains('TransformContentSubgraph_simple_completion_HttpURLConnection_POST')) {
      return 'Sending a bundled content update…'
    }
    if (p.contains('simple_completion_awaiting_chat_upstream_response_body')) {
      return 'Waiting on a background content edit…'
    }
    if (p.contains('simple_completion_HttpURLConnection_POST')) {
      return 'Waiting on a background response…'
    }
    if (p.contains('TranslateContentItem_apply_writes')) {
      return 'Saving updated content…'
    }
    if (p.contains('TranslateContentItem_ContentSubgraphAggregator_build')) {
      return 'Loading content for editing…'
    }
    if (p.contains('TranslateContentItem_parsing_validating')) {
      return 'Checking edited content before save…'
    }
    if (p.contains('TranslateContentItem')) {
      return 'Applying content updates…'
    }
    if (p.contains('TransformContentSubgraph_apply_writes')) {
      return 'Saving linked pages…'
    }
    if (p.contains('TransformContentSubgraph_ContentSubgraphAggregator_build')) {
      return 'Gathering linked content…'
    }
    if (p.contains('TransformContentSubgraph_parsing_validating')) {
      return 'Checking bundled edits…'
    }
    if (p.contains('TransformContentSubgraph')) {
      return 'Processing linked content…'
    }
    if (p.contains('native_tools_RestClient_POST_/v1/chat/completions') && !p.contains('response_ok')) {
      return 'Choosing the next step…'
    }
    if (p.contains('native_tool_loop_round') && p.contains('repository_tool') && !p.contains('repository_tool_done')) {
      return 'Updating your site…'
    }
    if (p.contains('native_tool_loop_round') && p.contains('_build_request')) {
      return 'Preparing the next step…'
    }
    return 'Organizing the next step…'
  }

  private static long resolveChatFluxAwaitMs() {
    try {
      def p = System.getProperty('crafterq.chatFluxAwaitMs')
      if (p != null && p.toString().trim()) {
        long n = Long.parseLong(p.toString().trim())
        if (n >= 120_000L && n <= 1_200_000L) {
          return n
        }
      }
    } catch (Throwable ignored) {}
    return 600_000L
  }

  /**
   * While the servlet waits on the Tools-loop+tools worker, emit periodic SSE lines so authors are not silent for minutes.
   * Override JVM {@code crafterq.openai.sseWaitHeartbeatMs} (3000–120000; default 12000).
   */
  private static long resolveOpenAiSseWaitHeartbeatMs() {
    try {
      def p = System.getProperty('crafterq.openai.sseWaitHeartbeatMs')
      if (p != null && p.toString()?.trim()) {
        long n = Long.parseLong(p.toString().trim())
        if (n >= 3_000L && n <= 120_000L) {
          return n
        }
      }
    } catch (Throwable ignored) {}
    return 12_000L
  }

  private static final long OPENAI_SSE_WAIT_HEARTBEAT_MS = resolveOpenAiSseWaitHeartbeatMs()

  /**
   * HTTP read timeout for Spring {@link RestClient} POSTs to {@code /v1/chat/completions} (tools-on/off sync paths).
   * Defaults to {@link #CHAT_FLUX_AWAIT_MS} + 30s so the client does not hit {@link ResourceAccessException} read
   * timeouts (JDK default is often ~60s) before the outer pipeline budget cancels. Override
   * {@code crafterq.openai.restReadTimeoutMs} (60_000–1_260_000).
   */
  private static int resolveOpenAiRestReadTimeoutMs() {
    try {
      def p = System.getProperty('crafterq.openai.restReadTimeoutMs')
      if (p != null && p.toString().trim()) {
        int n = Integer.parseInt(p.toString().trim())
        if (n >= 60_000 && n <= 1_260_000) {
          return n
        }
      }
    } catch (Throwable ignored) {}
    return (int) Math.min(1_260_000L, CHAT_FLUX_AWAIT_MS + 30_000L)
  }

  private static SimpleClientHttpRequestFactory openAiRestRequestFactory() {
    def rf = new SimpleClientHttpRequestFactory()
    rf.setReadTimeout(resolveOpenAiRestReadTimeoutMs())
    rf.setConnectTimeout(30_000)
    return rf
  }

  private static RestClient.Builder openAiRestClientBuilder(String apiKey, String wireBaseUrl = null) {
    String base = (wireBaseUrl ?: '').toString().trim()
    if (!base) {
      base = (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().trim()
    }
    base = base.replaceAll(/\/+$/, '')
    RestClient.builder()
      .baseUrl(base)
      .defaultHeader(HttpHeaders.AUTHORIZATION, 'Bearer ' + apiKey)
      .requestFactory(openAiRestRequestFactory())
  }

  /**
   * Effective URL for {@code POST .../chat/completions}, matching {@link #openAiRestClientBuilder}
   * {@code .post().uri("/v1/chat/completions")}. Spring AI's default base is often {@code https://api.openai.com}
   * (no {@code /v1}); appending only {@code /chat/completions} yields {@code .../chat/completions} and OpenAI returns
   * <strong>404</strong>. Always normalize so {@link #openAiSimpleCompletionAssistantText} hits the same host/path as
   * {@link #openAiHttpPostChatCompletionsReadBody}.
   */
  private static String resolveOpenAiSyncChatCompletionsUrl(String wireBaseUrl = null) {
    String b = (wireBaseUrl ?: '').toString().trim()
    if (!b) {
      b = (OpenAiApiConstants.DEFAULT_BASE_URL?.toString()?.trim() ?: 'https://api.openai.com')
    }
    b = b.replaceAll(/\/+$/, '')
    if (b.endsWith('/v1')) {
      return b + '/chat/completions'
    }
    return b + '/v1/chat/completions'
  }

  /**
   * Spring AI / WebClient / Netty: optional DEBUG in Studio logs (Log4j2). **Off by default.**
   * Set JVM system property {@code crafterq.springAiHttpDebug=true} to enable once per JVM.
   */
  private static final AtomicBoolean springAiVerboseHttpLoggingArmed = new AtomicBoolean(false)

  private static void ensureVerboseSpringAiHttpLogging() {
    String raw = System.getProperty('crafterq.springAiHttpDebug', 'false')
    if (!Boolean.parseBoolean((raw != null ? raw.trim() : 'false') ?: 'false')) {
      return
    }
    if (!springAiVerboseHttpLoggingArmed.compareAndSet(false, true)) {
      return
    }
    try {
      Class cfgCls = Class.forName('org.apache.logging.log4j.core.config.Configurator')
      Class levelCls = Class.forName('org.apache.logging.log4j.Level')
      Object debug = levelCls.getField('DEBUG').get(null)
      def setLevel = cfgCls.getMethod('setLevel', String, levelCls)
      [
        'org.springframework.ai',
        'org.springframework.ai.openai',
        'org.springframework.ai.chat.client',
        'org.springframework.web.reactive.function.client',
        'org.springframework.http.codec',
        'reactor.netty.http.client'
      ].each { String name -> setLevel.invoke(null, name, debug) }
      log.info(
        'AI Assistant: Spring AI / WebClient / Reactor Netty HTTP loggers set to DEBUG (crafterq.springAiHttpDebug=true).'
      )
    } catch (Throwable t) {
      log.warn('AI Assistant: failed to enable Spring AI HTTP debug loggers (Log4j2 Configurator): {}', t.message)
    }
  }

  private final def request
  private final def response
  private final def applicationContext
  private final def params
  private final def pluginConfig

  AiOrchestration(def request, def response, def applicationContext, def params, def pluginConfig) {
    this.request = request
    this.response = response
    this.applicationContext = applicationContext
    this.params = params
    this.pluginConfig = pluginConfig
  }

  private static boolean isToolRequiredIntent(String prompt) {
    def p = (prompt ?: '').toLowerCase()
    if (!p) return false
    def patterns = [
      /.*\bupdate\b.*/,
      /.*\bmodify\b.*/,
      /.*\bchange\b.*/,
      /.*\bedit\b.*/,
      /.*\bcreate\b.*/,
      /.*\bwrite\b.*/,
      /.*\brewrite\b.*/,
      /.*\btranslate\b.*/,
      /.*\btranslation\b.*/,
      /.*\blocalize\b.*/,
      /.*\blocalise\b.*/,
      /.*\brephrase\b.*/,
      /.*\bpublish\b.*/,
      /.*\brevert\b.*/,
      /.*\brollback\b.*/,
      /.*\btemplate\b.*/,
      /.*\bcontent type\b.*/,
      /.*\bform-definition\b.*/,
      /.*\bhome page\b.*/
    ]
    return patterns.any { p ==~ it }
  }

  /**
   * Prepended to the user message when {@code formEngineClientForward} — models often ignore trailing instructions
   * and answer with generic CMS docs unless this block is first.
   */
  private static String prependFormEngineClientApplyEnforcement(String prompt) {
    def tail = (prompt ?: '').toString()
    return '''[FORM-ENGINE — CLIENT FIELD APPLY — READ FIRST]
The author is in Studio's **legacy content form**. When the UI attaches **Current Studio content form**, it sends **metadata only** (content type, path, field ids, linked paths, model keys) — **not** full XML/JSON bodies. **GetContent** / **update_content** read the **git** copy; unsaved form edits are **not** inlined in the prompt — use **GetContent** after Save for repo truth, or return **`crafterqFormFieldUpdates`** from visible task + ids when the author expects client-side apply without Save.

**Content-changing requests** (translate, localize, rephrase, rewrite, fix grammar, shorten, expand, fill, update, change tone, write copy, etc.) mean **field values and item XML** — not FreeMarker templates, scripts, or other **code** unless the author explicitly asked for those. If the author updates **this page** / **the page** in preview **without** naming a single block, they mean **the page file and every referenced component** that shows copy (`sections_o`, `header_o` / `footer_o` / `left_rail_o`, etc.) — not the page item alone; apply or output updates for each path that holds visible text.
1) **Do the work** in the target language or style. **End your reply** with a Markdown **```json** fenced block containing ONLY valid JSON of the form: {"crafterqFormFieldUpdates":{"field_id":"new value",...}} using **exact** field element names from the form definition / XML in the prompt. List **every** field you changed. HTML/RTE fields: string values may include markup.
2) **Forbidden:** Generic CrafterCMS tutorials ("Access the Content Item", "Translation Configuration", "add a language", "click Save", workflow documentation), MCP/plugin commands, or refusing to translate when you can output the target language. A short intro sentence is OK; the **JSON block is mandatory** for these requests.

**Pure Q&A** (no edits to the open item): answer normally and **omit** the JSON block.

---

''' + tail
  }

  private static String addToolRequiredGuard(String prompt, boolean fullSuppressRepoWrites = false, String protectedFormItemPathNormalized = null) {
    def p = (prompt ?: '').toString()
    if (!isToolRequiredIntent(p)) return p
    def normProt = AuthoringPreviewContext.normalizeRepoPath(protectedFormItemPathNormalized)
    if (fullSuppressRepoWrites) {
      return '''[TOOL-GUARD]
This user request is a content/template/config modification task.
You MUST call at least one tool before giving your final response.
Do not respond with prose-only output for this request (no final answer that skips tools).
**WriteContent**, **publish_content**, and **revert_change** are **not registered** — never call them.
After **update_content** (or sufficient **GetContent** / **GetContentTypeFormDefinition**), your **final** reply must include **`crafterqFormFieldUpdates`** JSON (see system **Form-engine client-forward mode**) so the Studio form can apply edits — do not substitute MCP commands or “paste into Studio” tutorials.
First output a **business-readable ## Plan** (**📋** per numbered step, enough detail for a non-developer — see system STUDIO POLICY). **Do not** write plan steps that only restate that you will run tools or obey policy — each **📋** line must name a **concrete visitor- or editor-visible outcome** (what changes, where you verify it). **Do not** call tools until that heading and steps are visible. Then **follow that plan**; after each tool refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only — keep mid-flight updates compact. When you narrate tool use in your own words, prefix with **🛠️**. Do **not** fake server-style tool log lines (see system STUDIO POLICY).
Do **not** paste full FreeMarker (`.ftl`) bodies or large XML dumps into the author's chat — summarize outcomes; they edit in the form.
If target path/id is unclear and the user message does not include **Studio authoring context** with a current repository path, call discovery tools first.
Your **final** reply after tools must state **success or problems** using **✅** / **❌** / **⚠️**, include a **clear business-friendly** recap under **## Plan Execution** (not **## Plan** again) that mirrors the **📋** checklist — **open that section** with one short line that **core work is done** and the bullets are **recap / verification**, then **ask what's next**.
For **content XML** (pages/components): preserve `<page>`/`<component>` and field tags from the current file and content type (`formFieldIds` / GetContentTypeFormDefinition). For GetContentTypeFormDefinition use **contentPath** or copy **contentTypeId** from `<content-type>` — never infer content type from filename. For **page-wide** translate/tone/rewrite, include **all referenced component** items, not the page file only (see system **“This page”** rule).
When the author only asked to **update content** — **field values and item XML / static-assets**, not template or schema **file edits** (e.g. tone, grammar, proofreading, translate/localize, copy, rephrase) — use **update_content** / **GetContent** — **do not** call **update_template** or **update_content_type** to fix gaps in those tasks. You **may** use **analyze_template** or **GetContent** on `.ftl` **read-only** to diagnose why preview still disagrees with the goal; if the issue is **in the template** (hardcoded copy, wrong defaults, etc.), **tell the author** (path + brief evidence) — do not patch FTL without explicit consent to change templates.
[/TOOL-GUARD]

''' + p
    }
    if (normProt) {
      return """[TOOL-GUARD]
This user request is a content/template/config modification task.
You MUST call at least one tool before giving your final response.
The repository item **${normProt}** is open in the Studio **content form** with client-side apply: for **that path only**, do **not** call **WriteContent**, **publish_content**, or **revert_change** (they are blocked) — use **`crafterqFormFieldUpdates`** in your **final** JSON for that item.
For **any other path**, you may call **WriteContent** (and publish/revert) as usual after **update_*** tools.
First output a **business-readable ## Plan** (**📋** per step, stakeholder-friendly — see system STUDIO POLICY). **Do not** write plan steps that only restate that you will run tools or obey policy — each **📋** line must name a **concrete visitor- or editor-visible outcome**. **Do not** call tools until that heading and steps are visible. Then **follow that plan**; after each tool refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only. When you narrate tool use in your own words, prefix with **🛠️**. Do **not** fake server-style tool log lines (see system STUDIO POLICY).
Do **not** paste full FreeMarker (`.ftl`) bodies or large XML dumps into the author's chat — summarize outcomes.
Your **final** reply after tools must state **success or problems** using **✅** / **❌** / **⚠️**, include a **clear business-friendly** recap under **## Plan Execution** — **lead with** one short **done + now wrapping up** line, then markers — and **ask what's next**.
When the author only asked to **update content** — **field values and item XML / static-assets**, not template or schema **file edits** — use **update_content** / **GetContent** — **do not** call **update_template** or **update_content_type** to fix those tasks. You **may** use **analyze_template** or **GetContent** on `.ftl` **read-only** to diagnose; if the issue is **in the template**, **tell the author** — do not patch FTL without explicit consent to change templates. **Page-level** translate/rewrite: update the **page** and **each referenced component** with visible text (not the page file alone) unless the author limited scope.
[/TOOL-GUARD]

""" + p
    }
    return '''[TOOL-GUARD]
This user request is a content/template/config modification task.
You MUST call at least one tool before giving your final response.
Do not respond with prose-only output for this request (no final answer that skips tools).
First output a **business-readable ## Plan** (**📋** per step — see system STUDIO POLICY). **Do not** write plan steps that only restate that you will run tools or obey policy — each **📋** line must name a **concrete visitor- or editor-visible outcome**. **Do not** call tools until that heading and steps are visible. Then **follow that plan**; after each tool refresh the **same** **📋** lines with **✅** / **❌** / **⚠️** / **⬜** only — keep the step list stable. When you narrate tool use in your own words, prefix with **🛠️**. Do **not** fake server-style tool log lines (see system STUDIO POLICY).
Do **not** paste full FreeMarker (`.ftl`) bodies or large XML dumps into the author's chat — summarize what was saved; they edit files in Studio.
If target path/id is unclear and the user message does not include **Studio authoring context** with a current repository path, call discovery tools first.
After **update_content**, **update_template**, or **update_content_type** returns, you must still call **WriteContent** with the full file — those tools do not save.
When the author only asked to **update content** — **field values and item XML / static-assets**, not template or schema **file edits** — use **update_content** → **WriteContent** on the **content item** path — **do not** call **update_template** or **update_content_type** to fix those tasks. You **may** use **analyze_template** or **GetContent** on `.ftl` **read-only** to diagnose; if the issue is **in the template**, **tell the author** — do not patch FTL without explicit consent to change templates. For **page-wide** translate/rewrite, call **GetContent**/**update_content** and **WriteContent** for the **page** and **each referenced component** (see system **“This page”** rule) unless the author limited scope.
Your **final** reply after tools must state **success or problems** using **✅** / **❌** / **⚠️**, include a **clear business-friendly** recap under **## Plan Execution** with those markers — **first** a tight **main outcome shipped; below is scorecard** cue — and **ask what's next**—not only mid-flight progress.
For **content XML** (pages/components): do not invent a new element tree — preserve `<page>`/`<component>` and field tags from the current file and content type (`formFieldIds` / GetContentTypeFormDefinition). For GetContentTypeFormDefinition use **contentPath** (item XML path) or copy **contentTypeId** from `<content-type>` — never infer content type from filename.
[/TOOL-GUARD]

''' + p
  }

  /**
   * Convert tool result to string for the model after a tool call.
   * <p>{@link GetContent} results are shortened to raw {@code contentXml} (token savings).
   * Preparatory tools ({@code update_content}, {@code update_template}, …) embed {@code nextStep} /
   * {@code promptGuidance}; those maps must <strong>not</strong> be collapsed to XML-only or the model
   * never sees that it must call {@code WriteContent} to persist.</p>
   */
  private String mapResultToString(Object result, java.lang.reflect.Type returnType) {
    return toolResultToWireString(result, returnType)
  }

  /**
   * Shared tool callback → wire string (Spring AI tool result converter) for chat and headless runs.
   */
  static String toolResultToWireString(Object result, java.lang.reflect.Type returnType) {
    if (result instanceof Map) {
      def m = (Map) result
      if (m?.nextStep != null || m?.promptGuidance != null) {
        return JsonOutput.toJson(m)
      }
      if (m?.contentXml != null) return m.contentXml as String
      if (m?.formDefinitionXml != null) return m.formDefinitionXml as String
      if ('GenerateImage'.equals(m?.tool?.toString())) {
        def u = m.url?.toString()
        if (u && u.startsWith('data:image') && m.containsKey('b64_json')) {
          Map m2 = new LinkedHashMap<>(m)
          m2.remove('b64_json')
          return JsonOutput.toJson(m2)
        }
      }
    }
    return JsonOutput.toJson(result != null ? result : [])
  }

  /** Delegates to {@link StudioAiLlmKind#normalize(String)} — stable entry for REST scripts. */
  static String normalizeLlmProvider(String raw) {
    return StudioAiLlmKind.normalize(raw)
  }

  /**
   * Resolution order: {@code OPENAI_API_KEY} env, {@code crafter.openai.apiKey} JVM,
   * {@code OPENAI_API_KEY} JVM, then optional {@code fromWidgetOrRequest} (ui.xml / POST body — testing only).
   */
  static String resolveOpenAiApiKey(String fromWidgetOrRequest = null) {
    def fromEnv = System.getenv('OPENAI_API_KEY')
    if (fromEnv?.trim()) return fromEnv.trim()
    def p = System.getProperty('crafter.openai.apiKey')
    if (p?.trim()) return p.trim()
    p = System.getProperty('OPENAI_API_KEY')
    if (p?.trim()) return p.trim()
    def w = (fromWidgetOrRequest ?: '').toString().trim()
    return w ?: ''
  }

  /**
   * For logs only: which path {@link #resolveOpenAiApiKey} took (mirrors resolution order; no secret material).
   */
  static String openAiApiKeyResolutionSource() {
    if (System.getenv('OPENAI_API_KEY')?.toString()?.trim()) return 'OPENAI_API_KEY(env)'
    if (System.getProperty('crafter.openai.apiKey')?.trim()) return 'crafter.openai.apiKey(jvm)'
    if (System.getProperty('OPENAI_API_KEY')?.trim()) return 'OPENAI_API_KEY(jvm)'
    return 'widget-or-request'
  }

  /**
   * For logs only: leading + trailing characters of the key (never the full secret; middle elided).
   * Uses a longer tail than 4 chars so typical OpenAI key suffixes (e.g. last 6) are visible for verification.
   */
  static String openAiApiKeyLogPreview(String key) {
    def k = (key ?: '').toString().trim()
    if (!k) return '(empty)'
    int n = k.length()
    int showTail = n >= 48 ? 8 : (n >= 20 ? 6 : Math.min(4, Math.max(2, n.intdiv(4))))
    int showHead = Math.min(12, n - showTail - 1)
    if (showHead < 1) {
      showHead = 1
    }
    if (showHead + showTail >= n) {
      showTail = n - showHead - 1
    }
    if (showTail < 1) {
      return k.substring(0, 1) + '…' + k.substring(n - 1)
    }
    return k.substring(0, showHead) + '…' + k.substring(n - showTail)
  }

  /** Plain text from a Spring AI {@code Message} for request logging (best-effort across M6 shapes). */
  private static String openAiMessagePlainTextForLog(Object m) {
    if (m == null) return ''
    try {
      if (m.metaClass.respondsTo(m, 'getText')) {
        def t = m.getText()
        if (t != null) return t.toString()
      }
    } catch (Throwable ignored) {}
    try {
      if (m.metaClass.respondsTo(m, 'getContent')) {
        def c = m.getContent()
        if (c != null) return c.toString()
      }
    } catch (Throwable ignored) {}
    try {
      return m.text?.toString() ?: ''
    } catch (Throwable ignored) {}
    return m.toString()
  }

  private static List openAiChatMessagesWireShape(Prompt prompt) {
    def out = []
    if (prompt == null) return out
    def list = null
    try {
      list = prompt.getInstructions()
    } catch (Throwable ignored) {
      try {
        list = prompt.instructions
      } catch (Throwable ignored2) {}
    }
    if (!list) return out
    list.each { msg ->
      String role = 'user'
      try {
        def n = msg?.getClass()?.name ?: ''
        if (n.endsWith('SystemMessage') || n.contains('.SystemMessage')) role = 'system'
        else if (n.endsWith('UserMessage') || n.contains('.UserMessage')) role = 'user'
        else if (n.endsWith('AssistantMessage') || n.contains('.AssistantMessage')) role = 'assistant'
      } catch (Throwable ignored) {}
      out << [role: role, content: openAiMessagePlainTextForLog(msg)]
    }
    out
  }

  /**
   * OpenAI {@code tools[]} shape (function name, description, parameters object) from Spring AI callbacks.
   * Omits api_key; mirrors Chat Completions body aside from Spring-only / optional fields.
   */
  private static List openAiToolsWireShape(def tools) {
    def out = []
    if (tools == null) return out
    def slurper = new JsonSlurper()
    (tools as List).each { t ->
      String name = ''
      String desc = ''
      Object params = [:]
      try {
        if (t?.metaClass?.respondsTo(t, 'getToolDefinition')) {
          def td = t.getToolDefinition()
          if (td != null) {
            try {
              if (td.metaClass.respondsTo(td, 'name')) name = td.name()?.toString()?.trim() ?: ''
            } catch (Throwable ignored) {}
            try {
              if (td.metaClass.respondsTo(td, 'description')) desc = (td.description() ?: '').toString()
            } catch (Throwable ignored) {}
            try {
              if (td.metaClass.respondsTo(td, 'inputSchema')) {
                def raw = td.inputSchema()?.toString()?.trim()
                if (raw) {
                  try {
                    params = slurper.parseText(raw)
                  } catch (Throwable ignored) {
                    params = [ _unparsedSchema: raw ]
                  }
                }
              }
            } catch (Throwable ignored) {}
          }
        }
      } catch (Throwable ignored) {}
      if (!name) {
        try {
          name = t?.name?.toString()?.trim() ?: ''
        } catch (Throwable ignored) {}
      }
      if (name) {
        out << [type: 'function', function: [name: name, description: desc, parameters: params ?: [:]]]
      }
    }
    out
  }

  /**
   * Pretty-printed JSON approximating POST {@code /v1/chat/completions} (no api_key).
   * Logged as separate records (envelope, messages, one record per tool) so nothing is split mid-string
   * — fixed-size slicing produced invalid JSON fragments in logs.
   */
  private void logOpenAiChatCompletionsPayloadApprox(String agentId, String resolvedModel, Prompt prompt, def tools) {
    if (!log.isDebugEnabled()) {
      return
    }
    try {
      def messages = openAiChatMessagesWireShape(prompt)
      def toolsList = openAiToolsWireShape(tools)
      def envelope = [
        model        : resolvedModel,
        stream       : true,
        messageCount : messages.size(),
        toolCount    : toolsList.size()
      ]
      // OpenAI: tool_choice is invalid unless tools[] is non-empty; omit from approx log when no tools.
      if (!toolsList.isEmpty()) {
        envelope.tool_choice = 'auto'
      }
      log.debug(
        'Tools-loop /v1/chat/completions outbound (approx) agentId={} envelope:\n{}',
        agentId,
        JsonOutput.prettyPrint(JsonOutput.toJson(envelope))
      )
      log.debug(
        'Tools-loop /v1/chat/completions outbound (approx) agentId={} messages:\n{}',
        agentId,
        JsonOutput.prettyPrint(JsonOutput.toJson([messages: messages]))
      )
      int n = toolsList.size()
      for (int i = 0; i < n; i++) {
        log.debug(
          'Tools-loop /v1/chat/completions outbound (approx) agentId={} tools[{}/{}]:\n{}',
          agentId,
          i + 1,
          n,
          JsonOutput.prettyPrint(JsonOutput.toJson(toolsList[i]))
        )
      }
    } catch (Throwable t) {
      log.warn('Tools-loop outbound JSON log failed: {}', t.message)
    }
  }

  /**
   * Turns Studio / agent display strings into OpenAI API model ids: lowercase, hyphens, no interior spaces
   * (e.g. {@code GPT-5.4 nano} → {@code gpt-5.4-nano}, {@code GPT 4o mini} → {@code gpt-4o-mini}).
   */
  static String openAiCanonicalizeApiModelToken(String raw) {
    if (raw == null || !raw.toString().trim()) {
      return ''
    }
    String s = openAiNormalizeModelIdForHeuristics(raw.toString().trim())
    s = s.replaceAll(/\s+/, '-')
    s = s.replace('_', '-')
    s = s.replaceAll(/-+/, '-')
    s = s.replaceAll(/^-+/, '')
    s = s.replaceAll(/-+$/, '')
    return s
  }

  /**
   * Canonical image model id for {@code POST /v1/images/generations}: {@link #openAiCanonicalizeApiModelToken(String)}
   * on trimmed input. Returns the raw parameter when blank or when canonicalization yields an empty string.
   */
  static String normalizeOpenAiImagesApiModelId(String modelIdRawOrCanonical) {
    if (modelIdRawOrCanonical == null || !modelIdRawOrCanonical.toString().trim()) {
      return modelIdRawOrCanonical
    }
    String canon = openAiCanonicalizeApiModelToken(modelIdRawOrCanonical.toString().trim())
    if (!canon) {
      return modelIdRawOrCanonical
    }
    return canon
  }

  /** Wire JSON body for {@code /v1/chat/completions}: read {@code model} for author-facing errors. */
  private static String openAiExtractWireModelFromChatCompletionsRequestJson(String jsonBody) {
    if (jsonBody == null || !jsonBody.toString().trim()) {
      return ''
    }
    try {
      def p = new JsonSlurper().parseText(jsonBody.toString())
      if (p instanceof Map) {
        return (p.get('model') ?: '').toString().trim()
      }
    } catch (Throwable ignored) {
    }
    return ''
  }

  private static boolean openAiResponseBodyLooksLikeInvalidModelId(String responseBody) {
    String b = (responseBody ?: '').toLowerCase(Locale.ROOT)
    return b.contains('invalid model')
  }

  /**
   * When OpenAI returns HTTP 400 for an unknown model id, surface a clear configuration error (no silent fallback model).
   */
  private static IllegalStateException openAiNewIllegalStateForInvalidOpenAiModel(String requestJsonBody, String responseBody) {
    String wireModel = openAiExtractWireModelFromChatCompletionsRequestJson(requestJsonBody)
    String apiMsg = ''
    try {
      def p = new JsonSlurper().parseText((responseBody ?: '').toString())
      if (p instanceof Map && p.get('error') instanceof Map) {
        apiMsg = (p.get('error').get('message') ?: '').toString().trim()
      }
    } catch (Throwable ignored) {
    }
    StringBuilder sb = new StringBuilder()
    sb.append('The LLM model is not accepted by the configured chat host. ')
    sb.append('The model id sent on the wire was "').append(wireModel ? wireModel : '(unknown)').append('". ')
    if (apiMsg) {
      sb.append('Provider message: ').append(apiMsg).append(' ')
    }
    sb.append(
      'Set the agent chat model to an id your host and API key support (for example in ui.xml / control payload), pass llmModel on the chat request, or set JVM crafter.openai.model when using the default OpenAI row.'
    )
    return new IllegalStateException(sb.toString())
  }

  /**
   * If {@code rce} is HTTP 400 with OpenAI "invalid model", return {@link IllegalStateException}; otherwise return {@code rce}.
   */
  private static Throwable openAiPreferIllegalStateForInvalidModel(RestClientResponseException rce, String requestJsonBody) {
    int code = 0
    try {
      code = rce.getStatusCode().value()
    } catch (Throwable ignored) {
    }
    String body = ''
    try {
      body = rce.getResponseBodyAsString(StandardCharsets.UTF_8) ?: ''
    } catch (Throwable ignored) {
    }
    if (code == 400 && openAiResponseBodyLooksLikeInvalidModelId(body)) {
      return openAiNewIllegalStateForInvalidOpenAiModel(requestJsonBody, body)
    }
    return rce
  }

  static String resolveOpenAiModel(String fromRequest) {
    String base = (fromRequest ?: '').toString().trim() ?: (System.getProperty('crafter.openai.model') ?: '').toString().trim()
    if (!base) {
      throw new IllegalStateException(
        'The chat model is not configured properly. Set the agent LLM / llmModel in Studio (for example ui.xml), pass llmModel on the chat request, or set JVM property crafter.openai.model when using the default OpenAI vendor row.'
      )
    }
    String canon = openAiCanonicalizeApiModelToken(base)
    if (!canon) {
      throw new IllegalStateException(
        "The chat model is not configured properly. The value could not be turned into an API model id: \"${base}\"."
      )
    }
    return canon
  }

  /**
   * True for {@code gpt-5*} chat models (including dated ids); false for {@code o1}/{@code o3}/{@code o4} reasoning lines.
   */
  private static boolean openAiModelIsGpt5Family(String model) {
    String m = openAiNormalizeModelIdForHeuristics(model)
    if (!m) {
      return false
    }
    if (m.startsWith('o1') || m.startsWith('o3') || m.startsWith('o4')) {
      return false
    }
    if (m.contains('gpt-5') || m.contains('gpt_5')) {
      return true
    }
    return Pattern.compile('(?i)gpt[^a-z0-9]*5').matcher(m).find()
  }

  /**
   * Max completion tokens for {@code TranslateContentItem} inner {@code /v1/chat/completions} calls (smaller → faster stop).
   * Default {@code 8192}; clamp {@code 1024–32768}. Override: {@code -Dcrafterq.translateContentItemMaxOutTokens=4096}.
   */
  static int resolveTranslateContentItemMaxOutTokens() {
    try {
      def p = System.getProperty('crafterq.translateContentItemMaxOutTokens')?.toString()?.trim()
      if (p) {
        int v = Integer.parseInt(p)
        return Math.max(1024, Math.min(32_768, v))
      }
    } catch (Throwable ignored) {
    }
    return 8192
  }

  /**
   * When bundled inner tools omit {@code llmModel}, pick a **smaller** model in the **same** OpenAI family as
   * {@code defaultChatModel} (main chat): e.g. {@code gpt-5-2025-08-07} → {@code gpt-5-nano}, {@code gpt-4o} → {@code gpt-4o-mini}.
   * Used by {@code TransformContentSubgraph} and {@code TranslateContentItem}.
   */
  static String openAiTransformSubgraphDefaultInnerModel(String defaultChatModel) {
    String raw = (defaultChatModel ?: '').trim()
    String m = openAiNormalizeModelIdForHeuristics(raw)
    if (!m) {
      throw new IllegalStateException(
        'The LLM model is not configured properly: the main chat model is missing, so Translate/Transform subgraph cannot choose an inner completion model. Set the agent chat model, or pass llmModel (or model) on the tool input.'
      )
    }
    if (openAiModelIsGpt5Family(raw)) {
      String pick = m.contains('nano') ? raw : 'gpt-5-nano'
      String c = openAiCanonicalizeApiModelToken(pick)
      if (!c) {
        throw new IllegalStateException(
          'The LLM model is not configured properly: could not derive an inner tools-loop model id from the main chat model.'
        )
      }
      return c
    }
    if (m.startsWith('o1') || m.startsWith('o3') || m.startsWith('o4')) {
      if (m.contains('mini')) {
        String c = openAiCanonicalizeApiModelToken(raw)
        if (!c) {
          throw new IllegalStateException(
            'The LLM model is not configured properly: could not normalize the main chat model to an inner tools-loop model id.'
          )
        }
        return c
      }
      if (m.startsWith('o4')) {
        return 'o4-mini'
      }
      if (m.startsWith('o3')) {
        return 'o3-mini'
      }
      return 'o1-mini'
    }
    if (m.contains('gpt-4') || m.contains('gpt4') || m.contains('4o')) {
      if (m.contains('mini') && m.contains('4o')) {
        String c = openAiCanonicalizeApiModelToken(raw)
        if (!c) {
          throw new IllegalStateException(
            'The LLM model is not configured properly: could not normalize the main chat model to an inner tools-loop model id.'
          )
        }
        return c
      }
      return 'gpt-4o-mini'
    }
    String c2 = openAiCanonicalizeApiModelToken(raw)
    if (!c2) {
      throw new IllegalStateException(
        'The LLM model is not configured properly: the main chat model could not be normalized to a chat wire model id.'
      )
    }
    return c2
  }

  /**
   * Image model for logging only: returns canonical id or {@code null} when the agent/request sent no {@code imageModel}.
   * No JVM-side override; only the request value is considered.
   */
  static String imageModelFromRequestOrNull(String fromRequest) {
    String base = (fromRequest ?: '').toString().trim()
    if (!base) {
      return null
    }
    String canon = openAiCanonicalizeApiModelToken(base)
    if (!canon) {
      throw new IllegalStateException(
        "The GenerateImage model is not configured properly. The value could not be turned into an API model id: \"${base}\"."
      )
    }
    return normalizeOpenAiImagesApiModelId(canon)
  }

  /**
   * OpenAI Images API model id (e.g. {@code gpt-image-1}). Source: agent **{@code <imageModel>}** or POST **{@code imageModel}** only.
   * Canonicalized via {@link #normalizeOpenAiImagesApiModelId(String)}.
   */
  static String resolveOpenAiImageModel(String fromRequest) {
    String base = (fromRequest ?: '').toString().trim()
    if (!base) {
      throw new IllegalStateException(
        'The GenerateImage model is not configured properly. Set imageModel on the agent (ui.xml element imageModel) or pass imageModel on the chat request JSON body.'
      )
    }
    String canon = openAiCanonicalizeApiModelToken(base)
    if (!canon) {
      throw new IllegalStateException(
        "The GenerateImage model is not configured properly. The value could not be turned into an API model id: \"${base}\"."
      )
    }
    return normalizeOpenAiImagesApiModelId(canon)
  }

  /** Per-request expert skill URLs from the client (see {@code crafterq.expertSkills} request attribute). */
  List<Map> readExpertSkillSpecsFromRequest() {
    try {
      def v = request?.getAttribute('crafterq.expertSkills')
      if (v instanceof List) {
        List<Map> out = new ArrayList<>()
        for (Object o : (List) v) {
          if (o instanceof Map) {
            out.add((Map) o)
          }
        }
        return out
      }
    } catch (Throwable ignored) {}
    return []
  }

  /** Max merged prompt length for remote hosted chat and {@code ConsultCrafterQExpert} (chars). JVM {@code -Dcrafterq.maxPromptChars} (plugin descriptor cannot declare this name in all Studio versions). */
  int resolveMaxCrafterQPromptChars() {
    try {
      def p = System.getProperty('crafterq.maxPromptChars')
      if (p != null && p.toString().trim()) {
        int n = Integer.parseInt(p.toString().trim())
        if (n >= 256 && n <= 500_000) return n
      }
    } catch (Throwable ignored) {}
    return 1000
  }

  /**
   * Builds the Spring AI chat client + tools via {@link StudioAiLlmRuntime} ({@link OpenAiSpringAiLlmRuntime} vs
   * {@link ExpertApiLlmRuntime}).
   */
  private Map buildSpringAiChatClient(
    String agentId,
    String chatId,
    String llmRaw,
    String openAiModelParam,
    String openAiApiKeyFromRequest = null,
    Closure toolProgressListener = null,
    String imageModelParam = null,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPath = null,
    boolean enableTools = true,
    String imageGeneratorParam = null
  ) {
    def converter = { Object result, java.lang.reflect.Type returnType -> toolResultToWireString(result, returnType) }
    /** Spring AI tool callbacks run on Reactor/HTTP-client threads; copy servlet SecurityContext for Studio permission checks. */
    def securityContextForTools = null
    try {
      def ctx = SecurityContextHolder.getContext()
      if (ctx != null && ctx.getAuthentication() != null) {
        def copy = SecurityContextHolder.createEmptyContext()
        copy.setAuthentication(ctx.getAuthentication())
        securityContextForTools = copy
      }
    } catch (Throwable ignored) {}
    int crafterQCap = resolveMaxCrafterQPromptChars()
    def studioOps = new StudioToolOperations(request, applicationContext, params, securityContextForTools, agentId, crafterQCap)
    String llmNorm = StudioAiLlmKind.normalize(llmRaw)
    Collection agentToolSubset = null
    try {
      def raw = request?.getAttribute('crafterq.agentEnabledBuiltInTools')
      if (raw instanceof Collection && !((Collection) raw).isEmpty()) {
        agentToolSubset = (Collection) raw
      }
    } catch (Throwable ignoredSubset) {}
    def req = new StudioAiRuntimeBuildRequest(
      orchestration: this,
      toolResultConverter: converter,
      studioOps: studioOps,
      crafterQServletRequest: request,
      agentId: agentId,
      chatId: chatId,
      llmNormalized: llmNorm,
      openAiModelParam: openAiModelParam,
      openAiApiKeyFromRequest: openAiApiKeyFromRequest,
      toolProgressListener: toolProgressListener,
      imageModelParam: imageModelParam,
      imageGeneratorParam: imageGeneratorParam,
      fullSuppressRepoWrites: fullSuppressRepoWrites,
      protectedFormItemPath: protectedFormItemPath,
      enableTools: enableTools,
      agentEnabledBuiltInTools: agentToolSubset
    )
    return StudioAiLlmRuntimeFactory.runtimeFor(llmNorm).buildSessionBundle(req)
  }

  /**
   * OpenAI authoring <strong>system</strong> text only — same assembly as {@link #openAiAuthoringPrompt} uses for
   * {@link SystemMessage}, without servlet {@code request}. Used by the autonomous worker (and keeps stream + headless aligned).
   *
   * @param expertSkillSpecsNormalized maps with {@code skillId}, {@code name}, {@code url}, {@code description}
   *        (e.g. {@link plugins.org.craftercms.aiassistant.rag.ExpertSkillVectorRegistry#normalizeRequestExpertSkills}); may be null or empty
   */
  static String openAiAuthoringSystemOnlyForHeadless(
    String siteId,
    String userTextForRagAdjust,
    StudioToolOperations studioOps,
    String openAiApiKey,
    boolean fullSuppressRepoWrites,
    String protectedFormItemPathNormalized,
    boolean toolSchemasOnApi,
    List expertSkillSpecsNormalized
  ) {
    String site = (siteId ?: '').toString().trim()
    String utEarly = (userTextForRagAdjust ?: '').toString()
    String normProt = AuthoringPreviewContext.normalizeRepoPath(protectedFormItemPathNormalized)
    def core = toolSchemasOnApi ? ToolPrompts.getOPENAI_AUTHORING_INSTRUCTIONS() : ToolPrompts.getOPENAI_CHAT_ONLY_SYSTEM()
    core = PluginRagVectorRegistry.adjustAuthoringCore(core, site, utEarly, studioOps, openAiApiKey, toolSchemasOnApi)
    String sys = core
    if (fullSuppressRepoWrites) {
      sys += ToolPrompts.OPENAI_FORM_ENGINE_SUPPRESS_REPO_WRITES
    } else if (normProt) {
      sys += ToolPrompts.openAiFormEngineProtectedItemAddendum(normProt)
    }
    if (site) {
      if (toolSchemasOnApi) {
        if (fullSuppressRepoWrites) {
          sys += "\n\nCurrent CrafterCMS site id: \"${site}\". Always pass siteId=\"${site}\" on GetContent, ListContentTranslationScope, ListStudioContentTypes, ListPagesAndComponents, GetContentTypeFormDefinition, and update_* tools unless the user explicitly names another site. Never use \"default\" as siteId."
        } else {
          sys += "\n\nCurrent CrafterCMS site id: \"${site}\". Always pass siteId=\"${site}\" on GetContent, ListContentTranslationScope, TranslateContentItem, TranslateContentBatch, WriteContent, ListStudioContentTypes, ListPagesAndComponents, GetContentTypeFormDefinition, publish_content, revert_change, and update_* tools unless the user explicitly names another site. Never use \"default\" as siteId."
        }
      } else {
        sys += "\n\nCurrent CrafterCMS site id: \"${site}\"."
      }
    }
    if (toolSchemasOnApi) {
      List exList = expertSkillSpecsNormalized
      if (exList != null && !exList.isEmpty()) {
        sys += ToolPrompts.expertSkillsRagAppendix(exList)
      }
    }
    if (toolSchemasOnApi && studioOps != null) {
      try {
        if (studioOps.isCrafterqAgentIdPresent()) {
          String cq = studioOps.crafterqApiAgentId()
          sys +=
            '\n\n**CrafterQ hosted-chat tools:** **ListCrafterQAgentChats** and **GetCrafterQAgentChat** are on the wire for this session. Default **agentId** = "' +
            cq +
            '" (from agent configuration) — **omit agentId** in tool arguments unless overriding. **ListCrafterQAgentChats:** you may pass **no arguments** (empty object) or only **limit** — the server fills **startDate**/**endDate** as the **last 30 days UTC** when both are omitted. **Hard match:** your **`tool_calls`** in the first tool round must include **ListCrafterQAgentChats** for hosted-chat analytics asks — **never** substitute **ListContentTranslationScope** or **GetContent** for that intent.'
        }
      } catch (Throwable ignored) {}
    }
    if (toolSchemasOnApi) {
      sys += PlanOrchestration.machineInstructionsAddendum()
    }
    sys
  }

  /**
   * Includes active site id when available. When {@code toolSchemasOnApi} is false, system text matches OpenAI requests
   * that omit function tools ({@code <enableTools>false</enableTools>}).
   */
  private Prompt openAiAuthoringPrompt(
    String userText,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPathNormalized = null,
    boolean toolSchemasOnApi = true,
    StudioToolOperations studioOps = null,
    String openAiApiKey = null
  ) {
    def site = ''
    try {
      site = request?.getAttribute('crafterq.siteId')?.toString()?.trim() ?: ''
      if (!site) site = request?.getParameter('siteId')?.toString()?.trim() ?: ''
      if (!site) site = request?.getParameter('crafterSite')?.toString()?.trim() ?: ''
      if (!site && params != null) {
        try {
          site = params['siteId']?.toString()?.trim() ?: ''
        } catch (Throwable e) {
          try {
            site = params.siteId?.toString()?.trim() ?: ''
          } catch (Throwable e2) {}
        }
      }
    } catch (Throwable ignored) {}

    def utEarly = (userText ?: '').toString()
    List exList = []
    try {
      def raw = request?.getAttribute('crafterq.expertSkills')
      if (raw instanceof List && !((List) raw).isEmpty()) {
        exList = (List) raw
      }
    } catch (Throwable ignored) {}
    String sys = openAiAuthoringSystemOnlyForHeadless(
      site,
      utEarly,
      studioOps,
      openAiApiKey,
      fullSuppressRepoWrites,
      protectedFormItemPathNormalized,
      toolSchemasOnApi,
      exList
    )
    def ut = utEarly
    if (toolSchemasOnApi) {
      ut = ToolPrompts.getOPENAI_USER_MESSAGE_TOOLS_POLICY_PREFIX() + ut
    }
    return new Prompt([
      new SystemMessage(sys),
      new UserMessage(ut)
    ])
  }

  /**
   * Build OpenAI wire messages for chat.completions (tools-off {@link RestClient} path) without going through
   * {@link OpenAiChatModel#createRequest} + {@code ModelOptionsUtils.merge}, which can drop the
   * {@code stream} flag on {@link ChatCompletionRequest} (record + merge) and break both streaming
   * (hung read) and non-streaming (SSE body parsed as JSON → JsonEOFException).
   */
  private static List<ChatCompletionMessage> openAiChatCompletionMessagesForApi(Prompt prompt) {
    def instr = null
    try {
      instr = prompt.getInstructions()
    } catch (Throwable ignored) {
      try {
        instr = prompt.instructions
      } catch (Throwable ignored2) {}
    }
    if (!instr) {
      return []
    }
    def out = []
    instr.each { msg ->
      ChatCompletionMessage.Role role = ChatCompletionMessage.Role.USER
      try {
        def n = msg?.getClass()?.name ?: ''
        if (n.endsWith('SystemMessage') || n.contains('.SystemMessage')) {
          role = ChatCompletionMessage.Role.SYSTEM
        } else if (n.endsWith('UserMessage') || n.contains('.UserMessage')) {
          role = ChatCompletionMessage.Role.USER
        } else if (n.endsWith('AssistantMessage') || n.contains('.AssistantMessage')) {
          role = ChatCompletionMessage.Role.ASSISTANT
        }
      } catch (Throwable ignored) {}
      def text = openAiMessagePlainTextForLog(msg)
      out << new ChatCompletionMessage(text, role)
    }
    out
  }

  /** One {@code data:} line from Tools-loop chat SSE — assistant text delta. */
  private static String openAiStreamChunkDeltaText(Object root) {
    if (!(root instanceof Map)) {
      return ''
    }
    Map m = root as Map
    def choices = m.get('choices')
    if (!(choices instanceof List) || choices.isEmpty()) {
      return ''
    }
    def c0 = choices[0]
    if (!(c0 instanceof Map)) {
      return ''
    }
    def delta = ((Map) c0).get('delta')
    if (!(delta instanceof Map)) {
      return ''
    }
    def content = ((Map) delta).get('content')
    if (content instanceof CharSequence) {
      return content.toString()
    }
    if (content instanceof List) {
      StringBuilder sb = new StringBuilder()
      for (def part : (List) content) {
        if (part instanceof Map) {
          Map pm = part as Map
          def t = pm.get('text')
          if (t != null) {
            sb.append(t.toString())
          }
        }
      }
      return sb.toString()
    }
    return content != null ? content.toString() : ''
  }

  private static String openAiStreamChunkFinishReason(Object root) {
    if (!(root instanceof Map)) {
      return ''
    }
    def choices = ((Map) root).get('choices')
    if (!(choices instanceof List) || choices.isEmpty()) {
      return ''
    }
    def c0 = choices[0]
    if (!(c0 instanceof Map)) {
      return ''
    }
    def fr = ((Map) c0).get('finish_reason')
    return fr != null ? fr.toString() : ''
  }

  private static String openAiStreamChunkOpenAiErrorMessage(Object root) {
    if (!(root instanceof Map)) {
      return ''
    }
    def err = ((Map) root).get('error')
    if (err instanceof Map) {
      def em = ((Map) err).get('message')
      return em != null ? em.toString() : err.toString()
    }
    if (err != null) {
      return err.toString()
    }
    return ''
  }

  /**
   * Reads OpenAI {@code text/event-stream} chat.completions chunks and forwards assistant deltas as Studio SSE.
   */
  private static void openAiCopyUpstreamSseChatCompletionsToStudio(
    InputStream upstream,
    OutputStream out,
    String agentId,
    String model
  ) {
    if (upstream == null) {
      throw new IllegalStateException('Tools-loop chat (stream): empty response body')
    }
    def slurper = new JsonSlurper()
    boolean completedSent = false
    BufferedReader br = null
    try {
      br = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8))
      String line
      while ((line = br.readLine()) != null) {
        if (!line.startsWith('data:')) {
          continue
        }
        def payload = line.substring(5).trim()
        if (!payload) {
          continue
        }
        if ('[DONE]' == payload) {
          break
        }
        Object chunk
        try {
          chunk = slurper.parseText(payload)
        } catch (Throwable pe) {
          log.warn(
            'Tools-loop tools-off SSE: skip unparseable line agentId={} model={} line=\n{}',
            agentId,
            model,
            AiHttpProxy.elideForLog(payload, 500)
          )
          continue
        }
        def errMsg = openAiStreamChunkOpenAiErrorMessage(chunk)
        if (errMsg) {
          def ev = [text: '', metadata: [error: true, completed: true, message: 'Chat host: ' + errMsg]]
          synchronized (out) {
            out.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
          }
          completedSent = true
          return
        }
        def delta = openAiStreamChunkDeltaText(chunk)
        if (delta) {
          synchronized (out) {
            out.write(("data: ${JsonOutput.toJson([text: delta, metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
          }
        }
        def fr = openAiStreamChunkFinishReason(chunk)
        if (openAiFinishReasonImpliesStreamDone(fr)) {
          synchronized (out) {
            out.write(("data: ${JsonOutput.toJson([text: '', metadata: [completed: true]])}\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
          }
          completedSent = true
          break
        }
      }
    } finally {
      try {
        br?.close()
      } catch (Throwable ignored) {}
    }
    if (!completedSent) {
      synchronized (out) {
        out.write(("data: ${JsonOutput.toJson([text: '', metadata: [completed: true]])}\n\n").getBytes(StandardCharsets.UTF_8))
        out.flush()
      }
    }
  }

  private static Map openAiWireMessageFromChatCompletionMessage(ChatCompletionMessage cm) {
    if (cm == null) {
      return [:]
    }
    def roleStr = cm.role() != null ? cm.role().name().toLowerCase() : 'user'
    def c = null
    try {
      c = cm.content()
    } catch (Throwable ignored) {}
    def text = c != null ? c.toString() : ''
    return [role: roleStr, content: text]
  }

  private static List<Map> openAiBuildWireToolsFromCallbacks(List tools) {
    def out = []
    if (!tools) {
      return out
    }
    def slurper = new JsonSlurper()
    tools.each { t ->
      if (t instanceof FunctionToolCallback) {
        def td = t.getToolDefinition()
        if (td == null) {
          return
        }
        Object paramsObj
        try {
          def schema = td.inputSchema()
          paramsObj = schema ? slurper.parseText(schema.toString()) : null
        } catch (Throwable ignored) {
          paramsObj = null
        }
        if (!(paramsObj instanceof Map)) {
          paramsObj = [type: 'object', properties: [:]]
        }
        out << [
          type: 'function',
          function: [
            name: td.name(),
            description: (td.description() ?: '') as String,
            parameters: paramsObj
          ]
        ]
      }
    }
    out
  }

  private static Map<String, FunctionToolCallback> openAiToolCallbacksByName(List tools) {
    Map<String, FunctionToolCallback> m = new LinkedHashMap<>()
    if (!tools) {
      return m
    }
    tools.each { t ->
      if (t instanceof FunctionToolCallback) {
        def td = t.getToolDefinition()
        if (td?.name()) {
          m.put(td.name(), (FunctionToolCallback) t)
        }
      }
    }
    m
  }

  private static boolean openAiChoiceMessageHasToolCalls(Map msg) {
    if (!(msg instanceof Map)) {
      return false
    }
    def tc = msg.get('tool_calls')
    return tc instanceof List && !((List) tc).isEmpty()
  }

  private static String openAiAssistantTextFromChoiceMessageMap(Map msg) {
    if (!(msg instanceof Map)) {
      return ''
    }
    def refusal = msg.get('refusal')
    if (refusal != null && refusal.toString().trim()) {
      return refusal.toString()
    }
    def c = msg.get('content')
    if (c instanceof CharSequence) {
      return c.toString()
    }
    if (c instanceof List) {
      StringBuilder sb = new StringBuilder()
      for (def part : (List) c) {
        if (part instanceof Map) {
          Map pm = (Map) part
          def t = pm.get('text')
          if (t == null && pm.containsKey('content')) {
            t = pm.get('content')
          }
          if (t instanceof CharSequence && t.toString().trim()) {
            sb.append(t.toString())
          } else if (t instanceof List) {
            for (def inner : (List) t) {
              if (inner instanceof Map && ((Map) inner).get('text') != null) {
                sb.append(((Map) inner).get('text').toString())
              }
            }
          }
        } else if (part instanceof CharSequence) {
          sb.append(part.toString())
        }
      }
      return sb.toString()
    }
    return c != null ? c.toString() : ''
  }

  /**
   * Policy: never stream {@code ## Plan Execution} in the same assistant message as {@code tool_calls}
   * (models sometimes recap before tools finish). Strip from the first {@code ## Plan Execution} onward.
   */
  private static String openAiStripPlanExecutionWhenToolCallsPresent(String flat, boolean hasToolCalls) {
    if (!hasToolCalls) {
      return flat
    }
    if (flat == null || !flat.toString().trim()) {
      return flat == null ? '' : flat.toString()
    }
    String s = flat.toString()
    int idx = s.indexOf('## Plan Execution')
    if (idx < 0) {
      return s
    }
    return s.substring(0, idx).replaceAll(/\s+$/, '').trim()
  }

  /** Strips {@link PlanOrchestration} machine block from assistant {@code content} before wire history / next OpenAI round. */
  private static void openAiMutateAssistantContentStripOrchestratorBlock(Map msgCopy) {
    if (!(msgCopy instanceof Map)) {
      return
    }
    boolean hasTc = openAiChoiceMessageHasToolCalls(msgCopy)
    String flat = openAiAssistantTextFromChoiceMessageMap(msgCopy)
    String stripped = PlanOrchestration.stripOrchestrationPlanBlock(flat)
    stripped = openAiStripPlanExecutionWhenToolCallsPresent(stripped, hasTc)
    if (stripped != flat) {
      msgCopy.put('content', stripped)
    }
  }

  /**
   * Normalizes model ids for API-compat heuristics: lower case, strip soft hyphen, map Unicode hyphens to ASCII
   * so {@code gpt‑5} (U+2011) still matches {@code gpt-5} token checks.
   */
  private static String openAiNormalizeModelIdForHeuristics(String model) {
    if (model == null) {
      return ''
    }
    String s = model.toString().trim().toLowerCase(Locale.ROOT)
    s = s.replace('\u2011', '-').replace('\u2010', '-').replace('\u2212', '-')
    s = s.replace('\u00ad', '')
    // Zero-width / format chars that break naive `contains('gpt-5')` matching on some agent configs.
    s = s.replaceAll(/[\u200B-\u200D\uFEFF\u2060]/, '')
    return s
  }

  /**
   * Reasoning / gpt-5 family: {@code max_completion_tokens} instead of {@code max_tokens}, and non-default
   * {@code temperature} rejected (400 {@code unsupported_value}).
   */
  private static boolean openAiModelNeedsNeoChatCompletionWireParams(String model) {
    String m = openAiNormalizeModelIdForHeuristics(model)
    if (!m) {
      return false
    }
    // o-series + gpt-5 family: non-default temperature rejected (400 unsupported_value); use max_completion_tokens not max_tokens.
    // Dated ids like gpt-5-2025-08-07 normalize to lowercase and still contain "gpt-5".
    if (m.startsWith('o1') ||
      m.startsWith('o3') ||
      m.startsWith('o4') ||
      m.contains('gpt-5') ||
      m.contains('gpt_5')) {
      return true
    }
    // Rare aliases / exotic separators between "gpt" and "5" (still the fixed-temperature family).
    return Pattern.compile('(?i)gpt[^a-z0-9]*5').matcher(m).find()
  }

  /**
   * Output token limit map for {@code /v1/chat/completions}. Uses {@code max_completion_tokens} for OpenAI
   * reasoning / gpt-5 family models, or when {@link StudioAiLlmKind#toolsLoopChatPreferMaxCompletionTokensFromBundle} is true
   * on the script session bundle. Other hosts use {@code max_tokens}.
   *
   * @param toolsLoopSessionBundle optional map from {@code StudioAiLlmRuntime#buildSessionBundle} (script or future built-ins)
   */
  private static Map openAiChatCompletionOutputLimitParams(String model, int cap, Map toolsLoopSessionBundle = null) {
    if (StudioAiLlmKind.toolsLoopChatPreferMaxCompletionTokensFromBundle(toolsLoopSessionBundle)) {
      return [max_completion_tokens: cap]
    }
    if (openAiModelNeedsNeoChatCompletionWireParams(model)) {
      return [max_completion_tokens: cap]
    }
    return [max_tokens: cap]
  }

  /**
   * Models wired with {@code max_tokens} (non-neo) cap completion output below 32k (e.g. {@code gpt-4o-mini} ~16k);
   * larger values yield HTTP 400 from OpenAI.
   */
  private static int openAiClampMaxOutTokensForChatCompletionsModel(String model, int requested) {
    if (requested <= 0) {
      return 4096
    }
    if (openAiModelNeedsNeoChatCompletionWireParams(model)) {
      return Math.min(requested, 128_000)
    }
    return Math.min(requested, 16_384)
  }

  /**
   * Native tools-loop sync POSTs default a high completion budget ({@code 16000}); hosts may reject large values.
   * Optional {@link StudioAiLlmKind#toolsLoopChatMaxCompletionOutTokensFromBundle} caps completion output for script LLMs.
   */
  private static int openAiClampMaxOutTokensForToolsLoopWire(String model, int requested, Map toolsLoopSessionBundle = null) {
    int r = requested > 0 ? requested : 8192
    Integer scriptCap = StudioAiLlmKind.toolsLoopChatMaxCompletionOutTokensFromBundle(toolsLoopSessionBundle)
    if (scriptCap != null) {
      int out = Math.min(r, scriptCap)
      if (out < r) {
        log.debug(
          'Tools-loop: completion out tokens clamped requested={} -> {} (model={}; bundle {}={})',
          r,
          out,
          model,
          StudioAiLlmKind.BUNDLE_TOOLS_LOOP_CHAT_MAX_COMPLETION_OUT_TOKENS,
          scriptCap
        )
      }
      return out
    }
    return openAiClampMaxOutTokensForChatCompletionsModel(model, r)
  }

  private static void openAiTruncateToolsLoopWireToolTopLevelDescriptions(List wireTools, int maxLen) {
    if (!(wireTools instanceof List) || maxLen < 40) {
      return
    }
    for (def t : (List) wireTools) {
      if (!(t instanceof Map)) {
        continue
      }
      Map tm = (Map) t
      def fn = tm.get('function')
      if (!(fn instanceof Map)) {
        continue
      }
      Map fm = (Map) fn
      String d = fm.get('description')?.toString() ?: ''
      if (d.length() > maxLen) {
        fm.put('description', d.substring(0, Math.max(0, maxLen - 1)) + '…')
      }
    }
  }

  private static void openAiShrinkToolsLoopJsonSchemaDescriptionStrings(Object node, int maxLen) {
    if (node == null || maxLen < 8) {
      return
    }
    if (node instanceof Map) {
      Map map = (Map) node
      for (Object k : new ArrayList<>(map.keySet())) {
        Object val = map.get(k)
        String key = k != null ? k.toString() : ''
        if ('description'.equals(key) && val instanceof CharSequence) {
          String s = val.toString()
          if (s.length() > maxLen) {
            map.put(k, s.substring(0, Math.max(0, maxLen - 1)) + '…')
          }
        } else {
          openAiShrinkToolsLoopJsonSchemaDescriptionStrings(val, maxLen)
        }
      }
    } else if (node instanceof List) {
      for (Object item : (List) node) {
        openAiShrinkToolsLoopJsonSchemaDescriptionStrings(item, maxLen)
      }
    }
  }

  private static void openAiClearToolsLoopJsonSchemaDescriptionStrings(Object node) {
    if (node == null) {
      return
    }
    if (node instanceof Map) {
      Map map = (Map) node
      for (Object k : new ArrayList<>(map.keySet())) {
        if ('description'.equals(k?.toString())) {
          map.remove(k)
        } else {
          openAiClearToolsLoopJsonSchemaDescriptionStrings(map.get(k))
        }
      }
    } else if (node instanceof List) {
      for (Object item : (List) node) {
        openAiClearToolsLoopJsonSchemaDescriptionStrings(item)
      }
    }
  }

  private static void openAiCapToolsLoopWireMessageContents(List<Map> wireMessages, int maxPerMessage) {
    if (!(wireMessages instanceof List) || maxPerMessage < 256) {
      return
    }
    int lastUserIdx = -1
    for (int i = 0; i < wireMessages.size(); i++) {
      Map m = wireMessages.get(i) as Map
      if (m != null && 'user'.equalsIgnoreCase((m.get('role') ?: '').toString().trim())) {
        lastUserIdx = i
      }
    }
    for (int i = 0; i < wireMessages.size(); i++) {
      Map m = wireMessages.get(i) as Map
      if (m == null) {
        continue
      }
      def c = m.get('content')
      if (!(c instanceof CharSequence)) {
        continue
      }
      String s = c.toString()
      int cap = maxPerMessage
      if (i == lastUserIdx) {
        cap = (int) Math.min((long) maxPerMessage * 2L, 200_000L)
      }
      if (s.length() > cap) {
        int reserve = Math.min(220, Math.max(48, (int) (cap * 0.14d)))
        int head = Math.max(64, cap - reserve)
        m.put(
          'content',
          s.substring(0, head) +
            '\n\n[crafterq: content truncated for tools-loop request size; originalChars=' +
            s.length() +
            ']\n'
        )
      }
    }
  }

  /**
   * When {@code maxWireChars > 0} and serialized {@code reqMap} exceeds that budget, shrinks tool copy + message text in place.
   * Session bundle key: {@link StudioAiLlmKind#BUNDLE_TOOLS_LOOP_CHAT_MAX_WIRE_PAYLOAD_CHARS} (set from site script for strict hosts).
   */
  private static void openAiShrinkToolsLoopWirePayloadIfOverBudget(Map reqMap, List<Map> wireMessages, List wireTools, int maxWireChars) {
    if (maxWireChars <= 0) {
      return
    }
    String body
    try {
      body = JsonOutput.toJson(reqMap)
    } catch (Throwable t) {
      return
    }
    int n = body.length()
    if (n <= maxWireChars) {
      return
    }
    log.warn(
      'Tools-loop: wire JSON large ({} chars > cap {}); shrinking tool + message payload before POST',
      n,
      maxWireChars
    )
    for (topDesc in [2000, 900, 450, 220, 120]) {
      openAiTruncateToolsLoopWireToolTopLevelDescriptions(wireTools, topDesc as int)
      body = JsonOutput.toJson(reqMap)
      n = body.length()
      if (n <= maxWireChars) {
        log.info('Tools-loop: shrink ok after top-level tool description cap={} newChars={}', topDesc, n)
        return
      }
    }
    for (sl in [360, 180, 90]) {
      for (def t : (List) wireTools) {
        if (!(t instanceof Map)) {
          continue
        }
        def fn = ((Map) t).get('function')
        if (fn instanceof Map) {
          def params = ((Map) fn).get('parameters')
          openAiShrinkToolsLoopJsonSchemaDescriptionStrings(params, sl as int)
        }
      }
      body = JsonOutput.toJson(reqMap)
      n = body.length()
      if (n <= maxWireChars) {
        log.info('Tools-loop: shrink ok after JSON-schema description cap={} newChars={}', sl, n)
        return
      }
    }
    for (def t : (List) wireTools) {
      if (!(t instanceof Map)) {
        continue
      }
      def fn = ((Map) t).get('function')
      if (fn instanceof Map) {
        def params = ((Map) fn).get('parameters')
        openAiClearToolsLoopJsonSchemaDescriptionStrings(params)
      }
    }
    body = JsonOutput.toJson(reqMap)
    n = body.length()
    if (n <= maxWireChars) {
      log.info('Tools-loop: shrink ok after stripping nested JSON-schema descriptions newChars={}', n)
      return
    }
    for (mc in [
      48_000, 28_000, 18_000, 12_000, 9000, 6000, 5000, 4000, 3200, 2600, 2000, 1600, 1200, 900, 768, 640, 512, 448, 384, 320, 288, 256
    ]) {
      openAiCapToolsLoopWireMessageContents(wireMessages, mc as int)
      body = JsonOutput.toJson(reqMap)
      n = body.length()
      if (n <= maxWireChars) {
        log.info('Tools-loop: shrink ok after message content cap={} newChars={}', mc, n)
        return
      }
    }
    log.warn(
      'Tools-loop: wire JSON still large after shrink ({} chars > cap {}); upstream may reject the request',
      n,
      maxWireChars
    )
  }

  /**
   * o1 / o3 / o4 / gpt-5* reject non-default {@code temperature} (400 {@code unsupported_value}); omit the field so the API default applies.
   */
  private static Map openAiChatCompletionTemperatureParams(String model, double valueWhenSupported) {
    if (!openAiNormalizeModelIdForHeuristics(model)) {
      return [temperature: valueWhenSupported]
    }
    if (openAiModelNeedsNeoChatCompletionWireParams(model)) {
      return [:]
    }
    return [temperature: valueWhenSupported]
  }

  /**
   * Last line of defense: some Studio builds / classpath merges have still sent {@code temperature} for gpt-5/o
   * and OpenAI returns 400. Parse wire JSON and drop {@code temperature} when {@link #openAiModelNeedsNeoChatCompletionWireParams} applies.
   */
  /**
   * Last-resort strip when JsonSlurper path fails or another layer reintroduces {@code temperature}.
   * Keeps JSON valid for typical Groovy {@link JsonOutput} shapes (single chat.completions object).
   */
  private static String openAiChatCompletionJsonStripTemperatureRegex(String jsonBody) {
    if (!jsonBody?.toString()?.trim() || !jsonBody.contains('temperature')) {
      return jsonBody.toString()
    }
    String s = jsonBody.toString()
    s = s.replaceAll(/,\s*"temperature"\s*:\s*-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/, '')
    s = s.replaceAll(/"temperature"\s*:\s*-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\s*,/, '')
    s = s.replaceAll(/"temperature"\s*:\s*-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/, '')
    return s
  }

  private static String openAiChatCompletionJsonStripTemperatureForNeoModel(String model, String jsonBody) {
    if (!jsonBody?.toString()?.trim() || !openAiModelNeedsNeoChatCompletionWireParams(model)) {
      return jsonBody.toString()
    }
    if (!jsonBody.contains('temperature')) {
      return jsonBody.toString()
    }
    String out = jsonBody.toString()
    try {
      def parsed = new JsonSlurper().parseText(out)
      if (!(parsed instanceof Map)) {
        out = openAiChatCompletionJsonStripTemperatureRegex(out)
        return out
      }
      Map m = new LinkedHashMap((Map) parsed)
      if (m.remove('temperature') != null) {
        log.warn(
          'openAiChatCompletionJsonStripTemperatureForNeoModel: removed temperature from chat.completions body (model={})',
          model
        )
      }
      out = JsonOutput.toJson(m)
    } catch (Throwable t) {
      log.warn('openAiChatCompletionJsonStripTemperatureForNeoModel: parse failed — regex strip fallback: {}', t.message)
      out = openAiChatCompletionJsonStripTemperatureRegex(out)
    }
    if (out.contains('temperature')) {
      log.warn(
        'openAiChatCompletionJsonStripTemperatureForNeoModel: temperature still present after strip — second regex pass (model={})',
        model
      )
      out = openAiChatCompletionJsonStripTemperatureRegex(out)
    }
    return out
  }

  /** Emits one {@code tool-progress} SSE line (same channel as 🛠️ repo tool rows) for long-running OpenAI phases. */
  private static void openAiEmitSseToolProgressLine(OutputStream o, String markdownLine, String phase) {
    if (o == null || !markdownLine?.toString()?.trim()) {
      return
    }
    try {
      def ev = [
        text    : markdownLine.toString(),
        metadata: [status: 'tool-progress', tool: 'Tools-loop chat', phase: (phase ?: 'start').toString()]
      ]
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * Long-wait keepalive for the Tools-loop+tools worker: **does not** append a markdown line to the tool log — the Studio
   * client shows a single animated row that this frame **updates** in place.
   * <p>{@link Number} parameters accept Groovy {@code /} results (often {@link BigDecimal}) as well as {@code long}.</p>
   */
  private static void openAiEmitSsePipelineHeartbeat(OutputStream o, Number elapsedSec, Number nextInSec, String hintMd) {
    if (o == null) {
      return
    }
    long el = elapsedSec != null ? elapsedSec.longValue() : 0L
    long nx = (nextInSec != null && nextInSec.longValue() > 0L) ? nextInSec.longValue() : 12L
    try {
      def ev = [
        text    : '',
        metadata: [
          status    : 'pipeline-heartbeat',
          elapsedSec: el,
          nextInSec : nx,
          hint      : hintMd?.toString() ?: ''
        ]
      ]
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(ev)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
    }
  }

  /** Escapes ``` so wrapping {@code raw} in a Markdown ``` fence does not break Studio rendering. */
  private static String openAiEscapeTripleBackticksForMarkdownFence(String raw) {
    if (raw == null) {
      return ''
    }
    return raw.toString().replace('```', '\\`\\`\\`')
  }

  /**
   * Tool-progress debug wraps assistant text in a {@code ```text} fence with a size cap (~12k). Inline
   * {@code data:image/...;base64,...} payloads are often hundreds of KB — they (a) blow the cap mid-base64 so the
   * debug panel shows misleading garbage, and (b) duplicate the real preview in the final assistant markdown below.
   * Replace each with a short note so authors see intent without truncated ciphertext.
   */
  private static String openAiElideDataImageUrlsForToolProgressDebug(String raw) {
    if (raw == null || raw.isEmpty() || raw.indexOf('data:image') < 0) {
      return raw ?: ''
    }
    Pattern p = Pattern.compile('(?is)data:image/[a-z0-9.+-]+;base64,')
    Matcher mat = p.matcher(raw)
    StringBuilder out = new StringBuilder(Math.min(raw.length(), 200_000))
    int pos = 0
    int len = raw.length()
    while (pos < len) {
      mat.region(pos, len)
      if (!mat.find()) {
        out.append(raw, pos, len)
        break
      }
      out.append(raw, pos, mat.start())
      int payloadStart = mat.end()
      int i = payloadStart
      while (i < len) {
        char c = raw.charAt(i)
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=' ||
          c == '\n' || c == '\r' || c == ' ' || c == '\t') {
          i++
          continue
        }
        break
      }
      out.append('[inline image omitted from tool-progress debug (')
        .append(i - payloadStart)
        .append(' base64 chars); full image renders in the assistant reply below]')
      pos = i
    }
    return out.toString()
  }

  /**
   * Emits one {@code tool-progress} chunk with the **flattened assistant {@code content}** OpenAI returned for this
   * round plus each {@code tool_calls} entry (name + arguments preview). Gives authors visibility into the model
   * reply before repository tools execute.
   */
  private static void openAiEmitSseAssistantTurnDebugPreview(
    OutputStream o,
    String assistantFlatAsReceived,
    Map msgCopy,
    boolean hasTc,
    int zeroBasedRound,
    String agentId
  ) {
    if (o == null) {
      return
    }
    final int maxAssistant = 12000
    String body = openAiElideDataImageUrlsForToolProgressDebug((assistantFlatAsReceived ?: '').toString())
    boolean truncated = false
    if (body.length() > maxAssistant) {
      body = body.substring(0, maxAssistant)
      truncated = true
    }
    if (truncated) {
      body = body + '\n… [truncated for chat preview]'
    }
    body = openAiEscapeTripleBackticksForMarkdownFence(body)
    StringBuilder sb = new StringBuilder(Math.min(65536, body.length() + 2048))
    sb.append('🛠️🔍 **Tools-loop** — **assistant** message (tool loop round ').append(zeroBasedRound + 1).append(')\n\n')
    sb.append('**Assistant `content` (flattened, as returned):** ')
    if (!body.trim()) {
      sb.append('*(empty)*\n')
    } else {
      sb.append('\n```text\n').append(body).append('\n```\n')
    }
    if (hasTc) {
      sb.append('\n**`tool_calls`:**\n')
      def tcl = msgCopy != null ? msgCopy.get('tool_calls') : null
      if (tcl instanceof List && !((List) tcl).isEmpty()) {
        int idx = 0
        for (def tcObj : (List) tcl) {
          if (!(tcObj instanceof Map)) {
            continue
          }
          Map tc = (Map) tcObj
          idx++
          String id = tc.get('id')?.toString() ?: ''
          def fn = tc.get('function')
          String fnName = fn instanceof Map ? (fn.get('name')?.toString() ?: '') : ''
          String args = fn instanceof Map ? (fn.get('arguments')?.toString() ?: '') : ''
          if (args.length() > 4000) {
            args = args.substring(0, 3997) + '…'
          }
          args = openAiEscapeTripleBackticksForMarkdownFence(args)
          sb.append('\n').append(idx).append('. `').append(fnName.replace('`', '\'')).append('` — id `').append(id.replace('`', '\'')).append('`\n')
          sb.append('```json\n').append(args).append('\n```\n')
        }
      } else {
        sb.append('*(expected tool_calls but list was empty or invalid)*\n')
      }
    } else {
      sb.append('\n*(no `tool_calls` on this turn — model finished with text only)*\n')
    }
    if (agentId?.toString()?.trim()) {
      sb.append('\n`agentId`: `').append(agentId.toString().trim().replace('`', '\'')).append('`\n')
    }
    openAiEmitSseToolProgressLine(o, sb.toString(), 'debug')
  }

  /**
   * Parses {@code model} from wire JSON and strips {@code temperature} when {@link #openAiModelNeedsNeoChatCompletionWireParams}
   * applies. Called for <strong>every</strong> synchronous {@code /v1/chat/completions} POST so no caller can send {@code 0.1}
   * (or any explicit value) to GPT‑5 / o‑series — fixes divergent agent {@code llmModel} strings and Spring merge quirks.
   */
  private static String openAiChatCompletionsWireBodyApplyNeoTemperaturePolicy(String jsonBody) {
    if (!jsonBody?.toString()?.trim()) {
      return jsonBody?.toString() ?: ''
    }
    String raw = jsonBody.toString()
    try {
      def parsed = new JsonSlurper().parseText(raw)
      if (!(parsed instanceof Map)) {
        return raw
      }
      String m = (parsed.get('model') ?: '').toString()
      return openAiChatCompletionJsonStripTemperatureForNeoModel(m, raw)
    } catch (Throwable t) {
      log.warn('openAiChatCompletionsWireBodyApplyNeoTemperaturePolicy: parse failed, POSTing unchanged: {}', t.message)
      String low = raw.toLowerCase(Locale.ROOT)
      if (low.contains('gpt-5') || low.contains('gpt_5') || low.contains('"o1') || low.contains('"o3') || low.contains('"o4')) {
        return openAiChatCompletionJsonStripTemperatureRegex(raw)
      }
      return raw
    }
  }

  /**
   * Groq and similar hosts return 429 with {@code try again in Ns} in JSON; honors {@code Retry-After} when numeric.
   */
  private static long openAiToolsLoop429BackoffMs(RestClientResponseException e, int zeroBasedAttempt) {
    try {
      String ra = e.getResponseHeaders()?.getFirst(HttpHeaders.RETRY_AFTER)
      if (ra?.trim()) {
        String firstToken = ra.trim().split(/\s+/)[0]
        long sec = Long.parseLong(firstToken)
        if (sec > 0 && sec < 900) {
          return Math.min(180_000L, Math.max(400L, sec * 1000L))
        }
      }
    } catch (Throwable ignored) {
    }
    try {
      String body = e.getResponseBodyAsString(StandardCharsets.UTF_8)
      if (body) {
        Matcher m = Pattern.compile('(?i)try again in\\s+([0-9.]+)\\s*s').matcher(body)
        if (m.find()) {
          double sec = Double.parseDouble(m.group(1))
          if (sec > 0 && sec < 900) {
            return (long) Math.min(180_000L, Math.max(400L, Math.round(sec * 1000.0)))
          }
        }
      }
    } catch (Throwable ignored) {
    }
    long exp = 900L * (1L << Math.min(3, zeroBasedAttempt))
    return Math.min(45_000L, exp)
  }

  /**
   * POST {@code /v1/chat/completions} with {@code stream:false} and return the raw JSON body (UTF-8).
   * Bypasses {@link org.springframework.ai.openai.api.OpenAiApi#chatCompletionEntity} / Jackson binding.
   * On HTTP 429, sleeps with backoff and retries up to two additional attempts (helps Groq on_demand TPM bursts).
   */
  private static String openAiHttpPostChatCompletionsReadBody(
    String apiKey,
    String jsonBody,
    boolean logFailuresAsWarn = false,
    String wireBaseUrl = null
  ) {
    jsonBody = openAiChatCompletionsWireBodyApplyNeoTemperaturePolicy(jsonBody)
    final int maxTries = 3
    for (int attempt = 1; attempt <= maxTries; attempt++) {
      try {
        return openAiHttpPostChatCompletionsReadBodyOnce(apiKey, jsonBody, logFailuresAsWarn, wireBaseUrl)
      } catch (RestClientResponseException e) {
        if (e.getStatusCode()?.value() != 429 || attempt >= maxTries) {
          throw e
        }
        long ms = openAiToolsLoop429BackoffMs(e, attempt - 1)
        log.warn(
          'Tools-loop chat HTTP 429 Too Many Requests; backing off {} ms then retry {}/{}',
          ms,
          attempt + 1,
          maxTries
        )
        try {
          Thread.sleep(ms)
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt()
          throw ie
        }
      }
    }
    throw new IllegalStateException('Tools-loop chat: 429 retries exhausted')
  }

  private static String openAiHttpPostChatCompletionsReadBodyOnce(
    String apiKey,
    String jsonBody,
    boolean logFailuresAsWarn,
    String wireBaseUrl
  ) {
    crafterQToolWorkerDiagPhase("native_tools_RestClient_POST_/v1/chat/completions stream=false jsonChars=${(jsonBody ?: '').toString().length()}")
    openAiRestClientBuilder(apiKey, wireBaseUrl)
      .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
      .build()
      .post()
      .uri('/v1/chat/completions')
      .contentType(MediaType.APPLICATION_JSON)
      .body(jsonBody)
      .exchange { httpReq, resp ->
        def status = resp.getStatusCode()
        def statusText = resp.getStatusText()
        def headers = resp.getHeaders()
        byte[] bytes
        try {
          def is = resp.getBody()
          bytes = is != null ? is.readAllBytes() : new byte[0]
        } finally {
          try {
            resp.close()
          } catch (Throwable ignored) {}
        }
        def bodyStr = new String(bytes, StandardCharsets.UTF_8)
        if (!status.is2xxSuccessful()) {
          String hint401 = ''
          if (status.value() == 401) {
            hint401 =
              ' Troubleshooting (401): the Bearer key for tools-loop chat must be the API key for the configured wire base URL (script bundle or Studio provider config). A secret issued for a different vendor than the host typically returns 401.'
          }
          String hint413 = ''
          if (status.value() == 413) {
            hint413 =
              ' Troubleshooting (413): request too large for the chat host (token / TPM limits). Reduce tools or prompt size, set toolsLoopChatMaxWirePayloadChars on the script session bundle, or raise your provider tier.'
          }
          String hint429 = ''
          if (status.value() == 429) {
            hint429 =
              ' Troubleshooting (429): rate limit / TPM — the plugin retries a few times with backoff; if this persists, reduce prompt and tool output, disable unused tools on the agent, or upgrade the chat host tier.'
          }
          def msg =
            "Tools-loop chat HTTP ${status.value()} ${statusText} responseBody=\n${AiHttpProxy.elideForLog(bodyStr, 4000)}${hint401}${hint413}${hint429}"
          if (logFailuresAsWarn) {
            log.warn(msg)
          } else {
            log.error(msg)
          }
          def rce = new RestClientResponseException(
            'Tools-loop chat',
            status.value(),
            statusText,
            headers,
            bytes,
            StandardCharsets.UTF_8
          )
          Throwable toThrow = openAiPreferIllegalStateForInvalidModel(rce, jsonBody?.toString())
          if (toThrow instanceof IllegalStateException) {
            throw (IllegalStateException) toThrow
          }
          throw (RestClientResponseException) toThrow
        }
        crafterQToolWorkerDiagPhase('native_tools_RestClient_POST_/v1/chat/completions response_ok')
        bodyStr
      }
  }

  /**
   * Single non-streaming chat completion (no tools) for server-side helpers (e.g. subgraph transform / translate item).
   * Uses {@link HttpURLConnection} with an extended read timeout so large translate/rephrase jobs can finish.
   * @param workerPhasePrefix optional tag (e.g. {@code TranslateContentItem}) prefixed onto {@code crafterQToolWorkerDiagPhase}
   *        strings so SSE heartbeats distinguish per-item inner calls from true bundled transforms.
   */
  static String openAiSimpleCompletionAssistantText(
    String apiKey,
    String model,
    String systemText,
    String userText,
    int maxOutTokens,
    int readTimeoutMs = 600_000,
    String workerPhasePrefix = null,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null
  ) {
    String phasePfx = (workerPhasePrefix != null && workerPhasePrefix.toString().trim())
      ? workerPhasePrefix.toString().trim() + '_'
      : ''
    boolean countTranslateItemInflight = 'TranslateContentItem'.equals((workerPhasePrefix ?: '').toString().trim())
    if (countTranslateItemInflight) {
      CRAFTERQ_TRANSLATE_ITEM_INNER_INFLIGHT.incrementAndGet()
    }
    try {
    if (crafterQPipelineCancelEffective()) {
      crafterQToolWorkerDiagPhase(phasePfx + 'simple_completion_skipped_pipeline_cancelled')
      throw new InterruptedException(CRAFTQ_PIPELINE_CANCELLED)
    }
    int effMaxOut = openAiClampMaxOutTokensForToolsLoopWire(model, maxOutTokens, toolsLoopSessionBundle)
    if (effMaxOut < maxOutTokens) {
      log.info(
        'openAiSimpleCompletionAssistantText: clamping maxOutTokens {} -> {} for model {} wireBaseUrl={}',
        maxOutTokens,
        effMaxOut,
        model,
        wireBaseUrl ?: '(default)'
      )
    }
    def reqMap = [
      model   : model,
      messages: [
        [role: 'system', content: (systemText ?: '').toString()],
        [role: 'user', content: (userText ?: '').toString()]
      ],
      stream  : false
    ]
    reqMap.putAll(openAiChatCompletionOutputLimitParams(model, effMaxOut, toolsLoopSessionBundle))
    String jsonBody = openAiChatCompletionsWireBodyApplyNeoTemperaturePolicy(JsonOutput.toJson(reqMap))
    String urlStr = resolveOpenAiSyncChatCompletionsUrl(wireBaseUrl)
    crafterQToolWorkerDiagPhase(
      phasePfx +
        "simple_completion_HttpURLConnection_POST_/v1/chat/completions model=${model} wireJsonChars=${jsonBody.length()} userMsgChars=${(userText ?: '').toString().length()} readTimeoutMs=${readTimeoutMs}"
    )
    log.debug(
      'Tools-loop wire → POST /v1/chat/completions phase=simple_completion worker={} model={} systemChars={} userChars={} maxOutTokens={} readTimeoutMs={} wireJsonChars={} urlTail={}',
      (workerPhasePrefix ?: '(none)'),
      model,
      (systemText ?: '').length(),
      (userText ?: '').length(),
      effMaxOut,
      readTimeoutMs,
      jsonBody.length(),
      urlStr.contains('?') ? urlStr.substring(0, urlStr.indexOf('?')) : urlStr
    )
    HttpURLConnection conn = null
    try {
      conn = (HttpURLConnection) new URL(urlStr).openConnection()
      conn.setRequestMethod('POST')
      conn.setConnectTimeout(30_000)
      conn.setReadTimeout(Math.max(60_000, readTimeoutMs))
      conn.setRequestProperty(HttpHeaders.AUTHORIZATION, 'Bearer ' + apiKey)
      conn.setRequestProperty(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
      conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      conn.setDoOutput(true)
      byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8)
      conn.setFixedLengthStreamingMode(bodyBytes.length)
      conn.outputStream.write(bodyBytes)
      conn.outputStream.flush()
      if (crafterQPipelineCancelEffective()) {
        crafterQToolWorkerDiagPhase(phasePfx + 'simple_completion_skipped_after_request_body_pipeline_cancelled')
        throw new InterruptedException(CRAFTQ_PIPELINE_CANCELLED)
      }
      crafterQToolWorkerDiagPhase(
        phasePfx +
          "simple_completion_awaiting_chat_upstream_response_body model=${model} httpURLConnection readTimeoutMs=${Math.max(60_000, readTimeoutMs)}"
      )
      int code = conn.responseCode
      InputStream rawStream = code >= 200 && code < 300 ? conn.inputStream : conn.errorStream
      byte[] bytes
      try {
        bytes = rawStream != null ? rawStream.readAllBytes() : new byte[0]
      } finally {
        try {
          rawStream?.close()
        } catch (Throwable ignored) {}
      }
      String raw = new String(bytes, StandardCharsets.UTF_8)
      if (code < 200 || code >= 300) {
        log.error('Tools-loop simple completion HTTP {} body=\n{}', code, AiHttpProxy.elideForLog(raw, 4000))
        if (code == 400 && openAiResponseBodyLooksLikeInvalidModelId(raw)) {
          throw openAiNewIllegalStateForInvalidOpenAiModel(jsonBody, raw)
        }
        throw new IllegalStateException("Tools-loop chat HTTP ${code}: ${AiHttpProxy.elideForLog(raw, 800)}")
      }
      if (!raw?.trim()) {
        throw new IllegalStateException('Tools-loop simple completion: empty response body')
      }
      def slurper = new JsonSlurper()
      Object parsed = slurper.parseText(raw)
      if (!(parsed instanceof Map)) {
        throw new IllegalStateException('Tools-loop simple completion: expected JSON object')
      }
      Map root = (Map) parsed
      def errMsg = openAiStreamChunkOpenAiErrorMessage(root)
      if (errMsg) {
        throw new IllegalStateException('Tools-loop simple completion: ' + errMsg)
      }
      def choices = root.get('choices')
      if (!(choices instanceof List) || choices.isEmpty()) {
        throw new IllegalStateException('Tools-loop simple completion: missing choices')
      }
      def c0 = choices[0] as Map
      def message = c0.get('message')
      if (!(message instanceof Map)) {
        throw new IllegalStateException('Tools-loop simple completion: missing message')
      }
      crafterQToolWorkerDiagPhase(phasePfx + 'simple_completion_chat_upstream_response_parsed_ok')
      return openAiAssistantTextFromChoiceMessageMap((Map) message)
    } finally {
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {}
    }
    } finally {
      if (countTranslateItemInflight) {
        CRAFTERQ_TRANSLATE_ITEM_INNER_INFLIGHT.decrementAndGet()
      }
    }
  }

  /**
   * Headless OpenAI call (no servlet {@code AiOrchestration}): same native Studio {@code tools[]} + execution loop as
   * interactive chat. {@code tools} must be non-empty — callers must not substitute a tools-off completion.
   * <p>{@code userText} is prefixed with the OpenAI user-message tools policy prefix.</p>
   */
  static String openAiHeadlessNativeToolsCompletion(
    String apiKey,
    String model,
    String systemText,
    String userText,
    List tools,
    String agentIdForLogs,
    int maxOutTokens = 8192,
    int readTimeoutMs = 600_000,
    String workerPhasePrefix = 'HeadlessOpenAi',
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null
  ) {
    if (tools == null || tools.isEmpty()) {
      throw new IllegalStateException(
        'openAiHeadlessNativeToolsCompletion: tools list is null or empty; refusing a tools-off completion.'
      )
    }
    String userForTools = ToolPrompts.getOPENAI_USER_MESSAGE_TOOLS_POLICY_PREFIX() + (userText ?: '')
    Prompt prompt = new Prompt([
      new SystemMessage((systemText ?: '').toString()),
      new UserMessage(userForTools)
    ])
    return openAiExecuteNativeToolsViaRestClientReturnText(
      apiKey,
      model,
      prompt,
      tools,
      (agentIdForLogs ?: '').toString(),
      null,
      null,
      null,
      wireBaseUrl,
      toolsLoopSessionBundle
    )
  }

  private static List<Map> openAiDeepCloneWireMessages(List<Map> src) {
    if (src == null) {
      return []
    }
    def out = []
    for (def m : src) {
      if (m instanceof Map) {
        out << new LinkedHashMap((Map) m)
      }
    }
    out
  }

  private static Map openAiLastUserWireMessage(List<Map> wire) {
    Map last = null
    for (def m : wire) {
      if (m instanceof Map && 'user'.equals(((Map) m).get('role')?.toString())) {
        last = (Map) m
      }
    }
    last
  }

  /**
   * Second OpenAI pass after tools (QA JSON + optional correction loop). **Permanently disabled** — no JVM/env
   * toggle; keeps latency predictable and avoids extra {@code /v1/chat/completions} cost after tool work.
   */
  private static boolean openAiPostToolReviewEnabled() {
    return false
  }

  private static String openAiElideMiddleForReview(String s, int maxChars) {
    def t = (s ?: '').toString()
    if (t.length() <= maxChars) {
      return t
    }
    int head = Math.max(1200, (int) (maxChars * 0.45))
    int tail = Math.max(1200, maxChars - head - 80)
    if (head + tail >= t.length()) {
      return t
    }
    return t.substring(0, head) + '\n\n…[middle elided for review length]…\n\n' + t.substring(t.length() - tail)
  }

  private static Map openAiParseReviewJsonObject(String assistantText) {
    def raw = (assistantText ?: '').toString().trim()
    if (!raw) {
      return [accomplished: true, reason: 'empty reviewer reply', correctionInstructions: '']
    }
    if (raw.startsWith('```')) {
      raw = raw.replaceFirst(/(?s)^```(?:json)?\s*\n/, '').replaceFirst(/(?s)\n```\s*$/, '').trim()
    }
    try {
      def o = new JsonSlurper().parseText(raw)
      if (o instanceof Map) {
        def m = (Map) o
        def acc = m.get('accomplished')
        boolean ok = true
        if (acc != null) {
          if (acc instanceof Boolean) {
            ok = (Boolean) acc
          } else {
            ok = 'true'.equalsIgnoreCase(acc.toString())
          }
        }
        return [
          accomplished           : ok,
          reason                 : (m.get('reason') ?: '').toString(),
          correctionInstructions : (m.get('correctionInstructions') ?: m.get('correction_instructions') ?: '').toString()
        ]
      }
    } catch (Throwable t) {
      log.warn('openAiParseReviewJsonObject: {} bodyPrefix=\n{}', t.message, AiHttpProxy.elideForLog(raw, 800))
    }
    [accomplished: true, reason: 'review JSON parse failed', correctionInstructions: '']
  }

  private static Map openAiPostToolReview(
    String apiKey,
    String model,
    String originalUserContent,
    String assistantFinalOutput,
    String agentId,
    OutputStream sseOut = null
  ) {
    model = resolveOpenAiModel(model?.toString())
    int cap = 120_000
    try {
      def p = System.getProperty('crafterq.openai.reviewMaxChars')?.toString()?.trim()
      if (p) {
        cap = Math.max(8192, Integer.parseInt(p))
      }
    } catch (Throwable ignored) {}
    // Groovy `/` on Integer can yield BigDecimal — elide helper requires int maxChars.
    int halfCap = Math.floorDiv((int) cap, 2)
    String ou = openAiElideMiddleForReview(originalUserContent, halfCap)
    String af = openAiElideMiddleForReview(assistantFinalOutput, halfCap)
    def userBlock = """ORIGINAL_AUTHOR_REQUEST:
${ou}

ASSISTANT_FINAL_OUTPUT:
${af}"""
    def reqMap = [
      model   : model,
      messages: [
        [role: 'system', content: ToolPrompts.getOPENAI_POST_EXECUTION_REVIEW_SYSTEM()],
        [role: 'user', content: userBlock]
      ],
      stream  : false
    ]
    reqMap.putAll(openAiChatCompletionOutputLimitParams(model, 900))
    // Never send temperature on review: GPT‑5/o‑series reject non-default values; default sampling is fine for JSON review.
    def jsonBody =
      openAiChatCompletionsWireBodyApplyNeoTemperaturePolicy(JsonOutput.toJson(reqMap))
    log.debug(
      'Tools-loop wire → POST /v1/chat/completions phase=post_tool_review agentId={} model={} wireJsonChars={} neoWire={}',
      agentId,
      model,
      jsonBody.length(),
      openAiModelNeedsNeoChatCompletionWireParams(model)
    )
    openAiEmitSseToolProgressLine(
      sseOut,
      '🛠️🔄 Double-checking the assistant reply…\n',
      'start'
    )
    try {
      String raw = openAiHttpPostChatCompletionsReadBody(apiKey, jsonBody, true)
      if (!raw?.trim()) {
        throw new IllegalStateException('Tools-loop post-tool review: empty response body')
      }
      if (raw.trim().startsWith('data:')) {
        throw new IllegalStateException('Tools-loop post-tool review: SSE for stream=false')
      }
      def slurper = new JsonSlurper()
      Object parsed = slurper.parseText(raw)
      if (!(parsed instanceof Map)) {
        throw new IllegalStateException('Tools-loop post-tool review: expected JSON object')
      }
      Map root = parsed as Map
      def errMsg = openAiStreamChunkOpenAiErrorMessage(root)
      if (errMsg) {
        throw new IllegalStateException('Tools-loop post-tool review: ' + errMsg)
      }
      def choices = root.get('choices')
      if (!(choices instanceof List) || choices.isEmpty()) {
        throw new IllegalStateException('Tools-loop post-tool review: missing choices')
      }
      def c0 = choices[0] as Map
      def message = c0.get('message')
      if (!(message instanceof Map)) {
        throw new IllegalStateException('Tools-loop post-tool review: missing message')
      }
      String reviewText = openAiAssistantTextFromChoiceMessageMap((Map) message)
      return openAiParseReviewJsonObject(reviewText)
    } catch (RestClientResponseException rce) {
      String bp = ''
      try {
        bp = rce.responseBodyAsString ?: ''
      } catch (Throwable ignored) {
      }
      log.warn(
        'Tools-loop post-tool review: HTTP {} — skipping reviewer pass (model may reject temperature or other params). bodyPrefix=\n{}',
        rce.statusCode?.value() ?: rce.statusCode,
        AiHttpProxy.elideForLog(bp, 900)
      )
      return [
        accomplished           : true,
        reason                 : 'post-tool review skipped (chat.completions HTTP error)',
        correctionInstructions : ''
      ]
    } catch (Throwable t) {
      // Optional reviewer must never fail the main chat (classloader-specific HTTP wrappers, parse errors, etc.).
      log.warn('Tools-loop post-tool review failed — skipping reviewer pass', t)
      return [
        accomplished           : true,
        reason                 : 'post-tool review skipped (error)',
        correctionInstructions : ''
      ]
    }
  }

  private static String openAiBuildPostReviewCorrectionUserMessage(Map rev) {
    def r = (rev?.reason ?: '').toString().trim()
    def c = (rev?.correctionInstructions ?: '').toString().trim()
    return """[Studio — post-execution self-check]
An automated reviewer compared your last reply to the original author request and believes the task may be incomplete.

**Reviewer reason:** ${r ?: '(none)'}

**What you still need to do:**
${c ?: 'Re-read the original request, use tools as needed, and produce a complete answer for the author.'}

Use CMS tools if repository work is still missing. **Do not** stream a new **## Plan** for this follow-up — continue against the **## Plan** and **📋** checklist you already gave the author (run any missing verification tools, then mark those steps). Then write the updated final answer under **## Plan Execution** with the **same** **📋** checklist and final **✅ / ❌ / ⚠️** markers as required by policy."""
  }

  /** Collapses whitespace and normalizes quotes so substring checks survive minor typography / unicode differences. */
  private static String openAiPlanGateNormalizeForScan(String raw) {
    if (raw == null) {
      return ''
    }
    String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
    s = s.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201c', '"').replace('\u201d', '"')
    s = s.replace('\u00a0', ' ')
    return s.replaceAll(/\s+/, ' ').trim().toLowerCase(Locale.ROOT)
  }

  /**
   * Detects memorized lazy “execute the request / CMS tools …” slop (older assistant builds quoted it in {@code [TOOL-GUARD]}).
   * Used only to strip matching lines from streamed assistant text — the native tool loop does **not** block on plan shape.
   */
  private static boolean openAiContainsKnownForbiddenMetaPlan(String t) {
    String n = openAiPlanGateNormalizeForScan(t)
    if (!n) {
      return false
    }
    if (n.contains('execute the user request using the cms tools described in the studio authoring system message')) {
      return true
    }
    if (n.contains('execute the user request using the cms tools described')) {
      return true
    }
    // Singular “tool” variants models sometimes emit.
    if (n.contains('execute the user request using the cms tool described')) {
      return true
    }
    if (n.contains('using the cms tool described in the studio authoring system message')) {
      return true
    }
    if (n.contains('using the cms tools described in the studio authoring system message')) {
      return true
    }
    if (n.contains('execute the user request') && n.contains('cms tools') && n.contains('system message')) {
      return true
    }
    if (n.contains('execute the user request') && n.contains('cms tool') && n.contains('system message')) {
      return true
    }
    if (n.contains('execute the user request') && n.contains('studio authoring') && n.contains('message')) {
      return true
    }
    if (n.contains('use tools as described') && n.contains('system')) {
      return true
    }
    return false
  }

  /**
   * Last-resort cleanup before SSE: removes lazy meta plan lines so authors never see memorized {@code [TOOL-GUARD]}
   * parrot text. Drops a lone {@code Plan} / {@code ## Plan} heading when the immediate next non-empty line is forbidden.
   */
  private static String openAiStripForbiddenMetaPlanFromAssistantText(String raw) {
    if (raw == null) {
      return ''
    }
    String t = raw.toString()
    if (!t.trim()) {
      return t
    }
    List<String> lines = t.split(/\r?\n/, -1).toList()
    List<String> out = []
    int i = 0
    while (i < lines.size()) {
      String line = lines.get(i)
      String trimmed = line.trim()
      if (openAiContainsKnownForbiddenMetaPlan(trimmed)) {
        i++
        continue
      }
      String tl = trimmed.toLowerCase(Locale.ROOT)
      boolean planOnly =
        tl == 'plan' ||
          tl == 'plan:' ||
          tl == '**plan**' ||
          (tl =~ /(?i)^#+\s*plan\s*$/) ||
          (tl =~ /(?i)^##\s*plan\s*$/)
      if (planOnly) {
        int j = i + 1
        while (j < lines.size() && !lines.get(j).trim()) {
          j++
        }
        if (j < lines.size() && openAiContainsKnownForbiddenMetaPlan(lines.get(j).trim())) {
          i = j + 1
          continue
        }
      }
      out.add(line)
      i++
    }
    String joined = out.join('\n')
    return joined.replaceAll(/(?m)\n{3,}/, '\n\n').trim()
  }

  /** CMS tools that must not run when the author only asked about CrafterQ hosted chat logs (api.crafterq.ai). */
  private static final Set<String> CRAFTERRQ_HOSTED_CHAT_BLOCKED_TOOL_NAMES =
    [
      'ListContentTranslationScope',
      'TranslateContentBatch',
      'TranslateContentItem'
    ].toSet()

  /** First planned tool name from {@code <!--CRAFTERRQ_ORCH ... -->} when present. */
  private static String openAiPlannedFirstToolNameFromOrch(String assistantRaw) {
    List<Map> steps = PlanOrchestration.parseOrchestrationSteps(assistantRaw ?: '')
    if (steps.isEmpty()) {
      return ''
    }
    Map st0 = steps[0] as Map
    def tls = st0.get('tools')
    if (tls instanceof List && !((List) tls).isEmpty()) {
      return ((List) tls).get(0)?.toString()?.trim() ?: ''
    }
    def one = st0.get('tool')
    return one != null ? one.toString().trim() : ''
  }

  private static String openAiToolCallNameAt(List runList, int index) {
    if (runList == null || index < 0 || index >= runList.size()) {
      return ''
    }
    def tcObj = runList.get(index)
    if (!(tcObj instanceof Map)) {
      return ''
    }
    def fn = ((Map) tcObj).get('function')
    if (!(fn instanceof Map)) {
      return ''
    }
    return fn.get('name')?.toString()?.trim() ?: ''
  }

  /**
   * True when the user message is about **hosted CrafterQ chat** analytics (not CMS page work).
   * Conservative: requires {@code crafterq} plus at least one analytics phrase.
   */
  private static boolean openAiCrafterqHostedChatAnalyticsIntent(String userText) {
    String n = openAiPlanGateNormalizeForScan(userText)
    if (!n || !n.contains('crafterq')) {
      return false
    }
    return n.contains('number one') ||
      n.contains('top question') ||
      n.contains('what people') ||
      n.contains('people ask') ||
      n.contains('people asked') ||
      n.contains('hosted chat') ||
      n.contains('chat log') ||
      n.contains('chat analytics') ||
      n.contains('dislike') ||
      n.contains('most common') ||
      n.contains('most frequent') ||
      n.contains('most asked') ||
      n.contains('frequent question') ||
      (n.contains('question') && (n.contains(' in crafterq') || n.contains('from crafterq')))
  }

  private static Map openAiSyntheticSameIdToolCall(Map original, String newToolName, String newArgumentsJson) {
    Map tc = new LinkedHashMap((Map) original)
    Map fn = new LinkedHashMap()
    def oldFn = original.get('function')
    if (oldFn instanceof Map) {
      fn.putAll((Map) oldFn)
    }
    fn.put('name', newToolName)
    fn.put('arguments', newArgumentsJson != null ? newArgumentsJson : '{}')
    tc.put('function', fn)
    tc
  }

  /**
   * Round 0 only: if the model’s first tool is a common CMS mis-route for CrafterQ hosted-chat questions,
   * replace it with {@code ListCrafterQAgentChats} and drop sibling translate/scope calls in the same assistant turn.
   */
  private static List openAiRepairToolCallsForCrafterqHostedChatIntent(
    int round,
    List runList,
    String assistantRawForOrchestration,
    String agentId
  ) {
    if (runList == null || runList.isEmpty() || round != 0) {
      return runList
    }
    List out = new ArrayList(runList)
    String plannedFirst = openAiPlannedFirstToolNameFromOrch(assistantRawForOrchestration)
    String firstName = openAiToolCallNameAt(out, 0)
    boolean cmsMisrouteFirst =
      'ListContentTranslationScope'.equals(firstName) ||
        ('ListCrafterQAgentChats'.equals(plannedFirst) &&
          firstName &&
          !'ListCrafterQAgentChats'.equals(firstName) &&
          !'GetCrafterQAgentChat'.equals(firstName) &&
          (
            'ListPagesAndComponents'.equals(firstName) ||
              'ListStudioContentTypes'.equals(firstName) ||
              'GetContent'.equals(firstName) ||
              'GetContentTypeFormDefinition'.equals(firstName) ||
              'GetPreviewHtml'.equals(firstName) ||
              'analyze_template'.equals(firstName)
          ))
    if (cmsMisrouteFirst && out.get(0) instanceof Map) {
      out.set(0, openAiSyntheticSameIdToolCall((Map) out.get(0), 'ListCrafterQAgentChats', '{}'))
      log.warn(
        'Tools-loop tools-on: repaired first tool call to ListCrafterQAgentChats for CrafterQ hosted-chat analytics intent agentId={} was={} plannedOrchFirst={}',
        agentId,
        firstName,
        plannedFirst
      )
    }
    for (int i = out.size() - 1; i >= 1; i--) {
      String n = openAiToolCallNameAt(out, i)
      if (CRAFTERRQ_HOSTED_CHAT_BLOCKED_TOOL_NAMES.contains(n)) {
        out.remove(i)
        log.warn(
          'Tools-loop tools-on: removed same-round {} after CrafterQ hosted-chat repair agentId={}',
          n,
          agentId
        )
      }
    }
    return out
  }

  /**
   * When {@code GenerateImage} returns a large {@code data:image/...;base64,...} {@code url}, stores the full URL in
   * {@code generateImageDataUrlByToolCallId} under {@code toolCallId} and returns compact JSON for the OpenAI
   * {@code role:tool} wire (avoids {@code context_length_exceeded}). Returns {@code null} if not applicable.
   */
  private static String openAiCompactGenerateImageToolWireForOpenAiContext(
    String toolOutJson,
    String toolCallId,
    Map<String, String> generateImageDataUrlByToolCallId
  ) {
    if (!toolOutJson?.trim() || !toolCallId?.trim() || generateImageDataUrlByToolCallId == null) {
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
    Map m = (Map) parsed
    if (m.get('result') instanceof Map) {
      m = (Map) m.get('result')
    }
    String url = m.get('url') != null ? m.get('url').toString().trim() : ''
    if (!url.startsWith('data:image')) {
      return null
    }
    String urlLower = url.toLowerCase(Locale.ROOT)
    if (!urlLower.contains(';base64,')) {
      return null
    }
    String tid = toolCallId.trim()
    generateImageDataUrlByToolCallId.put(tid, url)
    Map compact = new LinkedHashMap<>()
    for (String k : ['ok', 'tool', 'model', 'revised_prompt', 'hint']) {
      if (m.containsKey(k) && m.get(k) != null) {
        compact.put(k, m.get(k))
      }
    }
    compact.put('crafterqInlineImageRef', tid)
    compact.put(
      'authorMarkdownInstruction',
      'In your next assistant message, include exactly ONE markdown image line using this URL in the parentheses (verbatim): ' +
        CRAFTERRQ_TOOL_IMAGE_REF_PREFIX + tid +
        ' Example: ![Generated illustration](' + CRAFTERRQ_TOOL_IMAGE_REF_PREFIX + tid +
        ') Do not use a data: URL.'
    )
    log.info(
      'Tools-loop native tools: GenerateImage compact tool wire toolCallId={} elidedDataUrlChars={}',
      tid,
      url.length()
    )
    return JsonOutput.toJson(compact)
  }

  /** Replaces {@code crafterq-tool-image://<toolCallId>} with stored {@code data:} URLs for author-visible output. */
  private static String openAiExpandCrafterqToolImageRefs(String text, Map<String, String> generateImageDataUrlByToolCallId) {
    if (text == null) {
      return ''
    }
    String out = text.toString()
    if (generateImageDataUrlByToolCallId == null || generateImageDataUrlByToolCallId.isEmpty()) {
      if (out.contains(CRAFTERRQ_TOOL_IMAGE_REF_PREFIX)) {
        log.warn(
          'Tools-loop native tools: assistant text still contains {} but inline image map is empty (GenerateImage wire may not have been compacted).',
          CRAFTERRQ_TOOL_IMAGE_REF_PREFIX
        )
      }
      return out
    }
    String soleUrl =
      generateImageDataUrlByToolCallId.size() == 1
        ? generateImageDataUrlByToolCallId.values().iterator().next()
        : null
    Pattern pat = Pattern.compile(Pattern.quote(CRAFTERRQ_TOOL_IMAGE_REF_PREFIX) + '([A-Za-z0-9_-]+)')
    Matcher mat = pat.matcher(out)
    StringBuffer sb = new StringBuffer()
    while (mat.find()) {
      String id = mat.group(1)
      String url = generateImageDataUrlByToolCallId.get(id)
      if (url == null && soleUrl != null) {
        url = soleUrl
      }
      if (url != null && url.length() > 0) {
        mat.appendReplacement(sb, Matcher.quoteReplacement(url))
      } else {
        mat.appendReplacement(sb, Matcher.quoteReplacement(mat.group(0)))
      }
    }
    mat.appendTail(sb)
    return sb.toString()
  }

  /**
   * If the model never echoed {@link #CRAFTERRQ_TOOL_IMAGE_REF_PREFIX} (or the inline {@code data:} bytes) in the final
   * assistant message, the author would see no image. Appends one markdown image line per stored {@code tool_call_id}
   * that is still missing, then {@link #openAiExpandCrafterqToolImageRefs} replaces refs with real {@code data:} URLs.
   */
  private static String openAiEnsureGenerateImageMarkdownLinesPresent(
    String assistantText,
    Map<String, String> generateImageDataUrlByToolCallId
  ) {
    if (generateImageDataUrlByToolCallId == null || generateImageDataUrlByToolCallId.isEmpty()) {
      return assistantText != null ? assistantText.toString() : ''
    }
    String text = (assistantText != null ? assistantText.toString() : '')
    int appended = 0
    StringBuilder tail = new StringBuilder()
    for (Map.Entry<String, String> e : generateImageDataUrlByToolCallId.entrySet()) {
      String id = e.getKey() != null ? e.getKey().toString().trim() : ''
      String url = e.getValue() != null ? e.getValue().toString() : ''
      if (!id || !url) {
        continue
      }
      String ref = CRAFTERRQ_TOOL_IMAGE_REF_PREFIX + id
      if (text.contains(ref) || text.contains(url)) {
        continue
      }
      tail.append('\n\n![](').append(ref).append(')')
      appended++
    }
    if (appended > 0) {
      log.info(
        'Tools-loop native tools: appended {} fallback markdown image line(s) for GenerateImage (assistant omitted crafterq-tool-image refs).',
        appended
      )
      return text + tail.toString()
    }
    return text
  }

  /**
   * Before appending an assistant {@code message} to {@code wireMessages}, replace any known huge {@code data:image}
   * URLs (same bytes as a prior {@code GenerateImage} tool result) with {@link #CRAFTERRQ_TOOL_IMAGE_REF_PREFIX} refs
   * so follow-up {@code POST /v1/chat/completions} requests stay within context limits.
   */
  private static void openAiMutateAssistantWireContentElideKnownGenerateImageDataUrls(
    Map msgCopy,
    Map<String, String> generateImageDataUrlByToolCallId
  ) {
    if (!(msgCopy instanceof Map) || generateImageDataUrlByToolCallId == null || generateImageDataUrlByToolCallId.isEmpty()) {
      return
    }
    def c = msgCopy.get('content')
    if (!(c instanceof CharSequence)) {
      return
    }
    String flat = c.toString()
    if (!flat) {
      return
    }
    String s = flat
    boolean changed = false
    for (Map.Entry<String, String> e : generateImageDataUrlByToolCallId.entrySet()) {
      String id = e.key
      String url = e.value
      if (!id || !url || !s.contains(url)) {
        continue
      }
      s = s.replace(url, CRAFTERRQ_TOOL_IMAGE_REF_PREFIX + id)
      changed = true
    }
    if (changed) {
      msgCopy.put('content', s)
    }
  }

  private static String openAiTruncateNativeToolWireContent(
    String fnName,
    Object toolOutRaw,
    String toolCallId = null,
    Map<String, String> generateImageDataUrlByToolCallId = null
  ) {
    String s = toolOutRaw != null ? toolOutRaw.toString() : ''
    if ('GenerateImage'.equals((fnName ?: '').toString().trim())) {
      if (generateImageDataUrlByToolCallId != null && toolCallId?.toString()?.trim()) {
        String compact = openAiCompactGenerateImageToolWireForOpenAiContext(s, toolCallId.trim(), generateImageDataUrlByToolCallId)
        if (compact != null) {
          return compact
        }
      }
      if (s.length() <= OPENAI_NATIVE_TOOL_WIRE_MAX_CHARS) {
        return s
      }
      int cap = OPENAI_NATIVE_TOOL_WIRE_MAX_CHARS
      String head = s.substring(0, cap)
      return head +
        '\n\n[crafterq: output truncated for chat context limit; tool=GenerateImage originalChars=' + s.length() + ']' +
        '\nHint: payload too large for wire; use a smaller image or save to /static-assets/.]'
    }
    if (s.length() <= OPENAI_NATIVE_TOOL_WIRE_MAX_CHARS) {
      return s
    }
    int cap = OPENAI_NATIVE_TOOL_WIRE_MAX_CHARS
    String head = s.substring(0, cap)
    String fn = (fnName ?: '').toString()
    return head +
      '\n\n[crafterq: output truncated for chat context limit; tool=' + fn + ' originalChars=' + s.length() + ']' +
      '\nHint: use a smaller size, a path prefix filter, or GetContent on specific paths.]'
  }

  private static String openAiRunNativeToolLoopToAssistantText(
    String apiKey,
    String model,
    List<Map> wireMessages,
    List wireTools,
    Map<String, FunctionToolCallback> byName,
    String agentId,
    int maxRounds,
    boolean logFirstPostChars,
    OutputStream ssePreToolAssistantText = null,
    AtomicBoolean cancelRequested = null,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null,
    Map<String, String> generateImageDataUrlByToolCallId = null
  ) {
    def slurper = new JsonSlurper()
    String assistantAccum = ''
    boolean finished = false
    boolean previousRoundHadRepoMutation = false
    for (int round = 0; round < maxRounds; round++) {
      if (cancelRequested != null && cancelRequested.get()) {
        Thread.currentThread().interrupt()
        throw new InterruptedException(CRAFTQ_PIPELINE_CANCELLED)
      }
      crafterQToolWorkerDiagPhase("native_tool_loop_round_${round}_build_request wireMsgCount=${wireMessages.size()}")
      def reqMap = [
        model: model,
        messages: wireMessages,
        tools: wireTools,
        tool_choice: 'auto',
        stream: false
      ]
      int effMaxOut = openAiClampMaxOutTokensForToolsLoopWire(model, 16000, toolsLoopSessionBundle)
      reqMap.putAll(openAiChatCompletionOutputLimitParams(model, effMaxOut, toolsLoopSessionBundle))
      int maxWire = StudioAiLlmKind.toolsLoopChatMaxWirePayloadCharsFromBundle(toolsLoopSessionBundle)
      openAiShrinkToolsLoopWirePayloadIfOverBudget(reqMap, wireMessages, wireTools, maxWire)
      def jsonBody = openAiChatCompletionsWireBodyApplyNeoTemperaturePolicy(JsonOutput.toJson(reqMap))
      if (logFirstPostChars && round == 0) {
        log.debug(
          'Tools-loop tools-on RestClient: first POST chars={} agentId={} model={} restReadTimeoutMs={}',
          jsonBody.length(),
          agentId,
          model,
          resolveOpenAiRestReadTimeoutMs()
        )
      }
      openAiEmitRoundWaitSse(ssePreToolAssistantText, round, model, agentId, jsonBody.length(), previousRoundHadRepoMutation)
      if (cancelRequested != null && cancelRequested.get()) {
        Thread.currentThread().interrupt()
        throw new InterruptedException(CRAFTQ_PIPELINE_CANCELLED)
      }
      String raw = openAiHttpPostChatCompletionsReadBody(apiKey, jsonBody, false, wireBaseUrl)
      if (cancelRequested != null && cancelRequested.get()) {
        Thread.currentThread().interrupt()
        throw new InterruptedException(CRAFTQ_PIPELINE_CANCELLED)
      }
      if (!raw?.trim()) {
        throw new IllegalStateException('Tools-loop chat: empty response body')
      }
      if (raw.trim().startsWith('data:')) {
        throw new IllegalStateException('Chat host returned SSE for stream=false (native tool loop)')
      }
      Object parsed
      try {
        parsed = slurper.parseText(raw)
      } catch (Throwable je) {
        log.error('Tools-loop tools-on: JSON parse failed bodyPrefix=\n{}', AiHttpProxy.elideForLog(raw, 2500))
        try {
          openAiEmitSseToolProgressLine(
            ssePreToolAssistantText,
            '🛠️❌ **Chat host** — **`chat.completions` body was not valid JSON** (fragment for debugging):\n```text\n' +
              openAiEscapeTripleBackticksForMarkdownFence(AiHttpProxy.elideForLog(raw, 8000)) +
              '\n```\n',
            'error'
          )
        } catch (Throwable ignoredPreview) {
        }
        throw je
      }
      if (!(parsed instanceof Map)) {
        throw new IllegalStateException('Tools-loop chat: expected JSON object')
      }
      Map root = parsed as Map
      def errMsg = openAiStreamChunkOpenAiErrorMessage(root)
      if (errMsg) {
        try {
          openAiEmitSseToolProgressLine(
            ssePreToolAssistantText,
            '🛠️❌ **Chat host** returned an error in **`chat.completions` JSON** (no assistant message to apply):\n```text\n' +
              openAiEscapeTripleBackticksForMarkdownFence(errMsg.toString()) +
              '\n```\n',
            'error'
          )
        } catch (Throwable ignoredErrPreview) {
        }
        throw new IllegalStateException('Chat host: ' + errMsg)
      }
      def choices = root.get('choices')
      if (!(choices instanceof List) || choices.isEmpty()) {
        throw new IllegalStateException('Tools-loop chat: missing choices')
      }
      def c0 = choices[0] as Map
      def message = c0.get('message')
      if (!(message instanceof Map)) {
        throw new IllegalStateException('Tools-loop chat: missing message')
      }
      Map msgCopy = new LinkedHashMap((Map) message)
      String assistantApiFlatForDebug = openAiAssistantTextFromChoiceMessageMap(msgCopy)
      String assistantPreTool = assistantApiFlatForDebug
      boolean hasTc = openAiChoiceMessageHasToolCalls(msgCopy)
      String assistantRawForOrchestration = assistantApiFlatForDebug
      if (hasTc) {
        def tcl0 = msgCopy.get('tool_calls')
        if (tcl0 instanceof List) {
          List tcl = (List) tcl0
          List ordered = PlanOrchestration.reorderToolCallsByPlan(new ArrayList(tcl), assistantRawForOrchestration)
          List runListPrep = ordered != null ? ordered : new ArrayList(tcl)
          String guardPre = openAiLastUserWireMessage(wireMessages)?.get('content')?.toString() ?: ''
          if (openAiCrafterqHostedChatAnalyticsIntent(guardPre) && byName.containsKey('ListCrafterQAgentChats')) {
            runListPrep = openAiRepairToolCallsForCrafterqHostedChatIntent(round, runListPrep, assistantRawForOrchestration, agentId)
          }
          msgCopy.put('tool_calls', runListPrep)
          if (ordered != null) {
            log.info(
              'Tools-loop tools-on: plan orchestrator reordered {} tool_calls to match CRAFTERRQ_ORCH block agentId={}',
              ordered.size(),
              agentId
            )
          }
        }
      }
      openAiMutateAssistantContentStripOrchestratorBlock(msgCopy)
      assistantPreTool = openAiAssistantTextFromChoiceMessageMap(msgCopy)
      if (ssePreToolAssistantText != null) {
        try {
          if (hasTc) {
            String cleanedPreTool = assistantPreTool?.trim() ? openAiStripForbiddenMetaPlanFromAssistantText(assistantPreTool.trim()) : ''
            String trimmedPlan = (cleanedPreTool ?: '').trim()
            if (trimmedPlan) {
              def chunk = trimmedPlan + '\n\n'
              synchronized (ssePreToolAssistantText) {
                ssePreToolAssistantText.write(
                  ("data: ${JsonOutput.toJson([text: chunk, metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8)
                )
                ssePreToolAssistantText.flush()
              }
            } else {
              log.info(
                'Tools-loop tools-on: no assistant text to stream before tool_calls (common for some models); CMS tools still run. agentId={} round={}',
                agentId,
                round
              )
            }
          }
        } catch (Throwable te) {
          if (isSseClientDisconnected(te)) {
            log.debug('Tools-loop tools-on: pre-tool SSE skip (response unusable / client gone): {}', te.message)
          } else {
            log.warn('Tools-loop tools-on: failed to stream assistant text before tool calls: {}', te.message)
          }
        }
        openAiEmitSseAssistantTurnDebugPreview(ssePreToolAssistantText, assistantApiFlatForDebug, msgCopy, hasTc, round, agentId)
      }
      openAiMutateAssistantWireContentElideKnownGenerateImageDataUrls(msgCopy, generateImageDataUrlByToolCallId)
      wireMessages << msgCopy
      if (hasTc) {
        def runList = msgCopy.get('tool_calls') as List
        boolean repoMutationThisRound = false
        String guardLoop = openAiLastUserWireMessage(wireMessages)?.get('content')?.toString() ?: ''
        boolean cqHostChatGuard =
          openAiCrafterqHostedChatAnalyticsIntent(guardLoop) && byName.containsKey('ListCrafterQAgentChats')
        for (def tcObj : runList) {
          if (cancelRequested != null && cancelRequested.get()) {
            Thread.currentThread().interrupt()
            throw new InterruptedException(CRAFTQ_PIPELINE_CANCELLED)
          }
          if (!(tcObj instanceof Map)) {
            continue
          }
          def tc = tcObj as Map
          String id = tc.get('id')?.toString()
          def fn = tc.get('function') as Map
          String fnName = fn instanceof Map ? (fn.get('name')?.toString() ?: '') : ''
          String argsStr = fn instanceof Map ? (fn.get('arguments')?.toString() ?: '{}') : '{}'
          boolean blockedCqMisroute = cqHostChatGuard && CRAFTERRQ_HOSTED_CHAT_BLOCKED_TOOL_NAMES.contains(fnName)
          if (blockedCqMisroute) {
            log.warn(
              'Tools-loop tools-on: blocked {} for CrafterQ hosted-chat analytics user intent (use ListCrafterQAgentChats / GetCrafterQAgentChat) agentId={}',
              fnName,
              agentId
            )
          } else if (fnName == 'WriteContent' ||
            fnName == 'publish_content' ||
            fnName == 'TranslateContentItem' ||
            fnName == 'TranslateContentBatch' ||
            fnName == 'revert_change') {
            repoMutationThisRound = true
          }
          FunctionToolCallback tcb = fnName ? byName.get(fnName) : null
          String toolOut
          crafterQToolWorkerDiagPhase(
            "native_tool_loop_round_${round}_repository_tool name=${fnName ?: '?'} argsChars=${(argsStr ?: '').length()}"
          )
          if (blockedCqMisroute) {
            toolOut = JsonOutput.toJson([
              ok                           : false,
              tool                         : fnName,
              blockedForCrafterqHostedChatIntent: true,
              message                      :
                'Tool not executed: the author asked about CrafterQ hosted chat logs (api.crafterq.ai), not CMS translation or scope walks. Call ListCrafterQAgentChats (arguments may be {}) then GetCrafterQAgentChat with a chatId from the listing.',
              hint                         : 'ListCrafterQAgentChats'
            ])
          } else if (tcb == null) {
            toolOut = JsonOutput.toJson([ok: false, error: 'unknown_tool', tool: fnName])
            log.warn('Tools-loop tools-on: unknown tool {} agentId={}', fnName, agentId)
          } else {
            try {
              toolOut = tcb.call(argsStr)
            } catch (Throwable tex) {
              log.warn('Tools-loop tools-on: tool {} failed: {}', fnName, tex.message)
              toolOut = JsonOutput.toJson([ok: false, error: tex.message?.toString()])
            }
          }
          crafterQToolWorkerDiagPhase(
            "native_tool_loop_round_${round}_repository_tool_done name=${fnName ?: '?'} outChars=${(toolOut ?: '').toString().length()}"
          )
          if (toolOut == null) {
            toolOut = ''
          } else if (toolOut instanceof Map) {
            // Spring AI may return the tool Map directly; JsonSlurper needs JSON, not Map#toString().
            toolOut = JsonOutput.toJson((Map) toolOut)
          } else {
            toolOut = toolOut.toString()
          }
          String toolWire = openAiTruncateNativeToolWireContent(fnName, toolOut, id, generateImageDataUrlByToolCallId)
          if (toolWire.length() < toolOut.length() && !'GenerateImage'.equals(fnName)) {
            log.warn(
              'Tools-loop native tools: truncated tool wire output tool={} agentId={} beforeChars={} afterChars={}',
              fnName,
              agentId,
              toolOut.length(),
              toolWire.length()
            )
          }
          wireMessages << [role: 'tool', tool_call_id: id, content: toolWire]
        }
        previousRoundHadRepoMutation = repoMutationThisRound
        continue
      }
      crafterQToolWorkerDiagPhase("native_tool_loop_round_${round}_final_assistant_message_no_more_tools")
      assistantAccum = openAiAssistantTextFromChoiceMessageMap(msgCopy)
      finished = true
      break
    }
    if (!finished) {
      throw new IllegalStateException("Tools-loop tools-on: exceeded ${maxRounds} tool rounds without a final assistant message")
    }
    return assistantAccum ?: ''
  }

  /**
   * Tools-loop native tools without {@link OpenAiChatModel}: sync {@code stream:false} rounds + {@link JsonSlurper}
   * + {@link FunctionToolCallback#call(String)} until the assistant stops calling tools.
   * <p>One chat session with tools enabled: the model should stream a **## Plan** (see system STUDIO POLICY) in the
   * <strong>first assistant message</strong> whenever it also issues tool calls; that assistant {@code content} is
   * forwarded to {@code sseOut} before tools run so authors see plan → tools → final answer like a composer flow.</p>
   * <p>Post-execution review (extra OpenAI pass + optional correction loop) is <strong>not</strong> run — hardcoded off.</p>
   *
   * @param sseOut when non-null (Studio SSE), assistant text before each tool round is streamed when present
   */
  static String openAiExecuteNativeToolsViaRestClientReturnText(
    String apiKey,
    String model,
    Prompt openAiPrompt,
    List tools,
    String agentId,
    OutputStream sseOut = null,
    Map toolTimingCtx = null,
    AtomicBoolean cancelRequested = null,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null
  ) {
    markPipelineWallStart(toolTimingCtx)
    if (cancelRequested != null && cancelRequested.get()) {
      throw new InterruptedException(CRAFTQ_PIPELINE_CANCELLED)
    }
    crafterQPipelineCancelBindingSet(cancelRequested)
    try {
    if (!apiKey) {
      throw new IllegalStateException('Tools-loop chat API key missing')
    }
    crafterQToolWorkerDiagPhase("native_tools_session_prepare agentId=${agentId ?: ''} model=${model ?: ''}")
    def wireTools = openAiBuildWireToolsFromCallbacks(tools)
    if (!wireTools) {
      throw new IllegalStateException('CMS tools: empty tool list')
    }
    Map<String, FunctionToolCallback> byName = openAiToolCallbacksByName(tools)
    List<Map> baseWire = []
    openAiChatCompletionMessagesForApi(openAiPrompt).each { cm ->
      baseWire << openAiWireMessageFromChatCompletionMessage(cm)
    }
    Map lastUserTemplate = openAiLastUserWireMessage(baseWire)
    if (lastUserTemplate == null || !lastUserTemplate.get('content')?.toString()?.trim()) {
      throw new IllegalStateException('Tools-loop tools-on: prompt has no user message')
    }
    List<Map> wireMessages = openAiDeepCloneWireMessages(baseWire)
    Map wmUser = openAiLastUserWireMessage(wireMessages)
    def origUser = wmUser?.get('content')?.toString() ?: ''
    Map<String, String> cqGenerateImageDataUrlByToolCallId = new LinkedHashMap<>()
    String assistantAccum = openAiRunNativeToolLoopToAssistantText(
      apiKey,
      model,
      wireMessages,
      wireTools,
      byName,
      agentId,
      40,
      true,
      sseOut,
      cancelRequested,
      wireBaseUrl,
      toolsLoopSessionBundle,
      cqGenerateImageDataUrlByToolCallId
    )
    if (openAiPostToolReviewEnabled() && (cancelRequested == null || !cancelRequested.get())) {
      try {
        openAiEmitSseToolProgressLine(
          sseOut,
          '🛠️🔄 **Post-tool review** … comparing your request to the assistant reply (tools-loop path only; no repository writes).\n',
          'start'
        )
        Map rev = openAiPostToolReview(apiKey, model, origUser, assistantAccum, agentId, sseOut)
        openAiEmitSseToolProgressLine(
          sseOut,
          '🛠️🔄 ✅ **Post-tool review** finished.\n',
          'done'
        )
        boolean acc = rev?.accomplished != null && Boolean.TRUE.equals(rev.accomplished)
        if (!acc) {
          String corr = (rev?.correctionInstructions ?: '').toString().trim()
          if (corr) {
            openAiEmitSseToolProgressLine(
              sseOut,
              '🛠️🔄 **Correction pass** … running follow-up tools from the review (same chat session).\n',
              'start'
            )
            wireMessages << [role: 'user', content: openAiBuildPostReviewCorrectionUserMessage(rev)]
            assistantAccum = openAiRunNativeToolLoopToAssistantText(
              apiKey,
              model,
              wireMessages,
              wireTools,
              byName,
              agentId,
              15,
              false,
              sseOut,
              cancelRequested,
              wireBaseUrl,
              toolsLoopSessionBundle,
              cqGenerateImageDataUrlByToolCallId
            )
          }
        }
      } catch (Throwable tre) {
        log.warn('Tools-loop post-tool review/correction skipped', tre)
        def em = (tre?.message ?: tre?.toString() ?: 'error').toString()
        if (em.length() > 200) {
          em = em.substring(0, 197) + '…'
        }
        openAiEmitSseToolProgressLine(
          sseOut,
          '🛠️🔄 ⚠️ **Post-tool review** skipped: ' + em + '\n',
          'warn'
        )
      }
    }
    if (sseOut != null) {
      try {
        def hint = [text: '', metadata: [status: 'crafterq-chat-phase', phase: 'summarizing-results']]
        synchronized (sseOut) {
          sseOut.write(("data: ${JsonOutput.toJson(hint)}\n\n").getBytes(StandardCharsets.UTF_8))
          sseOut.flush()
        }
      } catch (Throwable ignored) {
        // never break return path
      }
    }
    String stitched = openAiEnsureGenerateImageMarkdownLinesPresent((assistantAccum ?: '').toString(), cqGenerateImageDataUrlByToolCallId)
    return openAiExpandCrafterqToolImageRefs(stitched, cqGenerateImageDataUrlByToolCallId)
    } finally {
      crafterQPipelineCancelBindingClear()
    }
  }

  private void writeOpenAiToolsOnViaRestClientToolLoop(
    OutputStream out,
    String apiKey,
    String model,
    Prompt openAiPrompt,
    List tools,
    String agentId,
    Map toolTimingCtx = null,
    AtomicBoolean cancelRequested = null,
    AtomicBoolean terminalEmitted = null,
    String wireBaseUrl = null,
    Map toolsLoopSessionBundle = null
  ) {
    crafterQToolWorkerDiagPhase("openai_tools_worker_start agentId=${agentId ?: ''} model=${model ?: ''}")
    String text
    try {
      text = openAiExecuteNativeToolsViaRestClientReturnText(
        apiKey, model, openAiPrompt, tools, agentId, out, toolTimingCtx, cancelRequested, wireBaseUrl, toolsLoopSessionBundle)
    } catch (InterruptedException ie) {
      log.warn(
        'AI Assistant chat stream: Tools-loop tools worker stopped after cancel (client abort / Stop). agentId={} reason={}',
        agentId,
        ie.message
      )
      if (tryClaimOpenAiToolsTerminalEmit(terminalEmitted)) {
        writeSseErrorFrame(out, new InterruptedException('Request was cancelled or stopped before the assistant finished.'))
      }
      return
    }
    try {
      synchronized (out) {
        // Always emit the final assistant text chunk: only the terminal `metadata.completed` frame is
        // CAS-guarded (servlet timeout/recovery may have claimed first). Skipping the text here would drop a
        // valid reply on an otherwise live connection — duplicate completed is what we must avoid.
        String finalChunk = openAiStripForbiddenMetaPlanFromAssistantText((text ?: '').toString())
        out.write(("data: ${JsonOutput.toJson([text: finalChunk, metadata: [:]])}\n\n").getBytes(StandardCharsets.UTF_8))
        if (tryClaimOpenAiToolsTerminalEmit(terminalEmitted)) {
          def doneMeta = new LinkedHashMap()
          doneMeta.completed = true
          mergeToolPipelineWallMsIntoMetadata(doneMeta, toolTimingCtx)
          out.write(("data: ${JsonOutput.toJson([text: '', metadata: doneMeta])}\n\n").getBytes(StandardCharsets.UTF_8))
        }
        out.flush()
      }
    } catch (Throwable io) {
      if (isSseClientDisconnected(io)) {
        log.warn(
          'AI Assistant chat stream: CLIENT_ABORT — final SSE not written (connection already closed). agentId={} detail={}',
          agentId,
          io.message
        )
      } else {
        throw io
      }
    }
  }

  /**
   * OpenAI + native tools off: explicit {@code stream=true} on the request record (no {@link OpenAiChatModel} merge).
   * Uses {@link RestClient} {@code exchange} + line-wise SSE parsing so token deltas reach Studio (same HTTP path
   * that avoids {@code retrieve().body(String)} truncation). Emits Studio SSE then returns.
   */
  private void writeOpenAiToolsOffViaChatCompletionEntity(
    OutputStream out,
    String apiKey,
    String model,
    Prompt openAiPrompt,
    String agentId,
    String wireBaseUrl = null
  ) {
    if (!apiKey) {
      throw new IllegalStateException('Tools-loop tools-off chat: API key missing')
    }
    if (!model?.toString()?.trim()) {
      throw new IllegalStateException('Tools-loop tools-off chat: model missing')
    }
    def msgs = openAiChatCompletionMessagesForApi(openAiPrompt)
    // Groovy cannot resolve `new ChatCompletionRequest(msgs, model, null, true)` reliably: `null` matches
    // both (..., Double, boolean) and (..., List tools, Object toolChoice) → wrong ctor or wrong wire JSON.
    def reqCtor = ChatCompletionRequest.getConstructor(
      java.util.List.class,
      String.class,
      Double.class,
      boolean.class
    )
    def req = reqCtor.newInstance(msgs, model, null, true) as ChatCompletionRequest
    def jsonBody = openAiChatCompletionsWireBodyApplyNeoTemperaturePolicy(ModelOptionsUtils.toJsonString(req))
    try {
      log.debug(
        'Tools-loop tools-off request wire (truncated): {}',
        AiHttpProxy.elideForLog(jsonBody, 1200)
      )
    } catch (Throwable ignored) {}
    log.debug(
      'Tools-loop tools-off: RestClient exchange POST /v1/chat/completions (stream=true; forward upstream SSE) agentId={} model={} messageCount={}',
      agentId,
      model,
      msgs.size()
    )
    try {
      openAiRestClientBuilder(apiKey, wireBaseUrl)
        .defaultHeader(HttpHeaders.ACCEPT, 'text/event-stream, application/json')
        .build()
        .post()
        .uri('/v1/chat/completions')
        .contentType(MediaType.APPLICATION_JSON)
        .body(jsonBody)
        .exchange { httpReq, resp ->
          def status = resp.getStatusCode()
          def statusText = resp.getStatusText()
          def headers = resp.getHeaders()
          if (!status.is2xxSuccessful()) {
            byte[] bytes
            try {
              def eis = resp.getBody()
              bytes = eis != null ? eis.readAllBytes() : new byte[0]
            } finally {
              try {
                resp.close()
              } catch (Throwable ignored) {}
            }
            def bodyStr = new String(bytes, StandardCharsets.UTF_8)
            log.error('Tools-loop tools-off: HTTP {} body=\n{}', status, AiHttpProxy.elideForLog(bodyStr, 4000))
            throw new RestClientResponseException(
              'Tools-loop chat',
              status.value(),
              statusText,
              headers,
              bytes,
              StandardCharsets.UTF_8
            )
          }
          try {
            def is = resp.getBody()
            openAiCopyUpstreamSseChatCompletionsToStudio(is, out, agentId, model)
          } finally {
            try {
              resp.close()
            } catch (Throwable ignored) {}
          }
          ''
        }
    } catch (RestClientResponseException e) {
      String rb = ''
      try {
        rb = e.getResponseBodyAsString(StandardCharsets.UTF_8)
      } catch (Throwable ignored) {}
      log.error('Tools-loop tools-off: HTTP {} body=\n{}', e.statusCode, AiHttpProxy.elideForLog(rb ?: '', 4000))
      Throwable toThrow = openAiPreferIllegalStateForInvalidModel(e, jsonBody?.toString())
      if (toThrow instanceof IllegalStateException) {
        throw (IllegalStateException) toThrow
      }
      throw (RestClientResponseException) toThrow
    }
  }

  /**
   * Spring AI response-shape compatibility across versions.
   * `prompt().call()` may return a response-spec object instead of ChatResponse directly.
   */
  private static String extractContentFromCallResult(def callResult) {
    if (callResult == null) return ''
    def hasContent = false
    def hasChatResponse = false
    try {
      hasContent = callResult?.metaClass?.respondsTo(callResult, 'content')?.size() > 0
    } catch (Throwable ignored) {}
    try {
      hasChatResponse = callResult?.metaClass?.respondsTo(callResult, 'chatResponse')?.size() > 0
    } catch (Throwable ignored) {}

    // Prefer chatResponse() first for adapters that need it. On some Studio stacks OpenAiChatModel's
    // chatCompletionEntity path truncates JSON; native OpenAI tools use openAiExecuteNativeToolsViaRestClientReturnText instead.
    // IMPORTANT: use only one terminal accessor to avoid duplicate upstream requests.
    if (hasChatResponse) {
      def cr = callResult.chatResponse()
      def txt = cr?.result?.output?.text ?: cr?.result?.output?.content
      return txt != null ? txt.toString() : ''
    }
    if (hasContent) {
      def c = callResult.content()
      return c != null ? c.toString() : ''
    }

    def txt = callResult?.result?.output?.text ?: callResult?.result?.output?.content
    return txt != null ? txt.toString() : ''
  }

  /** Unwrap ExecutionException; user-friendly text when the remote hosted chat API returns 5xx. */
  private static Throwable unwrapThrowable(Throwable t) {
    if (t instanceof java.util.concurrent.ExecutionException && t.cause != null) return t.cause
    return t
  }

  /** OpenAI errors often include a JSON body with {@code error.message} — log / surface it for debugging. */
  private static String extractOpenAiHttpErrorBody(Throwable t) {
    Throwable c = unwrapThrowable(t)
    while (c != null) {
      if (c instanceof WebClientResponseException) {
        try {
          return ((WebClientResponseException) c).getResponseBodyAsString(StandardCharsets.UTF_8)
        } catch (Throwable ignored) {
          return ''
        }
      }
      if (c instanceof RestClientResponseException) {
        try {
          return ((RestClientResponseException) c).getResponseBodyAsString(StandardCharsets.UTF_8)
        } catch (Throwable ignored) {
          return ''
        }
      }
      c = c.cause
    }
    return ''
  }

  private static String formatStreamErrorMessage(Throwable t) {
    def root = unwrapThrowable(t)
    def msg = (root?.message ?: root?.toString() ?: 'Unknown error').toString()
    def openAiBody = extractOpenAiHttpErrorBody(t)
    if (openAiBody?.trim() && (msg.contains('api.openai.com') || root instanceof WebClientResponseException || root instanceof RestClientResponseException)) {
      def elided = openAiBody.length() > 2000 ? openAiBody.substring(0, 2000) + '…' : openAiBody
      return 'Chat request failed. HTTP detail: ' + elided
    }
    if (msg.contains('HTTP 5') && msg.contains('api.crafterq.ai')) {
      return '''The remote chat service (api.crafterq.ai) returned a server error (HTTP 5xx). The Studio plugin is working; the failure is upstream.

If Studio logs show a small prompt (well under the configured max), this is not the prompt-length limit—check agent ID, remote service health, and upstream logs. The plugin forwards nearly all inbound request headers to the remote API (except hop-by-hop and outbound Content-Type/Accept/Length).

Please try again or contact your administrator.

Technical detail: ''' + msg
    }
    if (msg.contains('timed out') || msg.contains('Timed out')) {
      return 'The request to the remote chat service timed out. Please try again.'
    }
    return 'Error: ' + msg
  }

  Map chatProxy(
    String agentId,
    String prompt,
    String chatId = null,
    String llm = null,
    String openAiModel = null,
    String openAiApiKey = null,
    String imageModel = null,
    boolean formEngineClientForward = false,
    String formEngineItemPathRaw = null,
    boolean enableTools = true,
    String imageGenerator = null
  ) {
    try {
      crafterQPipelineCancelBindingClear()
      ensureVerboseSpringAiHttpLogging()
      def fullSuppress = false
      def protNorm = null
      if (formEngineClientForward) {
        def n = AuthoringPreviewContext.normalizeRepoPath(formEngineItemPathRaw)
        if (n) {
          protNorm = n
        } else {
          fullSuppress = true
        }
      }
      def springAi = buildSpringAiChatClient(agentId, chatId, llm, openAiModel, openAiApiKey, null, imageModel, fullSuppress, protNorm, enableTools, imageGenerator)
      if (formEngineClientForward && !StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        log.warn(
          'Form-engine client-apply: llm is {} (not a tools-loop RestClient row). Use openAI / xAI / deepSeek / llama / genesis (gemini) on this agent for native RestClient tools + best compliance with crafterqFormFieldUpdates.',
          springAi.llm
        )
      }
      def bodyPrompt = formEngineClientForward ? prependFormEngineClientApplyEnforcement(prompt) : (prompt ?: '').toString()
      def userText = springAi.useTools ? addToolRequiredGuard(bodyPrompt, fullSuppress, protNorm) : bodyPrompt
      Prompt openAiPrompt = null
      def callSpec
      if (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        openAiPrompt = openAiAuthoringPrompt(
          userText,
          fullSuppress,
          protNorm,
          springAi.useTools,
          springAi.studioOps,
          StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi)
        )
        logOpenAiChatCompletionsPayloadApprox(
          agentId,
          (springAi.resolvedChatModel ?: resolveOpenAiModel(openAiModel)),
          openAiPrompt,
          springAi.tools
        )
        if (springAi.useTools) {
          // Native tools are executed via RestClient (see openAiExecuteNativeToolsViaRestClientReturnText), not OpenAiChatModel.
          callSpec = null
        } else {
          callSpec = springAi.chatClient.prompt(openAiPrompt)
        }
      } else {
        callSpec = springAi.useTools
          ? springAi.chatClient.prompt().user(userText).tools(*springAi.tools)
          : springAi.chatClient.prompt().user(userText)
      }
      String content
      if (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi) && springAi.useTools) {
        content = openAiExecuteNativeToolsViaRestClientReturnText(
          StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
          (springAi.resolvedChatModel ?: resolveOpenAiModel(openAiModel)),
          openAiPrompt,
          springAi.tools,
          agentId,
          null,
          null,
          null,
          StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi),
          springAi
        )
      } else {
        def callResult = callSpec.call()
        content = extractContentFromCallResult(callResult)
      }
      if (content == null) content = ''
      return [ok: true, response: [content: content, message: content]]
    } catch (IllegalStateException ise) {
      throw ise
    } catch (Exception e) {
      def body = extractOpenAiHttpErrorBody(e)
      def suffix = body?.trim() ? " Upstream body: ${body.length() > 1500 ? body.substring(0, 1500) + '…' : body}" : ''
      return [ok: false, message: "Spring AI chat failed: ${e.message}${suffix}"]
    }
  }

  /** Expert guidance / SME tools — server progress lines use {@code 🛠️🤓} before the category emoji. */
  private static boolean isExpertGuidanceToolName(String toolName) {
    def n = (toolName ?: '').toString().trim()
    return n == 'QueryExpertGuidance' || n == 'ConsultCrafterQExpert' || n == 'GetCrafterizingPlaybook'
  }

  /**
   * Second emoji after 🛠️ on server tool-progress lines: read 🔍, write/revert/publish/edit ✏️, analysis 📈, other 🔄.
   */
  private static String toolProgressCategoryEmoji(String toolName) {
    def n = (toolName ?: '').toString().trim()
    switch (n) {
      case 'GetContent':
      case 'ListContentTranslationScope':
      case 'GetContentTypeFormDefinition':
      case 'ListStudioContentTypes':
      case 'GetContentVersionHistory':
      case 'GetPreviewHtml':
      case 'FetchHttpUrl':
      case 'QueryExpertGuidance':
      case 'ListPagesAndComponents':
      case 'GetCrafterizingPlaybook':
      case 'ListCrafterQAgentChats':
      case 'GetCrafterQAgentChat':
        return '🔍'
      case 'Tools-loop chat':
        // Waiting on chat.completions between tool rounds — not a repo read; distinct from 🔍 tools.
        return '🔄'
      case 'WriteContent':
      case 'revert_change':
      case 'publish_content':
      case 'GenerateImage':
      case 'update_template':
      case 'update_content':
      case 'update_content_type':
      case 'TranslateContentItem':
      case 'TranslateContentBatch':
      case 'TransformContentSubgraph':
      case 'GetContentSubgraph':
        return '✏️'
      case 'analyze_template':
      case 'ConsultCrafterQExpert':
        return '📈'
      default:
        return '🔄'
    }
  }

  /** Product prefix for injected tool lines: {@code 🛠️} + category (never ⏳). Expert tools add {@code 🤓} after {@code 🛠️}. */
  private static String toolProgressLinePrefix(String toolName) {
    def cat = toolProgressCategoryEmoji(toolName)
    if (isExpertGuidanceToolName(toolName)) {
      return '🛠️🤓' + cat
    }
    return '🛠️' + cat
  }

  /** Short human timing suffix for tool-progress lines (e.g. {@code ·245ms}, {@code ·1.2s}). */
  private static String formatCqDurationSuffix(Long ms) {
    if (ms == null || ms < 0L) {
      return ''
    }
    if (ms < 1000L) {
      return ' ·' + ms + 'ms'
    }
    return ' ·' + String.format(Locale.US, '%.1fs', ms / 1000.0d)
  }

  /**
   * Logs + SSE immediately before each blocking {@code POST /v1/chat/completions} in the native tool loop (same 🛠️ channel).
   */
  private static void openAiEmitRoundWaitSse(
    OutputStream o,
    int zeroBasedRound,
    String model,
    String agentId,
    int wireJsonChars,
    boolean previousRoundHadRepoMutation = false
  ) {
    log.debug(
      'Tools-loop wire → POST /v1/chat/completions phase=native_tool_loop round={} agentId={} model={} wireJsonChars={}',
      zeroBasedRound + 1,
      agentId,
      model,
      wireJsonChars
    )
    if (o == null) {
      return
    }
    try {
      String toolName = 'Tools-loop chat'
      String pfx = toolProgressLinePrefix(toolName)
      String line
      if (zeroBasedRound <= 0) {
        line =
          pfx +
          ' **Starting** — reviewing your request and site context (step ' +
          (zeroBasedRound + 1) +
          ').\n'
      } else if (previousRoundHadRepoMutation) {
        line =
          pfx +
          ' **Main work is in the books** — validation, preview, or wrap-up from here (step ' +
          (zeroBasedRound + 1) +
          ').\n'
      } else {
        line =
          pfx +
          ' **Onward** — next authoring move (step ' +
          (zeroBasedRound + 1) +
          ').\n'
      }
      def event = [text: line, metadata: [status: 'tool-progress', tool: toolName, phase: 'start']]
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(event)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
    }
  }

  /** Mutable holder for plan + tool pipeline wall clock (thread-safe for Reactor + tool threads). */
  private static Map createToolTimingContext() {
    [pipelineStartMs: new AtomicLong(0L)]
  }

  private static void markPipelineWallStart(Map timingCtx) {
    if (timingCtx == null) return
    Object ps = timingCtx.pipelineStartMs
    if (ps instanceof AtomicLong) {
      ((AtomicLong) ps).compareAndSet(0L, System.currentTimeMillis())
    }
  }

  /** Adds {@code toolPipelineWallMs} when the pipeline start was recorded (wall ms from that mark to now). */
  private static void mergeToolPipelineWallMsIntoMetadata(Map metadata, Map timingCtx) {
    if (metadata == null || timingCtx == null) return
    Object ps = timingCtx.pipelineStartMs
    if (!(ps instanceof AtomicLong)) return
    long start = ((AtomicLong) ps).get()
    if (start <= 0L) return
    long wall = System.currentTimeMillis() - start
    if (wall < 0L) wall = 0L
    metadata.toolPipelineWallMs = wall
  }

  /**
   * Emits a chat SSE chunk so the UI shows tool progress while OpenAI runs tools (Reactor thread).
   * Each line starts with {@code 🛠️} plus a category emoji ({@code 🔍} read, {@code ✏️} write/revert, {@code 📈} analysis, {@code 🔄} other); expert guidance tools use {@code 🛠️🤓} before the category emoji. Phases add ✅ / ❌ / ⚠️ where applicable.
   * Non-terminal {@code progress} phase: {@code input.progressMessage} is appended after the prefix (e.g. batch translate dispatch list).
   * @param taskDurationMs wall time for this tool invocation (terminal phases only); rendered as a subtle suffix.
   */
  private void writeToolProgressSse(
    OutputStream o,
    String toolName,
    String phase,
    Map input,
    Throwable err,
    Object toolResult = null,
    Long taskDurationMs = null
  ) {
    if (o == null) return
    try {
      def pfx = toolProgressLinePrefix(toolName)
      def pathFull = (input?.path ?: input?.contentPath ?: input?.templatePath ?: input?.contentType ?: input?.url ?: input?.previewUrl ?: '')?.toString()?.trim() ?: ''
      def path = pathFull
      if (path.length() > 96) {
        path = path.substring(0, 93) + '…'
      }
      String line
      if ('start'.equals(phase)) {
        line = pfx + ' **' + toolName + '**' + (path ? ' (`' + path + '`)' : '') + ' …\n'
      } else if ('progress'.equals(phase)) {
        def body = (input?.progressMessage ?: input?.toolProgressMessage ?: '')?.toString()?.trim() ?: ''
        if (body.length() > 12000) {
          body = body.substring(0, 11800) + '\n… _(truncated)_\n'
        }
        line = body ? (pfx + body + (body.endsWith('\n') ? '' : '\n')) : (pfx + ' **' + toolName + '** …\n')
      } else if ('error'.equals(phase)) {
        def em = (err?.message ?: err?.toString() ?: 'error').toString()
        if (em.length() > 220) {
          em = em.substring(0, 217) + '…'
        }
        line = pfx + ' ❌ **' + toolName + '** failed: ' + em + '\n'
      } else if ('warn'.equals(phase)) {
        def hint = ''
        if (toolResult instanceof Map) {
          def m = (Map) toolResult
          hint = (m.message ?: m.hint ?: m.skippedReason ?: '')?.toString()?.trim() ?: ''
        }
        if (hint.length() > 140) {
          hint = hint.substring(0, 137) + '…'
        }
        if ('TranslateContentItem'.equalsIgnoreCase(toolName ?: '') && path) {
          line =
            pfx +
            ' ⚠️ **TranslateContentItem** (`' +
            path +
            '`) — translation **returned** with warnings' +
            (hint ? ': ' + hint : '.') +
            '\n'
        } else {
          line = pfx + ' ⚠️ **' + toolName + '**' + (path ? ' (`' + path + '`)' : '') + (hint ? ': ' + hint : ' — warning or partial result.') + '\n'
        }
      } else if ('done'.equals(phase)) {
        if ('TranslateContentItem'.equalsIgnoreCase(toolName ?: '') && path) {
          line =
            pfx +
            ' ✅ **TranslateContentItem** (`' +
            path +
            '`) — translation **returned** and saved to the repository.\n'
        } else if ('TranslateContentBatch'.equalsIgnoreCase(toolName ?: '') && toolResult instanceof Map) {
          def tr = (Map) toolResult
          def msg = (tr.message ?: '')?.toString()?.trim()
          line = pfx + ' ✅ **TranslateContentBatch**' + (msg ? ' — ' + msg : ' — finished.') + '\n'
        } else {
          line = pfx + ' ✅ **' + toolName + '**' + (path ? ' (`' + path + '`)' : '') + ' finished.\n'
        }
      } else {
        line = pfx + ' ✅ **' + toolName + '** finished.\n'
      }
      boolean terminal = 'done'.equals(phase) || 'warn'.equals(phase) || 'error'.equals(phase)
      if (terminal) {
        if (line.endsWith('\n')) {
          line = line.substring(0, line.length() - 1)
        }
        line = line + formatCqDurationSuffix(taskDurationMs) + '\n'
      }
      def event = [text: line, metadata: [status: 'tool-progress', tool: toolName, phase: phase]]
      if ('done'.equals(phase)) {
        def tn = toolName?.toString() ?: ''
        if (tn.equalsIgnoreCase('WriteContent')) {
          def repoPath = ''
          if (toolResult instanceof Map) {
            def tr = (Map) toolResult
            repoPath = (tr.path ?: tr.contentPath ?: '')?.toString()?.trim() ?: ''
          }
          if (!repoPath) {
            repoPath = pathFull
          }
          if (repoPath) {
            event.metadata.repoPath = repoPath
          }
        } else if (tn.equalsIgnoreCase('ListContentTranslationScope') && toolResult instanceof Map) {
          def tr = (Map) toolResult
          def rp = tr.root?.toString()?.trim()
          if (rp) {
            event.metadata.repoPath = rp
          }
        } else if (
          (tn.equalsIgnoreCase('TranslateContentItem') ||
            tn.equalsIgnoreCase('TranslateContentBatch') ||
            tn.equalsIgnoreCase('TransformContentSubgraph') ||
            tn.equalsIgnoreCase('GetContentSubgraph')) &&
          toolResult instanceof Map
        ) {
          def tr = (Map) toolResult
          def rp = tr.root?.toString()?.trim()
          if (!rp && tr.paths instanceof List && !((List) tr.paths).isEmpty()) {
            rp = ((List) tr.paths).get(0)?.toString()?.trim()
          }
          if (rp) {
            event.metadata.repoPath = rp
          }
        }
      }
      synchronized (o) {
        o.write(("data: ${JsonOutput.toJson(event)}\n\n").getBytes(StandardCharsets.UTF_8))
        o.flush()
      }
    } catch (Throwable ignored) {
      // never break tool execution
    }
  }

  /**
   * When SSE has already started, errors must be sent as an SSE frame — not by switching to JSON
   * (avoids {@code AsyncRequestNotUsableException} on committed async responses).
   */
  /**
   * OpenAI streaming often ends with an assistant delta that has {@code finishReason=stop} (or similar) but
   * <strong>empty</strong> {@code getText()} and no {@code completed} flag in message metadata. If we drop that
   * chunk, the Studio UI never receives {@code metadata.completed=true} and appears to hang until timeout.
   */
  private static String extractOpenAiFinishReason(def gen, def message, Map messageMeta, ChatResponse chatResponse) {
    try {
      def crm = chatResponse?.getMetadata()
      def chatMap = crm instanceof Map ? (crm as Map) : [:]
      def genMap = [:]
      try {
        if (gen?.metaClass?.respondsTo(gen, 'getMetadata')) {
          def gm = gen.getMetadata()
          if (gm instanceof Map) {
            genMap = gm as Map
          } else if (gm != null && gm.metaClass?.respondsTo(gm, 'getFinishReason')) {
            def frObj = gm.getFinishReason()
            if (frObj != null && frObj.toString().trim()) return frObj.toString().trim()
          }
        }
      } catch (Throwable ignored) {}
      def msgMap = (messageMeta instanceof Map) ? (messageMeta as Map) : [:]
      def keys = ['finishReason', 'finish_reason', 'reason']
      for (def map : [msgMap, genMap, chatMap]) {
        if (map == null) continue
        for (String k : keys) {
          def v = map[k]
          if (v != null && v.toString().trim()) return v.toString().trim()
        }
      }
    } catch (Throwable ignored) {}
    return ''
  }

  /** Terminal finish reasons from Tools-loop chat streaming (and some reasoning variants). */
  private static boolean openAiFinishReasonImpliesStreamDone(String finishReason) {
    if (!finishReason) return false
    def fr = finishReason.trim().toLowerCase()
    return fr == 'stop' ||
      fr == 'length' ||
      fr == 'content_filter' ||
      fr == 'tool_calls' ||
      fr == 'end_turn' ||
      fr.endsWith('_turn')
  }

  /**
   * Browser closed the tab, aborted fetch, or proxy dropped the SSE connection — not an LLM or upstream logic failure by itself.
   */
  private static boolean isSseClientDisconnected(Throwable t) {
    if (t == null) {
      return false
    }
    if (t instanceof ExecutionException && t.getCause() != null) {
      return isSseClientDisconnected(t.getCause())
    }
    Throwable cur = t
    int guard = 0
    while (cur != null && guard++ < 28) {
      String cn = cur.class.name
      String msg = (cur.message ?: '').toString().toLowerCase(Locale.ROOT)
      if (msg.contains('ob is null') || msg.contains('outputbuffer') || msg.contains('output buffer')) {
        return true
      }
      if (cur instanceof IllegalStateException &&
        (msg.contains('getoutputstream') || msg.contains('committed'))) {
        return true
      }
      if (msg.contains('response not usable') || msg.contains('not usable after response')) {
        return true
      }
      if (cn == 'org.springframework.web.context.request.async.AsyncRequestNotUsableException') {
        return true
      }
      if (cn.contains('ClientAbortException')) {
        return true
      }
      if (cn.contains('EofException') && cn.contains('jetty')) {
        return true
      }
      if (cur instanceof IOException) {
        if (msg.contains('broken pipe') || msg.contains('connection reset') || msg.contains('connection aborted')) {
          return true
        }
      }
      cur = cur.cause
    }
    return false
  }

  /**
   * When the author closes the chat stream (Stop / navigates away), Tomcat/Jetty usually breaks the outbound SSE write.
   * The Tools-loop+tools path runs work on a worker thread while the servlet thread waits on {@link Future#get}; probing
   * {@code flush()} between short timeouts detects disconnect so we can cancel tools and stop burning tokens.
   */
  private boolean probeSseClientDisconnected(OutputStream out) {
    if (out == null) {
      return false
    }
    try {
      synchronized (out) {
        out.flush()
      }
      return false
    } catch (Throwable t) {
      if (isSseClientDisconnected(t)) {
        return true
      }
      log.debug('probeSseClientDisconnected: flush raised {}', t.message)
      return false
    }
  }

  /**
   * Author-facing text for terminal SSE errors. {@link RestClientResponseException#getMessage()} is often only
   * {@code RestClientResponseException#getMessage()} is often only the short ctor label (first ctor arg) — authors need HTTP status and the upstream JSON {@code error} body.
   */
  private static String formatSseStreamErrorMessage(Throwable t) {
    if (t == null) {
      return 'Stream error'
    }
    Throwable cur = t
    int walk = 0
    while (cur != null && walk++ < 16) {
      if (cur instanceof IllegalStateException) {
        String m = (cur.message ?: '').toString()
        return m?.trim() ? m : 'Configuration error'
      }
      if (cur instanceof RestClientResponseException) {
        RestClientResponseException r = (RestClientResponseException) cur
        String body = ''
        try {
          body = r.getResponseBodyAsString(StandardCharsets.UTF_8) ?: ''
        } catch (Throwable ignored) {
        }
        int code = r.getStatusCode().value()
        def st = (r.getStatusText() ?: '').toString()
        def elided = AiHttpProxy.elideForLog(body, 1500)
        return "Tools-loop chat HTTP ${code} ${st}: ${elided ?: '(empty body)'}".trim()
      }
      if (cur instanceof WebClientResponseException) {
        WebClientResponseException w = (WebClientResponseException) cur
        String body = ''
        try {
          body = w.getResponseBodyAsString(StandardCharsets.UTF_8) ?: ''
        } catch (Throwable ignored) {
        }
        int code = w.getStatusCode().value()
        def st = (w.getStatusText() ?: '').toString()
        def elided = AiHttpProxy.elideForLog(body, 1500)
        return "Tools-loop chat HTTP ${code} ${st}: ${elided ?: '(empty body)'}".trim()
      }
      cur = cur.cause
    }
    Throwable root = t
    int guard = 0
    while (root?.cause != null && root != root.cause && guard++ < 12) {
      root = root.cause
    }
    return (root?.message ?: t?.message ?: 'Stream error').toString()
  }

  private void writeSseErrorFrame(OutputStream out, Throwable t) {
    if (out == null) return
    if (isSseClientDisconnected(t)) {
      return
    }
    try {
      def msg = formatSseStreamErrorMessage(t)
      if (msg.length() > 1800) {
        msg = msg.substring(0, 1800) + '…'
      }
      def errEvent = [text: '', metadata: [error: true, completed: true, message: msg]]
      synchronized (out) {
        out.write(("data: ${JsonOutput.toJson(errEvent)}\n\n").getBytes(StandardCharsets.UTF_8))
        out.flush()
      }
    } catch (Throwable io) {
      if (isSseClientDisconnected(io)) {
        log.debug('writeSseErrorFrame skipped (response already unusable / client gone): {}', io.message)
      } else {
        log.warn('writeSseErrorFrame failed: {}', io.message)
      }
    }
  }

  /**
   * Exactly-once terminal SSE for the native-tools worker vs servlet recovery paths: compare-and-set so only one
   * thread emits completed/error-with-completed (avoids duplicate terminal events if both race on cancel/timeout).
   */
  private static boolean tryClaimOpenAiToolsTerminalEmit(AtomicBoolean emittedFlag) {
    return emittedFlag == null || emittedFlag.compareAndSet(false, true)
  }

  private void ensureSseTerminalCompletedIfNeeded(
    OutputStream out,
    Map toolTimingCtx,
    AtomicBoolean emittedFlag,
    String reasonForLog
  ) {
    if (out == null) {
      return
    }
    if (!tryClaimOpenAiToolsTerminalEmit(emittedFlag)) {
      return
    }
    try {
      log.warn('AI Assistant SSE: forcing terminal completed frame (UI would hang otherwise) — {}', reasonForLog)
      synchronized (out) {
        def doneMeta = new LinkedHashMap()
        doneMeta.completed = true
        mergeToolPipelineWallMsIntoMetadata(doneMeta, toolTimingCtx)
        out.write(("data: ${JsonOutput.toJson([text: '', metadata: doneMeta])}\n\n").getBytes(StandardCharsets.UTF_8))
        out.flush()
      }
    } catch (Throwable t) {
      if (!isSseClientDisconnected(t)) {
        log.warn('ensureSseTerminalCompletedIfNeeded failed: {}', t.message)
      }
    }
  }

  Object chatStreamWithSpringAi(
    String agentId,
    String prompt,
    String chatId = null,
    String llm = null,
    String openAiModel = null,
    String openAiApiKey = null,
    String imageModel = null,
    boolean formEngineClientForward = false,
    String formEngineItemPathRaw = null,
    boolean enableTools = true,
    String imageGenerator = null
  ) {
    OutputStream out = null
    try {
      ensureVerboseSpringAiHttpLogging()
      response?.setContentType('text/event-stream')
      response?.setHeader('Cache-Control', 'no-cache')
      response?.setHeader('X-Accel-Buffering', 'no')

      out = response?.getOutputStream()
      out.write(': connected\n\n'.getBytes(StandardCharsets.UTF_8))
      out.flush()
      // New prompt / stream: ensure no stale native-tools cancel binding leaked onto this servlet thread.
      crafterQPipelineCancelBindingClear()

      def toolTimingCtx = createToolTimingContext()
      def toolProgressListener = { String tn, String ph, Map inp, Throwable er = null, Object tres = null, Long taskDurMs = null ->
        writeToolProgressSse(out, tn, ph, inp ?: [:], er, tres, taskDurMs)
      }

      def fullSuppress = false
      def protNorm = null
      if (formEngineClientForward) {
        def n = AuthoringPreviewContext.normalizeRepoPath(formEngineItemPathRaw)
        if (n) {
          protNorm = n
        } else {
          fullSuppress = true
        }
      }
      def springAi = buildSpringAiChatClient(agentId, chatId, llm, openAiModel, openAiApiKey, toolProgressListener, imageModel, fullSuppress, protNorm, enableTools, imageGenerator)
      if (formEngineClientForward && !StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        log.warn(
          'Form-engine client-apply: llm is {} (not a tools-loop RestClient row). Use openAI / xAI / deepSeek / llama / genesis (gemini) on this agent for native RestClient tools + best compliance with crafterqFormFieldUpdates.',
          springAi.llm
        )
      }
      def bodyPrompt = formEngineClientForward ? prependFormEngineClientApplyEnforcement(prompt) : (prompt ?: '').toString()
      def userText = springAi.useTools ? addToolRequiredGuard(bodyPrompt, fullSuppress, protNorm) : bodyPrompt
      def toolRequiredIntent = springAi.useTools && isToolRequiredIntent(bodyPrompt)
      log.debug("chatStreamWithSpringAi start: llm={} agentId={} promptLen={} toolRequiredIntent={} chatIdPresent={} useTools={} enableTools={} formEngineClientForward={} fullSuppressWrites={} protectedFormItemPath={}",
        springAi.llm, agentId, (bodyPrompt ?: '').length(), toolRequiredIntent, (chatId != null && chatId.toString().trim().length() > 0), springAi.useTools, enableTools, formEngineClientForward, fullSuppress, protNorm ?: '')

      // OpenAI + tools off: RestClient + upstream SSE (stream=true), not OpenAiChatModel (merge can break stream).
      // OpenAI + tools on: avoid OpenAiChatModel / OpenAiApi.chatCompletionEntity (truncated JSON on some Studio stacks);
      // use RestClient + stream:false + JsonSlurper tool loop on a worker thread with the same await budget.
      Prompt openAiPrompt = null
      def promptSpec
      if (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi)) {
        openAiPrompt = openAiAuthoringPrompt(
          userText,
          fullSuppress,
          protNorm,
          springAi.useTools,
          springAi.studioOps,
          StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi)
        )
        logOpenAiChatCompletionsPayloadApprox(
          agentId,
          (springAi.resolvedChatModel ?: resolveOpenAiModel(openAiModel)),
          openAiPrompt,
          springAi.tools
        )
        if (!springAi.useTools) {
          writeOpenAiToolsOffViaChatCompletionEntity(
            out,
            StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
            (springAi.resolvedChatModel ?: resolveOpenAiModel(openAiModel)),
            openAiPrompt,
            agentId,
            StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi)
          )
          return null
        }
        promptSpec = springAi.chatClient.prompt(openAiPrompt).tools(*springAi.tools)
      } else {
        promptSpec = springAi.useTools
          ? springAi.chatClient.prompt().user(userText).tools(*springAi.tools)
          : springAi.chatClient.prompt().user(userText)
      }
      def openAiToolsBlockingForStudioStream = (StudioAiLlmKind.useToolsLoopChatRestClient(springAi.llm, springAi) && springAi.useTools)

      // OpenAI + native tools: RestClient loop streams **## Plan** (or fallback) before repo tool rows. Sending the
      // workflow hint first makes the client treat 🛠️ as the first chunk and clears main text — authors see tools
      // with no plan above them. Flux/Spring-AI tool paths still get the hint (long gaps before first delta).
      if (toolRequiredIntent && !openAiToolsBlockingForStudioStream) {
        synchronized (out) {
          out.write(
            (
              'data: ' +
                JsonOutput.toJson([
                  text    : '🛠️🔄 **Working on your request** — short pauses between steps are normal. You’ll see progress lines below as each part finishes.\n',
                  metadata: [status: 'tool-workflow-hint']
                ]) +
                '\n\n'
            ).getBytes(StandardCharsets.UTF_8)
          )
          out.flush()
        }
      }

      def modelForLog = (springAi.resolvedChatModel ?: resolveOpenAiModel(openAiModel))

      def flux = null
      try {
        // Tool workflows: chatResponse() flux — skipped for Tools-loop+tools (hung upstream SSE on some Studio JVMs).
        if (springAi.useTools && !openAiToolsBlockingForStudioStream) {
          def streamSpec = promptSpec.stream()
          if (streamSpec?.metaClass?.respondsTo(streamSpec, 'chatResponse')) {
            flux = streamSpec.chatResponse()
          }
        }
      } catch (Throwable t) {
        log.warn('chatStreamWithSpringAi: chatResponse flux setup failed: {}', t.message)
      }

      if (flux != null && springAi.useTools && !openAiToolsBlockingForStudioStream) {
        markPipelineWallStart(toolTimingCtx)
        log.debug(
          'chatStreamWithSpringAi stream path: using chatResponse flux (await max {} ms). llm={} model={}',
          CHAT_FLUX_AWAIT_MS,
          springAi.llm,
          modelForLog
        )
        def latch = new CountDownLatch(1)
        def errorRef = new AtomicReference<Throwable>(null)
        def sentCompletedAtomic = new AtomicBoolean(false)
        def sawFirstClientChunk = new AtomicBoolean(false)
        def loggedEmptyAssistantTextDelta = new AtomicBoolean(false)

        def fluxTimed = (flux instanceof Flux) ? ((Flux) flux).timeout(Duration.ofMillis(CHAT_FLUX_AWAIT_MS)) : flux

        log.debug('chatStreamWithSpringAi: subscribing to chatResponse flux (agentId={}, model={})', agentId, modelForLog)
        def fluxDisposable = fluxTimed.subscribe(
          { ChatResponse chatResponse ->
            def gen = chatResponse?.getResult() ?: (chatResponse?.getResults() != null && !chatResponse.getResults().isEmpty() ? chatResponse.getResults().get(0) : null)
            def message = gen?.getOutput()
            def content = message != null ? (message.getText() ?: '') : ''
            def rawMeta = message?.getMetadata()
            def meta = (rawMeta instanceof Map) ? (rawMeta as Map) : [:]
            if (meta == null) meta = [:]
            def completed = (meta?.completed != null) ? meta.completed.asBoolean() : false
            def finishReason = extractOpenAiFinishReason(gen, message, meta as Map, chatResponse)
            def streamFinished = completed || openAiFinishReasonImpliesStreamDone(finishReason)

            if ((content == null || content.toString().isEmpty()) && !streamFinished) {
              def toolCallNames = []
              try {
                if (message?.metaClass?.respondsTo(message, 'getToolCalls')) {
                  def tcs = message.getToolCalls()
                  if (tcs != null && !tcs.isEmpty()) {
                    tcs.each { tc ->
                      def nm = null
                      try {
                        if (tc?.metaClass?.respondsTo(tc, 'name')) nm = tc.name()
                      } catch (Throwable ignored) {}
                      if (!nm) {
                        try {
                          if (tc?.metaClass?.respondsTo(tc, 'getName')) nm = tc.getName()
                        } catch (Throwable ignored2) {}
                      }
                      toolCallNames << (nm?.toString()?.trim() ?: '?')
                    }
                  }
                }
              } catch (Throwable ignored) {}
              if (toolCallNames && !toolCallNames.isEmpty()) {
                log.debug(
                  'chatStreamWithSpringAi: stream delta empty assistant text but toolCalls={} (agentId={}, model={})',
                  toolCallNames,
                  agentId,
                  modelForLog
                )
              } else if (loggedEmptyAssistantTextDelta.compareAndSet(false, true)) {
                log.debug(
                  'chatStreamWithSpringAi: stream delta with empty assistant text (agentId={}, model={}); some adapters only emit chunks when there is assistant text or completed=true. Some chat models may stream tool/reasoning segments without text first — the browser stays blank until the first text chunk (this is not proof the HTTP request body was invalid).',
                  agentId,
                  modelForLog
                )
              }
              return
            }

            if (sawFirstClientChunk.compareAndSet(false, true)) {
              log.debug('chatStreamWithSpringAi: first SSE chunk from chatResponse flux (agentId={}, model={})', agentId, modelForLog)
            }
            def metaOut = new LinkedHashMap((meta ?: [:]) as Map)
            if (streamFinished) {
              metaOut.completed = true
              mergeToolPipelineWallMsIntoMetadata(metaOut, toolTimingCtx)
            }
            if (finishReason && log.isDebugEnabled()) {
              log.debug('chatStreamWithSpringAi: chunk finishReason={} streamFinished={} (agentId={}, model={})', finishReason, streamFinished, agentId, modelForLog)
            }
            String fluxChunkText = openAiStripForbiddenMetaPlanFromAssistantText((content ?: '').toString())
            def event = [text: fluxChunkText, metadata: metaOut]
            if (streamFinished) sentCompletedAtomic.set(true)
            def payload = "data: ${JsonOutput.toJson(event)}\n\n"
            synchronized (out) {
              out.write(payload.getBytes(StandardCharsets.UTF_8))
              out.flush()
            }
          },
          { Throwable err ->
            log.warn('chatStreamWithSpringAi: chatResponse flux onError: {} — agentId={} model={}', err?.message, agentId, modelForLog)
            try {
              def body = extractOpenAiHttpErrorBody(err)
              if (body?.trim()) {
                log.error('Tools-loop chat error response body: {}', AiHttpProxy.elideForLog(body, 4000))
              }
            } catch (Throwable ignored) {}
            errorRef.set(err)
            latch.countDown()
          },
          {
            log.debug('chatStreamWithSpringAi: chatResponse flux onComplete (agentId={}, model={}, terminalChunkAlreadySent={})', agentId, modelForLog, sentCompletedAtomic.get())
            synchronized (out) {
              if (!sentCompletedAtomic.get()) {
                def doneFluxMeta = new LinkedHashMap()
                doneFluxMeta.completed = true
                mergeToolPipelineWallMsIntoMetadata(doneFluxMeta, toolTimingCtx)
                def event = [text: '', metadata: doneFluxMeta]
                def payload = "data: ${JsonOutput.toJson(event)}\n\n"
                out.write(payload.getBytes(StandardCharsets.UTF_8))
                out.flush()
              }
            }
            latch.countDown()
          }
        )

        // Poll with short awaits so UI Stop / SSE disconnect disposes the flux promptly instead of
        // blocking for CHAT_FLUX_AWAIT_MS on latch.await alone.
        long fluxDeadline = System.currentTimeMillis() + CHAT_FLUX_AWAIT_MS
        boolean fluxFinishedInTime = false
        while (true) {
          long remaining = fluxDeadline - System.currentTimeMillis()
          if (remaining <= 0L) {
            break
          }
          long slice = Math.min(250L, remaining)
          if (latch.await(slice, TimeUnit.MILLISECONDS)) {
            fluxFinishedInTime = true
            break
          }
          if (probeSseClientDisconnected(out)) {
            log.warn(
              'AI Assistant chat stream: CLIENT_ABORT — author stopped chat or browser closed SSE; disposing chatResponse flux subscription. agentId={} model={}',
              agentId,
              modelForLog
            )
            try {
              fluxDisposable?.dispose()
            } catch (Throwable ignored) {
            }
            return null
          }
        }
        if (!fluxFinishedInTime) {
          log.warn(
            'chatStreamWithSpringAi: chatResponse flux did not complete within {} ms (agentId={}, model={}).',
            CHAT_FLUX_AWAIT_MS,
            agentId,
            modelForLog
          )
          log.debug(
            'AI Assistant: cancelling Reactor subscription to tools-loop POST /v1/chat/completions (agentId={}, model={}); this closes the outbound HTTP connection so the chat host sees a client disconnect for this request.',
            agentId,
            modelForLog
          )
          try {
            fluxDisposable?.dispose()
          } catch (Throwable ignored) {}
          def errAfterCancel = errorRef.get()
          if (errAfterCancel != null) {
            log.error('chatStreamWithSpringAi: flux error after cancel', errAfterCancel)
            writeSseErrorFrame(out, errAfterCancel)
          } else {
            def msg = """Chat stream did not finish within ${(CHAT_FLUX_AWAIT_MS / 1000) as int} seconds (server-side limit); the Studio plugin cancelled the upstream HTTP request to your configured chat host.

If this is unexpected: verify outbound HTTPS from Studio to that host, API key and account status, and the model id (${modelForLog}).
Check Studio logs for Spring AI / WebClient / reactor.netty lines emitted for this request."""
            writeSseErrorFrame(out, new TimeoutException(msg))
          }
          return null
        }

        def fluxErr = errorRef.get()
        if (fluxErr != null) {
          log.error('chatStreamWithSpringAi: chatResponse flux failed', fluxErr)
          writeSseErrorFrame(out, fluxErr)
          return null
        }
      } else {
        log.debug(
          'chatStreamWithSpringAi: no chatResponse flux (useTools={}, openAiBlockingTools={}, llm={}) — using promptSpec.call() fallback',
          springAi.useTools,
          openAiToolsBlockingForStudioStream,
          springAi.llm
        )
        if (openAiToolsBlockingForStudioStream) {
          log.debug(
            'chatStreamWithSpringAi: Tools-loop+tools RestClient loop with {} ms cap (agentId={}, model={})',
            CHAT_FLUX_AWAIT_MS,
            agentId,
            modelForLog
          )
          if (openAiPrompt == null) {
            throw new IllegalStateException('Tools-loop tools stream: prompt missing')
          }
          ExecutorService pool = Executors.newSingleThreadExecutor()
          AtomicBoolean cancelRequested = new AtomicBoolean(false)
          AtomicBoolean openAiToolsTerminalEmitted = new AtomicBoolean(false)
          String toolDiagSessionId = 'td-' + java.util.UUID.randomUUID().toString()
          try {
            def fut = pool.submit({
              crafterQToolWorkerDiagSessionBind(toolDiagSessionId)
              try {
                writeOpenAiToolsOnViaRestClientToolLoop(
                  out,
                  StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(springAi),
                  (springAi.resolvedChatModel ?: resolveOpenAiModel(openAiModel)),
                  openAiPrompt,
                  springAi.tools,
                  agentId,
                  toolTimingCtx,
                  cancelRequested,
                  openAiToolsTerminalEmitted,
                  StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(springAi),
                  springAi
                )
                null
              } finally {
                crafterQToolWorkerDiagSessionEnd()
              }
            } as Callable)
            long deadline = System.currentTimeMillis() + CHAT_FLUX_AWAIT_MS
            boolean stoppedByClient = false
            long pipelineWaitStartMs = System.currentTimeMillis()
            long lastSseHeartbeatMs = pipelineWaitStartMs
            try {
              while (!fut.isDone()) {
                long waitMs = Math.min(250L, deadline - System.currentTimeMillis())
                if (waitMs <= 0L) {
                  cancelRequested.set(true)
                  try {
                    fut.cancel(true)
                  } catch (Throwable ignored) {
                  }
                  log.warn(
                    'AI Assistant chat stream: server-side timeout — cancelling Tools-loop tool worker ({}s cap). agentId={} model={}',
                    (CHAT_FLUX_AWAIT_MS / 1000) as int,
                    agentId,
                    modelForLog
                  )
                  def msg = """Tools-loop chat did not finish within ${(CHAT_FLUX_AWAIT_MS / 1000) as int} seconds (server-side limit); the request was cancelled.

If this is unexpected: verify outbound HTTPS from Studio to your configured chat host, API key and account status, and the model id (${modelForLog})."""
                  if (tryClaimOpenAiToolsTerminalEmit(openAiToolsTerminalEmitted)) {
                    writeSseErrorFrame(out, new TimeoutException(msg))
                  }
                  pool.shutdownNow()
                  return null
                }
                try {
                  fut.get(waitMs, TimeUnit.MILLISECONDS)
                } catch (TimeoutException te) {
                  if (probeSseClientDisconnected(out)) {
                    cancelRequested.set(true)
                    try {
                      fut.cancel(true)
                    } catch (Throwable ignored) {
                    }
                    stoppedByClient = true
                    log.warn(
                      'AI Assistant chat stream: CLIENT_ABORT — author stopped chat or browser closed SSE; cancelling Tools-loop tool worker (interrupt + executor shutdown). agentId={} model={}',
                      agentId,
                      modelForLog
                    )
                    break
                  }
                  long nowHb = System.currentTimeMillis()
                  if (nowHb - lastSseHeartbeatMs >= OPENAI_SSE_WAIT_HEARTBEAT_MS) {
                    lastSseHeartbeatMs = nowHb
                    long elapsedSec = (nowHb - pipelineWaitStartMs) / 1000L
                    def workerPhase = crafterQToolWorkerDiagPhaseGet(toolDiagSessionId)
                    log.debug(
                      'Studio AI Assistant SSE heartbeat: waiting on Tools-loop+tools worker elapsedSec={} agentId={} model={} workerPhase={}',
                      elapsedSec,
                      agentId,
                      modelForLog,
                      workerPhase ? workerPhase : '(worker phase unset — e.g. not in native tool loop yet)'
                    )
                    openAiEmitSsePipelineHeartbeat(
                      out,
                      elapsedSec,
                      OPENAI_SSE_WAIT_HEARTBEAT_MS / 1000L,
                      openAiPipelineWaitHintMarkdown(workerPhase)
                    )
                  }
                }
              }
              if (stoppedByClient) {
                pool.shutdownNow()
                try {
                  fut.get(5L, TimeUnit.SECONDS)
                } catch (Throwable ignored) {
                }
                ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, openAiToolsTerminalEmitted, 'SSE client gone or Stop — worker cancelled')
                log.warn(
                  'AI Assistant chat stream: CLIENT_ABORT — executor shutdownNow() applied after client abort; worker thread interrupted if still running. agentId={}',
                  agentId
                )
                return null
              }
              try {
                fut.get()
              } catch (CancellationException ce) {
                log.warn(
                  'AI Assistant chat stream: Tools-loop tool Future cancelled (timeout or client abort). agentId={} detail={}',
                  agentId,
                  ce.message
                )
                ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, openAiToolsTerminalEmitted, 'Tools-loop tool Future cancelled')
                return null
              } catch (ExecutionException ee) {
                Throwable c = ee.getCause() != null ? ee.getCause() : ee
                if (c instanceof InterruptedException && CRAFTQ_PIPELINE_CANCELLED == (c.message ?: '').toString()) {
                  log.warn(
                    'AI Assistant chat stream: Tools-loop tool pipeline exited cooperatively after CLIENT_ABORT cancel flag. agentId={}',
                    agentId
                  )
                  ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, openAiToolsTerminalEmitted, 'pipeline cancelled cooperatively')
                  return null
                }
                if (isSseClientDisconnected(ee) || isSseClientDisconnected(c)) {
                  log.warn(
                    'AI Assistant chat stream: CLIENT_ABORT during OpenAI tool workflow — {}',
                    c?.message ?: ee.message
                  )
                  ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, openAiToolsTerminalEmitted, 'client disconnect during tool workflow')
                  return null
                }
                if (c instanceof IllegalStateException) {
                  log.error('chatStreamWithSpringAi: Tools-loop tool worker failed', c)
                  if (tryClaimOpenAiToolsTerminalEmit(openAiToolsTerminalEmitted)) {
                    writeSseErrorFrame(out, c)
                  }
                  return null
                }
                throw ee
              }
              ensureSseTerminalCompletedIfNeeded(
                out,
                toolTimingCtx,
                openAiToolsTerminalEmitted,
                'worker returned without emitting metadata.completed (recovery)'
              )
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt()
              cancelRequested.set(true)
              try {
                fut.cancel(true)
              } catch (Throwable ignored) {
              }
              log.warn(
                'AI Assistant chat stream: servlet thread interrupted while waiting for Tools-loop tool worker — cancelling. agentId={}',
                agentId
              )
              ensureSseTerminalCompletedIfNeeded(out, toolTimingCtx, openAiToolsTerminalEmitted, 'servlet thread interrupted')
              return null
            }
          } finally {
            try {
              pool.shutdownNow()
            } catch (Throwable ignored) {}
          }
        } else {
          def callResult = promptSpec.call()
          def content = extractContentFromCallResult(callResult)
          def event = [text: (content ?: ''), metadata: [:]]
          synchronized (out) {
            out.write(("data: ${JsonOutput.toJson(event)}\n\n").getBytes(StandardCharsets.UTF_8))
            def doneCallMeta = new LinkedHashMap()
            doneCallMeta.completed = true
            mergeToolPipelineWallMsIntoMetadata(doneCallMeta, toolTimingCtx)
            out.write(("data: ${JsonOutput.toJson([text: '', metadata: doneCallMeta])}\n\n").getBytes(StandardCharsets.UTF_8))
            out.flush()
          }
        }
      }

      return null
    } catch (IllegalStateException ise) {
      if (out != null) {
        try {
          writeSseErrorFrame(out, ise)
        } catch (Throwable ignored) {
        }
        return null
      }
      throw ise
    } catch (Throwable t) {
      if (isSseClientDisconnected(t)) {
        log.warn(
          'AI Assistant chat stream: client aborted connection (UI Stop, fetch AbortError, tab closed, or proxy drop) — server pipeline stopped. detail={}',
          t.message
        )
        return null
      }
      log.error('chatStreamWithSpringAi failed', t)
      // Once getOutputStream() ran for SSE, never fall back to JSON in stream.post — async response may be committed.
      if (out != null) {
        writeSseErrorFrame(out, t)
        return null
      }
      return [message: "Spring AI stream failed: ${t.message}"]
    }
  }

  Object chatStreamProxy(String agentId, String prompt, String chatId = null) {
    def streamUrl = "https://api.crafterq.ai/v1/chats?agentId=${URLEncoder.encode(agentId, 'UTF-8')}&stream=true"
    def payload = (chatId ? [prompt: prompt, chatId: chatId] : [prompt: prompt])
    def payloadBytes = JsonOutput.toJson(payload).getBytes('UTF-8')

    HttpURLConnection conn = null
    InputStream inputStream = null

    try {
      conn = (HttpURLConnection) new URL(streamUrl).openConnection()
      conn.setRequestMethod('POST')
      conn.setRequestProperty('Content-Type', 'application/json')
      conn.setRequestProperty('Accept', 'text/event-stream')
      conn.setDoOutput(true)
      conn.setChunkedStreamingMode(0)

      def chatUserHeader = request?.getHeader('X-CrafterQ-Chat-User')
      if (chatUserHeader) conn.setRequestProperty('X-CrafterQ-Chat-User', chatUserHeader)

      def os = conn.getOutputStream()
      try { os.write(payloadBytes) } finally { os.close() }

      def status = conn.getResponseCode()
      if (status < 200 || status >= 300) {
        response?.setStatus(status)
        def err = conn.getErrorStream()?.text ?: conn.getResponseMessage()
        return [message: "Upstream error: ${err}"]
      }

      inputStream = conn.getInputStream()
      response?.setContentType('text/event-stream')
      response?.setHeader('Cache-Control', 'no-cache')
      response?.setHeader('X-Accel-Buffering', 'no')

      def out = response?.getOutputStream()
      byte[] buf = new byte[4096]
      int n
      while ((n = inputStream.read(buf)) != -1) {
        out.write(buf, 0, n)
        out.flush()
      }
      return null
    } finally {
      try { inputStream?.close() } catch (ignored) {}
      try { conn?.disconnect() } catch (ignored) {}
    }
  }

  String getPluginKey() {
    return pluginConfig?.getString('key')
  }

  Object proxyImage(String url) {
    if (!url?.toString()?.trim()) throw new IllegalArgumentException('Missing required parameter: url')
    def httpClient = org.apache.http.impl.client.HttpClients.createDefault()
    def req = new org.apache.http.client.methods.HttpGet(url.toString())
    try (org.apache.http.client.methods.CloseableHttpResponse res = httpClient.execute(req)) {
      def entity = res.getEntity()
      if (entity != null) {
        def inputStream = entity.getContent()
        response.contentType = 'image/png'
        response.outputStream << inputStream
        inputStream.close()
        response.flushBuffer()
      }
      return null
    }
  }
}

