package plugins.org.craftercms.aiassistant.imagegen

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.llm.StudioAiProviderCredentials
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.Map

/**
 * Built-in {@code POST …/v1/images/generations} HTTP wire for {@link StudioAiImageGenerator}. Not tied to the chat LLM vendor:
 * credentials and base URL come from {@link StudioAiImageGenContext}.
 */
final class OpenAiCompatibleImageGenerator implements StudioAiImageGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleImageGenerator.class)

  @Override
  Map generate(Map input, StudioAiImageGenContext ctx) {
    String apiKey = (ctx?.imagesApiKey ?: '').toString().trim()
    if (!apiKey) {
      return [error: true, message: 'No API key configured for image generation']
    }
    String urlPost = (ctx?.imagesGenerationsHttpUrl ?: '').toString().trim()
    if (!urlPost) {
      urlPost = StudioAiProviderCredentials.httpOpenAiImagesGenerationsUrl()
    }
    return postImagesGenerations(apiKey, urlPost, (ctx?.defaultImageModel ?: '').toString(), (Map) (input ?: [:]))
  }

  /**
   * JSON body for {@code POST /v1/images/generations} — assembled as a string so no accidental extra keys are merged.
   */
  static String buildOpenAiImagesGenerationsRequestJson(String model, String prompt, String size, String quality) {
    String mLower = model.toLowerCase(Locale.US)
    boolean gptFamily = mLower.startsWith('gpt-image') || mLower.startsWith('chatgpt-image')
    StringBuilder sb = new StringBuilder(Math.max(96, prompt.length() + 96))
    sb.append('{')
    sb.append('"model":').append(JsonOutput.toJson(model)).append(',')
    sb.append('"prompt":').append(JsonOutput.toJson(prompt)).append(',')
    sb.append('"n":1')
    // Default API response is usually a temporary https URL; b64_json is predictable for chat expansion.
    // gpt-image / chatgpt-image families use output_format instead; some providers reject response_format.
    if (!gptFamily) {
      sb.append(',"response_format":').append(JsonOutput.toJson('b64_json'))
    }
    if (size) {
      sb.append(',"size":').append(JsonOutput.toJson(size))
    }
    if (quality && gptFamily) {
      sb.append(',"quality":').append(JsonOutput.toJson(quality))
    }
    if (gptFamily) {
      sb.append(',"output_format":').append(JsonOutput.toJson('png'))
    }
    sb.append('}')
    return sb.toString()
  }

  static Map postImagesGenerations(String apiKey, String postUrl, String defaultImageModel, Map input) {
    def prompt = input?.prompt?.toString()?.trim()
    if (!prompt) {
      return [error: true, message: 'Missing required field: prompt']
    }
    def modelRaw = input?.model?.toString()?.trim() ?: defaultImageModel
    if (!modelRaw?.trim()) {
      return [error: true, message: 'No image model configured']
    }
    String model = AiOrchestration.normalizeOpenAiImagesApiModelId(AiOrchestration.openAiCanonicalizeApiModelToken(modelRaw))
    if (!model?.trim()) {
      return [error: true, message: 'No image model configured']
    }
    def size = input?.size?.toString()?.trim()
    def quality = input?.quality?.toString()?.trim()
    model = AiOrchestration.normalizeOpenAiImagesApiModelId(model)
    if (!model?.trim()) {
      return [error: true, message: 'No image model configured']
    }
    String body = buildOpenAiImagesGenerationsRequestJson(model, prompt, size, quality)
    if (LOG.isDebugEnabled()) {
      LOG.debug('OpenAiCompatibleImageGenerator wireJson={}', AiHttpProxy.elideForLog(body, 900))
    }
    HttpURLConnection conn = null
    try {
      conn = (HttpURLConnection) new URL(postUrl).openConnection()
      conn.requestMethod = 'POST'
      conn.setRequestProperty('Content-Type', 'application/json; charset=UTF-8')
      conn.setRequestProperty('Authorization', 'Bearer ' + apiKey)
      conn.doOutput = true
      conn.connectTimeout = 120_000
      conn.readTimeout = 300_000
      conn.outputStream.withWriter('UTF-8') { w -> w.write(body) }
      int code = conn.responseCode
      def stream = code >= 400 ? conn.errorStream : conn.inputStream
      def text = stream != null ? stream.getText('UTF-8') : ''
      if (code < 200 || code >= 300) {
        def errMsg = "Images API HTTP ${code}"
        try {
          def parsed = new JsonSlurper().parseText(text ?: '{}')
          if (parsed instanceof Map && parsed.error?.message) {
            errMsg = "${errMsg}: ${parsed.error.message}"
          } else if (text?.trim()) {
            errMsg = "${errMsg}: ${text.length() > 800 ? text.substring(0, 800) + '…' : text}"
          }
        } catch (Throwable ignored) {
          if (text?.trim()) errMsg = "${errMsg}: ${text.length() > 800 ? text.substring(0, 800) + '…' : text}"
        }
        if (errMsg?.contains('response_format')) {
          LOG.warn(
            'OpenAiCompatibleImageGenerator HTTP {} — provider mentioned response_format; JSON is built without that field. attemptElided={}',
            code,
            AiHttpProxy.elideForLog(body, 700)
          )
        }
        return [error: true, message: errMsg, httpStatus: code]
      }
      def json = new JsonSlurper().parseText(text ?: '{}')
      if (!(json instanceof Map)) {
        return [error: true, message: 'Unexpected images API response shape']
      }
      def data = json.data
      if (!(data instanceof List) || data.isEmpty()) {
        return [error: true, message: 'Images API returned no data array', raw: text]
      }
      def first = data[0]
      if (!(first instanceof Map)) {
        return [error: true, message: 'Images API data[0] is not an object', raw: text]
      }
      def out = [
        ok            : true,
        tool          : 'GenerateImage',
        model         : model,
        url           : first.url,
        b64_json      : first.b64_json,
        revised_prompt: first.revised_prompt
      ]
      if (!out.url && !out.b64_json) {
        return [error: true, message: 'No url or b64_json in image result', raw: text]
      }
      if (!out.url && out.b64_json) {
        def of = (first.output_format ?: json.output_format)?.toString()?.trim()?.toLowerCase(Locale.US)
        def mime = 'image/png'
        if (of == 'jpeg' || of == 'jpg') {
          mime = 'image/jpeg'
        } else if (of == 'webp') {
          mime = 'image/webp'
        } else if (of == 'png') {
          mime = 'image/png'
        }
        out.url = 'data:' + mime + ';base64,' + out.b64_json
        out.urlIsData = true
        out.remove('b64_json')
      }
      out.hint = Boolean.TRUE.equals(out.urlIsData)
        ? 'Image is returned as base64; `url` is a data URL for chat preview. For production CMS assets, save to /static-assets/ and reference that path.'
        : 'Image URL expires; for CMS use, download and upload to /static-assets/ then reference in content.'
      return out
    } catch (Throwable t) {
      LOG.warn('OpenAiCompatibleImageGenerator failed: {}', t.toString())
      return [error: true, message: (t.message ?: t.toString())]
    } finally {
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {}
    }
  }
}
