package plugins.org.craftercms.aiassistant.config

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.Set

/**
 * Optional site project policy for built-in CMS tools: {@code config/studio/scripts/aiassistant/config/tools.json}
 * (Studio module path {@link #TOOLS_JSON_PATH}).
 * <p>
 * JSON shape (all keys optional):
 * <pre>{@code
 * {
 *   "disabledBuiltInTools": ["GenerateImage", "FetchHttpUrl"],
 *   "enabledBuiltInTools": ["GetContent", "WriteContent"],
 *   "mcpEnabled": true,
 *   "mcpServers": [
 *     { "id": "docs", "url": "https://mcp.example.com/mcp", "headers": { "Authorization": "Bearer ${env:GITHUB_MCP_TOKEN}" }, "readTimeoutMs": 120000 }
 *   ],
 *   "disabledMcpTools": ["mcp_docs_search"],
 *   "intentRecipeRouting": {
 *     "enabled": false,
 *     "engineEnabled": false,
 *     "engineMaxSteps": 8,
 *     "engineMaxTotalChars": 200000,
 *     "engineMaxFieldChars": 120000,
 *     "requestClarificationOnUnmatched": false,
 *     "minConfidence": 0.55,
 *     "customRecipesPath": "/scripts/aiassistant/config/intent-recipes.json"
 *   }
 * }
 * }</pre>
 * <strong>MCP client:</strong> {@code mcpServers} is ignored unless {@code mcpEnabled} is JSON boolean {@code true} (default is <strong>off</strong> when omitted).
 * When {@code enabledBuiltInTools} is a <strong>non-empty</strong> array, it acts as a <strong>whitelist</strong> of
 * built-in tool names to keep (site {@code InvokeSiteUserTool} is still added when {@code user-tools/registry.json}
 * has entries). <strong>MCP tools</strong> ({@code mcp_<serverId>_<toolName>}) and {@code InvokeSiteUserTool} are
 * <strong>not</strong> removed by that whitelist. When omitted or empty, all built-in tools ship minus any names listed in {@code disabledBuiltInTools}.
 * </p>
 */
final class StudioAiAssistantProjectConfig {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiAssistantProjectConfig.class)

  /** Studio {@code studio} module path (same prefix as other aiassistant site scripts). */
  static final String TOOLS_JSON_PATH = '/scripts/aiassistant/config/tools.json'

  private StudioAiAssistantProjectConfig() {}

  static Map load(StudioToolOperations ops) {
    if (ops == null) {
      return Collections.emptyMap()
    }
    String siteId = ops.resolveEffectiveSiteId('')
    String raw = null
    try {
      raw = ops.readStudioConfigurationUtf8(siteId, TOOLS_JSON_PATH)
    } catch (Throwable t) {
      LOG.debug('StudioAiAssistantProjectConfig: read failed siteId={}: {}', siteId, t.message)
      return Collections.emptyMap()
    }
    if (raw == null || !raw.toString().trim()) {
      return Collections.emptyMap()
    }
    try {
      Object parsed = new JsonSlurper().parseText(raw.toString().trim())
      if (parsed instanceof Map) {
        return (Map) parsed
      }
    } catch (Throwable t) {
      LOG.warn('StudioAiAssistantProjectConfig: invalid JSON at {} siteId={}: {}', TOOLS_JSON_PATH, siteId, t.message)
    }
    return Collections.emptyMap()
  }

  /** Non-empty whitelist of tool callback names to retain; {@code null} = use full built-in set minus disabled. */
  static Set<String> enabledBuiltInWhitelist(Map cfg) {
    if (!(cfg instanceof Map)) {
      return null
    }
    Object raw = cfg.get('enabledBuiltInTools')
    if (!(raw instanceof List) || ((List) raw).isEmpty()) {
      return null
    }
    Set<String> out = new LinkedHashSet<>()
    for (Object o : (List) raw) {
      String n = o != null ? o.toString().trim() : ''
      if (n) {
        out.add(n)
      }
    }
    return out.isEmpty() ? null : out
  }

  static Set<String> disabledBuiltInSet(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptySet()
    }
    Object raw = cfg.get('disabledBuiltInTools')
    if (!(raw instanceof List)) {
      return Collections.emptySet()
    }
    Set<String> out = new LinkedHashSet<>()
    for (Object o : (List) raw) {
      String n = o != null ? o.toString().trim() : ''
      if (n) {
        out.add(n.toLowerCase(Locale.ROOT))
      }
    }
    return out
  }

  static boolean isToolNameDisabled(String toolName, Set<String> disabledLower) {
    if (toolName == null || disabledLower == null || disabledLower.isEmpty()) {
      return false
    }
    return disabledLower.contains(toolName.toString().trim().toLowerCase(Locale.ROOT))
  }

  /**
   * When {@code true}, {@code mcpServers} in {@code tools.json} is processed. Omitted or any other value = MCP off.
   * <p>Site configuration only (not a JVM env var).</p>
   */
  static boolean mcpClientEnabled(Map cfg) {
    if (!(cfg instanceof Map)) {
      return false
    }
    return Boolean.TRUE.equals(cfg.get('mcpEnabled'))
  }

  /** Optional MCP Streamable HTTP servers from {@code mcpServers} on {@code tools.json} (only when {@link #mcpClientEnabled}). */
  static List<Map> mcpServers(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptyList()
    }
    Object raw = cfg.get('mcpServers')
    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }
    List<Map> out = new ArrayList<>()
    for (Object o : (List) raw) {
      if (o instanceof Map) {
        out.add((Map) o)
      }
    }
    return out
  }

  /** Lowercase wire names (e.g. {@code mcp_docs_search}) listed in {@code disabledMcpTools}. */
  static Set<String> disabledMcpToolsLower(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptySet()
    }
    Object raw = cfg.get('disabledMcpTools')
    if (!(raw instanceof List)) {
      return Collections.emptySet()
    }
    Set<String> out = new LinkedHashSet<>()
    for (Object o : (List) raw) {
      String n = o != null ? o.toString().trim().toLowerCase(Locale.ROOT) : ''
      if (n) {
        out.add(n)
      }
    }
    return out
  }

  static boolean isMcpWireToolDisabled(Set<String> disabledLower, String wireName) {
    if (wireName == null || disabledLower == null || disabledLower.isEmpty()) {
      return false
    }
    return disabledLower.contains(wireName.toString().trim().toLowerCase(Locale.ROOT))
  }

  /** Optional {@code intentRecipeRouting} object from {@code tools.json}. */
  static Map intentRecipeRoutingSection(Map cfg) {
    if (!(cfg instanceof Map)) {
      return Collections.emptyMap()
    }
    Object o = cfg.get('intentRecipeRouting')
    return o instanceof Map ? (Map) o : Collections.emptyMap()
  }

  static boolean intentRecipeRoutingEnabled(Map cfg) {
    Boolean.TRUE.equals(intentRecipeRoutingSection(cfg).get('enabled'))
  }

  static boolean intentRecipeRequestClarificationOnUnmatched(Map cfg) {
    Boolean.TRUE.equals(intentRecipeRoutingSection(cfg).get('requestClarificationOnUnmatched'))
  }

  static double intentRecipeMinConfidence(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    Object v = m.get('minConfidence')
    if (v == null) {
      return 0.55d
    }
    try {
      double conf
      if (v instanceof Number) {
        conf = ((Number) v).doubleValue()
      } else {
        conf = Double.parseDouble(v.toString().trim())
      }
      if (conf < 0.0d) {
        return 0.0d
      }
      if (conf > 1.0d) {
        return 1.0d
      }
      return conf
    } catch (Throwable ignored) {
      return 0.55d
    }
  }

  /** Optional Studio module path to site recipe JSON (merged over bundled defaults by recipe {@code id}). */
  static String intentRecipeCustomRecipesPath(Map cfg) {
    Map m = intentRecipeRoutingSection(cfg)
    String p = m.get('customRecipesPath')?.toString()?.trim()
    return p ?: ''
  }

  /**
   * When {@code intentRecipeRouting.enabled} is true and a recipe matches: run {@code engineSteps} on the Studio JVM
   * before the main tools loop (see {@code AuthoringIntentRecipeEngine}). Default {@code false}.
   */
  static boolean intentRecipeEngineEnabled(Map cfg) {
    Boolean.TRUE.equals(intentRecipeRoutingSection(cfg).get('engineEnabled'))
  }

  /** Max deterministic steps per matched recipe (clamped {@code 1–32}, default {@code 8}). */
  static int intentRecipeEngineMaxSteps(Map cfg) {
    return intentRecipeRoutingInt(cfg, 'engineMaxSteps', 8, 1, 32)
  }

  /** Max characters for the entire prefetch block appended to the user message (clamped {@code 8_192–400_000}, default {@code 200_000}). */
  static int intentRecipeEngineMaxTotalChars(Map cfg) {
    return intentRecipeRoutingInt(cfg, 'engineMaxTotalChars', 200_000, 8192, 400_000)
  }

  /** Max characters retained per tool payload field such as {@code contentXml} / {@code formDefinitionXml} (default {@code 120_000}). */
  static int intentRecipeEngineMaxFieldChars(Map cfg) {
    return intentRecipeRoutingInt(cfg, 'engineMaxFieldChars', 120_000, 4096, 500_000)
  }

  private static int intentRecipeRoutingInt(Map cfg, String key, int defaultValue, int min, int max) {
    Map m = intentRecipeRoutingSection(cfg)
    Object v = m.get(key)
    if (v == null) {
      return defaultValue
    }
    try {
      int n = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim())
      return Math.max(min, Math.min(max, n))
    } catch (Throwable ignored) {
      return defaultValue
    }
  }
}
