// Copy to: config/studio/scripts/aiassistant/llm/groq/runtime.groovy
// Agent: <llm>script:groq</llm>
//
// Groq Cloud — **Groq** chat model ids only (`<llmModel>` / POST `llmModel`) + native CMS tools via Studio’s tools-loop HTTP.
//
// **Spring AI** is vendor-neutral (Anthropic, Ollama, Azure, many chat hosts). This sample imports `OpenAiApi` /
// `OpenAiChatModel` from the **`spring-ai-openai`** library module — those types implement one **HTTP JSON contract**
// (historically associated with that name on the wire), not “Spring AI only works with OpenAI Inc.” Groq documents the
// same contract at https://console.groq.com/docs/openai (URL slug only — Groq’s console and Groq’s keys).
//
// Required on the Studio host:
//   export GROQ_API_KEY=gsk_...
// Optional — tools-loop chat base URL (host only, no trailing /v1). Plugin env names:
//   export GROQ_OPENAI_COMPAT_BASE_URL=https://api.groq.com/openai
//   export SCRIPT_LLM_OPENAI_COMPAT_BASE_URL=...   (same meaning if you share one pattern across script LLMs)
//   export SCRIPT_LLM_API_KEY=...                  (optional alias for GROQ_API_KEY)
// If unset, this sample uses Groq’s documented public base for that wire (path ends with `/openai` on **api.groq.com** —
// Groq’s routing, not OpenAI’s servers). Pin the URL yourself if policy requires it.
//
// **Where HTTP goes:** `OpenAiApi` / `OpenAiChatModel` here only call **`base`** (Groq by default). They never fall back to
// **api.openai.com**. If you set `GROQ_OPENAI_COMPAT_BASE_URL` / `SCRIPT_LLM_OPENAI_COMPAT_BASE_URL` to OpenAI’s host, this
// script refuses that (Groq-only sample). **Separate:** built-in image / expert-embedding tools still use Studio’s global
// provider (`resolveOpenAiApiKey` below) — that may be OpenAI or another vendor depending on Studio config, not Groq chat.
//
// Chat model id (required — Groq deprecates model ids over time; this sample does not bake a default):
//   <llmModel>…</llmModel> or POST llmModel → `req.openAiModelParam` (Studio request field name — value is your Groq model id).
// Example (slash form is normal on Groq): meta-llama/llama-4-scout-17b-16e-instruct
// Site-wide optional model env: GROQ_LLM_MODEL or SCRIPT_LLM_MODEL
// Model list: https://console.groq.com/docs/models
//
// Built-in GenerateImage / expert embeddings use Studio’s separate image-and-embedding configuration (not GROQ_API_KEY); see plugin docs.
// Native CMS tools: this sample returns **session-bundle** keys (`toolsLoopChatPreferMaxCompletionTokens`, `toolsLoopChatMaxCompletionOutTokens`,
// `toolsLoopChatMaxWirePayloadChars`) so orchestration can emit `max_completion_tokens` and shrink oversized tool-loop JSON (vendor-neutral; see
// **StudioAiLlmKind** and docs/using-and-extending/script-llm-bring-your-own-backend.md). This script optionally reads
// `GROQ_TOOLS_LOOP_MAX_COMPLETION_TOKENS` / `GROQ_TOOLS_LOOP_MAX_WIRE_CHARS` and maps them into that bundle (core does not read those env vars).

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.DefaultChatClientBuilder
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi

import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmRuntime
import plugins.org.craftercms.aiassistant.llm.StudioAiRuntimeBuildRequest
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools

/** Groq-backed script LLM: Spring AI chat client for Groq. Spring AI is vendor-neutral; OpenAi* classes are from spring-ai-openai (HTTP contract naming, not OpenAI-only). */
class GroqScriptLlmRuntime implements StudioAiLlmRuntime {

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(GroqScriptLlmRuntime.class)

  /** Groq’s documented default base URL for the chat-completions JSON wire (see Groq console docs). */
  private static final String GROQ_DOCUMENTED_CHAT_API_BASE = 'https://api.groq.com/openai'

  private final String scriptLlmId

  GroqScriptLlmRuntime(String scriptLlmId) {
    this.scriptLlmId = (scriptLlmId ?: 'groq').toString()
  }

  @Override
  String normalizedKind() {
    return StudioAiLlmKind.SCRIPT_LLM_PREFIX + scriptLlmId
  }

  @Override
  boolean supportsNativeStudioTools() {
    return true
  }

  private static String compatBaseUrl() {
    String u = System.getenv('GROQ_OPENAI_COMPAT_BASE_URL')?.toString()?.trim()
    if (!u) {
      u = System.getenv('SCRIPT_LLM_OPENAI_COMPAT_BASE_URL')?.toString()?.trim()
    }
    if (!u) {
      u = GROQ_DOCUMENTED_CHAT_API_BASE
    }
    return u.replaceAll(/\/+$/, '')
  }

  private static String compatApiKey(StudioAiRuntimeBuildRequest req) {
    String k = System.getenv('GROQ_API_KEY')?.toString()?.trim()
    if (!k) {
      k = System.getenv('SCRIPT_LLM_API_KEY')?.toString()?.trim()
    }
    if (!k) {
      k = (req.openAiApiKeyFromRequest ?: '').toString().trim()
    }
    return k
  }

  /** Groq chat model id from agent/request, then optional site env (`openAiModelParam` is the Studio request field name). */
  private static String compatModelId(StudioAiRuntimeBuildRequest req) {
    String m = (req.openAiModelParam ?: '').toString().trim()
    if (m) {
      return m
    }
    m = System.getenv('GROQ_LLM_MODEL')?.toString()?.trim()
    if (m) {
      return m
    }
    m = System.getenv('SCRIPT_LLM_MODEL')?.toString()?.trim()
    if (m) {
      return m
    }
    return ''
  }

  /**
   * Groq-only sample: chat + tools-loop wire must not target OpenAI Inc.’s chat API host by mis-set env.
   * (Spring type names say {@code OpenAi*}; the HTTP host is still whatever {@code base} is — we enforce “not api.openai.com”.)
   */
  private static void refuseIfChatBaseIsOpenAiApiHost(String base) {
    try {
      String host = new java.net.URI((base ?: '').trim()).host
      if (host && host.equalsIgnoreCase('api.openai.com')) {
        throw new IllegalStateException(
          'Script LLM groq: chat base URL is api.openai.com — this sample is Groq-only. Use default or set GROQ_OPENAI_COMPAT_BASE_URL to Groq (e.g. https://api.groq.com/openai).'
        )
      }
    } catch (IllegalStateException e) {
      throw e
    } catch (Throwable ignored) {
      // malformed URL: other checks below surface a useful error
    }
  }

  @Override
  Map buildSessionBundle(StudioAiRuntimeBuildRequest req) {
    String base = compatBaseUrl()
    String apiKey = compatApiKey(req)
    if (!base?.toString()?.trim()) {
      throw new IllegalStateException(
        'Script LLM groq: tools-loop chat base URL ended up empty after normalization — check GROQ_OPENAI_COMPAT_BASE_URL / SCRIPT_LLM_OPENAI_COMPAT_BASE_URL (host only, no trailing /v1).'
      )
    }
    refuseIfChatBaseIsOpenAiApiHost(base)
    if (!apiKey) {
      throw new IllegalStateException(
        'Script LLM groq: set GROQ_API_KEY (or SCRIPT_LLM_API_KEY), or testing-only per-agent <openAiApiKey>. Use a **gsk_** Groq key — a non-Groq vendor key in that slot returns HTTP 401 from Groq.'
      )
    }
    if (base.contains('groq.com') && !apiKey.startsWith('gsk_')) {
      throw new IllegalStateException(
        "Script LLM groq: API key must be a Groq Console key (starts with gsk_). If you put another vendor’s secret in <openAiApiKey>, Groq returns 401 — use GROQ_API_KEY on the Studio host or a gsk_ value for local testing."
      )
    }
    String modelName = compatModelId(req)
    if (!modelName) {
      throw new IllegalStateException(
        'Script LLM groq: set a Groq chat model id on the agent (<llmModel>) or POST llmModel, or set env GROQ_LLM_MODEL / SCRIPT_LLM_MODEL. Groq deprecates model ids — see https://console.groq.com/docs/models'
      )
    }
    def orch = req.orchestration
    def imageModel = AiOrchestration.imageModelFromRequestOrNull(req.imageModelParam)
    // Not Groq chat: Studio’s configured key/host for built-in image + embedding tools (often OpenAI — see plugin image docs).
    String builtInImageAndEmbeddingKey = AiOrchestration.resolveOpenAiApiKey(null)
    def tools
    if (req.enableTools) {
      def expertSpecs = orch.readExpertSkillSpecsFromRequest()
      tools = AiOrchestrationTools.build(
        req.toolResultConverter,
        req.studioOps,
        req.toolProgressListener,
        builtInImageAndEmbeddingKey,
        imageModel,
        req.fullSuppressRepoWrites,
        req.protectedFormItemPath,
        expertSpecs,
        modelName,
        req.llmNormalized,
        req.imageGeneratorParam,
        req.agentEnabledBuiltInTools
      )
    } else {
      tools = []
    }
    // Spring AI type names say "OpenAi"; this client points at **Groq** (`base`) with a **Groq** `modelName`.
    def groqChatWireClient = OpenAiApi.builder().baseUrl(base).apiKey(apiKey).build()
    def options = OpenAiChatOptions.builder()
      .model(modelName)
      .internalToolExecutionEnabled(req.enableTools)
      .build()
    def chatModel = OpenAiChatModel.builder()
      .openAiApi(groqChatWireClient)
      .defaultOptions(options)
      .build()
    def chatClient = new DefaultChatClientBuilder(chatModel).build()
    LOG.debug(
      'Script LLM groq: model={} enableTools={} wireBaseUrl={} apiKeyPreview={} apiKeyChars={}',
      modelName,
      req.enableTools,
      base,
      AiOrchestration.openAiApiKeyLogPreview(apiKey),
      apiKey.length()
    )
    int maxCompOut = 8192
    try {
      String e = System.getenv('GROQ_TOOLS_LOOP_MAX_COMPLETION_TOKENS')?.toString()?.trim()
      if (e) {
        maxCompOut = Math.max(1, Integer.parseInt(e))
      }
    } catch (Throwable ignored) {}
    int maxWireChars = 56_000
    try {
      String ew = System.getenv('GROQ_TOOLS_LOOP_MAX_WIRE_CHARS')?.toString()?.trim()
      if (ew) {
        maxWireChars = Math.max(18_000, Integer.parseInt(ew))
      }
    } catch (Throwable ignored) {}
    return [
      chatClient              : chatClient,
      chatModel               : chatModel,
      tools                   : tools,
      llm                     : normalizedKind(),
      useTools                : req.enableTools,
      studioOps               : req.studioOps,
      toolsLoopChatApiKey     : apiKey,
      toolsLoopChatBaseUrl    : base,
      resolvedChatModel       : modelName,
      nativeToolTransport     : 'toolsLoopWire',
      toolsLoopChatPreferMaxCompletionTokens: true,
      toolsLoopChatMaxCompletionOutTokens   : maxCompOut,
      toolsLoopChatMaxWirePayloadChars      : maxWireChars
    ]
  }
}

new GroqScriptLlmRuntime(llmId as String)
