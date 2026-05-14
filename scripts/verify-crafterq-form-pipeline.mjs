/**
 * Fails if the AI Assistant form-engine control pipeline regresses.
 * Run: cd sources && yarn verify:form-pipeline
 */
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..');
const mainJs = join(root, 'sources/control/ai-assistant/main.js');
const indexTsx = join(root, 'sources/index.tsx');
const formCtl = join(root, 'sources/src/AiAssistantFormControl.tsx');
const fetchAgents = join(root, 'sources/src/fetchAiAssistantUiAgents.ts');

function fail(msg) {
  console.error('[verify-aiassistant-form-pipeline] FAIL: ' + msg);
  process.exit(1);
}

function needFile(p) {
  if (!existsSync(p)) fail('missing file: ' + p);
}

function needContains(path, needle, label) {
  const s = readFileSync(path, 'utf8');
  if (!s.includes(needle)) fail(label + ': must contain "' + needle + '" in ' + path);
}

needFile(mainJs);
needFile(indexTsx);
needFile(formCtl);
needFile(fetchAgents);

const main = readFileSync(mainJs, 'utf8');
// Ignore JSDoc; fail only on real calls like el.closest('agents').
const mainNoBlockComments = main.replace(/\/\*[\s\S]*?\*\//g, '\n');
if (/\.closest\s*\(\s*['"]agents['"]\s*\)/.test(mainNoBlockComments)) {
  fail('main.js must not call .closest(...) for agents tag (XML DOMParser breaks)');
}

needContains(mainJs, 'cqAgentsListCache.agents.length > 0', 'agent cache empty-guard');
needContains(mainJs, 'cqNearestAgentsAncestorForAgent', 'parent-walk for agents');
needContains(mainJs, 'cqNormalizePropertyList', 'property list normalization');
needContains(mainJs, 'cqPropertyEntryName', 'property name/id matching');
needContains(mainJs, 'cqFormFieldPropertiesFromRender', 'merged property sources');
needContains(mainJs, 'addParsedXml(cqGetUiXmlFromStore())', 'Redux ui.xml merge');
needContains(mainJs, 'addParsedXml(cqSyncFetchConfigurationXml(siteId))', 'API ui.xml merge');
needContains(mainJs, 'CRAFTERQ_FALLBACK_AGENTS', 'fallback agents');

const thenIdx = main.indexOf('.importPlugin(site');
if (thenIdx < 0) fail('main.js must call importPlugin(site,...');
const afterImport = main.slice(thenIdx);
const thenCb = afterImport.indexOf('.then(function (plugin)');
if (thenCb < 0) fail('main.js must use .then(function (plugin) after importPlugin');
const thenBody = afterImport.slice(thenCb, thenCb + 4000);
if (!thenBody.includes('cqWhenUiXmlReadyForAgents')) {
  fail('cqWhenUiXmlReadyForAgents must run inside importPlugin .then (ui.xml race guard)');
}
if (!main.includes('cqLoadAgentsForSite(siteId, { forceRefresh: true })')) {
  fail('cqWhenUiXmlReadyForAgents must call cqLoadAgentsForSite(..., { forceRefresh: true }) when ui.xml is ready');
}
if (!thenBody.includes('cqFormFieldPropertiesFromRender(config, self)')) {
  fail('cqFormFieldPropertiesFromRender must run inside importPlugin .then');
}
if (!thenBody.includes('cqVisibleAgentsFromProperties(fieldProps, agents)')) {
  fail('cqVisibleAgentsFromProperties must run inside importPlugin .then');
}

const idx = readFileSync(indexTsx, 'utf8');
if (!idx.includes('aiAssistantStudioPluginId') || !idx.includes('id: aiAssistantStudioPluginId')) {
  fail('index.tsx PluginDescriptor.id must be aiAssistantStudioPluginId');
}
if (!idx.includes('export default plugin') || !idx.includes('export { AiAssistantPopover, plugin }')) {
  fail('index.tsx must export default plugin and named plugin');
}

needContains(formCtl, 'normalizeAgentsProp', 'FormControl agents normalization');
needContains(fetchAgents, 'nearestAgentsAncestorForAgent', 'fetchAiAssistantUiAgents parent-walk');

console.log('[verify-aiassistant-form-pipeline] OK');
