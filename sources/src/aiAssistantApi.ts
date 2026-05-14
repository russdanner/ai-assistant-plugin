import type { ExpertSkillConfig } from './agentConfig';
import { getGlobalHeaders } from '@craftercms/studio-ui/utils/ajax';
import {
  getXSRFToken,
  getRequestForgeryTokenHeaderName
} from '@craftercms/studio-ui/utils/auth';

export const CRAFTERQ_CHAT_USER_HEADER = 'X-CrafterQ-Chat-User';

/**
 * Thrown when the HTTP response body ends without an SSE frame carrying {@code metadata.completed} or
 * {@code metadata.error} — e.g. network drop, proxy reset, Studio thread died, or browser navigated away.
 */
export class AiAssistantIncompleteStreamError extends Error {
  constructor() {
    super(
      'The connection closed before the assistant finished sending the reply (Studio never sent a completion signal). What you see above is only partial output.'
    );
    this.name = 'AiAssistantIncompleteStreamError';
  }
}
const CRAFTERQ_CHAT_USER_STORAGE_KEY = 'crafterq.chatUser';

export interface AiAssistantChatListResponse {
  chats: Array<{
    id: string;
    title?: string;
    updatedAt?: string;
    createdAt?: string;
    [key: string]: unknown;
  }>;
}

export interface AiAssistantChatMessage {
  messageType?: string; // "USER" | "ASSISTANT" (observed)
  text?: string;
  metadata?: {
    chatId?: string;
    messageId?: string;
    completed?: boolean;
    error?: boolean;
    /** Server set when the OpenAI plan gate stops the tool workflow — client may replace partial assistant output. */
    planGateFailure?: boolean;
    role?: string;
    [key: string]: unknown;
  };
  toolCalls?: unknown[];
  media?: unknown[];
  [key: string]: unknown;
}

export interface AiAssistantChatMessagesResponse {
  messages: AiAssistantChatMessage[];
}

function getStoredChatUser(): string | null {
  try {
    return window.localStorage.getItem(CRAFTERQ_CHAT_USER_STORAGE_KEY);
  } catch {
    return null;
  }
}

function setStoredChatUser(value: string | null) {
  try {
    if (!value) return;
    const trimmed = value.trim();
    if (trimmed) window.localStorage.setItem(CRAFTERQ_CHAT_USER_STORAGE_KEY, trimmed);
  } catch {
    // ignore
  }
}

export async function listChats(agentId: string): Promise<AiAssistantChatListResponse['chats']> {
  const url = new URL('https://api.crafterq.ai/v1/chats');
  url.searchParams.set('agentId', agentId);
  const token = getStoredChatUser();
  const res = await fetch(url.toString(), {
    mode: 'cors',
    credentials: 'include',
    headers: token ? { [CRAFTERQ_CHAT_USER_HEADER]: token } : undefined
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`CrafterQ listChats failed (${res.status}): ${text || res.statusText}`);
  }
  const json = (await res.json()) as AiAssistantChatListResponse;
  return json.chats ?? [];
}

export async function getChatMessages(chatId: string): Promise<AiAssistantChatMessage[]> {
  const token = getStoredChatUser();
  const res = await fetch(`https://api.crafterq.ai/v1/chats/${chatId}`, {
    mode: 'cors',
    credentials: 'include',
    headers: token ? { [CRAFTERQ_CHAT_USER_HEADER]: token } : undefined
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`CrafterQ getChat failed (${res.status}): ${text || res.statusText}`);
  }
  const json = (await res.json()) as AiAssistantChatMessagesResponse;
  return json.messages ?? [];
}

export interface StreamChatArgs {
  agentId: string;
  prompt: string;
  chatId?: string;
  /**
   * Studio preview item path (XB / ICE). Server appends **repository** authoring context for tools.
   * Omit in **form-engine** mode — unsaved edits live in the browser; use {@link authoringSurface} `formEngine` instead.
   */
  contentPath?: string;
  /** Preview content type id (XB). Omit in form-engine mode. */
  contentTypeId?: string;
  /** Studio UI label for the open item’s content type when the host exposes it (helps label→id matching). */
  contentTypeLabel?: string;
  /**
   * When the author’s browser shows Experience Builder preview (`…/studio/preview#/?page=…&site=…`), pass it so the
   * server-injected “Studio preview URL” matches the address bar (optional; server can synthesize from contentPath).
   */
  studioPreviewPageUrl?: string;
  /**
   * `preview` (default) — Experience Builder / preview chat; repo path + tools align with saved content.
   * `formEngine` — Content-type form assistant; client puts live `form.model` in the prompt; server must not imply repo == open form.
   */
  authoringSurface?: 'preview' | 'formEngine';
  /**
   * Only with `authoringSurface: 'formEngine'`. When true, server appends instructions for `crafterqFormFieldUpdates` JSON
   * so the browser can apply edits. **Never set for XB/ICE** — omit so preview uses tools + `contentPath` only.
   */
  formEngineClientJsonApply?: boolean;
  /**
   * Repository path of the item open in the content form (e.g. `/site/website/about/index.xml`).
   * With `formEngineClientJsonApply`, the server blocks WriteContent/publish/revert **only** for this path;
   * omit to fall back to blocking all repo writes for that session (safe when path is unknown).
   */
  formEngineItemPath?: string;
  /** `crafterQ` | `openAI` — must match server / widget configuration */
  llm?: string;
  /** Provider model id when llm is openAI; optional (server default gpt-4o-mini). Request body key **`llmModel`**. */
  llmModel?: string;
  /** OpenAI Images API model for GenerateImage; agent **imageModel** / request body **imageModel** (no default). */
  imageModel?: string;
  /** GenerateImage backend: agent **imageGenerator** / POST **imageGenerator** (blank / openAiWire / none / script:{id}). */
  imageGenerator?: string;
  /**
   * Optional key from widget ui.xml — server uses only if env/JVM key unset. Not recommended for production.
   */
  openAiApiKey?: string;
  /** Required by Studio plugin script API */
  siteId?: string;
  /**
   * Studio {@code crafterPreview} cookie value — sent to the plugin stream/chat API so {@code GetPreviewHtml} can
   * GET Engine preview markup without passing the token on every tool call.
   */
  previewToken?: string;
  /**
   * When false, server omits OpenAI CMS function tools (from ui.xml agent `enableTools`). Omitted defaults to tools on.
   * May be string when passed from serialized widget props.
   */
  enableTools?: boolean | string;
  /**
   * When true, this request omits CMS function tools (copy / image-style generation steps). Overrides enableTools for one round-trip only.
   * Same behavior for Experience Builder/ICE preview chat, floating dialog, and form-engine assistant (`authoringSurface`).
   */
  omitTools?: boolean;
  /**
   * Optional subset of CMS tool wire names for this request (POST **enabledBuiltInTools**). Include **`mcp:*`**
   * to retain all MCP tools after site policy. Omitted = full catalog (subject to site **tools.json**).
   */
  enabledBuiltInTools?: string[];
  /** Per-agent markdown RAG sources (OpenAI); forwarded as JSON for QueryExpertGuidance. */
  expertSkills?: ExpertSkillConfig[];
  /**
   * 1–64; forwarded on stream POST for default **TranslateContentBatch** parallelism when the model omits **maxConcurrency**
   * (from agent ui.xml **translateBatchConcurrency**). Server default 25 when omitted.
   */
  translateBatchConcurrency?: number;
  /** CrafterQ JWT for `Authorization: Bearer` on server-proxied api.crafterq.ai calls (ui.xml **crafterQBearerToken**). */
  crafterQBearerToken?: string;
  /** Studio host env var **name** for the CrafterQ JWT (ui.xml **crafterQBearerTokenEnv**); overrides literal when set and non-empty. */
  crafterQBearerTokenEnv?: string;
  signal?: AbortSignal;
  onMessage: (event: AiAssistantChatMessage) => void;
  /**
   * Every raw SSE {@code data:} JSON line as received (before parse). For diagnostics / “copy full stream” logs.
   */
  onRawSseDataLine?: (jsonLine: string) => void;
}

/**
 * Streams chat responses via SSE (text/event-stream) using fetch streaming.
 * This mirrors the widget behavior: POST /v1/chats?stream=true&agentId=...
 */
/**
 * Reads the Studio Experience Builder {@code crafterPreview} cookie when present (document.cookie).
 * Used to authorize {@code GetPreviewHtml} on the server via the chat POST body {@code previewToken}.
 */
export function readCrafterPreviewTokenFromCookie(): string | undefined {
  if (typeof document === 'undefined') return undefined;
  const parts = document.cookie.split(';');
  for (const raw of parts) {
    const p = raw.trim();
    const low = p.toLowerCase();
    if (!low.startsWith('crafterpreview=')) continue;
    const v = p.slice(p.indexOf('=') + 1).trim();
    if (!v) return undefined;
    try {
      return decodeURIComponent(v);
    } catch {
      return v;
    }
  }
  return undefined;
}

export function buildStudioAuthHeaders(): Record<string, string> {
  const out: Record<string, string> = {};
  const global = getGlobalHeaders();
  for (const [k, v] of Object.entries(global)) {
    if (v != null && String(v).trim() !== '') out[k] = String(v);
  }
  const xsrfToken = getXSRFToken();
  const xsrfHeaderName = getRequestForgeryTokenHeaderName();
  if (xsrfToken && xsrfHeaderName) out[xsrfHeaderName] = xsrfToken;
  return out;
}

export async function streamChat(args: StreamChatArgs): Promise<void> {
  const {
    agentId,
    prompt,
    chatId,
    contentPath,
    contentTypeId,
    contentTypeLabel,
    studioPreviewPageUrl,
    authoringSurface,
    formEngineClientJsonApply,
    formEngineItemPath,
    llm,
    llmModel,
    imageModel,
    imageGenerator,
    openAiApiKey,
    siteId,
    previewToken,
    enableTools,
    omitTools,
    enabledBuiltInTools,
    expertSkills,
    translateBatchConcurrency,
    crafterQBearerToken,
    crafterQBearerTokenEnv,
    signal,
    onMessage,
    onRawSseDataLine
  } = args;
  const token = getStoredChatUser();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    ...buildStudioAuthHeaders()
  };
  if (token) headers[CRAFTERQ_CHAT_USER_HEADER] = token;

  let pluginStreamUrl = '/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream';
  if (siteId) {
    pluginStreamUrl += (pluginStreamUrl.includes('?') ? '&' : '?') + 'siteId=' + encodeURIComponent(siteId);
  }
  const requestBody: Record<string, unknown> =
    chatId != null && String(chatId).trim() !== ''
      ? { agentId, prompt: prompt ?? '', chatId }
      : { agentId, prompt: prompt ?? '' };
  if (siteId != null && String(siteId).trim() !== '') requestBody.siteId = String(siteId).trim();
  if (llm != null && String(llm).trim() !== '') requestBody.llm = llm;
  if (llmModel != null && String(llmModel).trim() !== '') requestBody.llmModel = String(llmModel).trim();
  if (imageModel != null && String(imageModel).trim() !== '') requestBody.imageModel = String(imageModel).trim();
  if (imageGenerator != null && String(imageGenerator).trim() !== '')
    requestBody.imageGenerator = String(imageGenerator).trim();
  if (openAiApiKey != null && String(openAiApiKey).trim() !== '') requestBody.openAiApiKey = String(openAiApiKey).trim();
  if (contentPath != null && String(contentPath).trim() !== '') requestBody.contentPath = String(contentPath).trim();
  if (contentTypeId != null && String(contentTypeId).trim() !== '')
    requestBody.contentTypeId = String(contentTypeId).trim();
  if (contentTypeLabel != null && String(contentTypeLabel).trim() !== '')
    requestBody.contentTypeLabel = String(contentTypeLabel).trim();
  if (studioPreviewPageUrl != null && String(studioPreviewPageUrl).trim() !== '')
    requestBody.studioPreviewPageUrl = String(studioPreviewPageUrl).trim();
  if (authoringSurface != null && String(authoringSurface).trim() !== '')
    requestBody.authoringSurface = String(authoringSurface).trim();
  if (formEngineClientJsonApply === true) requestBody.formEngineClientJsonApply = true;
  if (formEngineItemPath != null && String(formEngineItemPath).trim() !== '')
    requestBody.formEngineItemPath = String(formEngineItemPath).trim();
  if (omitTools === true) requestBody.omitTools = true;
  if (
    enableTools === false ||
    (typeof enableTools === 'string' && ['false', '0', 'no'].includes(enableTools.trim().toLowerCase()))
  ) {
    requestBody.enableTools = false;
  }
  if (Array.isArray(enabledBuiltInTools) && enabledBuiltInTools.length > 0) {
    requestBody.enabledBuiltInTools = enabledBuiltInTools;
  }
  if (previewToken != null && String(previewToken).trim() !== '') {
    requestBody.previewToken = String(previewToken).trim();
  }
  if (Array.isArray(expertSkills) && expertSkills.length > 0) {
    requestBody.expertSkills = expertSkills.map((s) => ({
      name: s.name,
      url: s.url,
      description: s.description
    }));
  }
  if (
    translateBatchConcurrency != null &&
    Number.isFinite(translateBatchConcurrency) &&
    translateBatchConcurrency >= 1 &&
    translateBatchConcurrency <= 64
  ) {
    requestBody.translateBatchConcurrency = Math.floor(translateBatchConcurrency);
  }
  const bEnv = crafterQBearerTokenEnv != null ? String(crafterQBearerTokenEnv).trim() : '';
  if (bEnv) requestBody.crafterQBearerTokenEnv = bEnv;
  const bTok = crafterQBearerToken != null ? String(crafterQBearerToken).trim() : '';
  if (bTok) requestBody.crafterQBearerToken = bTok;

  const streamFromResponse = async (res: Response, failPrefix: string): Promise<void> => {
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`${failPrefix} failed (${res.status}): ${text || res.statusText}`);
    }

    const reader = res.body?.getReader();
    if (!reader) throw new Error(`${failPrefix}: no response body reader available`);

    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    /** Server may keep the HTTP connection open after the last SSE event; resolve as soon as we see a terminal frame. */
    let sawTerminalEvent = false;

    const dispatchSseDataLine = (jsonLine: string) => {
      try {
        onRawSseDataLine?.(jsonLine);
        const evt = JSON.parse(jsonLine) as AiAssistantChatMessage;
        onMessage(evt);
        const m = evt.metadata;
        if (m && (m.completed === true || m.error === true)) {
          sawTerminalEvent = true;
        }
      } catch {
        // ignore malformed chunks
      }
    };

    const processBufferFrames = () => {
      let frameEnd: number;
      while ((frameEnd = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, frameEnd);
        buffer = buffer.slice(frameEnd + 2);

        const lines = frame.split('\n');
        for (const rawLine of lines) {
          const line = rawLine.trimEnd();
          if (!line.startsWith('data:')) continue;
          const data = line.slice('data:'.length).trim();
          if (!data) continue;
          dispatchSseDataLine(data);
        }
      }
    };

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      processBufferFrames();
      if (sawTerminalEvent) {
        try {
          await reader.cancel();
        } catch {
          // ignore
        }
        return;
      }
    }
    if (!sawTerminalEvent) {
      throw new AiAssistantIncompleteStreamError();
    }
  };

  const res = await fetch(pluginStreamUrl, {
    method: 'POST',
    credentials: 'include',
    headers,
    body: JSON.stringify(requestBody),
    signal
  });
  await streamFromResponse(res, 'Plugin stream');
}

