package plugins.org.craftercms.aiassistant.imagegen

import groovy.lang.Binding
import groovy.lang.Closure
import groovy.lang.GroovyShell
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.security.MessageDigest
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.Set
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Site Groovy backends for {@link StudioAiImageGenerator} under
 * {@code /scripts/aiassistant/imagegen/{id}/generate.groovy} (same tree as
 * {@code config/studio/scripts/aiassistant/imagegen/{id}/generate.groovy} in the site repo).
 * <p>
 * The script must evaluate to a {@link groovy.lang.Closure} {@code (Map input, Map context) -> Map} or to an object
 * with a {@code generate(Map input, Map context)} method. Binding variables: {@code log}, {@code siteId},
 * {@code imageGenId}, {@code scriptPath}, {@code studio} ({@link StudioToolOperations}).
 * </p>
 */
final class StudioAiScriptImageGenLoader {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiScriptImageGenLoader.class)

  static final String IMAGEGEN_DIR_PREFIX = '/scripts/aiassistant/imagegen/'

  private static final Pattern SAFE_ID = Pattern.compile('^[a-z0-9_-]{1,64}$')

  private static final ConcurrentHashMap<String, CachedGen> CACHE = new ConcurrentHashMap<>()

  private StudioAiScriptImageGenLoader() {}

  private static final class CachedGen {
    final String contentSha256
    final Closure closure

    CachedGen(String contentSha256, Closure closure) {
      this.contentSha256 = contentSha256
      this.closure = closure
    }
  }

  static boolean isSafeId(String id) {
    String s = (id ?: '').toString().trim().toLowerCase(Locale.US)
    return SAFE_ID.matcher(s).matches()
  }

  static Closure loadGenerateClosure(StudioToolOperations ops, String imageGenId) {
    String id = (imageGenId ?: '').toString().trim().toLowerCase(Locale.US)
    if (!SAFE_ID.matcher(id).matches()) {
      throw new IllegalStateException("Invalid script image generator id '${imageGenId}' (use lowercase letters, digits, '_', '-', max 64).")
    }
    if (ops == null) {
      throw new IllegalStateException('StudioToolOperations is required to load script image generator.')
    }
    String siteId = ops.resolveEffectiveSiteId('')
    String rel = "${IMAGEGEN_DIR_PREFIX}${id}/generate.groovy"
    String src = ops.readStudioConfigurationUtf8(siteId, rel)
    if (!src?.trim()) {
      throw new IllegalStateException("Script image generator '${id}': missing ${rel}")
    }
    String sha = sha256Hex(src.bytes)
    String cacheKey = "${siteId}|${id}"
    CachedGen hit = CACHE.get(cacheKey)
    if (hit != null && sha == hit.contentSha256) {
      return hit.closure
    }
    Closure cl = compileClosure(ops, siteId, id, rel, src.toString())
    CACHE.put(cacheKey, new CachedGen(sha, cl))
    return cl
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

  private static ClassLoader scriptImageGenCompilerParent(StudioToolOperations ops) {
    ClassLoader pluginCl = StudioAiScriptImageGenLoader.class.getClassLoader()
    Set<ClassLoader> extras = new LinkedHashSet<>()
    ClassLoader tccl = Thread.currentThread().getContextClassLoader()
    if (tccl != null) {
      extras.add(tccl)
    }
    try {
      Object ctx = ops?.crafterqStudioApplicationContext()
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

  private static Closure compileClosure(StudioToolOperations ops, String siteId, String imageGenId, String scriptPath, String src) {
    ClassLoader parent = scriptImageGenCompilerParent(ops)
    Binding binding = new Binding()
    binding.setVariable('log', LOG)
    binding.setVariable('imageGenId', imageGenId)
    binding.setVariable('siteId', siteId)
    binding.setVariable('scriptPath', scriptPath)
    binding.setVariable('studio', ops)
    Object evaluated
    try {
      evaluated = new GroovyShell(parent, binding).evaluate(src.toString())
    } catch (Throwable t) {
      LOG.warn('StudioAiScriptImageGenLoader: script failed siteId={} path={}: {}', siteId, scriptPath, t.toString())
      throw new IllegalStateException("Script image generator '${imageGenId}' failed to compile or run: ${t.message ?: t.class.simpleName}", t)
    }
    if (evaluated instanceof Closure) {
      return (Closure) evaluated
    }
    if (evaluated != null && evaluated.metaClass.respondsTo(evaluated, 'generate', Map, Map)) {
      Object target = evaluated
      return { Map a, Map c -> target.generate(a, c) } as Closure
    }
    throw new IllegalStateException(
      "Script image generator '${imageGenId}' must evaluate to a Closure (input, context) -> Map or an object with generate(Map,Map); got ${evaluated?.class?.name}"
    )
  }

  static void clearCacheForTests() {
    CACHE.clear()
  }
}
