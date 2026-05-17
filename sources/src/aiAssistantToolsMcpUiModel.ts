import { STUDIO_AI_BUILTIN_TOOL_IDS, STUDIO_AI_MCP_ALL_TOKEN } from './studioAiOrchestrationToolIds';

/** Built-in wire names for hide/whitelist pickers (excludes the agent-only `mcp:*` sentinel). */
export const BUILTIN_TOOL_NAME_OPTIONS: readonly string[] = STUDIO_AI_BUILTIN_TOOL_IDS.filter(
  (id) => id !== STUDIO_AI_MCP_ALL_TOKEN
) as readonly string[];

export interface McpHeaderPair {
  key: string;
  value: string;
}

export interface McpServerFormRow {
  id: string;
  url: string;
  readTimeoutMs: string;
  headerPairs: McpHeaderPair[];
}

const KNOWN_TOP_LEVEL_KEYS = new Set([
  'disabledBuiltInTools',
  'enabledBuiltInTools',
  'mcpEnabled',
  'mcpServers',
  'disabledMcpTools',
  'intentRecipeRouting'
]);

const INTENT_RECIPE_ROUTING_KNOWN_KEYS = new Set([
  'enabled',
  'engineEnabled',
  'minConfidence',
  'requestClarificationOnUnmatched',
  'customRecipesPath'
]);

export interface IntentRecipeRoutingFormState {
  enabled: boolean;
  engineEnabled: boolean;
  minConfidence: string;
  requestClarificationOnUnmatched: boolean;
  customRecipesPath: string;
  /** Keys such as engineMaxSteps preserved when saving from Studio. */
  intentRecipeRoutingExtra?: Record<string, unknown>;
}

export function defaultIntentRecipeRoutingFormState(): IntentRecipeRoutingFormState {
  return {
    enabled: false,
    engineEnabled: false,
    minConfidence: '0.55',
    requestClarificationOnUnmatched: false,
    customRecipesPath: '',
    intentRecipeRoutingExtra: undefined
  };
}

export interface ToolsPolicyFormState {
  mcpEnabled: boolean;
  mcpServers: McpServerFormRow[];
  disabledBuiltInTools: string[];
  enabledBuiltInTools: string[];
  disabledMcpTools: string[];
  intentRecipeRouting: IntentRecipeRoutingFormState;
  /** Other top-level keys from tools.json preserved when saving. */
  extraFields?: Record<string, unknown>;
}

export function defaultToolsPolicyFormState(): ToolsPolicyFormState {
  return {
    mcpEnabled: false,
    mcpServers: [],
    disabledBuiltInTools: [],
    enabledBuiltInTools: [],
    disabledMcpTools: [],
    intentRecipeRouting: defaultIntentRecipeRoutingFormState(),
    extraFields: undefined
  };
}

function parseIntentRecipeRoutingFromUnknown(raw: unknown): IntentRecipeRoutingFormState {
  const base = defaultIntentRecipeRoutingFormState();
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return base;
  }
  const o = raw as Record<string, unknown>;
  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(o)) {
    if (!INTENT_RECIPE_ROUTING_KNOWN_KEYS.has(k)) {
      extra[k] = v;
    }
  }
  let minC = base.minConfidence;
  if (o.minConfidence != null) {
    minC = String(o.minConfidence).trim() || base.minConfidence;
  }
  return {
    enabled: Boolean(o.enabled),
    engineEnabled: Boolean(o.engineEnabled),
    minConfidence: minC,
    requestClarificationOnUnmatched: Boolean(o.requestClarificationOnUnmatched),
    customRecipesPath: o.customRecipesPath != null ? String(o.customRecipesPath).trim() : '',
    intentRecipeRoutingExtra: Object.keys(extra).length ? extra : undefined
  };
}

function intentRecipeRoutingToJsonObject(state: IntentRecipeRoutingFormState): Record<string, unknown> {
  const obj: Record<string, unknown> = { ...(state.intentRecipeRoutingExtra ?? {}) };
  obj.enabled = Boolean(state.enabled);
  obj.engineEnabled = Boolean(state.engineEnabled);
  const mc = Number(state.minConfidence.trim());
  if (Number.isFinite(mc)) {
    obj.minConfidence = mc;
  }
  if (state.requestClarificationOnUnmatched) {
    obj.requestClarificationOnUnmatched = true;
  }
  const path = state.customRecipesPath.trim();
  if (path) {
    obj.customRecipesPath = path;
  }
  return obj;
}

function headersObjectFromPairs(pairs: McpHeaderPair[]): Record<string, string> | undefined {
  const o: Record<string, string> = {};
  for (const { key, value } of pairs) {
    const k = key.trim();
    if (k) {
      o[k] = value;
    }
  }
  return Object.keys(o).length ? o : undefined;
}

function mcpServerRowFromUnknown(m: unknown): McpServerFormRow {
  if (!m || typeof m !== 'object' || Array.isArray(m)) {
    return { id: '', url: '', readTimeoutMs: '', headerPairs: [{ key: '', value: '' }] };
  }
  const rec = m as Record<string, unknown>;
  const headersRaw = rec.headers;
  const headerPairs: McpHeaderPair[] = [];
  if (headersRaw && typeof headersRaw === 'object' && !Array.isArray(headersRaw)) {
    for (const [k, v] of Object.entries(headersRaw as Record<string, unknown>)) {
      headerPairs.push({ key: k, value: v != null ? String(v) : '' });
    }
  }
  if (headerPairs.length === 0) {
    headerPairs.push({ key: '', value: '' });
  }
  return {
    id: rec.id != null ? String(rec.id) : '',
    url: rec.url != null ? String(rec.url) : '',
    readTimeoutMs: rec.readTimeoutMs != null ? String(rec.readTimeoutMs) : '',
    headerPairs
  };
}

export function parseToolsPolicyFromUnknown(raw: unknown): ToolsPolicyFormState {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return defaultToolsPolicyFormState();
  }
  const o = raw as Record<string, unknown>;
  const extraFields: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(o)) {
    if (!KNOWN_TOP_LEVEL_KEYS.has(k)) {
      extraFields[k] = v;
    }
  }
  const asStringArray = (v: unknown): string[] => {
    if (!Array.isArray(v)) {
      return [];
    }
    const out: string[] = [];
    for (const x of v) {
      const s = x != null ? String(x).trim() : '';
      if (s) {
        out.push(s);
      }
    }
    return out;
  };
  const serversRaw = o.mcpServers;
  const mcpServers: McpServerFormRow[] = [];
  if (Array.isArray(serversRaw)) {
    for (const s of serversRaw) {
      mcpServers.push(mcpServerRowFromUnknown(s));
    }
  }
  return {
    mcpEnabled: Boolean(o.mcpEnabled),
    mcpServers,
    disabledBuiltInTools: asStringArray(o.disabledBuiltInTools),
    enabledBuiltInTools: asStringArray(o.enabledBuiltInTools),
    disabledMcpTools: asStringArray(o.disabledMcpTools),
    intentRecipeRouting: parseIntentRecipeRoutingFromUnknown(o.intentRecipeRouting),
    extraFields: Object.keys(extraFields).length ? extraFields : undefined
  };
}

export function parseToolsPolicyFromJsonText(text: string): { ok: true; state: ToolsPolicyFormState } | { ok: false; message: string } {
  const t = text.trim();
  if (!t) {
    return { ok: true, state: defaultToolsPolicyFormState() };
  }
  try {
    const raw = JSON.parse(t) as unknown;
    return { ok: true, state: parseToolsPolicyFromUnknown(raw) };
  } catch {
    return { ok: false, message: 'Existing tools.json is not valid JSON. Fix the file in Git or restore defaults, then reload.' };
  }
}

export function validateToolsPolicy(state: ToolsPolicyFormState): { ok: true } | { ok: false; message: string } {
  const mc = Number(state.intentRecipeRouting.minConfidence.trim());
  if (state.intentRecipeRouting.enabled && (!Number.isFinite(mc) || mc < 0 || mc > 1)) {
    return { ok: false, message: 'Intent recipe min confidence must be a number between 0 and 1.' };
  }
  for (let i = 0; i < state.mcpServers.length; i++) {
    const r = state.mcpServers[i];
    const id = r.id.trim();
    const url = r.url.trim();
    if (!id && !url) {
      continue;
    }
    if (!id || !url) {
      return { ok: false, message: `MCP server ${i + 1}: both Server id and MCP URL are required (or clear the row).` };
    }
    const rt = r.readTimeoutMs.trim();
    if (rt) {
      const n = Number(rt);
      if (!Number.isFinite(n) || n < 1000 || n > 3_600_000) {
        return { ok: false, message: `MCP server "${id}": Read timeout must be between 1000 and 3600000 ms when set.` };
      }
    }
  }
  return { ok: true };
}

/** Body for {@code mcp-tools-preview} — same {@code mcpServers} rows as persisted in {@code tools.json}. */
export function buildMcpToolsPreviewBody(state: ToolsPolicyFormState): {
  mcpEnabled: boolean;
  mcpServers: Record<string, unknown>[];
} {
  const mcpServers = state.mcpServers
    .map((r) => {
      const id = r.id.trim();
      const url = r.url.trim();
      if (!id || !url) {
        return null;
      }
      const rec: Record<string, unknown> = { id, url };
      const headers = headersObjectFromPairs(r.headerPairs);
      if (headers) {
        rec.headers = headers;
      }
      const rt = r.readTimeoutMs.trim();
      if (rt) {
        const n = Math.round(Number(rt));
        if (Number.isFinite(n)) {
          rec.readTimeoutMs = n;
        }
      }
      return rec;
    })
    .filter((x): x is Record<string, unknown> => x != null);

  return { mcpEnabled: Boolean(state.mcpEnabled), mcpServers };
}

export function serializeToolsPolicyToJson(state: ToolsPolicyFormState): string {
  const mcpServers = state.mcpServers
    .map((r) => {
      const id = r.id.trim();
      const url = r.url.trim();
      if (!id && !url) {
        return null;
      }
      const rec: Record<string, unknown> = { id, url };
      const headers = headersObjectFromPairs(r.headerPairs);
      if (headers) {
        rec.headers = headers;
      }
      const rt = r.readTimeoutMs.trim();
      if (rt) {
        const n = Math.round(Number(rt));
        if (Number.isFinite(n)) {
          rec.readTimeoutMs = n;
        }
      }
      return rec;
    })
    .filter((x): x is Record<string, unknown> => x != null);

  const obj: Record<string, unknown> = { ...(state.extraFields ?? {}) };
  obj.disabledBuiltInTools = [...new Set(state.disabledBuiltInTools.map((s) => s.trim()).filter(Boolean))];
  obj.enabledBuiltInTools = [...new Set(state.enabledBuiltInTools.map((s) => s.trim()).filter(Boolean))];
  obj.mcpEnabled = Boolean(state.mcpEnabled);
  obj.mcpServers = mcpServers;
  obj.disabledMcpTools = [...new Set(state.disabledMcpTools.map((s) => s.trim()).filter(Boolean))];
  const irr = intentRecipeRoutingToJsonObject(state.intentRecipeRouting);
  if (
    state.intentRecipeRouting.enabled ||
    state.intentRecipeRouting.engineEnabled ||
    state.intentRecipeRouting.requestClarificationOnUnmatched ||
    state.intentRecipeRouting.customRecipesPath.trim() ||
    (state.intentRecipeRouting.intentRecipeRoutingExtra &&
      Object.keys(state.intentRecipeRouting.intentRecipeRoutingExtra).length > 0)
  ) {
    obj.intentRecipeRouting = irr;
  }
  return JSON.stringify(obj, null, 2);
}
