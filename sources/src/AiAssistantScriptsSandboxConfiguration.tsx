import * as React from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import { writeConfiguration } from '@craftercms/studio-ui/services/configuration';
import { firstValueFrom } from 'rxjs';
import AddRounded from '@mui/icons-material/AddRounded';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import EditRounded from '@mui/icons-material/EditRounded';
import FullscreenExitRounded from '@mui/icons-material/FullscreenExitRounded';
import FullscreenRounded from '@mui/icons-material/FullscreenRounded';
import RefreshRounded from '@mui/icons-material/RefreshRounded';
import SaveRounded from '@mui/icons-material/SaveRounded';
import FormatListBulletedRounded from '@mui/icons-material/FormatListBulletedRounded';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography
} from '@mui/material';
import {
  AI_ASSISTANT_IMAGEGEN_GROOVY_STUB,
  AI_ASSISTANT_LLM_RUNTIME_GROOVY_STUB,
  AI_ASSISTANT_USER_TOOLS_REGISTRY_STUB,
  AI_ASSISTANT_USER_TOOL_GROOVY_STUB,
  aiAssistantToolPromptMarkdownStub
} from './aiAssistantScriptStubs';
import AiAssistantToolsMcpForm from './AiAssistantToolsMcpForm';
import {
  defaultToolsPolicyFormState,
  parseToolsPolicyFromJsonText,
  serializeToolsPolicyToJson,
  validateToolsPolicy,
  buildMcpToolsPreviewBody,
  type ToolsPolicyFormState
} from './aiAssistantToolsMcpUiModel';
import AiAssistantStudioCodeEditor, { inferStudioSandboxEditorLanguage } from './AiAssistantStudioCodeEditor';
import {
  fetchAiAssistantPromptDetail,
  fetchAiAssistantScriptsIndex,
  fetchOptionalStudioSandboxUtf8,
  fetchStudioConfigFileUtf8,
  postAiAssistantScriptsMutate,
  postAiAssistantMcpToolsPreview,
  studioConfigRelativePath,
  TOOLS_JSON_SANDBOX_PATH,
  type AiAssistantScriptsIndexResponse,
  type AiAssistantScriptsIndexItem,
  type AiAssistantScriptsIndexTool,
  type AiAssistantScriptsToolPromptOverrideRow,
  type AiAssistantMcpPreviewServer
} from './aiAssistantScriptsApi';

const REGISTRY_REL = 'scripts/aiassistant/user-tools/registry.json';
/** Site policy + MCP: {@code StudioAiAssistantProjectConfig#TOOLS_JSON_PATH}. */
const TOOLS_JSON_REL = 'scripts/aiassistant/config/tools.json';

/** When embedded in Project Tools tabs, show only one vertical slice of this screen. */
export type AiAssistantScriptsSandboxPanel = 'all' | 'prompts' | 'tools' | 'scripts';

function safeJsonParse(text: string): unknown | null {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export interface AiAssistantScriptsSandboxConfigurationProps {
  /** Sub-panel when this component is split across Project Tools tabs. Default **all** = legacy single-page layout. */
  panel?: AiAssistantScriptsSandboxPanel;
}

function mcpPreviewHasPickableTools(servers: AiAssistantMcpPreviewServer[]): boolean {
  return servers.some((s) => s.ok && s.tools.length > 0);
}

export default function AiAssistantScriptsSandboxConfiguration(props: AiAssistantScriptsSandboxConfigurationProps) {
  const panel = props.panel ?? 'all';
  const showPrompts = panel === 'all' || panel === 'prompts';
  const showTools = panel === 'all' || panel === 'tools';
  const showScripts = panel === 'all' || panel === 'scripts';
  /** Tools-only tab: hide the raw registry JSON editor; the table + Add tool + Open in editor are enough for most authors. */
  const showRegistryJsonEditor = panel !== 'tools';

  const siteId = useActiveSiteId() ?? '';
  const [loadError, setLoadError] = useState<string | null>(null);
  const [index, setIndex] = useState<AiAssistantScriptsIndexResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [registryDraft, setRegistryDraft] = useState('');
  const [registryDirty, setRegistryDirty] = useState(false);
  const [savingRegistry, setSavingRegistry] = useState(false);

  const [toolsPolicy, setToolsPolicy] = useState<ToolsPolicyFormState>(() => defaultToolsPolicyFormState());
  const [toolsPolicyDirty, setToolsPolicyDirty] = useState(false);
  const [savingToolsPolicy, setSavingToolsPolicy] = useState(false);

  type McpToolsDialogState =
    | { purpose: 'listOnly'; servers: AiAssistantMcpPreviewServer[] }
    | {
        purpose: 'pickForSave';
        servers: AiAssistantMcpPreviewServer[];
        basePolicy: ToolsPolicyFormState;
        selection: Record<string, boolean>;
        preservedDisabled: string[];
      };
  const [mcpToolsDialog, setMcpToolsDialog] = useState<McpToolsDialogState | null>(null);
  const [listingMcpTools, setListingMcpTools] = useState(false);

  const [editorOpen, setEditorOpen] = useState(false);
  const [editorFullscreen, setEditorFullscreen] = useState(false);
  const [editorTitle, setEditorTitle] = useState('');
  const [editorStudioPath, setEditorStudioPath] = useState('');
  const [editorBody, setEditorBody] = useState('');
  const [editorStub, setEditorStub] = useState('');
  const [savingEditor, setSavingEditor] = useState(false);

  const [addOpen, setAddOpen] = useState<'imagegen' | 'llm' | 'tool' | null>(null);
  const [addId, setAddId] = useState('');
  const [addScript, setAddScript] = useState('');
  const [addDesc, setAddDesc] = useState('');

  const [promptReadOpen, setPromptReadOpen] = useState(false);
  const [promptReadKey, setPromptReadKey] = useState<string | null>(null);
  const [promptReadLoading, setPromptReadLoading] = useState(false);
  const [promptReadError, setPromptReadError] = useState<string | null>(null);
  const [promptReadDefault, setPromptReadDefault] = useState('');
  const [promptReadSite, setPromptReadSite] = useState('');
  const [promptReadSiteEffective, setPromptReadSiteEffective] = useState(false);
  const [promptReadDefaultTrunc, setPromptReadDefaultTrunc] = useState(false);
  const [promptReadSiteTrunc, setPromptReadSiteTrunc] = useState(false);
  const [promptReadFullscreen, setPromptReadFullscreen] = useState(false);
  const [addDialogFullscreen, setAddDialogFullscreen] = useState(false);

  const registryDirtyRef = React.useRef(false);
  React.useEffect(() => {
    registryDirtyRef.current = registryDirty;
  }, [registryDirty]);

  const toolsPolicyDirtyRef = React.useRef(false);
  React.useEffect(() => {
    toolsPolicyDirtyRef.current = toolsPolicyDirty;
  }, [toolsPolicyDirty]);

  const reload = useCallback(async () => {
    if (!siteId) return;
    setLoading(true);
    setLoadError(null);
    try {
      const data = await fetchAiAssistantScriptsIndex(siteId);
      if (data.ok === false) {
        setLoadError(data.message ?? 'Failed to load scripts index');
        setIndex(null);
        return;
      }
      setIndex(data);
      if (!registryDirtyRef.current) {
        const t = (data.registryText ?? '').trim();
        setRegistryDraft(t || AI_ASSISTANT_USER_TOOLS_REGISTRY_STUB);
      }
      if (!toolsPolicyDirtyRef.current && showTools) {
        try {
          const text = await fetchOptionalStudioSandboxUtf8(siteId, TOOLS_JSON_SANDBOX_PATH);
          const parsed = parseToolsPolicyFromJsonText(text.trim() ? text : '');
          if (!parsed.ok) {
            setLoadError(parsed.message);
            setToolsPolicy(defaultToolsPolicyFormState());
          } else {
            setToolsPolicy(parsed.state);
          }
        } catch {
          setToolsPolicy(defaultToolsPolicyFormState());
        }
      }
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
      setIndex(null);
    } finally {
      setLoading(false);
    }
  }, [siteId, showTools]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const mcpListPreviewAllowed = useMemo(() => {
    const v = validateToolsPolicy(toolsPolicy);
    if (!v.ok) return false;
    return toolsPolicy.mcpEnabled && buildMcpToolsPreviewBody(toolsPolicy).mcpServers.length > 0;
  }, [toolsPolicy]);
  const tools = useMemo(() => (index?.tools ?? []) as AiAssistantScriptsIndexTool[], [index]);
  const imageGens = useMemo(() => (index?.imageGenerators ?? []) as AiAssistantScriptsIndexItem[], [index]);
  const llms = useMemo(() => (index?.llmScripts ?? []) as AiAssistantScriptsIndexItem[], [index]);
  const toolPromptOverrides = useMemo(
    () => (index?.toolPromptOverrides ?? []) as AiAssistantScriptsToolPromptOverrideRow[],
    [index]
  );

  const saveRegistry = async () => {
    if (!siteId) return;
    if (safeJsonParse(registryDraft) == null) {
      setLoadError('Registry JSON is invalid — fix syntax before saving.');
      return;
    }
    setSavingRegistry(true);
    setLoadError(null);
    try {
      await firstValueFrom(writeConfiguration(siteId, REGISTRY_REL, 'studio', registryDraft));
      setRegistryDirty(false);
      await postAiAssistantScriptsMutate(siteId, { action: 'refreshSync' }).catch(() => {});
      await reload();
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setSavingRegistry(false);
    }
  };

  const performWriteToolsPolicy = async (policy: ToolsPolicyFormState) => {
    if (!siteId) return;
    const v = validateToolsPolicy(policy);
    if (!v.ok) {
      setLoadError(v.message);
      return;
    }
    const normalized = serializeToolsPolicyToJson(policy);
    setSavingToolsPolicy(true);
    setLoadError(null);
    try {
      await firstValueFrom(writeConfiguration(siteId, TOOLS_JSON_REL, 'studio', normalized));
      const roundTrip = parseToolsPolicyFromJsonText(normalized);
      if (roundTrip.ok) {
        setToolsPolicy(roundTrip.state);
      }
      setToolsPolicyDirty(false);
      await postAiAssistantScriptsMutate(siteId, { action: 'refreshSync' }).catch(() => {});
      await reload();
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setSavingToolsPolicy(false);
    }
  };

  const confirmMcpToolsDialogSave = async () => {
    if (!mcpToolsDialog || mcpToolsDialog.purpose !== 'pickForSave') return;
    const { basePolicy, servers, selection, preservedDisabled } = mcpToolsDialog;
    if (!mcpPreviewHasPickableTools(servers)) {
      setMcpToolsDialog(null);
      await performWriteToolsPolicy(basePolicy);
      return;
    }
    const disFromPicker = Object.keys(selection).filter((w) => !selection[w]);
    const nextDisabled = [...new Set([...preservedDisabled, ...disFromPicker])];
    const nextPolicy: ToolsPolicyFormState = { ...basePolicy, disabledMcpTools: nextDisabled };
    setMcpToolsDialog(null);
    await performWriteToolsPolicy(nextPolicy);
  };

  const setMcpServerToolsEnabled = (serverId: string, enabled: boolean) => {
    setMcpToolsDialog((d) => {
      if (!d || d.purpose !== 'pickForSave') return d;
      if (!mcpPreviewHasPickableTools(d.servers)) return d;
      const server = d.servers.find((s) => s.serverId === serverId && s.ok);
      if (!server) return d;
      const sel = { ...d.selection };
      for (const t of server.tools) {
        sel[t.wireName] = enabled;
      }
      return { ...d, selection: sel };
    });
  };

  const openMcpToolsListOnly = async () => {
    if (!siteId) return;
    const v = validateToolsPolicy(toolsPolicy);
    if (!v.ok) {
      setLoadError(v.message);
      return;
    }
    const previewBody = buildMcpToolsPreviewBody(toolsPolicy);
    if (!toolsPolicy.mcpEnabled || previewBody.mcpServers.length === 0) {
      return;
    }
    setListingMcpTools(true);
    setLoadError(null);
    try {
      const preview = await postAiAssistantMcpToolsPreview(siteId, previewBody);
      if (preview.ok === false) {
        setLoadError(preview.message ?? 'Could not list MCP tools.');
        return;
      }
      setMcpToolsDialog({ purpose: 'listOnly', servers: preview.servers ?? [] });
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setListingMcpTools(false);
    }
  };

  const saveToolsPolicy = async () => {
    if (!siteId) return;
    const v = validateToolsPolicy(toolsPolicy);
    if (!v.ok) {
      setLoadError(v.message);
      return;
    }
    const previewBody = buildMcpToolsPreviewBody(toolsPolicy);
    if (!toolsPolicy.mcpEnabled || previewBody.mcpServers.length === 0) {
      await performWriteToolsPolicy(toolsPolicy);
      return;
    }
    setSavingToolsPolicy(true);
    setLoadError(null);
    try {
      const preview = await postAiAssistantMcpToolsPreview(siteId, previewBody);
      if (preview.ok === false) {
        setLoadError(preview.message ?? 'Could not list MCP tools. Fix servers or try again.');
        return;
      }
      const servers = preview.servers ?? [];
      const discoveredLower = new Set<string>();
      for (const s of servers) {
        if (s.ok) {
          for (const t of s.tools) {
            discoveredLower.add(t.wireName.trim().toLowerCase());
          }
        }
      }
      const preservedDisabled = toolsPolicy.disabledMcpTools.filter((d) => !discoveredLower.has(d.trim().toLowerCase()));
      const disLower = new Set(toolsPolicy.disabledMcpTools.map((d) => d.trim().toLowerCase()));
      const selection: Record<string, boolean> = {};
      for (const s of servers) {
        if (!s.ok) continue;
        for (const t of s.tools) {
          selection[t.wireName] = !disLower.has(t.wireName.trim().toLowerCase());
        }
      }
      setMcpToolsDialog({
        purpose: 'pickForSave',
        basePolicy: toolsPolicy,
        selection,
        servers,
        preservedDisabled
      });
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setSavingToolsPolicy(false);
    }
  };

  const openEditor = (title: string, studioPath: string, body: string, stub: string) => {
    setEditorTitle(title);
    setEditorStudioPath(studioPath.startsWith('/') ? studioPath : `/${studioPath}`);
    const b = (body ?? '').trim();
    setEditorBody(b || stub);
    setEditorStub(stub);
    setEditorFullscreen(false);
    setEditorOpen(true);
  };

  const loadFileForEditor = async (title: string, studioPath: string, stub: string) => {
    if (!siteId) return;
    const rel = studioConfigRelativePath(studioPath);
    const text = await fetchStudioConfigFileUtf8(siteId, rel);
    openEditor(title, studioPath, text, stub);
  };

  const saveEditor = async () => {
    if (!siteId || !editorStudioPath) return;
    setSavingEditor(true);
    setLoadError(null);
    try {
      await firstValueFrom(writeConfiguration(siteId, studioConfigRelativePath(editorStudioPath), 'studio', editorBody));
      if (editorStudioPath.includes('/scripts/aiassistant/prompts/') && editorStudioPath.endsWith('.md')) {
        await postAiAssistantScriptsMutate(siteId, { action: 'invalidateToolPrompts' }).catch(() => {});
      } else {
        await postAiAssistantScriptsMutate(siteId, { action: 'refreshSync' }).catch(() => {});
      }
      setEditorFullscreen(false);
      setEditorOpen(false);
      await reload();
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setSavingEditor(false);
    }
  };

  const removeUserTool = async (toolId: string) => {
    if (!siteId || !window.confirm(`Remove tool "${toolId}" from the registry and delete its script file?`)) return;
    setLoadError(null);
    const r = await postAiAssistantScriptsMutate(siteId, { action: 'removeUserTool', toolId });
    if (r.ok === false) {
      setLoadError(r.message ?? 'Remove failed');
      return;
    }
    await reload();
  };

  const deleteRepoFile = async (repoPath: string, confirmMsg: string) => {
    if (!siteId || !window.confirm(confirmMsg)) return;
    setLoadError(null);
    const r = await postAiAssistantScriptsMutate(siteId, { action: 'deleteStudioRepo', repoPath });
    if (r.ok === false) {
      setLoadError(r.message ?? 'Delete failed');
      return;
    }
    await reload();
  };

  const removeToolPromptOverride = (key: string) => {
    void deleteRepoFile(
      `/config/studio/scripts/aiassistant/prompts/${key}.md`,
      `Remove site override for tool prompt "${key}"? The built-in default will apply again.`
    );
  };

  const openToolPromptOverride = (key: string) => {
    const sp = `/scripts/aiassistant/prompts/${key}.md`;
    void loadFileForEditor(`Tool prompt: ${key}`, sp, aiAssistantToolPromptMarkdownStub(key));
  };

  const openPromptRead = (key: string) => {
    if (!siteId) return;
    setPromptReadFullscreen(false);
    setPromptReadKey(key);
    setPromptReadOpen(true);
    setPromptReadLoading(true);
    setPromptReadError(null);
    setPromptReadDefault('');
    setPromptReadSite('');
    setPromptReadSiteEffective(false);
    setPromptReadDefaultTrunc(false);
    setPromptReadSiteTrunc(false);
    void fetchAiAssistantPromptDetail(siteId, key)
      .then((data) => {
        if (data.ok === false) {
          setPromptReadError(data.message ?? 'Failed to load prompt detail');
          return;
        }
        setPromptReadDefault(data.defaultText ?? '');
        setPromptReadSite(data.siteFileText ?? '');
        setPromptReadSiteEffective(Boolean(data.siteOverrideEffective));
        setPromptReadDefaultTrunc(Boolean(data.defaultTextTruncated));
        setPromptReadSiteTrunc(Boolean(data.siteFileTruncated));
      })
      .catch((e) => {
        setPromptReadError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        setPromptReadLoading(false);
      });
  };

  const submitAddImagegenOrLlm = async () => {
    if (!siteId || (addOpen !== 'imagegen' && addOpen !== 'llm')) return;
    const id = addId.trim().toLowerCase();
    if (!/^[a-z0-9_-]{1,64}$/.test(id)) {
      setLoadError('Id must be 1–64 chars: lowercase letters, digits, underscore, hyphen.');
      return;
    }
    setLoadError(null);
    try {
      if (addOpen === 'imagegen') {
        const sp = `/scripts/aiassistant/imagegen/${id}/generate.groovy`;
        await firstValueFrom(writeConfiguration(siteId, studioConfigRelativePath(sp), 'studio', AI_ASSISTANT_IMAGEGEN_GROOVY_STUB));
      } else {
        const sp = `/scripts/aiassistant/llm/${id}/runtime.groovy`;
        await firstValueFrom(writeConfiguration(siteId, studioConfigRelativePath(sp), 'studio', AI_ASSISTANT_LLM_RUNTIME_GROOVY_STUB));
      }
      await postAiAssistantScriptsMutate(siteId, { action: 'refreshSync' }).catch(() => {});
      setAddDialogFullscreen(false);
      setAddOpen(null);
      setAddId('');
      await reload();
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    }
  };

  const submitAddTool = async () => {
    if (!siteId || addOpen !== 'tool') return;
    const id = addId.trim();
    const script = (addScript.trim() || `${id.replace(/[^a-zA-Z0-9_-]/g, '_')}.groovy`).replace(/[^A-Za-z0-9_.-]/g, '');
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(id)) {
      setLoadError('Tool id must be 1–64 chars: letters, digits, underscore, hyphen.');
      return;
    }
    if (!/^[A-Za-z0-9][A-Za-z0-9_.-]*\.groovy$/.test(script)) {
      setLoadError('Script must be like MyTool.groovy.');
      return;
    }
    setLoadError(null);
    try {
      const raw = registryDraft.trim() || AI_ASSISTANT_USER_TOOLS_REGISTRY_STUB;
      const parsed = safeJsonParse(raw);
      let nextJson: string;
      if (Array.isArray(parsed)) {
        nextJson = JSON.stringify([...parsed, { id, script, description: addDesc.trim() }], null, 2);
      } else if (parsed && typeof parsed === 'object') {
        const o = { ...(parsed as Record<string, unknown>) };
        const toolsArr = Array.isArray(o.tools) ? [...(o.tools as unknown[])] : [];
        toolsArr.push({ id, script, description: addDesc.trim() });
        o.tools = toolsArr;
        nextJson = JSON.stringify(o, null, 2);
      } else {
        nextJson = JSON.stringify({ tools: [{ id, script, description: addDesc.trim() }] }, null, 2);
      }
      await firstValueFrom(writeConfiguration(siteId, REGISTRY_REL, 'studio', nextJson));
      setRegistryDraft(nextJson);
      setRegistryDirty(false);
      const sp = `/scripts/aiassistant/user-tools/${script}`;
      await firstValueFrom(writeConfiguration(siteId, studioConfigRelativePath(sp), 'studio', AI_ASSISTANT_USER_TOOL_GROOVY_STUB));
      await postAiAssistantScriptsMutate(siteId, { action: 'refreshSync' }).catch(() => {});
      setAddDialogFullscreen(false);
      setAddOpen(null);
      setAddId('');
      setAddScript('');
      setAddDesc('');
      await reload();
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    }
  };

  const runAddSubmit = () => {
    if (addOpen === 'tool') void submitAddTool();
    else void submitAddImagegenOrLlm();
  };

  const pageTitle =
    panel === 'prompts'
      ? 'Tool prompt overrides'
      : panel === 'tools'
        ? 'Tools and MCP'
        : panel === 'scripts'
          ? 'Script backends'
          : 'AI Assistant Scripts';

  const pageIntro =
    panel === 'prompts' ? (
      <Typography variant="body2" color="text.secondary" paragraph>
        Markdown under <code>scripts/aiassistant/prompts/&lt;KEY&gt;.md</code> overrides built-in tool prompt text (see
        ToolPromptsLoader). Empty files open with a working stub.
      </Typography>
    ) : null;

  return (
    <Box sx={{ p: 2, maxWidth: 1100, mx: 'auto' }}>
      <Typography variant="h5" component="h1" gutterBottom>
        {pageTitle}
      </Typography>
      {pageIntro}

      {!siteId ? (
        <Alert severity="info">Select a site to edit scripts.</Alert>
      ) : (
        <>
          {loadError ? (
            <Alert severity="error" sx={{ mb: 2 }} onClose={() => setLoadError(null)}>
              {loadError}
            </Alert>
          ) : null}
          <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" alignItems="center">
            <Button
              size="small"
              variant="outlined"
              startIcon={<RefreshRounded />}
              disabled={loading}
              onClick={() => {
                setRegistryDirty(false);
                setToolsPolicyDirty(false);
                void reload();
              }}
            >
              Reload
            </Button>
          </Stack>

          {showTools ? (
            <>
          <Typography variant="subtitle1" gutterBottom>
            Built-In Tools and MCP (<code>{TOOLS_JSON_REL}</code>):
          </Typography>
          <Typography variant="body2" color="text.secondary" paragraph>
            Use the form below to map built-in tools and optional MCP servers. The site file remains{' '}
            <code>scripts/aiassistant/config/tools.json</code> (written on Save). MCP tools use wire names like{' '}
            <code>mcp_&lt;serverId&gt;_&lt;toolName&gt;</code>.
          </Typography>
          <AiAssistantToolsMcpForm
            value={toolsPolicy}
            onChange={(next) => {
              setToolsPolicy(next);
              setToolsPolicyDirty(true);
            }}
          />
          <Stack direction="row" spacing={1} sx={{ mt: 2 }} flexWrap="wrap" alignItems="center">
            <Button
              size="small"
              variant="contained"
              startIcon={savingToolsPolicy ? <CircularProgress size={16} color="inherit" /> : <SaveRounded />}
              disabled={savingToolsPolicy || !toolsPolicyDirty}
              onClick={() => void saveToolsPolicy()}
            >
              Save tools &amp; MCP
            </Button>
            <Button
              size="small"
              variant="outlined"
              startIcon={listingMcpTools ? <CircularProgress size={16} color="inherit" /> : <FormatListBulletedRounded />}
              disabled={savingToolsPolicy || listingMcpTools || !mcpListPreviewAllowed}
              onClick={() => void openMcpToolsListOnly()}
            >
              List MCP tools
            </Button>
          </Stack>

          <Divider sx={{ my: 4 }} />

          <Typography variant="subtitle1" gutterBottom>
            Registry (<code>{REGISTRY_REL}</code>):
          </Typography>
          {showRegistryJsonEditor ? null : (
            <Typography variant="body2" color="text.secondary" paragraph>
              Use <strong>Add tool</strong> and the table below to change the registry. For a raw JSON view or hand edits,
              use <strong>Open in editor</strong>.
            </Typography>
          )}
          {showRegistryJsonEditor ? (
            <AiAssistantStudioCodeEditor
              language="json"
              value={registryDraft}
              onChange={(v) => {
                setRegistryDraft(v);
                setRegistryDirty(true);
              }}
              minHeightPx={260}
            />
          ) : null}
          {showRegistryJsonEditor ? (
            <Button
              sx={{ mt: 1 }}
              size="small"
              variant="contained"
              startIcon={<SaveRounded />}
              disabled={savingRegistry || !registryDirty}
              onClick={() => void saveRegistry()}
            >
              Save registry
            </Button>
          ) : null}
          <Button
            sx={{ mt: 1, ...(showRegistryJsonEditor ? { ml: 1 } : {}) }}
            size="small"
            onClick={() => loadFileForEditor('Registry', `/${REGISTRY_REL}`, AI_ASSISTANT_USER_TOOLS_REGISTRY_STUB)}
          >
            Open in editor
          </Button>
            </>
          ) : null}

          {showTools && showPrompts ? <Divider sx={{ my: 4 }} /> : null}

          {showPrompts ? (
            <>
          <Typography variant="subtitle1" gutterBottom>
            Tool Prompt Overrides (<code>scripts/aiassistant/prompts/</code>):
          </Typography>
          <Typography variant="body2" color="text.secondary" paragraph>
            Non-empty markdown for a key replaces the plugin default (see ToolPromptsLoader). Remove the file to use the
            built-in text again. Click a row to read the default and the site file side by side.
          </Typography>
          <TableContainer sx={{ maxHeight: 420, border: 1, borderColor: 'divider', borderRadius: 1 }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell>Key</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {toolPromptOverrides.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={3}>
                      <Typography variant="body2" color="text.secondary">
                        No prompt keys returned from the server.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  toolPromptOverrides.map((row) => (
                    <TableRow
                      key={row.key}
                      hover
                      selected={promptReadOpen && promptReadKey === row.key}
                      sx={{ cursor: 'pointer' }}
                      onClick={() => openPromptRead(row.key)}
                    >
                      <TableCell>
                        <code>{row.key}</code>
                      </TableCell>
                      <TableCell>
                        {row.hasOverride ? `Site override (${row.byteLength} bytes)` : 'Built-in default'}
                      </TableCell>
                      <TableCell align="right">
                        <Button
                          size="small"
                          startIcon={<EditRounded />}
                          onClick={(ev) => {
                            ev.stopPropagation();
                            void openToolPromptOverride(row.key);
                          }}
                        >
                          Override
                        </Button>
                        <Button
                          size="small"
                          color="error"
                          startIcon={<DeleteOutlineRounded />}
                          disabled={!row.hasOverride}
                          onClick={(ev) => {
                            ev.stopPropagation();
                            void removeToolPromptOverride(row.key);
                          }}
                        >
                          Remove override
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
            </>
          ) : null}

          {showPrompts && showTools ? <Divider sx={{ my: 4 }} /> : null}

          {showTools ? (
            <>
          <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
            <Typography variant="subtitle1">User Tools (Registry + Groovy):</Typography>
            <Button size="small" startIcon={<AddRounded />} onClick={() => { setAddDialogFullscreen(false); setAddOpen('tool'); }}>
              Add tool
            </Button>
          </Stack>
          <Table size="small" sx={{ border: 1, borderColor: 'divider', borderRadius: 1 }}>
            <TableHead>
              <TableRow>
                <TableCell>Id</TableCell>
                <TableCell>Script</TableCell>
                <TableCell>Description</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {tools.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4}>
                    <Typography variant="body2" color="text.secondary">
                      No tools in registry (or registry missing).
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                tools.map((t) => (
                  <TableRow key={t.id}>
                    <TableCell>{t.id}</TableCell>
                    <TableCell>
                      <code>{t.script}</code>
                    </TableCell>
                    <TableCell>{t.description}</TableCell>
                    <TableCell align="right">
                      <Button size="small" startIcon={<EditRounded />} onClick={() => void loadFileForEditor(`Tool ${t.id}`, t.studioPath, AI_ASSISTANT_USER_TOOL_GROOVY_STUB)}>
                        Edit
                      </Button>
                      <Button size="small" color="error" startIcon={<DeleteOutlineRounded />} onClick={() => void removeUserTool(t.id)}>
                        Remove
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
            </>
          ) : null}

          {showTools && showScripts ? <Divider sx={{ my: 4 }} /> : null}

          {showScripts ? (
            <>
          <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
            <Typography variant="subtitle1">Script Image Generators:</Typography>
            <Button size="small" startIcon={<AddRounded />} onClick={() => { setAddDialogFullscreen(false); setAddOpen('imagegen'); }}>
              Add generator
            </Button>
          </Stack>
          <Table size="small" sx={{ border: 1, borderColor: 'divider' }}>
            <TableHead>
              <TableRow>
                <TableCell>Id</TableCell>
                <TableCell>Path</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {imageGens.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={3}>
                    <Typography variant="body2" color="text.secondary">
                      No folders under imagegen (create one with Add generator).
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                imageGens.map((g) => (
                  <TableRow key={g.id}>
                    <TableCell>{g.id}</TableCell>
                    <TableCell>
                      <code>{g.studioPath ?? ''}</code>
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        startIcon={<EditRounded />}
                        onClick={() =>
                          void loadFileForEditor(`Image generator ${g.id}`, g.studioPath ?? '', AI_ASSISTANT_IMAGEGEN_GROOVY_STUB)
                        }
                      >
                        Edit
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        startIcon={<DeleteOutlineRounded />}
                        onClick={() =>
                          void deleteRepoFile(
                            `/config/studio${g.studioPath ?? ''}`,
                            `Delete script image generator ${g.id} (${g.studioPath})?`
                          )
                        }
                      >
                        Remove
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>

          <Divider sx={{ my: 4 }} />

          <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
            <Typography variant="subtitle1">Script LLMs:</Typography>
            <Button size="small" startIcon={<AddRounded />} onClick={() => { setAddDialogFullscreen(false); setAddOpen('llm'); }}>
              Add script LLM
            </Button>
          </Stack>
          <Table size="small" sx={{ border: 1, borderColor: 'divider' }}>
            <TableHead>
              <TableRow>
                <TableCell>Id</TableCell>
                <TableCell>Entry</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {llms.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={3}>
                    <Typography variant="body2" color="text.secondary">
                      No folders under llm (create one with Add script LLM).
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                llms.map((l) => (
                  <TableRow key={l.id}>
                    <TableCell>{l.id}</TableCell>
                    <TableCell>
                      <code>{l.studioPath ?? '(no runtime.groovy / llm.groovy)'}</code>
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        startIcon={<EditRounded />}
                        onClick={() => {
                          const sp =
                            l.studioPath && l.studioPath.length > 0
                              ? l.studioPath.startsWith('/')
                                ? l.studioPath
                                : `/${l.studioPath}`
                              : `/scripts/aiassistant/llm/${l.id}/runtime.groovy`;
                          void loadFileForEditor(`Script LLM ${l.id}`, sp, AI_ASSISTANT_LLM_RUNTIME_GROOVY_STUB);
                        }}
                      >
                        Edit
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        startIcon={<DeleteOutlineRounded />}
                        onClick={() =>
                          void deleteRepoFile(
                            `/config/studio/scripts/aiassistant/llm/${l.id}/runtime.groovy`,
                            `Delete ${l.id}/runtime.groovy if present? (You may also remove llm.groovy manually in Studio.)`
                          )
                        }
                      >
                        Remove
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
            </>
          ) : null}

          <Dialog
            open={editorOpen}
            onClose={() => {
              setEditorFullscreen(false);
              setEditorOpen(false);
            }}
            fullScreen={editorFullscreen}
            maxWidth={false}
            fullWidth
            scroll="paper"
            PaperProps={{
              sx: editorFullscreen
                ? {
                    m: 0,
                    maxHeight: '100%',
                    height: '100%',
                    display: 'flex',
                    flexDirection: 'column'
                  }
                : {
                    m: { xs: 1, sm: 2 },
                    width: { xs: 'calc(100vw - 16px)', sm: 'calc(100vw - 32px)' },
                    maxWidth: '100%',
                    height: { xs: 'calc(100vh - 16px)', sm: 'calc(100vh - 32px)' },
                    maxHeight: { xs: 'calc(100vh - 16px)', sm: 'calc(100vh - 32px)' },
                    display: 'flex',
                    flexDirection: 'column'
                  }
            }}
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
                {editorTitle}
              </Typography>
              <IconButton
                edge="end"
                aria-label={editorFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
                onClick={() => setEditorFullscreen((v) => !v)}
                size="small"
              >
                {editorFullscreen ? <FullscreenExitRounded /> : <FullscreenRounded />}
              </IconButton>
            </DialogTitle>
            <DialogContent
              dividers
              sx={{
                flex: '1 1 auto',
                minHeight: 0,
                overflow: 'hidden',
                display: 'flex',
                flexDirection: 'column',
                pt: 1
              }}
            >
              <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1, flexShrink: 0 }}>
                <code>{editorStudioPath}</code>
              </Typography>
              <Box sx={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', mb: 1 }}>
                <AiAssistantStudioCodeEditor
                  key={editorStudioPath}
                  language={inferStudioSandboxEditorLanguage(editorStudioPath)}
                  value={editorBody}
                  onChange={(v) => setEditorBody(v)}
                  flexFill
                  minHeightPx={400}
                />
              </Box>
              <Button size="small" sx={{ mt: 0, flexShrink: 0, alignSelf: 'flex-start' }} onClick={() => setEditorBody(editorStub)}>
                Reset to stub
              </Button>
            </DialogContent>
            <DialogActions sx={{ flexShrink: 0 }}>
              <Button
                onClick={() => {
                  setEditorFullscreen(false);
                  setEditorOpen(false);
                }}
              >
                Cancel
              </Button>
              <Button variant="contained" disabled={savingEditor} onClick={() => void saveEditor()}>
                {savingEditor ? 'Saving…' : 'Save'}
              </Button>
            </DialogActions>
          </Dialog>

          <Dialog
            open={promptReadOpen}
            onClose={() => {
              setPromptReadFullscreen(false);
              setPromptReadOpen(false);
              setPromptReadKey(null);
            }}
            fullScreen={promptReadFullscreen}
            maxWidth="xl"
            fullWidth
            scroll="paper"
            PaperProps={
              promptReadFullscreen
                ? {
                    sx: {
                      m: 0,
                      maxHeight: '100%',
                      height: '100%',
                      display: 'flex',
                      flexDirection: 'column'
                    }
                  }
                : {
                    sx: {
                      width: '100%',
                      maxWidth: 1400,
                      minHeight: '72vh',
                      maxHeight: '92vh',
                      display: 'flex',
                      flexDirection: 'column'
                    }
                  }
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
                {promptReadKey ? `Tool prompt: ${promptReadKey}` : 'Tool prompt'}
              </Typography>
              <IconButton
                edge="end"
                aria-label={promptReadFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
                onClick={() => setPromptReadFullscreen((v) => !v)}
                size="small"
              >
                {promptReadFullscreen ? <FullscreenExitRounded /> : <FullscreenRounded />}
              </IconButton>
            </DialogTitle>
            <DialogContent
              dividers
              sx={
                promptReadFullscreen
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
              {promptReadLoading ? <Typography variant="body2">Loading…</Typography> : null}
              {promptReadError ? (
                <Alert severity="error" sx={{ mb: 2 }} onClose={() => setPromptReadError(null)}>
                  {promptReadError}
                </Alert>
              ) : null}
              {!promptReadLoading && !promptReadError ? (
                <>
                  <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.5 }}>
                    Default (no site override): classpath <code>prompts/{promptReadKey}.md</code> if bundled, otherwise the
                    built-in Groovy literal.
                    {promptReadDefaultTrunc ? ' Truncated in this response for size.' : ''}
                  </Typography>
                  <Box sx={{ mb: 2 }}>
                    <AiAssistantStudioCodeEditor
                      language="markdown"
                      readOnly
                      value={promptReadDefault}
                      minHeightPx={promptReadFullscreen ? 360 : 280}
                    />
                  </Box>
                  <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.5 }}>
                    Site file <code>/scripts/aiassistant/prompts/{promptReadKey}.md</code> (raw). Non-blank file wins for this
                    site. {promptReadSiteEffective ? 'Override is active.' : 'Empty or whitespace only — default applies.'}{' '}
                    {promptReadSiteTrunc ? 'Truncated in this response for size.' : ''}
                  </Typography>
                  <AiAssistantStudioCodeEditor
                    language="markdown"
                    readOnly
                    value={promptReadSite}
                    minHeightPx={promptReadFullscreen ? 300 : 240}
                  />
                </>
              ) : null}
            </DialogContent>
            <DialogActions sx={{ flexShrink: 0 }}>
              <Button
                onClick={() => {
                  setPromptReadFullscreen(false);
                  setPromptReadOpen(false);
                  setPromptReadKey(null);
                }}
              >
                Close
              </Button>
              {promptReadKey ? (
                <Button
                  variant="outlined"
                  startIcon={<EditRounded />}
                  onClick={() => {
                    const k = promptReadKey;
                    setPromptReadFullscreen(false);
                    setPromptReadOpen(false);
                    setPromptReadKey(null);
                    void openToolPromptOverride(k);
                  }}
                >
                  Edit in editor
                </Button>
              ) : null}
            </DialogActions>
          </Dialog>

          <Dialog
            open={addOpen != null}
            onClose={() => {
              setAddDialogFullscreen(false);
              setAddOpen(null);
            }}
            fullScreen={addDialogFullscreen}
            maxWidth="xs"
            fullWidth
            scroll="paper"
            PaperProps={
              addDialogFullscreen
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
                {addOpen === 'imagegen' ? 'Add image generator' : addOpen === 'llm' ? 'Add script LLM' : 'Add user tool'}
              </Typography>
              <IconButton
                edge="end"
                aria-label={addDialogFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
                onClick={() => setAddDialogFullscreen((v) => !v)}
                size="small"
              >
                {addDialogFullscreen ? <FullscreenExitRounded /> : <FullscreenRounded />}
              </IconButton>
            </DialogTitle>
            <DialogContent
              sx={
                addDialogFullscreen
                  ? {
                      flex: '1 1 auto',
                      minHeight: 0,
                      overflow: 'auto'
                    }
                  : undefined
              }
            >
              <TextField
                label="Id (lowercase, a-z 0-9 _ -)"
                value={addId}
                onChange={(ev) => setAddId(ev.target.value)}
                fullWidth
                size="small"
                margin="normal"
              />
              {addOpen === 'tool' ? (
                <>
                  <TextField
                    label="Script file (e.g. MyTool.groovy)"
                    value={addScript}
                    onChange={(ev) => setAddScript(ev.target.value)}
                    fullWidth
                    size="small"
                    margin="normal"
                  />
                  <TextField
                    label="Description"
                    value={addDesc}
                    onChange={(ev) => setAddDesc(ev.target.value)}
                    fullWidth
                    size="small"
                    margin="normal"
                  />
                </>
              ) : null}
            </DialogContent>
            <DialogActions sx={{ flexShrink: 0 }}>
              <Button
                onClick={() => {
                  setAddDialogFullscreen(false);
                  setAddOpen(null);
                }}
              >
                Cancel
              </Button>
              <Button variant="contained" onClick={() => void runAddSubmit()}>
                Create
              </Button>
            </DialogActions>
          </Dialog>

          <Dialog
            open={mcpToolsDialog != null}
            onClose={() => !savingToolsPolicy && !listingMcpTools && setMcpToolsDialog(null)}
            maxWidth="md"
            fullWidth
          >
            <DialogTitle>
              {mcpToolsDialog?.purpose === 'listOnly' ? 'MCP tools from servers' : 'MCP tools to register'}
            </DialogTitle>
            <DialogContent dividers>
              {mcpToolsDialog ? (
                <Stack spacing={3}>
                  {mcpToolsDialog.purpose === 'listOnly' ? (
                    <Typography variant="body2" color="text.secondary" component="div">
                      {"Read-only preview from each server's "}
                      <code>tools/list</code>
                      {' (same wire names Studio uses in chat).'}
                    </Typography>
                  ) : mcpPreviewHasPickableTools(mcpToolsDialog.servers) ? (
                    <Typography variant="body2" color="text.secondary">
                      Unchecked tools are saved to <code>disabledMcpTools</code> in <code>{TOOLS_JSON_REL}</code>. Hide-list
                      entries that were not discovered in this run are kept.
                    </Typography>
                  ) : (
                    <Typography variant="body2" color="text.secondary" component="div">
                      {
                        "No tools were returned from any server, so per-tool enable/disable is not available. The table below shows each server's status. "
                      }
                      You can still <strong>Save</strong> to write the rest of this form unchanged, or <strong>Cancel</strong>.
                    </Typography>
                  )}
                  {mcpToolsDialog.servers.map((sv, svi) => {
                    const showPickUI =
                      mcpToolsDialog.purpose === 'pickForSave' && mcpPreviewHasPickableTools(mcpToolsDialog.servers);
                    return (
                      <Box key={`${sv.serverId}-${svi}`}>
                        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }} flexWrap="wrap" gap={1}>
                          <Typography variant="subtitle2">Server: {sv.serverId}</Typography>
                          {showPickUI && sv.ok ? (
                            <Stack direction="row" spacing={1}>
                              <Button size="small" onClick={() => setMcpServerToolsEnabled(sv.serverId, true)}>
                                Enable all
                              </Button>
                              <Button size="small" onClick={() => setMcpServerToolsEnabled(sv.serverId, false)}>
                                Disable all
                              </Button>
                            </Stack>
                          ) : null}
                        </Stack>
                        {sv.ok ? (
                          sv.tools.length === 0 ? (
                            <Typography variant="body2" color="text.secondary">
                              No tools returned by tools/list.
                            </Typography>
                          ) : (
                            <TableContainer sx={{ border: 1, borderColor: 'divider', borderRadius: 1 }}>
                              <Table size="small">
                                <TableHead>
                                  <TableRow>
                                    {showPickUI ? <TableCell padding="checkbox" /> : null}
                                    <TableCell>Wire name</TableCell>
                                    <TableCell>MCP tool</TableCell>
                                    <TableCell>Description</TableCell>
                                  </TableRow>
                                </TableHead>
                                <TableBody>
                                  {sv.tools.map((t) => (
                                    <TableRow key={t.wireName}>
                                      {showPickUI && mcpToolsDialog.purpose === 'pickForSave' ? (
                                        <TableCell padding="checkbox">
                                          <Checkbox
                                            checked={Boolean(mcpToolsDialog.selection[t.wireName])}
                                            onChange={() =>
                                              setMcpToolsDialog((d) => {
                                                if (!d || d.purpose !== 'pickForSave') return d;
                                                const cur = Boolean(d.selection[t.wireName]);
                                                return {
                                                  ...d,
                                                  selection: { ...d.selection, [t.wireName]: !cur }
                                                };
                                              })
                                            }
                                          />
                                        </TableCell>
                                      ) : null}
                                      <TableCell>
                                        <code>{t.wireName}</code>
                                      </TableCell>
                                      <TableCell>{t.mcpToolName}</TableCell>
                                      <TableCell sx={{ maxWidth: 360 }}>{t.description}</TableCell>
                                    </TableRow>
                                  ))}
                                </TableBody>
                              </Table>
                            </TableContainer>
                          )
                        ) : (
                          <Alert severity="error">{sv.message ?? 'Failed to reach MCP server.'}</Alert>
                        )}
                      </Box>
                    );
                  })}
                </Stack>
              ) : null}
            </DialogContent>
            <DialogActions>
              {mcpToolsDialog?.purpose === 'listOnly' ? (
                <Button onClick={() => setMcpToolsDialog(null)}>Close</Button>
              ) : (
                <>
                  <Button onClick={() => setMcpToolsDialog(null)} disabled={savingToolsPolicy}>
                    Cancel
                  </Button>
                  <Button variant="contained" onClick={() => void confirmMcpToolsDialogSave()} disabled={savingToolsPolicy}>
                    Save
                  </Button>
                </>
              )}
            </DialogActions>
          </Dialog>
        </>
      )}
    </Box>
  );
}
