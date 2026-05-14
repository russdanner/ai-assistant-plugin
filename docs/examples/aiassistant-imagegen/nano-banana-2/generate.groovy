import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

// Copy to: config/studio/scripts/aiassistant/imagegen/nano-banana-2/generate.groovy
// Agent: <imageGenerator>script:nano-banana-2</imageGenerator>
//
// **Nano Banana 2** — nickname for **Google Gemini 3.1 Flash Image** (model id often **`gemini-3.1-flash-image-preview`**).
// This script calls **`POST …/v1beta/models/{model}:generateContent`** on **generativelanguage.googleapis.com** with
// **`generationConfig.responseModalities`** including **IMAGE**, then returns a **`data:`** URL so **GenerateImage**
// matches the built-in tool result shape (`ok`, `tool`, `model`, `url`, optional `hint`).
//
// **Keys** (same sources as Studio **`llm=gemini`**):
//   export GEMINI_API_KEY=...   or   export GOOGLE_API_KEY=...
//   JVM: -Dcrafter.gemini.apiKey=...  /  -Dcrafter.google.apiKey=...
//
// **Model id** (newest wins): tool arg **`model`** → **`<imageModel>`** / `ctx.defaultImageModel` → env **`GEMINI_NANO_BANANA_MODEL`**
// → default **`gemini-3.1-flash-image-preview`**. Preview ids change — confirm in [Google AI Studio](https://aistudio.google.com/).
//
// **Optional env**
//   **`GEMINI_GENERATE_CONTENT_BASE_URL`** — default **`https://generativelanguage.googleapis.com/v1beta`**
//
// **Docs:** https://ai.google.dev/gemini-api/docs/image-generation

{ Map input, Map ctx ->
  String prompt = input?.prompt?.toString()?.trim()
  if (!prompt) {
    return [error: true, message: 'Missing required field: prompt']
  }

  String apiKey = System.getenv('GEMINI_API_KEY')?.toString()?.trim()
  if (!apiKey) {
    apiKey = System.getenv('GOOGLE_API_KEY')?.toString()?.trim()
  }
  if (!apiKey) {
    apiKey = System.getProperty('crafter.gemini.apiKey')?.toString()?.trim()
  }
  if (!apiKey) {
    apiKey = System.getProperty('crafter.google.apiKey')?.toString()?.trim()
  }
  if (!apiKey) {
    return [
      error  : true,
      message:
        'Nano Banana 2 / Gemini image: set GEMINI_API_KEY or GOOGLE_API_KEY on the Studio host (or JVM crafter.gemini.apiKey / crafter.google.apiKey).'
    ]
  }

  String modelRaw = input?.model?.toString()?.trim() ?: ctx?.defaultImageModel?.toString()?.trim()
  if (!modelRaw) {
    modelRaw = System.getenv('GEMINI_NANO_BANANA_MODEL')?.toString()?.trim()
  }
  String model = (modelRaw ?: 'gemini-3.1-flash-image-preview').toString().trim()

  String base = System.getenv('GEMINI_GENERATE_CONTENT_BASE_URL')?.toString()?.trim()
  if (!base) {
    base = 'https://generativelanguage.googleapis.com/v1beta'
  }
  base = base.replaceAll(/\/+$/, '')

  Map body = [
    contents: [[parts: [[text: prompt]]]],
    generationConfig: [
      responseModalities: ['TEXT', 'IMAGE']
    ]
  ]

  String json = JsonOutput.toJson(body)
  String urlStr = "${base}/models/${model}:generateContent"
  HttpURLConnection conn = null
  try {
    conn = (HttpURLConnection) new URL(urlStr).openConnection()
    conn.requestMethod = 'POST'
    conn.setRequestProperty('Content-Type', 'application/json; charset=UTF-8')
    conn.setRequestProperty('x-goog-api-key', apiKey)
    conn.doOutput = true
    conn.connectTimeout = 60_000
    conn.readTimeout = 300_000
    conn.outputStream.withWriter(StandardCharsets.UTF_8.name()) { w -> w.write(json) }
    int code = conn.responseCode
    String text = (code >= 400 ? conn.errorStream : conn.inputStream)?.getText(StandardCharsets.UTF_8.name()) ?: ''
    if (code < 200 || code >= 300) {
      String errMsg = "Gemini image HTTP ${code}"
      try {
        def ep = new JsonSlurper().parseText(text ?: '{}')
        if (ep instanceof Map && ep.error) {
          def em = ep.error instanceof Map ? ep.error.message : ep.error
          if (em) {
            errMsg = "${errMsg}: ${em}"
          }
        } else if (text?.trim()) {
          errMsg = "${errMsg}: ${text.length() > 1200 ? text.substring(0, 1200) + '…' : text}"
        }
      } catch (Throwable ignored) {
        if (text?.trim()) {
          errMsg = "${errMsg}: ${text.length() > 1200 ? text.substring(0, 1200) + '…' : text}"
        }
      }
      return [error: true, message: errMsg, httpStatus: code]
    }

    def root = new JsonSlurper().parseText(text ?: '{}')
    if (!(root instanceof Map)) {
      return [error: true, message: 'Unexpected Gemini response shape', raw: text]
    }
    Map rootM = (Map) root
    def cands = rootM.candidates
    if (!(cands instanceof List) || cands.isEmpty()) {
      return [error: true, message: 'Gemini returned no candidates', raw: text]
    }
    def c0 = cands[0]
    if (!(c0 instanceof Map)) {
      return [error: true, message: 'Gemini candidate[0] invalid', raw: text]
    }
    Map cand = (Map) c0
    if (cand.blockReason || cand.block_reason) {
      return [
        error  : true,
        message: "Gemini blocked this request: ${(cand.blockReason ?: cand.block_reason).toString()}",
        raw    : text
      ]
    }
    def content = cand.content
    if (!(content instanceof Map)) {
      return [error: true, message: 'Gemini candidate missing content', raw: text]
    }
    def parts = ((Map) content).parts
    if (!(parts instanceof List)) {
      return [error: true, message: 'Gemini content.parts missing', raw: text]
    }

    String revised = ''
    String mime = 'image/png'
    String b64 = ''
    for (def p : (List) parts) {
      if (!(p instanceof Map)) {
        continue
      }
      Map pm = (Map) p
      if (pm.text != null) {
        String t = pm.text.toString().trim()
        if (t) {
          revised = revised ? (revised + '\n' + t) : t
        }
      }
      def inline = pm.inlineData ?: pm.inline_data
      if (inline instanceof Map) {
        Map im = (Map) inline
        String mt = (im.mimeType ?: im.mime_type)?.toString()?.trim()
        String d = im.data?.toString()
        if (d) {
          b64 = d
          if (mt) {
            mime = mt
          }
          break
        }
      }
    }

    if (!b64) {
      return [error: true, message: 'Gemini response had no image inlineData (check model supports IMAGE modality).', raw: text]
    }

    String dataUrl = "data:${mime};base64,${b64}"
    return [
      ok            : true,
      tool          : 'GenerateImage',
      model         : model,
      url           : dataUrl,
      urlIsData     : true,
      revised_prompt: revised ?: null,
      hint          :
        'Nano Banana 2 / Gemini image returned as a data URL for chat preview. For production CMS assets, save to /static-assets/ and reference that path.'
    ]
  } catch (Throwable t) {
    try {
      log.warn('nano-banana-2 imagegen: {}', t.toString())
    } catch (Throwable ignored) {
    }
    return [error: true, message: (t.message ?: t.toString())]
  } finally {
    try {
      conn?.disconnect()
    } catch (Throwable ignored) {
    }
  }
}
