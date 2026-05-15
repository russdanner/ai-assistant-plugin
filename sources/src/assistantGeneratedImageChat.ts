/**
 * Sources for {@link AssistantChatGeneratedImages}:
 * 1) SSE {@code studioAiInlineImageUrls} (short https or small data URLs the server can embed in JSON metadata).
 * 2) Same-turn assistant text — large {@code data:image/...;base64,...} from {@code expandInlineImageRefs} is often **not**
 *    duplicated in metadata (size cap); we extract it once for the strip and strip it from markdown so the renderer
 *    is not fed megabyte lines.
 * 3) Ephemeral {@code ![…](https://…)} provider links that never appear in metadata.
 */

const MIN_EXTRACT_IMAGE_CHARS = 200;

const DATA_IMAGE_BASE64_IN_TEXT_RE =
  /data:image\/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+?(?=(?:\s*data:image\/)|(?:\s*[)\]>])|$)/gi;

/** Provider temp links are almost always HTTPS markdown images. */
const HTTPS_IMAGE_IN_MARKDOWN_RE = /!\[[^\]]*\]\(\s*<?\s*(https:\/\/[^)\s>]+)\s*>?\s*\)/gi;

export function resolvedGeneratedImageSources(urls: Record<string, string> | undefined): string[] {
  if (!urls || typeof urls !== 'object') return [];
  const seen = new Set<string>();
  const out: string[] = [];
  const entries = Object.entries(urls).sort(([a], [b]) => a.localeCompare(b));
  for (const [, v] of entries) {
    if (typeof v !== 'string') continue;
    const t = v.trim();
    if (t.length < 13) continue;
    if (t.startsWith('data:image/') || /^https?:\/\//i.test(t)) {
      if (!seen.has(t)) {
        seen.add(t);
        out.push(t);
      }
    }
  }
  return out;
}

function extractDataImageUrlsFromText(text: string | undefined): string[] {
  if (!text || text.indexOf('data:image') < 0) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  DATA_IMAGE_BASE64_IN_TEXT_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = DATA_IMAGE_BASE64_IN_TEXT_RE.exec(text)) !== null) {
    const compact = m[0].replace(/\s+/g, '');
    if (compact.length < MIN_EXTRACT_IMAGE_CHARS) continue;
    if (!seen.has(compact)) {
      seen.add(compact);
      out.push(compact);
    }
  }
  return out;
}

function extractHttpsMarkdownImageUrls(text: string | undefined): string[] {
  if (!text?.trim() || text.indexOf('![') < 0) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  HTTPS_IMAGE_IN_MARKDOWN_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = HTTPS_IMAGE_IN_MARKDOWN_RE.exec(text)) !== null) {
    const u = (m[1] || '').trim();
    if (u.length >= 16 && !seen.has(u)) {
      seen.add(u);
      out.push(u);
    }
  }
  return out;
}

/** Metadata first, then recover large payloads / temp https from stitched assistant text (server omits huge data in SSE JSON). */
export function combineGeneratedImageSources(
  urls: Record<string, string> | undefined,
  tailMarkdown: string | undefined
): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const u of resolvedGeneratedImageSources(urls)) {
    seen.add(u);
    out.push(u);
  }
  for (const u of extractDataImageUrlsFromText(tailMarkdown)) {
    if (!seen.has(u)) {
      seen.add(u);
      out.push(u);
    }
  }
  for (const u of extractHttpsMarkdownImageUrls(tailMarkdown)) {
    if (!seen.has(u)) {
      seen.add(u);
      out.push(u);
    }
  }
  return out;
}

export function stripDataImageUrlsFromText(text: string): string {
  if (!text || text.indexOf('data:image') < 0) return text;
  const cleaned = text.replace(DATA_IMAGE_BASE64_IN_TEXT_RE, (run) =>
    run.replace(/\s+/g, '').length >= MIN_EXTRACT_IMAGE_CHARS ? '' : run
  );
  return cleaned.replace(/\n{3,}/g, '\n\n');
}

function stripHttpsMarkdownImageLinesForUrls(text: string, httpsUrls: readonly string[]): string {
  let out = text;
  for (const raw of httpsUrls) {
    if (!/^https:\/\//i.test(raw)) continue;
    const esc = raw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(`!\\[[^\\]]*\\]\\(\\s*<?\\s*${esc}\\s*>?\\s*\\)`, 'gi'), '');
  }
  return out.replace(/\n{3,}/g, '\n\n');
}

/**
 * Drop payloads rendered in {@link AssistantChatGeneratedImages} so markdown is not asked to parse megabyte lines
 * or duplicate temp URLs.
 */
export function stripDisplayedGeneratedImages(text: string, displayedSources: readonly string[]): string {
  const https = displayedSources.filter((s) => /^https:\/\//i.test(s.trim()));
  let out = stripDataImageUrlsFromText(text);
  out = stripHttpsMarkdownImageLinesForUrls(out, https);
  return out.replace(/\n{3,}/g, '\n\n');
}

/** Remove markdown lines that point at {@code studio-ai-inline-image://…} when metadata supplies the real URL separately. */
export function stripStudioAiInlineImageMarkdownFromText(text: string, urls?: Record<string, string>): string {
  if (!text?.trim() || !urls) return text;
  let out = text;
  for (const id of Object.keys(urls)) {
    if (!id) continue;
    const esc = id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(`!\\[[^\\]]*\\]\\(\\s*<studio-ai-inline-image://${esc}>\\s*\\)`, 'gi'), '');
    out = out.replace(new RegExp(`!\\[[^\\]]*\\]\\(\\s*studio-ai-inline-image://${esc}\\s*\\)`, 'gi'), '');
  }
  return out.replace(/\n{3,}/g, '\n\n');
}
