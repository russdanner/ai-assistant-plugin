import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

String siteId = (params?.siteId ?: request.getParameter('siteId'))?.toString()?.trim()
if (!siteId) {
  response.setStatus(400)
  return [ok: false, message: 'Missing siteId']
}

def ops = new StudioToolOperations(request, applicationContext, params)
Map r = ops.listStudioContentTypes(siteId, false, '')

return [
  ok           : r.ok != false,
  siteId       : siteId,
  mode         : r.mode,
  count        : r.count,
  contentTypes : r.contentTypes ?: [],
  message      : r.message,
  hint         : r.hint
]
