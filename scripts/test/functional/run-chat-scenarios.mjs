#!/usr/bin/env node
/**
 * Plugin **functionality** smoke: scripted user prompts against
 * POST /studio/api/2/plugin/script/.../ai/stream (SSE), same contract as sources/src/aiAssistantApi.ts streamChat.
 *
 * LLM backend (OpenAI, proxy, mock gateway, etc.) is whatever Studio/JVM is configured for — this script only drives HTTP.
 *
 * Requires: Node 18+, live Studio, CRAFTER_STUDIO_TOKEN.
 * **agentId:** `CHAT_AGENT_ID`, scenario `defaults.agentId`, first `<crafterQAgentId>` under `<agents>` in site **ui.xml**
 * (via `get_configuration`), else the default UUID matching `AI_ASSISTANT_DEFAULT_AGENT_ID` in `sources/src/agentConfig.ts`.
 *
 * Does not assert exact LLM wording; asserts HTTP 200 and stream completion (`metadata.completed`), or fails on
 * `metadata.error` / incomplete stream.
 *
 * Usage (repo root):
 *   export CRAFTER_STUDIO_TOKEN='…'
 *   node scripts/test/functional/run-chat-scenarios.mjs [path/to/scenarios.json]
 *
 * Invoked by `./scripts/test/run-all.sh` step 4 when Studio is live (opt out: `RUN_ALL_SKIP_CHAT_SCENARIOS=1`).
 *
 * Env:
 *   CHAT_SITE_ID          Override defaults.siteId
 *   CHAT_AGENT_ID         Force agent id (optional if discoverable from ui.xml or default UUID works)
 *   CHAT_PREVIEW_TOKEN    crafterPreview cookie (recommended for translate / GetPreviewHtml tools)
 *   CHAT_TURN_TIMEOUT_MS  Per-turn wall clock (default 180000)
 */

import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

/** Same value as {@link AI_ASSISTANT_DEFAULT_AGENT_ID} in sources/src/agentConfig.ts */
const DEFAULT_AGENT_ID = '019c7237-478b-7f98-9a5c-87144c3fb010';

function usage(code = 0) {
  const msg = `run-chat-scenarios.mjs — SSE chat turns against Studio ai/stream

Usage:
  node scripts/test/functional/run-chat-scenarios.mjs [path/to/scenarios.json]

Env (required unless in JSON defaults):
  CRAFTER_STUDIO_URL     Base URL (default http://localhost:8080)
  CRAFTER_STUDIO_TOKEN   Bearer JWT

Agent id (first match wins):
  CHAT_AGENT_ID          Optional explicit id
  (else defaults.agentId in the scenario JSON if set and not a placeholder)
  (else first <crafterQAgentId> under <agents> in site ui.xml from get_configuration)
  (else default UUID — same as AI_ASSISTANT_DEFAULT_AGENT_ID in agentConfig.ts)

Optional:
  CHAT_SITE_ID           Site id (else scenarios.defaults.siteId)
  CHAT_PREVIEW_TOKEN     Preview cookie for tool calls
  CHAT_TURN_TIMEOUT_MS   Ms per turn (default 180000)
`;
  if (code) console.error(msg);
  else console.log(msg);
  process.exit(code);
}

function feedSseBuffer(buffer, chunk, t0, state) {
  buffer = (buffer + chunk).replace(/\r\n/g, '\n');
  while (true) {
    const sep = buffer.indexOf('\n\n');
    if (sep === -1) break;
    const block = buffer.slice(0, sep);
    buffer = buffer.slice(sep + 2);
    for (const line of block.split('\n')) {
      const t = line.trim();
      if (!t.startsWith('data:')) continue;
      const payload = t.slice(5).trim();
      if (!payload || payload === '[DONE]') continue;
      try {
        const ev = JSON.parse(payload);
        state.events.push(ev);
        if (state.firstTokenMs == null && t0 != null) state.firstTokenMs = Date.now() - t0;
        const meta = ev?.metadata;
        if (meta?.error) {
          state.err = true;
          state.errMsg = String(meta.message || meta.detail || 'metadata.error');
        }
        if (meta?.completed) state.completed = true;
      } catch {
        // ignore
      }
    }
  }
  return buffer;
}

/** Extract configuration XML/text from Studio get_configuration JSON (shapes vary by version). */
function configurationXmlFromGetConfigurationBody(j) {
  if (!j || typeof j !== 'object') return null;
  const code = j.response?.code;
  if (code !== undefined && code !== null && Number(code) !== 0) return null;
  const r = j.result;
  if (typeof r === 'string') return r;
  if (r && typeof r === 'object') {
    if (typeof r.content === 'string') return r.content;
    if (typeof r.xml === 'string') return r.xml;
    if (typeof r.configuration === 'string') return r.configuration;
  }
  if (typeof j.content === 'string') return j.content;
  return null;
}

/** First non-empty crafterQAgentId inside <agents>…</agents>, else first in file. */
function firstCrafterQAgentIdFromUiXml(xml) {
  if (!xml || typeof xml !== 'string') return null;
  const agentsBlock = xml.match(/<agents\b[^>]*>([\s\S]*?)<\/agents>/i);
  const scope = agentsBlock ? agentsBlock[1] : xml;
  const re = /<(?:[\w.-]+:)?crafterQAgentId\b[^>]*>\s*([^<]*?)\s*<\/(?:[\w.-]+:)?crafterQAgentId>/gi;
  let m;
  while ((m = re.exec(scope)) !== null) {
    const id = m[1].replace(/\s+/g, ' ').trim();
    if (id && !/^placeholder$/i.test(id)) return id;
  }
  return null;
}

async function fetchFirstAgentIdFromUiXml(baseUrl, siteId, token) {
  const q = new URLSearchParams({
    siteId: String(siteId),
    module: 'studio',
    path: 'ui.xml',
  });
  const url = `${baseUrl.replace(/\/$/, '')}/studio/api/2/configuration/get_configuration?${q}`;
  const res = await fetch(url, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
    },
  });
  const text = await res.text();
  if (!res.ok) {
    console.error(`get_configuration ui.xml: HTTP ${res.status} ${text.slice(0, 400)}`);
    return null;
  }
  let j;
  try {
    j = JSON.parse(text);
  } catch {
    console.error('get_configuration ui.xml: response was not JSON');
    return null;
  }
  const xml = configurationXmlFromGetConfigurationBody(j);
  if (!xml || typeof xml !== 'string') {
    console.error('get_configuration ui.xml: could not find XML content in JSON envelope');
    return null;
  }
  return firstCrafterQAgentIdFromUiXml(xml);
}

async function resolveAgentId({ baseUrl, siteId, token, defaults }) {
  const fromEnv = (process.env.CHAT_AGENT_ID || '').trim();
  if (fromEnv) return fromEnv;
  const fromJson = String(defaults.agentId || '').trim();
  if (fromJson && !fromJson.includes('REPLACE')) return fromJson;
  const fromXml = await fetchFirstAgentIdFromUiXml(baseUrl, siteId, token);
  if (fromXml) {
    console.log(`Resolved agentId from site ui.xml: ${fromXml}`);
    return fromXml;
  }
  console.log(
    `No crafterQAgentId in ui.xml; using default agent id (${DEFAULT_AGENT_ID}) — same as AI_ASSISTANT_DEFAULT_AGENT_ID in agentConfig.ts.`,
  );
  return DEFAULT_AGENT_ID;
}

async function runTurn({ baseUrl, siteId, token, previewToken, body, timeoutMs }) {
  const enc = encodeURIComponent(siteId);
  const url = `${baseUrl.replace(/\/$/, '')}/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=${enc}`;
  const merged = { ...body };
  if (previewToken && String(previewToken).trim()) merged.previewToken = String(previewToken).trim();

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  const t0 = Date.now();
  let res;
  try {
    res = await fetch(url, {
      method: 'POST',
      signal: ctrl.signal,
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(merged),
    });
  } catch (e) {
    clearTimeout(timer);
    const msg = e instanceof Error ? e.message : String(e);
    return { ok: false, httpStatus: 0, reason: `Fetch failed: ${msg}`, ms: Date.now() - t0 };
  }

  if (!res.ok) {
    clearTimeout(timer);
    const text = await res.text();
    return {
      ok: false,
      httpStatus: res.status,
      reason: `HTTP ${res.status}: ${text.slice(0, 600)}`,
      ms: Date.now() - t0,
    };
  }

  if (!res.body) {
    clearTimeout(timer);
    return { ok: false, httpStatus: res.status, reason: 'No response body (streaming not supported)', ms: Date.now() - t0 };
  }

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  const state = { events: [], completed: false, err: false, errMsg: '', firstTokenMs: null };
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf = feedSseBuffer(buf, dec.decode(value, { stream: true }), t0, state);
      if (state.err || state.completed) {
        await reader.cancel();
        break;
      }
    }
    buf = feedSseBuffer(buf, dec.decode(), t0, state);
  } finally {
    clearTimeout(timer);
  }

  if (state.err) {
    return { ok: false, httpStatus: res.status, reason: `Stream error: ${state.errMsg}`, ms: Date.now() - t0, firstTokenMs: state.firstTokenMs };
  }
  if (!state.completed) {
    return {
      ok: false,
      httpStatus: res.status,
      reason: 'Stream ended without metadata.completed',
      ms: Date.now() - t0,
      firstTokenMs: state.firstTokenMs,
    };
  }
  return {
    ok: true,
    httpStatus: res.status,
    ms: Date.now() - t0,
    firstTokenMs: state.firstTokenMs,
    eventCount: state.events.length,
  };
}

async function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.includes('-h')) usage(0);

  const scenarioPath =
    argv.find((a) => !a.startsWith('-')) ||
    new URL('../scenarios/chat-scenarios.example.json', import.meta.url).pathname;

  const raw = readFileSync(scenarioPath, 'utf8');
  const doc = JSON.parse(raw);
  const defaults = doc.defaults || {};
  const turns = doc.turns || [];

  const baseUrl = process.env.CRAFTER_STUDIO_URL || 'http://localhost:8080';
  const token = process.env.CRAFTER_STUDIO_TOKEN || '';
  const siteId = process.env.CHAT_SITE_ID || defaults.siteId || '';
  const previewToken = process.env.CHAT_PREVIEW_TOKEN || '';
  const timeoutMs = Number(process.env.CHAT_TURN_TIMEOUT_MS || '180000') || 180000;

  if (!token) {
    console.error('Missing CRAFTER_STUDIO_TOKEN');
    usage(2);
  }
  if (!siteId) {
    console.error('Set CHAT_SITE_ID or defaults.siteId in the scenario file.');
    process.exit(2);
  }

  const agentId = await resolveAgentId({ baseUrl, siteId, token, defaults });
  if (!agentId) {
    console.error('Could not resolve agent id.');
    process.exit(2);
  }

  const chatId = randomUUID();
  console.log(`Scenarios: ${scenarioPath}`);
  console.log(`Studio: ${baseUrl}  siteId=${siteId}  agentId=${agentId}  chatId=${chatId}`);
  console.log('');

  let failed = 0;
  for (const turn of turns) {
    const id = turn.id || '(no id)';
    const body = {
      ...defaults,
      ...(turn.request || {}),
      agentId,
      siteId,
      chatId,
      prompt: turn.prompt != null ? String(turn.prompt) : '',
    };
    const label = `${id}: ${turn.summary || turn.prompt?.slice(0, 60) || ''}`;
    process.stdout.write(`… ${label}\n`);
    const tStart = Date.now();
    try {
      const r = await runTurn({ baseUrl, siteId, token, previewToken, body, timeoutMs });
      const wall = Date.now() - tStart;
      if (r.ok) {
        console.log(
          `  ✅ completed  total=${r.ms}ms  first-chunk≈${r.firstTokenMs != null ? `${r.firstTokenMs}ms` : 'n/a'}  events=${r.eventCount}`,
        );
      } else {
        failed++;
        console.log(`  ❌ ${r.reason}  (wall ${wall}ms)`);
      }
    } catch (e) {
      failed++;
      console.log(`  ❌ ${e instanceof Error ? e.message : String(e)}  (wall ${Date.now() - tStart}ms)`);
    }
    console.log('');
  }

  if (failed) {
    console.error(`Done: ${failed} turn(s) failed.`);
    process.exit(1);
  }
  console.log('Done: all turns completed stream successfully.');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
