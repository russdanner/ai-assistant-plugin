package plugins.org.craftercms.aiassistant.authoring

import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * Appends Studio preview context to the user prompt so the LLM can resolve
 * phrases like "this page", "my page", or "update my content" without a path.
 */
class AuthoringPreviewContext {

  /** Publish / go-live without naming another path — pair with {@link #appendToUserPrompt} path injection. */
  private static final Pattern PUBLISH_NOW_INTENT = Pattern.compile(
    '(?i)\\b(publish(\\s+now|\\s+this|\\s+the\\s+(page|article|post|item))?|push\\s+to\\s+live|go\\s+live|deploy(\\s+now)?|release(\\s+to\\s+live)?|make\\s+it\\s+live|send\\s+to\\s+live|put\\s+it\\s+live)\\b'
  )

  /** Cross-language or “translate this” style work — paired with path injection. */
  private static final Pattern CROSS_LANGUAGE_TRANSLATE_INTENT = Pattern.compile(
    '(?i)\\b(translat|localiz|localization|to\\s+(spanish|french|german|arabic|italian|portuguese|dutch|russian|japanese|chinese|korean|hindi|vietnamese|polish|turkish|hebrew|swedish|norwegian|danish|finnish|greek)|in\\s+spanish|in\\s+french|in\\s+german|msa\\b|modern\\s+standard\\s+arabic)\\b'
  )

  /** Author means whole rendered page / site — do not apply narrow single-item fast path. */
  private static final Pattern FULL_PAGE_OR_SITE_COPY_INTENT = Pattern.compile(
    '(?i)\\b(this\\s+page|the\\s+page|full\\s+page|whole\\s+page|everything\\s+(on|in)|all\\s+(visible\\s+)?copy|what\\s+i\\s+see|entire\\s+site|sitewide|every\\s+page|whole\\s+site)\\b'
  )

  /** Same-language copy/tone/structure edits on the open item (not full-page / not translate). */
  private static final Pattern SINGLE_ITEM_EDIT_INTENT = Pattern.compile(
    '(?i)\\b(rephrase|proofread|rewrite|fix\\s+typos?|grammar|reading\\s+level|simplif(y|ying)|expand\\s+(the\\s+)?(bullets|body)|shorten|tweak|improve|polish|refine|update\\s+(the\\s+)?(title|headings?|body|copy|excerpt|seo|meta)|edit\\s+(the\\s+)?(copy|text|content|article|post)|change\\s+(the\\s+)?(wording|text|tone)|multicolor|headings?\\s+(in|with|to|like))\\b'
  )

  /** Author refers to display layer (FTL / template), not field XML only — paired with path injection. */
  private static final Pattern TEMPLATE_LAYER_MENTION = Pattern.compile(
    '(?i)\\b(template|templates|ftl|freemarker|\\.ftl|display\\s+template)\\b'
  )

  /** With {@link #TEMPLATE_LAYER_MENTION}, signals formatting / listing / dates in preview (not schema-only). */
  private static final Pattern TEMPLATE_DISPLAY_SHAPE_INTENT = Pattern.compile(
    '(?i)\\b(date|dates?|format|listing|list\\s+view|cards?|grid|markup|render(ing)?|layout|how\\s+it\\s+(shows|looks)|visible\\s+in\\s+preview)\\b'
  )

  /**
   * Author wants new repository content — generic; no assumed folder or content-type id.
   * Matches “create/add/write/draft … page/post/article/item” (including “write a blog article …” — {@code write}
   * was missing before and skipped the fast-path hint).
   */
  private static final Pattern NEW_CONTENT_INTENT = Pattern.compile(
    '(?i)\\b(create|add|make|build|write|draft)\\s+(?:(a|an|the)\\s+)?(?:(new)\\s+)?((blog|news|press)\\s+)?(page|post|article|item|entry|content\\s+item|url\\s+route|landing\\s+page|section\\s+page)\\b'
  )

  /** Client sends {@code authoringSurface: "formEngine"} for the content-type form assistant (not XB / preview). */
  static boolean isFormEngineSurface(Object raw) {
    def s = (raw ?: '').toString().trim().toLowerCase()
    return s == 'formengine' || s == 'form_engine'
  }

  /** True for JSON boolean true, or string "true" / "1" / "yes" (case-insensitive). */
  static boolean isTruthy(Object raw) {
    if (raw == null) return false
    if (raw instanceof Boolean) return ((Boolean) raw).booleanValue()
    def s = raw.toString().trim().toLowerCase()
    return s == 'true' || s == '1' || s == 'yes'
  }

  /**
   * Request body {@code enableTools}: absent/null/empty → {@code true} (OpenAI tools on, legacy default).
   * Explicit {@code false}, {@code "false"}, {@code "0"}, {@code "no"} → {@code false}.
   */
  static boolean parseEnableTools(Object raw) {
    if (raw == null) return true
    if (raw instanceof Boolean) return ((Boolean) raw).booleanValue()
    def s = raw.toString().trim().toLowerCase()
    if (!s) return true
    if (s == 'false' || s == '0' || s == 'no') return false
    if (s == 'true' || s == '1' || s == 'yes') return true
    return true
  }

  static String normalizeRepoPath(String path) {
    def p = (path ?: '').toString().trim()
    if (!p) return ''
    return p.startsWith('/') ? p : '/' + p
  }

  /** True when both normalize to the same non-empty repository path. */
  static boolean sameRepoPath(Object pathA, Object pathB) {
    def pa = normalizeRepoPath(pathA?.toString())
    def pb = normalizeRepoPath(pathB?.toString())
    return pa && pb && pa == pb
  }

  /**
   * Strips Studio-injected blocks from the orchestration user prompt so intent checks
   * (e.g. trivial greeting) only see what the author typed, not metadata that quotes
   * phrases like {@code "this page"} as examples.
   */
  static String stripStudioInjectedPromptBlocks(String fullPrompt) {
    def s = (fullPrompt ?: '').toString()
    if (!s.trim()) {
      return ''
    }
    def out = s
    try {
      // [Prior conversation …] … ---\n\n (from AiOrchestration.buildPriorTurnsContextBlock)
      out = out.replaceAll('(?s)\\[Prior conversation[^\\]]*\\][\\s\\S]*?\\n---\\s*\\n\\n', '')
      // [Request anchor …]\nRepository path: …\nContent-type id: …\n\n
      out = out.replaceAll(
        '(?is)\\[Request anchor[^\\]]*\\][^\\n]*\\nRepository path:\\s*[^\\n]+\\n(?:Content-type id:\\s*[^\\n]+\\n)?\\s*',
        ''
      )
      // --- Studio authoring context … --- (standalone closing line `---` before preview bundle or EOF)
      def ctxIdx = out.indexOf('--- Studio authoring context')
      if (ctxIdx >= 0) {
        def lineStart = out.lastIndexOf('\n', ctxIdx)
        def blockStart = lineStart >= 0 ? lineStart : 0
        def scan = out.indexOf('\n', ctxIdx)
        while (scan >= 0) {
          def nextNl = out.indexOf('\n', scan + 1)
          def line = nextNl < 0 ? out.substring(scan + 1) : out.substring(scan + 1, nextNl)
          if ('---'.equals(line.trim())) {
            def endExclusive = nextNl < 0 ? out.length() : nextNl + 1
            out = out.substring(0, blockStart) + out.substring(endExclusive)
            break
          }
          scan = nextNl
        }
      }
      // Trailing preview bundle (appendEnginePreviewHintIfPossible)
      out = out.replaceAll('(?ms)\\n\\n--- Studio preview URL[\\s\\S]*', '')
      out = out.replaceAll('(?ms)\\n\\n--- Engine preview URL[\\s\\S]*', '')
    } catch (Throwable ignored) {
    }
    return out.trim()
  }

  private static final Pattern CMS_TASK_SIGNAL = Pattern.compile(
    '(?i)(\\b(translat|localiz|publish|deploy|go\\s+live|revert|update|edit|change|rewrite|rephrase|delete|create|write|draft|generate\\s+image|draw|fix|content|templates?|template|css|scss|less|stylesheet|styling|branding|mockup|theme|layout|ftl|freemarker|component|sections?_o|writecontent|listpages|getcontent|static-assets|update_template|analyze_template)\\b|https?://|\\blook\\s+like\\b|\\bsimilar\\s+to\\b|\\bmatch(es)?\\b|\\bsite\\b|\\bwebsite\\b)'
  )

  /**
   * {@code google.com}, {@code www.nytimes.com/…} — authors often omit {@code https://}. TLD allow-list avoids
   * matching {@code index.xml}, {@code form-definition.xml}, etc.
   */
  private static final Pattern BARE_REFERENCE_HOST_PATTERN = Pattern.compile(
    '(?i)\\b(?:www\\.)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])\\.)+(?:com|org|net|io|co\\.uk|co|gov|edu|dev|app|cms|ai|uk|de|fr|es|it|ca|au|nz|jp|cn|in|br|mx|blog|news|shop|store|tv|me|info|biz)\\b'
  )

  /**
   * Broad visual / reference language — used for **longer** prompts when a URL/host is present (see
   * {@link #isAuthoringIntentExpansionCandidate}); **short** prompts use length alone (still require CMS tooling signal).
   */
  private static final Pattern AUTHORING_INTENT_EXPANSION_VISUAL = Pattern.compile(
    '(?i)(\\blook\\s+like\\b|\\bsimilar\\s+to\\b|\\bmatch(?:es)?\\b|\\bresemble\\b|\\bfeel\\s+like\\b|\\bstyled?\\s+like\\b|\\bsame\\s+(look|style)\\b|\\bvisual\\s+(transform|overhaul|refresh|redesign)\\b|\\bredesign\\b|\\bbranding\\b|\\bmockup\\b|\\btheme\\b|\\b(css|stylesheet|scss|less)\\b.*\\b(template|templates?|ftl|layout|site|page)\\b|\\b(template|templates?|ftl|layout)\\b.*\\b(css|stylesheet|theme)\\b)'
  )

  /** Author-visible text (after stripping Studio blocks) this long or shorter is treated as likely underspecified. */
  private static final int AUTHORING_INTENT_EXPANSION_SHORT_VISIBLE_MAX_CHARS = 320

  /**
   * After stripping Studio-injected blocks, true when the author-visible text suggests CMS / repo / fetch work
   * (used server-side to avoid false “trivial greeting” tool suppression and to recover missing {@code tool_calls}).
   */
  static boolean authorVisibleSuggestsCmsTooling(String fullOrUserPrompt) {
    def v = stripStudioInjectedPromptBlocks((fullOrUserPrompt ?: '').toString())
    return v && CMS_TASK_SIGNAL.matcher(v).find()
  }

  /**
   * True when the author-visible text names an {@code http(s)} URL or a **likely external host** (e.g. {@code google.com}
   * without a scheme).
   */
  static boolean authorVisibleContainsHttpOrLikelyExternalHost(String visible) {
    def v = (visible ?: '').toString()
    if (!v) {
      return false
    }
    def low = v.toLowerCase(Locale.ROOT)
    if (low.contains('http://') || low.contains('https://')) {
      return true
    }
    return BARE_REFERENCE_HOST_PATTERN.matcher(v).find()
  }

  /**
   * When non-null, intent recipe routing / expansion is skipped for this turn (stable codes for Studio logs).
   * See {@link #isAuthoringIntentExpansionCandidate}.
   */
  static String intentRecipeRouterEligibilitySkipReason(String fullPrompt) {
    if (isTrivialNonAuthoringTurn(fullPrompt)) {
      return 'trivial_non_authoring_turn'
    }
    def v = stripStudioInjectedPromptBlocks((fullPrompt ?: '').toString())
    if (!v) {
      return 'empty_visible_after_strip'
    }
    if (v.length() > 1600) {
      return 'visible_exceeds_1600_chars'
    }
    if (!authorVisibleSuggestsCmsTooling(fullPrompt)) {
      return 'no_cms_task_signal'
    }
    if (v.length() <= AUTHORING_INTENT_EXPANSION_SHORT_VISIBLE_MAX_CHARS) {
      return null
    }
    if (!authorVisibleContainsHttpOrLikelyExternalHost(v)) {
      return 'long_message_no_url_for_expansion_gate'
    }
    if (!AUTHORING_INTENT_EXPANSION_VISUAL.matcher(v).find()) {
      return 'long_message_url_without_visual_reference_phrase'
    }
    return null
  }

  /**
   * Eligible for the server’s **pre-tools** intent-expansion completion: either **short** author-visible text
   * (usually a one-liner too terse for reliable tool planning) or a **longer** message that combines a URL/host with
   * reference / visual language.
   */
  static boolean isAuthoringIntentExpansionCandidate(String fullPrompt) {
    return intentRecipeRouterEligibilitySkipReason(fullPrompt) == null
  }

  /**
   * True when the author-visible part of the prompt is a short greeting / chit-chat with
   * no CMS authoring signal — used to force tools off for preview chat (avoids destructive
   * tool runs when only Studio metadata was appended).
   */
  static boolean isTrivialNonAuthoringTurn(String fullPrompt) {
    def visible = stripStudioInjectedPromptBlocks(fullPrompt)
    if (!visible) {
      return true
    }
    // Any CMS / site / template signal — never force tools off (check before length heuristics).
    if (authorVisibleSuggestsCmsTooling(fullPrompt)) {
      return false
    }
    if (visible.length() > 160) {
      return false
    }
    def t = visible.trim().toLowerCase(Locale.ROOT)
    if (t.matches('(?is)^(hello|hi|hey(\\s+there)?|good\\s+(morning|afternoon|evening)|thanks?|thank\\s+you|thx|ok(ay)?|yes|no|howdy|sup|yo|\\?)+[\\s!.?]*$')) {
      return true
    }
    def words = t.split(/\s+/).findAll { it }
    if (words.isEmpty() || words.size() > 6) {
      return false
    }
    def first = words[0].replaceAll('^\\p{Punct}+|\\p{Punct}+$', '')
    def openers = [
      'hello', 'hi', 'hey', 'thanks', 'thank', 'thx', 'yo', 'sup', 'howdy',
      'greetings', 'morning', 'evening', 'afternoon', 'ok', 'okay', 'yes', 'no', 'cheers'
    ] as Set
    if (openers.contains(first)) {
      return true
    }
    return words.size() >= 2 && first == 'good' && ['morning', 'afternoon', 'evening'].contains(words[1])
  }

  /**
   * If {@code contentPath} is non-empty, appends a fixed block the model must honor.
   * {@code contentTypeId} is optional (e.g. from Studio preview); may be blank.
   * {@code contentTypeLabelRaw} optional Studio UI label for the open item’s type when the client supplies it.
   */
  static String appendToUserPrompt(String prompt, Object contentPathRaw, Object contentTypeIdRaw, Object contentTypeLabelRaw = null) {
    def path = normalizeRepoPath(contentPathRaw?.toString())
    if (!path) {
      return (prompt ?: '').toString()
    }

    def ctRaw = (contentTypeIdRaw ?: '').toString().trim()
    def ctLine = ''
    if (ctRaw) {
      def ct = ctRaw.startsWith('/') ? ctRaw : '/' + ctRaw
      ctLine = "\nCurrent item content-type id (from Studio preview): ${ct}"
    }

    def labelRaw = (contentTypeLabelRaw ?: '').toString().trim()
    def labelLine = ''
    if (labelRaw) {
      labelLine = "\nCurrent item content-type **label** (Studio UI; from client when available): ${labelRaw}"
    }

    def base = (prompt ?: '').toString()
    def fastPublish = fastPathPublishHint(base, path)
    def fastTranslate = fastPathTranslateHint(base, path)
    def fastTemplate = fastPathTemplateDisplayHint(base, path)
    def fastEdit = fastPathSingleItemEditHint(base, path)
    def fastNewContent = fastPathNewContentItemHint(base, path)
    return """${base}

--- Studio authoring context (when the user does not name a repository path) ---
**This block is Studio metadata, not the author’s request.** Quoted example phrases below explain how to **resolve paths** when the author’s **own words** use relative references — **do not** treat those examples as the author having said “this page”, “update my content”, etc., unless the same words appear **above** this block in the author’s message. **Greeting-only turns** (“hello”, “thanks”, tiny chitchat with **no** ask to change the site): **do not** open with **ListContentTranslationScope**, **TranslateContentItem**, **TranslateContentBatch**, or broad **GetContent** “discovery” just because this path exists — answer in prose. **That narrow rule applies only to pure greetings.** When the author **does** ask to change the site — including **CSS**, **SCSS/LESS**, **templates / FTL**, **static-assets**, **themes**, **layout**, **“make it look like”** a **URL**, or **reference-site** styling — that **is** an authoring task: use the normal **GetContent** / **update_template** / **WriteContent** / **FetchHttpUrl** (read-only reference) / **analyze_template** flow per system policy; **do not** refuse because the work is “not content XML.”
Current content item repository path: ${path}${ctLine}${labelLine}
**Content-type id vs. Studio label:** Authors often name a type by its **Studio list label** while items use a repository **content-type id** (`/page/...`, `/component/...`). **Resolve** with **siteId** + **ListStudioContentTypes**, then system **Exact catalog match beats guessing** (**string equality** after the same normalization on **`label`**, **`name`**, and **`name`** tail — **no** fuzzy “closest” pick). If **exactly one** row matches, use its **`name`** as **`contentTypeId`**; if **zero** or **many**, ask the author. **Do not** invent a **content-type id** from keywords alone.
When the author says "this page", "my page", "the current page", "this item", "update my content", or similar without specifying which file, treat that as **this** repository path. Use it as **contentPath** for update_content, GetContent, ListContentTranslationScope, WriteContent, GetContentTypeFormDefinition (when reading **that** item’s form), publish_content, revert_change, and for update_template / analyze_template when resolving the item's display-template. For **ListStudioContentTypes**, **do not** default to passing this path: call with **siteId** only first (full type catalog); pass **contentPath** only when you deliberately need folder-scoped allowed types. **Do not** call ListPagesAndComponents to guess a target when this block is present unless the user clearly refers to a different item or asks to browse or list the site.${fastPublish}${fastTranslate}${fastTemplate}${fastEdit}${fastNewContent}
---"""
  }

  /**
   * When the author wants to publish what is already open in preview — skip discovery reads.
   */
  static String fastPathPublishHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !PUBLISH_NOW_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — publish / go live:** Studio context already names **Current content item repository path**. Call **publish_content** with **contentPath** = that path (plus **siteId**, **publishingTarget** as requested, default **live** when the author says publish/go live) as your **first** tool after a minimal **## Plan** — **do not** call **GetContent**, **ListPagesAndComponents**, or **ListContentTranslationScope** first. Use **1–2** 📋 lines only. **Optional** extras (“also publish the listing page”, “watch propagation”, “publish dependencies”) must be **short sentences after ## Plan Execution** or a *Would you like…* offer — **not** extra 📋 steps unless the author explicitly asked for them.'''
  }

  /**
   * Cross-language / translate: avoid discovery reads before scoping; never re-list the same page without editing.
   */
  static String fastPathTranslateHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !CROSS_LANGUAGE_TRANSLATE_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — translate / localize:** You already have **Current content item repository path**. **Do not** open with **ListPagesAndComponents** or unrelated **GetContent** “discovery” reads. For **full page / everything visible** / **this page** copy: call **ListContentTranslationScope** **once** on that **contentPath**, then **TranslateContentBatch** (same instructions for every path) or **TranslateContentItem** per path — **reuse** the first scope result; **never** call **ListContentTranslationScope** twice on the same **contentPath** in one turn without a write in between. For **only this file / this item** when the author clearly narrowed scope to the open item: **TranslateContentItem** on **this path** alone is enough (no tree) unless they expand scope mid-task. Use **GetContentTypeFormDefinition** only when you need unknown field ids. Keep **## Plan** tight (see system policy).'''
  }

  /**
   * Display / FTL work tied to the open item: resolve {@code display-template} from XML first; avoid serial update_template discovery.
   */
  static String fastPathTemplateDisplayHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !TEMPLATE_LAYER_MENTION.matcher(p).find() || !TEMPLATE_DISPLAY_SHAPE_INTENT.matcher(p).find()) {
      return ''
    }
    if (PUBLISH_NOW_INTENT.matcher(p).find() || CROSS_LANGUAGE_TRANSLATE_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — template / how it renders (this preview item):** You have **Current content item repository path**. **Do not** open with **update_template** before reading XML. **Round 1:** **GetContent** on **this** **contentPath** and read **`<display-template>`** for the page shell **`.ftl`**. If the change affects a **listing/cards/grid** fed by **`sections_o`** or other node-selectors, **GetContent** those **referenced `.xml`** paths in the **same** tool round when possible and read **each** `<display-template>` — the real markup is often in a **component** template, not the page **index.xml**. Then **GetContent** or **update_template** on **each distinct `.ftl`**, **WriteContent** each changed template **once**. **Do not** loop **update_template → GetContent** on the same page XML for discovery. **One** **GetPreviewHtml** at the end when a preview URL exists. **2–4** 📋 lines; **do not** re-paste full **## Plan** every tool round.'''
  }

  /**
   * Same-language edits on the open XML only — not “this page” sitewide, not translate, not publish.
   */
  static String fastPathSingleItemEditHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path) {
      return ''
    }
    if (PUBLISH_NOW_INTENT.matcher(p).find() || CROSS_LANGUAGE_TRANSLATE_INTENT.matcher(p).find()) {
      return ''
    }
    if (FULL_PAGE_OR_SITE_COPY_INTENT.matcher(p).find()) {
      return ''
    }
    if (TEMPLATE_LAYER_MENTION.matcher(p).find()) {
      return ''
    }
    if (!SINGLE_ITEM_EDIT_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — edit open item (same language):** Scope is the **current** repository item. Use **GetContent** on **this** **contentPath**, revise fields in the **main** chat, **WriteContent** — **do not** call **TranslateContentItem** / **TranslateContentBatch**. **Do not** call **ListContentTranslationScope** unless the author asked for **referenced components** or **full-page** copy. **Do not** call **ListPagesAndComponents**. Optional **GetPreviewHtml** when preview URL exists. **2–3** 📋 lines.'''
  }

  /**
   * New repository content: avoid hard-coded site shapes; still discourage pointless listing.
   */
  static String fastPathNewContentItemHint(String userPrompt, String repoPath) {
    def p = (userPrompt ?: '').toString()
    def path = normalizeRepoPath(repoPath?.toString())
    if (!path || !NEW_CONTENT_INTENT.matcher(p).find()) {
      return ''
    }
    if (PUBLISH_NOW_INTENT.matcher(p).find()) {
      return ''
    }
    return '''

**Fast path — new content:** Do **not** assume a fixed URL folder layout or **content-type** id for this site. **Do not** open with **ListPagesAndComponents** (especially large **size** or whole-site inventory) — authors see empty chat and long waits; **ListPagesAndComponents** lists **items**, not **types**. Resolve **`contentTypeId`** first: call **ListStudioContentTypes** with **siteId** only (omit **contentPath**) for the **full** catalog (`mode` **all**); **`/page/...` rows are listed before** **`/component/...`**. Add **contentPath** only if you must check **allowed** types under a **known** parent folder — **not** as the default because preview is open on a hub or component. Apply **Exact catalog match beats guessing**: **only** when **exactly one** row **equals** the author’s **type phrase** (normalized **`label`**, **`name`**, or **`name`** tail), set **`contentTypeId`** to **that row’s `name`** — **do not** substitute **`/page/page_generic`**, **`/page/generic-page`**, or another type when that single exact match exists; if **zero** or **many** rows match, **ask** the author. Then **exactly one** **GetContentTypeFormDefinition** with that **`contentTypeId`** — **do not** pass **contentPath** of a **section hub** `…/<section>/index.xml` when its `<content-type>` is a **shell** (e.g. catch-all **`/page/…`**) and the **new** item’s resolved type is **different** — see system **Section hub `index.xml` vs child pages**. **Do not** batch **GetContentTypeFormDefinition** for unrelated **`/component/...`** types. Use **one** **GetContent** on a **sibling** item of the **same** type under the same `/site/website/…` tree when you still need XML field order (not the listing index unless it shares that type). In the **same first assistant `content`** as **## Plan** and **`tool_calls`**, include a **readable draft** of the new piece (title, outline, body in Markdown) so the author can **read while tools run** — **never** leave **`content`** empty on that first tool round. Then **WriteContent**. Use **ListPagesAndComponents** only when the author asked to **browse/search existing pages/items** or you truly lack **any** path hint after **ListStudioContentTypes** — keep **size** modest. **Never** use it right after **GetContentTypeFormDefinition** for a **resolved create** type — that sequence adds **no** value vs **one sibling GetContent**. After a successful **create**, if **formDefinitionXml** has **image-picker** fields and the new item’s XML still has no real images, add **one** optional invite in **## Plan Execution** for **GenerateImage** on author opt-in (see system STUDIO POLICY **New item — offer AI art for empty image fields**). **Before first WriteContent** on the new item, **GetContent** one **existing sibling** of the **same** `<content-type>` when any exist (fixes **`publishedDate_dt`** / **`*_dt`** literals and folder conventions). **Do not** ask “which folder?” before **tool_calls** — start tools immediately. **Do not** stream **`## Plan Execution`** in the same assistant message as **`tool_calls`** — one final recap after all tools (see STUDIO POLICY **Recap boundary**).'''
  }

  /**
   * Maps {@code /site/website/...} repository paths to the Engine browse path used in preview (hint only).
   */
  static String browsePathFromRepoWebsitePath(String repoPath) {
    def p = normalizeRepoPath(repoPath)
    if (!p || !p.startsWith('/site/website/')) return '/'
    def tail = p.substring('/site/website/'.length())
    if (!tail || tail.equalsIgnoreCase('index.xml')) return '/'
    def low = tail.toLowerCase(Locale.ROOT)
    if (low.endsWith('/index.xml')) {
      def folder = tail.substring(0, tail.length() - '/index.xml'.length()).replaceAll('/+$', '')
      return folder ? '/' + folder : '/'
    }
    int slash = tail.lastIndexOf('/')
    if (slash > 0) return '/' + tail.substring(0, slash)
    def fn = tail.replaceAll(/(?i)\.xml$/, '')
    return fn ? '/' + fn : '/'
  }

  /**
   * Same {@code scheme://host:port} as Studio uses for preview (from the servlet request).
   */
  static String previewOriginFromRequest(Object requestRaw) {
    def request = requestRaw
    if (!request) return ''
    try {
      def scheme = request.scheme?.toString() ?: 'http'
      def host = request.serverName?.toString()?.trim()
      if (!host) return ''
      int port = -1
      try {
        def sp = request.serverPort
        if (sp instanceof Integer) {
          port = (Integer) sp
        } else {
          port = Integer.parseInt(sp?.toString() ?: '-1')
        }
      } catch (Throwable ignored) {
        port = -1
      }
      boolean defPort = port < 0 ||
        (scheme.equalsIgnoreCase('https') && port == 443) ||
        (scheme.equalsIgnoreCase('http') && port == 80)
      return "${scheme}://${host}${defPort ? '' : ":${port}"}"
    } catch (Throwable ignored) {
      return ''
    }
  }

  /** True when {@code u} looks like Studio’s XB preview shell ({@code …/studio/preview#/?page=…&site=…}). */
  static boolean looksLikeStudioPreviewShellUrl(Object urlRaw) {
    def u = (urlRaw ?: '').toString().trim()
    if (!u) return false
    if (!u.contains('#')) return false
    return u.toLowerCase(Locale.ROOT).contains('/studio/preview')
  }

  /**
   * Studio Experience Builder address-bar style URL: {@code /studio/preview#/?page=/path&site=siteId} (fragment carries {@code page}).
   * Authors recognize this form; it is **not** valid for raw HTTP GET to fetch Engine HTML.
   */
  static String buildStudioPreviewShellAbsoluteUrl(Object requestRaw, Object siteIdRaw, Object contentPathRaw) {
    def site = (siteIdRaw ?: '').toString().trim()
    def repo = normalizeRepoPath(contentPathRaw?.toString())
    if (!site || !repo) return ''
    String origin = previewOriginFromRequest(requestRaw)
    if (!origin) return ''
    try {
      String browse = browsePathFromRepoWebsitePath(repo)
      if (!browse.startsWith('/')) browse = '/' + browse
      String encPage = URLEncoder.encode(browse, 'UTF-8')
      String encSite = URLEncoder.encode(site, 'UTF-8')
      return "${origin}/studio/preview#/?page=${encPage}&site=${encSite}"
    } catch (Throwable ignored) {
      return ''
    }
  }

  /**
   * Absolute Engine preview URL (same host as the Studio request) for GetPreviewHtml. Empty when inputs are insufficient.
   */
  static String buildEnginePreviewAbsoluteUrl(Object requestRaw, Object siteIdRaw, Object contentPathRaw) {
    def request = requestRaw
    if (!request) return ''
    def site = (siteIdRaw ?: '').toString().trim()
    def repo = normalizeRepoPath(contentPathRaw?.toString())
    if (!site || !repo) return ''
    try {
      String origin = previewOriginFromRequest(requestRaw)
      if (!origin) return ''
      String browse = browsePathFromRepoWebsitePath(repo)
      def encSite = URLEncoder.encode(site, 'UTF-8')
      if (browse == '/' || !browse) {
        return "${origin}/?crafterSite=${encSite}"
      }
      String pathPart = browse.startsWith('/') ? browse : '/' + browse
      return "${origin}${pathPart}?crafterSite=${encSite}"
    } catch (Throwable ignored) {
      return ''
    }
  }

  /**
   * Appends Studio shell URL (author-facing) plus Engine URL (tool-facing for GetPreviewHtml).
   * {@code clientStudioPreviewPageUrlRaw} optional — when the browser already has {@code …/studio/preview#/?page=…&site=…}, pass it so the prompt matches the author’s address bar.
   */
  static String appendEnginePreviewHintIfPossible(
    String prompt,
    Object request,
    Object siteIdRaw,
    Object contentPathRaw,
    Object clientStudioPreviewPageUrlRaw = null
  ) {
    def engineUrl = buildEnginePreviewAbsoluteUrl(request, siteIdRaw, contentPathRaw)
    def studioFromClient = (clientStudioPreviewPageUrlRaw ?: '').toString().trim()
    def studioDisplay = looksLikeStudioPreviewShellUrl(studioFromClient) ? studioFromClient : buildStudioPreviewShellAbsoluteUrl(request, siteIdRaw, contentPathRaw)
    if (!engineUrl && !studioDisplay) return (prompt ?: '').toString()
    def base = (prompt ?: '').toString()
    def studioBlock = studioDisplay ? """--- Studio preview URL (Experience Builder — matches the Studio address bar) ---
${studioDisplay}
When you tell the author **where to open preview** in Studio, use **this** URL (or an equivalent `/studio/preview#/?page=…&site=…` link). **Do not** present a bare Engine browse URL like `http://host/locale/path?crafterSite=…` as the author’s “Studio preview” link — that is for server-side HTML fetch only.

""" : ''
    def engineBlock = engineUrl ? """--- Engine preview URL (**GetPreviewHtml** tool only) ---
**GetPreviewHtml** performs an HTTP GET; it **cannot** use a Studio shell URL (`…/studio/preview#…`) because the `#…` fragment is never sent to a server. After writes that affect rendered output, pass **this** absolute Engine URL as the tool **url** (the plugin rewrites `/studio/preview#…` when needed, but prefer this ready-to-fetch URL):
${engineUrl}
""" : ''
    return """${base}

${studioBlock}${engineBlock}---"""
  }

  /**
   * Minimal notice for {@code authoringSurface: formEngine} only. Does **not** change XB / preview behavior.
   * Strong client-side JSON apply instructions are added only when the client sets {@code formEngineClientJsonApply: true}.
   */
  static String appendFormEngineAuthoringNotice(String prompt) {
    def base = (prompt ?: '').toString()
    return base + '''

--- Studio form-engine context ---
The author is in the **Studio content form** (legacy form engine), not Experience Builder. If this prompt includes a **Current Studio content form** block, it lists **paths and field ids only** (not full item XML/JSON) so you can plan with tools; values may still be **live in the browser** until Save.

**Server-side tools** (GetContent, WriteContent, etc.) read and write **repository** files only; they do not update the open form's in-memory fields.
---'''
  }

  /**
   * Optional extra instructions: Studio form assistant asks the model to return {@code aiassistantFormFieldUpdates} JSON
   * so the browser can apply values. Sent only when the client sets {@code formEngineClientJsonApply: true}.
   * **Never** append for Experience Builder / preview chat ({@code authoringSurface} is not formEngine).
   */
  static String appendFormEngineClientJsonApplyInstructions(String prompt) {
    def base = (prompt ?: '').toString()
    return base + '''

--- Studio form client-apply instructions (JSON for in-browser form only) ---
The client will **parse** a fenced JSON block and **write values into the open form** (not the repository).

When the author asks you to **translate, localize, rewrite, update, improve, shorten, fix, or write** field content, use **GetContent** / **update_content** as needed (full bodies are not inlined in the prompt). Output the new strings in **`aiassistantFormFieldUpdates`** using **field ids** from the metadata appendix or form definition. **Do not** answer with generic CrafterCMS documentation: no "Access the Content Item", "Translation Configuration", "add a supported language", "use Studio translation tools", step-by-step CMS guides, or "click Save" as a substitute for the actual translated text. **Do not** refuse to translate if you can output the target language.

At the **end** of your reply, include:
```json
{ "aiassistantFormFieldUpdates": { "field_id": "new string value", "body_html": "<p>...</p>" } }
```
Use **real field ids** from the form definition / XML in the prompt; values must be **strings** only. List every field you changed. For **pure Q&A** with no content change, omit the JSON block.
---'''
  }
}
