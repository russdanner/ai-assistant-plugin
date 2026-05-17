package plugins.org.craftercms.aiassistant.recipes

import groovy.json.JsonSlurper

import java.util.Locale

/**
 * Parses the JSON-only reply from the authoring **intent recipe** router completion.
 */
final class AuthoringIntentRecipeRouter {

  private AuthoringIntentRecipeRouter() {}

  /**
   * @return map with keys: recipeId (String or null), confidence (double 0..1), reason (String)
   */
  static Map parseRouterJson(String raw) {
    String t = stripFences((raw ?: '').toString().trim())
    if (!t) {
      return [recipeId: null, confidence: 0.0d, reason: 'empty router reply']
    }
    try {
      Object o = new JsonSlurper().parseText(t)
      if (!(o instanceof Map)) {
        return [recipeId: null, confidence: 0.0d, reason: 'router reply not a JSON object']
      }
      Map m = (Map) o
      String rid = m.get('recipeId')?.toString()?.trim()
      if (!rid || 'null'.equalsIgnoreCase(rid)) {
        rid = null
      }
      double conf = 0.0d
      try {
        def c = m.get('confidence')
        if (c instanceof Number) {
          conf = ((Number) c).doubleValue()
        } else if (c != null) {
          conf = Double.parseDouble(c.toString().trim())
        }
      } catch (Throwable ignored) {
        conf = 0.0d
      }
      if (conf < 0.0d) {
        conf = 0.0d
      }
      if (conf > 1.0d) {
        conf = 1.0d
      }
      String reason = m.get('reason')?.toString()?.trim() ?: ''
      [recipeId: rid, confidence: conf, reason: reason]
    } catch (Throwable t2) {
      return [recipeId: null, confidence: 0.0d, reason: 'parse error: ' + (t2.message ?: t2.toString())]
    }
  }

  private static String stripFences(String s) {
    String t = s.trim()
    if (!t.startsWith('```')) {
      return t
    }
    int nl = t.indexOf('\n')
    if (nl > 0) {
      t = t.substring(nl + 1)
    }
    if (t.endsWith('```')) {
      t = t.substring(0, t.length() - 3).trim()
    }
    t
  }
}
