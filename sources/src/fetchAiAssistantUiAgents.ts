import { fetchConfigurationXML } from '@craftercms/studio-ui/services/configuration';
import { firstValueFrom } from 'rxjs';
import { aiAssistantStudioPluginId, formControlWidgetId, helperWidgetId } from './consts';
import { exclusiveCentralChatAgentsFromFile, fetchCentralAgentsFile } from './centralAgentCatalog';
import { agentStableKey, type AgentConfig, type AgentLlm, type ExpertSkillConfig } from './agentConfig';

/** Prefer direct child elements named `tag` (matches typical ui.xml serialization). */
function childTextDirect(parent: Element, tag: string): string | undefined {
  const t = tag.toLowerCase();
  for (let i = 0; i < parent.children.length; i++) {
    const ch = parent.children[i];
    const name = ch.localName || ch.tagName.replace(/^.*:/, '');
    if (name.toLowerCase() === t) return ch.textContent?.trim() || undefined;
  }
  return undefined;
}

function parseAgentElement(agentEl: Element): AgentConfig | null {
  const id = childTextDirect(agentEl, 'crafterQAgentId') ?? '';
  const label = childTextDirect(agentEl, 'label') ?? '';
  if (!label.trim()) return null;
  let icon: string | undefined;
  for (let i = 0; i < agentEl.children.length; i++) {
    const ch = agentEl.children[i];
    const name = (ch.localName || '').toLowerCase();
    if (name === 'icon') {
      const idAttr = ch.getAttribute('id')?.trim();
      const body = (ch.textContent || '').trim();
      icon = idAttr || body || undefined;
      break;
    }
  }
  const llmRaw = (childTextDirect(agentEl, 'llm') ?? '').toLowerCase();
  let llm: AgentLlm | undefined;
  let legacyHostedUi = false;
  if (llmRaw === 'openai' || llmRaw === 'open-ai') llm = 'openAI';
  else if (
    llmRaw === 'aiassistant' ||
    llmRaw === 'hostedchat' ||
    llmRaw === 'hosted-chat' ||
    llmRaw === 'crafterq' ||
    llmRaw === 'crafter-q'
  ) {
    llm = 'openAI';
    legacyHostedUi = true;
  }
  const llmModel = childTextDirect(agentEl, 'llmModel');
  const imageModel = childTextDirect(agentEl, 'imageModel');
  const imageGenerator = childTextDirect(agentEl, 'imageGenerator');
  const openAiApiKey =
    childTextDirect(agentEl, 'openAiApiKey') ??
    childTextDirect(agentEl, 'open-ai-api-key') ??
    childTextDirect(agentEl, 'open_ai_api_key');
  const out: AgentConfig = { id: id.trim(), label: label.trim(), icon, prompts: [] };
  if (llm) out.llm = llm;
  if (llmModel) out.llmModel = llmModel;
  else if (legacyHostedUi && out.llm === 'openAI') out.llmModel = 'gpt-4o-mini';
  if (imageModel) out.imageModel = imageModel;
  if (imageGenerator) out.imageGenerator = imageGenerator;
  if (openAiApiKey?.trim()) out.openAiApiKey = openAiApiKey.trim();
  const enableToolsRaw = childTextDirect(agentEl, 'enableTools') ?? childTextDirect(agentEl, 'enable_tools');
  if (enableToolsRaw !== undefined && String(enableToolsRaw).trim() !== '') {
    const s = String(enableToolsRaw).trim().toLowerCase();
    if (s === 'false' || s === '0' || s === 'no') out.enableTools = false;
    else if (s === 'true' || s === '1' || s === 'yes') out.enableTools = true;
  }
  const expertSkills: ExpertSkillConfig[] = [];
  for (let j = 0; j < agentEl.children.length; j++) {
    const cel = agentEl.children[j];
    const cnm = (cel.localName || '').toLowerCase();
    if (cnm !== 'expertskill') continue;
    const esUrl =
      cel.getAttribute('url')?.trim() ||
      cel.getAttribute('href')?.trim() ||
      childTextDirect(cel, 'url') ||
      childTextDirect(cel, 'href');
    if (!esUrl?.trim()) continue;
    const esName =
      cel.getAttribute('name')?.trim() ||
      childTextDirect(cel, 'name') ||
      'Expert guidance';
    const esDesc =
      cel.getAttribute('description')?.trim() || childTextDirect(cel, 'description') || '';
    expertSkills.push({
      name: esName.trim(),
      url: esUrl.trim(),
      description: esDesc.trim()
    });
  }
  if (expertSkills.length) out.expertSkills = expertSkills;
  const tbcRaw =
    childTextDirect(agentEl, 'translateBatchConcurrency') ?? childTextDirect(agentEl, 'translate_batch_concurrency');
  if (tbcRaw != null && String(tbcRaw).trim() !== '') {
    const tbcN = parseInt(String(tbcRaw).trim(), 10);
    if (Number.isFinite(tbcN) && tbcN >= 1) {
      out.translateBatchConcurrency = Math.min(64, tbcN);
    }
  }
  return out;
}

/** Walk parents — avoid closest('agents') on XML DOMParser documents (can skip all agents). */
function nearestAgentsAncestorForAgent(ag: Element): Element | null {
  let el: Element | null = ag.parentElement;
  while (el) {
    const nm = (el.localName || el.tagName.replace(/^.*:/, '')).toLowerCase();
    if (nm === 'agents') return el;
    el = el.parentElement;
  }
  return null;
}

function collectAgentsInContainer(agentsContainer: Element): AgentConfig[] {
  const out: AgentConfig[] = [];
  const agentEls = agentsContainer.getElementsByTagName('agent');
  for (let i = 0; i < agentEls.length; i++) {
    const ag = agentEls[i];
    if (nearestAgentsAncestorForAgent(ag) !== agentsContainer) continue;
    const parsed = parseAgentElement(ag);
    if (parsed) out.push(parsed);
  }
  return out;
}

function collectAgentsUnderConfiguration(configurationEl: Element): AgentConfig[] {
  const agentsContainer = configurationEl.getElementsByTagName('agents')[0];
  if (!agentsContainer) return [];
  return collectAgentsInContainer(agentsContainer);
}

function mergeAgentsFromWidget(widgetEl: Element, byId: Map<string, AgentConfig>): void {
  const configs = widgetEl.getElementsByTagName('configuration');
  for (let c = 0; c < configs.length; c++) {
    const cfg = configs[c];
    if (!widgetEl.contains(cfg)) continue;
    const list = collectAgentsUnderConfiguration(cfg);
    for (const a of list) {
      byId.set(agentStableKey(a), a);
    }
  }
  const wch = widgetEl.children;
  for (let k = 0; k < wch.length; k++) {
    const cel = wch[k];
    const cname = (cel.localName || cel.tagName.replace(/^.*:/, '')).toLowerCase();
    if (cname !== 'agents') continue;
    for (const a of collectAgentsInContainer(cel)) {
      byId.set(agentStableKey(a), a);
    }
  }
}

/**
 * Walk site `ui.xml`: Helper widget and any widget that hosts `<plugin id="org.craftercms.aiassistant.studio">`.
 * Merges agents by stable key (same logic as legacy form control `main.js`).
 */
export function parseAgentsFromStudioUiXml(xmlString: string): AgentConfig[] {
  if (!xmlString || !xmlString.trim()) return [];
  const doc = new DOMParser().parseFromString(xmlString, 'text/xml');
  const parseError = doc.querySelector('parsererror');
  if (parseError) return [];

  const byId = new Map<string, AgentConfig>();

  const widgets = doc.getElementsByTagName('widget');
  for (let i = 0; i < widgets.length; i++) {
    const w = widgets[i];
    const wid = w.getAttribute('id');
    if (wid === helperWidgetId || wid === formControlWidgetId) {
      mergeAgentsFromWidget(w, byId);
    }
  }

  const plugins = doc.getElementsByTagName('plugin');
  for (let p = 0; p < plugins.length; p++) {
    const pl = plugins[p];
    const pid = pl.getAttribute('id') || pl.getAttribute('pluginId');
    if (pid !== aiAssistantStudioPluginId) continue;
    let el: Element | null = pl;
    while (el) {
      const local = String(el.localName || el.tagName.replace(/^.*:/, '')).toLowerCase();
      if (local === 'widget') {
        mergeAgentsFromWidget(el, byId);
        break;
      }
      el = el.parentElement;
    }
  }

  return Array.from(byId.values());
}

/**
 * Load chat-oriented agents for merging into the Helper / toolbar: prefers `config/studio/ai-assistant/agents.json`
 * when that file exists with at least one row **and** at least one `mode: chat` (or omitted mode) agent.
 * Otherwise parses `config/studio/ui.xml` like before.
 */
export async function fetchSiteChatAgentsForOverlay(
  siteId: string
): Promise<{ agents: AgentConfig[]; exclusive: boolean }> {
  if (!siteId) return { agents: [], exclusive: false };
  const file = await fetchCentralAgentsFile(siteId);
  if (file && file.agents.length > 0) {
    const ex = exclusiveCentralChatAgentsFromFile(file);
    if (ex) return { agents: ex, exclusive: true };
  }
  const xml = await firstValueFrom(fetchConfigurationXML(siteId, '/ui.xml', 'studio'));
  return { agents: parseAgentsFromStudioUiXml(xml ?? ''), exclusive: false };
}

/**
 * @deprecated Prefer {@link fetchSiteChatAgentsForOverlay} when you need the central-catalog vs ui.xml distinction.
 * Returns the same agent list as the overlay fetcher without the `exclusive` flag.
 */
export async function fetchAiAssistantAgentsFromSiteUi(siteId: string): Promise<AgentConfig[]> {
  const { agents } = await fetchSiteChatAgentsForOverlay(siteId);
  return agents;
}
