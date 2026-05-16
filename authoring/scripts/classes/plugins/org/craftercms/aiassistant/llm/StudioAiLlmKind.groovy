package plugins.org.craftercms.aiassistant.llm

import java.util.Locale
import java.util.Map
import java.util.regex.Pattern

/**
 * Normalized LLM <strong>transport</strong> identifiers for the Studio AI Assistant plugin (this codebase).
 * <p>
 * Missing, blank, or unrecognized {@code llm} values are rejected by {@link #normalize(String)} with
 * {@link IllegalArgumentException} (HTTP 400 on stream/chat). Authors must configure a provider ({@link #OPENAI_NATIVE},
 * Claude, script LLM, etc.) — there is no remote hosted default.
 * The built-in <strong>{@link #OPENAI_NATIVE}</strong> row is the <strong>OpenAI vendor</strong>; <strong>xAI</strong>,
 * <strong>deepSeek</strong>, <strong>llama</strong>, and <strong>gemini</strong> are <strong>other vendors</strong> that share the same
 * <strong>tools-loop</strong> {@code /v1/chat/completions} <strong>RestClient</strong> path in {@code AiOrchestration}.
 * <strong>Claude</strong> uses Spring AI Anthropic with Spring-managed tool execution.
 * Site-authored backends use {@link #SCRIPT_LLM_PREFIX} via Groovy under {@code /scripts/aiassistant/llm/{id}/}.
 * </p>
 */
final class StudioAiLlmKind {

  private StudioAiLlmKind() {}

  /** Bundle: API key for the tools-loop chat host (any vendor). */
  static final String BUNDLE_TOOLS_LOOP_CHAT_API_KEY = 'toolsLoopChatApiKey'

  /** Bundle: when {@code true}, tools-loop / simple-completion JSON uses {@code max_completion_tokens} instead of {@code max_tokens} (script/vendor choice). */
  static final String BUNDLE_TOOLS_LOOP_CHAT_PREFER_MAX_COMPLETION_TOKENS = 'toolsLoopChatPreferMaxCompletionTokens'

  /** Bundle: optional positive int — caps completion budget for tools-loop + simple-completion toward this wire host. */
  static final String BUNDLE_TOOLS_LOOP_CHAT_MAX_COMPLETION_OUT_TOKENS = 'toolsLoopChatMaxCompletionOutTokens'

  /**
   * Bundle: optional non-negative int — when {@code > 0}, serialized tools-loop request JSON (messages + tools) is shrunk in-place
   * before each POST until under this char budget (helps TPM / payload limits on strict hosts). {@code 0} = disabled.
   */
  static final String BUNDLE_TOOLS_LOOP_CHAT_MAX_WIRE_PAYLOAD_CHARS = 'toolsLoopChatMaxWirePayloadChars'

  /** Bundle: host-only base URL for tools-loop chat (no trailing {@code /v1}). */
  static final String BUNDLE_TOOLS_LOOP_CHAT_BASE_URL = 'toolsLoopChatBaseUrl'

  /** Bundle: {@code nativeToolTransport} value selecting the tools-loop RestClient path. */
  static final String NATIVE_TRANSPORT_TOOLS_LOOP_WIRE = 'toolsLoopWire'

  /** Spring AI OpenAI ChatModel + RestClient native-tool loop (CMS tools on the wire). */
  static final String OPENAI_NATIVE = 'openAI'

  /** OpenAI vendor + other LLMs on the same tools-loop REST surface; see {@link StudioAiProviderCredentials}. */
  static final String XAI_NATIVE = 'xAI'

  static final String DEEPSEEK_NATIVE = 'deepSeek'
  static final String LLAMA_NATIVE = 'llama'
  /** Google Generative Language tools-loop endpoint; {@code genesis} is an accepted alias in {@link #normalize}. */
  static final String GEMINI_NATIVE = 'gemini'

  /** Spring AI Anthropic (Claude); tools via Spring {@code ChatClient}, not the OpenAI RestClient loop. */
  static final String CLAUDE_NATIVE = 'claude'

  /**
   * Normalized id for site Groovy LLM under {@code /scripts/aiassistant/llm/{id}/}. Agent {@code <llm>} uses
   * {@code script:yourId} → normalized {@code scriptLlm:yourId}.
   */
  static final String SCRIPT_LLM_PREFIX = 'scriptLlm:'

  private static final Pattern SAFE_SCRIPT_LLM_ID = Pattern.compile('^[a-z0-9_-]{1,64}$')

  static boolean __KEEP_IS_OPENAI_NATIVE__(String normalizedKind) {
    return OPENAI_NATIVE == (normalizedKind ?: '').toString()
  }

  static boolean isScriptHostedLlm(String normalizedKind) {
    return (normalizedKind ?: '').toString().startsWith(SCRIPT_LLM_PREFIX)
  }

  /** Lowercase id segment after {@link #SCRIPT_LLM_PREFIX}; empty if not a script LLM token. */
  static String scriptLlmIdFromNormalized(String normalizedKind) {
    String s = (normalizedKind ?: '').toString()
    if (!s.startsWith(SCRIPT_LLM_PREFIX)) {
      return ''
    }
    return s.substring(SCRIPT_LLM_PREFIX.length()).trim().toLowerCase(Locale.US)
  }

  /** Built-in kinds that use the tools-loop RestClient path (no script bundle inspection). */
  static boolean useToolsLoopChatRestClientBuiltInKinds(String normalizedKind) {
    String n = (normalizedKind ?: '').toString()
    return OPENAI_NATIVE == n || XAI_NATIVE == n || DEEPSEEK_NATIVE == n || LLAMA_NATIVE == n || GEMINI_NATIVE == n
  }

  static String toolsLoopChatApiKeyFromBundle(Map bundle) {
    if (bundle == null) {
      return ''
    }
    return (bundle.get(BUNDLE_TOOLS_LOOP_CHAT_API_KEY) ?: '').toString().trim()
  }

  static String toolsLoopChatBaseUrlFromBundle(Map bundle) {
    if (bundle == null) {
      return ''
    }
    return (bundle.get(BUNDLE_TOOLS_LOOP_CHAT_BASE_URL) ?: '').toString().trim()
  }

  /** Script/vendor: prefer {@code max_completion_tokens} on {@code /v1/chat/completions} for this session. */
  static boolean toolsLoopChatPreferMaxCompletionTokensFromBundle(Map bundle) {
    if (bundle == null) {
      return false
    }
    Object v = bundle.get(BUNDLE_TOOLS_LOOP_CHAT_PREFER_MAX_COMPLETION_TOKENS)
    if (v == null) {
      return false
    }
    if (v instanceof Boolean) {
      return ((Boolean) v).booleanValue()
    }
    return Boolean.parseBoolean(v.toString().trim())
  }

  /** Script/vendor: optional positive cap on completion output tokens for tools-loop + simple wire completions. */
  static Integer toolsLoopChatMaxCompletionOutTokensFromBundle(Map bundle) {
    if (bundle == null) {
      return null
    }
    Object v = bundle.get(BUNDLE_TOOLS_LOOP_CHAT_MAX_COMPLETION_OUT_TOKENS)
    if (v == null) {
      return null
    }
    try {
      if (v instanceof Number) {
        int n = ((Number) v).intValue()
        return n > 0 ? n : null
      }
      int n = Integer.parseInt(v.toString().trim())
      return n > 0 ? n : null
    } catch (Throwable ignored) {
      return null
    }
  }

  /** Script/vendor: optional char budget for tools-loop POST JSON; {@code 0} = do not shrink. */
  static int toolsLoopChatMaxWirePayloadCharsFromBundle(Map bundle) {
    if (bundle == null) {
      return 0
    }
    Object v = bundle.get(BUNDLE_TOOLS_LOOP_CHAT_MAX_WIRE_PAYLOAD_CHARS)
    if (v == null) {
      return 0
    }
    try {
      if (v instanceof Number) {
        return Math.max(0, ((Number) v).intValue())
      }
      return Math.max(0, Integer.parseInt(v.toString().trim()))
    } catch (Throwable ignored) {
      return 0
    }
  }

  static boolean nativeToolTransportIsToolsLoopWire(String transportToken) {
    String t = (transportToken ?: '').toString().trim()
    if (!t) {
      return false
    }
    return NATIVE_TRANSPORT_TOOLS_LOOP_WIRE.equalsIgnoreCase(t)
  }

  /**
   * Tools-loop RestClient native tool loop (not Anthropic). When {@code springAiBundle} is the map from
   * {@code StudioAiLlmRuntime#buildSessionBundle}, script-hosted sessions may set {@code nativeToolTransport} to
   * {@link #NATIVE_TRANSPORT_TOOLS_LOOP_WIRE} or supply {@link #BUNDLE_TOOLS_LOOP_CHAT_BASE_URL} + {@code resolvedChatModel}
   * to opt into the same path.
   * Optional wire tuning (vendor-agnostic): {@link #BUNDLE_TOOLS_LOOP_CHAT_PREFER_MAX_COMPLETION_TOKENS},
   * {@link #BUNDLE_TOOLS_LOOP_CHAT_MAX_COMPLETION_OUT_TOKENS}, {@link #BUNDLE_TOOLS_LOOP_CHAT_MAX_WIRE_PAYLOAD_CHARS}.
   */
  static boolean useToolsLoopChatRestClient(String normalizedKind, Map springAiBundle = null) {
    if (springAiBundle != null) {
      String t = springAiBundle.get('nativeToolTransport')?.toString()?.trim()
      if (nativeToolTransportIsToolsLoopWire(t)) {
        return true
      }
      if (isScriptHostedLlm((normalizedKind ?: '').toString())) {
        String w = toolsLoopChatBaseUrlFromBundle(springAiBundle)
        String rm = springAiBundle.get('resolvedChatModel')?.toString()?.trim()
        if (w && rm) {
          return true
        }
      }
    }
    return useToolsLoopChatRestClientBuiltInKinds(normalizedKind)
  }

  static boolean isAnthropicClaude(String normalizedKind, Map springAiBundle = null) {
    if (springAiBundle != null) {
      String t = springAiBundle.get('nativeToolTransport')?.toString()?.trim()
      if (t && 'anthropic'.equalsIgnoreCase(t)) {
        return true
      }
    }
    return CLAUDE_NATIVE == (normalizedKind ?: '').toString()
  }

  /** Autonomous worker: built-in tools-loop kinds or site script LLM (script must return tools-loop bundle fields for headless tools). */
  static boolean supportsAutonomousNativeTools(String normalizedKind) {
    return useToolsLoopChatRestClientBuiltInKinds(normalizedKind) || isScriptHostedLlm(normalizedKind)
  }

  /**
   * Maps agent / POST {@code llm} strings to a normalized kind. Empty or blank throws {@link IllegalArgumentException}.
   * Unrecognized values and invalid {@code script:…} ids throw.
   * Obsolete hosted remote-chat spellings ({@code aiassistant}, {@code hostedchat}, …) and plugin-path strings ({@code ai-assistant}, …) throw with a targeted message.
   */
  static String normalize(String raw) {
    String trimmed = (raw ?: '').toString().trim()
    if (!trimmed) {
      throw new IllegalArgumentException(
        'Missing or blank llm: set <llm> on the agent in /config/studio/ui.xml (or include llm on the stream/chat POST body), e.g. openAI, claude, deepSeek, gemini, xAI, llama, script:myid.'
      )
    }
    String s = trimmed.toLowerCase(Locale.US)
    if (s == 'ai-assistant' || s == 'ai_assistant' || s == 'ai assistant') {
      throw new IllegalArgumentException(
        "llm='${trimmed}' is invalid: that string names the Studio AI Assistant plugin or form control, not an LLM provider. Use openAI, claude, xAI, deepSeek, llama, gemini, or script:<id>."
      )
    }
    if (s == 'aiassistant' || s == 'hostedchat' || s == 'hosted-chat' || s == new String([99, 114, 97, 102, 116, 101, 114, 113] as char[]) || s == new String([99, 114, 97, 102, 116, 101, 114, 45, 113] as char[])) {
      throw new IllegalArgumentException(
        "llm='${trimmed}' is no longer supported (hosted remote chat was removed). Configure an LLM provider such as openAI, claude, xAI, deepSeek, llama, gemini, or script:<id>."
      )
    }
    if (s.startsWith('script:')) {
      String id = s.substring('script:'.length()).trim()
      if (SAFE_SCRIPT_LLM_ID.matcher(id).matches()) {
        return SCRIPT_LLM_PREFIX + id
      }
      throw new IllegalArgumentException(
        "Invalid script LLM id in llm='${trimmed}': use script:<id> with id matching [a-z0-9_-]{1,64}."
      )
    }
    if (s == 'openai' || s == 'open-ai') {
      return OPENAI_NATIVE
    }
    if (s == 'xai' || s == 'x-ai' || s == 'grok') {
      return XAI_NATIVE
    }
    if (s == 'deepseek' || s == 'deep-seek') {
      return DEEPSEEK_NATIVE
    }
    if (s == 'llama' || s == 'ollama' || s == 'meta-llama' || s == 'meta_llama') {
      return LLAMA_NATIVE
    }
    if (s == 'gemini' || s == 'genesis' || s == 'google' || s == 'google-genai' || s == 'google_genai') {
      return GEMINI_NATIVE
    }
    if (s == 'claude' || s == 'anthropic') {
      return CLAUDE_NATIVE
    }
    throw new IllegalArgumentException(
      "Unrecognized llm='${trimmed}'. Supported: openAI, xAI, deepSeek, llama, gemini, genesis, claude, script:<id>."
    )
  }
}
