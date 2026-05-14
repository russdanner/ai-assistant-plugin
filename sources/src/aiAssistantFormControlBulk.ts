import { firstValueFrom } from 'rxjs';
import { fetchConfigurationXML, writeConfiguration } from '@craftercms/studio-ui/services/configuration';
import { aiAssistantStudioPluginId } from './consts';

export const AI_ASSISTANT_FORM_FIELD_MARKER_BEGIN = '<!-- AI_ASSISTANT_PLUGIN_FIELD_BEGIN -->';
export const AI_ASSISTANT_FORM_FIELD_MARKER_END = '<!-- AI_ASSISTANT_PLUGIN_FIELD_END -->';

const PLUGIN_FIELD_TYPE = `${aiAssistantStudioPluginId}/ai-assistant`;
const DEFAULT_FIELD_ID = 'cqAiAssistantStudio';

/** Minimal field block; Studio merges plugin control from site-config-tools registration. */
const MARKED_FIELD_FRAGMENT = `${AI_ASSISTANT_FORM_FIELD_MARKER_BEGIN}
<field>
  <type>${PLUGIN_FIELD_TYPE}</type>
  <id>${DEFAULT_FIELD_ID}</id>
  <iceId></iceId>
  <title>AI Assistant</title>
  <description></description>
  <defaultValue></defaultValue>
  <help></help>
  <properties>
  </properties>
  <constraints>
  </constraints>
</field>
${AI_ASSISTANT_FORM_FIELD_MARKER_END}
`;

function normalizeContentTypeId(id: string): string {
  const t = (id || '').trim();
  return t.startsWith('/') ? t : `/${t}`;
}

function formDefinitionStudioPath(contentTypeId: string): string {
  const normalized = normalizeContentTypeId(contentTypeId);
  return `content-types${normalized}/form-definition.xml`;
}

function hasPluginAssistantField(xml: string): boolean {
  if (!xml) return false;
  if (xml.includes(AI_ASSISTANT_FORM_FIELD_MARKER_BEGIN)) return true;
  return xml.includes(`<type>${PLUGIN_FIELD_TYPE}</type>`);
}

function stripMarkedBlock(xml: string): string {
  const re = new RegExp(
    `${AI_ASSISTANT_FORM_FIELD_MARKER_BEGIN.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[\\s\\S]*?${AI_ASSISTANT_FORM_FIELD_MARKER_END.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*`,
    'm'
  );
  return xml.replace(re, '');
}

/** Remove legacy unmarked field (same type + id) if present. */
function stripLegacyUnmarkedField(xml: string): string {
  const re = new RegExp(
    `<field>\\s*<type>\\s*${PLUGIN_FIELD_TYPE.replace(/\//g, '\\/')}\\s*</type>[\\s\\S]*?<id>\\s*${DEFAULT_FIELD_ID}\\s*</id>[\\s\\S]*?</field>\\s*`,
    'm'
  );
  return xml.replace(re, '');
}

function insertAfterFirstSectionFieldsOpen(xml: string): string | null {
  const m = xml.match(/<sections>\s*<section[^>]*>\s*<fields>/);
  if (!m || m.index == null) return null;
  const idx = m.index + m[0].length;
  return xml.slice(0, idx) + '\n' + MARKED_FIELD_FRAGMENT + '\n' + xml.slice(idx);
}

export async function fetchFormDefinitionXml(siteId: string, contentTypeId: string): Promise<string> {
  const path = formDefinitionStudioPath(contentTypeId);
  const xml = await firstValueFrom(fetchConfigurationXML(siteId, path, 'studio'));
  return xml ?? '';
}

export async function writeFormDefinitionXml(siteId: string, contentTypeId: string, xml: string): Promise<void> {
  const path = formDefinitionStudioPath(contentTypeId);
  await firstValueFrom(writeConfiguration(siteId, path, 'studio', xml));
}

export type BulkFormMutation = 'add' | 'remove';

export async function mutateFormDefinitionForContentType(
  siteId: string,
  contentTypeId: string,
  mode: BulkFormMutation
): Promise<'ok' | 'skipped' | 'missing_form' | 'unchanged'> {
  let xml = await fetchFormDefinitionXml(siteId, contentTypeId);
  if (!xml.trim()) return 'missing_form';

  if (mode === 'remove') {
    const next = stripLegacyUnmarkedField(stripMarkedBlock(xml));
    if (next === xml) return 'unchanged';
    await writeFormDefinitionXml(siteId, contentTypeId, next);
    return 'ok';
  }

  // add
  if (hasPluginAssistantField(xml)) return 'skipped';
  const inserted = insertAfterFirstSectionFieldsOpen(xml);
  if (inserted == null) return 'missing_form';
  await writeFormDefinitionXml(siteId, contentTypeId, inserted);
  return 'ok';
}

export async function runBulkFormControlChange(
  siteId: string,
  mode: BulkFormMutation,
  contentTypeIds: string[]
): Promise<{ ok: number; skipped: number; missing: number; unchanged: number; errors: string[] }> {
  const stats = { ok: 0, skipped: 0, missing: 0, unchanged: 0, errors: [] as string[] };
  const ids = [...new Set(contentTypeIds.map(normalizeContentTypeId).filter(Boolean))];
  for (const id of ids) {
    try {
      const r = await mutateFormDefinitionForContentType(siteId, id, mode);
      if (r === 'ok') stats.ok++;
      else if (r === 'skipped') stats.skipped++;
      else if (r === 'missing_form') stats.missing++;
      else stats.unchanged++;
    } catch (e) {
      stats.errors.push(`${id}: ${e instanceof Error ? e.message : String(e)}`);
    }
  }
  return stats;
}
