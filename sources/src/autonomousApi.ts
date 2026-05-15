import { buildStudioAuthHeaders } from './aiAssistantApi';
import type { AutonomousAgentDefinition } from './autonomousAssistantsConfig';

const BASE =
  '/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/autonomous/assistants';

function withSite(url: string, siteId: string): string {
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}siteId=${encodeURIComponent(siteId)}`;
}

/** Studio plugin controller wraps the Groovy script return map under `result` (see `aiAssistantImportApi.ts`). */
function unwrapPluginScriptBody(body: unknown): unknown {
  if (!body || typeof body !== 'object') return body;
  const o = body as Record<string, unknown>;
  const inner = o.result;
  if (inner && typeof inner === 'object' && !Array.isArray(inner)) return inner;
  return body;
}

export async function syncAutonomousAssistants(
  siteId: string,
  agents: AutonomousAgentDefinition[]
): Promise<{ ok?: boolean; agentIds?: string[]; message?: string }> {
  const res = await fetch(withSite(`${BASE}/sync`, siteId), {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...buildStudioAuthHeaders()
    },
    body: JSON.stringify({ siteId, agents })
  });
  const raw = await res.json().catch(() => ({}));
  const data = unwrapPluginScriptBody(raw) as { ok?: boolean; agentIds?: string[]; message?: string };
  if (!res.ok) {
    return { ok: false, message: data.message ?? (raw as { message?: string }).message ?? res.statusText };
  }
  return data;
}

export async function getAutonomousAssistantsStatus(siteId: string): Promise<unknown> {
  const res = await fetch(withSite(`${BASE}/status`, siteId), {
    method: 'GET',
    credentials: 'include',
    headers: { ...buildStudioAuthHeaders() }
  });
  const raw = await res.json();
  return unwrapPluginScriptBody(raw);
}

export async function postAutonomousAssistantsControl(
  siteId: string,
  action: string,
  agentId?: string,
  taskId?: string,
  extras?: Record<string, string | undefined>
): Promise<{ ok?: boolean; message?: string }> {
  const payload: Record<string, string> = { siteId, action, agentId: agentId ?? '' };
  if (taskId?.trim()) {
    payload.taskId = taskId.trim();
  }
  if (extras) {
    for (const [k, v] of Object.entries(extras)) {
      if (v !== undefined) {
        payload[k] = v;
      }
    }
  }
  const res = await fetch(withSite(`${BASE}/control`, siteId), {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...buildStudioAuthHeaders()
    },
    body: JSON.stringify(payload)
  });
  const raw = await res.json().catch(() => ({}));
  const data = unwrapPluginScriptBody(raw) as { ok?: boolean; message?: string };
  if (!res.ok) {
    return { ok: false, message: data.message ?? (raw as { message?: string }).message ?? res.statusText };
  }
  return data;
}
