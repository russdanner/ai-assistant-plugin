import { useCallback, useEffect, useMemo, useState } from 'react';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import { writeConfiguration } from '@craftercms/studio-ui/services/configuration';
import { firstValueFrom } from 'rxjs';
import SaveRounded from '@mui/icons-material/SaveRounded';
import Autocomplete from '@mui/material/Autocomplete';
import {
  Alert,
  Box,
  Button,
  Divider,
  FormControl,
  FormControlLabel,
  FormLabel,
  Radio,
  RadioGroup,
  Stack,
  Switch,
  TextField,
  Typography
} from '@mui/material';
import type { AiAssistantStudioUiConfig, ContentTypeImageAugmentationScope } from './aiAssistantStudioUiConfig';
import {
  DEFAULT_AI_ASSISTANT_STUDIO_UI_CONFIG,
  emitStudioUiConfigChanged,
  effectiveStudioSiteId,
  fetchStudioUiConfigAsync,
  invalidateStudioUiConfigCache,
  mergeStudioUiConfig,
  STUDIO_UI_CONFIG_REL_PATH
} from './aiAssistantStudioUiConfig';
import { fetchAiAssistantContentTypeCatalog, type AiAssistantContentTypeRow } from './aiAssistantContentTypesApi';
import { runBulkFormControlChange } from './aiAssistantFormControlBulk';

type CtOption = { id: string; label: string };

function rowToOption(row: AiAssistantContentTypeRow): CtOption | null {
  const id = String(row?.name ?? '').trim();
  if (!id) return null;
  const label = String(row?.label ?? '').trim();
  return { id: id.startsWith('/') ? id : `/${id}`, label: label ? `${label} (${id})` : id };
}

export default function AiAssistantStudioUiSettings() {
  const activeSite = useActiveSiteId();
  const siteId = useMemo(() => effectiveStudioSiteId(activeSite), [activeSite]);
  const [draft, setDraft] = useState<AiAssistantStudioUiConfig>(() => ({ ...DEFAULT_AI_ASSISTANT_STUDIO_UI_CONFIG }));
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<CtOption[]>([]);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [bulkMsg, setBulkMsg] = useState<string | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [formTargets, setFormTargets] = useState<CtOption[]>([]);

  const reload = useCallback(async () => {
    if (!siteId) return;
    setLoadError(null);
    setLoaded(false);
    try {
      const cfg = await fetchStudioUiConfigAsync(siteId);
      setDraft({ ...cfg });
      setDirty(false);
    } catch {
      setDraft(mergeStudioUiConfig(null));
      setDirty(false);
    } finally {
      setLoaded(true);
    }
  }, [siteId]);

  const loadCatalog = useCallback(async () => {
    if (!siteId) return;
    setCatalogError(null);
    try {
      const r = await fetchAiAssistantContentTypeCatalog(siteId);
      if (r.ok === false) {
        setCatalog([]);
        setCatalogError(r.message ?? 'Could not load content types.');
        return;
      }
      const opts: CtOption[] = [];
      for (const row of r.contentTypes ?? []) {
        const o = rowToOption(row);
        if (o) opts.push(o);
      }
      opts.sort((a, b) => a.id.localeCompare(b.id));
      setCatalog(opts);
    } catch (e) {
      setCatalog([]);
      setCatalogError(e instanceof Error ? e.message : String(e));
    }
  }, [siteId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    void loadCatalog();
  }, [loadCatalog]);

  const imageScope = (draft.contentTypeImageAugmentationScope ?? 'all') as ContentTypeImageAugmentationScope;
  const imageSelected = useMemo(() => {
    const allow = new Set(
      (draft.contentTypeIdsForImageAugmentation ?? []).map((x) => {
        const s = String(x).trim();
        return s.startsWith('/') ? s : `/${s}`;
      })
    );
    return catalog.filter((c) => allow.has(c.id));
  }, [catalog, draft.contentTypeIdsForImageAugmentation]);

  const setImageScope = (scope: ContentTypeImageAugmentationScope) => {
    setDraft((d) => ({ ...d, contentTypeImageAugmentationScope: scope }));
    setDirty(true);
  };

  const save = async () => {
    if (!siteId) return;
    setSaveError(null);
    setSaving(true);
    try {
      const body = {
        ...draft,
        version: typeof draft.version === 'number' ? draft.version : 1
      };
      await firstValueFrom(
        writeConfiguration(siteId, STUDIO_UI_CONFIG_REL_PATH, 'studio', JSON.stringify(body, null, 2))
      );
      invalidateStudioUiConfigCache(siteId);
      const refreshed = await fetchStudioUiConfigAsync(siteId);
      emitStudioUiConfigChanged(siteId);
      setDraft({ ...refreshed });
      setDirty(false);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const allTypeIds = useMemo(() => catalog.map((c) => c.id), [catalog]);

  const runBulk = async (mode: 'add' | 'remove', ids: string[]) => {
    if (!siteId) return;
    setBulkMsg(null);
    setBulkBusy(true);
    try {
      const stats = await runBulkFormControlChange(siteId, mode, ids);
      const parts = [
        `Updated: ${stats.ok}`,
        `Skipped (already present): ${stats.skipped}`,
        `Missing form: ${stats.missing}`,
        `Unchanged: ${stats.unchanged}`
      ];
      if (stats.errors.length) parts.push(`Errors: ${stats.errors.join('; ')}`);
      setBulkMsg(parts.join('. '));
    } catch (e) {
      setBulkMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setBulkBusy(false);
    }
  };

  if (!siteId) {
    return (
      <Box sx={{ p: 2 }}>
        <Alert severity="info">Select a site to edit AI Assistant UI and bulk form settings.</Alert>
      </Box>
    );
  }

  return (
    <Box sx={{ p: 2, maxWidth: 960 }}>
      <Typography variant="h6" gutterBottom>
        UI Configuration:
      </Typography>

      {loadError ? <Alert severity="warning">{loadError}</Alert> : null}
      {!loaded ? <Alert severity="info">Loading…</Alert> : null}
      {saveError ? <Alert severity="error">{saveError}</Alert> : null}

      <Stack spacing={3} sx={{ mt: 2, opacity: loaded ? 1 : 0.5, pointerEvents: loaded ? 'auto' : 'none' }}>
        <FormControlLabel
          control={
            <Switch
              checked={draft.showAiAssistantsInTopNavigation !== false}
              onChange={(_, v) => {
                setDraft((d) => ({ ...d, showAiAssistantsInTopNavigation: v }));
                setDirty(true);
              }}
            />
          }
          label="Show AI Assistants in top navigation (preview toolbar)"
        />
        <FormControlLabel
          control={
            <Switch
              checked={draft.showAutonomousAiAssistantsInSidebar === true}
              onChange={(_, v) => {
                setDraft((d) => ({ ...d, showAutonomousAiAssistantsInSidebar: v }));
                setDirty(true);
              }}
            />
          }
          label="Show Autonomous AI Assistants (experimental) in sidebar"
        />

        <Divider sx={{ my: 3 }} />

        <FormControl component="fieldset" variant="standard" sx={{ mt: 1 }}>
          <FormLabel component="legend">AI Assistant Form Engine Integration:</FormLabel>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1 }}>
            Update content types so AI Chat is present and image pickers support AI generated images.
          </Typography>
          <RadioGroup
            row
            value={imageScope}
            onChange={(_, v) => setImageScope(v as ContentTypeImageAugmentationScope)}
          >
            <FormControlLabel value="all" control={<Radio />} label="All content types" />
            <FormControlLabel value="none" control={<Radio />} label="None" />
            <FormControlLabel value="selected" control={<Radio />} label="Selected only" />
          </RadioGroup>
        </FormControl>

        <Autocomplete
          multiple
          options={catalog}
          getOptionLabel={(o) => o.label}
          isOptionEqualToValue={(a, b) => a.id === b.id}
          value={imageSelected}
          onChange={(_, v) => {
            setDraft((d) => ({
              ...d,
              contentTypeIdsForImageAugmentation: v.map((x) => x.id)
            }));
            setDirty(true);
          }}
          disabled={imageScope !== 'selected'}
          renderInput={(params) => (
            <TextField {...params} label="Content types (image augmentation)" placeholder="Pick types…" />
          )}
        />

        <Divider sx={{ my: 3 }} />

        <Typography variant="subtitle1" sx={{ mt: 1 }}>
          AI Assistant Form Control on Content Types:
        </Typography>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1 }}>
          Inserts or removes a marked field block in the first fields section of form-definition.xml for each chosen content
          type. Review in Git before publishing. Backup recommended.
        </Typography>
        {catalogError ? <Alert severity="warning">{catalogError}</Alert> : null}
        {bulkMsg ? <Alert severity="info">{bulkMsg}</Alert> : null}

        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} flexWrap="wrap" useFlexGap>
          <Button
            variant="outlined"
            disabled={bulkBusy || !catalog.length}
            onClick={() => void runBulk('add', allTypeIds)}
          >
            Add to all
          </Button>
          <Button
            variant="outlined"
            color="warning"
            disabled={bulkBusy || !catalog.length}
            onClick={() => void runBulk('remove', allTypeIds)}
          >
            Remove from all
          </Button>
        </Stack>

        <Autocomplete
          multiple
          options={catalog}
          getOptionLabel={(o) => o.label}
          isOptionEqualToValue={(a, b) => a.id === b.id}
          value={formTargets}
          onChange={(_, v) => setFormTargets(v)}
          renderInput={(params) => (
            <TextField {...params} label="Selected content types (form control)" placeholder="Pick types…" />
          )}
        />
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} flexWrap="wrap" useFlexGap>
          <Button
            variant="contained"
            disabled={bulkBusy || formTargets.length === 0}
            onClick={() => void runBulk('add', formTargets.map((x) => x.id))}
          >
            Add to selected
          </Button>
          <Button
            variant="outlined"
            color="warning"
            disabled={bulkBusy || formTargets.length === 0}
            onClick={() => void runBulk('remove', formTargets.map((x) => x.id))}
          >
            Remove from selected
          </Button>
        </Stack>

        <Divider />

        <Stack direction="row" spacing={1} alignItems="center">
          <Button
            variant="contained"
            startIcon={<SaveRounded />}
            disabled={!dirty || saving}
            onClick={() => void save()}
          >
            Save UI settings
          </Button>
          {dirty ? (
            <Typography variant="caption" color="text.secondary">
              Unsaved changes
            </Typography>
          ) : null}
        </Stack>
      </Stack>
    </Box>
  );
}
