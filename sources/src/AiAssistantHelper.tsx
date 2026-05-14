import * as React from 'react';
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { useDispatch, useSelector } from 'react-redux';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import ToolsPanelListItemButton from '@craftercms/studio-ui/components/ToolsPanelListItemButton';
import { batchActions } from '@craftercms/studio-ui/state/actions/misc';
import { pushIcePanelPage, setPreviewEditMode } from '@craftercms/studio-ui/state/actions/preview';
import { createToolsPanelPage, createWidgetDescriptor } from '@craftercms/studio-ui/utils/state';
import {
  Box,
  Dialog,
  DialogContent,
  IconButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Paper,
  Tooltip,
  Typography
} from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';
import RemoveRounded from '@mui/icons-material/RemoveRounded';
import OpenInBrowserRounded from '@mui/icons-material/OpenInBrowserRounded';
import AiAssistantIcon from './AiAssistant';
import AiAssistantChat from './AiAssistantChat';
import { AiAssistantIceChatShell } from './AiAssistantDialogContent';
import {
  DEFAULT_AGENTS,
  dedupeAgentsByStableKey,
  dropPlaceholderAgentsWhenRicherMatchesExist,
  getAgentsFromConfiguration,
  extractPositiveInt,
  mergeAgentsWithSiteUiXmlOverlay,
  normalizeEnabledBuiltInToolsRaw,
  readOptionalBooleanFromConfiguration,
  type AgentConfig,
  type ExpertSkillConfig
} from './agentConfig';
import { fetchSiteChatAgentsForOverlay } from './fetchAiAssistantUiAgents';
import { logoWidgetId } from './consts';
import { getAgentIcon } from './agentIcon';
import { helperWidgetId } from './consts';
import {
  effectiveStudioSiteId,
  getStudioUiConfigEpochSnapshot,
  subscribeStudioUiConfigChanged,
  syncReadStudioUiConfig
} from './aiAssistantStudioUiConfig';
import { dispatchPreviewToolbarUiXmlFixIfNeeded } from './aiAssistantPreviewToolbarUiXmlFix';

const DIALOG_WIDTH_STORAGE_KEY = 'aiassistant-dialog-width';
const DEFAULT_DIALOG_WIDTH = 480;
const MIN_DIALOG_WIDTH = 320;
const MAX_DIALOG_WIDTH = 900;


function getDialogWidthStorageKey(siteId: string, agentId: string): string {
  const site = (siteId || 'default').replace(/[^a-zA-Z0-9-_]/g, '_');
  const agent = (agentId || 'default').replace(/[^a-zA-Z0-9-_]/g, '_');
  return `${DIALOG_WIDTH_STORAGE_KEY}-${site}-${agent}`;
}

function getStoredDialogWidth(siteId: string, agentId: string): number | null {
  if (typeof localStorage === 'undefined') return null;
  try {
    const raw = localStorage.getItem(getDialogWidthStorageKey(siteId, agentId));
    if (raw == null) return null;
    const n = Number(raw);
    return Number.isFinite(n) && n >= MIN_DIALOG_WIDTH && n <= MAX_DIALOG_WIDTH ? n : null;
  } catch {
    return null;
  }
}

function setStoredDialogWidth(siteId: string, agentId: string, width: number): void {
  if (typeof localStorage === 'undefined') return;
  try {
    const w = Math.round(Math.max(MIN_DIALOG_WIDTH, Math.min(MAX_DIALOG_WIDTH, width)));
    localStorage.setItem(getDialogWidthStorageKey(siteId, agentId), String(w));
  } catch {
    // ignore
  }
}

export interface AiAssistantHelperProps {
  ui?: 'IconButton' | 'ListItemButton' | undefined;
  enableCustomModel?: string;
  configuration?: unknown;
  agents?: AgentConfig[];
  /**
   * When true (or `configuration.iceChatOnly`), render chat only for ICE tools panel embedding
   * via the same Helper widget id.
   */
  iceChatOnly?: boolean;
}

function readIceChatConfiguration(props: Readonly<AiAssistantHelperProps>): Record<string, unknown> | null {
  const fromNested =
    props.configuration != null && typeof props.configuration === 'object'
      ? (props.configuration as Record<string, unknown>)
      : null;
  const candidates = [fromNested, props as Record<string, unknown>].filter(Boolean) as Record<string, unknown>[];
  for (const o of candidates) {
    const v = o.iceChatOnly;
    if (v === true || String(v).toLowerCase() === 'true') return o;
  }
  return null;
}

/**
 * Studio may pass `ui` on the widget root or only under nested `configuration` (from merged `ui.xml`).
 */
function readHelperUi(props: Readonly<AiAssistantHelperProps>): 'IconButton' | 'ListItemButton' | undefined {
  const raw = props as Record<string, unknown>;
  const top = props.ui ?? raw['@_ui'];
  if (top === 'IconButton' || top === 'ListItemButton') return top;

  const walk = (o: unknown): 'IconButton' | 'ListItemButton' | undefined => {
    if (o == null || typeof o !== 'object') return undefined;
    const rec = o as Record<string, unknown>;
    const u = rec.ui ?? rec['@_ui'];
    if (u === 'IconButton' || u === 'ListItemButton') return u;
    return walk(rec.configuration);
  };
  return walk(props.configuration);
}

export function AiAssistantHelper(props: Readonly<AiAssistantHelperProps>) {
  const dispatch = useDispatch();
  const studioUiXml = useSelector((state: { uiConfig?: { xml?: string | null } }) => state.uiConfig?.xml ?? null);
  const { configuration, agents: agentsProp } = props;
  const ui = readHelperUi(props) ?? 'ListItemButton';
  const iceChatCfg = useMemo(() => readIceChatConfiguration(props), [configuration, props]);
  const activeSiteId = useActiveSiteId();
  const studioUiSiteKey = useMemo(() => effectiveStudioSiteId(activeSiteId), [activeSiteId]);
  const siteId = studioUiSiteKey || 'default';
  const subscribeUi = useCallback(
    (onStoreChange: () => void) => subscribeStudioUiConfigChanged(studioUiSiteKey, onStoreChange),
    [studioUiSiteKey]
  );
  const studioUiEpoch = useSyncExternalStore(
    subscribeUi,
    () => getStudioUiConfigEpochSnapshot(studioUiSiteKey),
    () => 0
  );
  const showAiInTopNav = useMemo(
    () => syncReadStudioUiConfig(studioUiSiteKey).showAiAssistantsInTopNavigation !== false,
    [studioUiSiteKey, studioUiEpoch]
  );
  useEffect(() => {
    dispatchPreviewToolbarUiXmlFixIfNeeded(studioUiXml, dispatch);
  }, [studioUiXml, dispatch]);
  const [siteChatOverlay, setSiteChatOverlay] = useState<{
    agents: AgentConfig[];
    exclusive: boolean;
  } | null>(null);
  useEffect(() => {
    if (!studioUiSiteKey || iceChatCfg) {
      setSiteChatOverlay(null);
      return;
    }
    let cancelled = false;
    fetchSiteChatAgentsForOverlay(studioUiSiteKey)
      .then((r) => {
        if (!cancelled) setSiteChatOverlay(r.agents.length || r.exclusive ? r : null);
      })
      .catch(() => {
        if (!cancelled) setSiteChatOverlay(null);
      });
    return () => {
      cancelled = true;
    };
  }, [studioUiSiteKey, iceChatCfg]);

  const agents = useMemo(() => {
    let base: AgentConfig[];
    if (Array.isArray(agentsProp) && agentsProp.length > 0) base = agentsProp;
    else {
      const agentsFromConfig = getAgentsFromConfiguration(props);
      if (agentsFromConfig.length > 0) base = agentsFromConfig;
      else if (configuration != null) {
        const fromConfig = getAgentsFromConfiguration(configuration);
        base = fromConfig.length > 0 ? fromConfig : DEFAULT_AGENTS;
      } else base = DEFAULT_AGENTS;
    }
    let merged: AgentConfig[];
    if (siteChatOverlay?.exclusive) {
      merged = siteChatOverlay.agents.length ? siteChatOverlay.agents : DEFAULT_AGENTS;
    } else {
      merged = siteChatOverlay?.agents?.length ? mergeAgentsWithSiteUiXmlOverlay(base, siteChatOverlay.agents) : base;
    }
    merged = dedupeAgentsByStableKey(merged);
    merged = dropPlaceholderAgentsWhenRicherMatchesExist(merged);
    return merged;
  }, [configuration, agentsProp, props, siteChatOverlay]);

  type OpenDialog = { id: string; agent: AgentConfig; minimized: boolean; width: number };
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [openDialogs, setOpenDialogs] = useState<OpenDialog[]>([]);

  const openAgentInExperienceBuilderPanel = (agent: AgentConfig) => {
    setMenuOpen(false);
    setMenuAnchor(null);
    const effectiveId = agent?.id?.trim() || '';
    const resolved = { ...agent, id: effectiveId };
    dispatch(
      batchActions([
        setPreviewEditMode({ editMode: true }),
        pushIcePanelPage(
          createToolsPanelPage(
            resolved.label,
            [
              createWidgetDescriptor({
                id: helperWidgetId,
                configuration: {
                  iceChatOnly: true,
                  agentId: effectiveId,
                  llm: resolved.llm,
                  llmModel: resolved.llmModel,
                  imageModel: resolved.imageModel,
                  imageGenerator: resolved.imageGenerator,
                  openAiApiKey: resolved.openAiApiKey,
                  prompts: resolved.prompts,
                  ...(resolved.enableTools !== undefined ? { enableTools: resolved.enableTools } : {}),
                  ...(Array.isArray(resolved.enabledBuiltInTools) && resolved.enabledBuiltInTools.length > 0
                    ? { enabledBuiltInTools: resolved.enabledBuiltInTools }
                    : {})
                }
              })
            ],
            'icePanel'
          )
        )
      ])
    );
  };

  const agentOpensAsPopup = (agent: AgentConfig): boolean => {
    const v = agent.openAsPopup as unknown;
    if (v === true) return true;
    if (typeof v === 'string') {
      const s = v.trim().toLowerCase();
      return s === 'true' || s === '1' || s === 'yes';
    }
    return false;
  };

  const openAgent = (agent: AgentConfig) => {
    if (agentOpensAsPopup(agent)) {
      setMenuOpen(false);
      setMenuAnchor(null);
      const effectiveId = agent?.id?.trim() || '';
      const width = getStoredDialogWidth(siteId, effectiveId) ?? DEFAULT_DIALOG_WIDTH;
      setOpenDialogs((prev) => [
        ...prev,
        {
          id: `aiassistant-dialog-${Date.now()}-${Math.random().toString(36).slice(2)}`,
          agent: { ...agent, id: effectiveId },
          minimized: false,
          width
        }
      ]);
    } else {
      openAgentInExperienceBuilderPanel(agent);
    }
  };

  const [resizeState, setResizeState] = useState<{
    dialogId: string;
    agentId: string;
    startX: number;
    startWidth: number;
  } | null>(null);
  const resizeStateRef = useRef(resizeState);
  resizeStateRef.current = resizeState;
  const lastWidthRef = useRef<number>(DEFAULT_DIALOG_WIDTH);

  useEffect(() => {
    if (!resizeState) return;
    const onMove = (e: MouseEvent) => {
      const s = resizeStateRef.current;
      if (!s) return;
      const delta = s.startX - e.clientX;
      const newWidth = Math.round(Math.max(MIN_DIALOG_WIDTH, Math.min(MAX_DIALOG_WIDTH, s.startWidth + delta)));
      lastWidthRef.current = newWidth;
      setOpenDialogs((prev) =>
        prev.map((d) => (d.id !== s.dialogId ? d : { ...d, width: newWidth }))
      );
    };
    const onUp = () => {
      const s = resizeStateRef.current;
      if (s) setStoredDialogWidth(siteId, s.agentId, lastWidthRef.current);
      setResizeState(null);
      document.body.style.removeProperty('user-select');
      document.body.style.removeProperty('cursor');
    };
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    return () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.removeProperty('user-select');
      document.body.style.removeProperty('cursor');
    };
  }, [resizeState?.dialogId, siteId]);

  const closeDialog = (id: string) => {
    setOpenDialogs((prev) => prev.filter((d) => d.id !== id));
  };

  const setDialogMinimized = (id: string, minimized: boolean) => {
    setOpenDialogs((prev) => prev.map((d) => (d.id === id ? { ...d, minimized } : d)));
  };

  const handleToolbarClick = (e: React.MouseEvent<HTMLElement>) => {
    const list = agents.length > 0 ? agents : DEFAULT_AGENTS;
    if (list.length === 0) return;
    if (list.length === 1) {
      openAgent(list[0]);
    } else {
      setMenuAnchor(e.currentTarget);
      setMenuOpen(true);
    }
  };

  const handleMenuClose = () => {
    setMenuOpen(false);
    setMenuAnchor(null);
  };

  const agentKey = (agent: AgentConfig) => `${agent.id}|${agent.label}`;

  const toolbarList = agents.length > 0 ? agents : DEFAULT_AGENTS;
  const primaryAgent = toolbarList[0];

  if (iceChatCfg) {
    const agentId = String(iceChatCfg.agentId ?? '').trim();
    const llm = iceChatCfg.llm as string | undefined;
    const iceRaw = iceChatCfg as Record<string, unknown>;
    const llmModel = (iceRaw.llmModel as string | undefined)?.trim();
    const imageModel = iceChatCfg.imageModel as string | undefined;
    const imageGenerator = (iceRaw.imageGenerator as string | undefined)?.trim();
    const openAiApiKey = iceChatCfg.openAiApiKey as string | undefined;
    const configPrompts = Array.isArray(iceChatCfg.prompts)
      ? (iceChatCfg.prompts as Array<{ userText: string; additionalContext?: string }>)
      : undefined;
    const iceEnableTools = readOptionalBooleanFromConfiguration(iceChatCfg, 'enableTools', 'enable_tools');
    const iceEnabledBuiltIn = normalizeEnabledBuiltInToolsRaw(iceRaw.enabledBuiltInTools);
    const iceExpertSkills = Array.isArray(iceChatCfg.expertSkills)
      ? (iceChatCfg.expertSkills as ExpertSkillConfig[])
      : undefined;
    const iceTranslateBatch = extractPositiveInt(iceRaw as Record<string, unknown>, 1, 64, 'translateBatchConcurrency', 'translate_batch_concurrency');
    const iceBearerEnv = (iceRaw.crafterQBearerTokenEnv as string | undefined)?.trim();
    const iceBearerTok = (iceRaw.crafterQBearerToken as string | undefined)?.trim();
    return (
      <AiAssistantIceChatShell>
        <AiAssistantChat
          agentId={agentId}
          llm={llm}
          llmModel={llmModel || undefined}
          imageModel={imageModel}
          imageGenerator={imageGenerator || undefined}
          openAiApiKey={openAiApiKey}
          enableTools={iceEnableTools}
          enabledBuiltInTools={iceEnabledBuiltIn}
          expertSkills={iceExpertSkills}
          configPrompts={configPrompts}
          embedTarget="icePanel"
          {...(iceTranslateBatch != null ? { translateBatchConcurrency: iceTranslateBatch } : {})}
          {...(iceBearerEnv ? { crafterQBearerTokenEnv: iceBearerEnv } : {})}
          {...(iceBearerTok ? { crafterQBearerToken: iceBearerTok } : {})}
        />
      </AiAssistantIceChatShell>
    );
  }

  return (
    <>
      {ui === 'IconButton' ? (
        showAiInTopNav ? (
          <Tooltip title={primaryAgent?.label ?? 'Studio AI Assistant'}>
            <IconButton
              onClick={handleToolbarClick}
              aria-haspopup={toolbarList.length > 1 ? 'menu' : undefined}
              aria-expanded={toolbarList.length > 1 ? menuOpen : undefined}
            >
              {getAgentIcon(primaryAgent?.icon)}
            </IconButton>
          </Tooltip>
        ) : null
      ) : (
        <ToolsPanelListItemButton
          icon={{ id: logoWidgetId }}
          title={primaryAgent?.label ?? 'Studio AI Assistant'}
          onClick={handleToolbarClick}
        />
      )}
      {menuAnchor && (
        <Menu
          open
          anchorEl={menuAnchor}
          onClose={handleMenuClose}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
          transformOrigin={{ vertical: 'top', horizontal: 'right' }}
          disableAutoFocusItem
          TransitionProps={{ timeout: 0 }}
        >
          {toolbarList.map((agent) => (
            <MenuItem
              key={agentKey(agent)}
              onClick={() => {
                openAgent(agent);
              }}
            >
              <ListItemIcon>{getAgentIcon(agent.icon)}</ListItemIcon>
              <ListItemText primary={agent.label} />
            </MenuItem>
          ))}
        </Menu>
      )}

      {typeof document !== 'undefined' &&
        createPortal(
          <>
            {openDialogs
              .filter((d) => !d.minimized)
              .map((d) => (
                <Dialog
                  key={d.id}
                  open
                  onClose={() => closeDialog(d.id)}
                  maxWidth={false}
                  PaperProps={{
                    sx: {
                      position: 'fixed',
                      right: 16,
                      bottom: 16,
                      top: 'auto',
                      left: 'auto',
                      margin: 0,
                      width: d.width,
                      minWidth: MIN_DIALOG_WIDTH,
                      maxWidth: MAX_DIALOG_WIDTH,
                      maxHeight: 'calc(100vh - 32px)',
                      minHeight: 400,
                      zIndex: 1300,
                      display: 'flex',
                      flexDirection: 'column',
                      overflow: 'hidden'
                    }
                  }}
                >
                  <Box sx={{ position: 'relative' }}>
                    <Box
                      aria-label="Resize dialog"
                      role="separator"
                      onMouseDown={(e) => {
                        e.preventDefault();
                        lastWidthRef.current = d.width;
                        setResizeState({
                          dialogId: d.id,
                          agentId: d.agent.id,
                          startX: e.clientX,
                          startWidth: d.width
                        });
                      }}
                      sx={{
                        position: 'absolute',
                        left: 0,
                        top: 0,
                        bottom: 0,
                        width: 6,
                        cursor: 'col-resize',
                        zIndex: 1,
                        '&:hover': { bgcolor: 'action.hover' }
                      }}
                    />
                    <Box sx={{ display: 'flex', alignItems: 'center', px: 2, py: 1, borderBottom: 1, borderColor: 'divider' }}>
                    <Typography variant="h6" component="span" sx={{ flex: 1 }}>
                      {d.agent.label}
                    </Typography>
                    <Tooltip title="Minimize">
                      <IconButton aria-label="Minimize" onClick={() => setDialogMinimized(d.id, true)} size="small">
                        <RemoveRounded />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title="Close">
                      <IconButton aria-label="Close" onClick={() => closeDialog(d.id)} size="small">
                        <CloseRounded />
                      </IconButton>
                    </Tooltip>
                  </Box>
                  </Box>
                  <DialogContent
                    sx={{
                      p: 0,
                      display: 'flex',
                      flexDirection: 'column',
                      flex: '1 1 auto',
                      minHeight: 0,
                      overflow: 'hidden'
                    }}
                  >
                    <Box sx={{ flex: 1, minHeight: 0, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
                      <AiAssistantChat
                        agentId={d.agent.id}
                        llm={d.agent.llm}
                        llmModel={d.agent.llmModel}
                        imageModel={d.agent.imageModel}
                        imageGenerator={d.agent.imageGenerator}
                        openAiApiKey={d.agent.openAiApiKey}
                        enableTools={d.agent.enableTools}
                        enabledBuiltInTools={d.agent.enabledBuiltInTools}
                        expertSkills={d.agent.expertSkills}
                        configPrompts={d.agent.prompts}
                        {...(d.agent.translateBatchConcurrency != null
                          ? { translateBatchConcurrency: d.agent.translateBatchConcurrency }
                          : {})}
                        {...(d.agent.crafterQBearerTokenEnv?.trim()
                          ? { crafterQBearerTokenEnv: d.agent.crafterQBearerTokenEnv.trim() }
                          : {})}
                        {...(d.agent.crafterQBearerToken?.trim()
                          ? { crafterQBearerToken: d.agent.crafterQBearerToken.trim() }
                          : {})}
                      />
                    </Box>
                  </DialogContent>
                </Dialog>
              ))}
            {openDialogs
              .filter((d) => d.minimized)
              .map((d, i) => (
                <Paper
                  key={d.id}
                  elevation={4}
                  sx={{
                    position: 'fixed',
                    bottom: 16 + i * 56,
                    right: 16,
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    px: 2,
                    py: 1.5,
                    zIndex: 1300,
                    border: '1px solid',
                    borderColor: 'divider'
                  }}
                >
                  <Typography variant="body1">{d.agent.label}</Typography>
                  <Tooltip title="Restore">
                    <IconButton aria-label="Restore" onClick={() => setDialogMinimized(d.id, false)} size="small">
                      <OpenInBrowserRounded />
                    </IconButton>
                  </Tooltip>
                  <Tooltip title="Close">
                    <IconButton aria-label="Close" onClick={() => closeDialog(d.id)} size="small">
                      <CloseRounded />
                    </IconButton>
                  </Tooltip>
                </Paper>
              ))}
          </>,
          document.body
        )}
    </>
  );
}

export default AiAssistantHelper;
