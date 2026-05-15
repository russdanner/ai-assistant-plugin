import { getHostToGuestBus } from '@craftercms/studio-ui/utils/subjects';
import { contentTypesResponse } from '@craftercms/studio-ui/state/actions/preview';
import type { ContentType, ContentTypeField } from '@craftercms/studio-ui/models/ContentType';
import { DEFAULT_IMPORT_REPO_PATH } from './aiAssistantImportPath';
import { authoringSiteIdFromWindow, shouldAugmentContentTypeForImagePatch, syncReadStudioUiConfig } from './aiAssistantStudioUiConfig';

const AIASSISTANT_IMG_DATASOURCE_TYPE = 'aiassistant-img-from-url';

let installed = false;

function propValue(table: ContentTypeField['properties'], key: string): string | undefined {
  if (!table) return undefined;
  const p = table[key] as { value?: unknown } | undefined;
  if (!p) return undefined;
  if (typeof p.value === 'string') return p.value;
  return undefined;
}

function datasourceRepoPath(ds: unknown): string {
  const typed = ds as { properties?: { property?: unknown } } | undefined;
  const raw = typed?.properties?.property;
  const arr = Array.isArray(raw) ? raw : raw ? [raw] : [];
  for (const p of arr as Array<{ name?: string; value?: string }>) {
    if (p?.name === 'repoPath' && p.value?.trim()) return p.value.trim();
  }
  return '';
}

function isAiAssistantImgDatasource(ds: unknown): boolean {
  const typed = ds as { type?: string } | undefined;
  const t = (typed?.type ?? '').trim();
  if (t === AIASSISTANT_IMG_DATASOURCE_TYPE) return true;
  const n = t.replace(/-/g, '').toLowerCase();
  return n === 'aiassistantimgfromurl';
}

type DS = NonNullable<ContentType['dataSources']>[number];

function buildDsById(dataSources: ContentType['dataSources']): Map<string, DS> {
  const m = new Map<string, DS>();
  if (!dataSources) return m;
  for (const ds of dataSources) {
    if (ds?.id) m.set(String(ds.id), ds);
  }
  return m;
}

/**
 * Studio's content-type parser only maps built-in datasource types (e.g. img-repository-upload) to
 * {@code allowImagesFromRepo}. Experience Builder uses that flag to include image fields in asset-drag
 * drop targets. Inject it for image-picker fields that reference {@code aiassistant-img-from-url}.
 */
function augmentImageField(field: ContentTypeField, dsById: Map<string, DS>): ContentTypeField {
  if (field.type !== 'image') return field;
  if (field.validations?.allowImagesFromRepo) return field;

  const im = propValue(field.properties, 'imageManager');
  if (!im?.trim()) return field;

  const ids = im
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  let hasRemoteUrlImportDs = false;
  let repoPath = '';
  for (const id of ids) {
    const ds = dsById.get(id);
    if (isAiAssistantImgDatasource(ds)) hasRemoteUrlImportDs = true;
    const rp = datasourceRepoPath(ds);
    if (rp && !repoPath) repoPath = rp;
  }
  if (!hasRemoteUrlImportDs) return field;

  return {
    ...field,
    validations: {
      ...field.validations,
      allowImagesFromRepo: {
        id: 'allowImagesFromRepo',
        value: repoPath || DEFAULT_IMPORT_REPO_PATH,
        level: 'required'
      }
    }
  };
}

function augmentFields(fields: ContentType['fields'], dsById: Map<string, DS>): ContentType['fields'] {
  if (!fields) return fields;
  let changed = false;
  const next: ContentType['fields'] = { ...fields };
  for (const fid of Object.keys(fields)) {
    const field = fields[fid];
    let nf = augmentImageField(field, dsById);
    if (nf.fields) {
      const inner = augmentFields(nf.fields, dsById);
      if (inner !== nf.fields) {
        nf = { ...nf, fields: inner };
        changed = true;
      }
    }
    if (nf !== field) {
      next[fid] = nf;
      changed = true;
    }
  }
  return changed ? next : fields;
}

function augmentContentType(ct: ContentType): ContentType {
  const dsById = buildDsById(ct.dataSources);
  const fields = augmentFields(ct.fields, dsById);
  if (fields === ct.fields) return ct;
  return { ...ct, fields };
}

export function installAiAssistantContentTypesHighlightPatch(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;

  const bus = getHostToGuestBus();
  const rawNext = bus.next.bind(bus);

  bus.next = (action: unknown) => {
    const a = action as { type?: string; payload?: { contentTypes?: ContentType[] } };
    if (a?.type !== contentTypesResponse.type || !Array.isArray(a.payload?.contentTypes)) {
      rawNext(action);
      return;
    }
    const cfg = syncReadStudioUiConfig((authoringSiteIdFromWindow() ?? '').trim());
    const contentTypes = a.payload!.contentTypes!.map((ct) =>
      shouldAugmentContentTypeForImagePatch(ct, cfg) ? augmentContentType(ct) : ct
    );
    rawNext({
      ...a,
      payload: {
        ...a.payload,
        contentTypes
      }
    });
  };
}
