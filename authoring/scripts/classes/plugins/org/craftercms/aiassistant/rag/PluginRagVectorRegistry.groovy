package plugins.org.craftercms.aiassistant.rag

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

@Grab(group='org.springframework.ai', module='spring-ai-core', version='1.0.0-M6', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-openai', version='1.0.0-M6', initClass=false)

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HashSet
import java.util.Locale
import java.util.Set
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel

/**
 * Lazy-loaded RAG index over bundled AI Assistant instruction corpus: load persisted embeddings from the site's Studio
 * configuration repo via {@link StudioToolOperations}, or rebuild (embed + write) when missing/stale.
 * <p>
 * JVM {@code aiassistant.pluginRag.mode}: {@code off} (default), {@code supplement} (full instructions + retrieved appendix),
 * {@code replace} (compact kernel + retrieved appendix). Opt-in only.</p>
 */
class PluginRagVectorRegistry {

  private static final Logger log = LoggerFactory.getLogger(PluginRagVectorRegistry.class)

  /** Same module/path convention as browser {@code get_configuration} with module {@code studio}. */
  private static final String STUDIO_CONFIG_REL_PATH = '/plugins/org/craftercms/aiassistant/aiassistant-plugin-rag-index.json'

  private static final int FORMAT_VERSION = 1

  private static final ConcurrentHashMap<String, Object> SITE_LOCKS = new ConcurrentHashMap<>()
  /** siteId -> compiled index (chunks + embeddings), or null sentinel handled inside load */
  private static final ConcurrentHashMap<String, List<RagChunk>> SITE_INDEX = new ConcurrentHashMap<>()

  static final class RagChunk {
    final String text
    final float[] embedding

    RagChunk(String text, float[] embedding) {
      this.text = text
      this.embedding = embedding
    }
  }

  static String pluginRagMode() {
    (System.getProperty('aiassistant.pluginRag.mode') ?: 'off').toString().trim().toLowerCase(Locale.US)
  }

  static boolean pluginRagModeActive() {
    def m = pluginRagMode()
    return m == 'supplement' || m == 'replace'
  }

  /** Compact kernel for {@code replace} mode — leading slice of full authoring instructions (classpath overrides apply). */
  static String ragKernelFromAuthoringInstructions() {
    String full = ToolPrompts.getLlm_AUTHORING_INSTRUCTIONS()
    int max = resolveKernelMaxChars()
    if (full.length() <= max) {
      return full
    }
    return full.substring(0, max) + '\n\n[Kernel ends — follow every retrieved chunk in "## Retrieved AI Assistant plugin reference" as part of system policy.]'
  }

  private static int resolveKernelMaxChars() {
    try {
      def p = System.getProperty('aiassistant.pluginRag.kernelMaxChars')?.toString()?.trim()
      if (p) {
        int n = Integer.parseInt(p)
        return Math.min(16_000, Math.max(1024, n))
      }
    } catch (Throwable ignored) {}
    return 5200
  }

  /**
   * When native tools are on, optionally shrink or augment {@code authoringCore} (full {@link ToolPrompts#getLlm_AUTHORING_INSTRUCTIONS()}).
   */
  static String adjustAuthoringCore(
    String authoringCore,
    String siteId,
    String userText,
    StudioToolOperations ops,
    String llmApiKey,
    boolean toolSchemasOnApi
  ) {
    if (!toolSchemasOnApi) {
      return authoringCore
    }
    def mode = pluginRagMode()
    if (mode == 'off') {
      return authoringCore
    }
    def site = (siteId ?: '').toString().trim()
    def key = (llmApiKey ?: '').toString().trim()
    if (!site || !ops || !key) {
      return authoringCore
    }
    try {
      def appendix = buildRetrievalAppendix(site, userText, ops, key)
      if (mode == 'supplement') {
        return appendix ? (authoringCore + '\n\n' + appendix) : authoringCore
      }
      if (mode == 'replace') {
        if (!appendix?.trim()) {
          log.warn('Plugin RAG replace mode: no retrieval appendix; falling back to full authoring instructions')
          return authoringCore
        }
        return ragKernelFromAuthoringInstructions() + '\n\n' + appendix
      }
    } catch (Throwable t) {
      log.warn('Plugin RAG adjustAuthoringCore failed (using full core): {}', t.message)
    }
    return authoringCore
  }

  private static String buildRetrievalAppendix(String siteId, String userText, StudioToolOperations ops, String apiKey) {
    List<RagChunk> idx = getOrBuildIndex(siteId, ops, apiKey)
    if (idx == null || idx.isEmpty()) {
      return ''
    }
    EmbeddingModel embeddingModel = ExpertSkillVectorRegistry.buildEmbeddingModel(apiKey)
    List<String> queries = retrievalQueries(userText)
    List<float[]> queryVecs = []
    for (String q : queries) {
      try {
        queryVecs.add(embeddingModel.embed(q))
      } catch (Throwable t) {
        log.debug('Plugin RAG query embed skip: {}', t.message)
      }
    }
    if (queryVecs.isEmpty()) {
      return ''
    }
    int topK = resolveTopK()
    int maxChars = resolveMaxAppendChars()
    List<ScoredChunk> scored = []
    for (RagChunk ch : idx) {
      float best = -1f
      for (float[] qv : queryVecs) {
        float s = cosineSimilarity(ch.embedding, qv)
        if (s > best) {
          best = s
        }
      }
      scored.add(new ScoredChunk(ch.text, best))
    }
    scored.sort { a, b -> Float.compare(b.score, a.score) }
    StringBuilder sb = new StringBuilder()
    sb.append('## Retrieved AI Assistant plugin reference (similarity-ranked; apply together with kernel/full policy)\n')
    int used = 0
    int n = 0
    Set<String> seen = new HashSet<>()
    for (ScoredChunk sc : scored) {
      if (n >= topK) {
        break
      }
      String t = sc.text?.toString() ?: ''
      if (!t.trim()) {
        continue
      }
      String fp = fingerprint(t)
      if (!seen.add(fp)) {
        continue
      }
      String block = "\n### [${n + 1}] (score=${String.format(Locale.US, '%.3f', sc.score)})\n${t}\n"
      if (used + block.length() > maxChars) {
        break
      }
      sb.append(block)
      used += block.length()
      n++
    }
    return sb.toString().trim()
  }

  private static final class ScoredChunk {
    final String text
    final float score

    ScoredChunk(String text, float score) {
      this.text = text
      this.score = score
    }
  }

  private static String fingerprint(String t) {
    try {
      MessageDigest md = MessageDigest.getInstance('SHA-256')
      byte[] d = md.digest(t.getBytes(StandardCharsets.UTF_8))
      StringBuilder h = new StringBuilder()
      for (int i = 0; i < 8; i++) {
        h.append(String.format('%02x', d[i] & 0xff))
      }
      return h.toString()
    } catch (Throwable e) {
      return Integer.toString(t.hashCode())
    }
  }

  private static List<String> retrievalQueries(String userText) {
    String u = (userText ?: '').toString().trim()
    List<String> q = new ArrayList<>()
    if (u) {
      q.add(u.length() > 2000 ? u.substring(0, 2000) : u)
    }
    q.add('WriteContent GetContent XML page component content type form definition CDATA')
    q.add('ListContentDependencyScope TranslateContentBatch TranslateContentItem translate localize full page pathChunks sections_o referenced components')
    q.add('FreeMarker template update_template content vs code analyze_template hardcoded')
    q.add('revert_change publish_content GetPreviewHtml ListContentDependencyScope')
    q.add('GetCrafterizingPlaybook crafterize expert skill QueryExpertGuidance')
    return q
  }

  private static int resolveTopK() {
    try {
      def p = System.getProperty('aiassistant.pluginRag.topK')?.toString()?.trim()
      if (p) {
        return Math.min(24, Math.max(1, Integer.parseInt(p)))
      }
    } catch (Throwable ignored) {}
    return 8
  }

  private static int resolveMaxAppendChars() {
    try {
      def p = System.getProperty('aiassistant.pluginRag.maxAppendChars')?.toString()?.trim()
      if (p) {
        return Math.min(80_000, Math.max(2000, Integer.parseInt(p)))
      }
    } catch (Throwable ignored) {}
    return 14_000
  }

  private static float cosineSimilarity(float[] a, float[] b) {
    if (a == null || b == null || a.length != b.length || a.length == 0) {
      return -1f
    }
    double dot = 0
    double na = 0
    double nb = 0
    for (int i = 0; i < a.length; i++) {
      float x = a[i]
      float y = b[i]
      dot += x * y
      na += x * x
      nb += y * y
    }
    double denom = Math.sqrt(na) * Math.sqrt(nb)
    return denom > 1e-12 ? (float) (dot / denom) : 0f
  }

  static List<RagChunk> getOrBuildIndex(String siteId, StudioToolOperations ops, String apiKey) {
    def site = (siteId ?: '').toString().trim()
    if (!site || !ops || !apiKey?.trim()) {
      return []
    }
    List<RagChunk> cached = SITE_INDEX.get(site)
    if (cached != null) {
      return cached
    }
    Object lk = SITE_LOCKS.computeIfAbsent(site, { k -> new Object() })
    synchronized (lk) {
      cached = SITE_INDEX.get(site)
      if (cached != null) {
        return cached
      }
      String corpus = buildCorpusText()
      String corpusSha = sha256Utf8(corpus)
      String pluginBuild = resolvePluginBuildId()
      String embeddingModelName = ExpertSkillVectorRegistry.resolveEmbeddingModelName()
      Map persisted = readPersistedWrapper(ops, site)
      List<RagChunk> loaded = tryLoadFromPersisted(persisted, corpusSha, pluginBuild, embeddingModelName)
      if (loaded != null && !loaded.isEmpty()) {
        SITE_INDEX.put(site, loaded)
        log.info('Plugin RAG index loaded from repo siteId={} chunks={}', site, loaded.size())
        return loaded
      }
      log.info('Plugin RAG index rebuild start siteId={} corpusSha={} model={}', site, corpusSha, embeddingModelName)
      List<RagChunk> built = buildIndexFromCorpus(corpus, apiKey)
      if (built.isEmpty()) {
        SITE_INDEX.put(site, built)
        return built
      }
      try {
        byte[] jsonBytes = serializeWrapper(corpusSha, pluginBuild, embeddingModelName, built).getBytes(StandardCharsets.UTF_8)
        ops.writeStudioConfiguration(site, STUDIO_CONFIG_REL_PATH, jsonBytes)
        log.info('Plugin RAG index persisted siteId={} bytes={} chunks={}', site, jsonBytes.length, built.size())
      } catch (Throwable t) {
        log.warn('Plugin RAG persist failed (in-memory only): {}', t.message)
      }
      SITE_INDEX.put(site, built)
      return built
    }
  }

  private static Map readPersistedWrapper(StudioToolOperations ops, String siteId) {
    try {
      String raw = ops.readStudioConfigurationUtf8(siteId, STUDIO_CONFIG_REL_PATH)
      if (!raw?.trim()) {
        return null
      }
      def slurper = new JsonSlurper()
      Object parsed = slurper.parseText(raw)
      return parsed instanceof Map ? (Map) parsed : null
    } catch (Throwable t) {
      log.debug('Plugin RAG read persisted failed: {}', t.message)
      return null
    }
  }

  private static List<RagChunk> tryLoadFromPersisted(Map wrapper, String corpusSha, String pluginBuild, String embeddingModelName) {
    if (wrapper == null) {
      return null
    }
    try {
      int fv = intVal(wrapper.get('formatVersion'), -1)
      if (fv != FORMAT_VERSION) {
        return null
      }
      if (!corpusSha.equals(wrapper.get('corpusSha256')?.toString())) {
        return null
      }
      if (!pluginBuild.equals(wrapper.get('pluginBuildId')?.toString())) {
        return null
      }
      if (!embeddingModelName.equals(wrapper.get('embeddingModel')?.toString())) {
        return null
      }
      String b64 = wrapper.get('payloadGzipBase64')?.toString()
      if (!b64?.trim()) {
        return null
      }
      byte[] gz = Base64.getDecoder().decode(b64.trim())
      ByteArrayOutputStream bout = new ByteArrayOutputStream(Math.max(gz.length * 2, 8192))
      new GZIPInputStream(new ByteArrayInputStream(gz)).withStream { InputStream gzin ->
        byte[] buf = new byte[8192]
        int n
        while ((n = gzin.read(buf)) != -1) {
          bout.write(buf, 0, n)
        }
      }
      def inner = new JsonSlurper().parseText(new String(bout.toByteArray(), StandardCharsets.UTF_8))
      if (!(inner instanceof Map)) {
        return null
      }
      Object chunksObj = ((Map) inner).get('chunks')
      if (!(chunksObj instanceof List)) {
        return null
      }
      List<RagChunk> out = new ArrayList<>()
      for (Object row : (List) chunksObj) {
        if (!(row instanceof Map)) {
          continue
        }
        Map m = (Map) row
        String text = m.get('text')?.toString()
        Object emb = m.get('embedding')
        float[] vec = listToFloatArray(emb)
        if (text?.trim() && vec != null && vec.length > 0) {
          out.add(new RagChunk(text, vec))
        }
      }
      return out.isEmpty() ? null : out
    } catch (Throwable t) {
      log.debug('Plugin RAG deserialize failed: {}', t.message)
      return null
    }
  }

  private static int intVal(Object o, int dflt) {
    try {
      if (o instanceof Number) {
        return ((Number) o).intValue()
      }
      if (o != null) {
        return Integer.parseInt(o.toString().trim())
      }
    } catch (Throwable ignored) {}
    return dflt
  }

  private static float[] listToFloatArray(Object emb) {
    if (!(emb instanceof List)) {
      return null
    }
    List list = (List) emb
    float[] a = new float[list.size()]
    for (int i = 0; i < list.size(); i++) {
      Object x = list.get(i)
      if (!(x instanceof Number)) {
        return null
      }
      a[i] = ((Number) x).floatValue()
    }
    return a
  }

  private static List<RagChunk> buildIndexFromCorpus(String corpus, String apiKey) {
    EmbeddingModel embeddingModel = ExpertSkillVectorRegistry.buildEmbeddingModel(apiKey)
    int maxChunkChars = 1800
    try {
      def pcc = System.getProperty('aiassistant.pluginRag.maxChunkChars')?.toString()?.trim()
      if (pcc) {
        maxChunkChars = Math.min(8000, Math.max(512, Integer.parseInt(pcc)))
      }
    } catch (Throwable ignored) {}
    List<String> texts = ExpertSkillVectorRegistry.chunkMarkdown(corpus, maxChunkChars)
    int maxChunks = 400
    try {
      def pc = System.getProperty('aiassistant.pluginRag.maxChunks')?.toString()?.trim()
      if (pc) {
        maxChunks = Math.min(2000, Math.max(8, Integer.parseInt(pc)))
      }
    } catch (Throwable ignored) {}
    if (texts.size() > maxChunks) {
      log.warn('Plugin RAG truncating chunks {} -> {}', texts.size(), maxChunks)
      texts = texts.subList(0, maxChunks)
    }
    List<RagChunk> out = new ArrayList<>()
    int batchSize = 64
    try {
      def pb = System.getProperty('aiassistant.pluginRag.embedBatchSize')?.toString()?.trim()
      if (pb) {
        batchSize = Math.min(128, Math.max(8, Integer.parseInt(pb)))
      }
    } catch (Throwable ignored) {}
    for (int i = 0; i < texts.size(); i += batchSize) {
      int hi = Math.min(i + batchSize, texts.size())
      List<String> sub = texts.subList(i, hi)
      List vecs = embeddingModel.embed(sub)
      if (vecs == null || vecs.size() != sub.size()) {
        throw new IllegalStateException('Embedding batch size mismatch')
      }
      for (int j = 0; j < sub.size(); j++) {
        Object v = vecs.get(j)
        if (!(v instanceof float[])) {
          throw new IllegalStateException('Unexpected embedding type: ' + (v?.getClass()?.name))
        }
        out.add(new RagChunk(sub.get(j), (float[]) v))
      }
    }
    return out
  }

  private static String serializeWrapper(String corpusSha, String pluginBuild, String embeddingModelName, List<RagChunk> chunks) {
    List<Map> rows = new ArrayList<>()
    int dims = 0
    for (RagChunk ch : chunks) {
      if (ch.embedding != null && ch.embedding.length > 0) {
        dims = ch.embedding.length
      }
      List<Float> embList = new ArrayList<>(ch.embedding.length)
      for (float f : ch.embedding) {
        embList.add(f)
      }
      rows.add([
        text     : ch.text,
        embedding: embList
      ])
    }
    String innerJson = JsonOutput.toJson([chunks: rows])
    byte[] innerBytes = innerJson.getBytes(StandardCharsets.UTF_8)
    ByteArrayOutputStream gzOut = new ByteArrayOutputStream(Math.max(innerBytes.length / 2, 4096))
    GZIPOutputStream z = new GZIPOutputStream(gzOut)
    try {
      z.write(innerBytes)
    } finally {
      z.close()
    }
    String b64 = Base64.getEncoder().encodeToString(gzOut.toByteArray())
    Map outer = [
      formatVersion : FORMAT_VERSION,
      corpusSha256  : corpusSha,
      pluginBuildId : pluginBuild,
      embeddingModel: embeddingModelName,
      dimensions    : dims,
      chunkCount    : chunks.size(),
      payloadGzipBase64: b64
    ]
    return JsonOutput.toJson(outer)
  }

  static String buildCorpusText() {
    StringBuilder sb = new StringBuilder(256_000)
    appendSection(sb, 'GENERAL_LLM_AUTHORING_INSTRUCTIONS', ToolPrompts.getLlm_AUTHORING_INSTRUCTIONS())
    appendSection(sb, 'GENERAL_LLM_USER_MESSAGE_TOOLS_POLICY_PREFIX', ToolPrompts.getLlm_USER_MESSAGE_TOOLS_POLICY_PREFIX())
    appendSection(sb, 'GENERAL_LLM_CHAT_ONLY_SYSTEM', ToolPrompts.getLlm_CHAT_ONLY_SYSTEM())
    appendSection(sb, 'GENERAL_LLM_FORM_ENGINE_SUPPRESS_REPO_WRITES', ToolPrompts.getLlm_FORM_ENGINE_SUPPRESS_REPO_WRITES())
    appendSection(sb, 'GENERAL_XML_REPAIR_REMINDER_AFTER_BAD_READ', ToolPrompts.getXML_REPAIR_REMINDER_AFTER_BAD_READ())
    appendSection(sb, 'CMS_CONTENT_UPDATE_CONTENT', ToolPrompts.getUPDATE_CONTENT())
    appendSection(sb, 'CMS_CONTENT_UPDATE_CONTENT_FORM_ENGINE', ToolPrompts.getUPDATE_CONTENT_FORM_ENGINE())
    appendSection(sb, 'CMS_DEVELOPMENT_ANALYZE_TEMPLATE', ToolPrompts.getANALYZE_TEMPLATE())
    appendSection(sb, 'CMS_DEVELOPMENT_UPDATE_TEMPLATE', ToolPrompts.getUPDATE_TEMPLATE())
    appendSection(sb, 'CMS_DEVELOPMENT_UPDATE_TEMPLATE_FORM_ENGINE', ToolPrompts.getUPDATE_TEMPLATE_FORM_ENGINE())
    appendSection(sb, 'CMS_DEVELOPMENT_UPDATE_CONTENT_TYPE', ToolPrompts.getUPDATE_CONTENT_TYPE())
    appendSection(sb, 'CMS_DEVELOPMENT_UPDATE_CONTENT_TYPE_FORM_ENGINE', ToolPrompts.getUPDATE_CONTENT_TYPE_FORM_ENGINE())
    appendSection(sb, 'CMS_CONTENT_DESC_GET_CONTENT', ToolPrompts.getDESC_GET_CONTENT())
    appendSection(sb, 'CMS_CONTENT_DESC_LIST_CONTENT_DEPENDENCY_SCOPE', ToolPrompts.getDESC_LIST_CONTENT_DEPENDENCY_SCOPE())
    appendSection(sb, 'CMS_DEVELOPMENT_DESC_GET_CONTENT_TYPE_FORM_DEFINITION', ToolPrompts.getDESC_GET_CONTENT_TYPE_FORM_DEFINITION())
    appendSection(sb, 'CMS_CONTENT_DESC_WRITE_CONTENT', ToolPrompts.getDESC_WRITE_CONTENT())
    appendSection(sb, 'CMS_CONTENT_DESC_TRANSFORM_CONTENT_SUBGRAPH', ToolPrompts.getDESC_TRANSFORM_CONTENT_SUBGRAPH())
    sb.toString()
  }

  private static void appendSection(StringBuilder sb, String title, String body) {
    sb.append('\n\n=== ').append(title).append(" ===\n\n")
    sb.append((body ?: '').toString())
  }

  private static String sha256Utf8(String s) {
    MessageDigest md = MessageDigest.getInstance('SHA-256')
    byte[] d = md.digest((s ?: '').getBytes(StandardCharsets.UTF_8))
    StringBuilder hex = new StringBuilder(d.length * 2)
    for (byte b : d) {
      hex.append(String.format('%02x', b & 0xff))
    }
    return hex.toString()
  }

  static String resolvePluginBuildId() {
    try {
      InputStream is = PluginRagVectorRegistry.class.getClassLoader().getResourceAsStream('craftercms-plugin.yaml')
      if (is != null) {
        try {
          String yaml = slurpStreamUtf8(is)
          def m = (yaml =~ /(?m)^\s+major:\s*(\d+)\s*$/)
          def m2 = (yaml =~ /(?m)^\s+minor:\s*(\d+)\s*$/)
          def m3 = (yaml =~ /(?m)^\s+patch:\s*(\d+)\s*$/)
          if (m.find() && m2.find() && m3.find()) {
            return "${m.group(1)}.${m2.group(1)}.${m3.group(1)}"
          }
        } finally {
          try {
            is.close()
          } catch (Throwable ignored) {}
        }
      }
    } catch (Throwable ignored) {}
    try {
      def p = System.getProperty('aiassistant.pluginRag.pluginBuildId')?.toString()?.trim()
      if (p) {
        return p
      }
    } catch (Throwable ignored) {}
    return 'unknown'
  }

  private static String slurpStreamUtf8(InputStream is) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    byte[] buf = new byte[8192]
    int n
    while ((n = is.read(buf)) != -1) {
      baos.write(buf, 0, n)
    }
    return new String(baos.toByteArray(), StandardCharsets.UTF_8)
  }
}
