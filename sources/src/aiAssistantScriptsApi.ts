import { fetchConfigurationXML } from '@craftercms/studio-ui/services/configuration';
import { fetchContentXML, fetchItemsByPath } from '@craftercms/studio-ui/services/content';
import { firstValueFrom, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { buildStudioAuthHeaders } from './aiAssistantApi';

const BASE = '/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/scripts';

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

export type AiAssistantScriptsIndexTool = {
  id: string;
  script: string;
  description: string;
  studioPath: string;
  hasSource: boolean;
  byteLength: number;
};

export type AiAssistantScriptsIndexItem = {
  id: string;
  studioPath: string | null;
  hasSource: boolean;
  byteLength: number;
};

export type AiAssistantScriptsToolPromptOverrideRow = {
  key: string;
  studioPath: string;
  hasOverride: boolean;
  byteLength: number;
};

export type AiAssistantPromptDetailResponse = {
  ok?: boolean;
  message?: string;
  key?: string;
  /** Classpath plugin KEY.md if present, else built-in Groovy literal (used when site file is missing or blank). */
  defaultText?: string;
  /** Raw UTF-8 of site prompts/KEY.md (may be blank). */
  siteFileText?: string;
  siteOverrideEffective?: boolean;
  defaultTextTruncated?: boolean;
  siteFileTruncated?: boolean;
};

export type AiAssistantScriptsIndexResponse = {
  ok?: boolean;
  message?: string;
  registryStudioPath?: string;
  registryText?: string;
  tools?: AiAssistantScriptsIndexTool[];
  imageGenerators?: AiAssistantScriptsIndexItem[];
  llmScripts?: AiAssistantScriptsIndexItem[];
  toolPromptOverrides?: AiAssistantScriptsToolPromptOverrideRow[];
};

export async function fetchAiAssistantPromptDetail(siteId: string, key: string): Promise<AiAssistantPromptDetailResponse> {
  const res = await fetch(
    `${withSite(`${BASE}/prompt`, siteId)}&key=${encodeURIComponent(key)}`,
    {
      method: 'GET',
      credentials: 'include',
      headers: { ...buildStudioAuthHeaders() }
    }
  );
  const raw = await res.json().catch(() => ({}));
  const data = unwrapPluginScriptBody(raw) as AiAssistantPromptDetailResponse;
  if (!res.ok) {
    return { ok: false, message: data.message ?? (raw as { message?: string }).message ?? res.statusText };
  }
  return data;
}

export async function fetchAiAssistantScriptsIndex(siteId: string): Promise<AiAssistantScriptsIndexResponse> {
  const res = await fetch(withSite(`${BASE}/index`, siteId), {
    method: 'GET',
    credentials: 'include',
    headers: { ...buildStudioAuthHeaders() }
  });
  const raw = await res.json().catch(() => ({}));
  const data = unwrapPluginScriptBody(raw) as AiAssistantScriptsIndexResponse;
  if (!res.ok) {
    return { ok: false, message: data.message ?? (raw as { message?: string }).message ?? res.statusText };
  }
  return data;
}

export type AiAssistantMcpToolPreviewItem = {
  wireName: string;
  mcpToolName: string;
  description: string;
};

export type AiAssistantMcpPreviewServer = {
  serverId: string;
  ok: boolean;
  message?: string;
  tools: AiAssistantMcpToolPreviewItem[];
};

export type AiAssistantMcpToolsPreviewResponse = {
  ok?: boolean;
  message?: string;
  mcpEnabled?: boolean;
  servers?: AiAssistantMcpPreviewServer[];
};

export async function postAiAssistantMcpToolsPreview(
  siteId: string,
  body: { mcpEnabled: boolean; mcpServers: Record<string, unknown>[] }
): Promise<AiAssistantMcpToolsPreviewResponse> {
  const res = await fetch(withSite(`${BASE}/mcp-tools-preview`, siteId), {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...buildStudioAuthHeaders()
    },
    body: JSON.stringify({ siteId, ...body })
  });
  const raw = await res.json().catch(() => ({}));
  const data = unwrapPluginScriptBody(raw) as AiAssistantMcpToolsPreviewResponse;
  if (!res.ok) {
    return { ok: false, message: data.message ?? (raw as { message?: string }).message ?? res.statusText };
  }
  return data;
}

export async function postAiAssistantScriptsMutate(
  siteId: string,
  payload: Record<string, unknown>
): Promise<{ ok?: boolean; message?: string }> {
  const res = await fetch(withSite(`${BASE}/mutate`, siteId), {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...buildStudioAuthHeaders()
    },
    body: JSON.stringify({ siteId, ...payload })
  });
  const raw = await res.json().catch(() => ({}));
  const data = unwrapPluginScriptBody(raw) as { ok?: boolean; message?: string };
  if (!res.ok) {
    return { ok: false, message: data.message ?? (raw as { message?: string }).message ?? res.statusText };
  }
  return data;
}

/** Studio configuration path for {@code writeConfiguration} / {@code fetchConfigurationXML} (no leading slash). */
export function studioConfigRelativePath(studioModulePath: string): string {
  const p = (studioModulePath ?? '').trim();
  return p.startsWith('/') ? p.slice(1) : p;
}

/** Sandbox repo path for {@code tools.json} (same file as Studio module {@code scripts/aiassistant/config/tools.json}). */
export const TOOLS_JSON_SANDBOX_PATH = '/config/studio/scripts/aiassistant/config/tools.json';

/** Raw UTF-8 from Studio {@code fetchContentXML} / similar payloads (string or {@code content}/{@code configuration} envelope). */
export function utf8FromStudioContentPayload(raw: unknown): string {
  if (raw == null) return '';
  if (typeof raw === 'string') return raw;
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    const o = raw as Record<string, unknown>;
    const content = o.content;
    if (typeof content === 'string') return content;
    const configuration = o.configuration;
    if (typeof configuration === 'string') return configuration;
  }
  return '';
}

/**
 * Reads UTF-8 text for a file under {@code /config/studio/...} via content APIs when present.
 * Does not call {@code get_configuration}, so a missing optional file does not produce Studio {@code ContentNotFoundException} logs.
 */
export async function fetchOptionalStudioSandboxUtf8(siteId: string, sandboxPath: string): Promise<string> {
  const sid = (siteId || '').trim();
  const path = (sandboxPath || '').trim().startsWith('/') ? (sandboxPath || '').trim() : `/${(sandboxPath || '').trim()}`;
  if (!sid || !path) return '';
  try {
    const listings = (await firstValueFrom(
      fetchItemsByPath(sid, [path], { preferContent: true })
    )) as unknown as { missingItems?: string[]; 0?: unknown };
    if (Array.isArray(listings?.missingItems) && listings.missingItems.includes(path)) {
      return '';
    }
    if (!listings?.[0]) {
      return '';
    }
    const raw = await firstValueFrom(fetchContentXML(sid, path, { lock: false }).pipe(catchError(() => of(null))));
    return utf8FromStudioContentPayload(raw).trim();
  } catch {
    return '';
  }
}

/**
 * Reads a site file under {@code /config/studio/<relative>} for the script sandbox editor.
 * <ol>
 *   <li>{@code get-content.json} via {@code fetchContentXML} when the path is not listed as missing from {@code sandbox_items_by_path}
 *       (does <strong>not</strong> require {@code items[0]} — that slot can be empty right after writes while the file still exists).</li>
 *   <li>If still empty, {@code get_configuration} raw string via {@code fetchConfigurationXML} — same stack as {@code write_configuration}.
 *       Do <strong>not</strong> use {@code fetchConfigurationJSON}: it runs XML {@code deserialize} and destroys Groovy/JSON/plain text.</li>
 * </ol>
 */
export async function fetchStudioConfigFileUtf8(siteId: string, studioConfigRelativeNoLeadingSlash: string): Promise<string> {
  const sid = (siteId || '').trim();
  const rel = (studioConfigRelativeNoLeadingSlash || '').trim().replace(/^\/+/, '');
  if (!sid || !rel) return '';
  const path = `/config/studio/${rel}`;
  try {
    const listings = (await firstValueFrom(
      fetchItemsByPath(sid, [path], { preferContent: true })
    )) as unknown as { missingItems?: string[] };
    if (Array.isArray(listings?.missingItems) && listings.missingItems.includes(path)) {
      return tryFetchConfigurationXmlPlain(sid, rel);
    }
    const raw = await firstValueFrom(fetchContentXML(sid, path, { lock: false }).pipe(catchError(() => of(null))));
    const fromGetContent = utf8FromStudioContentPayload(raw);
    if (fromGetContent.length > 0) {
      return fromGetContent;
    }
    return tryFetchConfigurationXmlPlain(sid, rel);
  } catch {
    return tryFetchConfigurationXmlPlain(sid, rel);
  }
}

async function tryFetchConfigurationXmlPlain(siteId: string, configPathRelative: string): Promise<string> {
  try {
    const s = await firstValueFrom(fetchConfigurationXML(siteId, configPathRelative, 'studio'));
    return typeof s === 'string' ? s : '';
  } catch {
    return '';
  }
}
