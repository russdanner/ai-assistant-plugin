import React, { useMemo, useState } from 'react';
import {
  Box,
  IconButton,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  Tooltip,
  Typography,
  useTheme
} from '@mui/material';
import ContentCopyRounded from '@mui/icons-material/ContentCopyRounded';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import StudioDraggableImage from './StudioDraggableImage';

/**
 * OpenAI / streaming payloads sometimes leave escape sequences as the two-character
 * sequences backslash+n or backslash+t instead of real newlines/tabs. Markdown then
 * shows one long line. Convert those literals to actual whitespace for display.
 */
export function normalizeLlmLiteralEscapes(input: string): string {
  if (!input) return input;
  return input
    .replace(/\\r\\n/g, '\n')
    .replace(/\\n/g, '\n')
    .replace(/\\r/g, '\n')
    .replace(/\\t/g, '\t');
}

/**
 * Micromark / GFM can fail to emit an {@code image} node for very long raw {@code data:image/...;base64,...}
 * destinations in {@code ![alt](...)} form, leaving the literal markdown visible. CommonMark allows
 * {@code ![alt](<...>)} so the destination is unambiguous; {@link StudioDraggableImage} still receives the
 * inner {@code data:} URL for decode / blob preview.
 */
function newBlobRefId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`;
}

/** End index (exclusive) of a {@code data:image/...;base64,...} run starting at {@code start}. */
function dataImageBase64RunEnd(input: string, start: number): number {
  const head = input.slice(start);
  const m = /^data:image\/[a-z0-9.+-]+;base64,/i.exec(head);
  if (!m) {
    return Math.min(input.length, start + 1);
  }
  let i = start + m[0].length;
  while (i < input.length) {
    const c = input[i]!;
    if (/[A-Za-z0-9+/=]/.test(c)) {
      i++;
    } else if (/\s/.test(c)) {
      // PEM-style wrapping / JSON pretty-print — still part of the payload until non-base64 char.
      i++;
    } else {
      break;
    }
  }
  return i;
}

/**
 * Strip whitespace inside {@code data:image/*;base64,...} payloads so markdown parsers see one contiguous URL and
 * {@link StudioDraggableImage} gets valid base64 for {@code atob}.
 */
function compactAllDataImageBase64Runs(text: string): string {
  if (!text || text.indexOf('data:image') < 0) {
    return text;
  }
  const lower = text.toLowerCase();
  const parts: string[] = [];
  let i = 0;
  while (i < text.length) {
    const idx = lower.indexOf('data:image/', i);
    if (idx < 0) {
      parts.push(text.slice(i));
      break;
    }
    parts.push(text.slice(i, idx));
    const head = text.slice(idx);
    const m = /^data:image\/[a-z0-9.+-]+;base64,/i.exec(head);
    if (!m) {
      parts.push(text[idx]!);
      i = idx + 1;
      continue;
    }
    const dataStart = idx + m[0].length;
    let j = dataStart;
    while (j < text.length) {
      const c = text[j]!;
      if (/[A-Za-z0-9+/=]/.test(c)) {
        j++;
      } else if (/\s/.test(c)) {
        j++;
      } else {
        break;
      }
    }
    parts.push(text.slice(idx, dataStart) + text.slice(dataStart, j).replace(/\s+/g, ''));
    i = j;
  }
  return parts.join('');
}

function isDataImageUrlInsideMarkdownImageDestination(input: string, startIdx: number): boolean {
  const lookback = input.slice(Math.max(0, startIdx - 12), startIdx);
  return /\]\(\s*<?\s*$/i.test(lookback);
}

/**
 * Models and the server sometimes emit a raw {@code data:image/...;base64,...} payload without
 * {@code ![alt](url)}. GFM leaves that as paragraph / autolink text (a giant blob). Wrap as image markdown first.
 */
export function wrapBareLongDataImageUrlsAsMarkdown(input: string, minUrlChars = 256): string {
  if (!input || input.indexOf('data:image') < 0) {
    return input;
  }
  const lower = input.toLowerCase();
  const parts: string[] = [];
  let i = 0;
  while (i < input.length) {
    const idx = lower.indexOf('data:image/', i);
    if (idx < 0) {
      parts.push(input.slice(i));
      break;
    }
    if (isDataImageUrlInsideMarkdownImageDestination(input, idx)) {
      const end = dataImageBase64RunEnd(input, idx);
      parts.push(input.slice(i, end));
      i = end;
      continue;
    }
    const end = dataImageBase64RunEnd(input, idx);
    const url = input.slice(idx, end);
    parts.push(input.slice(i, idx));
    if (url.length >= minUrlChars) {
      parts.push(`\n\n![](<${url}>)\n\n`);
    } else {
      parts.push(url);
    }
    i = end;
  }
  return parts.join('');
}

/** Apply data:image compaction only outside fenced ``` code blocks (preserve literal examples in fences). */
function preprocessAssistantMarkdownImagesSegment(
  text: string,
  longDataImageBlobRefMap: Map<string, string>
): string {
  const normalized = normalizeLlmLiteralEscapes(text);
  const compactData = compactAllDataImageBase64Runs(normalized);
  const withBareWrapped = wrapBareLongDataImageUrlsAsMarkdown(compactData);
  const shortened = replaceLongDataImageMarkdownWithBlobRefs(withBareWrapped, longDataImageBlobRefMap);
  return wrapDataImageMarkdownDestInAngleBrackets(shortened);
}

/** Normalize escapes, wrap bare {@code data:image} runs, shorten to blob refs, angle-bracket destinations. */
export function preprocessAssistantMarkdownImages(text: string): {
  displayText: string;
  longDataImageBlobRefMap: Map<string, string>;
} {
  const longDataImageBlobRefMap = new Map<string, string>();
  if (!text || text.indexOf('```') < 0) {
    return {
      displayText: preprocessAssistantMarkdownImagesSegment(text, longDataImageBlobRefMap),
      longDataImageBlobRefMap
    };
  }
  const fenceRe = /```[^\n]*\n[\s\S]*?```/g;
  const parts: string[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = fenceRe.exec(text)) !== null) {
    if (m.index > last) {
      parts.push(preprocessAssistantMarkdownImagesSegment(text.slice(last, m.index), longDataImageBlobRefMap));
    }
    parts.push(m[0]);
    last = m.index + m[0].length;
  }
  if (last < text.length) {
    parts.push(preprocessAssistantMarkdownImagesSegment(text.slice(last), longDataImageBlobRefMap));
  }
  return { displayText: parts.join(''), longDataImageBlobRefMap };
}

/**
 * Micromark / GFM can leave {@code ![alt](data:image/...;base64,...)} as plain text when the destination is huge.
 * Replace long {@code data:image/} destinations with short {@code studio-ai-blob-ref://<id>} URLs and record the
 * mapping in {@code outMap} so the {@code img} renderer can pass the original wire URL to {@link StudioDraggableImage}.
 */
export function replaceLongDataImageMarkdownWithBlobRefs(input: string, outMap: Map<string, string>): string {
  if (!input || input.indexOf('![') < 0 || input.indexOf('data:image') < 0) {
    return input;
  }
  const parts: string[] = [];
  let i = 0;
  while (i < input.length) {
    const start = input.indexOf('![', i);
    if (start < 0) {
      parts.push(input.slice(i));
      break;
    }
    parts.push(input.slice(i, start));
    const rb = input.indexOf(']', start + 2);
    if (rb < 0) {
      parts.push(input.slice(start));
      break;
    }
    const op = input.indexOf('(', rb);
    if (op !== rb + 1) {
      parts.push(input[start]!);
      i = start + 1;
      continue;
    }
    let p = op + 1;
    while (p < input.length && /\s/.test(input[p]!)) {
      p++;
    }
    let url = '';
    let closeParen = -1;
    if (input[p] === '<') {
      const gt = input.indexOf('>', p + 1);
      if (gt < 0) {
        parts.push(input.slice(start));
        break;
      }
      url = input.slice(p + 1, gt).trim();
      closeParen = input.indexOf(')', gt + 1);
    } else {
      closeParen = input.indexOf(')', p);
      if (closeParen < 0) {
        parts.push(input.slice(start));
        break;
      }
      url = input.slice(p, closeParen).trim();
    }
    if (closeParen < 0) {
      parts.push(input.slice(start));
      break;
    }
    const alt = input.slice(start + 2, rb);
    const low = url.toLowerCase();
    if (low.startsWith('data:image/') && url.length > 256) {
      const id = newBlobRefId();
      outMap.set(id, url);
      parts.push(`![${alt}](studio-ai-blob-ref://${id})`);
    } else {
      parts.push(input.slice(start, closeParen + 1));
    }
    i = closeParen + 1;
  }
  return parts.join('');
}

export function wrapDataImageMarkdownDestInAngleBrackets(input: string): string {
  if (!input || input.indexOf('![') < 0 || input.indexOf('data:image') < 0) {
    return input;
  }
  const parts: string[] = [];
  let i = 0;
  while (i < input.length) {
    const start = input.indexOf('![', i);
    if (start < 0) {
      parts.push(input.slice(i));
      break;
    }
    parts.push(input.slice(i, start));
    const rb = input.indexOf(']', start + 2);
    if (rb < 0) {
      parts.push(input.slice(start));
      break;
    }
    const op = input.indexOf('(', rb);
    if (op !== rb + 1) {
      parts.push(input[start]!);
      i = start + 1;
      continue;
    }
    let p = op + 1;
    while (p < input.length && /\s/.test(input[p]!)) {
      p++;
    }
    let url = '';
    let closeParen = -1;
    if (input[p] === '<') {
      const gt = input.indexOf('>', p + 1);
      if (gt < 0) {
        parts.push(input.slice(start));
        break;
      }
      url = input.slice(p + 1, gt).trim();
      closeParen = input.indexOf(')', gt + 1);
    } else {
      closeParen = input.indexOf(')', p);
      if (closeParen < 0) {
        parts.push(input.slice(start));
        break;
      }
      url = input.slice(p, closeParen).trim();
    }
    if (closeParen < 0) {
      parts.push(input.slice(start));
      break;
    }
    const alt = input.slice(start + 2, rb);
    const low = url.toLowerCase();
    if (!low.startsWith('data:image/')) {
      parts.push(input.slice(start, closeParen + 1));
      i = closeParen + 1;
      continue;
    }
    if (input[p] === '<') {
      parts.push(input.slice(start, closeParen + 1));
      i = closeParen + 1;
      continue;
    }
    parts.push(`![${alt}](<${url}>)`);
    i = closeParen + 1;
  }
  return parts.join('');
}

function assistantMarkdownUnresolvedStudioInlineImageRef(src: string): boolean {
  return src.trim().toLowerCase().startsWith('studio-ai-inline-image:');
}

function reactNodeToPlainText(node: React.ReactNode): string {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(reactNodeToPlainText).join('');
  if (React.isValidElement(node)) return reactNodeToPlainText((node.props as { children?: React.ReactNode }).children);
  return '';
}

/**
 * Micromark can still leave image markdown or a raw {@code data:} URL as a single paragraph of text.
 * Recover by rendering {@link StudioDraggableImage} instead of a wall of base64.
 */
function findFirstMarkdownAssistantImage(plain: string): {
  alt: string;
  url: string;
  start: number;
  end: number;
} | null {
  const re =
    /!\[([^\]]*)\]\(\s*(?:<(data:image\/[a-z0-9.+-]+;base64,[\s\S]*?)>|(data:image\/[a-z0-9.+-]+;base64,[\s\S]*?)|(studio-ai-blob-ref:\/\/[^)\s]+)|(studio-ai-inline-image:\/\/[^)\s]+))\s*\)/gi;
  const m = re.exec(plain);
  if (!m) {
    return null;
  }
  const alt = m[1] ?? '';
  const rawUrl = (m[2] || m[3] || m[4] || m[5] || '').trim();
  const url =
    rawUrl.startsWith('studio-ai-blob-ref://') ? rawUrl : rawUrl.replace(/\s+/g, '').trim();
  if (!url) {
    return null;
  }
  return { alt, url, start: m.index, end: m.index + m[0].length };
}

function AssistantMarkdownParagraph(props: Readonly<{
  children?: React.ReactNode;
  longDataImageBlobRefMap: Map<string, string>;
  studioAiInlineImageUrls?: Record<string, string>;
}>) {
  const { children, longDataImageBlobRefMap, studioAiInlineImageUrls } = props;
  const plain = reactNodeToPlainText(children).trim();
  if (!plain) {
    return (
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mb: 0.75 }}>
        {children}
      </Typography>
    );
  }
  const bareData = /^data:image\/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+$/i.exec(plain);
  if (bareData && bareData[0].replace(/\s+/g, '').length > 256) {
    return <StudioDraggableImage src={bareData[0].replace(/\s+/g, '')} alt="" />;
  }
  const rawImg = /^!\[([^\]]*)\]\(\s*(?:<([^>]+)>|([^)]+))\s*\)$/.exec(plain);
  if (rawImg) {
    const url = (rawImg[2] || rawImg[3] || '').trim();
    if (url) {
      return (
        <AssistantMarkdownImg
          src={url}
          alt={rawImg[1]}
          longDataImageBlobRefMap={longDataImageBlobRefMap}
          studioAiInlineImageUrls={studioAiInlineImageUrls}
        />
      );
    }
  }
  const recovered = findFirstMarkdownAssistantImage(plain);
  if (
    recovered &&
    (recovered.url.length > 120 ||
      recovered.url.startsWith('studio-ai-blob-ref://') ||
      assistantMarkdownUnresolvedStudioInlineImageRef(recovered.url))
  ) {
    const before = plain.slice(0, recovered.start).trim();
    const after = plain.slice(recovered.end).trim();
    return (
      <Box sx={{ mb: 0.75 }}>
        {before ? (
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mb: 0.5 }}>
            {before}
          </Typography>
        ) : null}
        <AssistantMarkdownImg src={recovered.url} alt={recovered.alt} longDataImageBlobRefMap={longDataImageBlobRefMap} studioAiInlineImageUrls={studioAiInlineImageUrls} />
        {after ? (
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mt: 0.5 }}>
            {after}
          </Typography>
        ) : null}
      </Box>
    );
  }
  return (
    <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mb: 0.75 }}>
      {children}
    </Typography>
  );
}

/** Resolves {@code studio-ai-blob-ref://} and {@code studio-ai-inline-image://toolCallId} (via optional SSE metadata map). */
function AssistantMarkdownImg(props: Readonly<{
  src?: string | null;
  alt?: string | null;
  longDataImageBlobRefMap: Map<string, string>;
  studioAiInlineImageUrls?: Record<string, string>;
}>) {
  const { src, alt, longDataImageBlobRefMap, studioAiInlineImageUrls } = props;
  const s = src?.trim() ?? '';
  if (s.startsWith('studio-ai-blob-ref://')) {
    const id = s.slice('studio-ai-blob-ref://'.length);
    const actual = longDataImageBlobRefMap.get(id);
    if (actual) {
      return <StudioDraggableImage src={actual} alt={alt} />;
    }
    return (
      <Typography component="span" variant="caption" color="text.secondary" sx={{ display: 'block', my: 0.5 }}>
        {(alt || '').toString().trim() ? `${(alt || '').toString().trim()} — ` : ''}
        Image preview unavailable in this message view.
      </Typography>
    );
  }
  // Unresolved studio-ai-inline-image refs: resolve from tool-progress metadata when present (see writeToolProgressSse).
  if (assistantMarkdownUnresolvedStudioInlineImageRef(s)) {
    const marker = 'studio-ai-inline-image://';
    const low = s.trim().toLowerCase();
    const idx = low.indexOf(marker);
    const id = idx >= 0 ? s.trim().slice(idx + marker.length).trim() : '';
    const resolved = id && studioAiInlineImageUrls ? studioAiInlineImageUrls[id] : undefined;
    if (resolved && resolved.length > 12) {
      return <StudioDraggableImage src={resolved} alt={alt} />;
    }
    return (
      <Typography component="span" variant="caption" color="text.secondary" sx={{ display: 'block', my: 0.5 }}>
        {(alt || '').toString().trim() ? `${(alt || '').toString().trim()} — ` : ''}
        Image preview is still loading or was not received from Studio (try again or check Studio logs).
      </Typography>
    );
  }
  return <StudioDraggableImage src={s} alt={alt} />;
}

/**
 * Default hast-util-sanitize schema only allows {@code http(s)} on {@code src}, which strips
 * inline {@code data:image/...} from assistant markdown (e.g. {@code GenerateImage} previews).
 */
function studioAiChatMarkdownSanitizeSchema() {
  const srcProtocols = [
    ...(defaultSchema.protocols?.src ?? []),
    'data',
    'blob',
    'studio-ai-inline-image',
    'studio-ai-blob-ref'
  ];
  return {
    ...defaultSchema,
    protocols: {
      ...defaultSchema.protocols,
      src: [...new Set(srcProtocols)]
    }
  };
}

function fencedBlockSanitizeSchema() {
  const base = studioAiChatMarkdownSanitizeSchema();
  return {
    ...base,
    attributes: {
      ...base.attributes,
      code: [...(base.attributes?.code || []), ['className']]
    }
  };
}

/** Rendered markdown inside a fenced markdown code block; nested fenced code renders as pre/code only (no CodeBlock recursion). */
function DraftMarkdownPreview(props: Readonly<{ value: string; studioAiInlineImageUrls?: Record<string, string> }>) {
  const theme = useTheme();
  const sanitizeSchema = useMemo(() => fencedBlockSanitizeSchema(), []);
  const { value, studioAiInlineImageUrls } = props;
  const { md, longDataImageBlobRefMap } = useMemo(() => {
    const { displayText, longDataImageBlobRefMap: map } = preprocessAssistantMarkdownImages(value);
    return { md: displayText, longDataImageBlobRefMap: map };
  }, [value]);

  const mdComponents = useMemo(
    () => ({
      h1: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant="h6" sx={{ fontWeight: 700, mt: 0.5, mb: 0.5 }}>
          {children}
        </Typography>
      ),
      h2: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant="subtitle1" sx={{ fontWeight: 700, mt: 1, mb: 0.5 }}>
          {children}
        </Typography>
      ),
      h3: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant="subtitle2" sx={{ fontWeight: 700, mt: 0.75, mb: 0.35 }}>
          {children}
        </Typography>
      ),
      p: ({ children }: { children?: React.ReactNode }) => (
        <AssistantMarkdownParagraph
          longDataImageBlobRefMap={longDataImageBlobRefMap}
          studioAiInlineImageUrls={studioAiInlineImageUrls}
        >
          {children}
        </AssistantMarkdownParagraph>
      ),
      ul: ({ children }: { children?: React.ReactNode }) => (
        <Box component="ul" sx={{ m: 0, pl: 2.2, mb: 0.75 }}>
          {children}
        </Box>
      ),
      ol: ({ children }: { children?: React.ReactNode }) => (
        <Box component="ol" sx={{ m: 0, pl: 2.2, mb: 0.75 }}>
          {children}
        </Box>
      ),
      li: ({ children }: { children?: React.ReactNode }) => (
        <Box component="li" sx={{ mb: 0.25, whiteSpace: 'normal' }}>
          {children}
        </Box>
      ),
      strong: ({ children }: { children?: React.ReactNode }) => (
        <Box component="strong" sx={{ fontWeight: 700 }}>
          {children}
        </Box>
      ),
      a: ({ href, children }: { href?: string; children?: React.ReactNode }) => (
        <Box component="a" href={href} target="_blank" rel="noreferrer" sx={{ color: theme.palette.primary.main }}>
          {children}
        </Box>
      ),
      img: ({ src, alt }: { src?: string | null; alt?: string | null }) => (
        <AssistantMarkdownImg
          src={src}
          alt={alt}
          longDataImageBlobRefMap={longDataImageBlobRefMap}
          studioAiInlineImageUrls={studioAiInlineImageUrls}
        />
      ),
      code: ({ className, children }: { className?: string; children?: React.ReactNode }) => {
        const raw = String(children ?? '').replace(/\n$/, '');
        const isInline = !className;
        if (isInline) {
          return (
            <Box
              component="code"
              sx={{
                px: 0.35,
                fontFamily:
                  'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
                fontSize: '0.8125rem',
                bgcolor: theme.palette.mode === 'dark' ? 'grey.800' : 'grey.200',
                borderRadius: 0.5
              }}
            >
              {raw}
            </Box>
          );
        }
        return (
          <Box
            component="pre"
            sx={{
              m: 0,
              my: 1,
              p: 1,
              overflow: 'auto',
              borderRadius: 1,
              fontSize: 12.5,
              bgcolor: theme.palette.mode === 'dark' ? 'grey.950' : 'grey.100',
              border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[300]}`
            }}
          >
            <code>{raw}</code>
          </Box>
        );
      }
    }),
    [theme, longDataImageBlobRefMap, studioAiInlineImageUrls]
  );

  return (
    <Box
      sx={{
        maxHeight: 440,
        overflow: 'auto',
        p: 1.25,
        bgcolor: theme.palette.mode === 'dark' ? 'grey.900' : 'grey.50',
        borderRadius: 1,
        border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`
      }}
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[[rehypeSanitize, sanitizeSchema]]} components={mdComponents}>
        {md}
      </ReactMarkdown>
    </Box>
  );
}

async function copyToClipboard(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // Fallback for contexts where Clipboard API is unavailable
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

/**
 * Models sometimes fence assistant prose as {@code ```text} even when it contains markdown (e.g. {@code ![alt](studio-ai-inline-image://…)}).
 * Without this, the chat shows a raw code block and images never resolve.
 */
function fencedCodeValueLooksLikeAssistantMarkdown(value: string): boolean {
  const v = value.trim();
  if (!v) return false;
  if (v.includes('studio-ai-inline-image:')) return true;
  if (/!\[[^\]]*\]\([^)]+\)/.test(v)) return true;
  if (/^#{1,6}\s/m.test(v)) return true;
  return false;
}

/** Short prose-only fenced blocks (e.g. image-only wrap-up) skip the heavy “Draft preview” chrome — render markdown inline. */
function compactProseMarkdownFence(value: string): boolean {
  const v = value.trim();
  if (!v || v.length > 8000) return false;
  if (v.includes('```')) return false;
  if (/(\n|^)#{1,6}\s/.test(v)) return false;
  if (v.includes('📋')) return false;
  if (/\n##\s*Plan\b/i.test(v) || /^##\s*Plan\b/i.test(v)) return false;
  return true;
}

function CodeBlock(props: {
  language?: string;
  value: string;
  /** When the fenced block is rendered as markdown preview, resolve {@code studio-ai-inline-image://} refs. */
  studioAiInlineImageUrls?: Record<string, string>;
}) {
  const theme = useTheme();
  const { language, value, studioAiInlineImageUrls } = props;
  const lang = (language || '').toLowerCase();
  const isHtml = lang === 'html' || lang === 'htm';
  const isMarkdownDraft = lang === 'markdown' || lang === 'md';
  const isTextFencedAsMarkdown =
    (lang === 'text' || lang === 'txt') && fencedCodeValueLooksLikeAssistantMarkdown(value);
  const renderMarkdownFencedBlock = isMarkdownDraft || isTextFencedAsMarkdown;
  const [htmlMode, setHtmlMode] = useState<'preview' | 'source'>('preview');
  const [mdMode, setMdMode] = useState<'preview' | 'source'>('preview');

  const header = (
    <Stack
      direction="row"
      alignItems="center"
      justifyContent="space-between"
      sx={{
        px: 1,
        py: 0.5,
        borderBottom: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`
      }}
    >
      <Typography variant="caption" sx={{ fontWeight: 600, opacity: 0.8 }}>
        {lang || 'code'}
      </Typography>
      <Stack direction="row" spacing={0.5} alignItems="center">
        <Tooltip title="Copy">
          <IconButton size="small" onClick={() => void copyToClipboard(value)} aria-label="Copy code">
            <ContentCopyRounded fontSize="inherit" />
          </IconButton>
        </Tooltip>
      </Stack>
    </Stack>
  );

  return (
    <Paper
      elevation={0}
      sx={{
        my: 1,
        overflow: 'hidden',
        borderRadius: 1.5,
        border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`,
        background: theme.palette.mode === 'dark' ? theme.palette.grey[950] : theme.palette.grey[50]
      }}
    >
      {isHtml ? (
        <>
          <Box sx={{ px: 1.5, pt: 1.5, pb: 0.75 }}>
            <Typography variant="subtitle1" component="div" sx={{ fontWeight: 700, lineHeight: 1.35 }}>
              Content preview
            </Typography>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5, display: 'block', lineHeight: 1.4 }}>
              The tinted box below is only this assistant HTML snippet in the chat. It is not your live site or Studio
              preview.
            </Typography>
          </Box>
          <Box
            sx={{
              mx: 1.5,
              mb: 1.5,
              pl: 1.25,
              pr: 0.5,
              py: 0.75,
              borderRadius: 1,
              borderLeft: `4px solid ${theme.palette.primary.main}`,
              background:
                theme.palette.mode === 'dark'
                  ? 'rgba(144, 202, 249, 0.09)'
                  : 'rgba(25, 118, 210, 0.07)',
              boxShadow: `inset 0 0 0 1px ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[300]}`
            }}
          >
            <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ pr: 0.5 }}>
              {header}
            </Stack>
            <Tabs
              value={htmlMode}
              onChange={(_, v) => setHtmlMode(v)}
              sx={{
                minHeight: 34,
                px: 1,
                borderBottom: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`
              }}
            >
              <Tab value="source" label="Source" sx={{ minHeight: 34, py: 0.5 }} />
              <Tab value="preview" label="Preview" sx={{ minHeight: 34, py: 0.5 }} />
            </Tabs>
            {htmlMode === 'preview' ? (
              <Box sx={{ p: 1 }}>
                {/*
                  Intentionally empty sandbox: no allow-scripts / allow-same-origin — assistant HTML is untrusted;
                  this is an isolated document preview only (not live site or Studio preview).
                */}
                <Box
                  component="iframe"
                  sandbox=""
                  sx={{
                    width: '100%',
                    height: 260,
                    border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`,
                    borderRadius: 1,
                    background: '#fff'
                  }}
                  srcDoc={value}
                  title="HTML preview"
                />
              </Box>
            ) : (
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1.25,
                  overflow: 'auto',
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
                  fontSize: 12.5,
                  lineHeight: 1.5
                }}
              >
                <code>{value}</code>
              </Box>
            )}
          </Box>
        </>
      ) : renderMarkdownFencedBlock && compactProseMarkdownFence(value) ? (
        <Box sx={{ px: 0.5, py: 0.25 }}>
          <DraftMarkdownPreview value={value} studioAiInlineImageUrls={studioAiInlineImageUrls} />
        </Box>
      ) : renderMarkdownFencedBlock ? (
        <>
          <Box sx={{ px: 1.5, pt: 1.5, pb: 0.75 }}>
            <Typography variant="subtitle1" component="div" sx={{ fontWeight: 700, lineHeight: 1.35 }}>
              Draft preview
            </Typography>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5, display: 'block', lineHeight: 1.4 }}>
              This tinted area is the assistant draft in chat only. Nothing is saved in the CMS until WriteContent succeeds
              on a repository path.
            </Typography>
          </Box>
          <Box
            sx={{
              mx: 1.5,
              mb: 1.5,
              pl: 1.25,
              pr: 0.5,
              py: 0.75,
              borderRadius: 1,
              borderLeft: `4px solid ${theme.palette.secondary.main}`,
              background:
                theme.palette.mode === 'dark'
                  ? 'rgba(186, 104, 200, 0.1)'
                  : 'rgba(123, 31, 162, 0.06)',
              boxShadow: `inset 0 0 0 1px ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[300]}`
            }}
          >
            <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ pr: 0.5 }}>
              {header}
            </Stack>
            <Tabs
              value={mdMode}
              onChange={(_, v) => setMdMode(v)}
              sx={{
                minHeight: 34,
                px: 1,
                borderBottom: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`
              }}
            >
              <Tab value="source" label="Source" sx={{ minHeight: 34, py: 0.5 }} />
              <Tab value="preview" label="Preview" sx={{ minHeight: 34, py: 0.5 }} />
            </Tabs>
            {mdMode === 'preview' ? (
              <Box sx={{ p: 1 }}>
                <DraftMarkdownPreview value={value} studioAiInlineImageUrls={studioAiInlineImageUrls} />
              </Box>
            ) : (
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1.25,
                  overflow: 'auto',
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
                  fontSize: 12.5,
                  lineHeight: 1.5
                }}
              >
                <code>{value}</code>
              </Box>
            )}
          </Box>
        </>
      ) : (
        <>
          {header}
          <Box
            component="pre"
            sx={{
              m: 0,
              p: 1.25,
              overflow: 'auto',
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
              fontSize: 12.5,
              lineHeight: 1.5
            }}
          >
            <code>{value}</code>
          </Box>
        </>
      )}
    </Paper>
  );
}

export default function MarkdownMessage(props: Readonly<{ text: string; studioAiInlineImageUrls?: Record<string, string> }>) {
  const theme = useTheme();
  const { text, studioAiInlineImageUrls } = props;

  const { displayText, longDataImageBlobRefMap } = useMemo(() => preprocessAssistantMarkdownImages(text), [text]);

  const sanitizeSchema = useMemo(() => {
    const base = studioAiChatMarkdownSanitizeSchema();
    return {
      ...base,
      attributes: {
        ...base.attributes,
        code: [...(base.attributes?.code || []), ['className']]
      }
    };
  }, []);

  const markdownComponents = useMemo(
    () => ({
      h1: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant="h6" sx={{ fontWeight: 700, mt: 1, mb: 0.5 }}>
          {children}
        </Typography>
      ),
      h2: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant="subtitle1" sx={{ fontWeight: 700, mt: 1, mb: 0.5 }}>
          {children}
        </Typography>
      ),
      h3: ({ children }: { children?: React.ReactNode }) => (
        <Typography variant="subtitle2" sx={{ fontWeight: 700, mt: 1, mb: 0.5 }}>
          {children}
        </Typography>
      ),
      p: ({ children }: { children?: React.ReactNode }) => (
        <AssistantMarkdownParagraph longDataImageBlobRefMap={longDataImageBlobRefMap} studioAiInlineImageUrls={studioAiInlineImageUrls}>
          {children}
        </AssistantMarkdownParagraph>
      ),
      ul: ({ children }: { children?: React.ReactNode }) => (
        <Box component="ul" sx={{ m: 0, pl: 2.2, mb: 0.75 }}>
          {children}
        </Box>
      ),
      ol: ({ children }: { children?: React.ReactNode }) => (
        <Box component="ol" sx={{ m: 0, pl: 2.2, mb: 0.75 }}>
          {children}
        </Box>
      ),
      li: ({ children }: { children?: React.ReactNode }) => (
        <Box
          component="li"
          sx={{
            mb: 0.25,
            whiteSpace: 'normal',
            '& > p': {
              display: 'inline',
              m: 0
            },
            '& > p + p': {
              display: 'block',
              mt: 0.5
            }
          }}
        >
          {children}
        </Box>
      ),
      strong: ({ children }: { children?: React.ReactNode }) => (
        <Box component="strong" sx={{ fontWeight: 700 }}>
          {children}
        </Box>
      ),
      em: ({ children }: { children?: React.ReactNode }) => (
        <Box component="em" sx={{ fontStyle: 'italic' }}>
          {children}
        </Box>
      ),
      a: ({ href, children }: { href?: string; children?: React.ReactNode }) => (
        <Box component="a" href={href} target="_blank" rel="noreferrer" sx={{ color: theme.palette.primary.main }}>
          {children}
        </Box>
      ),
      img: ({ src, alt }: { src?: string | null; alt?: string | null }) => (
        <AssistantMarkdownImg
          src={src}
          alt={alt}
          longDataImageBlobRefMap={longDataImageBlobRefMap}
          studioAiInlineImageUrls={studioAiInlineImageUrls}
        />
      ),
      table: ({ children }: { children?: React.ReactNode }) => (
        <TableContainer
          component={Paper}
          elevation={0}
          sx={{
            my: 1.5,
            overflow: 'auto',
            borderRadius: 1.5,
            border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`,
            background: theme.palette.mode === 'dark' ? theme.palette.grey[900] : theme.palette.grey[50]
          }}
        >
          <Table size="small" stickyHeader sx={{ minWidth: 280 }}>
            {children}
          </Table>
        </TableContainer>
      ),
      thead: ({ children }: { children?: React.ReactNode }) => <TableHead>{children}</TableHead>,
      tbody: ({ children }: { children?: React.ReactNode }) => <TableBody>{children}</TableBody>,
      tr: ({ children }: { children?: React.ReactNode }) => (
        <TableRow
          sx={{
            '&:last-child td, &:last-child th': { borderBottom: 0 }
          }}
        >
          {children}
        </TableRow>
      ),
      th: ({ children }: { children?: React.ReactNode }) => (
        <TableCell
          component="th"
          scope="col"
          sx={{
            fontWeight: 700,
            py: 1,
            px: 1.5,
            borderBottom: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[700] : theme.palette.grey[300]}`,
            background: theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[100],
            color: theme.palette.text.primary,
            fontSize: '0.8125rem'
          }}
        >
          {children}
        </TableCell>
      ),
      td: ({ children }: { children?: React.ReactNode }) => (
        <TableCell
          sx={{
            py: 1,
            px: 1.5,
            borderBottom: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`,
            fontSize: '0.8125rem'
          }}
        >
          {children}
        </TableCell>
      ),
      code: ({ className, children }: { className?: string; children?: React.ReactNode }) => {
        const raw = String(children ?? '');
        const isInline = !className;
        if (isInline) {
          return (
            <Box
              component="code"
              sx={{
                px: 0.5,
                py: 0.1,
                borderRadius: 0.75,
                fontFamily:
                  'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
                fontSize: 12.5,
                background: theme.palette.mode === 'dark' ? theme.palette.grey[900] : theme.palette.grey[100],
                border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[200]}`
              }}
            >
              {raw}
            </Box>
          );
        }

        const match = /language-(\w+)/.exec(className || '');
        const language = match?.[1];
        return <CodeBlock language={language} value={raw.replace(/\n$/, '')} studioAiInlineImageUrls={studioAiInlineImageUrls} />;
      }
    }),
    [theme, longDataImageBlobRefMap, studioAiInlineImageUrls]
  );

  return (
    <Box
      sx={{
        '& :first-of-type': { mt: 0 },
        '& :last-of-type': { mb: 0 }
      }}
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[[rehypeSanitize, sanitizeSchema]]} components={markdownComponents}>
        {displayText}
      </ReactMarkdown>
    </Box>
  );
}

