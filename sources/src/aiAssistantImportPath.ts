/**
 * Resolves the folder used when importing remote chat images into the repo so it matches
 * the page content type's image datasources (same {@code repoPath} as XB / form pickers).
 */

/** Used when the content type is unknown or has no image datasource paths yet. */
export const DEFAULT_IMPORT_REPO_PATH = '/static-assets/item/images/{yyyy}/{mm}/{dd}/';

type ContentTypeLike = {
  dataSources?: Array<{ type?: string; repoPath?: string }>;
  fields?: Record<string, { validations?: { allowImageUpload?: { value?: string } } }>;
};

/**
 * Priority: custom URL datasource → desktop upload → merged field {@code allowImageUpload} (standard image pickers) → repository upload.
 */
export function resolveImportRepoPathFromContentType(ct: unknown): string {
  if (!ct || typeof ct !== 'object') return DEFAULT_IMPORT_REPO_PATH;
  const c = ct as ContentTypeLike;

  if (Array.isArray(c.dataSources)) {
    for (const t of ['aiassistant-img-from-url', 'img-desktop-upload'] as const) {
      const ds = c.dataSources.find((d) => d?.type === t && String(d.repoPath ?? '').trim());
      if (ds?.repoPath) return String(ds.repoPath).trim();
    }
  }

  if (c.fields && typeof c.fields === 'object') {
    for (const fid of Object.keys(c.fields)) {
      const v = c.fields[fid]?.validations?.allowImageUpload?.value;
      if (typeof v === 'string' && v.trim()) return v.trim();
    }
  }

  if (Array.isArray(c.dataSources)) {
    const repo = c.dataSources.find(
      (d) => d?.type === 'img-repository-upload' && String(d.repoPath ?? '').trim()
    );
    if (repo?.repoPath) return String(repo.repoPath).trim();
  }

  return DEFAULT_IMPORT_REPO_PATH;
}
