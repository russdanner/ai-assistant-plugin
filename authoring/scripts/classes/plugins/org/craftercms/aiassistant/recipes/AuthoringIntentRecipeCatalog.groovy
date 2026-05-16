package plugins.org.craftercms.aiassistant.recipes

import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map

/**
 * Loads bundled + optional site **authoring intent recipes** for the intent-router pass.
 * <p>Default file ships next to this class on the classpath; sites may override via
 * {@link StudioAiAssistantProjectConfig#intentRecipeCustomRecipesPath}.</p>
 */
final class AuthoringIntentRecipeCatalog {

  private static final Logger log = LoggerFactory.getLogger(AuthoringIntentRecipeCatalog.class)

  private AuthoringIntentRecipeCatalog() {}

  private static final String BUNDLED_RELATIVE = 'authoring-intent-recipes-default.json'

  /** Classpath path under {@code scripts/classes} when the file is deployed beside Groovy sources. */
  private static final String PACKAGE_RESOURCE_PREFIX = 'plugins/org/craftercms/aiassistant/recipes/'

  /** Optional JVM override: absolute path to bundled recipes JSON (hotfix without redeploy). */
  private static final String SYSPROP_BUNDLED_PATH = 'aiassistant.authoringIntentRecipesDefault.path'

  /**
   * Last-resort catalog when the JSON file is not on disk / classpath (marketplace copy often omits sibling files).
   * Keep in sync with {@link #BUNDLED_RELATIVE}.
   */
  private static final String BUNDLED_RECIPES_JSON_EMBEDDED = '''{
  "version": 1,
  "recipes": [
    {
      "id": "modify_page_content",
      "title": "Modify page or component content",
      "description": "Change copy, tone, grammar, translate, or field values on a page or component XML item; author does not ask for new FreeMarker, form-definition schema, or site-wide discovery only.",
      "matchHints": ["update", "change", "translate", "rewrite", "this page", "proofread", "grammar", "tone", "rephrase", "localize"],
      "phases": {
        "context": {
          "hints": [
            "Load the target item with GetContent (and GetContentTypeFormDefinition with contentPath when the form model matters).",
            "For full-page visible copy, consider ListContentTranslationScope from the page path before editing multiple items."
          ],
          "engineSteps": [
            { "tool": "GetContent", "args": { "siteId": "$siteId", "path": "$contentPath" } },
            { "tool": "GetContentTypeFormDefinition", "args": { "siteId": "$siteId", "contentPath": "$contentPath" } }
          ]
        },
        "action": ["Use update_content or GetContent → revise XML → WriteContent; preserve <page>/<component> structure and node-selector shapes."],
        "confirmation": ["When an Engine preview URL exists, use GetPreviewHtml after substantive writes affecting rendered output."]
      }
    },
    {
      "id": "template_display_change",
      "title": "Template / display (FTL) change",
      "description": "Author explicitly wants layout, FreeMarker, listing markup, dates formatting in code, or how the page renders.",
      "matchHints": ["template", "ftl", "freemarker", "render", "layout", "listing", "cards", "display"],
      "phases": {
        "context": ["GetContent on page/component XML; read display-template; follow sections_o keys to component templates when the shell is not the listing."],
        "action": ["Read templates with GetContent or analyze_template (read-only) before update_template; persist with WriteContent on .ftl paths."],
        "confirmation": ["GetPreviewHtml when preview URL is available."]
      }
    },
    {
      "id": "publish_item",
      "title": "Publish or go live",
      "description": "Author wants to publish the current item or a named path to live/staging.",
      "matchHints": ["publish", "go live", "deploy", "push to live", "release"],
      "phases": {
        "context": ["Confirm siteId and target path from Studio context or author text."],
        "action": ["Use publish_content with path/contentPath; avoid unnecessary discovery reads when context already has the item path."],
        "confirmation": ["Summarize publish package outcome from tool result."]
      }
    },
    {
      "id": "new_content_item",
      "title": "Create new page or component",
      "description": "Author asks to create, draft, or write a new item (new URL or new component), not only edit the open file.",
      "matchHints": ["create", "new page", "new article", "draft", "write a", "add a page"],
      "phases": {
        "context": {
          "hints": ["ListStudioContentTypes (siteId only) then exact catalog match; GetContentTypeFormDefinition for resolved contentTypeId; GetContent on one sibling of the same type when siblings exist."],
          "engineSteps": [{ "tool": "ListStudioContentTypes", "args": { "siteId": "$siteId", "searchable": false } }]
        },
        "action": ["WriteContent the new item with correct conventions (objectId, dates, file-name, sections)."],
        "confirmation": ["Tell the author how to preview the new route; optional GetPreviewHtml."]
      }
    }
  ]
}'''

  /**
   * @return immutable list of recipe maps (each may contain id, title, description, matchHints, phases)
   */
  static List<Map> loadRecipes(StudioToolOperations ops, Map projectCfg) {
    List<Map> merged = new ArrayList<>()
    Set<String> seen = new LinkedHashSet<>()
    for (Map r : parseBundledRecipes()) {
      String id = r?.id?.toString()?.trim()
      if (!id) {
        continue
      }
      merged.add(new LinkedHashMap<>(r))
      seen.add(id)
    }
    String sitePath = StudioAiAssistantProjectConfig.intentRecipeCustomRecipesPath(projectCfg)
    if (ops != null && sitePath?.trim()) {
      try {
        String siteId = ops.resolveEffectiveSiteId('')
        String raw = ops.readStudioConfigurationUtf8(siteId, sitePath.trim())
        if (raw?.trim()) {
          for (Map r : parseRecipesArrayFromJsonText(raw)) {
            String id = r?.id?.toString()?.trim()
            if (!id) {
              continue
            }
            if (seen.contains(id)) {
              for (int i = 0; i < merged.size(); i++) {
                if (id == merged.get(i)?.get('id')?.toString()?.trim()) {
                  merged.set(i, new LinkedHashMap<>(r))
                  break
                }
              }
            } else {
              merged.add(new LinkedHashMap<>(r))
              seen.add(id)
            }
          }
        }
      } catch (Throwable t) {
        log.warn('AuthoringIntentRecipeCatalog: site recipes read failed path={}: {}', sitePath, t.message)
      }
    }
    Collections.unmodifiableList(merged)
  }

  private static List<Map> parseBundledRecipes() {
    String raw = loadBundledRecipesJsonText()
    if (!raw?.trim()) {
      log.warn(
        'AuthoringIntentRecipeCatalog: missing bundled {} on disk/classpath — using embedded default catalog (deploy {} under config/studio/scripts/classes/{}/ or set JVM {} to override)',
        BUNDLED_RELATIVE,
        BUNDLED_RELATIVE,
        PACKAGE_RESOURCE_PREFIX,
        SYSPROP_BUNDLED_PATH
      )
      raw = BUNDLED_RECIPES_JSON_EMBEDDED
    }
    return parseRecipesArrayFromJsonText(raw)
  }

  /**
   * Studio loads Groovy from {@code config/studio/scripts/classes/…} on disk; JSON beside those sources is not
   * always visible to {@link Class#getResourceAsStream(String)}. Try peer resource, package classpath, then code-source directory.
   */
  private static String loadBundledRecipesJsonText() {
    String override = System.getProperty(SYSPROP_BUNDLED_PATH)?.toString()?.trim()
    if (override) {
      try {
        File f = new File(override)
        if (f.isFile()) {
          return f.getText('UTF-8')
        }
        log.warn('{} set but not a file: {}', SYSPROP_BUNDLED_PATH, override)
      } catch (Throwable t) {
        log.warn('Failed reading bundled recipes from {}: {}', override, t.message)
      }
    }

    String fromStream = readUtf8FromResourceStream(AuthoringIntentRecipeCatalog.class.getResourceAsStream(BUNDLED_RELATIVE))
    if (fromStream?.trim()) {
      return fromStream
    }

    ClassLoader cl = AuthoringIntentRecipeCatalog.class.classLoader
    String pkgPath = "${PACKAGE_RESOURCE_PREFIX}${BUNDLED_RELATIVE}"
    fromStream = readUtf8FromResourceStream(cl?.getResourceAsStream(pkgPath))
    if (fromStream?.trim()) {
      return fromStream
    }

    fromStream = readUtf8FromResourceStream(Thread.currentThread().contextClassLoader?.getResourceAsStream(pkgPath))
    if (fromStream?.trim()) {
      return fromStream
    }

    try {
      def loc = AuthoringIntentRecipeCatalog.class.protectionDomain?.codeSource?.location
      if (loc != null) {
        File base = new File(loc.toURI())
        if (base.isFile()) {
          base = base.parentFile
        }
        if (base != null && base.isDirectory()) {
          File candidate = new File(base, BUNDLED_RELATIVE)
          if (candidate.isFile()) {
            return candidate.getText('UTF-8')
          }
        }
      }
    } catch (Throwable t) {
      log.debug('AuthoringIntentRecipeCatalog: code-source directory load failed: {}', t.message)
    }

    return ''
  }

  private static String readUtf8FromResourceStream(InputStream is) {
    if (is == null) {
      return ''
    }
    try {
      return new String(is.readAllBytes(), StandardCharsets.UTF_8)
    } finally {
      try {
        is.close()
      } catch (Throwable ignored) {}
    }
  }

  static List<Map> parseRecipesArrayFromJsonText(String raw) {
    if (!raw?.trim()) {
      return []
    }
    try {
      Object root = new JsonSlurper().parseText(raw.trim())
      if (!(root instanceof Map)) {
        return []
      }
      Object arr = ((Map) root).get('recipes')
      if (!(arr instanceof List)) {
        return []
      }
      List<Map> out = []
      for (Object o : (List) arr) {
        if (o instanceof Map) {
          out.add((Map) o)
        }
      }
      return out
    } catch (Throwable t) {
      log.warn('AuthoringIntentRecipeCatalog: JSON parse failed: {}', t.message)
      return []
    }
  }

  /**
   * Compact markdown for the router model (ids + titles + short descriptions).
   */
  static String toRouterCatalogMarkdown(List<Map> recipes) {
    if (recipes == null || recipes.isEmpty()) {
      return '(no recipes configured)'
    }
    StringBuilder sb = new StringBuilder()
    sb.append('| recipeId | title | description (short) |\n')
    sb.append('|----------|-------|----------------------|\n')
    for (Map r : recipes) {
      String id = escMdCell(r?.id?.toString()?.trim() ?: '')
      String title = escMdCell(r?.title?.toString()?.trim() ?: '')
      String desc = escMdCell(trimDesc(r?.description?.toString() ?: '', 220))
      sb.append('| `').append(id).append('` | ').append(title).append(' | ').append(desc).append(" |\n")
    }
    sb.append('\nIf **none** of the rows fit, return `"recipeId": null`.')
    sb
  }

  private static String escMdCell(String s) {
    (s ?: '').replace('|', '/').replace('\n', ' ').trim()
  }

  private static String trimDesc(String s, int max) {
    String t = (s ?: '').replace('\n', ' ').trim()
    if (t.length() <= max) {
      return t
    }
    return t.substring(0, max) + '…'
  }

  static Map findRecipeById(List<Map> recipes, String id) {
    if (!id?.trim() || recipes == null) {
      return null
    }
    for (Map r : recipes) {
      if (id == r?.get('id')?.toString()?.trim()) {
        return r
      }
    }
    null
  }

  /**
   * Collects deterministic read-only {@code engineSteps} in execution order:
   * {@code phases.context} → {@code phases.action} → {@code phases.confirmation} (each phase may be a {@link Map}
   * with an {@code engineSteps} array), then legacy top-level {@code engineSteps}.
   */
  static List<Map> collectEngineSteps(Map recipe) {
    List<Map> out = new ArrayList<>()
    if (!(recipe instanceof Map)) {
      return Collections.unmodifiableList(out)
    }
    appendEngineStepsFromPhase(recipe.get('phases'), 'context', out)
    appendEngineStepsFromPhase(recipe.get('phases'), 'action', out)
    appendEngineStepsFromPhase(recipe.get('phases'), 'confirmation', out)
    Object legacy = recipe.get('engineSteps')
    if (legacy instanceof List) {
      for (Object o : (List) legacy) {
        if (o instanceof Map) {
          out.add(new LinkedHashMap<>((Map) o))
        }
      }
    }
    Collections.unmodifiableList(out)
  }

  private static void appendEngineStepsFromPhase(Object phases, String phaseKey, List<Map> sink) {
    if (!(phases instanceof Map)) {
      return
    }
    Object phaseVal = ((Map) phases).get(phaseKey)
    if (!(phaseVal instanceof Map)) {
      return
    }
    Object es = ((Map) phaseVal).get('engineSteps')
    if (!(es instanceof List)) {
      return
    }
    for (Object o : (List) es) {
      if (o instanceof Map) {
        sink.add(new LinkedHashMap<>((Map) o))
      }
    }
  }

  static String formatMatchedRecipePrelude(Map recipe, String recipeId, double confidence, String reason) {
    StringBuilder sb = new StringBuilder()
    sb.append('[Studio — matched authoring intent recipe]\n')
    sb.append('recipeId: ').append(recipeId).append('\n')
    sb.append('confidence: ').append(String.format(java.util.Locale.US, '%.2f', confidence)).append('\n')
    if (reason?.trim()) {
      sb.append('routerNote: ').append(reason.trim().replace('\n', ' ')).append('\n')
    }
    sb.append('\n**Align ## Plan and CMS tools with these phases** (visitor-visible outcomes; do not treat this block as a substitute for calling tools when work is required):\n\n')
    appendPhase(sb, 'Context', recipe?.phases, 'context')
    appendPhase(sb, 'Action', recipe?.phases, 'action')
    appendPhase(sb, 'Confirmation', recipe?.phases, 'confirmation')
    sb.append('\n---\n\n')
    sb.toString()
  }

  static String matchedUserPrelude(Map recipe) {
    String p = recipe?.get('matchedUserPrelude')?.toString()?.trim()
    return p ?: ''
  }

  static boolean authorVisibleMatchesOrchestrationBypass(String authorVisible, List<String> bypassKeywords) {
    if (!authorVisible?.trim() || bypassKeywords == null || bypassKeywords.isEmpty()) {
      return false
    }
    String a = authorVisible.toLowerCase(Locale.ROOT)
    for (String kw : bypassKeywords) {
      String k = (kw ?: '').trim().toLowerCase(Locale.ROOT)
      if (k && a.contains(k)) {
        return true
      }
    }
    false
  }

  static Map orchestrationTelemetryExtras(Map recipe) {
    List<String> allow = toolsLoopAllowlistNames(recipe)
    List<String> bypass = toolsLoopAllowlistBypassKeywords(recipe)
    if (allow.isEmpty() && bypass.isEmpty()) {
      return Collections.emptyMap()
    }
    Map extra = new LinkedHashMap<>()
    if (!allow.isEmpty()) {
      extra.put('toolsLoopAllowlist', allow)
    }
    if (!bypass.isEmpty()) {
      extra.put('toolsLoopAllowlistBypassIfAuthorMentions', bypass)
    }
    Collections.unmodifiableMap(extra)
  }

  private static List<String> toolsLoopAllowlistNames(Map recipe) {
    Object raw = recipe?.get('toolsLoopAllowlist')
    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }
    List<String> out = []
    for (Object o : (List) raw) {
      String n = o?.toString()?.trim()
      if (n) {
        out.add(n)
      }
    }
    out
  }

  private static List<String> toolsLoopAllowlistBypassKeywords(Map recipe) {
    Object raw = recipe?.get('toolsLoopAllowlistBypassIfAuthorMentions')
    if (!(raw instanceof List)) {
      return Collections.emptyList()
    }
    List<String> out = []
    for (Object o : (List) raw) {
      String n = o?.toString()?.trim()
      if (n) {
        out.add(n)
      }
    }
    out
  }

  private static void appendPhase(StringBuilder sb, String label, Object phases, String key) {
    if (!(phases instanceof Map)) {
      return
    }
    Object raw = ((Map) phases).get(key)
    if (raw == null) {
      return
    }
    if (raw instanceof List) {
      appendPhaseHintLines(sb, label, (List) raw)
      return
    }
    if (raw instanceof Map) {
      Map pm = (Map) raw
      Object hints = pm.get('hints')
      if (!(hints instanceof List) || ((List) hints).isEmpty()) {
        hints = pm.get('lines')
      }
      if (hints instanceof List && !((List) hints).isEmpty()) {
        appendPhaseHintLines(sb, label, (List) hints)
      }
    }
  }

  private static void appendPhaseHintLines(StringBuilder sb, String label, List lines) {
    if (lines == null || lines.isEmpty()) {
      return
    }
    sb.append('**').append(label).append(":**\n")
    for (Object line : lines) {
      String s = line?.toString()?.trim()
      if (s) {
        sb.append('- ').append(s).append('\n')
      }
    }
    sb.append('\n')
  }
}
