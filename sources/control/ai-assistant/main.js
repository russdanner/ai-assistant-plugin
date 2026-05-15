/*
 * Copyright (C) 2026 Crafter Software Corporation. All Rights Reserved.
 *
 * Form engine control: AI Assistant in the Content Types palette.
 * - getSupportedProperties: one boolean property per agent from ui.xml (same object shape as built-in datasource
 *   booleans, e.g. Components datasource allowShared / allowEmbedded — see aiassistant-img-from-url datasource).
 * - render: one expandable row per enabled agent; chat only inside expanded row (see AiAssistantFormControlPanel.tsx).
 *
 * REQUIRED (do not regress): pass pre-filtered agents via cqVisibleAgentsFromProperties — every Property-enabled
 * agent must appear; React must not depend on enabledAgentKeys/stable-key filtering of the full list.
 */
/* global CStudioForms, CStudioRemote, YAHOO, CStudioAuthoring, CStudioAuthoringContext, craftercms, DOMParser */

var CRAFTERQ_FORM_CONTROL_WIDGET_ID = 'craftercms.components.aiassistant.FormControl';
var CRAFTERQ_HELPER_WIDGET_ID = 'craftercms.components.aiassistant.Helper';
/** Matches <plugin id="…"> in ui.xml for this Studio plugin */
var CRAFTERQ_PLUGIN_ID = 'org.craftercms.aiassistant.studio';

var AIASSISTANT_FALLBACK_AGENTS = [
  {
    id: '019c7237-478b-7f98-9a5c-87144c3fb010',
    label: 'Content assistant',
    llm: 'openAI',
    llmModel: 'gpt-4o-mini',
    prompts: []
  }
];

/** Must match agentStableKey() in sources/src/agentConfig.ts (composite when id+label both set). */
function cqStableKey(id, label) {
  var i = String(id || '').trim();
  var l = String(label || '').trim();
  if (i && l) return i + '\x1e' + l;
  if (i) return i;
  return l || 'agent';
}

/** Must match agentFormPropertyName() in sources/src/agentConfig.ts */
function cqAgentPropName(agent) {
  var key = cqStableKey(agent.id, agent.label);
  var s = String(key).replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  return 'cqShow_' + s;
}

function cqSyncFetchConfigurationXml(siteId) {
  var qs =
    '?siteId=' +
    encodeURIComponent(siteId) +
    '&module=' +
    encodeURIComponent('studio') +
    '&path=' +
    encodeURIComponent('/ui.xml');
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/studio/api/2/configuration/get_configuration' + qs, false);
  xhr.withCredentials = true;
  xhr.setRequestHeader('Accept', 'application/json');
  try {
    xhr.send(null);
  } catch (e) {
    return '';
  }
  if (xhr.status < 200 || xhr.status >= 300) return '';
  try {
    var j = JSON.parse(xhr.responseText);
    var c = j.response && j.response.content;
    return typeof c === 'string' ? c : '';
  } catch (e2) {
    return '';
  }
}

/** Sandbox repo path — use content APIs so missing file does not hit `get_configuration` (Studio ERROR 7000). */
var CRAFTERQ_CENTRAL_AGENTS_SANDBOX_PATH = '/config/studio/ai-assistant/agents.json';

function cqApplyXsrfHeaders(xhr) {
  try {
    var m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
    var token = m && decodeURIComponent(m[1]);
    if (token) xhr.setRequestHeader('X-XSRF-TOKEN', token);
  } catch (e) {}
}

/** @returns {boolean} */
function cqSandboxHasCentralAgentsFile(siteId) {
  try {
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/studio/api/2/content/sandbox_items_by_path', false);
    xhr.withCredentials = true;
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('Accept', 'application/json');
    cqApplyXsrfHeaders(xhr);
    xhr.send(
      JSON.stringify({
        siteId: siteId,
        paths: [CRAFTERQ_CENTRAL_AGENTS_SANDBOX_PATH],
        preferContent: true
      })
    );
    if (xhr.status < 200 || xhr.status >= 300) return false;
    var j = JSON.parse(xhr.responseText);
    var resp = j.response || j;
    var miss = resp.missingItems;
    if (Array.isArray(miss) && miss.indexOf(CRAFTERQ_CENTRAL_AGENTS_SANDBOX_PATH) !== -1) return false;
    var items = resp.items;
    return !!(items && items.length && items[0]);
  } catch (e) {
    return false;
  }
}

/** Site catalog `config/studio/ai-assistant/agents.json` — when present with chat rows, form engine uses those agents only. */
function cqSyncFetchCentralAgentsJson(siteId) {
  if (!siteId || !cqSandboxHasCentralAgentsFile(siteId)) return null;
  var qs =
    '?site_id=' +
    encodeURIComponent(siteId) +
    '&path=' +
    encodeURIComponent(CRAFTERQ_CENTRAL_AGENTS_SANDBOX_PATH) +
    '&edit=false';
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/studio/api/1/services/api/1/content/get-content.json' + qs, false);
  xhr.withCredentials = true;
  xhr.setRequestHeader('Accept', 'application/json');
  try {
    xhr.send(null);
  } catch (e) {
    return null;
  }
  if (xhr.status < 200 || xhr.status >= 300) return null;
  try {
    var j = JSON.parse(xhr.responseText);
    var c = j.response && j.response.content;
    if (c != null && typeof c === 'object') return c;
    if (typeof c === 'string' && String(c).trim()) return JSON.parse(c);
    return null;
  } catch (e2) {
    return null;
  }
}

function cqChatAgentFromCentralJsonEntry(e) {
  if (!e || typeof e !== 'object') return null;
  var mode = String(e.mode != null ? e.mode : 'chat')
    .trim()
    .toLowerCase();
  if (mode === 'autonomous') return null;
  var id = String(e.crafterQAgentId != null ? e.crafterQAgentId : e.id != null ? e.id : '').trim();
  var label = String(e.label != null ? e.label : e.name != null ? e.name : '').trim();
  if (!label) return null;
  var out = { id: id, label: label, prompts: [] };
  if (e.icon != null && String(e.icon).trim()) out.icon = String(e.icon).trim();
  var llmRaw = String(e.llm != null ? e.llm : '')
    .trim()
    .toLowerCase();
  var legacyHosted = false;
  if (llmRaw === 'openai' || llmRaw === 'open-ai') out.llm = 'openAI';
  else if (
    llmRaw === 'aiassistant' ||
    llmRaw === 'hostedchat' ||
    llmRaw === 'hosted-chat' ||
    llmRaw === 'crafterq' ||
    llmRaw === 'crafter-q'
  ) {
    out.llm = 'openAI';
    legacyHosted = true;
  }
  if (e.llmModel != null && String(e.llmModel).trim()) out.llmModel = String(e.llmModel).trim();
  else if (legacyHosted && out.llm === 'openAI') out.llmModel = 'gpt-4o-mini';
  if (e.imageModel != null && String(e.imageModel).trim()) out.imageModel = String(e.imageModel).trim();
  if (e.imageGenerator != null && String(e.imageGenerator).trim()) out.imageGenerator = String(e.imageGenerator).trim();
  if (e.openAiApiKey != null && String(e.openAiApiKey).trim()) out.openAiApiKey = String(e.openAiApiKey).trim();
  var et = e.enableTools != null ? e.enableTools : e.enable_tools;
  if (et != null && String(et).trim() !== '') {
    var es = String(et)
      .trim()
      .toLowerCase();
    if (es === 'false' || es === '0' || es === 'no') out.enableTools = false;
    else if (es === 'true' || es === '1' || es === 'yes') out.enableTools = true;
  }
  if (e.prompts != null && Array.isArray(e.prompts)) {
    for (var pi = 0; pi < e.prompts.length; pi++) {
      var p = e.prompts[pi];
      if (typeof p === 'string') {
        var pt = String(p).trim();
        if (pt) out.prompts.push({ userText: pt });
      } else if (p && typeof p === 'object' && p.userText != null && String(p.userText).trim()) {
        out.prompts.push({ userText: String(p.userText).trim() });
      }
    }
  }
  return out;
}

/** @returns {Array|null} non-null when catalog should replace ui.xml for chat agents */
function cqCentralCatalogExclusiveChatAgents(siteId) {
  var parsed = cqSyncFetchCentralAgentsJson(siteId);
  if (!parsed || !Array.isArray(parsed.agents) || parsed.agents.length === 0) return null;
  var chat = [];
  for (var i = 0; i < parsed.agents.length; i++) {
    var row = cqChatAgentFromCentralJsonEntry(parsed.agents[i]);
    if (row) chat.push(row);
  }
  return chat.length ? chat : null;
}

function cqGetUiXmlFromStore() {
  try {
    if (!craftercms || typeof craftercms.getStore !== 'function') return '';
    var state = craftercms.getStore().getState();
    var xml = state && state.uiConfig && state.uiConfig.xml;
    return typeof xml === 'string' ? xml : '';
  } catch (e) {
    return '';
  }
}

function cqChildTextDirect(parent, tag) {
  var t = tag.toLowerCase();
  var ch = parent.children;
  for (var i = 0; i < ch.length; i++) {
    var el = ch[i];
    var name = el.localName || String(el.tagName || '').replace(/^.*:/, '');
    if (String(name).toLowerCase() === t) return (el.textContent || '').trim() || undefined;
  }
  return undefined;
}

function cqParseAgentElement(agentEl) {
  var id = cqChildTextDirect(agentEl, 'crafterQAgentId') || '';
  var label = cqChildTextDirect(agentEl, 'label') || '';
  if (!String(label).trim()) return null;
  var icon;
  var ch = agentEl.children;
  for (var i = 0; i < ch.length; i++) {
    var el = ch[i];
    var nm = String(el.localName || '').toLowerCase();
    if (nm === 'icon') {
      icon = el.getAttribute('id') || (el.textContent || '').trim() || undefined;
      break;
    }
  }
  var llmRaw = String(cqChildTextDirect(agentEl, 'llm') || '').toLowerCase();
  var llm;
  var legacyHostedUi = false;
  if (llmRaw === 'openai' || llmRaw === 'open-ai') llm = 'openAI';
  else if (
    llmRaw === 'aiassistant' ||
    llmRaw === 'hostedchat' ||
    llmRaw === 'hosted-chat' ||
    llmRaw === 'crafterq' ||
    llmRaw === 'crafter-q'
  ) {
    llm = 'openAI';
    legacyHostedUi = true;
  }
  var llmModel = cqChildTextDirect(agentEl, 'llmModel');
  var imageModel = cqChildTextDirect(agentEl, 'imageModel');
  var imageGenerator = cqChildTextDirect(agentEl, 'imageGenerator');
  var openAiApiKey =
    cqChildTextDirect(agentEl, 'openAiApiKey') ||
    cqChildTextDirect(agentEl, 'open-ai-api-key') ||
    cqChildTextDirect(agentEl, 'open_ai_api_key');
  var out = { id: String(id).trim(), label: String(label).trim(), icon: icon, prompts: [] };
  if (llm) out.llm = llm;
  if (llmModel) out.llmModel = llmModel;
  else if (legacyHostedUi && out.llm === 'openAI') out.llmModel = 'gpt-4o-mini';
  if (imageModel) out.imageModel = imageModel;
  if (imageGenerator) out.imageGenerator = imageGenerator;
  if (openAiApiKey && String(openAiApiKey).trim()) out.openAiApiKey = String(openAiApiKey).trim();
  var enableToolsRaw = cqChildTextDirect(agentEl, 'enableTools') || cqChildTextDirect(agentEl, 'enable_tools');
  if (enableToolsRaw != null && String(enableToolsRaw).trim() !== '') {
    var es = String(enableToolsRaw).trim().toLowerCase();
    if (es === 'false' || es === '0' || es === 'no') out.enableTools = false;
    else if (es === 'true' || es === '1' || es === 'yes') out.enableTools = true;
  }
  var expertSkills = [];
  for (var j = 0; j < ch.length; j++) {
    var cel = ch[j];
    var cnm = String(cel.localName || '').toLowerCase();
    if (cnm !== 'expertskill') continue;
    var esUrl =
      cel.getAttribute('url') ||
      cel.getAttribute('href') ||
      cqChildTextDirect(cel, 'url') ||
      cqChildTextDirect(cel, 'href');
    if (!String(esUrl || '').trim()) continue;
    var esName =
      cel.getAttribute('name') ||
      cqChildTextDirect(cel, 'name') ||
      'Expert guidance';
    var esDesc =
      cel.getAttribute('description') ||
      cqChildTextDirect(cel, 'description') ||
      '';
    expertSkills.push({
      name: String(esName).trim(),
      url: String(esUrl).trim(),
      description: String(esDesc).trim()
    });
  }
  if (expertSkills.length) out.expertSkills = expertSkills;
  var tbcRaw =
    cqChildTextDirect(agentEl, 'translateBatchConcurrency') ||
    cqChildTextDirect(agentEl, 'translate_batch_concurrency');
  if (tbcRaw != null && String(tbcRaw).trim() !== '') {
    var tbcN = parseInt(String(tbcRaw).trim(), 10);
    if (Number.isFinite(tbcN) && tbcN >= 1) {
      out.translateBatchConcurrency = Math.min(64, tbcN);
    }
  }
  return out;
}

/**
 * Nearest ancestor named `agents` for this <agent>. Do not use Element.closest('agents') — tag selectors are
 * unreliable on XML documents from DOMParser, which can skip every agent and empty the accordion list.
 */
function cqNearestAgentsAncestorForAgent(ag) {
  var el = ag && ag.parentElement;
  while (el) {
    var nm = String(el.localName || String(el.tagName || '').replace(/^.*:/, '')).toLowerCase();
    if (nm === 'agents') return el;
    el = el.parentElement;
  }
  return null;
}

/** Parse every <agent> whose nearest <agents> ancestor is exactly `agentsContainer`. */
function cqCollectAgentsInContainer(agentsContainer) {
  if (!agentsContainer) return [];
  var out = [];
  var agentEls = agentsContainer.getElementsByTagName('agent');
  for (var i = 0; i < agentEls.length; i++) {
    var ag = agentEls[i];
    if (cqNearestAgentsAncestorForAgent(ag) !== agentsContainer) continue;
    var parsed = cqParseAgentElement(ag);
    if (parsed) out.push(parsed);
  }
  return out;
}

function cqCollectAgentsUnderConfiguration(configurationEl) {
  var agentsContainer = configurationEl.getElementsByTagName('agents')[0];
  if (!agentsContainer) return [];
  return cqCollectAgentsInContainer(agentsContainer);
}

/** Merge agents from every <configuration> under a <widget> (Tools Panel, Preview Toolbar, etc.). */
function cqMergeAgentsFromWidget(widgetEl, byKey) {
  var configs = widgetEl.getElementsByTagName('configuration');
  for (var c = 0; c < configs.length; c++) {
    var cfg = configs[c];
    if (!widgetEl.contains(cfg)) continue;
    var list = cqCollectAgentsUnderConfiguration(cfg);
    for (var j = 0; j < list.length; j++) {
      var a = list[j];
      byKey[cqStableKey(a.id, a.label)] = a;
    }
  }
  var wch = widgetEl.children;
  for (var k = 0; k < wch.length; k++) {
    var cel = wch[k];
    var cname = String(cel.localName || cel.tagName || '').replace(/^.*:/, '').toLowerCase();
    if (cname !== 'agents') continue;
    var direct = cqCollectAgentsInContainer(cel);
    for (var d = 0; d < direct.length; d++) {
      var ad = direct[d];
      byKey[cqStableKey(ad.id, ad.label)] = ad;
    }
  }
}

function cqParseAgentsFromUiXml(xmlString) {
  if (!xmlString || !String(xmlString).trim()) return [];
  var doc = new DOMParser().parseFromString(xmlString, 'text/xml');
  if (doc.querySelector('parsererror')) return [];

  var byKey = {};

  var widgets = doc.getElementsByTagName('widget');
  for (var i = 0; i < widgets.length; i++) {
    var w = widgets[i];
    var wid = w.getAttribute('id');
    if (wid === CRAFTERQ_HELPER_WIDGET_ID || wid === CRAFTERQ_FORM_CONTROL_WIDGET_ID) {
      cqMergeAgentsFromWidget(w, byKey);
    }
  }

  // Any widget that hosts this plugin (Preview Toolbar, Tools, etc.) — same idea as Studio merging plugin XML.
  var plugins = doc.getElementsByTagName('plugin');
  for (var p = 0; p < plugins.length; p++) {
    var pl = plugins[p];
    var pid = pl.getAttribute('id') || pl.getAttribute('pluginId');
    if (pid !== CRAFTERQ_PLUGIN_ID) continue;
    var el = pl;
    while (el && el.nodeType === 1) {
      var local = String(el.localName || el.tagName || '').replace(/^.*:/, '').toLowerCase();
      if (local === 'widget') {
        cqMergeAgentsFromWidget(el, byKey);
        break;
      }
      el = el.parentElement;
    }
  }

  return Object.keys(byKey).map(function (k) {
    return byKey[k];
  });
}

/**
 * Studio Redux `uiConfig.xml` can lag or omit Helper `<agent>` entries that exist in the repo `ui.xml`.
 * Previously we returned as soon as the store parsed any agents — often one default — and never merged the API
 * copy, so the form accordion showed a single row. Union both sources (by stable key) when we have a site id.
 */
var cqAgentsListCache = { siteId: '', mergedAt: 0, agents: null };
var CQ_AGENTS_LIST_TTL_MS = 4000;

/** True when string looks like merged Studio ui.xml (not an empty/error body). */
function cqUiXmlStringLooksReady(xml) {
  if (!xml || typeof xml !== 'string') return false;
  var s = xml.trim();
  if (s.length < 80) return false;
  var lower = s.toLowerCase();
  return lower.indexOf('<widget') >= 0 || lower.indexOf('<plugin') >= 0 || lower.indexOf('<studio') >= 0;
}

/**
 * Merge agents from Redux + get_configuration. Use forceRefresh to bypass TTL (e.g. after waiting for ui.xml).
 */
function cqLoadAgentsForSite(siteId, options) {
  options = options || {};
  var forceRefresh = options.forceRefresh === true;
  var now = Date.now();
  var cacheKey = siteId || '';
  // `[]` is truthy in JS — never reuse a cached empty list (would hide all accordion rows for TTL ms).
  if (
    !forceRefresh &&
    Array.isArray(cqAgentsListCache.agents) &&
    cqAgentsListCache.agents.length > 0 &&
    cqAgentsListCache.siteId === cacheKey &&
    now - cqAgentsListCache.mergedAt < CQ_AGENTS_LIST_TTL_MS
  ) {
    return cqAgentsListCache.agents.slice();
  }

  var byKey = {};
  function addParsedXml(xml) {
    try {
      var list = cqParseAgentsFromUiXml(xml);
      for (var i = 0; i < list.length; i++) {
        var a = list[i];
        byKey[cqStableKey(a.id, a.label)] = a;
      }
    } catch (ignore) {}
  }

  try {
    var centralChat = siteId ? cqCentralCatalogExclusiveChatAgents(siteId) : null;
    if (centralChat) {
      for (var ci = 0; ci < centralChat.length; ci++) {
        var ca = centralChat[ci];
        byKey[cqStableKey(ca.id, ca.label)] = ca;
      }
    } else {
      addParsedXml(cqGetUiXmlFromStore());
      if (siteId) {
        addParsedXml(cqSyncFetchConfigurationXml(siteId));
      }
    }
  } catch (ignore2) {}

  var merged = Object.keys(byKey).map(function (k) {
    return byKey[k];
  });
  var out = merged.length ? merged : AIASSISTANT_FALLBACK_AGENTS.slice();
  cqAgentsListCache = { siteId: cacheKey, mergedAt: now, agents: out };
  return out.slice();
}

/**
 * After importPlugin resolves, Redux and get_configuration often still return empty ui.xml on a cold reload.
 * Poll briefly (async) until we see real XML or cap out, then merge once with forceRefresh.
 */
function cqWhenUiXmlReadyForAgents(siteId, done) {
  var maxAttempts = 12;
  var delayMs = 50;
  var attempt = 0;
  function tick() {
    var storeXml = cqGetUiXmlFromStore();
    var apiXml = siteId ? cqSyncFetchConfigurationXml(siteId) : '';
    var centralChatEarly = siteId ? cqCentralCatalogExclusiveChatAgents(siteId) : null;
    var ready =
      (centralChatEarly && centralChatEarly.length > 0) ||
      cqUiXmlStringLooksReady(storeXml) ||
      cqUiXmlStringLooksReady(apiXml);
    if (ready || attempt >= maxAttempts - 1) {
      done(cqLoadAgentsForSite(siteId, { forceRefresh: true }));
      return;
    }
    attempt++;
    setTimeout(tick, delayMs);
  }
  tick();
}

/**
 * Studio often sends boolean field values as strings, empty, or undefined until the properties sheet hydrates.
 * Only treat an agent as OFF when the property is explicitly false-like; otherwise ON (matches defaultValue: true).
 */
function cqPropertyMeansAgentDisabled(v) {
  if (v === false || v === 0) return true;
  if (v === true || v === 1) return false;
  if (v == null) return false;
  var s = String(v).trim().toLowerCase();
  if (s === '') return false;
  return s === 'false' || s === '0' || s === 'no' || s === 'off';
}

/**
 * Normalize legacy form property bags: may be an array, `{ property: [...] }`, or numeric-key object.
 */
function cqNormalizePropertyList(raw) {
  if (raw == null) return [];
  if (Array.isArray(raw)) return raw;
  if (typeof raw !== 'object') return [];
  if (Array.isArray(raw.property)) return raw.property.slice();
  if (raw.property != null && typeof raw.property === 'object') {
    var p = raw.property;
    if (Array.isArray(p)) return p.slice();
    if (p.name != null || p.value != null || p.Value != null || p.id != null || p.Id != null) return [p];
    var pk = Object.keys(p);
    if (pk.length) {
      return pk.map(function (k) {
        return p[k];
      });
    }
  }
  return Object.keys(raw).map(function (k) {
    var v = raw[k];
    if (v && typeof v === 'object' && !Array.isArray(v)) return v;
    return { name: k, value: v };
  });
}

function cqPropertyEntryName(entry) {
  if (!entry || typeof entry !== 'object') return '';
  if (entry.name != null && String(entry.name) !== '') return String(entry.name);
  if (entry.id != null && String(entry.id) !== '') return String(entry.id);
  if (entry.Name != null && String(entry.Name) !== '') return String(entry.Name);
  if (entry.Id != null && String(entry.Id) !== '') return String(entry.Id);
  return '';
}

/**
 * Merge property sources in order (later wins). Form engine may put live values on `config.properties`,
 * `config.configuration.properties`, or only on `this.properties`; shapes are not always arrays.
 */
function cqFormFieldPropertiesFromRender(config, self) {
  var map = {};
  var sources = [];
  if (self && self.properties != null) sources.push(self.properties);
  if (config) {
    if (config.properties != null) sources.push(config.properties);
    if (config.configuration && config.configuration.properties != null) sources.push(config.configuration.properties);
    if (config.props && config.props.properties != null) sources.push(config.props.properties);
  }
  for (var s = 0; s < sources.length; s++) {
    var arr = cqNormalizePropertyList(sources[s]);
    for (var i = 0; i < arr.length; i++) {
      var e = arr[i];
      var key = cqPropertyEntryName(e);
      if (!key) continue;
      map[key] = e;
    }
  }
  var out = [];
  Object.keys(map).forEach(function (k) {
    out.push(map[k]);
  });
  return out;
}

/** Some Studio builds use Value (capital V) on property entries. */
function cqFormPropertyEntryValue(entry) {
  if (!entry || typeof entry !== 'object') return undefined;
  var v = entry.value;
  if (v !== undefined && v !== null && v !== '') return v;
  var big = entry.Value;
  if (big !== undefined && big !== null && big !== '') return big;
  return v;
}

/**
 * Same enable logic as Form field Properties booleans; returns agent objects for React (no key filtering).
 * Keep in sync with getSupportedProperties / cqAgentPropName — required for “all selected agents in panel”.
 */
function cqVisibleAgentsFromProperties(properties, agents) {
  var out = [];
  var props = properties || [];
  for (var i = 0; i < agents.length; i++) {
    var a = agents[i];
    var pname = cqAgentPropName(a);
    var enabled = true;
    for (var j = 0; j < props.length; j++) {
      var pnameEntry = cqPropertyEntryName(props[j]);
      if (pnameEntry === pname) {
        enabled = !cqPropertyMeansAgentDisabled(cqFormPropertyEntryValue(props[j]));
        break;
      }
    }
    if (enabled) out.push(a);
  }
  if (out.length === 0 && agents.length > 0) {
    return agents.slice();
  }
  return out;
}

// ---------------------------------------------------------------------------
// Form shell width (95%) — layout helper only. Do NOT remove or alter the
// cqVisibleAgentsFromProperties + FormCtl({ agents: visibleAgents }) contract above.
// ---------------------------------------------------------------------------
function cqIsStudioFormWidthShell(el) {
  if (!el || !el.classList) return false;
  return el.classList.contains('container') || el.classList.contains('MuiContainer-root');
}

function cqIncrementFormContainerWiden(el) {
  el.__aiAssistantWidenRefcount = (el.__aiAssistantWidenRefcount || 0) + 1;
}

function cqDecrementFormContainerWiden(el) {
  var n = (el.__aiAssistantWidenRefcount || 0) - 1;
  el.__aiAssistantWidenRefcount = n < 0 ? 0 : n;
  if (el.__aiAssistantWidenRefcount === 0) {
    try {
      el.style.removeProperty('width');
      el.style.removeProperty('max-width');
    } catch (ignore) {}
  }
}

function cqDetachFormContainerWiden(control) {
  var el = control._cqWidenedFormContainer;
  if (!el) return;
  cqDecrementFormContainerWiden(el);
  control._cqWidenedFormContainer = null;
}

/** Normalize content type id to path segment (e.g. page/home -> /page/home). */
function cqNormalizeContentTypeId(id) {
  var t = String(id || '').trim();
  if (!t) return '';
  return t.charAt(0) === '/' ? t : '/' + t;
}

/** Best-effort content type id from legacy form engine instance. */
function cqGetFormContentTypeId(form) {
  if (!form) return '';
  try {
    var d = form.definition;
    var fromDef = d && (d.contentType || d['content-type']);
    var a =
      form.contentTypePath ||
      form.contentType ||
      form.contentTypeId ||
      fromDef ||
      '';
    return String(a).trim();
  } catch (e) {
    return '';
  }
}

function cqSyncFetchFormDefinitionXml(siteId, contentTypeId) {
  var norm = cqNormalizeContentTypeId(contentTypeId);
  if (!norm || !siteId) return '';
  var path = '/content-types' + norm + '/form-definition.xml';
  var qs =
    '?siteId=' +
    encodeURIComponent(siteId) +
    '&module=' +
    encodeURIComponent('studio') +
    '&path=' +
    encodeURIComponent(path);
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/studio/api/2/configuration/get_configuration' + qs, false);
  xhr.withCredentials = true;
  xhr.setRequestHeader('Accept', 'application/json');
  try {
    xhr.send(null);
  } catch (e) {
    return '';
  }
  if (xhr.status < 200 || xhr.status >= 300) return '';
  try {
    var j = JSON.parse(xhr.responseText);
    var c = j.response && j.response.content;
    return typeof c === 'string' ? c : '';
  } catch (e2) {
    return '';
  }
}

function cqSerializeFormValueForSnapshot(v) {
  if (v == null) return null;
  var t = typeof v;
  if (t === 'string' || t === 'number' || t === 'boolean') return v;
  try {
    return JSON.parse(JSON.stringify(v));
  } catch (ignore) {
    try {
      return String(v);
    } catch (ignore2) {
      return '[unserializable]';
    }
  }
}

/** Studio form engine stores authoritative state on `form.model` (see forms-engine.js `CStudioForm`). */
function cqDeepCloneJsonModel(model) {
  if (!model || typeof model !== 'object') return null;
  try {
    return JSON.parse(JSON.stringify(model));
  } catch (ignore) {
    return null;
  }
}

/**
 * Flush focused control into `form.model` so pending edits are included (same idea as save shortcut blurring activeElement).
 */
function cqFlushFocusedFieldIntoModel(form) {
  if (!form) return;
  try {
    if (typeof form.setFocusedField === 'function') {
      form.setFocusedField(null);
      return;
    }
  } catch (ignore) {}
  try {
    var ae = document.activeElement;
    if (ae && typeof ae.blur === 'function') ae.blur();
  } catch (ignore2) {}
}

/**
 * Same XML shape the form engine uses on Save (`forms-engine.js` → `CStudioForms.Util.serializeModelToXml`).
 */
function cqSerializeLiveFormToXml(form) {
  if (!form) return '';
  cqFlushFocusedFieldIntoModel(form);
  try {
    if (
      typeof CStudioForms !== 'undefined' &&
      CStudioForms.Util &&
      typeof CStudioForms.Util.serializeModelToXml === 'function'
    ) {
      var xml = CStudioForms.Util.serializeModelToXml(form, false);
      return typeof xml === 'string' ? xml : '';
    }
  } catch (ignore) {}
  return '';
}

/**
 * Merge assistant flat field ids into form.model, sync controls (setValue), refresh validation UI.
 */
function cqApplyAssistantFieldUpdates(control, flatUpdates) {
  var form = control.form || (control.owner && control.owner.form);
  if (!form || !flatUpdates || typeof flatUpdates !== 'object') {
    return { applied: [], skipped: [], errors: ['no form or updates'], error: 'no form or updates' };
  }
  var CRemote = typeof CStudioRemote !== 'undefined' && CStudioRemote ? CStudioRemote : {};
  cqFlushFocusedFieldIntoModel(form);
  var applied = [];
  var skipped = [];
  var errors = [];
  var keys = Object.keys(flatUpdates);
  var ki;
  for (ki = 0; ki < keys.length; ki++) {
    var key = keys[ki];
    if (!key || String(key).charAt(0) === '$') continue;
    var raw = flatUpdates[key];
    var value = raw == null ? '' : typeof raw === 'string' ? raw : String(raw);
    try {
      if (typeof form.updateModel === 'function') {
        form.updateModel(key, value, null);
        applied.push(key);
      } else {
        skipped.push(key);
      }
    } catch (ex) {
      skipped.push(key);
      errors.push(key + ': ' + (ex && ex.message ? ex.message : String(ex)));
    }
  }
  try {
    var touchedSections = {};
    var s;
    var f;
    for (s = 0; s < form.sections.length; s++) {
      var sec = form.sections[s];
      if (!sec || !sec.fields) continue;
      for (f = 0; f < sec.fields.length; f++) {
        var fld = sec.fields[f];
        if (!fld || typeof fld.id !== 'string') continue;
        if (applied.indexOf(fld.id) < 0) continue;
        touchedSections[s] = true;
        try {
          var mv = form.model[fld.id];
          if (typeof fld.setValue === 'function') {
            fld.setValue(mv != null ? mv : '', CRemote[fld.id]);
          }
          if (typeof fld.renderValidation === 'function') {
            fld.renderValidation(true);
          }
        } catch (ex2) {
          errors.push(fld.id + ' sync: ' + (ex2 && ex2.message ? ex2.message : String(ex2)));
        }
      }
    }
    for (var si in touchedSections) {
      if (!Object.prototype.hasOwnProperty.call(touchedSections, si)) continue;
      var sidx = Number(si);
      var s2 = form.sections[sidx];
      if (s2 && typeof s2.notifyValidation === 'function') {
        s2.notifyValidation();
      }
    }
  } catch (ex3) {
    errors.push('sections: ' + (ex3 && ex3.message ? ex3.message : String(ex3)));
  }
  var err = errors.length ? errors.join('; ') : '';
  return { applied: applied, skipped: skipped, errors: errors, error: err || undefined };
}

/**
 * Snapshot current in-memory field values from the legacy form engine (includes unsaved edits).
 * Prefer full `form.model` — it is what save serializes; walking sections/getValue alone misses nested/repeat data.
 */
function cqCollectFormFieldSnapshot(form) {
  if (!form) return '{}';
  cqFlushFocusedFieldIntoModel(form);
  try {
    if (typeof form.getModel === 'function') {
      var m = form.getModel();
      var clonedGet = cqDeepCloneJsonModel(m);
      if (clonedGet) return JSON.stringify(clonedGet);
      if (m && typeof m === 'object') return JSON.stringify(m);
    }
  } catch (a) {
    /* continue */
  }
  try {
    var cloned = cqDeepCloneJsonModel(form.model);
    if (cloned) return JSON.stringify(cloned);
    if (form.model && typeof form.model === 'object') return JSON.stringify(form.model);
  } catch (b) {
    /* continue */
  }
  var out = {};
  try {
    var sections = form.sections;
    if (sections && sections.length) {
      for (var i = 0; i < sections.length; i++) {
        var sec = sections[i];
        var fields = sec.fields;
        if (!fields || !fields.length) continue;
        for (var j = 0; j < fields.length; j++) {
          var fld = fields[j];
          var fid = fld.id;
          if (!fid) continue;
          if (typeof fld.getValue === 'function') {
            try {
              out[fid] = cqSerializeFormValueForSnapshot(fld.getValue());
            } catch (e) {
              out[fid] = '[Assistant: getValue failed]';
            }
          }
        }
      }
      return JSON.stringify(out);
    }
  } catch (c) {
    /* fall through */
  }
  return '{}';
}

/**
 * Returns fresh form definition XML + field snapshot for each chat send (React calls this from AiAssistantChat).
 * Definition is cached per content type while the control instance lives.
 */
function cqMakeGetAuthoringFormContext(control) {
  var defCache = { ct: '', xml: '' };
  return function () {
    var form = control.form || (control.owner && control.owner.form);
    var site = (CStudioAuthoringContext && CStudioAuthoringContext.site) || '';
    var ct = cqGetFormContentTypeId(form);
    var defXml = '';
    if (site && ct) {
      if (defCache.ct !== ct) {
        defCache.ct = ct;
        defCache.xml = cqSyncFetchFormDefinitionXml(site, ct);
      }
      defXml = defCache.xml || '';
    }
    var path = '';
    try {
      path = String(form.path || '').trim();
    } catch (pathErr) {
      path = '';
    }
    return {
      contentTypeId: ct,
      definitionXml: defXml,
      fieldValuesJson: cqCollectFormFieldSnapshot(form),
      serializedContentXml: cqSerializeLiveFormToXml(form),
      contentPath: path,
      applyAssistantFieldUpdates: function (updates) {
        return cqApplyAssistantFieldUpdates(control, updates);
      }
    };
  };
}

/** Widen the outermost layout shell (Bootstrap/MUI container) so fields use more horizontal space with the AI Assistant side panel. */
function cqWidenOutermostFormContainer(fieldContainerEl, control) {
  var last = null;
  try {
    for (var el = fieldContainerEl; el && el !== document.body; el = el.parentElement) {
      if (cqIsStudioFormWidthShell(el)) last = el;
    }
  } catch (ignore) {}
  if (!last) return;
  cqIncrementFormContainerWiden(last);
  try {
    last.style.setProperty('width', '95%', 'important');
    last.style.setProperty('max-width', '95%', 'important');
  } catch (ignore2) {}
  control._cqWidenedFormContainer = last;
}

function cqApplyFormActionBarOffset(control) {
  if (!control) return;
  if (typeof document === 'undefined') return;
  try {
    var panel = document.querySelector('[data-aiassistant-form-panel="true"]');
    if (!panel) return;
    var panelRect = panel.getBoundingClientRect();
    if (!panelRect || !panelRect.width) return;
    var visibleRightEdge = panelRect.left - 8;
    var selector =
      '.modal-footer, .MuiDialogActions-root, .studio-dialog-footer, .cstudio-dialog-footer, .form-actions, .dialog-actions, .btn-toolbar';
    var rowSelector = '.dialog-actions, .form-actions, .modal-footer, .MuiDialogActions-root, .btn-toolbar';
    var groupSelector = '.btn-group';
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!el || !el.getBoundingClientRect) continue;
      var rect = el.getBoundingClientRect();
      var overlap = Math.ceil(rect.right - visibleRightEdge);
      if (overlap > 0) {
        var shift = overlap + 16; // small visual cushion from panel edge
        el.style.setProperty('transform', 'translateX(-' + shift + 'px)', 'important');
        el.style.setProperty('padding-right', '12px', 'important');
        el.style.setProperty('overflow', 'visible', 'important');
        el.setAttribute('data-cq-form-action-offset', '1');
      }
    }

    // Fallback for dynamic Studio shells where action bars don't use known class names.
    // Detect likely Save/Cancel controls near bottom-right and shift their nearest grouped container.
    var controls = document.querySelectorAll('button, [role="button"], a');
    var groups = new Map();
    for (var c = 0; c < controls.length; c++) {
      var btn = controls[c];
      if (!btn || !btn.getBoundingClientRect) continue;
      var text = (btn.textContent || '').trim().toLowerCase();
      var aria = String(btn.getAttribute('aria-label') || '').toLowerCase();
      var title = String(btn.getAttribute('title') || '').toLowerCase();
      var val = String(btn.getAttribute('value') || '').toLowerCase();
      var idc = String(btn.id || '').toLowerCase();
      var cls = String(btn.className || '').toLowerCase();
      var token = text + ' ' + aria + ' ' + title + ' ' + val + ' ' + idc + ' ' + cls;
      if (
        token.indexOf('save') < 0 &&
        token.indexOf('cancel') < 0 &&
        token.indexOf('close') < 0 &&
        token.indexOf('minimize') < 0
      ) {
        continue;
      }
      var br = btn.getBoundingClientRect();
      if (br.width <= 0 || br.height <= 0) continue;
      // Action controls are usually in the footer region.
      if (br.top < window.innerHeight - 220) continue;
      if (br.right <= visibleRightEdge) continue;
      var row = btn.closest(rowSelector);
      var grp = btn.closest(groupSelector);
      var container = row || grp || btn.parentElement;
      if (!container || !container.getBoundingClientRect) continue;
      var cr = container.getBoundingClientRect();
      var groupOverlap = Math.ceil(cr.right - visibleRightEdge);
      if (groupOverlap <= 0) continue;
      var prev = groups.get(container) || 0;
      if (groupOverlap > prev) groups.set(container, groupOverlap);
    }
    groups.forEach(function (shift, container) {
      try {
        var shiftWithPad = shift + 16; // maintain spacing from panel
        container.style.setProperty('transform', 'translateX(-' + shiftWithPad + 'px)', 'important');
        container.style.setProperty('padding-right', '12px', 'important');
        container.style.setProperty('overflow', 'visible', 'important');
        container.setAttribute('data-cq-form-action-offset', '1');
      } catch (ignoreGroup) {}
    });

    // Keep real Cancel to the left of the real Save split group without changing split internals.
    try {
      var all = document.querySelectorAll('button, [role="button"], a');
      var cancelBtn = null;
      var saveBtn = null;
      for (var s = 0; s < all.length; s++) {
        var n = all[s];
        if (!n || !n.getBoundingClientRect) continue;
        var nr = n.getBoundingClientRect();
        if (nr.width <= 0 || nr.height <= 0) continue;
        if (nr.top < window.innerHeight - 220) continue;
        var tkn =
          (n.textContent || '').toLowerCase() +
          ' ' +
          String(n.getAttribute('aria-label') || '').toLowerCase() +
          ' ' +
          String(n.getAttribute('title') || '').toLowerCase() +
          ' ' +
          String(n.className || '').toLowerCase();
        if (!cancelBtn && tkn.indexOf('cancel') >= 0) cancelBtn = n;
        if (!saveBtn && tkn.indexOf('save') >= 0) saveBtn = n;
      }
      if (cancelBtn && saveBtn) {
        var saveGroup = saveBtn.closest('.btn-group') || saveBtn;
        var row =
          saveGroup.closest('.dialog-actions, .form-actions, .modal-footer, .MuiDialogActions-root, .btn-toolbar') ||
          saveGroup.parentElement;
        var cancelNode = cancelBtn.closest('.btn-group') || cancelBtn;
        if (row && cancelNode && saveGroup && cancelNode !== saveGroup) {
          if (cancelNode.parentElement !== row) {
            row.insertBefore(cancelNode, saveGroup);
          } else if (cancelNode.nextSibling !== saveGroup) {
            row.insertBefore(cancelNode, saveGroup);
          }
          cancelNode.style.setProperty('margin-right', '8px', 'important');
          cancelNode.setAttribute('data-cq-form-action-offset', '1');
        }
      }
    } catch (ignorePair) {}
  } catch (ignore) {}
}

function cqScheduleFormActionBarOffset(control) {
  if (!control) return;
  try {
    if (control._cqFormActionRaf) {
      cancelAnimationFrame(control._cqFormActionRaf);
    }
  } catch (ignore) {}
  try {
    control._cqFormActionRaf = requestAnimationFrame(function () {
      cqApplyFormActionBarOffset(control);
    });
  } catch (ignore2) {}
}

function cqStopFormActionBarOffset(control) {
  if (!control) return;
  try {
    if (control._cqFormActionRaf) cancelAnimationFrame(control._cqFormActionRaf);
  } catch (ignore) {}
  control._cqFormActionRaf = 0;
  try {
    if (control._cqFormActionObserver) control._cqFormActionObserver.disconnect();
  } catch (ignore2) {}
  control._cqFormActionObserver = null;
  try {
    if (control._cqFormActionResizeHandler) {
      window.removeEventListener('resize', control._cqFormActionResizeHandler);
    }
  } catch (ignore3) {}
  control._cqFormActionResizeHandler = null;
  try {
    var timers = control._cqFormActionTimers || [];
    for (var i = 0; i < timers.length; i++) window.clearTimeout(timers[i]);
  } catch (ignore4) {}
  control._cqFormActionTimers = [];
  try {
    var nodes = document.querySelectorAll('[data-cq-form-action-offset="1"]');
    for (var j = 0; j < nodes.length; j++) {
      var el = nodes[j];
      el.style.removeProperty('transform');
      el.style.removeProperty('z-index');
      el.style.removeProperty('padding-right');
      el.style.removeProperty('overflow');
      el.style.removeProperty('margin-right');
      el.removeAttribute('data-cq-form-action-offset');
    }
  } catch (ignore5) {}
}

/** True when the form engine opened this field or the whole form in read-only / view mode — do not mount the assistant panel. */
function cqIsReadOnlyFormControl(control) {
  if (!control) return false;
  try {
    var ro = control.readonly;
    if (ro === true || ro === 1) return true;
    if (typeof ro === 'string' && ['true', '1', 'yes', 'on'].indexOf(String(ro).toLowerCase()) >= 0) return true;
  } catch (e0) {}
  try {
    var form = control.form;
    if (!form) return false;
    var fro = form.readonly;
    var froCamel = form.readOnly;
    if (fro === true || froCamel === true || fro === 1 || froCamel === 1) return true;
    if (typeof fro === 'string' && ['true', '1', 'yes', 'on'].indexOf(String(fro).toLowerCase()) >= 0) return true;
    if (typeof froCamel === 'string' && ['true', '1', 'yes', 'on'].indexOf(String(froCamel).toLowerCase()) >= 0)
      return true;
  } catch (e1) {}
  return false;
}

function cqStartFormActionBarOffset(control) {
  if (!control || typeof MutationObserver === 'undefined') return;
  cqStopFormActionBarOffset(control);
  cqScheduleFormActionBarOffset(control);
  control._cqFormActionTimers = [
    window.setTimeout(function () {
      cqScheduleFormActionBarOffset(control);
    }, 60),
    window.setTimeout(function () {
      cqScheduleFormActionBarOffset(control);
    }, 220),
    window.setTimeout(function () {
      cqScheduleFormActionBarOffset(control);
    }, 700)
  ];
  try {
    control._cqFormActionObserver = new MutationObserver(function () {
      cqScheduleFormActionBarOffset(control);
    });
    control._cqFormActionObserver.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class', 'style']
    });
  } catch (ignore) {}
  control._cqFormActionResizeHandler = function () {
    cqScheduleFormActionBarOffset(control);
  };
  try {
    window.addEventListener('resize', control._cqFormActionResizeHandler);
  } catch (ignore2) {}
}

CStudioForms.Controls.CrafterqAssistant =
  CStudioForms.Controls.CrafterqAssistant ||
  function (id, form, owner, properties, constraints, readonly) {
    this.owner = owner;
    this.owner.registerField(this);
    this.errors = [];
    this.properties = properties;
    this.constraints = constraints;
    this.form = form;
    this.id = id;
    this.readonly = readonly;
    this.value = '';
    this._reactRoot = null;
    this._cqWidenedFormContainer = null;
    this._cqFormActionObserver = null;
    this._cqFormActionResizeHandler = null;
    this._cqFormActionRaf = 0;
    this._cqFormActionTimers = [];
    return this;
  };

YAHOO.extend(CStudioForms.Controls.CrafterqAssistant, CStudioForms.CStudioFormField, {
  getLabel: function () {
    return 'Studio AI Assistant';
  },

  getName: function () {
    return 'ai-assistant';
  },

  getSupportedProperties: function () {
    var site = (CStudioAuthoringContext && CStudioAuthoringContext.site) || '';
    var agents = cqLoadAgentsForSite(site);
    // Same shape as built-in datasource booleans (e.g. Components: allowShared, allowEmbedded in contentTypes.js).
    return agents.map(function (a) {
      return {
        label: a.label,
        name: cqAgentPropName(a),
        type: 'boolean',
        defaultValue: 'true'
      };
    });
  },

  getSupportedConstraints: function () {
    return [];
  },

  _onChange: function () {},

  getValue: function () {
    return this.value || '';
  },

  setValue: function (value) {
    this.value = value == null ? '' : String(value);
  },

  render: function (config, containerEl) {
    var self = this;
    if (this._reactRoot) {
      try {
        this._reactRoot.unmount();
      } catch (ignore) {}
      this._reactRoot = null;
    }
    cqDetachFormContainerWiden(self);
    containerEl.innerHTML = '';
    if (cqIsReadOnlyFormControl(self)) {
      return;
    }
    var mount = document.createElement('div');
    mount.className = 'cstudio-plugin-aiassistant-form-assistant';
    mount.setAttribute('data-aiassistant-form-mount', 'true');
    containerEl.appendChild(mount);
    cqWidenOutermostFormContainer(containerEl, self);

    var site = CStudioAuthoringContext.site;
    if (!site || typeof craftercms === 'undefined' || !craftercms.services || !craftercms.services.plugin) {
      mount.textContent = 'Assistant: Studio plugin API is not available in this context.';
      return;
    }

    craftercms.services.plugin
      .importPlugin(site, 'aiassistant', 'components', 'index.js', 'org.craftercms.aiassistant.studio')
      .then(function (plugin) {
        var React = craftercms.libs.React;
        var ReactDOMClient = craftercms.libs.ReactDOMClient;
        if (!React || !ReactDOMClient || !plugin || !plugin.widgets) {
          mount.textContent = 'Assistant: Failed to load plugin UI.';
          return;
        }
        var FormCtl = plugin.widgets[CRAFTERQ_FORM_CONTROL_WIDGET_ID];
        var CrafterRoot = craftercms.utils.constants.components.get('craftercms.components.CrafterCMSNextBridge');
        if (!FormCtl || !CrafterRoot) {
          mount.textContent = 'Assistant: Form control widget is not registered in the plugin bundle.';
          return;
        }
        var root = ReactDOMClient.createRoot(mount);
        self._reactRoot = root;
        // Wait for ui.xml (Redux +/or API) — cold reload often races; rendering immediately yields empty/wrong agents.
        cqWhenUiXmlReadyForAgents(site, function (agents) {
          if (!self._reactRoot || self._reactRoot !== root) return;
          var fieldProps = cqFormFieldPropertiesFromRender(config, self);
          var visibleAgents = cqVisibleAgentsFromProperties(fieldProps, agents);
          root.render(
            React.createElement(CrafterRoot, null, React.createElement(FormCtl, {
              agents: visibleAgents,
              getAuthoringFormContext: cqMakeGetAuthoringFormContext(self)
            }))
          );
        });
      })
      .catch(function (err) {
        mount.textContent = 'Assistant: ' + ((err && err.message) || String(err));
      });
  }
});

CStudioAuthoring.Module.moduleLoaded('ai-assistant', CStudioForms.Controls.CrafterqAssistant);
