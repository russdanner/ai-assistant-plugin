import * as React from 'react';
import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useState } from 'react';
import { useDispatch } from 'react-redux';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import { writeConfiguration } from '@craftercms/studio-ui/services/configuration';
import { fetchSiteUiConfig } from '@craftercms/studio-ui/state/actions/configuration';
import { firstValueFrom } from 'rxjs';
import AddRounded from '@mui/icons-material/AddRounded';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import EditRounded from '@mui/icons-material/EditRounded';
import FullscreenExitRounded from '@mui/icons-material/FullscreenExitRounded';
import FullscreenRounded from '@mui/icons-material/FullscreenRounded';
import RefreshRounded from '@mui/icons-material/RefreshRounded';
import SaveRounded from '@mui/icons-material/SaveRounded';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  FormControlLabel,
  FormGroup,
  FormLabel,
  IconButton,
  InputLabel,
  List,
  ListItem,
  ListItemSecondaryAction,
  ListItemText,
  MenuItem,
  Select,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography
} from '@mui/material';
import type { PromptConfig } from './agentConfig';
import { normalizeEnabledBuiltInToolsRaw } from './agentConfig';
import { fetchAiAssistantScriptsIndex, type AiAssistantScriptsIndexItem } from './aiAssistantScriptsApi';
import {
  STUDIO_AI_BUILTIN_TOOL_IDS,
  STUDIO_AI_CLAUDE_CHAT_MODELS,
  STUDIO_AI_DEFAULT_IMAGE_MODEL,
  STUDIO_AI_LLM_VENDOR_IDS,
  STUDIO_AI_TOOLS_LOOP_CHAT_MODELS
} from './studioAiOrchestrationToolIds';
import {
  CENTRAL_AGENTS_STUDIO_PATH,
  catalogAutonomousAgents,
  catalogChatAgents,
  defaultCentralAgentsFile,
  fetchCentralAgentsFile,
  isCentralAgentsFileShape,
  rawPromptsToEditorRows,
  serializeCentralCatalogPrompts,
  type CentralAgentFileEntry,
  type CentralAgentMode,
  type CentralAgentsFile
} from './centralAgentCatalog';

/** Select value when the agent uses a script LLM id not found under {@code scripts/aiassistant/llm/} (manual id). */
const CQ_SCRIPT_LLM_SELECT_CUSTOM = '__cqScriptLlmCustom__';
/** Select value when image {@code script:} id is not under {@code scripts/aiassistant/imagegen/}. */
const CQ_SCRIPT_IMAGE_SELECT_CUSTOM = '__cqScriptImageCustom__';

function cloneCatalog(f: CentralAgentsFile): CentralAgentsFile {
  return { version: f.version ?? 1, agents: f.agents.map((a) => ({ ...a })) };
}

function parseLlmVendorAndScript(llm: unknown): { vendor: string; scriptId: string } {
  const s = String(llm ?? 'openAI').trim();
  const low = s.toLowerCase();
  if (low === 'script' || low.startsWith('script:')) {
    return { vendor: 'script', scriptId: low.startsWith('script:') ? s.slice('script:'.length).trim() : '' };
  }
  if (
    low === 'aiassistant' ||
    low === 'hostedchat' ||
    low === 'hosted-chat' ||
    low === 'crafterq' ||
    low === 'crafter-q'
  ) {
    return { vendor: 'openAI', scriptId: '' };
  }
  return { vendor: s || 'openAI', scriptId: '' };
}

/**
 * Older builds set `llmModel` to the script folder id when editing script LLMs — that collides with provider model
 * (`<llmModel>` for provider-specific model ids). Strip that mistake so reload/edit round-trips correctly.
 */
function sanitizeScriptLlmModelField(entry: CentralAgentFileEntry): CentralAgentFileEntry {
  const llmRaw = String(entry.llm ?? '').trim();
  const low = llmRaw.toLowerCase();
  if (!low.startsWith('script:')) return entry;
  const scriptId = llmRaw.slice('script:'.length).trim();
  const lm = String(entry.llmModel ?? '').trim();
  if (scriptId && lm === scriptId) {
    const next = { ...entry } as Record<string, unknown>;
    delete next.llmModel;
    return next as CentralAgentFileEntry;
  }
  return entry;
}

function parseImageGenKind(gen: unknown): 'openai' | 'none' | 'script' {
  const g = String(gen ?? '').trim().toLowerCase();
  if (g === 'none' || g === 'off' || g === 'disabled') return 'none';
  if (g === 'script' || g.startsWith('script:')) return 'script';
  return 'openai';
}

function imageGenScriptId(gen: unknown): string {
  const g = String(gen ?? '').trim();
  if (g.toLowerCase().startsWith('script:')) return g.slice('script:'.length).trim();
  return '';
}

function allToolIdsCheckedState(entry: CentralAgentFileEntry): Set<string> {
  const parsed = normalizeEnabledBuiltInToolsRaw(entry.enabledBuiltInTools ?? entry.enabled_built_in_tools);
  if (parsed?.length) return new Set(parsed);
  return new Set(STUDIO_AI_BUILTIN_TOOL_IDS);
}

function toolCheckboxListEqualsFullSet(sel: Set<string>): boolean {
  if (sel.size !== STUDIO_AI_BUILTIN_TOOL_IDS.length) return false;
  return STUDIO_AI_BUILTIN_TOOL_IDS.every((id) => sel.has(id));
}

function setToolCheckedOnEntry(d: CentralAgentFileEntry, toolId: string, checked: boolean): CentralAgentFileEntry {
  const cur = allToolIdsCheckedState(d);
  if (checked) cur.add(toolId);
  else cur.delete(toolId);
  if (toolCheckboxListEqualsFullSet(cur)) {
    const r = { ...d } as Record<string, unknown>;
    delete r.enabledBuiltInTools;
    delete r.enabled_built_in_tools;
    return r as CentralAgentFileEntry;
  }
  return { ...d, enabledBuiltInTools: [...cur].sort() };
}

function llmModelPresetRows(vendor: string): readonly string[] {
  if (vendor === 'claude') return STUDIO_AI_CLAUDE_CHAT_MODELS;
  return STUDIO_AI_TOOLS_LOOP_CHAT_MODELS;
}

function CmsToolCheckboxes(props: {
  draft: CentralAgentFileEntry;
  onToggle: (toolId: string, checked: boolean) => void;
}) {
  const { draft, onToggle } = props;
  return (
    <Box sx={{ maxHeight: 220, overflowY: 'auto', border: 1, borderColor: 'divider', borderRadius: 1, p: 1 }}>
      <FormLabel component="legend" sx={{ fontSize: '1.05rem', fontWeight: 600, color: 'text.primary', mb: 0.5 }}>
        CMS Tools for This Agent:
      </FormLabel>
      <FormGroup>
        {STUDIO_AI_BUILTIN_TOOL_IDS.map((id) => (
          <FormControlLabel
            key={id}
            control={
              <Checkbox
                size="small"
                checked={allToolIdsCheckedState(draft).has(id)}
                onChange={(ev) => onToggle(id, ev.target.checked)}
              />
            }
            label={
              <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                {id}
              </Typography>
            }
          />
        ))}
      </FormGroup>
    </Box>
  );
}

/** Apply safe defaults before persisting so authors can save drafts from the panel without pre-filling every field. */
function normalizeCatalogForSave(f: CentralAgentsFile): CentralAgentsFile {
  const agents = f.agents.map((raw) => {
    const e = { ...raw } as CentralAgentFileEntry;
    const mode = String(e.mode ?? 'chat').toLowerCase() === 'autonomous' ? 'autonomous' : 'chat';
    if (mode === 'autonomous') {
      const name = String(e.name ?? e.label ?? '').trim() || 'agent';
      const schedule = String(e.schedule ?? '').trim() || '0 0 * * * ?';
      const prompt = String(e.prompt ?? '');
      const scopeRaw = String(e.scope ?? 'project').trim().toLowerCase();
      const scope = scopeRaw === 'user' || scopeRaw === 'role' ? scopeRaw : 'project';
      const out = {
        ...e,
        mode: 'autonomous',
        name,
        schedule,
        prompt,
        scope,
        llm: String(e.llm ?? 'openAI').trim() || 'openAI',
        llmModel: String(e.llmModel ?? 'gpt-4o-mini').trim() || 'gpt-4o-mini'
      } as CentralAgentFileEntry;
      const outRec = out as Record<string, unknown>;
      const lzAuto = String(out.llm ?? '').trim().toLowerCase();
      if (lzAuto === 'crafterq' || lzAuto === 'crafter-q') outRec.llm = 'openAI';
      delete outRec.prompts;
      const llmS = String(out.llm ?? '').toLowerCase();
      const scriptish = llmS === 'script' || llmS.startsWith('script:');
      if (!scriptish && !String(out.imageModel ?? '').trim()) {
        out.imageModel = STUDIO_AI_DEFAULT_IMAGE_MODEL;
      }
      if (out.enableTools === false) {
        delete outRec.enabledBuiltInTools;
        delete outRec.enabled_built_in_tools;
      }
      return out;
    }
    const label = String(e.label ?? e.name ?? '').trim() || 'Untitled assistant';
    const id = e.crafterQAgentId ?? e.id;
    const outChat = {
      ...e,
      mode: 'chat',
      label,
      ...(id != null && String(id).trim() !== '' ? { crafterQAgentId: String(id).trim() } : {})
    } as CentralAgentFileEntry;
    const recChat = outChat as Record<string, unknown>;
    const lzChat = String(outChat.llm ?? '').trim().toLowerCase();
    if (lzChat === 'crafterq' || lzChat === 'crafter-q') recChat.llm = 'openAI';
    delete recChat.prompt;
    delete recChat.schedule;
    delete recChat.scope;
    delete recChat.manageOtherAgentsHumanTasks;
    delete recChat.startAutomatically;
    delete recChat.stopOnFailure;
    delete recChat.name;
    const pr = serializeCentralCatalogPrompts(rawPromptsToEditorRows(e.prompts));
    if (pr) recChat.prompts = pr;
    else delete recChat.prompts;
    if (outChat.enableTools === false) {
      delete recChat.enabledBuiltInTools;
      delete recChat.enabled_built_in_tools;
    }
    const llmChat = String(outChat.llm ?? '').toLowerCase();
    const chatScriptish = llmChat === 'script' || llmChat.startsWith('script:');
    if (!chatScriptish && !String(outChat.imageModel ?? '').trim()) {
      outChat.imageModel = STUDIO_AI_DEFAULT_IMAGE_MODEL;
    }
    const opensPopup =
      recChat.openAsPopup === true ||
      String(recChat.openAsPopup ?? '').trim().toLowerCase() === 'true';
    if (opensPopup) recChat.openAsPopup = true;
    else {
      delete recChat.openAsPopup;
      delete recChat.open_as_popup;
    }
    return outChat;
  });
  return { version: f.version ?? 1, agents };
}

function summarizeEntry(e: CentralAgentFileEntry): string {
  const mode = String(e.mode ?? 'chat').toLowerCase() === 'autonomous' ? 'autonomous' : 'chat';
  if (mode === 'autonomous') {
    return `${String(e.name ?? e.label ?? 'Unnamed')} — ${e.schedule ?? '(no schedule)'}`;
  }
  return `${String(e.label ?? e.name ?? 'Unnamed')} (${String(e.llm ?? 'openAI')})`;
}

function isAutonomousEntry(e: CentralAgentFileEntry): boolean {
  return String(e.mode ?? 'chat').toLowerCase() === 'autonomous';
}

/** Parent can call {@link save} before switching tabs (e.g. Project Tools shell). */
export type AiAssistantCentralAgentsCatalogHandle = {
  /** Writes `agents.json`; returns whether the write succeeded. */
  save: () => Promise<boolean>;
};

export type AiAssistantCentralAgentsConfigurationProps = {
  /** Notifies parent when in-memory catalog differs from last saved file (for tab-leave guard). */
  onDirtyChange?: (dirty: boolean) => void;
};

const AiAssistantCentralAgentsConfiguration = forwardRef<
  AiAssistantCentralAgentsCatalogHandle,
  AiAssistantCentralAgentsConfigurationProps
>(function AiAssistantCentralAgentsConfiguration(props, ref) {
  const { onDirtyChange } = props;
  const siteId = useActiveSiteId() ?? '';
  const dispatch = useDispatch();
  const [catalog, setCatalog] = useState<CentralAgentsFile>({ version: 1, agents: [] });
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [editIndex, setEditIndex] = useState<number | null>(null);
  const [draft, setDraft] = useState<CentralAgentFileEntry | null>(null);
  /** Chat quick-prompt rows while the edit dialog is open (trimmed on save). */
  const [chatPromptRows, setChatPromptRows] = useState<PromptConfig[]>([]);
  const [agentDialogFullscreen, setAgentDialogFullscreen] = useState(false);
  const [scriptsIndexRows, setScriptsIndexRows] = useState<{
    llm: AiAssistantScriptsIndexItem[];
    imageGen: AiAssistantScriptsIndexItem[];
  }>({ llm: [], imageGen: [] });

  const scriptsRowsRef = React.useRef(scriptsIndexRows);
  scriptsRowsRef.current = scriptsIndexRows;

  const loadScriptsSandboxIndex = useCallback(async () => {
    if (!siteId) {
      setScriptsIndexRows({ llm: [], imageGen: [] });
      return;
    }
    try {
      const data = await fetchAiAssistantScriptsIndex(siteId);
      setScriptsIndexRows({
        llm: Array.isArray(data.llmScripts) ? data.llmScripts : [],
        imageGen: Array.isArray(data.imageGenerators) ? data.imageGenerators : []
      });
    } catch {
      setScriptsIndexRows({ llm: [], imageGen: [] });
    }
  }, [siteId]);

  const dirtyRef = React.useRef(dirty);
  dirtyRef.current = dirty;

  const reload = useCallback(async () => {
    if (!siteId) return;
    if (dirtyRef.current) {
      if (!window.confirm('You have unsaved agent catalog changes. Reload from disk and discard them?')) {
        return;
      }
    }
    setLoadError(null);
    setLoaded(false);
    try {
      const data = await fetchCentralAgentsFile(siteId);
      if (data && isCentralAgentsFileShape(data)) {
        setCatalog({ version: typeof data.version === 'number' ? data.version : 1, agents: [...data.agents] });
      } else {
        setCatalog(defaultCentralAgentsFile());
      }
      setDirty(false);
    } catch {
      setCatalog(defaultCentralAgentsFile());
      setDirty(false);
    } finally {
      setLoaded(true);
    }
    void loadScriptsSandboxIndex();
  }, [siteId, loadScriptsSandboxIndex]);

  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);

  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const chatCount = useMemo(() => catalogChatAgents(catalog).length, [catalog]);
  const autonomousCount = useMemo(() => catalogAutonomousAgents(catalog).length, [catalog]);

  const { chatIndices, autonomousIndices } = useMemo(() => {
    const chat: number[] = [];
    const autonomous: number[] = [];
    catalog.agents.forEach((e, i) => {
      if (isAutonomousEntry(e)) autonomous.push(i);
      else chat.push(i);
    });
    return { chatIndices: chat, autonomousIndices: autonomous };
  }, [catalog]);

  const openAddChat = () => {
    setFormError(null);
    setAgentDialogFullscreen(false);
    setChatPromptRows([]);
    setDraft({
      mode: 'chat',
      label: 'New assistant',
      crafterQAgentId: '',
      llm: 'openAI',
      llmModel: 'gpt-4o-mini',
      imageModel: STUDIO_AI_DEFAULT_IMAGE_MODEL,
      enableTools: true,
      prompts: []
    });
    setEditIndex(-1);
  };

  const openAddAutonomous = () => {
    setFormError(null);
    setAgentDialogFullscreen(false);
    setChatPromptRows([]);
    setDraft({
      mode: 'autonomous',
      name: 'New autonomous agent',
      schedule: '0 0 * * * ?',
      prompt: '',
      scope: 'project',
      llm: 'openAI',
      llmModel: 'gpt-4o-mini',
      imageModel: STUDIO_AI_DEFAULT_IMAGE_MODEL,
      manageOtherAgentsHumanTasks: false
    });
    setEditIndex(-1);
  };

  const openEdit = (index: number) => {
    setFormError(null);
    setAgentDialogFullscreen(false);
    const entry = sanitizeScriptLlmModelField(catalog.agents[index]);
    setDraft({ ...entry });
    setChatPromptRows(rawPromptsToEditorRows(entry.prompts));
    setEditIndex(index);
  };

  const closeDialog = () => {
    setFormError(null);
    setAgentDialogFullscreen(false);
    setEditIndex(null);
    setDraft(null);
  };

  const applyDraft = () => {
    if (draft == null || editIndex === null) return;
    const sp = parseLlmVendorAndScript(draft.llm);
    if (sp.vendor === 'script') {
      const id = sp.scriptId.trim();
      if (!id || !/^[a-z0-9_-]{1,64}$/.test(id)) {
        setFormError('Script LLM requires a script id (1–64 chars: a-z, 0-9, dash, underscore).');
        return;
      }
    }
    const next = cloneCatalog(catalog);
    const mode = String(draft.mode ?? 'chat').toLowerCase() === 'autonomous' ? 'autonomous' : 'chat';
    const mergedDraft: CentralAgentFileEntry =
      mode === 'autonomous'
        ? (() => {
            const x = { ...draft } as Record<string, unknown>;
            delete x.prompts;
            return x as CentralAgentFileEntry;
          })()
        : ({ ...draft, prompts: serializeCentralCatalogPrompts(chatPromptRows) ?? [] } as CentralAgentFileEntry);
    if (editIndex < 0) next.agents.push(mergedDraft);
    else next.agents[editIndex] = mergedDraft;
    setCatalog(next);
    setDirty(true);
    closeDialog();
  };

  const removeAt = (index: number) => {
    const next = cloneCatalog(catalog);
    next.agents.splice(index, 1);
    setCatalog(next);
    setDirty(true);
  };

  const persistCatalog = useCallback(async (): Promise<boolean> => {
    if (!siteId) return false;
    setSaveError(null);
    setSaving(true);
    try {
      const toWrite = normalizeCatalogForSave(catalog);
      const body = JSON.stringify(toWrite, null, 2);
      await firstValueFrom(writeConfiguration(siteId, CENTRAL_AGENTS_STUDIO_PATH, 'studio', body));
      setCatalog(toWrite);
      setDirty(false);
      dispatch(fetchSiteUiConfig({ site: siteId }));
      return true;
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e));
      return false;
    } finally {
      setSaving(false);
    }
  }, [siteId, catalog, dispatch]);

  useImperativeHandle(
    ref,
    () => ({
      save: () => persistCatalog()
    }),
    [persistCatalog]
  );

  const save = () => void persistCatalog();

  const mode: CentralAgentMode =
    draft && String(draft.mode ?? 'chat').toLowerCase() === 'autonomous' ? 'autonomous' : 'chat';

  return (
    <Box sx={{ p: 2, maxWidth: 960, mx: 'auto' }}>
      <Typography variant="h5" component="h1" gutterBottom>
        AI Assistant Agents
      </Typography>

      {dirty ? (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Unsaved changes — save before you leave this tab.
        </Alert>
      ) : null}

      {!siteId ? (
        <Alert severity="info">Select a site.</Alert>
      ) : (
        <>
          {loadError && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {loadError}
            </Alert>
          )}
          {saveError && (
            <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSaveError(null)}>
              {saveError}
            </Alert>
          )}

          <Stack direction="row" spacing={1} flexWrap="wrap" sx={{ mb: 2 }} alignItems="center">
            <Button
              startIcon={<RefreshRounded />}
              onClick={() => void reload()}
              disabled={!loaded || saving}
              variant="outlined"
              size="small"
            >
              Reload
            </Button>
            <Button
              size="small"
              variant="outlined"
              disabled={!loaded || saving}
              onClick={() => {
                if (dirtyRef.current) {
                  if (
                    !window.confirm(
                      'Replace the in-memory catalog with the built-in example? Unsaved edits will be lost.'
                    )
                  ) {
                    return;
                  }
                }
                setCatalog(defaultCentralAgentsFile());
                setDirty(true);
              }}
            >
              Replace with example catalog
            </Button>
            <Button
              startIcon={<SaveRounded />}
              variant="contained"
              color={dirty ? 'warning' : 'primary'}
              disabled={!loaded || saving || !dirty}
              onClick={save}
            >
              {saving ? 'Saving…' : 'Save to site'}
            </Button>
            <Typography variant="caption" color="text.secondary">
              {chatCount} chat · {autonomousCount} Autonomous
            </Typography>
          </Stack>

          {!loaded ? (
            <Typography variant="body2">Loading…</Typography>
          ) : catalog.agents.length === 0 ? (
            <Stack spacing={2}>
              <Alert severity="warning">
                No agents yet. Add a chat assistant or an autonomous agent, or use Replace with example catalog, then
                save.
              </Alert>
              <Stack direction="row" spacing={1} flexWrap="wrap">
                <Button
                  startIcon={<AddRounded />}
                  onClick={openAddChat}
                  disabled={!loaded || saving}
                  variant="outlined"
                  size="small"
                >
                  Add chat assistant
                </Button>
                <Button
                  startIcon={<AddRounded />}
                  onClick={openAddAutonomous}
                  disabled={!loaded || saving}
                  variant="outlined"
                  size="small"
                >
                  Add autonomous agent
                </Button>
              </Stack>
            </Stack>
          ) : (
            <Stack spacing={4}>
              <Box>
                <Stack
                  direction="row"
                  alignItems="center"
                  justifyContent="space-between"
                  flexWrap="wrap"
                  gap={1}
                  sx={{ mb: 2 }}
                >
                  <Typography variant="subtitle1" component="h2">
                    Chat Assistants:
                  </Typography>
                  <Button
                    startIcon={<AddRounded />}
                    onClick={openAddChat}
                    disabled={!loaded || saving}
                    variant="outlined"
                    size="small"
                  >
                    Add chat assistant
                  </Button>
                </Stack>
                {chatIndices.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    No chat assistants in this catalog.
                  </Typography>
                ) : (
                  <List dense disablePadding sx={{ border: 1, borderColor: 'divider', borderRadius: 1 }}>
                    {chatIndices.map((i, ord) => (
                      <React.Fragment key={`chat-agent-${i}`}>
                        {ord > 0 ? <Divider component="li" /> : null}
                        <ListItem>
                          <ListItemText primary={summarizeEntry(catalog.agents[i])} />
                          <ListItemSecondaryAction>
                            <Button size="small" startIcon={<EditRounded />} onClick={() => openEdit(i)}>
                              Edit
                            </Button>
                            <Button
                              size="small"
                              color="error"
                              startIcon={<DeleteOutlineRounded />}
                              onClick={() => removeAt(i)}
                            >
                              Remove
                            </Button>
                          </ListItemSecondaryAction>
                        </ListItem>
                      </React.Fragment>
                    ))}
                  </List>
                )}
              </Box>

              <Box>
                <Stack
                  direction="row"
                  alignItems="center"
                  justifyContent="space-between"
                  flexWrap="wrap"
                  gap={1}
                  sx={{ mb: 2 }}
                >
                  <Typography variant="subtitle1" component="h2">
                    Autonomous Agents:
                  </Typography>
                  <Button
                    startIcon={<AddRounded />}
                    onClick={openAddAutonomous}
                    disabled={!loaded || saving}
                    variant="outlined"
                    size="small"
                  >
                    Add autonomous agent
                  </Button>
                </Stack>
                {autonomousIndices.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    No autonomous agents in this catalog.
                  </Typography>
                ) : (
                  <List dense disablePadding sx={{ border: 1, borderColor: 'divider', borderRadius: 1 }}>
                    {autonomousIndices.map((i, ord) => (
                      <React.Fragment key={`auto-agent-${i}`}>
                        {ord > 0 ? <Divider component="li" /> : null}
                        <ListItem>
                          <ListItemText primary={summarizeEntry(catalog.agents[i])} />
                          <ListItemSecondaryAction>
                            <Button size="small" startIcon={<EditRounded />} onClick={() => openEdit(i)}>
                              Edit
                            </Button>
                            <Button
                              size="small"
                              color="error"
                              startIcon={<DeleteOutlineRounded />}
                              onClick={() => removeAt(i)}
                            >
                              Remove
                            </Button>
                          </ListItemSecondaryAction>
                        </ListItem>
                      </React.Fragment>
                    ))}
                  </List>
                )}
              </Box>
            </Stack>
          )}
        </>
      )}

      <Dialog
        open={draft != null && editIndex !== null}
        onClose={closeDialog}
        fullScreen={agentDialogFullscreen}
        maxWidth="md"
        fullWidth
        scroll="paper"
        PaperProps={
          agentDialogFullscreen
            ? {
                sx: {
                  m: 0,
                  maxHeight: '100%',
                  height: '100%',
                  display: 'flex',
                  flexDirection: 'column'
                }
              }
            : undefined
        }
      >
        <DialogTitle
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 1,
            flexShrink: 0,
            pr: 1
          }}
        >
          <Typography component="span" variant="h6" sx={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {editIndex != null && editIndex < 0 ? 'Add agent' : 'Edit agent'}
          </Typography>
          <IconButton
            edge="end"
            aria-label={agentDialogFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
            onClick={() => setAgentDialogFullscreen((v) => !v)}
            size="small"
          >
            {agentDialogFullscreen ? <FullscreenExitRounded /> : <FullscreenRounded />}
          </IconButton>
        </DialogTitle>
        <DialogContent
          sx={
            agentDialogFullscreen
              ? {
                  flex: '1 1 auto',
                  minHeight: 0,
                  overflow: 'auto',
                  display: 'flex',
                  flexDirection: 'column'
                }
              : undefined
          }
        >
          {draft &&
            (() => {
              const sp = parseLlmVendorAndScript(draft.llm);
              const imgK = parseImageGenKind(draft.imageGenerator);
              const llmScriptRows = scriptsIndexRows.llm;
              const imgScriptRows = scriptsIndexRows.imageGen;
              const curLlmScriptId = sp.scriptId;
              const llmScriptSelectVal = llmScriptRows.some((r) => r.id === curLlmScriptId)
                ? curLlmScriptId
                : CQ_SCRIPT_LLM_SELECT_CUSTOM;
              const curImgScriptId = imageGenScriptId(draft.imageGenerator);
              const imgScriptSelectVal = imgScriptRows.some((r) => r.id === curImgScriptId)
                ? curImgScriptId
                : CQ_SCRIPT_IMAGE_SELECT_CUSTOM;
              const presets = llmModelPresetRows(sp.vendor);
              const modelSelectValue =
                sp.vendor === 'script'
                  ? '__na__'
                  : presets.includes(String(draft.llmModel ?? '').trim())
                    ? String(draft.llmModel ?? '').trim()
                    : '__custom__';
              const llmVendorImageRows = (
                <>
                  <FormControl fullWidth size="small">
                    <InputLabel id="cq-central-llm-v">LLM provider</InputLabel>
                    <Select
                      labelId="cq-central-llm-v"
                      label="LLM provider"
                      value={sp.vendor}
                      onChange={(ev) => {
                        const v = String(ev.target.value);
                        setDraft((d) => {
                          if (!d) return d;
                          if (v === 'script') {
                            const first = scriptsRowsRef.current.llm[0]?.id?.trim();
                            return { ...d, llm: first ? `script:${first}` : 'script', llmModel: 'composer-2' };
                          }
                          return { ...d, llm: v, llmModel: d.llmModel?.trim() ? d.llmModel : 'gpt-4o-mini' };
                        });
                      }}
                    >
                      {STUDIO_AI_LLM_VENDOR_IDS.map((id) => (
                        <MenuItem key={id} value={id}>
                          {id}
                        </MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                  {sp.vendor === 'script' ? (
                    <>
                      <Stack direction="row" spacing={1} alignItems="flex-start">
                        <FormControl fullWidth size="small" sx={{ flex: 1 }}>
                          <InputLabel id="cq-central-script-llm-pick">Script LLM</InputLabel>
                          <Select
                            labelId="cq-central-script-llm-pick"
                            label="Script LLM"
                            value={llmScriptSelectVal}
                            onChange={(ev) => {
                              const v = String(ev.target.value);
                              setDraft((d) => {
                                if (!d) return d;
                                if (v === CQ_SCRIPT_LLM_SELECT_CUSTOM) return { ...d, llm: 'script' };
                                return { ...d, llm: `script:${v}` };
                              });
                            }}
                          >
                            {llmScriptRows.map((row) => (
                              <MenuItem key={row.id} value={row.id}>
                                {row.id}
                                {!row.hasSource ? ' — add runtime.groovy' : ''}
                              </MenuItem>
                            ))}
                            <MenuItem value={CQ_SCRIPT_LLM_SELECT_CUSTOM}>Custom id…</MenuItem>
                          </Select>
                        </FormControl>
                        <Tooltip title="Refresh list">
                          <IconButton
                            size="small"
                            sx={{ mt: 0.5 }}
                            aria-label="Refresh script LLM list"
                            onClick={() => void loadScriptsSandboxIndex()}
                          >
                            <RefreshRounded fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </Stack>
                      {llmScriptSelectVal === CQ_SCRIPT_LLM_SELECT_CUSTOM ? (
                        <TextField
                          label="Custom script LLM id"
                          value={sp.scriptId}
                          onChange={(ev) => {
                            const id = ev.target.value.trim();
                            setDraft((d) => (d ? { ...d, llm: id ? `script:${id}` : 'script' } : d));
                          }}
                          fullWidth
                          size="small"
                          helperText="1–64 characters: letters, numbers, dash, underscore."
                        />
                      ) : null}
                      <TextField
                        label="Provider model id (llmModel)"
                        value={String(draft.llmModel ?? '').trim()}
                        onChange={(ev) => setDraft((d) => (d ? { ...d, llmModel: ev.target.value } : d))}
                        fullWidth
                        size="small"
                        helperText="Backend model id (e.g. composer-2)."
                      />
                    </>
                  ) : (
                    <>
                      <FormControl fullWidth size="small">
                        <InputLabel id="cq-central-llm-m">LLM model</InputLabel>
                        <Select
                          labelId="cq-central-llm-m"
                          label="LLM model"
                          value={modelSelectValue}
                          onChange={(ev) => {
                            const v = String(ev.target.value);
                            setDraft((d) => (d ? { ...d, llmModel: v === '__custom__' ? d.llmModel : v } : d));
                          }}
                        >
                          {presets.map((m) => (
                            <MenuItem key={m} value={m}>
                              {m}
                            </MenuItem>
                          ))}
                          <MenuItem value="__custom__">Custom model id…</MenuItem>
                        </Select>
                      </FormControl>
                      {modelSelectValue === '__custom__' ? (
                        <TextField
                          label="Custom LLM model id"
                          value={String(draft.llmModel ?? '')}
                          onChange={(ev) => setDraft((d) => (d ? { ...d, llmModel: ev.target.value } : d))}
                          fullWidth
                          size="small"
                        />
                      ) : null}
                    </>
                  )}
                  <FormControl fullWidth size="small">
                    <InputLabel id="cq-central-img-gen">Image generator</InputLabel>
                    <Select
                      labelId="cq-central-img-gen"
                      label="Image generator"
                      value={imgK}
                      onChange={(ev) => {
                        const k = ev.target.value as 'openai' | 'none' | 'script';
                        setDraft((d) => {
                          if (!d) return d;
                          if (k === 'none') return { ...d, imageGenerator: 'none' };
                          if (k === 'script') {
                            const first = scriptsRowsRef.current.imageGen[0]?.id?.trim();
                            const cur = imageGenScriptId(d.imageGenerator);
                            const pick =
                              cur && scriptsRowsRef.current.imageGen.some((x) => x.id === cur)
                                ? cur
                                : first || 'myimage';
                            return { ...d, imageGenerator: `script:${pick}` };
                          }
                          return { ...d, imageGenerator: '' };
                        });
                      }}
                    >
                      <MenuItem value="openai">Built-in image API (default)</MenuItem>
                      <MenuItem value="none">None / disabled</MenuItem>
                      <MenuItem value="script">Site script</MenuItem>
                    </Select>
                  </FormControl>
                  {imgK === 'script' ? (
                    <>
                      <Stack direction="row" spacing={1} alignItems="flex-start">
                        <FormControl fullWidth size="small" sx={{ flex: 1 }}>
                          <InputLabel id="cq-central-script-img-pick">Image script</InputLabel>
                          <Select
                            labelId="cq-central-script-img-pick"
                            label="Image script"
                            value={imgScriptSelectVal}
                            onChange={(ev) => {
                              const v = String(ev.target.value);
                              setDraft((d) => {
                                if (!d) return d;
                                if (v === CQ_SCRIPT_IMAGE_SELECT_CUSTOM) return { ...d, imageGenerator: 'script' };
                                return { ...d, imageGenerator: `script:${v}` };
                              });
                            }}
                          >
                            {imgScriptRows.map((row) => (
                              <MenuItem key={row.id} value={row.id}>
                                {row.id}
                                {!row.hasSource ? ' — add generate.groovy' : ''}
                              </MenuItem>
                            ))}
                            <MenuItem value={CQ_SCRIPT_IMAGE_SELECT_CUSTOM}>Custom id…</MenuItem>
                          </Select>
                        </FormControl>
                        <Tooltip title="Refresh list">
                          <IconButton
                            size="small"
                            sx={{ mt: 0.5 }}
                            aria-label="Refresh image script list"
                            onClick={() => void loadScriptsSandboxIndex()}
                          >
                            <RefreshRounded fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </Stack>
                      {imgScriptSelectVal === CQ_SCRIPT_IMAGE_SELECT_CUSTOM ? (
                        <TextField
                          label="Custom image generator script id"
                          value={imageGenScriptId(draft.imageGenerator)}
                          onChange={(ev) =>
                            setDraft((d) =>
                              d ? { ...d, imageGenerator: `script:${ev.target.value.trim()}` } : d
                            )
                          }
                          fullWidth
                          size="small"
                          helperText="1–64 characters: letters, numbers, dash, underscore."
                        />
                      ) : null}
                    </>
                  ) : null}
                  <TextField
                    label="Image model (OpenAI Images default)"
                    value={String(draft.imageModel ?? STUDIO_AI_DEFAULT_IMAGE_MODEL)}
                    onChange={(ev) => setDraft((d) => (d ? { ...d, imageModel: ev.target.value } : d))}
                    fullWidth
                    size="small"
                  />
                </>
              );
              return (
                <Stack spacing={2} sx={{ mt: 1 }}>
                  {formError ? (
                    <Alert severity="error" onClose={() => setFormError(null)}>
                      {formError}
                    </Alert>
                  ) : null}
                  <FormControlLabel
                    control={
                      <Switch
                        checked={mode === 'autonomous'}
                        onChange={(ev) => {
                          const autonomous = ev.target.checked;
                          setChatPromptRows(autonomous ? [] : []);
                          setDraft((d) => {
                            if (!d) return d;
                            if (autonomous) {
                              const { prompts: _drop, ...rest } = d as Record<string, unknown>;
                              return {
                                ...rest,
                                mode: 'autonomous',
                                name: String(d.name ?? d.label ?? 'Agent').trim() || 'Agent',
                                schedule: String(d.schedule ?? '0 0 * * * ?'),
                                prompt: String(d.prompt ?? ''),
                                scope: String(d.scope ?? 'project')
                              } as CentralAgentFileEntry;
                            }
                            return {
                              ...d,
                              mode: 'chat',
                              label: String(d.label ?? d.name ?? 'Assistant').trim() || 'Assistant',
                              crafterQAgentId: d.crafterQAgentId ?? d.id ?? '',
                              prompts: []
                            } as CentralAgentFileEntry;
                          });
                        }}
                      />
                    }
                    label="Autonomous (scheduled)"
                  />
                  {mode === 'chat' ? (
                    <>
                      <TextField
                        label="Label"
                        value={String(draft.label ?? '')}
                        onChange={(ev) => setDraft((d) => (d ? { ...d, label: ev.target.value } : d))}
                        fullWidth
                        size="small"
                      />
                      <TextField
                        label="CrafterQ agent id (optional for OpenAI-only)"
                        value={String(draft.crafterQAgentId ?? draft.id ?? '')}
                        onChange={(ev) =>
                          setDraft((d) => (d ? { ...d, crafterQAgentId: ev.target.value, id: ev.target.value } : d))
                        }
                        fullWidth
                        size="small"
                      />
                      <TextField
                        label="MUI icon id (optional)"
                        value={String(draft.icon ?? '')}
                        onChange={(ev) => setDraft((d) => (d ? { ...d, icon: ev.target.value } : d))}
                        fullWidth
                        size="small"
                        placeholder="@mui/icons-material/AutoAwesomeRounded"
                      />
                      {llmVendorImageRows}
                      <FormControlLabel
                        control={
                          <Switch
                            checked={draft.enableTools !== false}
                            onChange={(ev) => setDraft((d) => (d ? { ...d, enableTools: ev.target.checked } : d))}
                          />
                        }
                        label="Enable CMS tools (native tool loop)"
                      />
                      <FormControlLabel
                        control={
                          <Switch
                            checked={
                              draft.openAsPopup === true ||
                              String(draft.openAsPopup ?? '').trim().toLowerCase() === 'true'
                            }
                            onChange={(ev) =>
                              setDraft((d) => {
                                if (!d) return d;
                                if (!ev.target.checked) {
                                  const next = { ...d } as Record<string, unknown>;
                                  delete next.openAsPopup;
                                  delete next.open_as_popup;
                                  return next as CentralAgentFileEntry;
                                }
                                return { ...d, openAsPopup: true };
                              })
                            }
                          />
                        }
                        label="Open chat in a floating dialog (default: Experience Builder tools panel)"
                      />
                      {draft.enableTools !== false ? (
                        <CmsToolCheckboxes
                          draft={draft}
                          onToggle={(toolId, checked) =>
                            setDraft((d) => (d ? setToolCheckedOnEntry(d, toolId, checked) : d))
                          }
                        />
                      ) : null}
                      <Box>
                        <FormLabel
                          component="legend"
                          sx={{ fontSize: '1.05rem', fontWeight: 600, color: 'text.primary', mt: 1 }}
                        >
                          Quick Prompts (Chat Chips):
                        </FormLabel>
                        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5, mb: 1 }}>
                          Optional shortcuts above the composer (max 10).
                        </Typography>
                        <Stack spacing={1.5}>
                          {chatPromptRows.map((row, idx) => (
                            <Box
                              key={idx}
                              sx={{
                                border: 1,
                                borderColor: 'divider',
                                borderRadius: 1,
                                p: 1.5,
                                pr: 5,
                                position: 'relative'
                              }}
                            >
                              <IconButton
                                size="small"
                                aria-label="Remove prompt"
                                sx={{ position: 'absolute', right: 4, top: 4 }}
                                onClick={() => setChatPromptRows(chatPromptRows.filter((_, i) => i !== idx))}
                              >
                                <DeleteOutlineRounded fontSize="small" />
                              </IconButton>
                              <Stack spacing={1}>
                                <TextField
                                  label="Chip label (userText)"
                                  value={row.userText}
                                  onChange={(ev) => {
                                    const v = ev.target.value;
                                    setChatPromptRows(chatPromptRows.map((r, i) => (i === idx ? { ...r, userText: v } : r)));
                                  }}
                                  fullWidth
                                  size="small"
                                  placeholder="e.g. Shorten for mobile"
                                />
                                <TextField
                                  label="Additional context (optional)"
                                  value={row.additionalContext ?? ''}
                                  onChange={(ev) => {
                                    const v = ev.target.value;
                                    setChatPromptRows(
                                      chatPromptRows.map((r, i) => (i === idx ? { ...r, additionalContext: v } : r))
                                    );
                                  }}
                                  fullWidth
                                  multiline
                                  minRows={2}
                                  size="small"
                                  placeholder="e.g. Keep the same tone as the page. Mention the hero image. Under 120 words."
                                />
                                <FormControlLabel
                                  control={
                                    <Checkbox
                                      size="small"
                                      checked={row.omitTools === true}
                                      onChange={(ev) => {
                                        const checked = ev.target.checked;
                                        setChatPromptRows(
                                          chatPromptRows.map((r, i) => {
                                            if (i !== idx) return r;
                                            const next: PromptConfig = { ...r };
                                            if (checked) next.omitTools = true;
                                            else delete next.omitTools;
                                            return next;
                                          })
                                        );
                                      }}
                                    />
                                  }
                                  label={
                                    <Typography variant="body2">
                                      Omit CMS tools when this chip is used (omitTools)
                                    </Typography>
                                  }
                                />
                              </Stack>
                            </Box>
                          ))}
                        </Stack>
                        <Button
                          sx={{ mt: 1 }}
                          size="small"
                          startIcon={<AddRounded />}
                          disabled={chatPromptRows.length >= 10}
                          onClick={() =>
                            setChatPromptRows([...chatPromptRows, { userText: '', additionalContext: '' }])
                          }
                        >
                          Add prompt
                        </Button>
                      </Box>
                    </>
                  ) : (
                    <>
                      <TextField
                        label="Agent name"
                        value={String(draft.name ?? '')}
                        onChange={(ev) => setDraft((d) => (d ? { ...d, name: ev.target.value } : d))}
                        fullWidth
                        size="small"
                      />
                      <TextField
                        label="Schedule (Quartz cron)"
                        value={String(draft.schedule ?? '')}
                        onChange={(ev) => setDraft((d) => (d ? { ...d, schedule: ev.target.value } : d))}
                        fullWidth
                        size="small"
                        helperText="Quartz cron, e.g. every minute: 0 * * * * ?"
                      />
                      <TextField
                        label="System prompt"
                        value={String(draft.prompt ?? '')}
                        onChange={(ev) => setDraft((d) => (d ? { ...d, prompt: ev.target.value } : d))}
                        fullWidth
                        multiline
                        minRows={4}
                        size="small"
                        helperText="Instructions for each scheduled run."
                        placeholder={
                          'e.g. On each run: scan /site/website/news/ for draft items older than 7 days, ' +
                          'list their internal names, and suggest one-line social posts for each.'
                        }
                      />
                      <FormControl fullWidth size="small">
                        <InputLabel id="cq-central-scope">Scope</InputLabel>
                        <Select
                          labelId="cq-central-scope"
                          label="Scope"
                          value={String(draft.scope ?? 'project')}
                          onChange={(ev) => setDraft((d) => (d ? { ...d, scope: ev.target.value } : d))}
                        >
                          <MenuItem value="project">project</MenuItem>
                          <MenuItem value="user">user</MenuItem>
                          <MenuItem value="role">role</MenuItem>
                        </Select>
                      </FormControl>
                      {llmVendorImageRows}
                      <CmsToolCheckboxes
                        draft={draft}
                        onToggle={(toolId, checked) =>
                          setDraft((d) => (d ? setToolCheckedOnEntry(d, toolId, checked) : d))
                        }
                      />
                      <FormControlLabel
                        control={
                          <Switch
                            checked={
                              draft.manageOtherAgentsHumanTasks === true ||
                              String(draft.manageOtherAgentsHumanTasks).toLowerCase() === 'true'
                            }
                            onChange={(ev) =>
                              setDraft((d) => (d ? { ...d, manageOtherAgentsHumanTasks: ev.target.checked } : d))
                            }
                          />
                        }
                        label="Manage other agents’ human tasks"
                      />
                      <FormControlLabel
                        control={
                          <Switch
                            checked={draft.startAutomatically !== false}
                            onChange={(ev) => setDraft((d) => (d ? { ...d, startAutomatically: ev.target.checked } : d))}
                          />
                        }
                        label="Start automatically (with supervisor)"
                      />
                      <FormControlLabel
                        control={
                          <Switch
                            checked={draft.stopOnFailure !== false}
                            onChange={(ev) => setDraft((d) => (d ? { ...d, stopOnFailure: ev.target.checked } : d))}
                          />
                        }
                        label="Stop on failure"
                      />
                    </>
                  )}
                </Stack>
              );
            })()}
        </DialogContent>
        <DialogActions sx={{ flexShrink: 0 }}>
          <Button onClick={closeDialog}>Cancel</Button>
          <Button variant="contained" onClick={applyDraft}>
            Apply to catalog
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
});

export default AiAssistantCentralAgentsConfiguration;
