/**
 * Formats the in-memory session stream capture for debugging (Copy session log).
 * Part A = parsed timeline (intent / phases / errors); Part B = verbatim redacted lines.
 */

const TEXT_PREVIEW_CHARS = 320;

/** Best-effort redaction before copying the raw SSE debug log to the clipboard. */
export function redactSessionLogLineForCopy(s: string): string {
  return s
    .replace(/("?(authorization|bearer|token|previewToken)"?\s*:\s*)"[^"]+"/gi, '$1"***"')
    .replace(/("?(?:\w*[Bb]earer\w*|[Tt]oken\w*|previewToken)"?\s*:\s*)"[^"]+"/g, '$1"***"');
}

function previewText(s: string, max = TEXT_PREVIEW_CHARS): string {
  const t = (s || '').trim();
  if (!t) return '(empty)';
  if (t.length <= max) return t;
  return `${t.slice(0, max)}… [+${t.length - max} chars — see VERBATIM]`;
}

type ParsedLine =
  | { kind: 'iso_sse'; iso: string; json: Record<string, unknown> | null; rawLine: string }
  | { kind: 'client_json'; json: Record<string, unknown>; rawLine: string }
  | { kind: 'unknown'; rawLine: string };

function parseLogLine(line: string): ParsedLine {
  /** Raw SSE rows are logged as `{ISO}\\t{json}` from the chat stream hook. */
  const isoRow = /^(\d{4}-\d{2}-\d{2}T[^\t]+)\t([\s\S]+)$/.exec(line);
  if (isoRow) {
    const iso = isoRow[1];
    const rest = isoRow[2].trim();
    try {
      const json = JSON.parse(rest) as Record<string, unknown>;
      return { kind: 'iso_sse', iso, json, rawLine: line };
    } catch {
      return { kind: 'iso_sse', iso, json: null, rawLine: line };
    }
  }
  try {
    const json = JSON.parse(line) as Record<string, unknown>;
    return { kind: 'client_json', json, rawLine: line };
  } catch {
    return { kind: 'unknown', rawLine: line };
  }
}

function buildParsedTimeline(lines: string[]): string {
  const out: string[] = [];
  let turn = 0;
  let pendingAssistantChars = 0;
  let streamIdsLoggedForTurn = false;

  const flushDeltas = () => {
    if (pendingAssistantChars > 0) {
      out.push(
        `  • Assistant model text (aggregated deltas): +${pendingAssistantChars} chars — fragments in VERBATIM`
      );
      pendingAssistantChars = 0;
    }
  };

  for (const line of lines) {
    const parsed = parseLogLine(line);

    if (parsed.kind === 'unknown') {
      flushDeltas();
      out.push(`  • Non-JSON line: ${previewText(parsed.rawLine, 160)}`);
      continue;
    }

    if (parsed.kind === 'client_json') {
      const o = parsed.json;
      const kind = typeof o.kind === 'string' ? o.kind : '';

      if (kind === 'client.sessionReset') {
        flushDeltas();
        out.push('');
        out.push(
          `── Session capture cleared @ ${typeof o.ts === 'string' ? o.ts : '?'} (site=${String(o.siteId ?? '')} agent=${String(o.agentId ?? '')})`
        );
        continue;
      }

      if (kind === 'client.userSend') {
        flushDeltas();
        turn++;
        streamIdsLoggedForTurn = false;
        out.push('');
        out.push(`── Turn ${turn} — CLIENT → SERVER @ ${typeof o.ts === 'string' ? o.ts : '?'}`);
        const ctx = o.context && typeof o.context === 'object' ? (o.context as Record<string, unknown>) : null;
        if (ctx) {
          const bits: string[] = [];
          const keys = [
            'siteId',
            'agentId',
            'llm',
            'llmModel',
            'imageModel',
            'imageGenerator',
            'authoringSurface',
            'omitTools',
            'enableTools',
            'chatId',
            'contentPath',
            'contentTypeId',
            'studioPreviewPageUrl'
          ];
          for (const k of keys) {
            const v = ctx[k];
            if (v != null && String(v).trim() !== '') bits.push(`${k}=${String(v)}`);
          }
          if (bits.length) out.push(`  Request context: ${bits.join(' | ')}`);
        }
        const disp = typeof o.displayText === 'string' ? o.displayText : '';
        const wire = typeof o.wirePrompt === 'string' ? o.wirePrompt : '';
        out.push(`  Bubble text (${disp.length} chars): ${previewText(disp)}`);
        out.push(`  Wire prompt (${wire.length} chars): ${previewText(wire)}`);
        continue;
      }

      if (kind === 'client.streamOutcome') {
        flushDeltas();
        const oc = typeof o.outcome === 'string' ? o.outcome : 'unknown';
        const msg = typeof o.message === 'string' ? o.message : '';
        const et = typeof o.errorType === 'string' ? o.errorType : '';
        out.push(
          `  ◆ CLIENT OUTCOME @ ${typeof o.ts === 'string' ? o.ts : '?'}: ${oc}${et ? ` (${et})` : ''}${msg ? ` — ${previewText(msg, 480)}` : ''}`
        );
        continue;
      }

      flushDeltas();
      out.push(`  • Client event kind=${kind || '(missing)'}`);
      continue;
    }

    // iso_sse
    if (!parsed.json) {
      flushDeltas();
      out.push(`  • SSE @ ${parsed.iso} [payload not JSON]`);
      continue;
    }

    const e = parsed.json;
    const meta = e.metadata && typeof e.metadata === 'object' ? (e.metadata as Record<string, unknown>) : {};
    const text = typeof e.text === 'string' ? e.text : '';

    const terminal = meta.completed === true || meta.error === true;
    const status = meta.status != null ? String(meta.status) : '';
    const phase = meta.phase != null ? String(meta.phase) : '';

      const phaseInteresting =
        status === 'aiassistant-chat-phase' &&
      phase === 'summarizing-results';

    const interesting =
      terminal ||
      meta.planGateFailure === true ||
      status === 'tool-progress' ||
      status === 'tool-workflow-hint' ||
      status === 'pipeline-heartbeat' ||
      phaseInteresting;

    if (!streamIdsLoggedForTurn && (meta.chatId || meta.messageId)) {
      flushDeltas();
      streamIdsLoggedForTurn = true;
      out.push(
        `  • Stream ids (@ ${parsed.iso}): chatId=${meta.chatId ?? '—'} | messageId=${meta.messageId ?? '—'}`
      );
    }

    if (interesting) {
      flushDeltas();
      const bullets: string[] = [];
      if (meta.completed === true) bullets.push('Terminal: completed=true (normal end of SSE)');
      if (meta.error === true)
        bullets.push(`Terminal: error=true — ${previewText(String(meta.message ?? '(no message)'), 240)}`);
      if (meta.planGateFailure === true) bullets.push('planGateFailure=true — UI may replace assistant output');
      if (status === 'pipeline-heartbeat') {
        bullets.push(
          `pipeline-heartbeat: elapsedSec=${meta.elapsedSec ?? '?'} nextInSec=${meta.nextInSec ?? '?'} hint=${previewText(String(meta.hint ?? ''), 180)}`
        );
      }
      if (status === 'tool-progress' || status === 'tool-workflow-hint') {
        bullets.push(`Tool strip: status=${status} phase=${phase || '—'} tool=${meta.tool ?? '—'}`);
        const oneLine = text.replace(/\s+/g, ' ').trim();
        if (oneLine) bullets.push(`  strip preview: ${previewText(oneLine, 220)}`);
      }
      if (phaseInteresting) {
        bullets.push(
          'Phase: summarizing-results — orchestration summarizing tool results into final assistant markdown'
        );
      }
      out.push(`  • SSE @ ${parsed.iso}`);
      for (const b of bullets) out.push(`      → ${b}`);
      continue;
    }

    if (text.length) {
      pendingAssistantChars += text.length;
    }
  }

  flushDeltas();
  const body = out.join('\n').trim();
  return body || '(Timeline empty — no recognizable events.)';
}

export function formatSessionLogForDebugCopy(lines: string[]): string {
  const generatedAt = new Date().toISOString();
  const timeline = buildParsedTimeline(lines);
  const verbatim = lines.map(redactSessionLogLineForCopy).join('\n');

  return [
    '==============================================================================',
    'AI ASSISTANT — SESSION DEBUG LOG (for maintainers)',
    `Generated (copy time): ${generatedAt}`,
    '',
    'How to read:',
    '  • TIMELINE — what happened in order (phases, tools, terminal frames, client outcomes).',
    '  • VERBATIM — exact captured lines (JSON); secrets redacted; use for grep / repro.',
    '',
    '--- TIMELINE ---',
    timeline,
    '',
    '--- VERBATIM (redacted, chronological) ---',
    verbatim || '(empty)',
    ''
  ].join('\n');
}
