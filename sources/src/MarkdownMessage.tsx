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
export function normalizeOpenAiLiteralEscapes(input: string): string {
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
    'studio-ai-blob-ref',
    // Legacy threads / older plugin builds
    'crafterq-tool-image'
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
function DraftMarkdownPreview(props: Readonly<{ value: string }>) {
  const theme = useTheme();
  const sanitizeSchema = useMemo(() => fencedBlockSanitizeSchema(), []);
  const { value } = props;
  const { md, longDataImageBlobRefMap } = useMemo(() => {
    const map = new Map<string, string>();
    const normalized = normalizeOpenAiLiteralEscapes(value);
    const shortened = replaceLongDataImageMarkdownWithBlobRefs(normalized, map);
    const wrapped = wrapDataImageMarkdownDestInAngleBrackets(shortened);
    return { md: wrapped, longDataImageBlobRefMap: map };
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
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mb: 0.75 }}>
          {children}
        </Typography>
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
      img: ({ src, alt }: { src?: string | null; alt?: string | null }) => {
        const s = src?.trim() ?? '';
        if (s.startsWith('studio-ai-blob-ref://')) {
          const id = s.slice('studio-ai-blob-ref://'.length);
          const actual = longDataImageBlobRefMap.get(id);
          if (actual) {
            return <StudioDraggableImage src={actual} alt={alt} />;
          }
        }
        return <StudioDraggableImage src={src} alt={alt} />;
      },
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
    [theme, longDataImageBlobRefMap]
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

function CodeBlock(props: { language?: string; value: string }) {
  const theme = useTheme();
  const { language, value } = props;
  const lang = (language || '').toLowerCase();
  const isHtml = lang === 'html' || lang === 'htm';
  const isMarkdownDraft = lang === 'markdown' || lang === 'md';
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
      ) : isMarkdownDraft ? (
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
                <DraftMarkdownPreview value={value} />
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

export default function MarkdownMessage(props: Readonly<{ text: string }>) {
  const theme = useTheme();
  const { text } = props;

  const { displayText, longDataImageBlobRefMap } = useMemo(() => {
    const map = new Map<string, string>();
    const normalized = normalizeOpenAiLiteralEscapes(text);
    const shortened = replaceLongDataImageMarkdownWithBlobRefs(normalized, map);
    const wrapped = wrapDataImageMarkdownDestInAngleBrackets(shortened);
    return { displayText: wrapped, longDataImageBlobRefMap: map };
  }, [text]);

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
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mb: 0.75 }}>
          {children}
        </Typography>
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
      img: ({ src, alt }: { src?: string | null; alt?: string | null }) => {
        const s = src?.trim() ?? '';
        if (s.startsWith('studio-ai-blob-ref://')) {
          const id = s.slice('studio-ai-blob-ref://'.length);
          const actual = longDataImageBlobRefMap.get(id);
          if (actual) {
            return <StudioDraggableImage src={actual} alt={alt} />;
          }
        }
        return <StudioDraggableImage src={src} alt={alt} />;
      },
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
        return <CodeBlock language={language} value={raw.replace(/\n$/, '')} />;
      }
    }),
    [theme, longDataImageBlobRefMap]
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

