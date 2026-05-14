/**
 * Site UI flags for AI Assistant Studio surfaces (`config/studio/scripts/aiassistant/config/studio-ui.json`).
 * Read synchronously where needed (preview bus, Helper) via {@link syncReadStudioUiConfig}.
 */
import type { ContentType } from '@craftercms/studio-ui/models/ContentType';
import { fetchConfigurationXML } from '@craftercms/studio-ui/services/configuration';
import { fetchContentXML, fetchItemsByPath } from '@craftercms/studio-ui/services/content';
import { firstValueFrom, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

export type ContentTypeImageAugmentationScope = 'all' | 'none' | 'selected';

export interface AiAssistantStudioUiConfig {
  version?: number;
  /** Preview toolbar Helper (`ui="IconButton"`). Default true. */
  showAiAssistantsInTopNavigation?: boolean;
  /** Tools Panel Autonomous Assistants widget. Default false (opt-in). */
  showAutonomousAiAssistantsInSidebar?: boolean;
  /**
   * When {@code all} (default), {@link installAiAssistantContentTypesHighlightPatch} augments every content type.
   * When {@code none}, no augmentation. When {@code selected}, only ids in {@link contentTypeIdsForImageAugmentation}.
   */
  contentTypeImageAugmentationScope?: ContentTypeImageAugmentationScope;
  /** Normalized ids like {@code /page/article} — used when scope is {@code selected}. */
  contentTypeIdsForImageAugmentation?: string[];
}

/** Relative to `config/studio/` for {@code fetchConfigurationJSON} / {@code writeConfiguration}. */
export const STUDIO_UI_CONFIG_REL_PATH = 'scripts/aiassistant/config/studio-ui.json';

/**
 * Sandbox repo path for {@code get-content.json} / {@link fetchItemsByPath} (same pattern as central {@code agents.json}).
 * Prefer this over {@code get_configuration} for JSON: some Studio paths serve the file reliably only via content APIs.
 */
export const STUDIO_UI_CONFIG_SANDBOX_PATH = '/config/studio/scripts/aiassistant/config/studio-ui.json';

export const DEFAULT_AI_ASSISTANT_STUDIO_UI_CONFIG: AiAssistantStudioUiConfig = {
  version: 1,
  showAiAssistantsInTopNavigation: true,
  showAutonomousAiAssistantsInSidebar: false,
  contentTypeImageAugmentationScope: 'all',
  contentTypeIdsForImageAugmentation: []
};

const cache = new Map<string, AiAssistantStudioUiConfig>();

function normalizeId(id: string): string {
  const t = (id || '').trim();
  if (!t) return '';
  return t.startsWith('/') ? t : `/${t}`;
}

/** Studio sometimes returns booleans as strings (or 0/1) after XML/JSON round-trips. */
function coerceOptionalBoolean(v: unknown): boolean | undefined {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'number') {
    if (v === 1) return true;
    if (v === 0) return false;
  }
  if (typeof v === 'string') {
    const s = v.trim().toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no') return false;
  }
  return undefined;
}

export function mergeStudioUiConfig(raw: unknown): AiAssistantStudioUiConfig {
  const base = { ...DEFAULT_AI_ASSISTANT_STUDIO_UI_CONFIG };
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return base;
  const o = raw as Record<string, unknown>;
  const topNav = coerceOptionalBoolean(o.showAiAssistantsInTopNavigation);
  if (topNav !== undefined) {
    base.showAiAssistantsInTopNavigation = topNav;
  }
  const autonomousSidebar = coerceOptionalBoolean(o.showAutonomousAiAssistantsInSidebar);
  if (autonomousSidebar !== undefined) {
    base.showAutonomousAiAssistantsInSidebar = autonomousSidebar;
  }
  const scope = String(o.contentTypeImageAugmentationScope ?? '').trim().toLowerCase();
  if (scope === 'none' || scope === 'selected' || scope === 'all') {
    base.contentTypeImageAugmentationScope = scope as ContentTypeImageAugmentationScope;
  }
  if (Array.isArray(o.contentTypeIdsForImageAugmentation)) {
    base.contentTypeIdsForImageAugmentation = o.contentTypeIdsForImageAugmentation
      .map((x) => normalizeId(String(x)))
      .filter(Boolean);
  }
  return base;
}

function parseJsonConfigPayload(raw: unknown): unknown | null {
  if (raw == null) return null;
  if (typeof raw === 'string') {
    const s = raw.trim();
    if (!s) return null;
    try {
      return JSON.parse(s) as unknown;
    } catch {
      return null;
    }
  }
  if (typeof raw === 'object' && !Array.isArray(raw)) return raw;
  return null;
}

export function authoringSiteIdFromWindow(): string | undefined {
  try {
    const w = window as unknown as { CStudioAuthoringContext?: { siteId?: string } };
    const s = w.CStudioAuthoringContext?.siteId;
    return typeof s === 'string' && s.trim() ? s.trim() : undefined;
  } catch {
    return undefined;
  }
}

/** Same site resolution as {@link syncReadStudioUiConfig}: Redux active id, else authoring window context. */
export function effectiveStudioSiteId(activeFromHook: string | undefined): string {
  return (activeFromHook ?? '').trim() || (authoringSiteIdFromWindow() ?? '').trim();
}

function syncFetchStudioUiJsonFromSandbox(siteId: string): unknown | null {
  if (typeof XMLHttpRequest === 'undefined') return null;
  const sid = (siteId || '').trim();
  if (!sid) return null;
  const qs =
    '?site_id=' +
    encodeURIComponent(sid) +
    '&path=' +
    encodeURIComponent(STUDIO_UI_CONFIG_SANDBOX_PATH) +
    '&edit=false';
  const xhr = new XMLHttpRequest();
  xhr.open('GET', '/studio/api/1/services/api/1/content/get-content.json' + qs, false);
  xhr.withCredentials = true;
  xhr.setRequestHeader('Accept', 'application/json');
  try {
    xhr.send(null);
  } catch {
    return null;
  }
  if (xhr.status < 200 || xhr.status >= 300) return null;
  try {
    const j = JSON.parse(xhr.responseText) as { response?: { content?: unknown } };
    return parseJsonConfigPayload(j.response?.content);
  } catch {
    return null;
  }
}

function syncFetchConfigurationJsonObject(siteId: string, modulePath: string): unknown | null {
  if (typeof XMLHttpRequest === 'undefined') return null;
  const qs =
    '?siteId=' +
    encodeURIComponent(siteId) +
    '&module=' +
    encodeURIComponent('studio') +
    '&path=' +
    encodeURIComponent(modulePath.startsWith('/') ? modulePath.slice(1) : modulePath);
  const xhr = new XMLHttpRequest();
  xhr.open('GET', '/studio/api/2/configuration/get_configuration' + qs, false);
  xhr.withCredentials = true;
  xhr.setRequestHeader('Accept', 'application/json');
  try {
    xhr.send(null);
  } catch {
    return null;
  }
  if (xhr.status < 200 || xhr.status >= 300) return null;
  try {
    const j = JSON.parse(xhr.responseText) as { response?: { content?: unknown } };
    const c = j.response?.content;
    if (c != null && typeof c === 'object' && !Array.isArray(c)) return c;
    if (typeof c === 'string' && c.trim()) return JSON.parse(c) as unknown;
    return null;
  } catch {
    return null;
  }
}

/**
 * Async read (sandbox content APIs) — use in Project Tools and after saves; updates {@link syncReadStudioUiConfig} cache.
 */
export async function fetchStudioUiConfigAsync(siteId: string): Promise<AiAssistantStudioUiConfig> {
  const sid = (siteId || '').trim();
  if (!sid) return { ...DEFAULT_AI_ASSISTANT_STUDIO_UI_CONFIG };
  try {
    const listings = (await firstValueFrom(
      fetchItemsByPath(sid, [STUDIO_UI_CONFIG_SANDBOX_PATH], { preferContent: true })
    )) as unknown as { 0?: unknown; missingItems?: string[] };
    if (Array.isArray(listings.missingItems) && listings.missingItems.includes(STUDIO_UI_CONFIG_SANDBOX_PATH)) {
      const merged = mergeStudioUiConfig(null);
      cache.set(sid, merged);
      return merged;
    }
    if (!listings[0]) {
      const merged = mergeStudioUiConfig(null);
      cache.set(sid, merged);
      return merged;
    }
    const raw = await firstValueFrom(
      fetchContentXML(sid, STUDIO_UI_CONFIG_SANDBOX_PATH, { lock: false }).pipe(catchError(() => of(null)))
    );
    let parsed = parseJsonConfigPayload(raw);
    if (parsed == null) {
      try {
        const xmlStr = await firstValueFrom(fetchConfigurationXML(sid, STUDIO_UI_CONFIG_REL_PATH, 'studio'));
        if (typeof xmlStr === 'string' && xmlStr.trim()) {
          parsed = JSON.parse(xmlStr) as unknown;
        }
      } catch {
        parsed = null;
      }
    }
    const merged = mergeStudioUiConfig(parsed);
    cache.set(sid, merged);
    return merged;
  } catch {
    const merged = mergeStudioUiConfig(null);
    cache.set(sid, merged);
    return merged;
  }
}

/** Synchronous read (cached per site) for Helper / preview bus / autonomous gate. */
export function syncReadStudioUiConfig(siteId: string): AiAssistantStudioUiConfig {
  const sid = (siteId || '').trim();
  if (!sid) return { ...DEFAULT_AI_ASSISTANT_STUDIO_UI_CONFIG };
  if (cache.has(sid)) return cache.get(sid)!;
  const raw = syncFetchStudioUiJsonFromSandbox(sid) ?? syncFetchConfigurationJsonObject(sid, STUDIO_UI_CONFIG_REL_PATH);
  const merged = mergeStudioUiConfig(raw);
  cache.set(sid, merged);
  return merged;
}

export function invalidateStudioUiConfigCache(siteId?: string): void {
  if (siteId?.trim()) {
    cache.delete(siteId.trim());
  } else {
    cache.clear();
  }
}

/** Dispatched on `window` after a successful save of `studio-ui.json` so toolbar/sidebar re-read flags. */
export const STUDIO_UI_CONFIG_CHANGED_EVENT = 'aiassistant:studio-ui-config-changed';

type AiassistantEpochWindow = Window & {
  __aiassistantStudioUiEpochBySite?: Record<string, number>;
};

export function getStudioUiConfigEpochSnapshot(siteId: string): number {
  const sid = (siteId || '').trim();
  if (!sid || typeof window === 'undefined') return 0;
  return (window as AiassistantEpochWindow).__aiassistantStudioUiEpochBySite?.[sid] ?? 0;
}

/** Subscribe to {@link STUDIO_UI_CONFIG_CHANGED_EVENT} for a single site (Tools + Project Tools share `window`). */
export function subscribeStudioUiConfigChanged(siteId: string, onStoreChange: () => void): () => void {
  const sid = (siteId || '').trim();
  if (!sid || typeof window === 'undefined') return () => {};
  const h = (ev: Event) => {
    const d = (ev as CustomEvent<{ siteId?: string }>).detail;
    if (d?.siteId === sid) onStoreChange();
  };
  window.addEventListener(STUDIO_UI_CONFIG_CHANGED_EVENT, h as EventListener);
  return () => window.removeEventListener(STUDIO_UI_CONFIG_CHANGED_EVENT, h as EventListener);
}

export function emitStudioUiConfigChanged(siteId: string): void {
  if (typeof window === 'undefined') return;
  const sid = (siteId || '').trim();
  if (!sid) return;
  const w = window as AiassistantEpochWindow;
  const prev = w.__aiassistantStudioUiEpochBySite ?? {};
  w.__aiassistantStudioUiEpochBySite = { ...prev, [sid]: (prev[sid] ?? 0) + 1 };
  try {
    window.dispatchEvent(
      new CustomEvent(STUDIO_UI_CONFIG_CHANGED_EVENT, { detail: { siteId: sid }, bubbles: true })
    );
  } catch {
    // ignore
  }
}

export function shouldAugmentContentTypeForImagePatch(ct: ContentType, cfg: AiAssistantStudioUiConfig): boolean {
  const scope = cfg.contentTypeImageAugmentationScope ?? 'all';
  if (scope === 'none') return false;
  if (scope === 'all') return true;
  const id = normalizeId(String(ct?.id ?? ''));
  const allow = new Set((cfg.contentTypeIdsForImageAugmentation ?? []).map(normalizeId).filter(Boolean));
  return id ? allow.has(id) : false;
}
