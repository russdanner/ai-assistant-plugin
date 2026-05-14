import jakarta.servlet.http.HttpServletResponse
import plugins.org.craftercms.aiassistant.http.AiHttpProxy
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Downloads an image from a remote URL and writes it to the site sandbox under {@code /static-assets/...},
 * same as a desktop upload, for use from:
 * <ul>
 *   <li>CrafterQ chat drag-and-drop (after client prefetch)</li>
 *   <li>Form data source {@code crafterq-img-from-url} (URL prompt or data URL)</li>
 * </ul>
 *
 * <p>Query: {@code siteId} (required). Body JSON:</p>
 * <pre>
 * {
 *   "imageUrl": "https://...",
 *   "repoPath": "/static-assets/item/images/{yyyy}/{mm}/{dd}/",
 *   "fileName": "optional.png",
 *   "objectId": "optional macro",
 *   "objectGroupId": "optional macro"
 * }
 * </pre>
 */
def body = AiHttpProxy.parseJsonBody(request) ?: [:]
def siteId = (params.siteId ?: body.siteId)?.toString()?.trim()
def imageUrl = body.imageUrl?.toString()?.trim()
def repoPath = body.repoPath != null ? body.repoPath.toString().trim() : ''
def fileName = body.fileName?.toString()?.trim()
def objectId = body.objectId?.toString()?.trim()
def objectGroupId = body.objectGroupId?.toString()?.trim()

if (!siteId) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Missing required query parameter: siteId']
}
if (!imageUrl) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: 'Missing required field: imageUrl']
}

try {
  def ops = new StudioToolOperations(request, applicationContext, params)
  def result = ops.importImageFromRemoteUrl(siteId, imageUrl, repoPath ?: '/static-assets/item/images/{yyyy}/{mm}/{dd}/', fileName, objectId, objectGroupId)
  return result
} catch (IllegalArgumentException iae) {
  response.status = HttpServletResponse.SC_BAD_REQUEST
  return [ok: false, message: iae.message ?: 'Invalid request']
} catch (Throwable t) {
  response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
  return [ok: false, message: t.message ?: t.toString()]
}
