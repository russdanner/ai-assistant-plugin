/**
 * Central AI Assistant agent catalog: `config/studio/ai-assistant/agents.json`
 * When this file exists and contains at least one `agents[]` entry, chat agents are taken only from
 * entries with `mode: "chat"` (or omitted mode, treated as chat). Autonomous agents are taken from
 * `mode: "autonomous"`. Otherwise the plugin keeps merging agents from `ui.xml` as before.
 */
import type { AgentConfig, AgentLlm, PromptConfig } from './agentConfig';
import { normalizeEnabledBuiltInToolsRaw } from './agentConfig';
import type { AutonomousAgentDefinition } from './autonomousAssistantsConfig';
import { fetchConfigurationXML } from '@craftercms/studio-ui/services/configuration';
import { fetchContentXML, fetchItemsByPath } from '@craftercms/studio-ui/services/content';
import { firstValueFrom, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

/** Sandbox repo path — preferred read via content APIs (see {@link fetchCentralAgentsFile}). */
export const CENTRAL_AGENTS_SANDBOX_PATH = '/config/studio/ai-assistant/agents.json';

/** Relative to `config/studio/` for {@code writeConfiguration} (Studio module {@code studio}). */
export const CENTRAL_AGENTS_STUDIO_PATH = 'ai-assistant/agents.json';

export type CentralAgentMode = 'chat' | 'autonomous';

export type CentralAgentFileEntry = Record<string, unknown>;

export type CentralAgentsFile = {
  version?: number;
  agents: CentralAgentFileEntry[];
};

function asRecord(v: unknown): Record<string, unknown> | null {
  return v != null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
}

function normalizeMode(raw: unknown): CentralAgentMode {
  const s = String(raw ?? 'chat').trim().toLowerCase();
  return s === 'autonomous' ? 'autonomous' : 'chat';
}

/**
 * Parses `prompts` from `agents.json` / central catalog into {@link PromptConfig} (drops entries with no userText).
 */
export function parsePrompts(raw: unknown): PromptConfig[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  const out: PromptConfig[] = [];
  for (const p of raw) {
    if (typeof p === 'string') {
      const t = p.trim();
      if (t) out.push({ userText: t });
      continue;
    }
    const o = asRecord(p);
    if (!o) continue;
    const userText = String(o.userText ?? o.text ?? o.prompt ?? '').trim();
    if (!userText) continue;
    const additionalContext =
      typeof o.additionalContext === 'string' && o.additionalContext.trim() ? o.additionalContext.trim() : undefined;
    const omitTools =
      o.omitTools === true ||
      o.omitTools === 1 ||
      String(o.omitTools ?? '').trim().toLowerCase() === 'true';
    out.push({ userText, ...(additionalContext ? { additionalContext } : {}), ...(omitTools ? { omitTools: true } : {}) });
  }
  return out.length ? out : undefined;
}

/** One row per array element for the Studio catalog editor (keeps empty userText while typing). */
export function rawPromptsToEditorRows(raw: unknown): PromptConfig[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((item): PromptConfig => {
    if (typeof item === 'string') {
      return { userText: item, additionalContext: '' };
    }
    const o = asRecord(item);
    if (!o) return { userText: '', additionalContext: '' };
    const userText = String(o.userText ?? o.text ?? o.prompt ?? '');
    const acRaw = typeof o.additionalContext === 'string' ? o.additionalContext : '';
    const omitTools =
      o.omitTools === true ||
      o.omitTools === 1 ||
      String(o.omitTools ?? '').trim().toLowerCase() === 'true';
    const row: PromptConfig = { userText: userText, additionalContext: acRaw };
    if (!acRaw) delete (row as { additionalContext?: string }).additionalContext;
    if (omitTools) row.omitTools = true;
    return row;
  });
}

/** Persistable `prompts` array for agents.json (skips blank chip labels). */
export function serializeCentralCatalogPrompts(rows: PromptConfig[]): unknown[] | undefined {
  const out: unknown[] = [];
  for (const p of rows) {
    const userText = (p.userText || '').trim();
    if (!userText) continue;
    const o: Record<string, unknown> = { userText };
    const ac = (p.additionalContext || '').trim();
    if (ac) o.additionalContext = ac;
    if (p.omitTools === true) o.omitTools = true;
    out.push(o);
  }
  return out.length ? out : undefined;
}

function parseExpertSkills(raw: unknown): AgentConfig['expertSkills'] {
  if (!Array.isArray(raw) || !raw.length) return undefined;
  const skills: NonNullable<AgentConfig['expertSkills']> = [];
  for (const e of raw) {
    const o = asRecord(e);
    if (!o) continue;
    const url = String(o.url ?? o.href ?? '').trim();
    if (!url) continue;
    skills.push({
      name: typeof o.name === 'string' ? o.name.trim() : undefined,
      url,
      description: typeof o.description === 'string' ? o.description.trim() : undefined
    });
  }
  return skills.length ? skills : undefined;
}

/** True when the site file is present, parses, and declares an `agents` array (even empty). */
export function isCentralAgentsFileShape(v: unknown): v is CentralAgentsFile {
  const o = asRecord(v);
  if (!o || !Array.isArray(o.agents)) return false;
  return true;
}

export function entryToChatAgent(entry: CentralAgentFileEntry): AgentConfig | null {
  const mode = normalizeMode(entry.mode);
  if (mode === 'autonomous') return null;
  const id = String(entry.crafterQAgentId ?? entry.id ?? '').trim();
  const label = String(entry.label ?? entry.name ?? '').trim();
  if (!label) return null;
  const llmRaw = String(entry.llm ?? '').trim();
  let llm: AgentLlm | undefined;
  let legacyHostedLlm = false;
  if (llmRaw) {
    const low = llmRaw.toLowerCase();
    if (low === 'openai' || low === 'open-ai') llm = 'openAI';
    else if (
      low === 'aiassistant' ||
      low === 'hostedchat' ||
      low === 'hosted-chat' ||
      low === 'crafterq' ||
      low === 'crafter-q'
    ) {
      llm = 'openAI';
      legacyHostedLlm = true;
    } else llm = llmRaw;
  }
  const enableToolsRaw = entry.enableTools ?? entry.enable_tools;
  let enableTools: boolean | undefined;
  if (enableToolsRaw !== undefined && String(enableToolsRaw).trim() !== '') {
    const s = String(enableToolsRaw).trim().toLowerCase();
    if (s === 'false' || s === '0' || s === 'no') enableTools = false;
    else if (s === 'true' || s === '1' || s === 'yes') enableTools = true;
  }
  const icon = typeof entry.icon === 'string' ? entry.icon.trim() : undefined;
  const prompts = parsePrompts(entry.prompts);
  const expertSkills = parseExpertSkills(entry.expertSkills ?? entry.expertSkill);
  const out: AgentConfig = { id, label, ...(icon ? { icon } : {}), ...(prompts ? { prompts } : {}) };
  if (llm) out.llm = llm;
  const lmTrim = typeof entry.llmModel === 'string' ? entry.llmModel.trim() : '';
  if (lmTrim) out.llmModel = lmTrim;
  else if (legacyHostedLlm && out.llm === 'openAI') out.llmModel = 'gpt-4o-mini';
  if (typeof entry.imageModel === 'string' && entry.imageModel.trim()) out.imageModel = entry.imageModel.trim();
  if (typeof entry.imageGenerator === 'string' && entry.imageGenerator.trim())
    out.imageGenerator = entry.imageGenerator.trim();
  if (typeof entry.openAiApiKey === 'string' && entry.openAiApiKey.trim()) out.openAiApiKey = entry.openAiApiKey.trim();
  if (enableTools !== undefined) out.enableTools = enableTools;
  const popRaw = entry.openAsPopup;
  if (popRaw === true || String(popRaw ?? '').trim().toLowerCase() === 'true') out.openAsPopup = true;
  else if (popRaw === false || String(popRaw ?? '').trim().toLowerCase() === 'false') out.openAsPopup = false;
  if (expertSkills) out.expertSkills = expertSkills;
  const tbc = entry.translateBatchConcurrency ?? entry.translate_batch_concurrency;
  if (tbc != null) {
    const n = parseInt(String(tbc).trim(), 10);
    if (Number.isFinite(n) && n >= 1) out.translateBatchConcurrency = Math.min(64, n);
  }
  const enabledBuiltIn = normalizeEnabledBuiltInToolsRaw(entry.enabledBuiltInTools ?? entry.enabled_built_in_tools);
  if (enabledBuiltIn?.length) out.enabledBuiltInTools = enabledBuiltIn;
  return out;
}

export function entryToAutonomousDefinition(entry: CentralAgentFileEntry): AutonomousAgentDefinition | null {
  const mode = normalizeMode(entry.mode);
  if (mode !== 'autonomous') return null;
  const name = String(entry.name ?? entry.label ?? '').trim();
  if (!name) return null;
  const schedule = String(entry.schedule ?? '0 0 * * * ?').trim();
  const prompt = String(entry.prompt ?? '').trim();
  const scopeRaw = String(entry.scope ?? 'project').trim().toLowerCase();
  const scope =
    scopeRaw === 'user' || scopeRaw === 'role' || scopeRaw === 'project' ? scopeRaw : ('project' as const);
  let llm = String(entry.llm ?? 'openAI').trim();
  const lz = llm.toLowerCase();
  if (
    lz === 'crafterq' ||
    lz === 'crafter-q' ||
    lz === 'aiassistant' ||
    lz === 'hostedchat' ||
    lz === 'hosted-chat'
  ) {
    llm = 'openAI';
  }
  const llmModel = String(entry.llmModel ?? 'gpt-4o-mini').trim();
  const imageModel = entry.imageModel != null ? String(entry.imageModel).trim() : undefined;
  const imageGenerator =
    entry.imageGenerator != null && String(entry.imageGenerator).trim()
      ? String(entry.imageGenerator).trim()
      : undefined;
  const openAiApiKey = entry.openAiApiKey != null ? String(entry.openAiApiKey).trim() : undefined;
  const manageOtherAgentsHumanTasks =
    entry.manageOtherAgentsHumanTasks === true ||
    String(entry.manageOtherAgentsHumanTasks ?? '').toLowerCase() === 'true';
  const startAutomatically =
    entry.startAutomatically === false || String(entry.startAutomatically ?? '').toLowerCase() === 'false'
      ? false
      : undefined;
  const stopOnFailure =
    entry.stopOnFailure === false || String(entry.stopOnFailure ?? '').toLowerCase() === 'false' ? false : undefined;
  const expertSkills = parseExpertSkills(entry.expertSkills ?? entry.expertSkill);
  const enabledBuiltIn = normalizeEnabledBuiltInToolsRaw(entry.enabledBuiltInTools ?? entry.enabled_built_in_tools);
  const out: AutonomousAgentDefinition = {
    name,
    schedule: schedule || '0 0 * * * ?',
    prompt,
    scope,
    llm,
    llmModel,
    ...(imageModel ? { imageModel } : {}),
    ...(imageGenerator ? { imageGenerator } : {}),
    ...(openAiApiKey ? { openAiApiKey } : {}),
    ...(manageOtherAgentsHumanTasks ? { manageOtherAgentsHumanTasks: true } : {}),
    ...(startAutomatically === false ? { startAutomatically: false } : {}),
    ...(stopOnFailure === false ? { stopOnFailure: false } : {})
  };
  if (expertSkills) {
    out.expertSkills = expertSkills as AutonomousAgentDefinition['expertSkills'];
  }
  if (enabledBuiltIn?.length) {
    out.enabledBuiltInTools = enabledBuiltIn;
  }
  return out;
}

export function catalogChatAgents(file: CentralAgentsFile): AgentConfig[] {
  return file.agents.map((e) => entryToChatAgent(e)).filter(Boolean) as AgentConfig[];
}

export function catalogAutonomousAgents(file: CentralAgentsFile): AutonomousAgentDefinition[] {
  return file.agents.map((e) => entryToAutonomousDefinition(e)).filter(Boolean) as AutonomousAgentDefinition[];
}

/**
 * When the catalog file exists and has at least one agent row, chat agents are sourced **only** from
 * `mode: chat` entries (including omitted mode). If the file has only autonomous rows, returns `null` so
 * callers fall back to `ui.xml` for interactive chat agents.
 */
export function exclusiveCentralChatAgentsFromFile(file: CentralAgentsFile): AgentConfig[] | null {
  if (!file.agents.length) return null;
  const chat = catalogChatAgents(file);
  return chat.length ? chat : null;
}

function parseCentralAgentsFromContentPayload(raw: unknown): CentralAgentsFile | null {
  if (raw == null) return null;
  let data: unknown;
  if (typeof raw === 'string') {
    const s = raw.trim();
    if (!s) return null;
    try {
      data = JSON.parse(s);
    } catch {
      return null;
    }
  } else if (typeof raw === 'object') {
    data = raw;
  } else {
    return null;
  }
  if (!isCentralAgentsFileShape(data)) return null;
  return { version: typeof data.version === 'number' ? data.version : 1, agents: data.agents as CentralAgentFileEntry[] };
}

function unwrapConfigurationEnvelope(raw: unknown): unknown {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const r = raw as Record<string, unknown>;
    if (typeof r.content === 'string') return r.content;
    if (typeof r.configuration === 'string') return r.configuration;
  }
  return raw;
}

/**
 * Loads the central catalog so reads match {@code write_configuration} writes.
 *
 * **Important:** {@code fetchConfigurationJSON} runs XML `deserialize` on the response body — that is wrong for
 * `.json` files and yields garbage / empty objects, so reloads looked like saves “did nothing”. We read the sandbox
 * file via content APIs first (same pattern as {@code fetchStudioUiConfigAsync}), then fall back to raw
 * {@code get_configuration} + {@code JSON.parse}.
 */
export async function fetchCentralAgentsFile(siteId: string): Promise<CentralAgentsFile | null> {
  if (!siteId) return null;
  try {
    const listings = (await firstValueFrom(
      fetchItemsByPath(siteId, [CENTRAL_AGENTS_SANDBOX_PATH], { preferContent: true })
    )) as unknown as { missingItems?: string[] };

    const missing = Array.isArray(listings.missingItems) && listings.missingItems.includes(CENTRAL_AGENTS_SANDBOX_PATH);
    if (!missing) {
      const fromSandbox = await firstValueFrom(
        fetchContentXML(siteId, CENTRAL_AGENTS_SANDBOX_PATH, { lock: false }).pipe(catchError(() => of(null)))
      );
      let blob: unknown = fromSandbox;
      blob = unwrapConfigurationEnvelope(blob);
      const parsed = parseCentralAgentsFromContentPayload(blob);
      if (parsed) return parsed;
    }

    const confStr = await firstValueFrom(fetchConfigurationXML(siteId, CENTRAL_AGENTS_STUDIO_PATH, 'studio'));
    if (typeof confStr === 'string' && confStr.trim()) {
      const trimmed = confStr.trim();
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        let raw: unknown = JSON.parse(trimmed);
        raw = unwrapConfigurationEnvelope(raw);
        return parseCentralAgentsFromContentPayload(raw);
      }
    }
    return null;
  } catch {
    return null;
  }
}

export function defaultCentralAgentsFile(): CentralAgentsFile {
  return {
    version: 1,
    agents: [
      {
        mode: 'chat',
        crafterQAgentId: '019c7237-478b-7f98-9a5c-87144c3fb010',
        label: 'Authoring Assistant',
        icon: '@mui/icons-material/AutoAwesomeRounded',
        llm: 'openAI',
        llmModel: 'gpt-4o-mini',
        imageModel: 'gpt-image-1',
        enableTools: true,
        prompts: [
          { userText: 'What can you help me with?' },
          {
            userText: 'Summarize this page',
            additionalContext:
              'Use bullet points. Prefer the current form/page context when summarizing. Keep under 200 words.'
          }
        ]
      },
      {
        mode: 'autonomous',
        name: 'Prototype agent',
        schedule: '0 * * * * ?',
        prompt: 'You are an autonomous assistant. Reply with JSON only as instructed by the server.',
        scope: 'project',
        llm: 'openAI',
        llmModel: 'gpt-4o-mini',
        imageModel: 'gpt-image-1',
        manageOtherAgentsHumanTasks: false
      }
    ]
  };
}
