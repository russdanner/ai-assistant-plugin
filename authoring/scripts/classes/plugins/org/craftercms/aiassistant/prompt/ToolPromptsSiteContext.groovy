package plugins.org.craftercms.aiassistant.prompt

/**
 * Request-scoped Studio site id + application context so {@link ToolPromptsLoader} can resolve prompt overrides from
 * {@code /scripts/aiassistant/prompts/&lt;KEY&gt;.md} in the site sandbox (before classpath defaults).
 * <p>Call {@link #enter} before orchestration and {@link #exit} in a {@code finally} block on the same thread.</p>
 */
final class ToolPromptsSiteContext {

  private static final ThreadLocal<Map> TL = new ThreadLocal<>()

  private ToolPromptsSiteContext() {}

  static void enter(Object applicationContext, String siteId) {
    Map m = new LinkedHashMap()
    m.put('applicationContext', applicationContext)
    m.put('siteId', (siteId ?: '').toString().trim())
    TL.set(m)
  }

  static void exit() {
    TL.remove()
  }

  static Map current() {
    TL.get()
  }
}
