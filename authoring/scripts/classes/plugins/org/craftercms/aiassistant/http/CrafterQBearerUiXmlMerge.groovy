package plugins.org.craftercms.aiassistant.http

import org.dom4j.Document
import org.dom4j.Element
import org.dom4j.io.SAXReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration

import java.io.StringReader
import java.util.Iterator
import java.util.LinkedHashMap
import java.util.List
import java.util.Map

/**
 * Stream/chat POST bodies often omit fields that exist on the matching {@code <agent>} in site {@code /ui.xml}.
 * Merges missing {@code imageModel}, {@code llmModel}, {@code llm}, and {@code imageGenerator} from that row onto the POST body.
 */
final class CrafterQBearerUiXmlMerge {
  private static final Logger log = LoggerFactory.getLogger(CrafterQBearerUiXmlMerge)

  private CrafterQBearerUiXmlMerge() {}

  private static SAXReader newHardenedSaxReader() {
    SAXReader reader = new SAXReader()
    reader.setValidation(false)
    try {
      reader.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://xml.org/sax/features/external-general-entities', false)
    } catch (Throwable ignored) {
    }
    try {
      reader.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
    } catch (Throwable ignored) {
    }
    reader
  }

  private static Element findDirectChildByLocalName(Element root, String localName) {
    if (root == null || !localName) {
      return null
    }
    for (Iterator it = root.elementIterator(); it.hasNext();) {
      Element e = (Element) it.next()
      if (localName == e.getQName().getName()) {
        return e
      }
    }
    null
  }

  private static void collectElementsWithLocalName(Element el, String localName, List<Element> sink) {
    if (el == null || !localName) {
      return
    }
    if (localName == el.getQName().getName()) {
      sink.add(el)
    }
    for (Iterator it = el.elementIterator(); it.hasNext();) {
      collectElementsWithLocalName((Element) it.next(), localName, sink)
    }
  }

  private static Map extractAgentStreamOverlayFromUiXml(String uiXmlUtf8, String crafterQApiAgentId) {
    Map out = new LinkedHashMap()
    out.put('imageModel', '')
    out.put('llmModel', '')
    out.put('imageGenerator', '')
    out.put('llm', '')
    String wanted = (crafterQApiAgentId ?: '').toString().trim()
    if (!wanted || uiXmlUtf8 == null || !uiXmlUtf8.toString().trim()) {
      return out
    }
    try {
      Document doc = newHardenedSaxReader().read(new StringReader(uiXmlUtf8.toString()))
      Element root = doc.getRootElement()
      List<Element> agents = []
      collectElementsWithLocalName(root, 'agent', agents)
      for (Element agentEl : agents) {
        Element idEl = findDirectChildByLocalName(agentEl, 'crafterQAgentId')
        String idText = idEl?.getTextTrim() ?: ''
        if (!wanted.equals(idText)) {
          continue
        }
        out.put('imageModel', findDirectChildByLocalName(agentEl, 'imageModel')?.getTextTrim() ?: '')
        out.put('llmModel', findDirectChildByLocalName(agentEl, 'llmModel')?.getTextTrim() ?: '')
        out.put('imageGenerator', findDirectChildByLocalName(agentEl, 'imageGenerator')?.getTextTrim() ?: '')
        out.put('llm', findDirectChildByLocalName(agentEl, 'llm')?.getTextTrim() ?: '')
        break
      }
    } catch (Throwable t) {
      log.warn('extractAgentStreamOverlayFromUiXml: failed to parse ui.xml: {}', t.message)
    }
    out
  }

  private static String toSandboxConfigStudioRepoPath(String studioModuleRelativePath) {
    String p = (studioModuleRelativePath ?: '').toString().trim()
    if (!p.startsWith('/')) {
      p = "/${p}"
    }
    return "/config/studio${p}"
  }

  /**
   * Reads merged site {@code /ui.xml} (module {@code studio}) without touching {@link plugins.org.craftercms.aiassistant.tools.StudioToolOperations}.
   */
  static String readStudioUiXmlUtf8(Object applicationContext, String siteId) {
    if (applicationContext == null || siteId == null || !siteId.toString().trim()) {
      return null
    }
    String site = siteId.toString().trim()
    Object configurationServiceBean = null
    Object cstudioContentServiceBean = null
    try {
      configurationServiceBean = applicationContext.get('configurationService')
    } catch (Throwable ignored) {
    }
    try {
      cstudioContentServiceBean = applicationContext.get('cstudioContentService')
    } catch (Throwable ignored2) {
    }
    if (configurationServiceBean == null) {
      return null
    }
    String path = '/ui.xml'
    String sandboxRepoPath = toSandboxConfigStudioRepoPath(path)
    try {
      Object v1 = cstudioContentServiceBean
      if (v1 != null && v1.metaClass.respondsTo(v1, 'contentExists', String, String)) {
        Object exists = v1.contentExists(site, sandboxRepoPath)
        if (!(exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists))) {
          log.trace('readStudioUiXmlUtf8: skip read (missing) siteId={} repoPath={}', site, sandboxRepoPath)
          return null
        }
      } else if (v1 != null && v1.metaClass.respondsTo(v1, 'shallowContentExists', String, String)) {
        Object exists = v1.shallowContentExists(site, sandboxRepoPath)
        if (!(exists instanceof Boolean ? ((Boolean) exists).booleanValue() : Boolean.TRUE.equals(exists))) {
          log.trace('readStudioUiXmlUtf8: skip read (shallow missing) siteId={} repoPath={}', site, sandboxRepoPath)
          return null
        }
      }
    } catch (Throwable probeIgnored) {
    }
    try {
      return configurationServiceBean.getConfigurationAsString(site, 'studio', path, '')
    } catch (Throwable t) {
      log.debug('readStudioUiXmlUtf8 failed siteId={}: {}', site, t.message)
      return null
    }
  }

  /**
   * Fills missing {@code imageModel}, {@code llmModel}, {@code llm}, and/or {@code imageGenerator} on {@code body} from site {@code /ui.xml} for {@code crafterQAgentId}.
   */
  static void mergeStreamAgentFieldsFromSiteUiXmlIfMissing(Object applicationContext, Map body, String siteId, String crafterQApiAgentId) {
    if (!(body instanceof Map) || body == null) {
      return
    }
    String imgBody = (body.imageModel ?: body.get('image-model') ?: body.image_model)?.toString()?.trim() ?: ''
    String llmModelBody = (body.llmModel ?: body.get('llm-model') ?: body.llm_model)?.toString()?.trim() ?: ''
    String imgGenBody =
      (body.imageGenerator ?: body.get('image-generator') ?: body.image_generator)?.toString()?.trim() ?: ''
    String llmTransportBody = (body.llm ?: body.get('llm'))?.toString()?.trim() ?: ''
    if (imgBody && llmModelBody && imgGenBody && llmTransportBody) {
      return
    }
    String site = (siteId ?: '').toString().trim()
    String agent = (crafterQApiAgentId ?: '').toString().trim()
    if (!site || !agent) {
      return
    }
    String uiXml = readStudioUiXmlUtf8(applicationContext, site)
    if (uiXml == null || !uiXml.toString().trim()) {
      log.debug('mergeStreamAgentFieldsFromSiteUiXmlIfMissing: empty ui.xml siteId={}', site)
      return
    }
    Map extracted = extractAgentStreamOverlayFromUiXml(uiXml.toString(), agent)
    String xmlImg = (extracted.imageModel ?: '').toString().trim()
    String xmlLlmModel = (extracted.llmModel ?: '').toString().trim()
    String xmlImgGen = (extracted.imageGenerator ?: '').toString().trim()
    String xmlLlmTransport = (extracted.llm ?: '').toString().trim()
    if (!xmlImg && !xmlLlmModel && !xmlImgGen && !xmlLlmTransport) {
      log.debug(
        'Agent ui.xml merge: no overlay fields for stream agentId={} siteId={} (no <agent> with matching <crafterQAgentId>, or that row has no mergeable fields)',
        agent,
        site
      )
      return
    }
    if (!imgBody && xmlImg) {
      String imgNorm = AiOrchestration.normalizeOpenAiImagesApiModelId(xmlImg)
      body.put('imageModel', imgNorm)
      log.info(
        'Agent ui.xml merge: copied imageModel="{}" into POST body (POST omitted it; fixes GenerateImage without asking the author) siteId={} agent={}',
        imgNorm,
        site,
        agent
      )
    }
    if (!llmModelBody && xmlLlmModel) {
      body.put('llmModel', xmlLlmModel)
      log.info('Agent ui.xml merge: copied llmModel="{}" into POST body (POST omitted it) siteId={} agent={}', xmlLlmModel, site, agent)
    }
    if (!imgGenBody && xmlImgGen) {
      body.put('imageGenerator', xmlImgGen)
      log.info(
        'Agent ui.xml merge: copied imageGenerator="{}" into POST body (POST omitted it) siteId={} agent={}',
        xmlImgGen,
        site,
        agent
      )
    }
    if (!llmTransportBody && xmlLlmTransport) {
      body.put('llm', xmlLlmTransport)
      log.info(
        'Agent ui.xml merge: copied llm="{}" into POST body (POST omitted it) siteId={} agent={}',
        xmlLlmTransport,
        site,
        agent
      )
    }
  }
}
