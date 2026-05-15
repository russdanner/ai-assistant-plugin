package plugins.org.craftercms.aiassistant.llm

import org.springframework.ai.openai.api.common.OpenAiApiConstants

import java.util.Locale

/**
 * API keys, default models, and tools-loop {@link org.springframework.ai.openai.api.OpenAiApi} base URLs
 * for non-OpenAI {@link StudioAiLlmKind} values. RestClient + {@code OpenAiApi} append {@code /v1/chat/completions}
 * to the base URL — bases here must <strong>not</strong> include a trailing {@code /v1}.
 */
final class StudioAiProviderCredentials {

  private StudioAiProviderCredentials() {}

  /** Spring {@link OpenAiApi} + native RestClient tools loop: host-only style base (no trailing {@code /v1}). */
  static String wireOpenAiRestBaseUrl(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().replaceAll(/\/+$/, '')
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return firstNonBlank(
        System.getenv('XAI_OPENAI_BASE_URL'),
        System.getProperty('crafter.xai.openAiBaseUrl'),
        'https://api.x.ai'
      )
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return firstNonBlank(
        System.getenv('DEEPSEEK_OPENAI_BASE_URL'),
        System.getProperty('crafter.deepseek.openAiBaseUrl'),
        'https://api.deepseek.com'
      )
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return firstNonBlank(
        System.getenv('LLAMA_OPENAI_BASE_URL'),
        System.getenv('OLLAMA_OPENAI_BASE_URL'),
        System.getProperty('crafter.llama.openAiBaseUrl'),
        'http://127.0.0.1:11434'
      )
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return firstNonBlank(
        System.getenv('GEMINI_OPENAI_BASE_URL'),
        System.getenv('GOOGLE_GENAI_OPENAI_BASE_URL'),
        System.getProperty('crafter.gemini.openAiBaseUrl'),
        'https://generativelanguage.googleapis.com/v1beta/openai'
      )
    }
    return (OpenAiApiConstants.DEFAULT_BASE_URL ?: 'https://api.openai.com').toString().replaceAll(/\/+$/, '')
  }

  /**
   * Absolute URL for {@link java.net.HttpURLConnection} simple completions (must match
   * {@link org.springframework.ai.openai.api.OpenAiApi} path rules for the same provider).
   */
  static String httpChatCompletionsUrl(String llmNormalized) {
    String b = wireOpenAiRestBaseUrl(llmNormalized).replaceAll(/\/+$/, '')
    if (b.endsWith('/v1')) {
      return b + '/chat/completions'
    }
    return b + '/v1/chat/completions'
  }

  /**
   * Absolute POST URL for OpenAI Images-compatible {@code /v1/images/generations}.
   * Defaults to the same host family as {@link #wireOpenAiRestBaseUrl}{@code (OPENAI_NATIVE)}; override with
   * {@code OPENAI_IMAGES_OPENAI_BASE_URL} or JVM {@code crafter.openai.imagesOpenAiBaseUrl} when using a compatible proxy.
   */
  static String httpOpenAiImagesGenerationsUrl() {
    String b = firstNonBlank(
      System.getenv('OPENAI_IMAGES_OPENAI_BASE_URL'),
      System.getProperty('crafter.openai.imagesOpenAiBaseUrl'),
      wireOpenAiRestBaseUrl(StudioAiLlmKind.OPENAI_NATIVE)
    )
    b = b.replaceAll(/\/+$/, '')
    if (b.endsWith('/v1')) {
      return b + '/images/generations'
    }
    return b + '/v1/images/generations'
  }

  static String resolveApiKey(String llmNormalized, String fromWidgetOrRequest = null) {
    String n = (llmNormalized ?: '').toString()
    String w = (fromWidgetOrRequest ?: '').toString().trim()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return resolveOpenAiStyleKey(
        'OPENAI_API_KEY',
        'crafter.openai.apiKey',
        'OPENAI_API_KEY',
        w
      )
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return resolveOpenAiStyleKey(
        'XAI_API_KEY',
        'crafter.xai.apiKey',
        'XAI_API_KEY',
        w
      )
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return resolveOpenAiStyleKey(
        'DEEPSEEK_API_KEY',
        'crafter.deepseek.apiKey',
        'DEEPSEEK_API_KEY',
        w
      )
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      // Ollama often accepts any non-empty placeholder; still allow env for hosted tools-loop Llama endpoints.
      String k = resolveOpenAiStyleKey(
        'LLAMA_API_KEY',
        'crafter.llama.apiKey',
        'LLAMA_API_KEY',
        w
      )
      return k ?: 'ollama'
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return resolveOpenAiStyleKey(
        'GEMINI_API_KEY',
        'crafter.gemini.apiKey',
        'GOOGLE_API_KEY',
        w
      ) ?: resolveOpenAiStyleKey(
        'GOOGLE_API_KEY',
        'crafter.google.apiKey',
        'GOOGLE_API_KEY',
        ''
      )
    }
    return ''
  }

  static String apiKeyResolutionSourceForLog(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return openAiStyleSource('OPENAI_API_KEY', 'crafter.openai.apiKey', 'OPENAI_API_KEY')
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return openAiStyleSource('XAI_API_KEY', 'crafter.xai.apiKey', 'XAI_API_KEY')
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return openAiStyleSource('DEEPSEEK_API_KEY', 'crafter.deepseek.apiKey', 'DEEPSEEK_API_KEY')
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return openAiStyleSource('LLAMA_API_KEY', 'crafter.llama.apiKey', 'LLAMA_API_KEY')
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      if (System.getenv('GEMINI_API_KEY')?.toString()?.trim()) return 'GEMINI_API_KEY(env)'
      if (System.getProperty('crafter.gemini.apiKey')?.trim()) return 'crafter.gemini.apiKey(jvm)'
      if (System.getenv('GOOGLE_API_KEY')?.toString()?.trim()) return 'GOOGLE_API_KEY(env)'
      if (System.getProperty('crafter.google.apiKey')?.trim()) return 'crafter.google.apiKey(jvm)'
      return 'widget-or-request'
    }
    return 'unknown'
  }

  /**
   * True when the resolved key equals the widget value and no server-side env/JVM key was set for that provider
   * (mirrors the OpenAI-only warning logic, extended per provider).
   */
  static boolean isLikelyWidgetOnlyServerKeyMissing(String llmNormalized, String resolvedApiKey, Object widgetRaw) {
    String apiKey = (resolvedApiKey ?: '').toString().trim()
    String w = (widgetRaw ?: '').toString().trim()
    if (!apiKey || apiKey != w) {
      return false
    }
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      return !System.getenv('OPENAI_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.openai.apiKey')?.trim() &&
        !System.getProperty('OPENAI_API_KEY')?.trim()
    }
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return !System.getenv('XAI_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.xai.apiKey')?.trim()
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return !System.getenv('DEEPSEEK_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.deepseek.apiKey')?.trim()
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return !System.getenv('LLAMA_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.llama.apiKey')?.trim()
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return !System.getenv('GEMINI_API_KEY')?.toString()?.trim() &&
        !System.getenv('GOOGLE_API_KEY')?.toString()?.trim() &&
        !System.getProperty('crafter.gemini.apiKey')?.trim() &&
        !System.getProperty('crafter.google.apiKey')?.trim()
    }
    return false
  }

  static String missingApiKeyMessage(String llmNormalized) {
    String n = (llmNormalized ?: '').toString()
    if (StudioAiLlmKind.XAI_NATIVE == n) {
      return 'LLM is xAI but no API key was found. Set XAI_API_KEY or JVM crafter.xai.apiKey on Studio. For local testing only, optional agent <openAiApiKey> in ui.xml (see docs/using-and-extending/llm-configuration.md).'
    }
    if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
      return 'LLM is DeepSeek but no API key was found. Set DEEPSEEK_API_KEY or JVM crafter.deepseek.apiKey on Studio. For local testing only, optional agent <openAiApiKey> in ui.xml.'
    }
    if (StudioAiLlmKind.LLAMA_NATIVE == n) {
      return 'LLM is llama (tools-loop host) but no key was resolved. Set LLAMA_API_KEY / crafter.llama.apiKey for hosted endpoints, or rely on the Ollama default placeholder when the server does not require a secret.'
    }
    if (StudioAiLlmKind.GEMINI_NATIVE == n) {
      return 'LLM is gemini (Google tools-loop endpoint) but no API key was found. Set GEMINI_API_KEY or GOOGLE_API_KEY (or JVM crafter.gemini.apiKey / crafter.google.apiKey).'
    }
    return 'No API key was found for this LLM provider.'
  }

  /**
   * Chat model id for {@code /v1/chat/completions}. When {@code fromRequestOrAgent} is blank, uses JVM defaults per provider.
   */
  static String resolveChatModelId(String llmNormalized, String fromRequestOrAgent) {
    String n = (llmNormalized ?: '').toString()
    String raw = (fromRequestOrAgent ?: '').toString().trim()
    if (StudioAiLlmKind.OPENAI_NATIVE == n) {
      if (!raw) {
        raw = (System.getProperty('crafter.openai.model') ?: '').toString().trim()
      }
      if (!raw) {
        throw new IllegalStateException(
          'The OpenAI chat model is not configured properly. Set the agent LLM / llmModel in Studio (for example ui.xml), pass llmModel on the chat request, or set JVM property crafter.openai.model to a valid OpenAI chat model id.'
        )
      }
      return plugins.org.craftercms.aiassistant.orchestration.AiOrchestration.openAiCanonicalizeApiModelToken(raw)
    }
    if (!raw) {
      if (StudioAiLlmKind.XAI_NATIVE == n) {
        raw = (System.getProperty('crafter.xai.model') ?: 'grok-2-latest').toString().trim()
      } else if (StudioAiLlmKind.DEEPSEEK_NATIVE == n) {
        raw = (System.getProperty('crafter.deepseek.model') ?: 'deepseek-chat').toString().trim()
      } else if (StudioAiLlmKind.LLAMA_NATIVE == n) {
        raw = (System.getProperty('crafter.llama.model') ?: 'llama3.2').toString().trim()
      } else if (StudioAiLlmKind.GEMINI_NATIVE == n) {
        raw = (System.getProperty('crafter.gemini.model') ?: 'gemini-2.0-flash').toString().trim()
      }
    }
    if (!raw) {
      throw new IllegalStateException(
        "The chat model is not configured for llm='${n}'. Set <llmModel> on the agent or pass llmModel on the request (or JVM crafter.*.model for this provider)."
      )
    }
    return plugins.org.craftercms.aiassistant.orchestration.AiOrchestration.openAiCanonicalizeApiModelToken(raw)
  }

  static String resolveAnthropicApiKey(String fromWidgetOrRequest = null) {
    resolveOpenAiStyleKey(
      'ANTHROPIC_API_KEY',
      'crafter.anthropic.apiKey',
      'ANTHROPIC_API_KEY',
      (fromWidgetOrRequest ?: '').toString().trim()
    )
  }

  static String anthropicApiKeySourceForLog() {
    openAiStyleSource('ANTHROPIC_API_KEY', 'crafter.anthropic.apiKey', 'ANTHROPIC_API_KEY')
  }

  static String resolveAnthropicChatModel(String fromRequestOrAgent) {
    String raw = (fromRequestOrAgent ?: '').toString().trim()
    if (!raw) {
      raw = (System.getProperty('crafter.anthropic.model') ?: 'claude-3-5-sonnet-20241022').toString().trim()
    }
    if (!raw) {
      throw new IllegalStateException('The Claude model is not configured. Set agent llmModel or JVM crafter.anthropic.model.')
    }
    return plugins.org.craftercms.aiassistant.orchestration.AiOrchestration.openAiCanonicalizeApiModelToken(raw)
  }

  private static String firstNonBlank(String... vals) {
    for (String v : vals) {
      if (v != null && v.toString().trim()) {
        return v.toString().trim().replaceAll(/\/+$/, '')
      }
    }
    return ''
  }

  private static String resolveOpenAiStyleKey(String envName, String jvmPrimary, String jvmAlt, String widget) {
    def e = System.getenv(envName)
    if (e?.toString()?.trim()) return e.toString().trim()
    def p = System.getProperty(jvmPrimary)
    if (p?.trim()) return p.trim()
    p = System.getProperty(jvmAlt)
    if (p?.trim()) return p.trim()
    return (widget ?: '').toString().trim()
  }

  private static String openAiStyleSource(String envName, String jvmPrimary, String jvmAlt) {
    if (System.getenv(envName)?.toString()?.trim()) return "${envName}(env)"
    if (System.getProperty(jvmPrimary)?.trim()) return "${jvmPrimary}(jvm)"
    if (System.getProperty(jvmAlt)?.trim()) return "${jvmAlt}(jvm)"
    return 'widget-or-request'
  }
}
