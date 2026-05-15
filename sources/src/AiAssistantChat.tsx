import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  FormControlLabel,
  IconButton,
  Paper,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
  useTheme
} from '@mui/material';
import { keyframes } from '@mui/material/styles';
import SendRounded from '@mui/icons-material/SendRounded';
import StopRounded from '@mui/icons-material/StopRounded';
import ContentCopyRounded from '@mui/icons-material/ContentCopyRounded';
import ReplayRounded from '@mui/icons-material/ReplayRounded';
import CloseRounded from '@mui/icons-material/CloseRounded';
import AddCommentRounded from '@mui/icons-material/AddCommentRounded';
import MicRounded from '@mui/icons-material/MicRounded';
import AssignmentRounded from '@mui/icons-material/AssignmentRounded';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import useActiveUser from '@craftercms/studio-ui/hooks/useActiveUser';
import useCurrentPreviewItem from '@craftercms/studio-ui/hooks/useCurrentPreviewItem';
import usePreviewGuest from '@craftercms/studio-ui/hooks/usePreviewGuest';
import { fetchContentXML } from '@craftercms/studio-ui/services/content';
import { fetchConfigurationXML } from '@craftercms/studio-ui/services/configuration';
import { fetchGuestModel, reloadRequest } from '@craftercms/studio-ui/state/actions/preview';
import { getHostToGuestBus, getHostToHostBus } from '@craftercms/studio-ui/utils/subjects';
import { firstValueFrom } from 'rxjs';
import {
  AiAssistantChatMessage,
  AiAssistantIncompleteStreamError,
  readCrafterPreviewTokenFromCookie,
  streamChat
} from './aiAssistantApi';
import { formatSessionLogForDebugCopy } from './aiAssistantSessionDebugLog';
import type { ExpertSkillConfig, PromptConfig } from './agentConfig';
import type { AuthoringFormContextSnapshot } from './aiAssistantFormAuthoringTypes';
import MarkdownMessage, { normalizeOpenAiLiteralEscapes } from './MarkdownMessage';
import GenerateImageBlurredPlaceholder from './GenerateImageBlurredPlaceholder';
import AssistantChatGeneratedImages from './AssistantChatGeneratedImages';
import {
  combineGeneratedImageSources,
  stripDisplayedGeneratedImages,
  stripStudioAiInlineImageMarkdownFromText
} from './assistantGeneratedImageChat';
import { getSpeechRecognitionCtor } from './browserSpeechRecognition';

/**
 * Form-engine assistant passes {@link AiAssistantChatProps.getAuthoringFormContext}; XB / ICE does not.
 * Form state lives in the browser until Save; preview chat uses repo paths + server-side tools.
 */
function isFormEngineAuthoringChat(getAuthoringFormContext: unknown): boolean {
  return typeof getAuthoringFormContext === 'function';
}

const MAX_SESSION_STREAM_LOG_LINES = 4000;

function pushStreamLog(logRef: React.MutableRefObject<string[]>, line: string) {
  const arr = logRef.current;
  arr.push(line);
  if (arr.length > MAX_SESSION_STREAM_LOG_LINES) {
    arr.splice(0, arr.length - MAX_SESSION_STREAM_LOG_LINES);
  }
}

/** Nearest ancestor that scrolls (e.g. ICE `ResizeableDrawer` drawerBody uses overflow-y: auto). */
function getScrollParent(node: HTMLElement | null): HTMLElement | null {
  let el: HTMLElement | null = node?.parentElement ?? null;
  while (el) {
    const { overflowY, overflow } = getComputedStyle(el);
    const oy = overflowY || overflow;
    if (oy === 'auto' || oy === 'scroll' || oy === 'overlay') return el;
    el = el.parentElement;
  }
  return null;
}

/** Best-effort: Studio preview item → human-readable content-type label (shape varies by Studio version). */
function resolvePreviewContentTypeLabel(item: unknown): string | undefined {
  if (!item || typeof item !== 'object') return undefined;
  const o = item as Record<string, unknown>;
  const nestedCt = o.contentType;
  if (nestedCt && typeof nestedCt === 'object') {
    const ct = nestedCt as Record<string, unknown>;
    for (const k of ['label', 'name', 'displayName', 'internalName']) {
      const v = ct[k];
      if (typeof v === 'string' && v.trim()) return v.trim();
    }
  }
  for (const k of ['contentTypeLabel', 'contentTypeName', 'typeLabel', 'contentTypeDisplayName']) {
    const v = o[k];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  return undefined;
}

/** Studio XB shell: `…/studio/preview#/?page=…&site=…` (matches the author’s address bar). */
function studioPreviewShellUrlLooksLikeAuthorBar(u: string): boolean {
  const low = u.toLowerCase();
  return low.includes('/studio/preview') && u.includes('#');
}

/**
 * Prefer the real Studio address bar when the chat runs in the top Studio window; otherwise omit (server synthesizes).
 */
function pickStudioPreviewPageUrlForServer(previewItem: unknown): string | undefined {
  const fromItem =
    previewItem && typeof previewItem === 'object' && typeof (previewItem as { previewUrl?: string }).previewUrl === 'string'
      ? (previewItem as { previewUrl: string }).previewUrl.trim()
      : '';
  const fromWin =
    typeof window !== 'undefined' && typeof window.location?.href === 'string' ? window.location.href.trim() : '';
  for (const c of [fromItem, fromWin]) {
    if (c && studioPreviewShellUrlLooksLikeAuthorBar(c)) return c;
  }
  return undefined;
}

/** Rough markdown → plain text for speech synthesis (avoid reading asterisks and code fences). */
function textForSpeechSynthesis(markdown: string): string {
  if (!markdown) return '';
  let s = markdown
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/\[(.*?)\]\([^)]*\)/g, '$1')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*\d+\.\s+/gm, '')
    .replace(/<[^>]+>/g, ' ');
  s = s.replace(/\s+/g, ' ').trim();
  return s;
}

function isSpeechSynthesisAvailable(): boolean {
  return typeof window !== 'undefined' && typeof window.speechSynthesis !== 'undefined';
}

/** True when `fetch` / stream read failed because the {@link AbortSignal} fired. */
function isFetchAbortError(e: unknown): boolean {
  if (typeof DOMException !== 'undefined' && e instanceof DOMException && e.name === 'AbortError') return true;
  if (e instanceof Error && e.name === 'AbortError') return true;
  const msg = e instanceof Error ? e.message : String(e);
  return /aborted a request|AbortError|signal is aborted/i.test(msg);
}

/** First line only, capped — avoid dumping stack traces into the chat bubble. */
function sanitizeErrorForAuthor(errText: string): string {
  const t = errText.trim();
  if (!t) return 'See Studio logs for details.';
  const first = t.split('\n')[0].trim();
  if (first.length > 320) return `${first.slice(0, 317)}…`;
  return first;
}

/** Server-injected debug block — omit from the chat bubble. */
function stripOrchestrationDebugComment(text: string): string {
  if (!text) return text;
  return text
    .replace(/<!--CRAFTERRQ_ORCH[\s\S]*?-->/gi, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/**
 * The plan usually streams into {@link UiMessage.assistantPreToolsText}; the final chunk often repeats ## Plan.
 * Show the post-tool section once (prefer ## Plan Execution onward).
 */
function dedupeAssistantPostToolsMarkdown(preTools: string | undefined, tail: string): string {
  const t0 = stripOrchestrationDebugComment(tail);
  const pre = (preTools ?? '').trim();
  if (!pre || !t0) return t0;
  if (!/^##\s*plan\b/im.test(pre)) return t0;

  const nlExec = /\n##\s*Plan Execution\b/im.exec(t0);
  if (nlExec) {
    const prefix = t0.slice(0, nlExec.index);
    // Do not drop intro / markdown images that appear before "## Plan Execution" (dedupe is plan-shape only).
    if (/!\[[^\]]*\]\([^)]+\)|studio-ai-inline-image:|data:image\//i.test(prefix)) {
      return t0;
    }
    return t0.slice(nlExec.index + 1).trimStart();
  }
  if (/^##\s*Plan Execution\b/im.test(t0)) return t0.trimStart();

  const nlPlan = /\n##\s*Plan\b/im.exec(t0);
  if (nlPlan) {
    const tailFrom = t0.slice(nlPlan.index + 1);
    const next = tailFrom.search(/\n##\s+/m);
    if (next >= 0) return tailFrom.slice(next + 1).trimStart();
  }
  if (/^##\s*Plan\b/im.test(t0)) {
    const next = t0.search(/\n##\s+/m);
    if (next >= 0) return t0.slice(next + 1).trimStart();
  }
  return t0;
}

/**
 * Drops memorized lazy meta-plan lines some models still emit (matches server-side plan gate / strip).
 * Defense when Studio runs an older plugin Groovy build or a provider path skips server sanitization.
 */
function stripForbiddenLazyPlanLines(raw: string): string {
  if (!raw?.trim()) return raw;

  const normLine = (s: string) =>
    s
      .trim()
      .replace(/\u00a0/g, ' ')
      .replace(/\s+/g, ' ')
      .toLowerCase();

  const lineForbidden = (trimmed: string): boolean => {
    const n = normLine(trimmed);
    if (!n) return false;
    if (n.includes('execute the user request using the cms tools described in the studio authoring system message')) {
      return true;
    }
    if (n.includes('execute the user request using the cms tools described')) return true;
    if (n.includes('execute the user request using the cms tool described')) return true;
    if (n.includes('using the cms tools described in the studio authoring system message')) return true;
    if (n.includes('using the cms tool described in the studio authoring system message')) return true;
    if (n.includes('execute the user request') && n.includes('cms tool') && n.includes('system message')) {
      return true;
    }
    if (n.includes('execute the user request') && n.includes('studio authoring') && n.includes('message')) {
      return true;
    }
    return false;
  };

  const lines = raw.split(/\r?\n/);
  const out: string[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();
    if (lineForbidden(trimmed)) {
      i++;
      continue;
    }
    const tl = trimmed.toLowerCase();
    const planOnly =
      tl === 'plan' ||
      tl === 'plan:' ||
      tl === '**plan**' ||
      /^#+\s*plan\s*$/i.test(tl);
    if (planOnly) {
      let j = i + 1;
      while (j < lines.length && !lines[j].trim()) {
        j++;
      }
      if (j < lines.length && lineForbidden(lines[j]!.trim())) {
        i = j + 1;
        continue;
      }
    }
    out.push(line);
    i++;
  }
  return out.join('\n').replace(/\n{3,}/g, '\n\n');
}

function assistantVisibleTextLen(m: {
  text?: string;
  toolProgressText?: string;
  assistantPreToolsText?: string;
  reasoningStreamText?: string;
}): number {
  return (
    (m.assistantPreToolsText || '').length +
    (m.toolProgressText || '').length +
    (m.text || '').length +
    (m.reasoningStreamText || '').length
  );
}

/** Collapse whitespace and case for deduping 📋 chips (pre-tools ## Plan vs post-tools ## Plan Execution often repeat the same steps). */
function verificationPromptDedupeKey(step: string): string {
  return step
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .replace(/[…]+$/u, '')
    .replace(/\.+$/u, '')
    .trim();
}

/**
 * Pulls 📋 lines from ## Plan / ## Plan Execution so authors can one-click re-run a verification step as a new prompt.
 * At most **three** chips — combined markdown often contains the same checklist twice (plan + execution).
 */
function extractVerificationPrompts(markdown: string): string[] {
  const raw = (markdown || '').trim();
  if (!raw) return [];
  const lines = raw.split('\n');
  const out: string[] = [];
  const seen = new Set<string>();
  let inPlanSection = false;
  const max = 3;
  for (const line of lines) {
    if (out.length >= max) break;
    const t = line.trim();
    if (/^##\s+plan\b/i.test(t) || /^##\s+plan\s+execution\b/i.test(t)) {
      inPlanSection = true;
      continue;
    }
    if (/^##\s+/i.test(t)) {
      inPlanSection = false;
      continue;
    }
    if (!inPlanSection) continue;
    const m = t.match(/^📋\s*(.+)$/);
    if (!m) continue;
    const step = m[1]
      .replace(/✅\s*$/u, '')
      .replace(/❌\s*$/u, '')
      .replace(/⚠️\s*$/u, '')
      .replace(/⬜\s*$/u, '')
      .replace(/\s+/g, ' ')
      .trim();
    if (step.length < 8) continue;
    const key = verificationPromptDedupeKey(step);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(step);
  }
  return out;
}

/** Strip common markdown bold from a line for trigger matching. */
function stripMarkdownBold(s: string): string {
  return (s || '').replace(/\*\*/g, '').trim();
}

/**
 * True when the line introduces optional follow-up actions (not a checklist 📋 step).
 */
function isFollowUpTriggerLine(trimmed: string): boolean {
  const n = stripMarkdownBold(trimmed);
  if (!n) return false;
  if (/^would you like (?:me )?to\b/i.test(n)) return true;
  if (/^open items\b/i.test(n)) return true;
  if (/^next steps\b/i.test(n)) return true;
  if (/^what would you like\b/i.test(n)) return true;
  if (/^optional\b/i.test(n) && /:/.test(n)) return true;
  return false;
}

/**
 * Plain-line follow-up after "Would you like…" — avoid capturing section labels or preview URLs.
 */
function looksLikeFollowUpPromptClause(t: string): boolean {
  const s = t.trim();
  if (s.length < 10) return false;
  if (/^https?:\/\//i.test(s)) return false;
  if (/^[-*]\s*📋/.test(s)) return false;
  if (/^(review|preview|open)\b/i.test(s) && /:?\s*$/i.test(s) && s.length < 55) return false;
  if (/[?]$/.test(s)) return true;
  return /^(mark|generate|add|publish|set|update|create|remove|delete|move|rename|link|unlink|feature|include|exclude|attach|detach|schedule|unschedule|localize|translate|revert|undo)\b/i.test(
    s
  );
}

/**
 * Pulls suggested follow-up lines after "Would you like me to:" / "Open items…" / etc.
 * so authors can one-click send them like 📋 verification chips.
 */
function extractFollowUpActionPrompts(markdown: string): string[] {
  const raw = normalizeOpenAiLiteralEscapes((markdown || '').trim());
  if (!raw) return [];
  const lines = raw.split(/\r?\n/);
  const out: string[] = [];
  const seen = new Set<string>();
  let mode: 'scan' | 'collect' = 'scan';
  let blankRun = 0;
  const max = 8;

  for (const rawLine of lines) {
    const t = rawLine.trim();
    if (!t) {
      if (mode === 'collect') {
        blankRun++;
        if (blankRun >= 2) break;
      }
      continue;
    }
    blankRun = 0;

    if (t.startsWith('<!--') || /^##\s+/.test(t) || /^🛠️/.test(t)) {
      if (mode === 'collect') break;
      continue;
    }

    if (mode === 'scan') {
      if (isFollowUpTriggerLine(t)) {
        mode = 'collect';
      }
      continue;
    }

    // collect
    let item = '';
    const listHyphen = t.match(/^\s*[-*]\s+(.+)$/);
    const listNum = t.match(/^\s*\d+[.)]\s+(.+)$/);
    if (listHyphen) item = listHyphen[1].trim();
    else if (listNum) item = listNum[1].trim();
    else if (looksLikeFollowUpPromptClause(t)) item = t;
    else continue;

    if (item.length < 8) continue;
    if (/^https?:\/\//i.test(item)) continue;
    const key = verificationPromptDedupeKey(item);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(item);
    if (out.length >= max) break;
  }
  return out;
}

type PromptMacrosContext = {
  dateToday: string;
  timeNow: string;
  currentPage: string;
  currentUsername: string;
  /** Repository path of the item open in preview (e.g. /site/website/about/index.xml). */
  currentContentPath: string;
  siteId: string;
};

async function copyToClipboard(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
  }
}

function expandPromptMacros(input: string, ctx: PromptMacrosContext): string {
  if (!input) return input;
  return input
    .split('DATE_TODAY').join(ctx.dateToday)
    .split('TIME_NOW').join(ctx.timeNow)
    .split('CURRENT_PAGE').join(ctx.currentPage)
    .split('CURRENT_CONTENT_PATH').join(ctx.currentContentPath)
    .split('CURRENT_USERNAME').join(ctx.currentUsername);
}

/** Normalize content type id to path form (e.g. "page/home" -> "/page/home"). */
function normalizeContentTypeId(id: string): string {
  const t = (id || '').trim();
  return t.startsWith('/') ? t : `/${t}`;
}

/**
 * Fetch form definition XML for a content type from Studio config.
 * Path: /content-types/{contentTypeId}/form-definition.xml (studio module).
 */
async function fetchFormDefinitionXml(siteId: string, contentTypeId: string): Promise<string> {
  const normalized = normalizeContentTypeId(contentTypeId);
  const path = `/content-types${normalized}/form-definition.xml`;
  const xml = await firstValueFrom(fetchConfigurationXML(siteId, path, 'studio'));
  return xml ?? '';
}

const CONTENT_TYPE_MACRO_PATTERN = /CONTENT_TYPE:([a-zA-Z0-9/_.-]+)/g;

/**
 * Expand CURRENT_CONTENT_TYPE and CONTENT_TYPE:name macros by loading form definitions.
 * CURRENT_CONTENT_TYPE uses the current preview item's content type.
 * CONTENT_TYPE:page/home (etc.) loads the form for that content type path.
 */
async function expandContentTypeMacros(
  prompt: string,
  siteId: string,
  currentContentTypeId: string | undefined
): Promise<string> {
  if (!prompt || (!prompt.includes('CURRENT_CONTENT_TYPE') && !prompt.includes('CONTENT_TYPE:')))
    return prompt;

  let out = prompt;

  if (out.includes('CURRENT_CONTENT_TYPE')) {
    const ct = (currentContentTypeId || '').trim() ? normalizeContentTypeId(currentContentTypeId!) : '';
    try {
      const xml = ct ? await fetchFormDefinitionXml(siteId, ct) : '';
      out = out.split('CURRENT_CONTENT_TYPE').join(xml || '[No form definition for current item]');
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      out = out.split('CURRENT_CONTENT_TYPE').join(`[Form definition unavailable: ${msg}]`);
    }
  }

  const namedMatches = [...out.matchAll(CONTENT_TYPE_MACRO_PATTERN)];
  for (const m of namedMatches) {
    const full = m[0];
    const contentTypeId = m[1];
    try {
      const xml = await fetchFormDefinitionXml(siteId, contentTypeId);
      out = out.replace(full, xml || `[No form definition for ${contentTypeId}]`);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      out = out.replace(full, `[Form definition unavailable for ${contentTypeId}: ${msg}]`);
    }
  }

  return out;
}

/**
 * Replace content-type macros in display text with short placeholders so the chat bubble doesn't show full XML.
 */
function expandContentTypeMacrosForDisplay(
  text: string,
  currentContentTypeId: string | undefined
): string {
  if (!text) return text;
  let out = text;
  if (out.includes('CURRENT_CONTENT_TYPE')) {
    const label = (currentContentTypeId || '').trim() || 'current item';
    out = out.split('CURRENT_CONTENT_TYPE').join(`[Form: ${label}]`);
  }
  out = out.replace(CONTENT_TYPE_MACRO_PATTERN, (_, id) => `[Form: ${id}]`);
  return out;
}

/**
 * Fetch raw content XML for a content item by path (e.g. /site/website/index.xml).
 */
async function fetchContentXml(siteId: string, path: string): Promise<string> {
  const normalized = (path || '').trim();
  if (!normalized.startsWith('/')) return '';
  const xml = await firstValueFrom(fetchContentXML(siteId, normalized));
  return xml ?? '';
}

/** Matches CONTENT:/site/website/index.xml or CONTENT:/site/components/foo.xml */
const CONTENT_PATH_MACRO_PATTERN = /CONTENT:(\/[^\s]+)/g;

/** Compare repo paths for macro substitution (form `path` vs CONTENT:… token). */
function cqNormalizeStudioContentPath(p: string | undefined): string {
  let s = (p || '').trim();
  if (!s) return '';
  try {
    s = decodeURIComponent(s);
  } catch {
    /* ignore */
  }
  if (!s.startsWith('/')) s = `/${s}`;
  return s.replace(/\/+/g, '/');
}

/** When set (form-engine assistant), content macros use the live form model instead of fetching XML from the repo. */
type LiveAuthoringMacroSource = {
  contentItemPath?: string;
  fieldValuesJson?: string;
  /** Prefer over JSON — `CStudioForms.Util.serializeModelToXml(form, false)`. */
  serializedContentXml?: string;
};

function buildLiveContentMacroSubstitution(live: LiveAuthoringMacroSource | undefined): string | undefined {
  const path = (live?.contentItemPath || '').trim();
  const hasXml = Boolean((live?.serializedContentXml || '').trim());
  const fj = (live?.fieldValuesJson || '').trim();
  const hasJson = Boolean(fj && fj !== '{}');
  if (!path && !hasXml && !hasJson) return undefined;
  return (
    '[Studio form — **full XML/JSON omitted from prompt** for size. Use **GetContent** for saved repo bodies; the **Current Studio content form** appendix lists **field ids** and **linked paths** only. ' +
    'Unsaved edits are not inlined — **GetContent** is git until the author Saves (or apply via `aiassistantFormFieldUpdates` using ids from the appendix / form definition).]\n' +
    (path ? `Open item path: ${path}` : '')
  );
}

/** Appended to the API prompt only (not the visible user message) when embedded in the form-engine control. */
/** Cap when scanning form-definition XML for `<field>` / `<id>` extraction only (body is not sent). */
const MAX_FORM_DEF_PARSE_CHARS = 600_000;
/** Cap when scanning serialized content XML for `<key>` paths only (body is not sent). */
const MAX_SERIALIZED_XML_KEY_SCAN_CHARS = 2_000_000;
const MAX_METADATA_LINE_CHARS = 12_000;

function truncateMetadataLine(s: string, max: number): string {
  if (!s) return '';
  if (s.length <= max) return s;
  return `${s.slice(0, max)} … [+${s.length - max} chars omitted]`;
}

/**
 * Field element ids from form-definition.xml (structure only — no values sent on the wire).
 */
function extractFormFieldIdsFromDefinitionXml(definitionXml: string): string[] {
  const s = definitionXml.slice(0, MAX_FORM_DEF_PARSE_CHARS);
  const seen = new Set<string>();
  const out: string[] = [];
  const fieldRe = /<field\b[^>]*>([\s\S]*?)<\/field>/gi;
  let m: RegExpExecArray | null;
  while ((m = fieldRe.exec(s)) !== null) {
    const idm = /<id>([^<]+)<\/id>/i.exec(m[1]);
    if (!idm) continue;
    const id = idm[1].trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
  }
  return out;
}

/**
 * Repository paths referenced by node-selector-style `<key>` elements (metadata only).
 */
function extractLinkedRepoPathsFromSerializedContentXml(xml: string): string[] {
  const s = xml.slice(0, MAX_SERIALIZED_XML_KEY_SCAN_CHARS);
  const keys = new Set<string>();
  const re = /<key>(\/site\/[^<]+)<\/key>/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(s)) !== null) {
    const k = m[1].trim();
    if (k) keys.add(k);
  }
  return [...keys];
}

function extractShallowKeysFromFieldValuesJson(jsonStr: string): string[] {
  const raw = jsonStr.trim();
  if (!raw || raw === '{}') return [];
  try {
    const o = JSON.parse(raw) as Record<string, unknown>;
    if (!o || typeof o !== 'object' || Array.isArray(o)) return [];
    return Object.keys(o).filter((k) => k && typeof k === 'string');
  } catch {
    return [];
  }
}

/** Appended to the prompt appendix only when `formEngineClientJsonApply` is enabled (form assistant, not XB). */
const FORM_ENGINE_APPLY_INSTRUCTIONS = `
**Client-side form apply:** When you change field content (including **translate** to another language), end with fenced JSON (field ids from the form definition / XML; string values only). **Do not** reply with generic CrafterCMS docs ("Translation Configuration", "open the content item", "add a language") instead of the translated text in JSON.

\`\`\`json
{ "aiassistantFormFieldUpdates": { "title_t": "…", "body_html": "<p>…</p>" } }
\`\`\`
List every field you changed; omit the block for pure Q&A. Repeat groups: ids like "sections_o|0|section_html".`;

function buildAuthoringFormAppendix(
  ctx: AuthoringFormContextSnapshot | undefined,
  options?: { includeClientJsonApplyInstructions?: boolean }
): string {
  if (!ctx) return '';
  const ct = (ctx.contentTypeId || '').trim();
  const def = (ctx.definitionXml || '').trim();
  const vals = (ctx.fieldValuesJson || '').trim();
  const ser = (ctx.serializedContentXml || '').trim();
  const itemPath = (ctx.contentPath || '').trim();
  const hasDef = Boolean(def);
  const hasVals = Boolean(vals && vals !== '{}');
  const hasSer = Boolean(ser);
  if (!hasDef && !hasVals && !hasSer && !ct && !itemPath) return '';
  const lines: string[] = [];
  lines.push(
    '--- Current Studio content form (metadata only — no full XML/JSON bodies; use GetContent / GetContentTypeFormDefinition / update_content) ---'
  );
  lines.push(
    'The author may have **unsaved** edits in the form; values are **not** copied here. Plan using paths and ids below, then read bodies with tools.'
  );
  if (ct) lines.push(`Content type id: ${ct}`);
  if (itemPath) lines.push(`Content item path (open in form): ${itemPath}`);
  if (hasDef) {
    const fieldIds = extractFormFieldIdsFromDefinitionXml(def);
    if (fieldIds.length) {
      lines.push(
        `Form field ids (from form-definition; no values): ${truncateMetadataLine(fieldIds.join(', '), MAX_METADATA_LINE_CHARS)}`
      );
    } else {
      lines.push('Form field ids: (none extracted — use GetContentTypeFormDefinition with contentPath.)');
    }
  }
  if (hasSer) {
    const linked = extractLinkedRepoPathsFromSerializedContentXml(ser);
    if (linked.length) {
      lines.push(
        `Linked repository paths (from <key> in serialized item; no XML bodies): ${truncateMetadataLine(linked.join(', '), MAX_METADATA_LINE_CHARS)}`
      );
    }
  }
  if (hasVals) {
    const keys = extractShallowKeysFromFieldValuesJson(vals);
    if (keys.length) {
      lines.push(
        `In-memory model top-level keys (no values): ${truncateMetadataLine(keys.join(', '), MAX_METADATA_LINE_CHARS)}`
      );
    }
  }
  if (options?.includeClientJsonApplyInstructions) {
    lines.push(FORM_ENGINE_APPLY_INSTRUCTIONS.trim());
  }
  lines.push('--- End Studio content form ---');
  return '\n\n' + lines.join('\n');
}

/**
 * Parse assistant reply for `aiassistantFormFieldUpdates` inside a ```json fenced block.
 */
function tryExtractAiassistantFormFieldUpdates(assistantText: string): Record<string, string> | null {
  const marker = 'aiassistantFormFieldUpdates';
  if (!assistantText.includes(marker)) return null;
  const re = /```(?:json)?\s*([\s\S]*?)```/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(assistantText)) !== null) {
    try {
      const obj = JSON.parse(m[1].trim()) as unknown;
      if (!obj || typeof obj !== 'object' || Array.isArray(obj)) continue;
      const raw = (obj as { aiassistantFormFieldUpdates?: unknown }).aiassistantFormFieldUpdates;
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue;
      const out: Record<string, string> = {};
      for (const [k, v] of Object.entries(raw)) {
        if (typeof k !== 'string' || !k.trim()) continue;
        if (v == null) out[k] = '';
        else if (typeof v === 'string') out[k] = v;
        else if (typeof v === 'number' || typeof v === 'boolean') out[k] = String(v);
      }
      return Object.keys(out).length ? out : null;
    } catch {
      /* try next fence */
    }
  }
  return null;
}

/**
 * Expand CURRENT_CONTENT and CONTENT:path macros.
 * In the form-engine assistant (`liveAuthoring` set), uses the live form `model` JSON for the item being edited
 * instead of loading repository XML (saved file would miss unsaved edits).
 * Otherwise loads raw content XML from the repo (preview / dialog context).
 */
async function expandContentMacros(
  prompt: string,
  siteId: string,
  currentContentPath: string | undefined,
  liveAuthoring?: LiveAuthoringMacroSource | null
): Promise<string> {
  if (!prompt || (!prompt.includes('CURRENT_CONTENT') && !prompt.includes('CONTENT:/')))
    return prompt;

  const liveBody = buildLiveContentMacroSubstitution(liveAuthoring);
  const editingPathNorm = cqNormalizeStudioContentPath(liveAuthoring?.contentItemPath);

  let out = prompt;

  if (out.includes('CURRENT_CONTENT')) {
    if (liveBody) {
      out = out.split('CURRENT_CONTENT').join(liveBody);
    } else {
      const path = (currentContentPath || '').trim();
      try {
        const xml = path ? await fetchContentXml(siteId, path) : '';
        out = out.split('CURRENT_CONTENT').join(xml || '[No content for current item]');
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        out = out.split('CURRENT_CONTENT').join(`[Content unavailable: ${msg}]`);
      }
    }
  }

  const pathMatches = [...out.matchAll(CONTENT_PATH_MACRO_PATTERN)];
  for (const m of pathMatches) {
    const full = m[0];
    const itemPath = m[1];
    const itemNorm = cqNormalizeStudioContentPath(itemPath);
    if (liveBody && editingPathNorm && itemNorm === editingPathNorm) {
      out = out.replace(full, liveBody);
      continue;
    }
    try {
      const xml = await fetchContentXml(siteId, itemPath);
      out = out.replace(full, xml || `[No content at ${itemPath}]`);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      out = out.replace(full, `[Content unavailable for ${itemPath}: ${msg}]`);
    }
  }

  return out;
}

/**
 * Replace content macros in display text with short placeholders.
 */
function expandContentMacrosForDisplay(
  text: string,
  currentContentPath: string | undefined
): string {
  if (!text) return text;
  let out = text;
  if (out.includes('CURRENT_CONTENT')) {
    const label = (currentContentPath || '').trim() || 'current item';
    out = out.split('CURRENT_CONTENT').join(`[Content: ${label}]`);
  }
  out = out.replace(CONTENT_PATH_MACRO_PATTERN, (_, p) => `[Content: ${p}]`);
  return out;
}

/**
 * Ask Studio preview to refresh after successful repository writes.
 * Mirrors Studio 4.x PreviewAddressBar `onRefresh` behavior.
 */
function triggerStudioPreviewReload(): void {
  const action = reloadRequest();
  getHostToGuestBus().next(action);
  getHostToHostBus().next(action);
}

/** Repository path for a page content item (Studio can switch preview via {@link fetchGuestModel}). */
function isWebsitePageRepoPath(repoPath: string): boolean {
  const p = (repoPath || '').trim();
  return p.startsWith('/site/website/') && p.endsWith('/index.xml');
}

/**
 * Move Studio preview to the written page item so authors see the new URL, not only a reload of the prior frame.
 * Still followed at stream end by {@link triggerStudioPreviewReload} for a fresh load.
 */
function navigateStudioPreviewToRepoPath(repoPath: string): void {
  if (!isWebsitePageRepoPath(repoPath)) return;
  const action = fetchGuestModel({ path: repoPath.trim() });
  getHostToGuestBus().next(action);
  getHostToHostBus().next(action);
}

/**
 * Tool names that finish with phase {@code done} after mutating the site sandbox (or static assets).
 * Each completion triggers an immediate {@link triggerStudioPreviewReload} so authors see work in progress;
 * the stream still ends with another refresh if any of these ran ({@link shouldRefreshPreview}).
 * {@code WriteContent} covers templates (.ftl), scripts (.groovy), Studio config XML, content-type form
 * definitions, and content items; for a written {@code /site/website/.../index.xml} path, preview also runs
 * {@link fetchGuestModel} so the frame tracks the new page.
 */
const PREVIEW_RELOAD_TOOL_NAMES_ON_DONE = new Set<string>([
  'WriteContent',
  'revert_change',
  'GenerateImage',
  'publish_content',
  'update_content',
  'update_template',
  'update_content_type',
  'TranslateContentItem',
  'TranslateContentBatch',
  'TransformContentSubgraph',
  'GetContentSubgraph'
]);

const toolProgressScrollBoxSx = {
  maxHeight: 200,
  overflowY: 'auto',
  mb: 1,
  px: 1,
  py: 0.75,
  borderRadius: 1,
  border: 1,
  borderColor: 'divider',
  bgcolor: 'action.hover'
} as const;

/** Visible “alive” pulse on the pipeline wait row (in addition to {@link CircularProgress}). */
const cqPipelineHeartbeatBarPulse = keyframes({
  '0%, 100%': { opacity: 1, filter: 'brightness(1)' },
  '50%': { opacity: 0.88, filter: 'brightness(1.06)' }
});

/**
 * True once the server emitted a terminal {@code GenerateImage} tool-progress row (✅ finished, ❌, or ⚠️).
 * Scans all lines so a later debug/flatten block mentioning GenerateImage cannot keep the shimmer stuck on.
 */
function generateImageToolSettledInToolProgress(toolProgressText: string | undefined): boolean {
  if (!toolProgressText?.trim()) return false;
  for (const raw of toolProgressText.split('\n')) {
    const L = raw.trim();
    if (!L.includes('🛠️') || !L.includes('GenerateImage') || !/\*\*GenerateImage\*\*/.test(L)) continue;
    if (/\bfinished\b/i.test(L)) return true;
    if (/❌\s*\*\*GenerateImage\*\*/.test(L)) return true;
    if (/⚠️\s*\*\*GenerateImage\*\*/.test(L)) return true;
    if (/✅\s*\*\*GenerateImage\*\*/.test(L)) return true;
  }
  return false;
}

/**
 * True when the latest server-injected {@code GenerateImage} line is the running “…” row, not {@code finished} /
 * warning / error.
 */
function isGenerateImageRunRowActive(toolProgressText: string | undefined): boolean {
  if (generateImageToolSettledInToolProgress(toolProgressText)) return false;
  const s = toolProgressText?.trim();
  if (!s?.includes('GenerateImage')) return false;
  const lines = s.split(/\n/).map((l) => l.trim());
  for (let i = lines.length - 1; i >= 0; i--) {
    const L = lines[i];
    if (!L.includes('GenerateImage')) continue;
    if (/\bfinished\b/i.test(L)) return false;
    if (L.includes('\u26A0\uFE0F') || L.includes('\u274C')) return false;
    // Server uses Unicode ellipsis U+2026 in " …\n"; `/…/` in JS is three wildcards — must match U+2026 or ASCII "..."
    if (/\*\*GenerateImage\*\*/.test(L) && (/\u2026/.test(L) || /\.{3}/.test(L))) return true;
    return false;
  }
  return false;
}

/**
 * True when every {@code studio-ai-inline-image://id} in markdown has a corresponding URL from server tool-progress metadata.
 */
function studioAiInlineImageRefsResolvedInMap(
  tailMarkdown: string,
  urls: Record<string, string> | undefined
): boolean {
  if (!urls || typeof urls !== 'object') return false;
  const re = /studio-ai-inline-image:\/\/([^)\s<>]+)/gi;
  const ids: string[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(tailMarkdown)) !== null) {
    ids.push(m[1]);
  }
  if (!ids.length) return false;
  return ids.every((id) => typeof urls[id] === 'string' && urls[id].length > 12);
}

/**
 * Merge {@code studioAiInlineImageUrls} objects from SSE metadata (one map per terminal GenerateImage tool row).
 */
function mergeStudioAiInlineImageUrlMetadata(
  prior: Record<string, string> | undefined,
  incoming: unknown
): Record<string, string> | undefined {
  if (incoming == null || typeof incoming !== 'object' || Array.isArray(incoming)) return prior;
  const next: Record<string, string> = { ...(prior || {}) };
  let added = false;
  for (const [k, v] of Object.entries(incoming as Record<string, unknown>)) {
    if (typeof v === 'string' && v.length > 0) {
      next[k] = v;
      added = true;
    }
  }
  if (!added) return prior;
  return next;
}

function hasStudioAiInlineImageUrlPayload(v: unknown): boolean {
  return !!(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v as Record<string, unknown>).length > 0);
}

function studioAiInlineUrlsPatch(
  m: { studioAiInlineImageUrls?: Record<string, string> },
  incoming: unknown
): Partial<{ studioAiInlineImageUrls: Record<string, string> }> {
  const merged = mergeStudioAiInlineImageUrlMetadata(m.studioAiInlineImageUrls, incoming);
  if (merged === undefined) return {};
  return { studioAiInlineImageUrls: merged };
}

/**
 * True when {@code markdown} already contains a complete GFM image link the renderer can show (replaces placeholder).
 */
function hasCompleteMarkdownInlineImage(markdown: string | undefined): boolean {
  if (!markdown?.trim()) return false;
  const re = /!\[[^\]]*\]\(\s*(?:<([^>]+)>|([^)]+))\s*\)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(markdown)) !== null) {
    const url = (m[1] || m[2] || '').trim();
    if (/^data:image\//i.test(url) && url.length > 120) return true;
    if (/^https?:\/\//i.test(url) && url.length > 12) return true;
    if (/^studio-ai-blob-ref:\/\//i.test(url)) return true;
  }
  if (/data:image\/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=]{120,}/i.test(markdown)) return true;
  return false;
}

/**
 * Keep the shimmer until the strip has sources or markdown already shows a loadable image.
 */
function shouldShowGenerateImagePlaceholder(
  toolProgressText: string | undefined,
  tailMarkdown: string,
  studioAiInlineImageUrls?: Record<string, string>
): boolean {
  if (combineGeneratedImageSources(studioAiInlineImageUrls, tailMarkdown).length > 0) return false;
  if (hasCompleteMarkdownInlineImage(tailMarkdown)) return false;
  if (studioAiInlineImageRefsResolvedInMap(tailMarkdown, studioAiInlineImageUrls)) return false;
  if (!toolProgressText?.includes('GenerateImage')) return false;
  if (isGenerateImageRunRowActive(toolProgressText)) return true;
  if (generateImageToolSettledInToolProgress(toolProgressText) && /studio-ai-inline-image:\/\//i.test(tailMarkdown)) {
    return true;
  }
  return false;
}

/** Keeps the tool-progress list pinned to the latest line as SSE chunks append. */
function ToolProgressScrollArea(props: Readonly<{ text: string }>) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [props.text]);
  return (
    <Box ref={ref} sx={toolProgressScrollBoxSx}>
      <MarkdownMessage text={props.text} />
    </Box>
  );
}

/** In-bubble wait row updated by SSE `pipeline-heartbeat` (MUI spinner = reliable animation vs nested sx @keyframes). */
function PipelineHeartbeatBar(props: Readonly<{ elapsedSec: number; nextInSec: number; hint: string }>) {
  const { elapsedSec, nextInSec, hint } = props;
  return (
    <Box
      role="status"
      aria-live="polite"
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 1.25,
        mb: 1,
        py: 1,
        px: 1.25,
        borderRadius: 1,
        border: '1px solid',
        borderColor: 'primary.light',
        bgcolor: (t) => (t.palette.mode === 'dark' ? 'rgba(144, 202, 249, 0.12)' : 'rgba(25, 118, 210, 0.09)'),
        animation: `${cqPipelineHeartbeatBarPulse} 1.35s ease-in-out infinite`,
        '@media (prefers-reduced-motion: reduce)': {
          animation: 'none',
          opacity: 1
        }
      }}
    >
      <CircularProgress
        variant="indeterminate"
        size={18}
        thickness={4.5}
        aria-hidden
        sx={{ color: 'primary.main', flexShrink: 0, mt: '1px' }}
      />
      <Typography variant="caption" color="text.secondary" component="div" sx={{ lineHeight: 1.45, minWidth: 0 }}>
        Still here (~{elapsedSec}s){hint ? ` — ${hint}` : ''}
        <Box component="span" sx={{ display: 'block', opacity: 0.85, mt: 0.35 }}>
          Next update in ~{nextInSec}s
        </Box>
      </Typography>
    </Box>
  );
}

/** Server-reported wall time for plan + tools (ms); shown as a subtle caption when present. */
function formatCqPipelineWallMs(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '';
  const rounded = Math.round(ms);
  if (rounded < 1000) return `${rounded}ms`;
  return `${(rounded / 1000).toFixed(1)}s`;
}

function AssistantPipelineTimingLine(props: Readonly<{ wallMs?: number }>) {
  if (props.wallMs == null || props.wallMs < 0) return null;
  return (
    <Typography
      variant="caption"
      component="p"
      sx={{
        mt: 0.75,
        mb: 0,
        color: 'text.secondary',
        opacity: 0.7,
        fontStyle: 'italic',
        letterSpacing: '0.01em'
      }}
    >
      Completed in {formatCqPipelineWallMs(props.wallMs)}
    </Typography>
  );
}

/** Muted stream of tokens before tool-progress begins — hidden once tools run. */
function AssistantReasoningLive(props: Readonly<{ text: string }>) {
  const t = props.text.trim();
  if (!t) return null;
  return (
    <Box
      sx={{
        mb: 1,
        color: 'text.secondary',
        opacity: 0.72,
        fontSize: '0.8125rem',
        lineHeight: 1.45
      }}
    >
      <Typography
        variant="caption"
        component="div"
        sx={{
          mb: 0.5,
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
          opacity: 0.9
        }}
      >
        Live model output
      </Typography>
      <MarkdownMessage text={t} />
    </Box>
  );
}

type UiMessage = {
  id: string;
  role: 'user' | 'assistant' | 'system';
  text: string;
  /**
   * Assistant text streamed before the first tool-progress event (typically ## Plan).
   * Set when tools run; if absent while {@link toolProgressText} is set, conversation predates this layout (tools above full narrative).
   */
  assistantPreToolsText?: string;
  /** Server SSE tool-progress lines (🛠️ …); shown in a short scroll area between plan and answer when {@link assistantPreToolsText} is set. */
  toolProgressText?: string;
  /**
   * Raw assistant text before the first tool-progress chunk — shown in muted “live” styling, then cleared once tools run
   * or folded into {@link text} when the stream completes without tools.
   */
  reasoningStreamText?: string;
  /** Wall clock from server pipeline start through plan + tools (metadata.toolPipelineWallMs on completed). */
  toolPipelineWallMs?: number;
  /** Transient: server signaled final narrative is starting (cleared when summary text arrives). */
  summarizingResults?: boolean;
  /** In-place wait indicator while the OpenAI+tools worker is busy (SSE `pipeline-heartbeat`; not tool-log lines). */
  pipelineHeartbeat?: { elapsedSec: number; nextInSec: number; hint: string };
  /** URLs for {@code studio-ai-inline-image://toolCallId} from server SSE (see {@code writeToolProgressSse} GenerateImage rows). */
  studioAiInlineImageUrls?: Record<string, string>;
  isStreaming?: boolean;
};

function combinedAssistantMarkdownForVerification(m: UiMessage): string {
  const tail = dedupeAssistantPostToolsMarkdown(m.assistantPreToolsText, m.text);
  return [m.assistantPreToolsText?.trim(), m.toolProgressText?.trim(), tail.trim()].filter(Boolean).join('\n\n');
}

/** When the stream aborts mid-flight, merge grey “live” tokens into the main bubble so nothing is dropped. */
function foldAssistantReasoningIntoMainText(m: Pick<UiMessage, 'text' | 'reasoningStreamText'>): {
  text: string;
  reasoningStreamText: string;
} {
  const r = (m.reasoningStreamText || '').trim();
  if (!r) return { text: m.text || '', reasoningStreamText: '' };
  const t = (m.text || '').trim();
  return { text: t ? `${t}\n\n${r}` : r, reasoningStreamText: '' };
}

type StoredConversation = {
  version: 1;
  chatId?: string;
  messages: UiMessage[];
};

function getConversationStorageKey(siteId: string, agentId: string): string {
  const site = (siteId || 'default').replace(/[^a-zA-Z0-9-_]/g, '_');
  const agent = (agentId || 'default').replace(/[^a-zA-Z0-9-_]/g, '_');
  return `aiassistant-conversation-${site}-${agent}`;
}

function loadConversation(siteId: string, agentId: string): StoredConversation | null {
  if (typeof localStorage === 'undefined') return null;
  try {
    const raw = localStorage.getItem(getConversationStorageKey(siteId, agentId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredConversation>;
    if (parsed?.version !== 1 || !Array.isArray(parsed.messages)) return null;
    return {
      version: 1,
      chatId: typeof parsed.chatId === 'string' ? parsed.chatId : undefined,
      messages: parsed.messages
        .filter((m) => m && typeof m.id === 'string' && typeof m.text === 'string' && typeof m.role === 'string')
        .map((m) => {
          const toolProgressText =
            typeof (m as { toolProgressText?: unknown }).toolProgressText === 'string'
              ? (m as { toolProgressText: string }).toolProgressText
              : undefined;
          const assistantPreToolsText =
            typeof (m as { assistantPreToolsText?: unknown }).assistantPreToolsText === 'string'
              ? (m as { assistantPreToolsText: string }).assistantPreToolsText
              : undefined;
          const rawWall = (m as { toolPipelineWallMs?: unknown }).toolPipelineWallMs;
          const toolPipelineWallMs =
            typeof rawWall === 'number' && Number.isFinite(rawWall) && rawWall >= 0 ? Math.round(rawWall) : undefined;
          return {
            id: m.id,
            role: m.role === 'assistant' || m.role === 'system' ? m.role : 'user',
            text: m.text,
            ...(assistantPreToolsText !== undefined ? { assistantPreToolsText } : {}),
            ...(toolProgressText !== undefined && toolProgressText !== '' ? { toolProgressText } : {}),
            ...(toolPipelineWallMs !== undefined ? { toolPipelineWallMs } : {}),
            isStreaming: false
          };
        })
    };
  } catch {
    return null;
  }
}

function saveConversation(siteId: string, agentId: string, payload: StoredConversation): void {
  if (typeof localStorage === 'undefined') return;
  try {
    localStorage.setItem(getConversationStorageKey(siteId, agentId), JSON.stringify(payload));
  } catch {
    // ignore
  }
}

function clearConversation(siteId: string, agentId: string): void {
  if (typeof localStorage === 'undefined') return;
  try {
    localStorage.removeItem(getConversationStorageKey(siteId, agentId));
  } catch {
    // ignore
  }
}

function mapRole(msg: AiAssistantChatMessage): UiMessage['role'] {
  const mt = (msg.messageType || msg.metadata?.role || '').toString().toUpperCase();
  if (mt.includes('USER')) return 'user';
  if (mt.includes('ASSISTANT')) return 'assistant';
  return 'system';
}

function clipContextChunk(s: string, max: number): string {
  const t = (s || '').trim();
  if (t.length <= max) return t;
  return `${t.slice(0, max)}…`;
}

/**
 * Prior turns: assistant replies often repeat **## Plan** then **## Plan Execution** — keep execution/recap only to
 * shrink the wire prompt and reduce redundant reasoning.
 */
function abbreviateAssistantTurnForPriorContext(body: string): string {
  let t = stripOrchestrationDebugComment(body).trim();
  if (!t) return '';
  const planExec = /^##\s+Plan Execution\b/im;
  const match = planExec.exec(t);
  if (match?.index != null) {
    t = t.slice(match.index).trim();
  }
  return t;
}

/**
 * Abbreviated prior user/assistant turns so OpenAI single-turn requests retain conversational continuity
 * without multi-message API history. Used for **every** AI panel embed (XB/ICE preview sidebar, floating dialog,
 * content-type form assistant) — not form-engine-specific.
 */
function buildPriorTurnsContextBlock(prior: UiMessage[]): string {
  const relevant = prior.filter((m) => m.role === 'user' || m.role === 'assistant');
  if (relevant.length === 0) return '';
  const totalMax = 8000;
  const slice = relevant.length > 8 ? relevant.slice(-8) : relevant;
  const lines: string[] = [];
  let used = 0;
  for (const m of slice) {
    const label = m.role === 'user' ? 'User' : 'Assistant';
    let body = (m.text || '').trim();
    if (m.role === 'assistant' && !body && m.assistantPreToolsText) {
      body = m.assistantPreToolsText.trim();
    }
    if (!body) continue;
    const prepared =
      m.role === 'assistant' ? abbreviateAssistantTurnForPriorContext(body) : stripOrchestrationDebugComment(body).trim();
    if (!prepared.trim()) continue;
    const perMsgMax = m.role === 'assistant' ? 1800 : 2800;
    const piece = clipContextChunk(prepared, perMsgMax);
    const line = `${label}: ${piece}`;
    if (used + line.length + 2 > totalMax) break;
    lines.push(line);
    used += line.length + 2;
  }
  if (lines.length === 0) return '';
  return (
    '[Prior conversation — abbreviated for context. Current request follows after the separator.]\n\n' +
    `${lines.join('\n\n')}\n\n---\n\n`
  );
}

export interface AiAssistantChatProps {
  agentId: string;
  /** Widget agent `<llm>` when set in ui.xml. Omitted from POST if unset—server then normalizes to hosted CrafterQ; set explicitly for predictable routing. */
  llm?: string;
  llmModel?: string;
  /** OpenAI Images API model for GenerateImage; from agent ui.xml **imageModel** or request body only (no default). */
  imageModel?: string;
  /** GenerateImage backend: ui.xml **imageGenerator** / stream POST (blank / openAiWire / none / script:{id}). */
  imageGenerator?: string;
  /** From ui.xml; server uses only if env/JVM OpenAI key unset. Testing only — not recommended. */
  openAiApiKey?: string;
  initialMessages?: Array<{ role: string; content: string }>;
  /** Prompts from ui.xml agent config; shown above chat. When set, overrides API quick messages. */
  configPrompts?: PromptConfig[];
  /**
   * `icePanel` — Studio ICE tools drawer scrolls the whole page (`drawerBody { overflowY: auto }`).
   * Use flow layout + sticky composer against that scroll. `default` — inner transcript scroll (popup dialog).
   */
  embedTarget?: 'default' | 'icePanel';
  /**
   * Form-engine AI assistant only: called on every send to attach form-definition XML + live field values
   * to the request (not shown in the chat bubble).
   */
  getAuthoringFormContext?: () => AuthoringFormContextSnapshot;
  /**
   * Form assistant only: when true (default), send `formEngineClientJsonApply` and append client-JSON apply instructions.
   * Set false to disable. **Never** set on XB/ICE — those surfaces omit `getAuthoringFormContext` and use tools.
   */
  formEngineClientJsonApply?: boolean;
  /**
   * When false, POST omits OpenAI function tools (agent ui.xml `enableTools`).
   * Applies on **all** surfaces (XB/ICE, dialog, form engine). Focused no-tools turns also use quick-prompt **`omitTools`** (see {@link PromptConfig.omitTools} / POST **`omitTools`**).
   */
  enableTools?: boolean;
  /**
   * Optional subset of CMS tool wire names (POST **enabledBuiltInTools**). Include **`mcp:*`** for all MCP tools.
   */
  enabledBuiltInTools?: string[];
  /** Optional per-agent markdown RAG URLs (OpenAI); sent as `expertSkills` on stream POST. */
  expertSkills?: ExpertSkillConfig[];
  /** 1–64; sent on stream POST for TranslateContentBatch default parallelism (ui.xml translateBatchConcurrency). */
  translateBatchConcurrency?: number;
}

export default function AiAssistantChat(props: Readonly<AiAssistantChatProps>) {
  const theme = useTheme();
  const {
    agentId: agentIdProp,
    llm,
    llmModel,
    imageModel,
    imageGenerator,
    openAiApiKey,
    initialMessages,
    configPrompts,
    embedTarget = 'default',
    getAuthoringFormContext,
    formEngineClientJsonApply,
    enableTools,
    enabledBuiltInTools,
    expertSkills,
    translateBatchConcurrency
  } = props;
  /** Widget **`agentId`** from agent configuration (UUID when applicable). */
  const agentId = agentIdProp?.trim() ?? '';

  const siteId = useActiveSiteId() ?? 'default';
  const previewItem = useCurrentPreviewItem();
  const guest = usePreviewGuest();
  const user = useActiveUser();

  /** Guest model (XB) — may exist when itemsByPath has not loaded DetailedItem yet. */
  const guestMainModel =
    guest?.modelId && guest?.models ? (guest.models as Record<string, { craftercms?: { path?: string; contentTypeId?: string } }>)[guest.modelId] : undefined;

  const resolvedContentPath =
    (previewItem as { path?: string } | undefined)?.path?.trim() ||
    (typeof guest?.path === 'string' ? guest.path.trim() : '') ||
    (guestMainModel?.craftercms?.path && String(guestMainModel.craftercms.path).trim()) ||
    '';

  const resolvedContentTypeId =
    (previewItem as { contentTypeId?: string } | undefined)?.contentTypeId?.trim() ||
    (guestMainModel?.craftercms?.contentTypeId && String(guestMainModel.craftercms.contentTypeId).trim()) ||
    '';

  const macroValuesRef = useRef({
    siteId,
    currentPage: '',
    currentUsername: 'unknown',
    contentTypeId: '',
    contentPath: ''
  });
  macroValuesRef.current = {
    siteId,
    currentPage: (previewItem as { previewUrl?: string } | undefined)?.previewUrl ?? (typeof window !== 'undefined' ? window.location.href : '') ?? '',
    currentUsername: (user as { username?: string } | undefined)?.username ?? 'unknown',
    contentTypeId: resolvedContentTypeId,
    contentPath: resolvedContentPath
  };

  const [loading, setLoading] = useState(false);
  const [configError, setConfigError] = useState<string | null>(null);
  /** Shown in the composer until the author types (native placeholder — grey, not submitted). */
  const promptPlaceholder =
    'e.g. Summarize this page for a marketing stakeholder in three short bullet points';
  const quickMessages: string[] = [];
  const welcomeMessage: string | null = null;

  const [chatId, setChatId] = useState<string | undefined>(undefined);
  const [messages, setMessages] = useState<UiMessage[]>(() => {
    const base: UiMessage[] = [];
    (initialMessages ?? []).forEach((m, idx) => {
      base.push({
        id: `initial-${idx}`,
        role: (m.role || '').toLowerCase() === 'assistant' ? 'assistant' : 'user',
        text: m.content ?? ''
      });
    });
    return base;
  });
  const messagesRef = useRef(messages);
  messagesRef.current = messages;
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [readResponsesAloud, setReadResponsesAloud] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  /** True when the user clicked Stop (vs timeout / navigation) — shapes catch handling. */
  const userStopRequestedRef = useRef(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const saveDebounceRef = useRef<number | null>(null);
  /** Snapshot for the debounced localStorage write; teardown flush uses this so agent switches do not save the wrong row. */
  const pendingPersistRef = useRef<{
    siteId: string;
    agentId: string;
    chatId: string | undefined;
    messages: UiMessage[];
  } | null>(null);
  const iceRootRef = useRef<HTMLDivElement | null>(null);
  const lastSpokenAssistantIdRef = useRef<string | null>(null);
  /** Raw SSE JSON lines + structured client markers — formatted by formatSessionLogForDebugCopy when copying. */
  const sessionStreamLogRef = useRef<string[]>([]);

  // Restore per-agent conversation across reloads / reopens.
  useEffect(() => {
    const stored = loadConversation(siteId, agentId);
    if (stored) {
      setChatId(stored.chatId);
      setMessages(stored.messages);
      return;
    }
    // No stored conversation: reset to initial messages for this render context.
    const base: UiMessage[] = [];
    (initialMessages ?? []).forEach((m, idx) => {
      base.push({
        id: `initial-${idx}`,
        role: (m.role || '').toLowerCase() === 'assistant' ? 'assistant' : 'user',
        text: m.content ?? ''
      });
    });
    setChatId(undefined);
    setMessages(base);
  }, [siteId, agentId, initialMessages]);

  // Persist per-agent conversation: avoid writing on every SSE chunk (localStorage jank + quota).
  useEffect(() => {
    const hasStreaming = messages.some((m) => m.isStreaming);
    if (hasStreaming) {
      return;
    }
    pendingPersistRef.current = { siteId, agentId, chatId, messages };
    if (saveDebounceRef.current) {
      window.clearTimeout(saveDebounceRef.current);
    }
    saveDebounceRef.current = window.setTimeout(() => {
      const p = pendingPersistRef.current;
      if (p && typeof localStorage !== 'undefined') {
        saveConversation(p.siteId, p.agentId, {
          version: 1,
          chatId: p.chatId,
          messages: p.messages.map((m) => ({ ...m, isStreaming: false }))
        });
      }
      saveDebounceRef.current = null;
    }, 600);
    return () => {
      if (saveDebounceRef.current) {
        window.clearTimeout(saveDebounceRef.current);
        saveDebounceRef.current = null;
      }
    };
  }, [siteId, agentId, chatId, messages]);

  useEffect(() => {
    return () => {
      const p = pendingPersistRef.current;
      if (p && typeof localStorage !== 'undefined' && !p.messages.some((m) => m.isStreaming)) {
        saveConversation(p.siteId, p.agentId, {
          version: 1,
          chatId: p.chatId,
          messages: p.messages.map((m) => ({ ...m, isStreaming: false }))
        });
      }
    };
  }, [siteId, agentId]);

  const quickMessagesToShow = useMemo(() => {
    if (configPrompts && configPrompts.length > 0) {
      return configPrompts
        .filter((p) => Boolean(p && p.userText && p.userText.trim()))
        .slice(0, 10);
    }
    return (quickMessages ?? []).filter(Boolean).slice(0, 10).map((s) => ({ userText: s }));
  }, [configPrompts, quickMessages]);

  const promptToSend = (p: PromptConfig) => {
    const ut = (p.userText || '').trim();
    const ctx = (p.additionalContext || '').trim();
    if (!ctx) return ut;
    return `${ut}\n\nAdditional context:\n${ctx}`;
  };

  useEffect(() => {
    if (embedTarget === 'icePanel') {
      const root = iceRootRef.current;
      if (!root) return;
      const sp = getScrollParent(root);
      if (sp) sp.scrollTop = sp.scrollHeight;
      return;
    }
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [messages, sending, embedTarget]);

  const ttsAvailable = useMemo(() => isSpeechSynthesisAvailable(), []);

  useEffect(() => {
    if (!readResponsesAloud || !ttsAvailable) return;
    const synth = window.speechSynthesis;
    const assistants = messages.filter((m) => m.role === 'assistant' || m.role === 'system');
    const last = assistants[assistants.length - 1];
    const narr =
      `${(last?.assistantPreToolsText ?? '').trim()}\n${dedupeAssistantPostToolsMarkdown(last?.assistantPreToolsText, last?.text ?? '')}`.trim();
    if (!last || last.isStreaming || !narr) return;
    if (lastSpokenAssistantIdRef.current === last.id) return;

    lastSpokenAssistantIdRef.current = last.id;
    synth.cancel();
    const plain = textForSpeechSynthesis(narr);
    if (!plain) return;
    const u = new SpeechSynthesisUtterance(plain);
    u.lang = typeof navigator !== 'undefined' && navigator.language ? navigator.language : 'en-US';
    synth.speak(u);
  }, [messages, readResponsesAloud, ttsAvailable]);

  useEffect(() => {
    return () => {
      window.speechSynthesis?.cancel();
    };
  }, []);

  // Allow sending even if a previous request is stuck; startSend aborts in-flight first.
  const canSend = useMemo(() => draft.trim().length > 0, [draft]);

  /** 📋 plan lines from the latest finished assistant message — click runs as a new prompt. */
  const verificationPrompts = useMemo(() => {
    const last = [...messages].reverse().find((m) => m.role === 'assistant' && !m.isStreaming);
    if (!last) return [];
    return extractVerificationPrompts(combinedAssistantMarkdownForVerification(last));
  }, [messages]);

  /** "Would you like me to:" / list follow-ups — click sends as next prompt (deduped vs 📋 chips). */
  const followUpActionPrompts = useMemo(() => {
    const last = [...messages].reverse().find((m) => m.role === 'assistant' && !m.isStreaming);
    if (!last) return [];
    const combined = combinedAssistantMarkdownForVerification(last);
    const verifyKeys = new Set(verificationPrompts.map((v) => verificationPromptDedupeKey(v)));
    return extractFollowUpActionPrompts(combined).filter((p) => !verifyKeys.has(verificationPromptDedupeKey(p)));
  }, [messages, verificationPrompts]);

  const speechCtor = useMemo(() => getSpeechRecognitionCtor(), []);
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const voiceActiveRef = useRef(false);
  const draftAtVoiceStartRef = useRef('');
  const voiceFinalsRef = useRef('');
  const [voiceListening, setVoiceListening] = useState(false);
  const [voiceError, setVoiceError] = useState<string | null>(null);
  const [promptFocused, setPromptFocused] = useState(false);

  const stopVoiceInput = useCallback(() => {
    voiceActiveRef.current = false;
    const r = recognitionRef.current;
    recognitionRef.current = null;
    try {
      r?.stop();
    } catch {
      // ignore
    }
    setVoiceListening(false);
  }, []);

  const startVoiceInput = useCallback(() => {
    const Ctor = speechCtor;
    if (!Ctor || sending) return;
    setVoiceError(null);
    draftAtVoiceStartRef.current = draft;
    voiceFinalsRef.current = '';
    voiceActiveRef.current = true;
    const rec = new Ctor();
    recognitionRef.current = rec;
    rec.lang = typeof navigator !== 'undefined' && navigator.language ? navigator.language : 'en-US';
    rec.continuous = true;
    rec.interimResults = true;
    rec.onresult = (event: SpeechRecognitionEvent) => {
      let interim = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        const text = result[0]?.transcript ?? '';
        if (result.isFinal) {
          const t = text.trim();
          if (t) {
            voiceFinalsRef.current = (voiceFinalsRef.current + (voiceFinalsRef.current ? ' ' : '') + t).trim();
          }
        } else {
          interim += text;
        }
      }
      const start = draftAtVoiceStartRef.current;
      const finals = voiceFinalsRef.current.trim();
      const inter = interim.trim();
      const chunks = [start.trim(), finals, inter].filter(Boolean);
      setDraft(chunks.join(' '));
    };
    rec.onerror = (ev: SpeechRecognitionErrorEvent) => {
      if (ev.error === 'aborted') return;
      if (ev.error === 'no-speech') return;
      const msg =
        ev.error === 'not-allowed'
          ? 'Microphone permission denied'
          : ev.error === 'service-not-allowed'
            ? 'Speech recognition not allowed'
            : ev.error;
      setVoiceError(msg);
      stopVoiceInput();
    };
    try {
      rec.start();
      setVoiceListening(true);
    } catch (e) {
      const m = e instanceof Error ? e.message : String(e);
      setVoiceError(m);
      voiceActiveRef.current = false;
      recognitionRef.current = null;
    }
  }, [speechCtor, sending, draft, stopVoiceInput]);

  const toggleVoiceInput = useCallback(() => {
    if (voiceListening) stopVoiceInput();
    else startVoiceInput();
  }, [voiceListening, stopVoiceInput, startVoiceInput]);

  useEffect(() => {
    return () => {
      voiceActiveRef.current = false;
      try {
        recognitionRef.current?.stop();
      } catch {
        // ignore
      }
      recognitionRef.current = null;
    };
  }, []);

  const stopStreaming = useCallback(() => {
    userStopRequestedRef.current = true;
    abortRef.current?.abort();
  }, []);

  /** Remove one bubble from history (demos / retakes). Aborts stream if removing the in-flight assistant or its paired user message. */
  const removeBubble = useCallback(
    (id: string) => {
      const prev = messagesRef.current;
      const idx = prev.findIndex((x) => x.id === id);
      if (idx < 0) return;
      const target = prev[idx];
      const next = prev[idx + 1];
      const removeStreamingPair =
        target.role === 'user' && next?.role === 'assistant' && Boolean(next.isStreaming);
      if (target.isStreaming || removeStreamingPair) {
        stopStreaming();
      }
      const drop = new Set<string>([id]);
      if (removeStreamingPair && next) drop.add(next.id);
      setMessages((p) => p.filter((m) => !drop.has(m.id)));
    },
    [stopStreaming]
  );

  const handleNewChat = () => {
    stopVoiceInput();
    setVoiceError(null);
    window.speechSynthesis?.cancel();
    lastSpokenAssistantIdRef.current = null;
    abortRef.current?.abort();
    setSending(false);
    setDraft('');
    setChatId(undefined);
    setMessages([]);
    sessionStreamLogRef.current = [];
    try {
      pushStreamLog(
        sessionStreamLogRef,
        JSON.stringify({
          kind: 'client.sessionReset',
          ts: new Date().toISOString(),
          siteId,
          agentId
        })
      );
    } catch {
      /* ignore log serialization errors */
    }
    clearConversation(siteId, agentId);
  };

  const startSend = async (prompt: string, displayInChat?: string, sendOptions?: { omitTools?: boolean }) => {
    const trimmed = prompt.trim();
    if (!trimmed) return;

    stopVoiceInput();

    const now = new Date();
    const pad2 = (n: number) => String(n).padStart(2, '0');
    const macroCtx: PromptMacrosContext = {
      dateToday: `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`,
      timeNow: `${pad2(now.getHours())}:${pad2(now.getMinutes())}`,
      currentPage: macroValuesRef.current.currentPage,
      currentContentPath: macroValuesRef.current.contentPath,
      currentUsername: macroValuesRef.current.currentUsername,
      siteId: macroValuesRef.current.siteId
    };
    let expandedPrompt = expandPromptMacros(trimmed, macroCtx).trim();
    const expandedDisplaySync = expandPromptMacros((displayInChat ?? trimmed).trim(), macroCtx).trim();
    expandedPrompt = await expandContentTypeMacros(
      expandedPrompt,
      macroCtx.siteId,
      macroValuesRef.current.contentTypeId
    ).then((s) => s.trim());

    let authoringSnap: AuthoringFormContextSnapshot | undefined;
    if (typeof getAuthoringFormContext === 'function') {
      try {
        authoringSnap = getAuthoringFormContext();
      } catch {
        /* ignore — still send prompt without live form appendix / macro substitution */
      }
    }

    const formEngine = isFormEngineAuthoringChat(getAuthoringFormContext);
    const wantClientJsonApply = formEngine && formEngineClientJsonApply !== false;

    expandedPrompt = await expandContentMacros(
      expandedPrompt,
      macroCtx.siteId,
      macroValuesRef.current.contentPath,
      authoringSnap
        ? {
            contentItemPath: authoringSnap.contentPath,
            fieldValuesJson: authoringSnap.fieldValuesJson,
            serializedContentXml: authoringSnap.serializedContentXml
          }
        : undefined
    ).then((s) => s.trim());
    let expandedDisplay = expandContentTypeMacrosForDisplay(
      expandedDisplaySync,
      macroValuesRef.current.contentTypeId
    );
    expandedDisplay = expandContentMacrosForDisplay(
      expandedDisplay,
      macroValuesRef.current.contentPath
    ).trim();
    if (!expandedPrompt) return;

    const omitToolsThisSend = sendOptions?.omitTools === true;

    if (authoringSnap) {
      try {
        const appendix = buildAuthoringFormAppendix(authoringSnap, {
          includeClientJsonApplyInstructions: wantClientJsonApply
        });
        if (appendix) expandedPrompt = expandedPrompt + appendix;
      } catch {
        /* ignore snapshot appendix errors — still send the user prompt */
      }
    }

    const priorBlock = buildPriorTurnsContextBlock(messages);
    const previewPath = macroValuesRef.current.contentPath?.trim();
    const previewCt = macroValuesRef.current.contentTypeId?.trim();
    const requestAnchor =
      !formEngine && (previewPath || previewCt)
        ? `[Request anchor — default target when the user says "this page", "this article", or similar without another path]\n${previewPath ? `Repository path: ${previewPath}` : ''}${previewPath && previewCt ? '\n' : ''}${previewCt ? `Content-type id: ${previewCt.startsWith('/') ? previewCt : `/${previewCt}`}` : ''}\n\n`
        : '';
    const currentRequestBody = requestAnchor ? `${requestAnchor}${expandedPrompt}` : expandedPrompt;
    const wirePrompt = priorBlock ? `${priorBlock}Current request:\n${currentRequestBody}` : currentRequestBody;

    abortRef.current?.abort();
    userStopRequestedRef.current = false;
    const ac = new AbortController();
    abortRef.current = ac;

    const userId = `user-${Date.now()}`;
    const assistantId = `assistant-${Date.now()}`;
    const userBubbleText = expandedDisplay || expandedPrompt;

    /** `crafterPreview` cookie when present — forwarded for server preview tools (GetPreviewHtml, etc.). */
    const previewTokenForStream = readCrafterPreviewTokenFromCookie();
    const previewContentTypeLabel = formEngine ? undefined : resolvePreviewContentTypeLabel(previewItem);
    const studioPreviewPageUrl = formEngine ? undefined : pickStudioPreviewPageUrlForServer(previewItem);

    setMessages((prev) => [
      ...prev,
      { id: userId, role: 'user', text: userBubbleText },
      { id: assistantId, role: 'assistant', text: '', isStreaming: true }
    ]);
    try {
      pushStreamLog(
        sessionStreamLogRef,
        JSON.stringify({
          kind: 'client.userSend',
          ts: new Date().toISOString(),
          context: {
            siteId,
            agentId,
            llm: llm ?? null,
            llmModel: llmModel ?? null,
            imageModel: imageModel ?? null,
            imageGenerator: imageGenerator != null ? String(imageGenerator).trim() || null : null,
            authoringSurface: formEngine ? 'formEngine' : 'preview',
            omitTools: omitToolsThisSend,
            enableTools: enableTools !== false,
            chatId: chatId ?? null,
            contentPath: formEngine ? null : macroValuesRef.current.contentPath?.trim() || null,
            contentTypeId: formEngine ? null : macroValuesRef.current.contentTypeId?.trim() || null,
            studioPreviewPageUrl: studioPreviewPageUrl ?? null,
            formEngineClientJsonApply: wantClientJsonApply,
            formEngineItemPath:
              formEngine && wantClientJsonApply && authoringSnap?.contentPath?.trim()
                ? authoringSnap.contentPath.trim()
                : null
          },
          displayText: userBubbleText,
          wirePrompt: wirePrompt
        })
      );
    } catch {
      /* ignore log serialization errors */
    }
    setDraft('');
    setSending(true);

    let streamingMessageId: string | undefined;
    let assistantTextAccum = '';
    let formUpdatesApplied = false;
    let shouldRefreshPreview = false;
    /** Set when the client stream wait hits the hard cap (try/catch are separate scopes — must be outside `try`). */
    let streamHitTimeout = false;

    try {
      const streamPromise = streamChat({
        agentId,
        chatId,
        prompt: wirePrompt,
        // Form engine: do not send preview path — server would treat it as repo truth for tools; unsaved edits are only in the prompt appendix.
        contentPath: formEngine ? undefined : macroValuesRef.current.contentPath?.trim() || undefined,
        contentTypeId: formEngine ? undefined : macroValuesRef.current.contentTypeId?.trim() || undefined,
        ...(previewContentTypeLabel ? { contentTypeLabel: previewContentTypeLabel } : {}),
        ...(studioPreviewPageUrl ? { studioPreviewPageUrl } : {}),
        authoringSurface: formEngine ? 'formEngine' : undefined,
        formEngineClientJsonApply: formEngine && wantClientJsonApply ? true : undefined,
        formEngineItemPath:
          formEngine && wantClientJsonApply && authoringSnap?.contentPath?.trim()
            ? authoringSnap.contentPath.trim()
            : undefined,
        llm,
        llmModel,
        imageModel,
        ...(imageGenerator != null && String(imageGenerator).trim() !== ''
          ? { imageGenerator: String(imageGenerator).trim() }
          : {}),
        openAiApiKey,
        siteId,
        ...(previewTokenForStream ? { previewToken: previewTokenForStream } : {}),
        ...(omitToolsThisSend ? { omitTools: true } : {}),
        ...(enableTools === false ? { enableTools: false } : {}),
        ...(Array.isArray(enabledBuiltInTools) && enabledBuiltInTools.length > 0 ? { enabledBuiltInTools } : {}),
        ...(Array.isArray(expertSkills) && expertSkills.length > 0 ? { expertSkills } : {}),
        ...(translateBatchConcurrency != null &&
        Number.isFinite(translateBatchConcurrency) &&
        translateBatchConcurrency >= 1 &&
        translateBatchConcurrency <= 64
          ? { translateBatchConcurrency: Math.floor(translateBatchConcurrency) }
          : {}),
        signal: ac.signal,
        onRawSseDataLine: (jsonLine) => {
          pushStreamLog(sessionStreamLogRef, `${new Date().toISOString()}\t${jsonLine}`);
        },
        onMessage: (evt) => {
          const evtChatId = evt.metadata?.chatId;
          if (evtChatId && !chatId) setChatId(evtChatId);

          const evtMsgId = evt.metadata?.messageId;
          if (evtMsgId && !streamingMessageId) streamingMessageId = evtMsgId;

          const isCompleted = Boolean(evt.metadata?.completed);
          const streamErr = Boolean(evt.metadata?.error);
          const streamErrMsg = evt.metadata?.message;
          const planGateFailure = Boolean(evt.metadata?.planGateFailure === true);
          const toolStatus = String(evt.metadata?.status || '');
          const toolPhase = String(evt.metadata?.phase || '');
          const toolName = String(evt.metadata?.tool || '');
          /** Tool rows and the initial workflow hint share the 🛠️ strip (server uses `tool-progress` vs `tool-workflow-hint`). */
          const isToolProgressChunk =
            toolStatus === 'tool-progress' || toolStatus === 'tool-workflow-hint';
          const rawTextChunk = evt.text ?? '';
          const textChunk = isToolProgressChunk ? rawTextChunk : stripForbiddenLazyPlanLines(rawTextChunk);
          const summarizingResultsHint =
            evt.metadata?.status === 'aiassistant-chat-phase' &&
            String(evt.metadata?.phase || '') === 'summarizing-results';

          const md = evt.metadata && typeof evt.metadata === 'object' ? (evt.metadata as Record<string, unknown>) : undefined;
          const incomingStudioAiInlineImgUrls = md?.studioAiInlineImageUrls;
          const mdStatus = md && md.status != null ? String(md.status).trim() : '';
          if (mdStatus === 'pipeline-heartbeat') {
            const rawEl = md.elapsedSec;
            const rawNext = md.nextInSec;
            const elapsedSec = Math.max(0, Math.floor(Number(rawEl) || 0));
            const nextN = Number(rawNext);
            const nextInSec =
              Number.isFinite(nextN) && nextN > 0 ? Math.round(nextN) : Math.max(1, Math.round(Number(rawNext) || 12));
            const hint = String(md.hint ?? '').trim();
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId
                  ? {
                      ...m,
                      pipelineHeartbeat: { elapsedSec, nextInSec, hint }
                    }
                  : m
              )
            );
            return;
          }

          if (planGateFailure && streamErr && textChunk.trim()) {
            const rawPipePg = evt.metadata?.toolPipelineWallMs;
            const toolPipelineWallMsPg =
              typeof rawPipePg === 'number' && Number.isFinite(rawPipePg) && rawPipePg >= 0
                ? Math.round(rawPipePg)
                : undefined;
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId
                  ? {
                      ...m,
                      text: textChunk,
                      assistantPreToolsText: undefined,
                      toolProgressText: undefined,
                      reasoningStreamText: undefined,
                      pipelineHeartbeat: undefined,
                      isStreaming: false,
                      summarizingResults: false,
                      ...(toolPipelineWallMsPg !== undefined ? { toolPipelineWallMs: toolPipelineWallMsPg } : {})
                    }
                  : m
              )
            );
            return;
          }

          if (summarizingResultsHint) {
            setMessages((prev) =>
              prev.map((m) => (m.id === assistantId ? { ...m, summarizingResults: true } : m))
            );
          }

          if (hasStudioAiInlineImageUrlPayload(incomingStudioAiInlineImgUrls)) {
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId ? { ...m, ...studioAiInlineUrlsPatch(m, incomingStudioAiInlineImgUrls) } : m
              )
            );
          }

          if (
            !formEngine &&
            !streamErr &&
            toolStatus === 'tool-progress' &&
            toolPhase === 'done' &&
            PREVIEW_RELOAD_TOOL_NAMES_ON_DONE.has(toolName)
          ) {
            shouldRefreshPreview = true;
            if (toolName === 'WriteContent') {
              const repoPath =
                typeof evt.metadata?.repoPath === 'string' ? evt.metadata.repoPath.trim() : '';
              if (repoPath) {
                navigateStudioPreviewToRepoPath(repoPath);
              }
            }
            triggerStudioPreviewReload();
          }

          if (textChunk) {
            if (isToolProgressChunk) {
              setMessages((prev) =>
                prev.map((m) => {
                  if (m.id !== assistantId) return m;
                  const hadNoToolLinesYet = !(m.toolProgressText || '').length;
                  const priorMain = (m.text || '').trim();
                  const reasoningHead = (m.reasoningStreamText || '').trim();
                  const planPrefix = reasoningHead || priorMain;
                  if (hadNoToolLinesYet) {
                    return {
                      ...m,
                      assistantPreToolsText: planPrefix || undefined,
                      reasoningStreamText: '',
                      text: '',
                      toolProgressText: textChunk,
                      ...studioAiInlineUrlsPatch(m, incomingStudioAiInlineImgUrls)
                    };
                  }
                  const prior = m.toolProgressText || '';
                  // ReactMarkdown collapses single newlines into spaces; separate SSE chunks with a blank line
                  // so each 🛠️ tool line stays readable when pasted or viewed in the tool strip.
                  const join = prior.endsWith('\n\n')
                    ? textChunk.startsWith('\n')
                      ? ''
                      : '\n'
                    : '\n\n';
                  return {
                    ...m,
                    toolProgressText: prior + join + textChunk,
                    ...studioAiInlineUrlsPatch(m, incomingStudioAiInlineImgUrls)
                  };
                })
              );
            } else {
              assistantTextAccum += textChunk;
              setMessages((prev) =>
                prev.map((m) => {
                  if (m.id !== assistantId) return m;
                  const toolsAlreadyVisible = !!(m.toolProgressText || '').trim();
                  const noPlanAboveToolsYet = !(m.assistantPreToolsText || '').trim();
                  if (!toolsAlreadyVisible) {
                    return {
                      ...m,
                      reasoningStreamText: (m.reasoningStreamText || '') + textChunk,
                      ...(textChunk.trim() !== '' ? { summarizingResults: false } : {}),
                      ...studioAiInlineUrlsPatch(m, incomingStudioAiInlineImgUrls)
                    };
                  }
                  if (noPlanAboveToolsYet && textChunk.trim()) {
                    const head = textChunk.trimStart();
                    if (
                      head.startsWith('## Plan') ||
                      head.startsWith('## plan') ||
                      textChunk.includes('Workspace fallback')
                    ) {
                      return {
                        ...m,
                        assistantPreToolsText: (m.assistantPreToolsText || '') + textChunk,
                        ...(textChunk.trim() !== '' ? { summarizingResults: false } : {}),
                        ...studioAiInlineUrlsPatch(m, incomingStudioAiInlineImgUrls)
                      };
                    }
                  }
                  return {
                    ...m,
                    text: (m.text || '') + textChunk,
                    ...(textChunk.trim() !== '' ? { summarizingResults: false } : {}),
                    ...studioAiInlineUrlsPatch(m, incomingStudioAiInlineImgUrls)
                  };
                })
              );
            }
          }

          if (streamErr && streamErrMsg) {
            const errLine = '\n\n**Stream error:** ' + sanitizeErrorForAuthor(String(streamErrMsg));
            setMessages((prev) =>
              prev.map((m) => {
                if (m.id !== assistantId) return m;
                const nextText = (m.text || '').includes(String(streamErrMsg)) ? m.text : (m.text || '') + errLine;
                return {
                  ...m,
                  text: nextText,
                  isStreaming: false,
                  summarizingResults: false,
                  pipelineHeartbeat: undefined
                };
              })
            );
          }

          if (isCompleted) {
            const rawPipe = evt.metadata?.toolPipelineWallMs;
            const toolPipelineWallMs =
              typeof rawPipe === 'number' && Number.isFinite(rawPipe) && rawPipe >= 0
                ? Math.round(rawPipe)
                : undefined;
            setMessages((prev) =>
              prev.map((m) => {
                if (m.id !== assistantId) return m;
                const noTools = !(m.toolProgressText || '').trim();
                const reasoningRest = (m.reasoningStreamText || '').trim();
                const mainRest = (m.text || '').trim();
                const foldReasoning = noTools && reasoningRest && !mainRest;
                return {
                  ...m,
                  isStreaming: false,
                  summarizingResults: false,
                  pipelineHeartbeat: undefined,
                  ...(foldReasoning ? { text: reasoningRest, reasoningStreamText: '' } : {}),
                  ...(toolPipelineWallMs !== undefined ? { toolPipelineWallMs } : {})
                };
              })
            );

            if (
              formEngine &&
              wantClientJsonApply &&
              !formUpdatesApplied &&
              typeof getAuthoringFormContext === 'function' &&
              assistantTextAccum
            ) {
              formUpdatesApplied = true;
              try {
                const snap = getAuthoringFormContext();
                const updates = tryExtractAiassistantFormFieldUpdates(assistantTextAccum);
                if (updates && typeof snap.applyAssistantFieldUpdates === 'function') {
                  const result = snap.applyAssistantFieldUpdates(updates);
                  const parts: string[] = [];
                  if (result.applied?.length) {
                    parts.push(`**Applied to form:** ${result.applied.join(', ')}`);
                  }
                  if (result.error) {
                    parts.push(`**Apply issues:** ${result.error}`);
                  } else if (Object.keys(updates).length && !result.applied?.length) {
                    parts.push('**Apply issues:** no fields matched the open form (check field ids).');
                  }
                  if (parts.length) {
                    const note = '\n\n---\n' + parts.join('\n');
                    setMessages((prev) =>
                      prev.map((m) => {
                        if (m.id !== assistantId) return m;
                        return { ...m, text: (m.text || '') + note };
                      })
                    );
                  }
                }
              } catch {
                /* ignore apply failures — assistant text still shown */
              }
            }
          }
        }
      });

      // OpenAI + multi-step tools can exceed several minutes; cap aligns with the plugin orchestration stream await default.
      const CHAT_STREAM_TIMEOUT_MS = 600000;
      let streamTimeoutId: number | undefined;
      const timeoutPromise = new Promise<never>((_, reject) => {
        streamTimeoutId = window.setTimeout(() => {
          streamHitTimeout = true;
          ac.abort();
          reject(new Error('Timed out waiting for chat response'));
        }, CHAT_STREAM_TIMEOUT_MS);
      });
      try {
        await Promise.race([streamPromise, timeoutPromise]);
      } finally {
        if (streamTimeoutId !== undefined) window.clearTimeout(streamTimeoutId);
      }

      try {
        pushStreamLog(
          sessionStreamLogRef,
          JSON.stringify({
            kind: 'client.streamOutcome',
            ts: new Date().toISOString(),
            outcome: 'stream_finished_ok'
          })
        );
      } catch {
        /* ignore log serialization errors */
      }

      if (!formEngine && shouldRefreshPreview) {
        triggerStudioPreviewReload();
      }
    } catch (e) {
      const errText = e instanceof Error ? e.message : String(e);
      const userStopped = userStopRequestedRef.current && isFetchAbortError(e);
      const timedOut =
        streamHitTimeout ||
        errText.includes('Timed out waiting for chat response');
      const abortWithoutExplicitStop = isFetchAbortError(e) && !userStopped && !timedOut;
      let streamOutcome = 'request_error';
      if (userStopped) streamOutcome = 'user_stop';
      else if (timedOut) streamOutcome = 'timeout';
      else if (e instanceof AiAssistantIncompleteStreamError) streamOutcome = 'incomplete_stream';
      else if (abortWithoutExplicitStop) streamOutcome = 'aborted_or_network';
      try {
        pushStreamLog(
          sessionStreamLogRef,
          JSON.stringify({
            kind: 'client.streamOutcome',
            ts: new Date().toISOString(),
            outcome: streamOutcome,
            errorType: e instanceof Error ? e.name : typeof e,
            message: errText.slice(0, 4000)
          })
        );
      } catch {
        /* ignore log serialization errors */
      }
      setMessages((prev) =>
        prev.map((m) => {
          if (m.id !== assistantId) return m;
          const folded = foldAssistantReasoningIntoMainText(m);
          const m2 = { ...m, text: folded.text, reasoningStreamText: folded.reasoningStreamText };
          const hadPartial = assistantVisibleTextLen(m2) > 0;
          const sep = hadPartial ? '\n\n---\n\n' : '\n\n';
          if (userStopped) {
            const note = '\n\n*Stopped.*';
            const combined =
              (m2.text || '') + (m2.toolProgressText || '') + (m2.assistantPreToolsText || '');
            if (combined.includes('*Stopped.*')) return { ...m2, isStreaming: false, summarizingResults: false };
            return { ...m2, text: (m2.text || '') + note, isStreaming: false, summarizingResults: false };
          }
          if (timedOut && (isFetchAbortError(e) || errText.includes('Timed out'))) {
            const note = `${sep}**Timed out** waiting for the rest of the response from Studio. Nothing below the separator is a complete answer.`;
            return {
              ...m2,
              text: (m2.text || '').includes('Timed out') ? m2.text : (m2.text || '') + note,
              isStreaming: false,
              summarizingResults: false
            };
          }
          if (e instanceof AiAssistantIncompleteStreamError) {
            const combinedCheck =
              (m2.text || '') +
              (m2.toolProgressText || '') +
              (m2.assistantPreToolsText || '') +
              (m2.reasoningStreamText || '');
            if (combinedCheck.includes('**Could not finish.**')) {
              return { ...m2, isStreaming: false, summarizingResults: false };
            }
            const note = `${sep}**Could not finish.** ${e.message}`;
            return {
              ...m2,
              text: (m2.text || '') + note,
              isStreaming: false,
              summarizingResults: false
            };
          }
          if (abortWithoutExplicitStop) {
            const note = `${sep}**Interrupted.** The request was cancelled or the browser lost the connection before the assistant finished. Partial text above is not a full reply.`;
            return {
              ...m2,
              text: (m2.text || '').includes('**Interrupted.**') ? m2.text : (m2.text || '') + note,
              isStreaming: false,
              summarizingResults: false
            };
          }
          const detail = sanitizeErrorForAuthor(errText);
          const note = `${sep}**Studio request failed.** ${detail}`;
          return {
            ...m2,
            text: (m2.text || '').includes('**Studio request failed.**') ? m2.text : (m2.text || '') + note,
            isStreaming: false,
            summarizingResults: false
          };
        })
      );
    } finally {
      setSending(false);
    }
  };

  if (loading) {
    return (
      <Box sx={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress size={28} />
      </Box>
    );
  }

  if (configError) {
    return (
      <Box sx={{ p: 2 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          Assistant unavailable
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {configError}
        </Typography>
      </Box>
    );
  }

  const isIcePanel = embedTarget === 'icePanel';
  const chatSurfaceBg =
    theme.palette.mode === 'dark' ? theme.palette.grey[900] : theme.palette.grey[50];

  const messageBubbles = messages.map((m) => (
    <Box
      key={m.id}
      sx={{
        display: 'flex',
        justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start'
      }}
    >
      <Paper
        elevation={0}
        sx={{
          position: 'relative',
          px: 1.5,
          py: 1,
          pr: 4.25,
          maxWidth: '80%',
          borderRadius: 2,
          backgroundColor:
            m.role === 'user'
              ? theme.palette.primary.main
              : theme.palette.mode === 'dark'
                ? theme.palette.grey[800]
                : '#fff',
          color: m.role === 'user' ? theme.palette.primary.contrastText : theme.palette.text.primary,
          border:
            m.role === 'user'
              ? 'none'
              : `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[700] : theme.palette.grey[200]}`
        }}
      >
        <Tooltip title="Remove from chat history">
          <IconButton
            size="small"
            aria-label="Remove message from chat history"
            onClick={() => removeBubble(m.id)}
            sx={{
              position: 'absolute',
              top: 2,
              right: 2,
              zIndex: 1,
              ...(m.role === 'user'
                ? {
                    color: 'inherit',
                    opacity: 0.92,
                    '&:hover': { opacity: 1, bgcolor: 'rgba(255,255,255,0.12)' }
                  }
                : {
                    color: 'text.secondary',
                    opacity: 0.9,
                    '&:hover': { opacity: 1, bgcolor: 'action.hover' }
                  })
            }}
          >
            <CloseRounded fontSize="inherit" sx={{ fontSize: 18 }} />
          </IconButton>
        </Tooltip>
        <Box sx={{ minWidth: 0 }}>
          {m.role === 'assistant' || m.role === 'system' ? (
            <Box>
              {m.reasoningStreamText?.trim() && m.isStreaming ? (
                <AssistantReasoningLive text={m.reasoningStreamText || ''} />
              ) : null}
              {m.pipelineHeartbeat ? (
                <PipelineHeartbeatBar
                  elapsedSec={m.pipelineHeartbeat.elapsedSec}
                  nextInSec={m.pipelineHeartbeat.nextInSec}
                  hint={m.pipelineHeartbeat.hint}
                />
              ) : null}
              {m.toolProgressText?.trim() && m.assistantPreToolsText !== undefined ? (
                <>
                  {m.assistantPreToolsText.trim() ? (
                    <Box sx={{ mb: 1 }}>
                      <MarkdownMessage
                        text={stripDisplayedGeneratedImages(
                          stripStudioAiInlineImageMarkdownFromText(m.assistantPreToolsText, m.studioAiInlineImageUrls),
                          combineGeneratedImageSources(m.studioAiInlineImageUrls, m.assistantPreToolsText)
                        )}
                        studioAiInlineImageUrls={
                          combineGeneratedImageSources(m.studioAiInlineImageUrls, m.assistantPreToolsText).length
                            ? undefined
                            : m.studioAiInlineImageUrls
                        }
                      />
                    </Box>
                  ) : null}
                  <ToolProgressScrollArea text={m.toolProgressText} />
                  {m.summarizingResults ? (
                    <Typography
                      variant="caption"
                      component="p"
                      sx={{
                        mt: 0.75,
                        mb: 0,
                        color: 'text.secondary',
                        opacity: 0.85,
                        fontStyle: 'italic'
                      }}
                    >
                      Summarizing results…
                    </Typography>
                  ) : null}
                  {(() => {
                    const tailRaw = dedupeAssistantPostToolsMarkdown(m.assistantPreToolsText, m.text);
                    const imageStripSources = combineGeneratedImageSources(m.studioAiInlineImageUrls, tailRaw);
                    const tailDisplay = stripDisplayedGeneratedImages(
                      stripStudioAiInlineImageMarkdownFromText(tailRaw, m.studioAiInlineImageUrls),
                      imageStripSources
                    );
                    const mdUrls = imageStripSources.length ? undefined : m.studioAiInlineImageUrls;
                    const showGenImgPlaceholder = shouldShowGenerateImagePlaceholder(
                      m.toolProgressText,
                      tailRaw,
                      m.studioAiInlineImageUrls
                    );
                    return (
                      <>
                        {imageStripSources.length ? <AssistantChatGeneratedImages sources={imageStripSources} /> : null}
                        {!imageStripSources.length && showGenImgPlaceholder ? (
                          <GenerateImageBlurredPlaceholder />
                        ) : null}
                        <MarkdownMessage text={tailDisplay} studioAiInlineImageUrls={mdUrls} />
                      </>
                    );
                  })()}
                  <AssistantPipelineTimingLine wallMs={m.toolPipelineWallMs} />
                </>
              ) : (
                <>
                  {m.toolProgressText?.trim() ? (
                    <ToolProgressScrollArea text={m.toolProgressText} />
                  ) : null}
                  {m.summarizingResults ? (
                    <Typography
                      variant="caption"
                      component="p"
                      sx={{
                        mt: 0.75,
                        mb: 0,
                        color: 'text.secondary',
                        opacity: 0.85,
                        fontStyle: 'italic'
                      }}
                    >
                      Summarizing results…
                    </Typography>
                  ) : null}
                  {(() => {
                    const tailRaw = dedupeAssistantPostToolsMarkdown(m.assistantPreToolsText, m.text);
                    const imageStripSources = combineGeneratedImageSources(m.studioAiInlineImageUrls, tailRaw);
                    const tailDisplay = stripDisplayedGeneratedImages(
                      stripStudioAiInlineImageMarkdownFromText(tailRaw, m.studioAiInlineImageUrls),
                      imageStripSources
                    );
                    const mdUrls = imageStripSources.length ? undefined : m.studioAiInlineImageUrls;
                    const showGenImgPlaceholder = shouldShowGenerateImagePlaceholder(
                      m.toolProgressText,
                      tailRaw,
                      m.studioAiInlineImageUrls
                    );
                    return (
                      <>
                        {imageStripSources.length ? <AssistantChatGeneratedImages sources={imageStripSources} /> : null}
                        {!imageStripSources.length && showGenImgPlaceholder ? (
                          <GenerateImageBlurredPlaceholder />
                        ) : null}
                        <MarkdownMessage text={tailDisplay} studioAiInlineImageUrls={mdUrls} />
                      </>
                    );
                  })()}
                  <AssistantPipelineTimingLine wallMs={m.toolPipelineWallMs} />
                </>
              )}
            </Box>
          ) : (
            <>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
                {m.text}
              </Typography>
              <Box
                sx={{
                  display: 'flex',
                  justifyContent: 'flex-end',
                  alignItems: 'center',
                  gap: 0.25,
                  mt: 0.75,
                  opacity: 0.92
                }}
              >
                <Tooltip title="Copy message">
                  <IconButton
                    size="small"
                    onClick={() => void copyToClipboard(m.text)}
                    aria-label="Copy user message"
                    sx={{ color: 'inherit' }}
                  >
                    <ContentCopyRounded fontSize="small" />
                  </IconButton>
                </Tooltip>
                <Tooltip title="Replay (send again)">
                  <span>
                    <IconButton
                      size="small"
                      onClick={() => void startSend(m.text.trim(), m.text.trim())}
                      disabled={sending}
                      aria-label="Replay user message"
                      sx={{ color: 'inherit' }}
                    >
                      <ReplayRounded fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              </Box>
            </>
          )}
        </Box>
        {m.role === 'assistant' && (m.isStreaming || m.pipelineHeartbeat) && (
          <Stack direction="row" alignItems="center" spacing={0.75} sx={{ mt: 0.5, opacity: 0.8 }}>
            <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }} aria-label="Assistant is working">
              <Typography variant="caption" sx={{ lineHeight: 1 }}>
                Working
              </Typography>
              <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.25 }}>
                {[0, 1, 2].map((i) => (
                  <Box
                    key={i}
                    component="span"
                    sx={{
                      width: 4,
                      height: 4,
                      borderRadius: '50%',
                      backgroundColor: 'currentColor',
                      display: 'inline-block',
                      animation: 'cqTypingDotBounce 1.1s infinite ease-in-out',
                      animationDelay: `${i * 0.15}s`,
                      '@keyframes cqTypingDotBounce': {
                        '0%, 80%, 100%': { transform: 'translateY(0)', opacity: 0.4 },
                        '40%': { transform: 'translateY(-2px)', opacity: 1 }
                      }
                    }}
                  />
                ))}
              </Box>
            </Box>
            <Tooltip title="Stop">
              <IconButton
                size="small"
                color="error"
                onClick={() => stopStreaming()}
                aria-label="Stop assistant"
              >
                <StopRounded fontSize="small" />
              </IconButton>
            </Tooltip>
          </Stack>
        )}
        {!m.isStreaming &&
          (m.role === 'assistant' || m.role === 'system') &&
          (m.text.trim() ||
            (m.toolProgressText && m.toolProgressText.trim()) ||
            (m.assistantPreToolsText && m.assistantPreToolsText.trim())) && (
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 0.75 }}>
            <Tooltip title="Copy response">
              <IconButton
                size="small"
                onClick={() =>
                  void copyToClipboard(
                    m.toolProgressText?.trim() && m.assistantPreToolsText !== undefined
                      ? [
                          m.assistantPreToolsText?.trim(),
                          m.toolProgressText.trim(),
                          dedupeAssistantPostToolsMarkdown(m.assistantPreToolsText, m.text).trim()
                        ]
                          .filter(Boolean)
                          .join('\n\n---\n\n')
                      : [m.toolProgressText?.trim(), dedupeAssistantPostToolsMarkdown(m.assistantPreToolsText, m.text).trim()]
                          .filter(Boolean)
                          .join('\n\n---\n\n')
                  )
                }
                aria-label="Copy response"
                sx={{
                  opacity: 0.75,
                  color: theme.palette.text.secondary
                }}
              >
                <ContentCopyRounded fontSize="small" />
              </IconButton>
            </Tooltip>
          </Box>
        )}
      </Paper>
    </Box>
  ));

  const quickPromptsRow =
    quickMessagesToShow.length > 0 ? (
      <Box sx={{ p: 1.5, flexShrink: 0, minWidth: 0 }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1}
          sx={{ width: '100%', minWidth: 0, flexWrap: { sm: 'wrap' }, rowGap: 1 }}
        >
          {quickMessagesToShow.map((qm, idx) => (
            <Button
              key={`${qm.userText}-${idx}`}
              size="small"
              variant="outlined"
              onClick={() =>
                startSend(promptToSend(qm), qm.userText, { omitTools: qm.omitTools === true })
              }
              disabled={sending}
              sx={{
                width: { xs: '100%', sm: 'auto' },
                maxWidth: { xs: '100%', sm: 'min(100%, 28rem)' },
                textTransform: 'none',
                justifyContent: 'flex-start',
                whiteSpace: 'normal',
                textAlign: 'left',
                py: 1,
                minHeight: 0,
                overflowWrap: 'anywhere',
                wordBreak: 'break-word'
              }}
            >
              {qm.userText}
            </Button>
          ))}
        </Stack>
      </Box>
    ) : null;

  /** ICE: drawer scrolls the whole widget — pin quick prompts only (Studio owns title/back in PanelHeader). */
  const iceStickyQuickPrompts =
    isIcePanel && quickPromptsRow ? (
      <Box
        sx={{
          position: 'sticky',
          top: 0,
          zIndex: 3,
          flexShrink: 0,
          bgcolor: chatSurfaceBg,
          borderBottom: 1,
          borderColor: 'divider',
          boxShadow: (t) =>
            t.palette.mode === 'dark' ? '0 2px 8px rgba(0,0,0,0.25)' : '0 2px 8px rgba(0,0,0,0.06)'
        }}
      >
        {quickPromptsRow}
        <Divider sx={{ flexShrink: 0 }} />
      </Box>
    ) : null;

  const composerSection = (
    <Box
      sx={{
        bgcolor: 'background.paper',
        borderTop: 1,
        borderColor: 'divider',
        boxShadow: (t) =>
          t.palette.mode === 'dark' ? '0 -4px 16px rgba(0,0,0,0.35)' : '0 -4px 16px rgba(0,0,0,0.06)',
        pb: 'calc(12px + env(safe-area-inset-bottom, 0px))',
        pt: 0,
        minWidth: 0,
        ...(isIcePanel
          ? { position: 'sticky', bottom: 0, zIndex: 4, alignSelf: 'stretch' }
          : { flexShrink: 0 })
      }}
    >
      <Box sx={{ px: { xs: 1, sm: 2 }, pt: 1.5, minWidth: 0 }}>
        <Stack direction="row" spacing={1} alignItems="flex-end" sx={{ width: '100%', minWidth: 0 }}>
          <Stack direction="row" spacing={0.5} alignItems="flex-end" sx={{ flexShrink: 0 }}>
            <Tooltip title="Start a new chat">
              <span>
                <IconButton
                  aria-label="New chat"
                  onClick={handleNewChat}
                  disabled={sending}
                  color="default"
                >
                  <AddCommentRounded />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="Copy debug session log (parsed timeline + verbatim redacted SSE — for improving assistant behavior)">
              <span>
                <IconButton
                  aria-label="Copy assistant debug session log"
                  onClick={() =>
                    void copyToClipboard(
                      sessionStreamLogRef.current.length
                        ? formatSessionLogForDebugCopy(sessionStreamLogRef.current)
                        : '(Session log is empty — send a message first.)'
                    )
                  }
                  disabled={sending}
                  color="default"
                >
                  <AssignmentRounded />
                </IconButton>
              </span>
            </Tooltip>
          </Stack>
          <Box sx={{ flex: '1 1 0%', minWidth: 0, minHeight: 0 }}>
            <TextField
              fullWidth
              multiline
              minRows={1}
              maxRows={promptFocused || voiceListening ? 8 : 1}
              size="small"
              placeholder={voiceListening ? 'Listening… speak your prompt' : promptPlaceholder}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onFocus={() => setPromptFocused(true)}
              onBlur={() => setPromptFocused(false)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  if (canSend) startSend(draft);
                }
              }}
              inputProps={{ readOnly: voiceListening }}
              disabled={false}
              sx={{
                minWidth: 0,
                '& .MuiInputBase-root': {
                  alignItems: 'flex-start',
                  maxHeight: 'min(12rem, 32vh)'
                },
                '& textarea': {
                  resize: 'none',
                  overflowY: 'auto !important',
                  maxHeight: 'min(11rem, 30vh) !important'
                }
              }}
            />
          </Box>
          <Stack direction="row" spacing={0.5} alignItems="flex-end" sx={{ flexShrink: 0 }}>
            {speechCtor ? (
              <Tooltip
                title={
                  voiceError
                    ? voiceError
                    : voiceListening
                      ? 'Stop voice input'
                      : 'Use voice instead of typing (browser speech recognition)'
                }
              >
                <span>
                  <IconButton
                    color={voiceListening ? 'error' : 'default'}
                    onClick={() => toggleVoiceInput()}
                    disabled={sending}
                    aria-label={voiceListening ? 'Stop voice input' : 'Start voice input'}
                    aria-pressed={voiceListening}
                  >
                    <MicRounded />
                  </IconButton>
                </span>
              </Tooltip>
            ) : null}
            <Tooltip title={sending ? 'Wait for the response to finish, or tap Stop beside Working' : 'Send'}>
              <span>
                <IconButton
                  color="primary"
                  onClick={() => startSend(draft)}
                  disabled={!canSend || sending}
                  aria-label="Send"
                >
                  <SendRounded />
                </IconButton>
              </span>
            </Tooltip>
          </Stack>
        </Stack>
        {verificationPrompts.length > 0 && !sending ? (
          <Box sx={{ mt: 1.25, px: 0.25, minWidth: 0, width: '100%' }}>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.75 }}>
              Optional checks — click to send as the next prompt
            </Typography>
            <Stack spacing={0.75} sx={{ width: '100%', minWidth: 0 }}>
              {verificationPrompts.map((vp, idx) => (
                <Chip
                  key={`cq-verify-${idx}-${vp.slice(0, 48)}`}
                  label={vp.length > 96 ? `${vp.slice(0, 93)}…` : vp}
                  size="small"
                  variant="outlined"
                  clickable
                  disabled={sending}
                  onClick={() => startSend(vp, vp)}
                  sx={{
                    width: '100%',
                    maxWidth: '100%',
                    height: 'auto',
                    minHeight: 32,
                    justifyContent: 'flex-start',
                    '& .MuiChip-label': {
                      whiteSpace: 'normal',
                      textAlign: 'left',
                      display: 'block',
                      py: 0.75,
                      overflowWrap: 'anywhere',
                      wordBreak: 'break-word'
                    }
                  }}
                />
              ))}
            </Stack>
          </Box>
        ) : null}
        {followUpActionPrompts.length > 0 && !sending ? (
          <Box sx={{ mt: verificationPrompts.length > 0 ? 1 : 1.25, px: 0.25, minWidth: 0, width: '100%' }}>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.75 }}>
              Suggested follow-ups — click to send as the next prompt
            </Typography>
            <Stack spacing={0.75} sx={{ width: '100%', minWidth: 0 }}>
              {followUpActionPrompts.map((fp, idx) => (
                <Chip
                  key={`cq-follow-${idx}-${fp.slice(0, 48)}`}
                  label={fp.length > 96 ? `${fp.slice(0, 93)}…` : fp}
                  size="small"
                  variant="outlined"
                  color="primary"
                  clickable
                  disabled={sending}
                  onClick={() => startSend(fp, fp)}
                  sx={{
                    width: '100%',
                    maxWidth: '100%',
                    height: 'auto',
                    minHeight: 32,
                    justifyContent: 'flex-start',
                    '& .MuiChip-label': {
                      whiteSpace: 'normal',
                      textAlign: 'left',
                      display: 'block',
                      py: 0.75,
                      overflowWrap: 'anywhere',
                      wordBreak: 'break-word'
                    }
                  }}
                />
              ))}
            </Stack>
          </Box>
        ) : null}
        {voiceError && !voiceListening ? (
          <Typography variant="caption" color="error" sx={{ mt: 0.75, display: 'block', px: 0.5 }}>
            {voiceError}
          </Typography>
        ) : null}
        {ttsAvailable ? (
          <FormControlLabel
            sx={{
              mt: 0.75,
              mr: 0,
              ml: 0,
              width: '100%',
              minWidth: 0,
              display: 'flex',
              alignItems: 'flex-start',
              gap: 0.75
            }}
            control={
              <Switch
                size="small"
                checked={readResponsesAloud}
                onChange={(_, checked) => {
                  setReadResponsesAloud(checked);
                  if (checked) {
                    const assistants = messages.filter((m) => m.role === 'assistant' || m.role === 'system');
                    const last = assistants[assistants.length - 1];
                    lastSpokenAssistantIdRef.current = last?.id ?? null;
                  } else {
                    window.speechSynthesis?.cancel();
                    lastSpokenAssistantIdRef.current = null;
                  }
                }}
                inputProps={{ 'aria-label': 'Read assistant responses aloud' }}
                sx={{ mt: 0.25, flexShrink: 0 }}
              />
            }
            label={
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ userSelect: 'none', whiteSpace: 'normal', overflowWrap: 'anywhere' }}
              >
                Read responses aloud
              </Typography>
            }
          />
        ) : null}
      </Box>
    </Box>
  );

  if (isIcePanel) {
    return (
      <Box
        ref={iceRootRef}
        sx={{
          width: '100%',
          minWidth: 0,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'visible'
        }}
      >
        {iceStickyQuickPrompts}
        {!isIcePanel ? quickPromptsRow : null}
        {!isIcePanel && quickPromptsRow ? <Divider sx={{ flexShrink: 0 }} /> : null}
        <Box sx={{ bgcolor: chatSurfaceBg }}>
          <Stack spacing={1.25} sx={{ px: 2, pt: 2, pb: 1 }}>
            {messageBubbles}
          </Stack>
        </Box>
        {composerSection}
      </Box>
    );
  }

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        flex: 1,
        minHeight: 0,
        minWidth: 0,
        width: '100%',
        height: '100%',
        maxHeight: '100%',
        alignSelf: 'stretch',
        overflow: 'hidden'
      }}
    >
      {quickPromptsRow}
      <Divider sx={{ flexShrink: 0 }} />
      <Box
        ref={scrollRef}
        sx={{
          flex: '1 1 0%',
          minHeight: 0,
          minWidth: 0,
          overflowY: 'auto',
          overflowX: 'hidden',
          WebkitOverflowScrolling: 'touch',
          background: chatSurfaceBg
        }}
      >
        <Stack spacing={1.25} sx={{ px: 2, pt: 2, pb: 1 }}>
          {messageBubbles}
        </Stack>
      </Box>
      <Divider sx={{ flexShrink: 0 }} />
      {composerSection}
    </Box>
  );
}

