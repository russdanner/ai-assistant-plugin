import { buildStudioAuthHeaders } from './aiAssistantApi';

const IMPORT_SCRIPT_PATH =
  '/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/authoring/import-image-from-url';

export type ImportImageFromUrlResult = {
  ok?: boolean;
  relativeUrl?: string;
  fileName?: string;
  byteLength?: number;
  contentType?: string;
  message?: string;
};

/**
 * Studio {@code PluginController.runScript} wraps the script return map under {@code result}
 * (see {@code ResultConstants.RESULT_KEY_RESULT}).
 */
function unwrapPluginScriptBody<T extends Record<string, unknown>>(body: unknown): T {
  if (!body || typeof body !== 'object') return body as T;
  const o = body as Record<string, unknown>;
  const inner = o.result;
  if (inner && typeof inner === 'object' && !Array.isArray(inner)) return inner as T;
  return body as T;
}

const importPromises = new Map<string, Promise<string>>();

function cacheKey(siteId: string, imageUrl: string, repoPath: string): string {
  return `${siteId}\n${imageUrl}\n${repoPath}`;
}

/**
 * Server downloads {@code imageUrl} and writes under {@code repoPath} (with {@code {yyyy}} macros).
 * Returns the repository path (e.g. {@code /static-assets/...}) suitable for XB asset drag.
 */
export async function importRemoteImageToRepo(
  siteId: string,
  imageUrl: string,
  repoPath: string,
  signal?: AbortSignal
): Promise<string> {
  const trimmedSite = siteId?.trim() || '';
  const trimmedUrl = imageUrl?.trim() || '';
  const trimmedPath = (repoPath || '/static-assets/item/images/{yyyy}/{mm}/{dd}/').trim();
  if (!trimmedSite) throw new Error('importRemoteImageToRepo: missing siteId');
  if (!trimmedUrl) throw new Error('importRemoteImageToRepo: missing imageUrl');

  const key = cacheKey(trimmedSite, trimmedUrl, trimmedPath);
  const existing = importPromises.get(key);
  if (existing) return existing;

  const p = (async () => {
    try {
      const url = `${IMPORT_SCRIPT_PATH}?siteId=${encodeURIComponent(trimmedSite)}`;
      const res = await fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          ...buildStudioAuthHeaders()
        },
        body: JSON.stringify({ imageUrl: trimmedUrl, repoPath: trimmedPath }),
        signal
      });
      const raw = (await res.json().catch(() => ({}))) as unknown;
      const data = unwrapPluginScriptBody<ImportImageFromUrlResult>(raw);
      if (!res.ok || data.ok === false) {
        throw new Error(data.message || `Import failed (${res.status})`);
      }
      const rel = data.relativeUrl?.trim();
      if (!rel) throw new Error('Import response missing relativeUrl');
      return rel;
    } catch (e) {
      importPromises.delete(key);
      throw e;
    }
  })();

  importPromises.set(key, p);
  return p;
}

export function isProbablyRemoteImageUrl(src: string): boolean {
  const s = src?.trim() ?? '';
  return /^https?:\/\//i.test(s);
}

/** True when dropping from chat should run {@link importRemoteImageToRepo} (https URL or raster {@code data:image}). */
export function isImageUrlImportableOnDrop(src: string): boolean {
  const s = src?.trim() ?? '';
  if (!s) return false;
  if (isProbablyRemoteImageUrl(s)) return true;
  return /^data:image\//i.test(s);
}
