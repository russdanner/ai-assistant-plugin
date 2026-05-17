/**
 * Autonomous Agents — definitions from widget `ui.xml` / JSON configuration.
 * XML shape: `<autonomousAgents><agent><name/><schedule/><startAutomatically/>…</agent></autonomousAgents>`
 */
import { normalizeExpertSkillsRaw, type ExpertSkillConfig, normalizeEnabledBuiltInToolsRaw } from './agentConfig';

export type AutonomousScope = 'user' | 'role' | 'project';

export interface AutonomousAgentDefinition {
  name: string;
  /** Quartz-style expression (server prototype maps common patterns to periods). */
  schedule: string;
  prompt: string;
  scope: AutonomousScope;
  llm: string;
  llmModel: string;
  imageModel?: string;
  /** Optional GenerateImage backend (same semantics as interactive chat **imageGenerator**). */
  imageGenerator?: string;
  llmApiKey?: string;
  /**
   * When true, the model may set `ownerAgentId` on new human tasks and dismiss/complete tasks owned by other agents.
   * Default false: only this agent’s tasks are modified.
   */
  manageOtherAgentsHumanTasks?: boolean;
  /**
   * When false, the server registers the agent as **stopped** after sync until an author uses **Start** in the widget.
   * Default true: status **waiting** after sync (still requires **Start system** + supervisor for ticks to run).
   */
  startAutomatically?: boolean;
  /**
   * When true (default), a failed run sets the agent to **error** until cleared; other agents and the supervisor keep running.
   * When false, the failure is recorded on state but the agent returns to **waiting** with **next step due** so the next tick retries.
   */
  stopOnFailure?: boolean;
  /** Optional markdown URLs for OpenAI **QueryExpertGuidance** (same shape as Helper `<expertSkill>` rows). */
  expertSkills?: ExpertSkillConfig[];
  /**
   * Optional subset of CMS tool wire names for autonomous runs (same as chat stream **enabledBuiltInTools**).
   * Include **mcp:*** to keep all MCP tools.
   */
  enabledBuiltInTools?: string[];
}

/** Mirrors `AutonomousAgentIdBuilder` (Groovy) for project-scoped ids used in sync/status. */
export function normalizeAgentNameForId(raw: string): string {
  let s = (raw ?? '').trim().toLowerCase();
  s = s.replace(/\s+/g, '-');
  s = s.replace(/[^a-z0-9-]+/g, '-');
  s = s.replace(/-+/g, '-');
  s = s.replace(/^-+|-+$/g, '');
  return s || 'agent';
}

export function sanitizeScopeIdForAgentId(scopeId: string): string {
  return scopeId.replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/-+/g, '-');
}

/**
 * Same shape as {@link AutonomousAgentIdBuilder.buildAgentId} in the autonomous Groovy package.
 */
export function buildAutonomousAgentId(
  projectId: string,
  scope: AutonomousScope,
  scopeId: string,
  agentName: string
): string {
  const p = (projectId || 'default').trim();
  let sc: AutonomousScope = scope;
  if (sc !== 'user' && sc !== 'role' && sc !== 'project') sc = 'project';
  const sid = sanitizeScopeIdForAgentId((scopeId || p).trim());
  const n = normalizeAgentNameForId(agentName);
  return `${p}-${sc}-${sid}-${n}`;
}

/** One row for the autonomous UI table — either from `/status` or merged from configuration. */
export type AutonomousTableAgentRow = {
  agentId: string;
  definition?: Record<string, unknown>;
  state?: Record<string, unknown>;
  pastRunReports?: unknown[];
  /** Present when the row is built from ui.xml before the server registry lists it (e.g. before sync). */
  syntheticFromConfig?: boolean;
};

/** Viewer context so client-built agent ids match {@code sync.post.groovy} (user/role scopeId). */
export type AutonomousMergeViewer = {
  username: string;
  /** First role id for the active site (best-effort mirror of sync’s first {@code ROLE_*} authority). */
  roleScopeId: string;
};

/**
 * Ensures every configured agent appears in the table even when `/status` returns an empty `agents`
 * list (registry not yet populated). Matches server rows by `agentId` or `definition.name`, then
 * synthesizes rows with the same id {@link buildAutonomousAgentId} uses on sync.
 */
export function mergeAutonomousAgentsForTable(
  siteId: string,
  defs: AutonomousAgentDefinition[],
  statusAgents: AutonomousTableAgentRow[] | undefined | null,
  viewer?: AutonomousMergeViewer
): AutonomousTableAgentRow[] {
  const list = Array.isArray(statusAgents) ? statusAgents : [];
  const taken = new Set<string>();
  const out: AutonomousTableAgentRow[] = [];

  const vUsername = (viewer?.username ?? '').trim() || 'anonymous';
  const vRole = (viewer?.roleScopeId ?? viewer?.username ?? siteId).trim() || siteId;

  const syntheticRow = (d: AutonomousAgentDefinition, id: string): AutonomousTableAgentRow => ({
    agentId: id,
    definition: {
      name: d.name,
      schedule: d.schedule,
      scope: d.scope,
      prompt: d.prompt,
      llm: d.llm,
      llmModel: d.llmModel,
      ...(d.imageModel != null ? { imageModel: d.imageModel } : {}),
      ...(d.imageGenerator != null && String(d.imageGenerator).trim() !== ''
        ? { imageGenerator: String(d.imageGenerator).trim() }
        : {}),
      ...(d.manageOtherAgentsHumanTasks ? { manageOtherAgentsHumanTasks: true } : {}),
      ...(d.startAutomatically === false ? { startAutomatically: false } : {}),
      ...(d.stopOnFailure === false ? { stopOnFailure: false } : {}),
      ...(Array.isArray(d.expertSkills) && d.expertSkills.length > 0 ? { expertSkills: d.expertSkills } : {}),
      siteId
    },
    state: { status: 'pending' },
    syntheticFromConfig: true
  });

  for (const d of defs) {
    const name = d.name;
    const byName = list.find((a) => !taken.has(a.agentId) && String(a.definition?.name ?? '') === name);
    if (byName) {
      taken.add(byName.agentId);
      out.push(byName);
      continue;
    }
    const nn = normalizeAgentNameForId(name);
    const byNorm = list.find((a) => {
      if (taken.has(a.agentId)) return false;
      const def = a.definition;
      const raw =
        def && typeof def === 'object'
          ? String((def as Record<string, unknown>).name ?? (def as Record<string, unknown>).label ?? '')
          : '';
      return normalizeAgentNameForId(raw) === nn;
    });
    if (byNorm) {
      taken.add(byNorm.agentId);
      out.push(byNorm);
      continue;
    }

    let id: string;
    if (d.scope === 'project') {
      id = buildAutonomousAgentId(siteId, 'project', siteId, name);
    } else if (d.scope === 'user') {
      id = buildAutonomousAgentId(siteId, 'user', vUsername, name);
    } else {
      id = buildAutonomousAgentId(siteId, 'role', vRole, name);
    }

    const hit = list.find((a) => a.agentId === id && !taken.has(a.agentId));
    if (hit) {
      taken.add(hit.agentId);
      out.push(hit);
    } else {
      out.push(syntheticRow(d, id));
    }
  }

  for (const a of list) {
    if (!taken.has(a.agentId)) {
      out.push(a);
    }
  }
  return out;
}

function normalizeManageOtherAgentsHumanTasks(raw: unknown): boolean | undefined {
  if (raw === true || raw === 1) return true;
  if (raw === false || raw === 0) return false;
  if (typeof raw === 'string') {
    const s = raw.trim().toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no' || s === '') return false;
  }
  return undefined;
}

function normalizeStartAutomatically(raw: unknown): boolean | undefined {
  if (raw === true || raw === 1) return true;
  if (raw === false || raw === 0) return false;
  if (typeof raw === 'string') {
    const s = raw.trim().toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no') return false;
  }
  return undefined;
}

function normalizeStopOnFailure(raw: unknown): boolean | undefined {
  if (raw === true || raw === 1) return true;
  if (raw === false || raw === 0) return false;
  if (typeof raw === 'string') {
    const s = raw.trim().toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no') return false;
  }
  return undefined;
}

function normalizeOne(raw: unknown): AutonomousAgentDefinition | null {
  if (raw == null || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  const name = String(o.name ?? o.label ?? '').trim();
  if (!name) return null;
  const scopeRaw = String(o.scope ?? 'project').trim().toLowerCase();
  const scope: AutonomousScope =
    scopeRaw === 'user' || scopeRaw === 'role' || scopeRaw === 'project' ? scopeRaw : 'project';
  const manageCross = normalizeManageOtherAgentsHumanTasks(o.manageOtherAgentsHumanTasks);
  const startAuto = normalizeStartAutomatically(
    o.startAutomatically ?? o.start_automatically ?? o.automaticallyStart ?? o.automatically_start
  );
  const stopFail = normalizeStopOnFailure(o.stopOnFailure ?? o.stop_on_failure);
  const expertSkills = normalizeExpertSkillsRaw(o.expertSkills) ?? normalizeExpertSkillsRaw(o.expertSkill);
  const enabledBuiltIn = normalizeEnabledBuiltInToolsRaw(o.enabledBuiltInTools ?? o.enabled_built_in_tools);
  return {
    name,
    schedule: String(o.schedule ?? '0 0 * * * ?').trim(),
    prompt: String(o.prompt ?? '').trim(),
    scope,
    llm: String(o.llm ?? 'openAI').trim(),
    llmModel: String(o.llmModel ?? 'gpt-4o-mini').trim(),
    imageModel: o.imageModel != null ? String(o.imageModel).trim() : undefined,
    imageGenerator:
      o.imageGenerator != null && String(o.imageGenerator).trim() !== ''
        ? String(o.imageGenerator).trim()
        : undefined,
    llmApiKey: o.llmApiKey != null ? String(o.llmApiKey).trim() : undefined,
    ...(manageCross !== undefined ? { manageOtherAgentsHumanTasks: manageCross } : {}),
    ...(startAuto === false ? { startAutomatically: false } : {}),
    ...(stopFail === false ? { stopOnFailure: false } : {}),
    ...(expertSkills && expertSkills.length > 0 ? { expertSkills } : {}),
    ...(enabledBuiltIn?.length ? { enabledBuiltInTools: enabledBuiltIn } : {})
  };
}

/**
 * Same nesting patterns as `getAgentsFromConfiguration` in `agentConfig.ts`: Studio may pass
 * `autonomousAgents` at the top level, under `configuration`, or under `configuration.configuration`.
 */
function readAgentsRaw(configuration: unknown): unknown {
  const config =
    configuration != null && typeof configuration === 'object' ? (configuration as Record<string, unknown>) : null;
  if (!config) return null;
  let raw: unknown = config.autonomousAgents;
  if (raw == null && config.configuration != null && typeof config.configuration === 'object') {
    const inner = config.configuration as Record<string, unknown>;
    raw =
      inner.autonomousAgents ??
      (inner.configuration != null && typeof inner.configuration === 'object'
        ? (inner.configuration as Record<string, unknown>).autonomousAgents
        : undefined);
  }
  if (raw == null && config.configuration != null && typeof config.configuration === 'object') {
    const inner = config.configuration as Record<string, unknown>;
    if (inner.configuration != null && typeof inner.configuration === 'object') {
      const deep = (inner.configuration as Record<string, unknown>).autonomousAgents;
      if (deep != null) raw = deep;
    }
  }
  return raw;
}

/**
 * Studio deserializes a **single** `<agent>` as a flat object `{ name, schedule, ... }` under `agent`.
 * `Object.values(that)` would yield primitive strings — use this instead.
 */
function agentsFromRawAgentField(listOrSingle: unknown): unknown[] {
  if (listOrSingle == null) return [];
  if (Array.isArray(listOrSingle)) return listOrSingle;
  if (typeof listOrSingle !== 'object') return [];
  const o = listOrSingle as Record<string, unknown>;
  const keys = Object.keys(o);
  const numericKeyed = keys.length > 0 && keys.every((k) => /^\d+$/.test(k));
  if (numericKeyed) {
    return [...keys].sort((a, b) => Number(a) - Number(b)).map((k) => o[k]);
  }
  const looksLikeSingleAgentRow =
    typeof o.name === 'string' ||
    typeof o.label === 'string' ||
    typeof o.schedule === 'string' ||
    typeof o.prompt === 'string' ||
    typeof o.llm === 'string';
  if (looksLikeSingleAgentRow) {
    return [o];
  }
  return Object.values(o);
}

/**
 * Pass **merged widget props**: `{ ...props.configuration, ...props }` so `autonomousAgents` is found whether
 * Studio nested it or spread `<configuration>` onto the component root (see `Widget.js`).
 */
export function getAutonomousAgentsFromConfiguration(configurationOrMergedProps: unknown): AutonomousAgentDefinition[] {
  const raw = readAgentsRaw(configurationOrMergedProps);
  if (raw == null) return [];

  if (Array.isArray(raw)) {
    return raw.map(normalizeOne).filter(Boolean) as AutonomousAgentDefinition[];
  }
  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>;
    const listOrSingle = obj.agent;
    if (listOrSingle == null) return [];
    const arr = agentsFromRawAgentField(listOrSingle);
    return arr.map(normalizeOne).filter(Boolean) as AutonomousAgentDefinition[];
  }
  return [];
}

/** Merge nested `configuration` with root props (Studio spreads config onto the component after registration). */
export function mergeAutonomousWidgetProps(props: Record<string, unknown>): Record<string, unknown> {
  const nested =
    props.configuration != null && typeof props.configuration === 'object' && !Array.isArray(props.configuration)
      ? (props.configuration as Record<string, unknown>)
      : {};
  return { ...nested, ...props };
}
