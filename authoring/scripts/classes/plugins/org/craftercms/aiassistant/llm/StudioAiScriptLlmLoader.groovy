package plugins.org.craftercms.aiassistant.llm

import groovy.lang.Binding
import groovy.lang.GroovyShell
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.security.MessageDigest
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.List
import java.util.Locale
import java.util.Set
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Loads site-authored Groovy from {@code /scripts/aiassistant/llm/{llmId}/runtime.groovy} (or {@code llm.groovy})
 * under Studio module {@code studio} — same tree as {@code config/studio/scripts/aiassistant/llm/{llmId}/} in the site repo.
 * <p>
 * The script must evaluate to either:
 * <ul>
 *   <li>An instance of {@link StudioAiLlmRuntime}, or</li>
 *   <li>A {@link Map} with {@code supportsNativeStudioTools} (boolean), optional {@code normalizedKind} (string),
 *       and {@code buildSessionBundle} — a one-arg {@link groovy.lang.Closure} taking {@link StudioAiRuntimeBuildRequest}
 *       and returning the same map shape as {@link OpenAiSpringAiLlmRuntime#buildSessionBundle} (or Anthropic equivalent).</li>
 * </ul>
 * </p>
 */
final class StudioAiScriptLlmLoader {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiScriptLlmLoader.class)

  /** Studio {@code configurationService} path prefix (under {@code config/studio/}). */
  static final String LLM_DIR_PREFIX = '/scripts/aiassistant/llm/'

  private static final Pattern SAFE_LLM_ID = Pattern.compile('^[a-z0-9_-]{1,64}$')

  private static final List<String> ENTRY_FILES = ['runtime.groovy', 'llm.groovy'] as List

  private static final ConcurrentHashMap<String, CachedDelegate> CACHE = new ConcurrentHashMap<>()

  private StudioAiScriptLlmLoader() {}

  private static final class CachedDelegate {
    final String contentSha256
    final StudioAiLlmRuntime delegate

    CachedDelegate(String contentSha256, StudioAiLlmRuntime delegate) {
      this.contentSha256 = contentSha256
      this.delegate = delegate
    }
  }

  static boolean isSafeLlmId(String llmId) {
    String id = (llmId ?: '').toString().trim().toLowerCase(Locale.US)
    return SAFE_LLM_ID.matcher(id).matches()
  }

  /**
   * Returns a cached {@link StudioAiLlmRuntime} for the script at {@code llmId} when source hash matches;
   * otherwise recompiles.
   */
  static StudioAiLlmRuntime loadDelegateRuntime(StudioToolOperations ops, String llmId) {
    String id = (llmId ?: '').toString().trim().toLowerCase(Locale.US)
    if (!SAFE_LLM_ID.matcher(id).matches()) {
      throw new IllegalStateException("Invalid script LLM id '${llmId}' (use lowercase letters, digits, '_', '-', max 64).")
    }
    if (ops == null) {
      throw new IllegalStateException('StudioToolOperations is required to load script LLM.')
    }
    String siteId = ops.resolveEffectiveSiteId('')
    String src = null
    String usedPath = null
    for (String fn : ENTRY_FILES) {
      String rel = "${LLM_DIR_PREFIX}${id}/${fn}"
      String body = ops.readStudioConfigurationUtf8(siteId, rel)
      if (body?.trim()) {
        src = body.toString()
        usedPath = rel
        break
      }
    }
    if (!src?.trim()) {
      throw new IllegalStateException(
        "Script LLM '${id}': no Groovy entry file under ${LLM_DIR_PREFIX}${id}/ (tried ${ENTRY_FILES.join(', ')})."
      )
    }
    String sha = sha256Hex(src.bytes)
    String cacheKey = "${siteId}|${id}"
    CachedDelegate hit = CACHE.get(cacheKey)
    if (hit != null && sha == hit.contentSha256) {
      return hit.delegate
    }
    StudioAiLlmRuntime delegate = compileDelegate(ops, siteId, id, usedPath, src)
    CACHE.put(cacheKey, new CachedDelegate(sha, delegate))
    return delegate
  }

  private static String sha256Hex(byte[] bytes) {
    MessageDigest md = MessageDigest.getInstance('SHA-256')
    byte[] d = md.digest(bytes)
    StringBuilder sb = new StringBuilder(d.length * 2)
    for (byte b : d) {
      sb.append(String.format(Locale.US, '%02x', b))
    }
    return sb.toString()
  }

  /**
   * Groovy import resolution must see both this plugin's classes and (for advanced scripts) types from the Studio
   * webapp such as Spring AI. Using only the servlet TCCL can hide the plugin; using only the plugin CL can hide
   * Spring AI. Parent = plugin; {@code findClass} falls back to TCCL then Spring context loaders.
   */
  private static ClassLoader scriptLlmCompilerParent(StudioToolOperations ops) {
    ClassLoader pluginCl = StudioAiScriptLlmLoader.class.getClassLoader()
    Set<ClassLoader> extras = new LinkedHashSet<>()
    ClassLoader tccl = Thread.currentThread().getContextClassLoader()
    if (tccl != null) {
      extras.add(tccl)
    }
    try {
      Object ctx = ops?.studioApplicationContext()
      ClassLoader ctxCl = ctx?.getClassLoader()
      if (ctxCl != null) {
        extras.add(ctxCl)
      }
    } catch (Throwable ignored) {
    }
    extras.remove(pluginCl)
    if (extras.isEmpty()) {
      return pluginCl
    }
    final List<ClassLoader> extraList = new ArrayList<>(extras)
    return new ClassLoader(pluginCl) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        ClassNotFoundException last = null
        for (ClassLoader cl : extraList) {
          try {
            return cl.loadClass(name)
          } catch (ClassNotFoundException e) {
            last = e
          }
        }
        if (last != null) {
          throw last
        }
        throw new ClassNotFoundException(name)
      }
    }
  }

  private static StudioAiLlmRuntime compileDelegate(StudioToolOperations ops, String siteId, String llmId, String scriptPath, String src) {
    ClassLoader parent = scriptLlmCompilerParent(ops)
    Binding binding = new Binding()
    binding.setVariable('log', LOG)
    binding.setVariable('llmId', llmId)
    binding.setVariable('siteId', siteId)
    binding.setVariable('scriptPath', scriptPath)
    Object evaluated
    try {
      evaluated = new GroovyShell(parent, binding).evaluate(src.toString())
    } catch (Throwable t) {
      LOG.warn('StudioAiScriptLlmLoader: script failed siteId={} path={}: {}', siteId, scriptPath, t.toString())
      throw new IllegalStateException("Script LLM '${llmId}' failed to compile or run: ${t.message ?: t.class.simpleName}", t)
    }
    if (evaluated instanceof StudioAiLlmRuntime) {
      return (StudioAiLlmRuntime) evaluated
    }
    if (evaluated instanceof Map) {
      Map m = (Map) evaluated
      Object cl = m.get('buildSessionBundle')
      if (!(cl instanceof Closure)) {
        throw new IllegalStateException(
          "Script LLM '${llmId}': Map result must include a 'buildSessionBundle' Closure (one StudioAiRuntimeBuildRequest arg)."
        )
      }
      boolean sup = false
      Object st = m.get('supportsNativeStudioTools')
      if (st instanceof Boolean) {
        sup = (Boolean) st
      } else if (st != null) {
        sup = Boolean.parseBoolean(st.toString())
      }
      String nk = (m.get('normalizedKind') ?: '')?.toString()?.trim()
      if (!nk) {
        nk = StudioAiLlmKind.SCRIPT_LLM_PREFIX + llmId
      }
      return new StudioAiMapBackedScriptLlmRuntime(nk, sup, (Closure) cl)
    }
    throw new IllegalStateException(
      "Script LLM '${llmId}' must evaluate to a StudioAiLlmRuntime instance or a Map with buildSessionBundle (Closure); got ${evaluated?.class?.name}"
    )
  }

  static void clearCacheForTests() {
    CACHE.clear()
  }
}
