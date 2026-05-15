/**
 * Agent configuration as defined in ui.xml <configuration><agents><agent>...</agent></agents></configuration>
 */
/**
 * Studio stream `llm` value. Common: `openAI`, `claude`, `xAI`, `deepSeek`, `llama`, `gemini`,
 * or `script:<id>` (see server `StudioAiLlmKind`). Hosted CrafterQ chat is not supported — configure a real provider.
 */
export type AgentLlm = string;

/** Optional per-agent markdown RAG source (OpenAI path); configured in ui.xml as `<expertSkill>` children. */
export interface ExpertSkillConfig {
  /** Display name for the system prompt table. */
  name?: string;
  /** Public http(s) URL whose body is treated as UTF-8 markdown for chunking + embeddings. */
  url: string;
  /** When to call QueryExpertGuidance for this skill. */
  description?: string;
}

export interface AgentConfig {
  /**
   * CrafterQ SaaS agent UUID (and stream `agentId`), from ui.xml **{@code <crafterQAgentId>}** / widget JSON **`crafterQAgentId`**.
   * With **label**, used for merge/dedupe and form toggles. For **OpenAI** without CrafterQ calls, may be omitted (empty).
   */
  id: string;
  label: string;
  icon?: string;
  /**
   * `true` = floating dialog. `false` or omitted = Experience Builder right (ICE) tools panel
   * (edit mode on). Default is panel. XML / JSON: `<openAsPopup>true</openAsPopup>` or `"openAsPopup": true`.
   */
  openAsPopup?: boolean;
  /** From `<llm>openAI</llm>`, `<llm>claude</llm>`, `<llm>script:…</llm>`, etc. in widget configuration. Omitted unless set in ui.xml; stream/chat then omit POST `llm` unless the server merges it from `/ui.xml` — missing `llm` after merge is **400**. Prefer setting explicitly. */
  llm?: AgentLlm;
  /**
   * When false (ui.xml `<enableTools>false</enableTools>`), the plugin sends `enableTools: false` so OpenAI
   * requests omit CMS function tools. Omitted or true: default (tools on for OpenAI).
   */
  enableTools?: boolean;
  /**
   * Optional subset of built-in CMS tool wire names (e.g. `GetContent`, `WriteContent`). Forwarded on stream POST as
   * `enabledBuiltInTools` when non-empty. Include `mcp:*` to allow all MCP tools. Omitted = full catalog (subject to site `tools.json`).
   */
  enabledBuiltInTools?: string[];
  /** Optional provider model id when `llm` is `openAI` (e.g. `gpt-4o-mini`). ui.xml **`<llmModel>`** / JSON **`llmModel`**. */
  llmModel?: string;
  /** OpenAI Images API model when llm is openAI (e.g. gpt-image-1). ui.xml **`<imageModel>`** / JSON **`imageModel`** — no JVM fallback. */
  imageModel?: string;
  /**
   * GenerateImage backend: ui.xml **`<imageGenerator>`** / JSON **`imageGenerator`**. Blank = built-in GenerateImage HTTP wire when configured; values **none**, **off**, or **disabled** turn the tool off; **script:{id}** runs `/scripts/aiassistant/imagegen/{id}/generate.groovy`.
   */
  imageGenerator?: string;
  /**
   * Optional OpenAI API key from ui.xml — **not recommended** (exposed in Studio config / sent on requests).
   * Used only when `OPENAI_API_KEY` / JVM keys are unset. For local testing.
   */
  openAiApiKey?: string;
  prompts?: PromptConfig[];
  /** Markdown URLs for server-side QueryExpertGuidance (Spring AI vector store); OpenAI agents only. */
  expertSkills?: ExpertSkillConfig[];
  /**
   * Parallel **TranslateContentBatch** workers when the model omits **maxConcurrency** (1–64).
   * ui.xml: **`<translateBatchConcurrency>25</translateBatchConcurrency>`** (or `translate_batch_concurrency`). Omitted → server default **25**.
   */
  translateBatchConcurrency?: number;
}

/**
 * Stable key for matching agents between ui.xml, form field properties, and the form-control UI.
 * When both `id` and `label` are set, uses a composite key so multiple `<agent>` rows with the **same**
 * backend `id` (e.g. same CrafterQ UUID, different labels) stay distinct — otherwise merging collapses them.
 */
export function agentStableKey(a: Pick<AgentConfig, 'id' | 'label'>): string {
  const id = (a.id || '').trim();
  const label = (a.label || '').trim();
  if (id && label) return `${id}\u001e${label}`;
  if (id) return id;
  return label || 'agent';
}

/**
 * Form engine control property `name` for “show this agent in the panel”.
 * Must stay in sync with `cqAgentPropName` in `sources/control/ai-assistant/main.js`.
 */
export function agentFormPropertyName(a: Pick<AgentConfig, 'id' | 'label'>): string {
  const key = agentStableKey(a);
  const s = key.replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  return 'cqShow_' + s;
}

/** Label used when Studio JSON omits `<label>` (see {@link normalizeAgent}); not a real product name. */
export const CRAFTERQ_AGENT_LABEL_PLACEHOLDER = 'CrafterQ';

/** Default **`<crafterQAgentId>`** / label pair from `craftercms-plugin.yaml` sample Helper `<agent>` (CrafterQ cloud). */
export const CRAFTERQ_PLUGIN_SAMPLE_AGENT_ID = '019c7237-478b-7f98-9a5c-87144c3fb010';
export const CRAFTERQ_PLUGIN_SAMPLE_AGENT_LABEL = 'CrafterQ content';

function shouldOverlayLabelFromSite(agent: AgentConfig, ui: AgentConfig): boolean {
  const u = (ui.label || '').trim();
  if (!u) return false;
  const a = (agent.label || '').trim();
  if (!a) return true;
  if (a === CRAFTERQ_AGENT_LABEL_PLACEHOLDER && u !== a) return true;
  return false;
}

function shouldOverlayIconFromSite(agent: AgentConfig, ui: AgentConfig): boolean {
  return Boolean((ui.icon || '').trim() && !(agent.icon || '').trim());
}

function shouldOverlayPromptsFromSite(agent: AgentConfig, ui: AgentConfig): boolean {
  return Boolean(Array.isArray(ui.prompts) && ui.prompts.length > 0 && (!agent.prompts || agent.prompts.length === 0));
}

/**
 * Merge site `ui.xml` agents onto widget JSON agents.
 * When the widget row has no **{@code <crafterQAgentId>}**, Studio often omits label/icon/prompts so {@link normalizeAgent}
 * fills a placeholder label — stable keys then diverge from `/ui.xml` and the Helper menu shows duplicates.
 * Match: stable key, then id; when id is empty and site lists exactly one agent, treat that row as the overlay.
 */
export function mergeAgentsWithSiteUiXmlOverlay(fromWidget: AgentConfig[], fromUiXml: AgentConfig[]): AgentConfig[] {
  if (!fromUiXml.length || !fromWidget.length) return fromWidget;
  return fromWidget.map((agent) => {
    const key = agentStableKey(agent);
    const byKey = fromUiXml.find((u) => agentStableKey(u) === key);
    const idTrim = (agent.id || '').trim();
    let ui = byKey;
    if (!ui && idTrim) {
      ui = fromUiXml.find((u) => (u.id || '').trim() === idTrim);
    }
    if (!ui && !idTrim && fromUiXml.length === 1) {
      ui = fromUiXml[0];
    }
    if (!ui) return agent;
    return {
      ...agent,
      ...(shouldOverlayLabelFromSite(agent, ui) ? { label: (ui.label || '').trim() } : {}),
      ...(shouldOverlayIconFromSite(agent, ui) ? { icon: ui.icon } : {}),
      ...(shouldOverlayPromptsFromSite(agent, ui) ? { prompts: ui.prompts } : {}),
      ...(ui.id?.trim() && !(agent.id || '').trim() ? { id: ui.id.trim() } : {}),
      ...(ui.enableTools !== undefined ? { enableTools: ui.enableTools } : {}),
      ...(ui.llm !== undefined && agent.llm === undefined ? { llm: ui.llm } : {}),
      ...(typeof ui.llmModel === 'string' &&
      ui.llmModel.trim() &&
      !(agent.llmModel || '').trim()
        ? { llmModel: ui.llmModel.trim() }
        : {}),
      ...(typeof ui.imageModel === 'string' &&
      ui.imageModel.trim() &&
      !(agent.imageModel || '').trim()
        ? { imageModel: ui.imageModel.trim() }
        : {}),
      ...(typeof ui.imageGenerator === 'string' &&
      ui.imageGenerator.trim() &&
      !(agent.imageGenerator || '').trim()
        ? { imageGenerator: ui.imageGenerator.trim() }
        : {}),
      ...(ui.openAiApiKey !== undefined && agent.openAiApiKey === undefined ? { openAiApiKey: ui.openAiApiKey } : {}),
      ...(ui.openAsPopup !== undefined && agent.openAsPopup === undefined ? { openAsPopup: ui.openAsPopup } : {}),
      ...(Array.isArray(ui.expertSkills) &&
      ui.expertSkills.length > 0 &&
      (!agent.expertSkills || agent.expertSkills.length === 0)
        ? { expertSkills: ui.expertSkills }
        : {}),
      ...(ui.translateBatchConcurrency != null && Number.isFinite(ui.translateBatchConcurrency)
        ? { translateBatchConcurrency: ui.translateBatchConcurrency }
        : {}),
      ...(Array.isArray(ui.enabledBuiltInTools) &&
      ui.enabledBuiltInTools.length > 0 &&
      (!agent.enabledBuiltInTools || agent.enabledBuiltInTools.length === 0)
        ? { enabledBuiltInTools: [...ui.enabledBuiltInTools] }
        : {})
    };
  });
}

/** Keep first occurrence per {@link agentStableKey} (order preserved). */
export function dedupeAgentsByStableKey(agents: AgentConfig[]): AgentConfig[] {
  const m = new Map<string, AgentConfig>();
  for (const a of agents) {
    const k = agentStableKey(a);
    if (!m.has(k)) m.set(k, a);
  }
  return Array.from(m.values());
}

/**
 * Remove (a) JSON placeholder rows (label exactly {@link CRAFTERQ_AGENT_LABEL_PLACEHOLDER}) whenever another agent
 * has a non-placeholder label (Studio may still attach a non-sample id to the fallback row), and
 * (b) the plugin-install sample agent (`{@link CRAFTERQ_PLUGIN_SAMPLE_AGENT_ID}` + {@link CRAFTERQ_PLUGIN_SAMPLE_AGENT_LABEL}})
 * when at least one other row looks author-defined — typical duplicate Helper menu (Studio merges blueprint + site `ui.xml`).
 */
export function dropPlaceholderAgentsWhenRicherMatchesExist(agents: AgentConfig[]): AgentConfig[] {
  const deduped = dedupeAgentsByStableKey(agents);
  if (deduped.length <= 1) return deduped;

  const hasRicher = deduped.some((a) => {
    const lab = (a.label || '').trim();
    if (!lab) return false;
    if (lab === CRAFTERQ_AGENT_LABEL_PLACEHOLDER) return false;
    if (lab === CRAFTERQ_PLUGIN_SAMPLE_AGENT_LABEL) return false;
    return true;
  });
  if (!hasRicher) return deduped;

  return deduped.filter((a) => {
    const id = (a.id || '').trim();
    const label = (a.label || '').trim();
    if (hasRicher && label === CRAFTERQ_AGENT_LABEL_PLACEHOLDER) return false;
    if (id === CRAFTERQ_PLUGIN_SAMPLE_AGENT_ID && label === CRAFTERQ_PLUGIN_SAMPLE_AGENT_LABEL) return false;
    return true;
  });
}

export type PromptConfig = {
  userText: string;
  additionalContext?: string;
  /** When true, chip-triggered request sends {@code omitTools} — OpenAI omits CMS tools for that LLM call (copy/generation focus). */
  omitTools?: boolean;
};

const DEFAULT_AGENT_ID = '';

/** Fallback when no config or parsing fails — one toolbar/menu row so click always has a target. */
const DEFAULT_AGENT: AgentConfig = {
  id: CRAFTERQ_PLUGIN_SAMPLE_AGENT_ID,
  label: 'Studio AI Assistant',
  llm: 'openAI',
  llmModel: 'gpt-4o-mini',
  prompts: []
};

/** Fallback list so Helper click / agent menus always have at least one entry (see {@link getAgentsFromConfiguration}). */
export const DEFAULT_AGENTS: AgentConfig[] = [DEFAULT_AGENT];

/**
 * Default agents for the Form Engine “CrafterQ assistant” control when no agents come from ui.xml / widget config.
 * Keep IDs in sync with `sources/control/ai-assistant/main.js` (`CRAFTERQ_AGENT_CATALOG`).
 */
export const DEFAULT_FORM_CONTROL_AGENTS: AgentConfig[] = [
  {
    id: '019c7237-478b-7f98-9a5c-87144c3fb010',
    label: 'Content assistant',
    llm: 'openAI',
    llmModel: 'gpt-4o-mini'
  }
];

/**
 * Normalize agents from widget configuration.
 * Falls back to DEFAULT_AGENTS when no config or no agents found so the UI always works.
 */
export function getAgentsFromConfiguration(configuration: unknown): AgentConfig[] {
  const config = configuration != null && typeof configuration === 'object' ? (configuration as Record<string, unknown>) : null;
  if (!config) return DEFAULT_AGENTS;

  // Prefer nested configuration.agents; fallback to top-level agents (e.g. if config is spread onto props)
  let agentsRaw: unknown = config.agents;
  if (agentsRaw == null && config.configuration != null && typeof config.configuration === 'object') {
    const inner = config.configuration as Record<string, unknown>;
    agentsRaw = inner.agents ?? (inner.configuration != null && typeof inner.configuration === 'object' ? (inner.configuration as Record<string, unknown>).agents : undefined);
  }
  if (agentsRaw == null && config.configuration != null && typeof config.configuration === 'object') {
    const inner = config.configuration as Record<string, unknown>;
    if (inner.configuration != null && typeof inner.configuration === 'object') {
      const deep = (inner.configuration as Record<string, unknown>).agents;
      if (deep != null) agentsRaw = deep;
    }
  }
  if (agentsRaw == null) {
    const singleAgent = config.agent ?? (config.configuration && typeof config.configuration === 'object' ? (config.configuration as Record<string, unknown>).agent : undefined);
    if (singleAgent != null) {
      const one = normalizeAgentOrWrapped(singleAgent);
      if (one) return [one];
    }
    return DEFAULT_AGENTS;
  }

  // Direct array
  if (Array.isArray(agentsRaw)) {
    const list = agentsRaw.map((a) => normalizeAgentOrWrapped(a)).filter(Boolean) as AgentConfig[];
    return list.length > 0 ? list : DEFAULT_AGENTS;
  }

  // Nested: { agent: [ {...}, {...} ] } or { agent: { "0": {...}, "1": {...} } } — same pattern as uigoodies CopyCurrentPageUrl (Object.keys(environments.label))
  if (typeof agentsRaw === 'object' && agentsRaw !== null) {
    const obj = agentsRaw as Record<string, unknown>;
    const listOrSingle = obj.agent;
    if (listOrSingle != null) {
      const arr = Array.isArray(listOrSingle)
        ? listOrSingle
        : typeof listOrSingle === 'object' && listOrSingle !== null
          ? Object.values(listOrSingle as Record<string, unknown>)
          : [];
      const list = arr.map((a) => normalizeAgentOrWrapped(a)).filter(Boolean) as AgentConfig[];
      if (list.length > 0) return list;
    }
  }
  return DEFAULT_AGENTS;
}

/** Normalize one item that might be `{ agent: { crafterQAgentId, label, ... } }` or plain `{ crafterQAgentId, label, ... }`. */
function normalizeAgentOrWrapped(a: unknown): AgentConfig | null {
  if (!a || typeof a !== 'object') return null;
  const o = a as Record<string, unknown>;
  const agent = o.agent && typeof o.agent === 'object' ? (o.agent as Record<string, unknown>) : o;
  return normalizeAgent(agent);
}

/**
 * Normalize one agent from config: **crafterQAgentId**, **label**, optional **icon**, **prompts**, etc.
 */
export function normalizeExpertSkillsRaw(raw: unknown): ExpertSkillConfig[] | undefined {
  if (raw == null) return undefined;
  const rows: ExpertSkillConfig[] = [];
  const pushFromRecord = (r: Record<string, unknown>) => {
    const url = extractString(r.url) ?? extractString(r.href);
    if (!url?.trim()) return;
    rows.push({
      name: extractString(r.name) ?? 'Expert guidance',
      url: url.trim(),
      description: extractString(r.description) ?? ''
    });
  };
  if (Array.isArray(raw)) {
    for (const item of raw) {
      if (!item || typeof item !== 'object') continue;
      pushFromRecord(item as Record<string, unknown>);
    }
  } else if (typeof raw === 'object') {
    const o = raw as Record<string, unknown>;
    const nested = o.expertSkill;
    if (Array.isArray(nested)) {
      for (const item of nested) {
        if (!item || typeof item !== 'object') continue;
        pushFromRecord(item as Record<string, unknown>);
      }
    } else if (nested && typeof nested === 'object') {
      pushFromRecord(nested as Record<string, unknown>);
    }
  }
  return rows.length ? rows : undefined;
}

export function normalizeEnabledBuiltInToolsRaw(raw: unknown): string[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  const out: string[] = [];
  for (const x of raw) {
    const s = String(x ?? '').trim();
    if (s) out.push(s);
  }
  return out.length ? out : undefined;
}

function normalizeAgent(a: unknown): AgentConfig | null {
  if (!a || typeof a !== 'object') return null;
  const o = a as Record<string, unknown>;
  const id = extractString(o.crafterQAgentId) ?? DEFAULT_AGENT_ID;
  const label = extractString(o.label) ?? CRAFTERQ_AGENT_LABEL_PLACEHOLDER;
  if (!label.trim()) return null;
  let icon: string | undefined;
  const iconVal = o.icon;
  if (typeof iconVal === 'string') icon = iconVal;
  else if (iconVal && typeof iconVal === 'object') {
    const iconObj = iconVal as Record<string, unknown>;
    icon = typeof iconObj.id === 'string' ? iconObj.id : typeof iconObj['@_id'] === 'string' ? (iconObj['@_id'] as string) : undefined;
  }
  const prompts = normalizePrompts(o.prompts);
  const llmStr = extractString(o.llm)?.trim();
  let llm: AgentLlm | undefined;
  let legacyHostedLlm = false;
  if (llmStr) {
    const low = llmStr.toLowerCase();
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
    } else llm = llmStr;
  }
  const llmModel = extractString(o.llmModel);
  const imageModel = extractString(o.imageModel);
  const imageGenerator =
    extractString(o.imageGenerator) ??
    extractString(o['image-generator']) ??
    extractString(o.image_generator);
  const openAiApiKey =
    extractString(o.openAiApiKey) ??
    extractString(o['open-ai-api-key']) ??
    extractString(o.open_ai_api_key);
  const out: AgentConfig = { id: id.trim(), label, icon, prompts };
  if (llm) out.llm = llm;
  if (llmModel) out.llmModel = llmModel;
  else if (legacyHostedLlm && out.llm === 'openAI') out.llmModel = 'gpt-4o-mini';
  if (imageModel) out.imageModel = imageModel;
  if (imageGenerator) out.imageGenerator = imageGenerator;
  if (openAiApiKey?.trim()) out.openAiApiKey = openAiApiKey.trim();
  const openAsPopup = extractBooleanFromRecord(o, 'openAsPopup', 'open_as_popup', 'OpenAsPopup');
  if (openAsPopup !== undefined) out.openAsPopup = openAsPopup;
  const enableTools = extractBooleanFromRecord(o, 'enableTools', 'enable_tools');
  if (enableTools !== undefined) out.enableTools = enableTools;
  const expertSkills = normalizeExpertSkillsRaw(o.expertSkills) ?? normalizeExpertSkillsRaw(o.expertSkill);
  if (expertSkills) out.expertSkills = expertSkills;
  const translateBatchConcurrency = extractPositiveInt(
    o,
    1,
    64,
    'translateBatchConcurrency',
    'translate_batch_concurrency',
    'TranslateBatchConcurrency'
  );
  if (translateBatchConcurrency != null) out.translateBatchConcurrency = translateBatchConcurrency;
  const enabledBuiltIn = normalizeEnabledBuiltInToolsRaw(o.enabledBuiltInTools ?? o.enabled_built_in_tools);
  if (enabledBuiltIn?.length) out.enabledBuiltInTools = enabledBuiltIn;
  return out;
}

/** Integer in inclusive range; undefined if missing or invalid. */
export function extractPositiveInt(
  o: Record<string, unknown>,
  min: number,
  max: number,
  ...keys: string[]
): number | undefined {
  for (const k of keys) {
    const v = o[k];
    if (v == null) continue;
    let n: number;
    if (typeof v === 'number' && Number.isFinite(v)) n = Math.floor(v);
    else {
      const s = extractString(v);
      if (!s) continue;
      n = parseInt(s, 10);
    }
    if (!Number.isFinite(n)) continue;
    if (n < min || n > max) continue;
    return n;
  }
  return undefined;
}

function extractBooleanFromRecord(o: Record<string, unknown>, ...keys: string[]): boolean | undefined {
  for (const k of keys) {
    const v = o[k];
    if (v === true) return true;
    if (v === false) return false;
    const s = extractString(v)?.toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no') return false;
  }
  return undefined;
}

/** First matching optional boolean on a Studio widget/configuration object (JSON props). */
export function readOptionalBooleanFromConfiguration(o: unknown, ...keys: string[]): boolean | undefined {
  if (o == null || typeof o !== 'object') return undefined;
  return extractBooleanFromRecord(o as Record<string, unknown>, ...keys);
}

function extractString(v: unknown): string | undefined {
  if (v == null) return undefined;
  if (typeof v === 'string') return v.trim() || undefined;
  if (Array.isArray(v)) {
    const first = v[0];
    if (first != null) return extractString(first);
    return undefined;
  }
  if (typeof v === 'object') {
    const o = v as Record<string, unknown>;
    const candidates = [
      o.$text,
      o.value,
      o['#text'],
      o.__text,
      o._,
      o['@_id'],
      o.text,
      o.content
    ];
    for (const c of candidates) {
      if (typeof c === 'string') return c.trim() || undefined;
    }
    for (const c of candidates) {
      if (c != null && typeof c === 'object') {
        const s = extractString(c);
        if (s) return s;
      }
    }
    const values = Object.values(o);
    if (values.length > 0) {
      const first = extractString(values[0]);
      if (first) return first;
    }
  }
  return undefined;
}

function extractAdditionalContextField(o: Record<string, unknown>): string | undefined {
  const ctx =
    extractString(o.additionalContext) ??
    extractString(o['additional-context']) ??
    extractString(o.context) ??
    extractString(o.AdditionalContext) ??
    extractString(o.additional_context);
  return ctx?.trim() ? ctx.trim() : undefined;
}

/** Quick-action chips should be short labels; long / multiline "userText" is almost always mis-parsed additional context. */
const MAX_QUICK_PROMPT_LABEL_CHARS = 100;
const MIN_MULTILINE_QUICK_PROMPT_CHARS = 48;

function isLikelyMisplacedContextPrompt(p: PromptConfig): boolean {
  const t = (p.userText || '').trim();
  if (!t) return false;
  if (t.length > MAX_QUICK_PROMPT_LABEL_CHARS) return true;
  if (t.includes('\n') && t.length >= MIN_MULTILINE_QUICK_PROMPT_CHARS) return true;
  return false;
}

/**
 * When Studio/XML produces two <prompt> entries (or flattens context into #text), the second
 * entry often becomes a second "button" with the full context as userText. Fold those into the
 * previous prompt's additionalContext instead.
 */
function mergeMisplacedContextPrompts(prompts: PromptConfig[]): PromptConfig[] {
  if (prompts.length <= 1) return prompts;
  const out: PromptConfig[] = [];
  for (const p of prompts) {
    if (isLikelyMisplacedContextPrompt(p) && out.length > 0) {
      const prev = out[out.length - 1];
      const body = (p.userText || '').trim();
      const extra = (p.additionalContext || '').trim();
      const chunk = [body, extra].filter(Boolean).join('\n\n');
      prev.additionalContext = prev.additionalContext ? `${prev.additionalContext}\n\n${chunk}` : chunk;
      continue;
    }
    out.push({ ...p });
  }
  return out;
}

function normalizePrompts(prompts: unknown): PromptConfig[] {
  const normalizeOne = (p: unknown): PromptConfig | null => {
    if (p == null) return null;
    // Back-compat: <prompt>Text</prompt>
    if (typeof p === 'string') return { userText: p };
    if (typeof p !== 'object') {
      const s = extractString(p);
      return s ? { userText: s } : null;
    }

    const o = p as Record<string, unknown>;
    // New structure:
    // <prompt><userText>...</userText><additionalContext>...</additionalContext></prompt>
    const userText =
      extractString(o.userText) ??
      extractString(o['user-text']) ??
      extractString(o.text) ??
      extractString(o.UserText) ??
      extractString(o.user_text);
    const additionalContext = extractAdditionalContextField(o);

    if (userText && userText.trim()) {
      const pc: PromptConfig = { userText: userText.trim() };
      if (additionalContext) pc.additionalContext = additionalContext;
      const omitTools = extractBooleanFromRecord(o, 'omitTools', 'omit_tools');
      if (omitTools === true) pc.omitTools = true;
      return pc;
    }

    // Studio/XML parsers sometimes emit sibling nodes as two array entries: one { userText }, one { additionalContext }.
    // Never promote additionalContext alone to userText (that created a second "quick" button). Merge in processPromptList instead.
    if (additionalContext) return null;

    // Some parsers might flatten the inner text into #text/value
    const fallback = extractString(o);
    return fallback ? { userText: fallback } : null;
  };

  const coerceList = (raw: unknown): unknown[] => {
    if (raw == null) return [];
    if (Array.isArray(raw)) return raw;
    if (typeof raw === 'object') return Object.values(raw as Record<string, unknown>);
    return [raw];
  };

  /** Preserve order; merge orphan context-only fragments into the previous prompt. */
  const processPromptList = (arr: unknown[]): PromptConfig[] => {
    const out: PromptConfig[] = [];
    for (const item of arr) {
      const normalized = normalizeOne(item);
      if (normalized) {
        out.push(normalized);
        continue;
      }
      if (item != null && typeof item === 'object') {
        const ctx = extractAdditionalContextField(item as Record<string, unknown>);
        if (ctx && out.length > 0) {
          const prev = out[out.length - 1];
          prev.additionalContext = prev.additionalContext ? `${prev.additionalContext}\n\n${ctx}` : ctx;
        }
      }
    }
    return out;
  };

  const finalize = (arr: unknown[]) => mergeMisplacedContextPrompts(processPromptList(arr));

  if (prompts && typeof prompts === 'object') {
    const p = prompts as Record<string, unknown>;
    const raw = p.prompt;
    return finalize(coerceList(raw));
  }

  return finalize(coerceList(prompts));
}

