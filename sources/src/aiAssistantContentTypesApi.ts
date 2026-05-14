import { buildStudioAuthHeaders } from './aiAssistantApi';

const BASE =
  '/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/content-types/list';

function withSite(url: string, siteId: string): string {
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}siteId=${encodeURIComponent(siteId)}`;
}

function unwrapPluginScriptBody(body: unknown): unknown {
  if (!body || typeof body !== 'object') return body;
  const o = body as Record<string, unknown>;
  const inner = o.result;
  if (inner && typeof inner === 'object' && !Array.isArray(inner)) return inner;
  return body;
}

export type AiAssistantContentTypeRow = {
  name?: string;
  label?: string;
  [key: string]: unknown;
};

export type AiAssistantContentTypesListResponse = {
  ok?: boolean;
  siteId?: string;
  mode?: string;
  count?: number;
  contentTypes?: AiAssistantContentTypeRow[];
  message?: string;
};

export async function fetchAiAssistantContentTypeCatalog(
  siteId: string
): Promise<AiAssistantContentTypesListResponse> {
  const res = await fetch(withSite(BASE, siteId), {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...buildStudioAuthHeaders()
    }
  });
  const raw = await res.json().catch(() => ({}));
  const data = unwrapPluginScriptBody(raw) as AiAssistantContentTypesListResponse;
  if (!res.ok) {
    return {
      ok: false,
      message: data.message ?? (raw as { message?: string }).message ?? res.statusText,
      contentTypes: []
    };
  }
  return data;
}
