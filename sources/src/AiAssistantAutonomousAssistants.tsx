import * as React from 'react';
import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import { firstValueFrom } from 'rxjs';
import { createPortal } from 'react-dom';
import useActiveSiteId from '@craftercms/studio-ui/hooks/useActiveSiteId';
import useActiveUser from '@craftercms/studio-ui/hooks/useActiveUser';
import { usePossibleTranslation } from '@craftercms/studio-ui/hooks/usePossibleTranslation';
import SystemIcon from '@craftercms/studio-ui/components/SystemIcon';
import type { SystemIconDescriptor } from '@craftercms/studio-ui/components/SystemIcon';
import type { User } from '@craftercms/studio-ui/models/User';
import { fetchAll as fetchAllStudioUsers } from '@craftercms/studio-ui/services/users';
import { getInitials, toColor } from '@craftercms/studio-ui/utils/string';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CloseIcon from '@mui/icons-material/Close';
import CloseRounded from '@mui/icons-material/CloseRounded';
import OpenInBrowserRounded from '@mui/icons-material/OpenInBrowserRounded';
import RemoveRounded from '@mui/icons-material/RemoveRounded';
import ChevronRightRounded from '@mui/icons-material/ChevronRightRounded';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import InfoOutlined from '@mui/icons-material/InfoOutlined';
import {
  Alert,
  Avatar,
  Badge,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  GlobalStyles,
  IconButton,
  InputLabel,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography
} from '@mui/material';
import type { Theme } from '@mui/material/styles';
import AutonomousAgentsMarkIcon from './autonomousAgentsMarkIcon';
import {
  getAutonomousAgentsFromConfiguration,
  mergeAutonomousAgentsForTable,
  mergeAutonomousWidgetProps,
  type AutonomousMergeViewer,
  type AutonomousTableAgentRow
} from './autonomousAssistantsConfig';
import { catalogAutonomousAgents, fetchCentralAgentsFile, type CentralAgentsFile } from './centralAgentCatalog';
import { autonomousAgentsMarkWidgetId } from './consts';
import {
  getAutonomousAssistantsStatus,
  postAutonomousAssistantsControl,
  syncAutonomousAssistants
} from './autonomousApi';
import {
  effectiveStudioSiteId,
  getStudioUiConfigEpochSnapshot,
  subscribeStudioUiConfigChanged,
  syncReadStudioUiConfig
} from './aiAssistantStudioUiConfig';

export interface AiAssistantAutonomousAssistantsProps {
  configuration?: unknown;
  /** When Studio maps widget XML onto props, `autonomousAgents` may appear here (sibling of `configuration`). */
  autonomousAgents?: unknown;
}

type HumanTaskRow = {
  id?: string;
  title?: string;
  prompt?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  /** Full agent id that owns this task; defaults to the agent whose state stores the row. */
  ownerAgentId?: string;
  /** Short display label for {@link ownerAgentId} (set by server). */
  ownerName?: string;
  /** Studio login assigned via {@code assign_human_task}. */
  assignedUsername?: string;
  /** Display label for {@link assignedUsername} (set by server / client). */
  assignedName?: string;
};

/** Human task plus which agent state row holds it (required for REST control). */
type FlatHumanTaskRow = HumanTaskRow & {
  storageAgentId: string;
  storageAgentLabel: string;
};

const TASK_ASSIGN_FILTER_ALL = 'all';
const TASK_ASSIGN_FILTER_UNASSIGNED = 'unassigned';
const TASK_ASSIGN_FILTER_ME = 'me';
const TASK_USER_FILTER_PREFIX = 'u:';

function formatStudioUserLabel(u: Pick<User, 'username' | 'firstName' | 'lastName'>): string {
  const n = [u.firstName, u.lastName].filter(Boolean).join(' ').trim();
  return n ? `${n} (${u.username})` : u.username;
}

/** Studio-style initials avatar (matches dashlet user chips; optional photo URL when API provides it). */
function HumanTaskAssigneeAvatar(props: {
  readonly username: string;
  readonly firstName?: string;
  readonly lastName?: string;
  readonly avatarUrl?: string;
}) {
  const { username, firstName = '', lastName = '', avatarUrl } = props;
  const trimmedUrl = avatarUrl?.trim();
  const person = { username, firstName, lastName, avatar: '' };
  const initials = getInitials(person);
  const bg = toColor(username);
  return (
    <Avatar
      src={trimmedUrl || undefined}
      alt=""
      sx={{
        width: 32,
        height: 32,
        fontSize: '0.8125rem',
        flexShrink: 0,
        ...(trimmedUrl
          ? {}
          : {
              bgcolor: bg,
              color: (theme: Theme) => theme.palette.getContrastText(bg)
            })
      }}
    >
      {trimmedUrl ? undefined : initials}
    </Avatar>
  );
}

type AgentLastError = {
  message?: string;
  at?: string;
  exceptionClass?: string;
  stackTrace?: string;
  stopOnFailure?: boolean;
};

type AgentErrorRow = {
  agentId: string;
  name?: string;
  lastError?: AgentLastError | Record<string, unknown>;
};

function lastErrorDetailStack(props: { readonly le: AgentLastError | undefined }) {
  const { le } = props;
  if (!le?.message) return null;
  return (
    <Stack spacing={0.75} sx={{ minWidth: 0, alignItems: 'stretch' }}>
      <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
        {le.message}
      </Typography>
      {le.at ? (
        <Typography variant="caption" color="text.secondary">
          {le.at}
        </Typography>
      ) : null}
      {le.exceptionClass ? (
        <Typography variant="caption" color="text.secondary" component="div">
          {le.exceptionClass}
        </Typography>
      ) : null}
      {le.stackTrace ? (
        <Box
          sx={{
            maxHeight: 200,
            overflow: 'auto',
            mt: 0.5,
            p: 1,
            bgcolor: 'action.hover',
            borderRadius: 1,
            border: 1,
            borderColor: 'divider'
          }}
        >
          <Typography
            component="pre"
            variant="caption"
            sx={{ whiteSpace: 'pre-wrap', m: 0, fontFamily: 'ui-monospace, monospace', fontSize: '0.7rem' }}
          >
            {le.stackTrace}
          </Typography>
        </Box>
      ) : null}
    </Stack>
  );
}

type StatusAgentRow = AutonomousTableAgentRow;

type StatusPayload = {
  ok?: boolean;
  agents?: StatusAgentRow[];
  supervisor?: Record<string, unknown>;
  openHumanTaskCount?: number;
  agentsInError?: AgentErrorRow[];
  hasAgentError?: boolean;
};

function humanTaskOwnerAgentId(task: HumanTaskRow, fallbackAgentId: string): string {
  const o = String(task.ownerAgentId ?? '').trim();
  return o || fallbackAgentId;
}

function humanTaskOwnerLabel(task: HumanTaskRow, fallbackAgentId: string): string {
  const name = String(task.ownerName ?? '').trim();
  if (name) return name;
  const id = humanTaskOwnerAgentId(task, fallbackAgentId);
  const i = id.lastIndexOf('-');
  return i >= 0 ? id.slice(i + 1) : id;
}

function countOpenTasksForAgent(state: Record<string, unknown> | undefined, agentId: string): number {
  const raw = state?.humanTasks;
  if (!Array.isArray(raw)) return 0;
  let n = 0;
  for (const t of raw) {
    if (!t || typeof t !== 'object') continue;
    const row = t as HumanTaskRow;
    const owner = String(row.ownerAgentId ?? '').trim();
    if (owner && owner !== agentId) continue;
    const st = String(row.status ?? 'open').toLowerCase();
    if (st !== 'dismissed' && st !== 'done') n++;
  }
  return n;
}

function humanTaskRowActionsDisabled(
  baseDisabled: boolean,
  task: HumanTaskRow,
  panelAgentId: string,
  manageOtherAgentsHumanTasks: boolean
): boolean {
  if (baseDisabled) return true;
  const owner = String(task.ownerAgentId ?? '').trim();
  if (!owner || owner === panelAgentId) return false;
  return !manageOtherAgentsHumanTasks;
}

type AgentLastActivity = { whenLine: string; statusLine: string };

/** Last run time + one-line outcome for the agents table (uses server state fields). */
function formatAgentLastActivity(
  state: Record<string, unknown> | undefined,
  synthetic: boolean
): AgentLastActivity {
  if (synthetic) {
    return { whenLine: '—', statusLine: 'Not linked' };
  }
  const lifecycle = String(state?.status ?? '').trim().toLowerCase();

  let whenMs: number | null = null;
  const isoRaw = state?.lastRunIso;
  if (typeof isoRaw === 'string' && isoRaw.trim()) {
    const t = Date.parse(isoRaw.trim());
    if (!Number.isNaN(t)) whenMs = t;
  }
  const lr = state?.lastRunMillis;
  if (whenMs == null && typeof lr === 'number' && Number.isFinite(lr) && lr > 0) {
    whenMs = lr;
  }

  const whenLine =
    whenMs != null
      ? new Date(whenMs).toLocaleString(undefined, {
          month: 'short',
          day: 'numeric',
          year: 'numeric',
          hour: 'numeric',
          minute: '2-digit'
        })
      : '—';

  let statusLine = 'No runs yet';
  if (lifecycle === 'error') {
    statusLine = 'Last run failed';
  } else if (lifecycle === 'waiting') {
    const le = state?.lastError as AgentLastError | undefined;
    if (le?.message) {
      statusLine = 'Retry scheduled after failure';
    } else if (whenMs != null) {
      const hist = state?.executionHistory;
      if (Array.isArray(hist) && hist.length > 0) {
        const last = hist[hist.length - 1] as Record<string, unknown>;
        const next = last?.next;
        const wantsFollowUp = next === true || String(next).toLowerCase() === 'true';
        statusLine = wantsFollowUp ? 'Follow-up due' : 'Completed';
      } else {
        statusLine = 'Completed';
      }
    }
  } else if (lifecycle === 'running') {
    statusLine = 'Run in progress';
  } else if (whenMs != null) {
    const hist = state?.executionHistory;
    if (Array.isArray(hist) && hist.length > 0) {
      const last = hist[hist.length - 1] as Record<string, unknown>;
      const next = last?.next;
      const wantsFollowUp = next === true || String(next).toLowerCase() === 'true';
      statusLine = wantsFollowUp ? 'Follow-up due' : 'Completed';
    } else {
      statusLine = 'Completed';
    }
  }

  return { whenLine, statusLine };
}

/** Studio / Groovy may serialize booleans as strings; avoid `Boolean("false") === true`. */
function parseJsonBoolean(v: unknown): boolean {
  if (v === true || v === 1) return true;
  if (v === false || v === 0) return false;
  if (typeof v === 'string') {
    const s = v.trim().toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no' || s === '') return false;
  }
  return Boolean(v);
}

/** Per-agent `startAutomatically` from ui.xml / registry; defaults to true when omitted. */
function definitionStartAutomatically(def: unknown): boolean {
  if (def == null || typeof def !== 'object') return true;
  const o = def as Record<string, unknown>;
  const v = o.startAutomatically ?? o.start_automatically ?? o.automaticallyStart ?? o.automatically_start;
  if (v === false || v === 0) return false;
  if (typeof v === 'string' && ['false', '0', 'no'].includes(v.trim().toLowerCase())) return false;
  return true;
}

/** Per-agent `stopOnFailure` from ui.xml / registry; defaults to true when omitted. */
function definitionStopOnFailure(def: unknown): boolean {
  if (def == null || typeof def !== 'object') return true;
  const o = def as Record<string, unknown>;
  const v = o.stopOnFailure ?? o.stop_on_failure;
  if (v === false || v === 0) return false;
  if (typeof v === 'string' && ['false', '0', 'no'].includes(v.trim().toLowerCase())) return false;
  return true;
}

function scalarForAgentDetails(v: unknown): string {
  if (v == null) return '—';
  const s = typeof v === 'string' ? v.trim() : String(v).trim();
  return s || '—';
}

function AgentConfigurationDetailsContent(props: { readonly row: AutonomousTableAgentRow }) {
  const { row } = props;
  const d = (row.definition ?? {}) as Record<string, unknown>;
  const promptText = typeof d.prompt === 'string' ? d.prompt : '';
  const apiRaw = d.llmApiKey;
  const apiKeyInConfig =
    (typeof apiRaw === 'string' && apiRaw.trim().length > 0) ||
    (apiRaw != null && typeof apiRaw !== 'string' && String(apiRaw).trim().length > 0);

  const field = (label: string, value: string) => (
    <Box key={label} sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.25 }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ wordBreak: 'break-word', whiteSpace: 'pre-wrap' }}>
        {value}
      </Typography>
    </Box>
  );

  return (
    <Stack spacing={2} sx={{ minWidth: 0 }}>
      {field('Agent id', row.agentId)}
      {field('Name', scalarForAgentDetails(d.name ?? d.label))}
      {field('Schedule', scalarForAgentDetails(d.schedule))}
      {field('Scope', scalarForAgentDetails(d.scope))}
      {field('Scope id', scalarForAgentDetails(d.scopeId))}
      {field('LLM', scalarForAgentDetails(d.llm))}
      {field('LLM model', scalarForAgentDetails(d.llmModel))}
      {field('Image model', scalarForAgentDetails(d.imageModel))}
      {field('Image generator', scalarForAgentDetails(d.imageGenerator))}
      {field('Start automatically', definitionStartAutomatically(d) ? 'Yes' : 'No')}
      {field('Stop on failure', definitionStopOnFailure(d) ? 'Yes' : 'No')}
      {field(
        'Expert skills (markdown URLs)',
        Array.isArray(d.expertSkills) && d.expertSkills.length > 0 ? String(d.expertSkills.length) : '—'
      )}
      {field("Manage other agents' human tasks", parseJsonBoolean(d.manageOtherAgentsHumanTasks) ? 'Yes' : 'No')}
      {field('Per-agent OpenAI API key in config', apiKeyInConfig ? 'Set (hidden)' : '—')}
      {row.syntheticFromConfig ? (
        <Alert severity="info" sx={{ py: 0.75 }}>
          This row reflects site UI configuration only. Use Sync so the server registers the agent and returns the
          canonical definition.
        </Alert>
      ) : null}
      <Divider />
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5 }}>
          Prompt
        </Typography>
        <Box
          sx={{
            maxHeight: 280,
            overflow: 'auto',
            p: 1.5,
            borderRadius: 1,
            border: 1,
            borderColor: 'divider',
            bgcolor: (theme) => (theme.palette.mode === 'dark' ? 'grey.900' : 'grey.50')
          }}
        >
          <Typography
            variant="body2"
            component="pre"
            sx={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', m: 0, wordBreak: 'break-word' }}
          >
            {promptText.trim() ? promptText : '—'}
          </Typography>
        </Box>
      </Box>
      {(row.state?.lastError as AgentLastError | undefined)?.message ? (
        <>
          <Divider />
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5 }}>
            Last run failure (live state)
          </Typography>
          {lastErrorDetailStack({ le: row.state?.lastError as AgentLastError | undefined })}
        </>
      ) : null}
    </Stack>
  );
}

function AutonomousAgentStatusChip(props: { readonly status: string; readonly synthetic?: boolean }) {
  if (props.synthetic) {
    return <Chip size="small" label="Not linked yet" color="warning" variant="outlined" />;
  }
  const raw = (props.status ?? '—').trim();
  const s = raw.toLowerCase();
  let color: 'default' | 'error' | 'info' | 'success' | 'warning' = 'default';
  if (s === 'error') color = 'error';
  else if (s === 'running' || s === 'waiting') color = 'success';
  else if (s === 'pending') color = 'info';
  else if (s === 'stopped' || s === 'disabled') color = 'warning';
  return <Chip size="small" label={raw || '—'} color={color} variant="outlined" />;
}

function widgetTitleText(merged: Record<string, unknown>): string {
  const t = merged.title;
  if (t == null) return 'Autonomous Agents';
  if (typeof t === 'string') return t.trim() || 'Autonomous Agents';
  if (typeof t === 'object' && t !== null && '#text' in (t as object)) {
    const v = (t as { '#text': unknown })['#text'];
    return String(v ?? '').trim() || 'Autonomous Agents';
  }
  return 'Autonomous Agents';
}

/** Same `icon` shape as {@link ToolsPanelListItemButton} / {@link SystemIcon}; YAML `<icon>` with SVG string is ignored here. */
function systemIconDescriptorFromWidgetMerged(merged: Record<string, unknown>): SystemIconDescriptor {
  const raw = merged.icon;
  if (raw && typeof raw === 'object' && raw !== null && 'id' in raw) {
    const id = (raw as { id: unknown }).id;
    if (typeof id === 'string' && id.trim()) return { id: id.trim() };
  }
  return { id: autonomousAgentsMarkWidgetId };
}

function AiAssistantAutonomousAssistantsImpl(props: Readonly<AiAssistantAutonomousAssistantsProps>) {
  const activeSiteId = useActiveSiteId();
  const siteId = activeSiteId ?? '';
  const activeUser = useActiveUser();
  const merged = useMemo(() => mergeAutonomousWidgetProps(props as Record<string, unknown>), [props]);
  const [centralAgentsFile, setCentralAgentsFile] = useState<CentralAgentsFile | null | undefined>(undefined);
  useEffect(() => {
    if (!siteId) {
      setCentralAgentsFile(undefined);
      return;
    }
    let cancelled = false;
    fetchCentralAgentsFile(siteId)
      .then((f) => {
        if (!cancelled) setCentralAgentsFile(f);
      })
      .catch(() => {
        if (!cancelled) setCentralAgentsFile(null);
      });
    return () => {
      cancelled = true;
    };
  }, [siteId]);

  const defs = useMemo(() => {
    if (centralAgentsFile && centralAgentsFile.agents.length > 0) {
      const fromCentral = catalogAutonomousAgents(centralAgentsFile);
      if (fromCentral.length) return fromCentral;
    }
    return getAutonomousAgentsFromConfiguration(merged);
  }, [centralAgentsFile, merged]);
  const listTitle = useMemo(() => widgetTitleText(merged), [merged]);
  const listTitleTranslated = usePossibleTranslation(listTitle);
  const toolsListSystemIcon = useMemo(() => systemIconDescriptorFromWidgetMerged(merged), [merged]);
  const [dialogOpen, setDialogOpen] = useState(false);
  /** When true, the main dialog is hidden and a bottom strip is shown (like legacy Helper popups). */
  const [dialogMinimized, setDialogMinimized] = useState(false);
  const [status, setStatus] = useState<StatusPayload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<string>('');
  const [copyHint, setCopyHint] = useState<string | null>(null);
  const [nameFilter, setNameFilter] = useState('');
  const [taskAgentFilter, setTaskAgentFilter] = useState('');
  const [taskAssignFilter, setTaskAssignFilter] = useState(TASK_ASSIGN_FILTER_ALL);
  const [studioUserOptions, setStudioUserOptions] = useState<User[]>([]);
  /** When set, a read-only configuration dialog is open for that agent id. */
  const [configDetailsAgentId, setConfigDetailsAgentId] = useState<string | null>(null);

  useEffect(() => {
    if (!dialogOpen) {
      setConfigDetailsAgentId(null);
    }
  }, [dialogOpen]);

  const mergeViewer = useMemo((): AutonomousMergeViewer => {
    const username = (activeUser?.username ?? '').trim() || 'anonymous';
    let roleScopeId = username;
    const roles = siteId ? activeUser?.rolesBySite?.[siteId] : undefined;
    if (Array.isArray(roles)) {
      for (const r of roles) {
        const raw = String(r ?? '').trim();
        if (!raw) continue;
        roleScopeId = raw.replace(/^ROLE_/i, '');
        break;
      }
    }
    return { username, roleScopeId };
  }, [activeUser?.username, activeUser?.rolesBySite, siteId]);

  const mergedAgents = useMemo(
    () =>
      mergeAutonomousAgentsForTable(
        siteId,
        defs,
        status?.agents as AutonomousTableAgentRow[] | undefined,
        mergeViewer
      ),
    [siteId, defs, status?.agents, mergeViewer]
  );

  const configDetailsRow = useMemo(
    () =>
      configDetailsAgentId ? mergedAgents.find((a) => a.agentId === configDetailsAgentId) ?? null : null,
    [configDetailsAgentId, mergedAgents]
  );

  const filteredAgents = useMemo(() => {
    const q = nameFilter.trim().toLowerCase();
    if (!q) return mergedAgents;
    return mergedAgents.filter((a) => {
      const label = String((a.definition?.name as string) || a.agentId).toLowerCase();
      return label.includes(q);
    });
  }, [mergedAgents, nameFilter]);

  const refresh = useCallback(async () => {
    if (!siteId) return;
    try {
      const s = (await getAutonomousAssistantsStatus(siteId)) as StatusPayload;
      setStatus(s);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [siteId]);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!siteId || defs.length === 0) {
        setStatus(null);
        return;
      }
      setBusy(true);
      setError(null);
      try {
        const syn = await syncAutonomousAssistants(siteId, defs);
        if (syn.ok === false) {
          setError(syn.message ?? 'Sync failed');
        }
        if (!cancelled) {
          const st = (await getAutonomousAssistantsStatus(siteId)) as StatusPayload;
          setStatus(st);
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setBusy(false);
      }
    };
    void run();
    return () => {
      cancelled = true;
    };
  }, [siteId, defs]);

  useEffect(() => {
    if (!siteId) return;
    const ms = dialogOpen ? 3000 : 8000;
    const id = window.setInterval(() => {
      void refresh();
    }, ms);
    return () => window.clearInterval(id);
  }, [siteId, refresh, dialogOpen]);

  useEffect(() => {
    if (dialogOpen && siteId && defs.length > 0) {
      void refresh();
    }
  }, [dialogOpen, siteId, defs.length, refresh]);

  useEffect(() => {
    if (!dialogOpen) return;
    let cancelled = false;
    void firstValueFrom(fetchAllStudioUsers({ limit: 500, offset: 0 }))
      .then((paged) => {
        if (cancelled) return;
        setStudioUserOptions([...paged]);
      })
      .catch(() => {
        if (!cancelled) setStudioUserOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [dialogOpen]);

  const openHumanTaskCount = status?.openHumanTaskCount ?? 0;
  const agentsInError = status?.agentsInError ?? [];
  const hasAgentError = Boolean(status?.hasAgentError || agentsInError.length);
  const supervisorHaltReason = String(status?.supervisor?.supervisorHaltReason ?? '').trim();
  /** Author-facing: JVM tick may stay scheduled while disabled; use the enabled flag only (see status.get + disableSupervisor). */
  const supervisorOn = parseJsonBoolean(status?.supervisor?.supervisorEnabled);
  const supervisorScheduleLive = parseJsonBoolean(status?.supervisor?.supervisorRunning);

  useEffect(() => {
    const body = document.body;
    if (!body) return;
    if (openHumanTaskCount > 0) {
      body.setAttribute('data-cq-autonomous-open-tasks', String(openHumanTaskCount));
    } else {
      body.removeAttribute('data-cq-autonomous-open-tasks');
    }
    if (hasAgentError || supervisorHaltReason) {
      body.setAttribute('data-cq-autonomous-has-error', '1');
    } else {
      body.removeAttribute('data-cq-autonomous-has-error');
    }
    return () => {
      body.removeAttribute('data-cq-autonomous-open-tasks');
      body.removeAttribute('data-cq-autonomous-has-error');
    };
  }, [openHumanTaskCount, hasAgentError, supervisorHaltReason]);

  useEffect(() => {
    if (!mergedAgents.length) return;
    setSelected((prev) => (prev && mergedAgents.some((m) => m.agentId === prev) ? prev : mergedAgents[0].agentId));
  }, [mergedAgents]);

  useEffect(() => {
    if (!filteredAgents.length) return;
    if (!filteredAgents.some((a) => a.agentId === selected)) {
      setSelected(filteredAgents[0].agentId);
    }
  }, [filteredAgents, selected]);

  const selectedRow = useMemo(
    () => mergedAgents.find((a) => a.agentId === selected),
    [mergedAgents, selected]
  );

  const pastRunReportsEmpty = useMemo(() => {
    const pr = selectedRow?.pastRunReports;
    return !Array.isArray(pr) || pr.length === 0;
  }, [selectedRow?.pastRunReports]);

  const doControl = async (
    action: string,
    agentId?: string,
    taskId?: string,
    extras?: Record<string, string | undefined>
  ) => {
    if (!siteId) return;
    setBusy(true);
    setError(null);
    try {
      const r = await postAutonomousAssistantsControl(siteId, action, agentId, taskId, extras);
      if (r.ok === false) setError(r.message ?? action);
      else if (action === 'enable_supervisor' && defs.length > 0) {
        const syn = await syncAutonomousAssistants(siteId, defs);
        if (syn.ok === false) setError(syn.message ?? 'Re-sync after start failed');
      }
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const manageByStorageId = useMemo(() => {
    const m: Record<string, boolean> = {};
    for (const a of mergedAgents) {
      if (a.syntheticFromConfig) continue;
      m[a.agentId] = parseJsonBoolean(a.definition?.manageOtherAgentsHumanTasks);
    }
    return m;
  }, [mergedAgents]);

  const allFlatHumanTasks = useMemo((): FlatHumanTaskRow[] => {
    const out: FlatHumanTaskRow[] = [];
    for (const a of mergedAgents) {
      if (a.syntheticFromConfig) continue;
      const storageId = a.agentId;
      const label = String((a.definition?.name as string) || storageId).trim();
      const raw = a.state?.humanTasks;
      if (!Array.isArray(raw)) continue;
      for (const t of raw) {
        if (!t || typeof t !== 'object') continue;
        out.push({ ...(t as HumanTaskRow), storageAgentId: storageId, storageAgentLabel: label });
      }
    }
    return out;
  }, [mergedAgents]);

  const assignFilterUsers = useMemo(() => {
    const m = new Map<string, string>();
    for (const t of allFlatHumanTasks) {
      if ((t.status ?? 'open').toLowerCase() === 'dismissed') continue;
      const u = String(t.assignedUsername ?? '').trim();
      if (u) m.set(u, String(t.assignedName ?? '').trim() || u);
    }
    return Array.from(m.entries()).sort((a, b) => a[1].localeCompare(b[1]));
  }, [allFlatHumanTasks]);

  const visibleAggregatedTasks = useMemo(() => {
    let list = allFlatHumanTasks.filter((t) => (t.status ?? 'open').toLowerCase() !== 'dismissed');
    const af = taskAgentFilter.trim();
    if (af) {
      list = list.filter((t) => t.storageAgentId === af);
    }
    if (taskAssignFilter === TASK_ASSIGN_FILTER_UNASSIGNED) {
      list = list.filter((t) => !String(t.assignedUsername ?? '').trim());
    } else if (taskAssignFilter === TASK_ASSIGN_FILTER_ME) {
      const me = (activeUser?.username ?? '').trim().toLowerCase();
      list = list.filter((t) => String(t.assignedUsername ?? '').trim().toLowerCase() === me);
    } else if (taskAssignFilter.startsWith(TASK_USER_FILTER_PREFIX)) {
      const u = taskAssignFilter.slice(TASK_USER_FILTER_PREFIX.length);
      list = list.filter((t) => String(t.assignedUsername ?? '').trim() === u);
    }
    return list;
  }, [allFlatHumanTasks, taskAgentFilter, taskAssignFilter, activeUser?.username]);

  const copyTaskPrompt = async (prompt: string) => {
    const text = prompt?.trim() ?? '';
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      setCopyHint('Copied prompt');
      window.setTimeout(() => setCopyHint(null), 2000);
    } catch {
      setCopyHint('Copy failed');
      window.setTimeout(() => setCopyHint(null), 2000);
    }
  };

  const listSubtitle = !siteId
    ? 'Select a project'
    : defs.length === 0
      ? 'No agents configured'
      : hasAgentError || supervisorHaltReason
        ? 'Needs attention'
        : supervisorOn
          ? 'On'
          : '';

  const headerError = hasAgentError || Boolean(supervisorHaltReason);
  const agentControlsDisabled = busy || !selected || Boolean(selectedRow?.syntheticFromConfig);

  const panelBody =
    !siteId ? (
      <Typography variant="body2">Select a site to manage Autonomous Agents.</Typography>
    ) : defs.length === 0 ? (
      <>
        <Typography variant="subtitle1" gutterBottom>
          {listTitle}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          No agents configured. In Project Configuration → UI, under this widget’s &lt;configuration&gt;, add
          &lt;autonomousAgents&gt;&lt;agent&gt;…&lt;/agent&gt;&lt;/autonomousAgents&gt; (see plugin installation sample in
          craftercms-plugin.yaml).
        </Typography>
      </>
    ) : (
    <Box
      data-cq-autonomous-widget="1"
      data-cq-autonomous-has-error={headerError ? '1' : '0'}
      sx={{
        p: 0,
        pt: 0.5,
        display: 'flex',
        flexDirection: 'column',
        gap: 2,
        flex: 1,
        minHeight: 0,
        width: '100%',
        ...(headerError
          ? {
              borderLeft: (t) => `4px solid ${t.palette.error.main}`,
              pl: 2,
              ml: -0.5,
              bgcolor: (t) => (t.palette.mode === 'dark' ? 'rgba(211,47,47,0.08)' : 'rgba(211,47,47,0.06)')
            }
          : {})
      }}
    >
      <Stack spacing={2.5} sx={{ width: '100%', flexShrink: 0 }}>
      {error ? (
        <Alert severity="error" onClose={() => setError(null)} sx={{ borderRadius: 2 }}>
          {error}
        </Alert>
      ) : null}
      {supervisorHaltReason ? (
        <Alert severity="error">
          Automatic runs stopped after a failure: {supervisorHaltReason}
        </Alert>
      ) : null}
      {agentsInError.length > 0 ? (
        <Alert severity="error" icon={<ErrorOutlineIcon />}>
          <Typography variant="subtitle2" gutterBottom>
            Agents in error ({agentsInError.length})
          </Typography>
          <Table size="small" sx={{ minWidth: 400 }}>
            <TableHead>
              <TableRow>
                <TableCell>Agent</TableCell>
                <TableCell>When</TableCell>
                <TableCell>Message</TableCell>
                <TableCell align="right">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {agentsInError.map((row) => {
                const le = row.lastError as AgentLastError | undefined;
                const at = le?.at ?? '—';
                return (
                  <TableRow key={row.agentId}>
                    <TableCell>{row.name || row.agentId}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{at}</TableCell>
                    <TableCell sx={{ maxWidth: 480, verticalAlign: 'top' }}>
                      {le?.message ? (
                        lastErrorDetailStack({ le })
                      ) : (
                        <Typography variant="body2" component="span">
                          {JSON.stringify(row.lastError ?? {})}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        variant="outlined"
                        disabled={busy}
                        onClick={() => doControl('clear_agent_error', row.agentId)}
                      >
                        Clear error
                      </Button>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Alert>
      ) : null}
      </Stack>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: 'minmax(0, 1fr)', lg: 'minmax(0, 0.42fr) minmax(0, 0.58fr)' },
          gap: 2.5,
          width: '100%',
          alignItems: 'stretch',
          flex: 1,
          minHeight: 0
        }}
      >
        <Stack
          spacing={2.5}
          sx={{
            minWidth: 0,
            minHeight: 0,
            height: { lg: '100%' },
            display: 'flex',
            flexDirection: 'column'
          }}
        >
      <Paper
        id="cq-autonomous-system-supervisor"
        variant="outlined"
        sx={{ p: 2.5, borderRadius: 2, bgcolor: 'background.paper' }}
      >
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          alignItems={{ sm: 'center' }}
          justifyContent="space-between"
        >
          <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 0.06, fontWeight: 600 }}>
            System supervisor
          </Typography>
          <Stack direction="row" alignItems="center" flexWrap="wrap" gap={1.5}>
            <Button
              size="medium"
              variant="contained"
              color="primary"
              disabled={busy || supervisorOn}
              onClick={() => doControl('enable_supervisor')}
              sx={{ textTransform: 'none', minWidth: 128, fontWeight: 600 }}
            >
              Start system
            </Button>
            <Button
              size="medium"
              variant="outlined"
              color="inherit"
              disabled={busy || !supervisorOn}
              onClick={() => doControl('disable_supervisor')}
              sx={{ textTransform: 'none', minWidth: 112, fontWeight: 600 }}
            >
              Stop system
            </Button>
            <Chip
              size="small"
              label={supervisorOn ? 'Running' : 'Stopped'}
              color={supervisorOn ? 'success' : 'error'}
              variant="filled"
              sx={
                supervisorOn
                  ? undefined
                  : {
                      bgcolor: 'error.main',
                      '& .MuiChip-label': { color: '#fff', fontWeight: 700 }
                    }
              }
            />
          </Stack>
        </Stack>
        {supervisorOn && !supervisorScheduleLive ? (
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 2, lineHeight: 1.5 }}>
            Schedule reconnecting…
          </Typography>
        ) : null}
      </Paper>

      <Paper variant="outlined" sx={{ p: 2.5, borderRadius: 2, bgcolor: 'background.paper', flex: { lg: 1 }, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} justifyContent="space-between" sx={{ mb: 2 }}>
          <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 0.06, fontWeight: 600 }}>
            Agents
          </Typography>
          <TextField
            size="small"
            value={nameFilter}
            onChange={(e) => setNameFilter(e.target.value)}
            placeholder="Search by name…"
            inputProps={{ 'aria-label': 'Search agents by name' }}
            disabled={busy}
            sx={{ width: { xs: '100%', sm: 280, lg: 'min(100%, 360px)' } }}
          />
        </Stack>
        <TableContainer
          sx={{
            flex: { lg: 1 },
            minHeight: { lg: 200 },
            maxHeight: { xs: 280, lg: 'min(56vh, 560px)' },
            borderRadius: 1,
            border: 1,
            borderColor: 'divider'
          }}
        >
          <Table size="small" stickyHeader>
            <TableHead>
              <TableRow>
                <TableCell sx={{ fontWeight: 700, bgcolor: 'action.hover', py: 1.25 }}>Agent</TableCell>
                <TableCell sx={{ fontWeight: 700, bgcolor: 'action.hover', py: 1.25 }}>Status</TableCell>
                <TableCell sx={{ fontWeight: 700, bgcolor: 'action.hover', py: 1.25, minWidth: 148 }}>
                  Last activity
                </TableCell>
                <TableCell align="center" sx={{ fontWeight: 700, bgcolor: 'action.hover', py: 1.25, width: 56 }}>
                  Details
                </TableCell>
                <TableCell align="right" sx={{ fontWeight: 700, bgcolor: 'action.hover', py: 1.25 }}>
                  Open tasks
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filteredAgents.map((a) => {
                const activity = formatAgentLastActivity(
                  a.state as Record<string, unknown> | undefined,
                  Boolean(a.syntheticFromConfig)
                );
                return (
                <TableRow
                  key={a.agentId}
                  hover
                  selected={a.agentId === selected}
                  onClick={() => setSelected(a.agentId)}
                  sx={{ cursor: 'pointer', '&.Mui-selected': { bgcolor: 'action.selected' } }}
                >
                  <TableCell sx={{ fontWeight: a.agentId === selected ? 600 : 400 }}>
                    {(a.definition?.name as string) || a.agentId}
                  </TableCell>
                  <TableCell>
                    <AutonomousAgentStatusChip
                      status={String(a.state?.status ?? '—')}
                      synthetic={Boolean(a.syntheticFromConfig)}
                    />
                  </TableCell>
                  <TableCell sx={{ verticalAlign: 'top', py: 1.25 }}>
                    <Typography variant="body2" component="div" sx={{ lineHeight: 1.35 }}>
                      {activity.whenLine}
                    </Typography>
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ lineHeight: 1.4 }}>
                      {activity.statusLine}
                    </Typography>
                  </TableCell>
                  <TableCell align="center" sx={{ py: 0.5, verticalAlign: 'middle' }}>
                    <Tooltip title="View configuration">
                      <IconButton
                        size="small"
                        aria-label={`Configuration details for ${String((a.definition?.name as string) || a.agentId)}`}
                        onClick={(e) => {
                          e.stopPropagation();
                          setConfigDetailsAgentId(a.agentId);
                        }}
                      >
                        <InfoOutlined fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                  <TableCell align="right">{countOpenTasksForAgent(a.state, a.agentId)}</TableCell>
                </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
        {filteredAgents.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2, textAlign: 'center' }}>
            No agents match this search.
          </Typography>
        ) : null}
      </Paper>
        </Stack>

        <Stack
          spacing={2.5}
          sx={{
            minWidth: 0,
            minHeight: 0,
            display: 'flex',
            flexDirection: 'column',
            height: { lg: '100%' }
          }}
        >
      <Paper variant="outlined" sx={{ p: 2.5, borderRadius: 2, bgcolor: 'background.paper', flexShrink: 0 }}>
        <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 0.06, fontWeight: 600 }}>
          Agent actions
        </Typography>
        <Stack direction="row" alignItems="center" flexWrap="wrap" gap={1} sx={{ mt: 1 }}>
          <Typography variant="h6" component="h2" sx={{ fontSize: '1.15rem', fontWeight: 600, letterSpacing: -0.01 }}>
            {(selectedRow?.definition?.name as string) || selectedRow?.agentId || '—'}
          </Typography>
          {selectedRow?.state?.nextStepRequired ? (
            <Chip size="small" label="Next step due" color="info" variant="outlined" />
          ) : null}
        </Stack>
        {!definitionStartAutomatically(selectedRow?.definition) ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1, maxWidth: 560 }}>
            This agent has startAutomatically set to false: after sync it stays stopped until you use Start below (the
            supervisor must also be on).
          </Typography>
        ) : null}
        {selectedRow?.state?.status === 'error' ? (
          <Alert
            severity="error"
            sx={{ mt: 2 }}
            action={
              <Button
                color="inherit"
                size="small"
                disabled={agentControlsDisabled}
                onClick={() => doControl('clear_agent_error', selected)}
                sx={{ textTransform: 'none', fontWeight: 600 }}
              >
                Clear error
              </Button>
            }
          >
            {lastErrorDetailStack({ le: selectedRow.state.lastError as AgentLastError | undefined }) ?? (
              <Typography variant="body2">Run failed</Typography>
            )}
          </Alert>
        ) : null}
        {selectedRow?.state?.status !== 'error' &&
        selectedRow?.state?.lastError &&
        typeof (selectedRow.state.lastError as AgentLastError).message === 'string' &&
        String((selectedRow.state.lastError as AgentLastError).message).trim() ? (
          <Alert
            severity="warning"
            sx={{ mt: 2 }}
            action={
              <Button
                color="inherit"
                size="small"
                disabled={agentControlsDisabled}
                onClick={() => doControl('clear_agent_error', selected)}
                sx={{ textTransform: 'none', fontWeight: 600 }}
              >
                Clear error
              </Button>
            }
          >
            <Typography variant="body2" sx={{ mb: 1 }}>
              The last run failed; this agent is scheduled to retry on the next supervisor tick because stopOnFailure is
              off.
            </Typography>
            {lastErrorDetailStack({ le: selectedRow.state.lastError as AgentLastError | undefined })}
          </Alert>
        ) : null}
        <Divider sx={{ my: 2 }} />
        <Stack
          direction="row"
          flexWrap="wrap"
          gap={1}
          useFlexGap
          sx={{ width: '100%', justifyContent: { xs: 'flex-start', lg: 'flex-end' } }}
        >
          <Button
            variant="outlined"
            size="medium"
            disabled={agentControlsDisabled}
            onClick={() => doControl('start_agent', selected)}
            sx={{ textTransform: 'none', fontWeight: 600 }}
          >
            Start
          </Button>
          <Button
            variant="outlined"
            size="medium"
            disabled={agentControlsDisabled}
            onClick={() => doControl('stop_agent', selected)}
            sx={{ textTransform: 'none', fontWeight: 600 }}
          >
            Stop
          </Button>
          <Button
            variant="contained"
            color="primary"
            size="medium"
            disabled={agentControlsDisabled}
            onClick={() => doControl('execute_now', selected)}
            sx={{ textTransform: 'none', fontWeight: 600 }}
          >
            Run now
          </Button>
          <Button
            variant="outlined"
            size="medium"
            disabled={agentControlsDisabled}
            onClick={() => doControl('disable_agent', selected)}
            sx={{ textTransform: 'none', fontWeight: 600 }}
          >
            Disable
          </Button>
          <Button
            variant="outlined"
            size="medium"
            disabled={agentControlsDisabled}
            onClick={() => doControl('enable_agent', selected)}
            sx={{ textTransform: 'none', fontWeight: 600 }}
          >
            Enable
          </Button>
        </Stack>
      </Paper>

      <Paper
        variant="outlined"
        sx={{
          p: 2.5,
          borderRadius: 2,
          bgcolor: 'background.paper',
          flex: { lg: 1 },
          minHeight: { lg: 0 },
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden'
        }}
      >
        <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 0.06, fontWeight: 600 }}>
          Follow-up tasks
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mt: 1.5, flexWrap: 'wrap' }}>
          <FormControl size="small" sx={{ minWidth: { xs: '100%', sm: 200 } }}>
            <InputLabel id="cq-autonomous-task-agent-filter">Agent</InputLabel>
            <Select
              labelId="cq-autonomous-task-agent-filter"
              label="Agent"
              value={taskAgentFilter}
              onChange={(e) => setTaskAgentFilter(String(e.target.value))}
              disabled={busy}
            >
              <MenuItem value="">All agents</MenuItem>
              {mergedAgents
                .filter((a) => !a.syntheticFromConfig)
                .map((a) => (
                  <MenuItem key={a.agentId} value={a.agentId}>
                    {String((a.definition?.name as string) || a.agentId)}
                  </MenuItem>
                ))}
            </Select>
          </FormControl>
          <FormControl size="small" sx={{ minWidth: { xs: '100%', sm: 220 } }}>
            <InputLabel id="cq-autonomous-task-assign-filter">Assignment</InputLabel>
            <Select
              labelId="cq-autonomous-task-assign-filter"
              label="Assignment"
              value={taskAssignFilter}
              onChange={(e) => setTaskAssignFilter(String(e.target.value))}
              disabled={busy}
            >
              <MenuItem value={TASK_ASSIGN_FILTER_ALL}>All</MenuItem>
              <MenuItem value={TASK_ASSIGN_FILTER_UNASSIGNED}>Unassigned</MenuItem>
              <MenuItem value={TASK_ASSIGN_FILTER_ME}>Assigned to me</MenuItem>
              {assignFilterUsers.map(([username, disp]) => (
                <MenuItem key={username} value={`${TASK_USER_FILTER_PREFIX}${username}`}>
                  {disp}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Stack>
        {copyHint ? (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            {copyHint}
          </Typography>
        ) : null}
        {visibleAggregatedTasks.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2, py: 1.5, lineHeight: 1.6 }}>
            None match these filters. When a run needs your input, tasks appear here (all agents).
          </Typography>
        ) : (
          <List
            dense
            disablePadding
            sx={{
              mt: 1.5,
              border: 1,
              borderColor: 'divider',
              borderRadius: 1,
              flex: { lg: 1 },
              minHeight: { lg: 0 },
              maxHeight: { xs: 280 },
              overflow: 'auto'
            }}
          >
            {visibleAggregatedTasks.map((t, taskIdx) => {
              const row = t as FlatHumanTaskRow;
              const id = String(row.id ?? '');
              const st = (row.status ?? 'open').toLowerCase();
              const done = st === 'done';
              const title = (row.title as string)?.trim() || 'Task';
              const prompt = String(row.prompt ?? '');
              const manageCross = manageByStorageId[row.storageAgentId] ?? false;
              const taskRowLocked = humanTaskRowActionsDisabled(busy, row, row.storageAgentId, manageCross);
              const ownerLabel = humanTaskOwnerLabel(row, row.storageAgentId);
              const assignValue = String(row.assignedUsername ?? '').trim();
              const assignUsersForSelect: User[] = (() => {
                if (!assignValue) return studioUserOptions;
                if (studioUserOptions.some((u) => u.username === assignValue)) return studioUserOptions;
                return [
                  ...studioUserOptions,
                  {
                    id: 0,
                    username: assignValue,
                    firstName: String(row.assignedName ?? assignValue).trim() || assignValue,
                    lastName: '',
                    email: '',
                    enabled: true,
                    externallyManaged: false
                  }
                ];
              })();
              const pickedForAvatar = assignValue
                ? assignUsersForSelect.find((u) => u.username === assignValue)
                : undefined;
              const avatarUrl = pickedForAvatar ? String((pickedForAvatar as { avatar?: string }).avatar ?? '').trim() : '';
              const nameParts = String(row.assignedName ?? '').trim().split(/\s+/).filter(Boolean);
              return (
                <ListItem
                  key={`${row.storageAgentId}-${id || title + prompt.slice(0, 40)}`}
                  disablePadding
                  sx={{
                    display: 'block',
                    py: 0,
                    ...(taskIdx < visibleAggregatedTasks.length - 1
                      ? { borderBottom: (theme) => `1px solid ${theme.palette.divider}` }
                      : {})
                  }}
                >
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={1.5}
                    alignItems={{ xs: 'stretch', sm: 'flex-start' }}
                    sx={{ py: 1.25, px: 1.5, width: '100%', minWidth: 0 }}
                  >
                    <Stack direction="row" spacing={1} alignItems="flex-start" sx={{ flex: 1, minWidth: 0 }}>
                      <Checkbox
                        size="small"
                        checked={done}
                        disabled={taskRowLocked || !id}
                        onChange={(_, checked) => {
                          void doControl(
                            checked ? 'complete_human_task' : 'reopen_human_task',
                            row.storageAgentId,
                            id
                          );
                        }}
                        inputProps={{ 'aria-label': `Done: ${title}` }}
                        sx={{ mt: 0.25, flexShrink: 0 }}
                      />
                      <ListItemText
                        sx={{ my: 0, minWidth: 0 }}
                        primary={
                          <Stack spacing={0.25} alignItems="flex-start">
                            <Typography
                              variant="body2"
                              component="span"
                              sx={done ? { textDecoration: 'line-through', color: 'text.secondary' } : undefined}
                            >
                              {title}
                            </Typography>
                            <Typography variant="caption" color="text.secondary" component="span">
                              Queue: {row.storageAgentLabel} · From: {ownerLabel}
                            </Typography>
                          </Stack>
                        }
                        secondary={prompt}
                        secondaryTypographyProps={{
                          variant: 'caption',
                          sx: { whiteSpace: 'pre-wrap', wordBreak: 'break-word' }
                        }}
                      />
                    </Stack>
                    <Stack
                      spacing={1}
                      sx={{
                        width: { xs: '100%', sm: 240 },
                        minWidth: { sm: 240 },
                        flexShrink: 0,
                        alignSelf: { sm: 'stretch' }
                      }}
                    >
                      <Stack direction="row" spacing={1} alignItems="flex-start" sx={{ width: '100%', minWidth: 0 }}>
                        {assignValue ? (
                          <Tooltip
                            title={formatStudioUserLabel(
                              pickedForAvatar ?? { username: assignValue, firstName: '', lastName: '' }
                            )}
                          >
                            <Box component="span" sx={{ lineHeight: 0, display: 'inline-block' }}>
                              <HumanTaskAssigneeAvatar
                                username={assignValue}
                                firstName={pickedForAvatar?.firstName ?? nameParts[0] ?? assignValue}
                                lastName={pickedForAvatar?.lastName ?? nameParts.slice(1).join(' ')}
                                avatarUrl={avatarUrl}
                              />
                            </Box>
                          </Tooltip>
                        ) : null}
                        <TextField
                          select
                          size="small"
                          label="Assignee"
                          value={assignValue}
                          fullWidth
                          disabled={taskRowLocked || !id}
                          onChange={(e) => {
                            const v = String(e.target.value);
                            const picked = assignUsersForSelect.find((u) => u.username === v);
                            const name = picked ? formatStudioUserLabel(picked) : v || String(row.assignedName ?? '').trim();
                            void doControl('assign_human_task', row.storageAgentId, id, {
                              assignedUsername: v,
                              assignedName: v ? name : ''
                            });
                          }}
                          SelectProps={{ displayEmpty: true }}
                          InputLabelProps={{ shrink: true }}
                          sx={{ flex: 1, minWidth: 0, '& .MuiInputBase-root': { backgroundColor: 'background.paper' } }}
                        >
                          <MenuItem value="">
                            <em>Unassigned</em>
                          </MenuItem>
                          {assignUsersForSelect.map((u) => (
                            <MenuItem key={u.username} value={u.username}>
                              {formatStudioUserLabel(u)}
                            </MenuItem>
                          ))}
                        </TextField>
                      </Stack>
                      <Stack direction="row" justifyContent="flex-end" spacing={0.5}>
                        <Tooltip title="Copy prompt">
                          <span>
                            <IconButton
                              size="small"
                              disabled={busy || !prompt}
                              onClick={() => void copyTaskPrompt(prompt)}
                              aria-label="Copy prompt"
                            >
                              <ContentCopyIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                        <Tooltip title="Dismiss">
                          <span>
                            <IconButton
                              size="small"
                              disabled={taskRowLocked || !id}
                              onClick={() => doControl('dismiss_human_task', row.storageAgentId, id)}
                              aria-label="Dismiss task"
                            >
                              <CloseIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                      </Stack>
                    </Stack>
                  </Stack>
                </ListItem>
              );
            })}
          </List>
        )}
      </Paper>

      <Paper
        variant="outlined"
        sx={{
          p: 2.5,
          borderRadius: 2,
          bgcolor: 'background.paper',
          flex: { lg: 1 },
          minHeight: { lg: 0 },
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden'
        }}
      >
        <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 0.06, fontWeight: 600 }}>
          Run history
        </Typography>
        {pastRunReportsEmpty ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2, py: 1.5 }}>
            No completed runs recorded for this agent yet.
          </Typography>
        ) : (
          <Box
            component="pre"
            sx={{
              mt: 1.5,
              m: 0,
              p: 2,
              flex: { lg: 1 },
              minHeight: { lg: 120 },
              maxHeight: { xs: 220, sm: 320, lg: 'min(48vh, 520px)' },
              overflow: 'auto',
              bgcolor: (theme) => (theme.palette.mode === 'dark' ? 'grey.900' : 'grey.50'),
              borderRadius: 1,
              fontSize: 12,
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
              lineHeight: 1.5,
              border: 1,
              borderColor: 'divider',
              whiteSpace: 'pre-wrap'
            }}
          >
            {JSON.stringify(selectedRow?.pastRunReports ?? [], null, 2)}
          </Box>
        )}
      </Paper>
        </Stack>
      </Box>
    </Box>
    );

  return (
    <>
      <GlobalStyles
        styles={(theme) => ({
          '[data-cq-autonomous-widget="1"][data-cq-autonomous-has-error="1"] .MuiTypography-subtitle1': {
            color: theme.palette.error.main
          }
        })}
      />
      <ListItemButton
        onClick={() => {
          setDialogOpen(true);
          setDialogMinimized(false);
        }}
      >
        <ListItemIcon>
          <Badge
            badgeContent={openHumanTaskCount}
            color="warning"
            max={99}
            overlap="circular"
            invisible={openHumanTaskCount === 0}
            anchorOrigin={{ vertical: 'top', horizontal: 'right' }}
          >
            <SystemIcon
              icon={toolsListSystemIcon}
              svgIconProps={{
                sx: (t: Theme) => ({
                  fontSize: 22,
                  color: hasAgentError || supervisorHaltReason
                    ? t.palette.warning.main
                    : supervisorOn
                      ? t.palette.success.main
                      : t.palette.error.main
                })
              }}
            />
          </Badge>
        </ListItemIcon>
        <ListItemText
          primary={listTitleTranslated}
          secondary={listSubtitle || undefined}
          primaryTypographyProps={{ noWrap: true }}
          secondaryTypographyProps={{ noWrap: true }}
        />
        <ChevronRightRounded color="action" sx={{ flexShrink: 0 }} />
      </ListItemButton>
      {typeof document !== 'undefined' &&
        createPortal(
          <>
            {dialogOpen && !dialogMinimized ? (
              <Dialog
                open
                onClose={() => {
                  setDialogOpen(false);
                  setDialogMinimized(false);
                }}
                maxWidth={false}
                fullWidth={false}
                scroll="paper"
                aria-labelledby="cq-autonomous-dialog-title"
                PaperProps={{
                  sx: {
                    width: { xs: '100%', sm: 'min(96vw, 1680px)' },
                    maxWidth: '96vw',
                    maxHeight: 'calc(100vh - 16px)',
                    m: { xs: 0.5, sm: 1.5 }
                  }
                }}
              >
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    px: 2.5,
                    py: 1.25,
                    borderBottom: 1,
                    borderColor: 'divider',
                    gap: 1.5,
                    bgcolor: (theme) => (theme.palette.mode === 'dark' ? 'grey.900' : 'grey.50')
                  }}
                >
                  <Badge
                    badgeContent={openHumanTaskCount}
                    color="warning"
                    max={99}
                    overlap="circular"
                    invisible={openHumanTaskCount === 0}
                    anchorOrigin={{ vertical: 'top', horizontal: 'right' }}
                  >
                    <AutonomousAgentsMarkIcon
                      sx={(t: Theme) => ({
                        fontSize: 28,
                        color: hasAgentError || supervisorHaltReason
                          ? t.palette.warning.main
                          : supervisorOn
                            ? t.palette.success.main
                            : t.palette.error.main
                      })}
                      aria-hidden
                    />
                  </Badge>
                  <Typography id="cq-autonomous-dialog-title" variant="h6" component="span" sx={{ flex: 1 }}>
                    {listTitle}
                  </Typography>
                  <Tooltip title="Minimize">
                    <IconButton aria-label="Minimize" onClick={() => setDialogMinimized(true)} size="small">
                      <RemoveRounded />
                    </IconButton>
                  </Tooltip>
                  <Tooltip title="Close">
                    <IconButton
                      aria-label="Close"
                      onClick={() => {
                        setDialogOpen(false);
                        setDialogMinimized(false);
                      }}
                      size="small"
                    >
                      <CloseRounded />
                    </IconButton>
                  </Tooltip>
                </Box>
                <DialogContent
                  dividers
                  sx={{
                    pt: 3,
                    px: { xs: 2, sm: 3 },
                    pb: 3,
                    minHeight: 'min(85vh, 900px)',
                    maxHeight: 'calc(100vh - 120px)',
                    overflow: 'auto',
                    bgcolor: 'background.default',
                    display: 'flex',
                    flexDirection: 'column'
                  }}
                >
                  {panelBody}
                </DialogContent>
              </Dialog>
            ) : null}
            {configDetailsAgentId && configDetailsRow ? (
              <Dialog
                open
                onClose={() => setConfigDetailsAgentId(null)}
                maxWidth="sm"
                fullWidth
                scroll="paper"
                aria-labelledby="cq-autonomous-config-details-title"
              >
                <DialogTitle id="cq-autonomous-config-details-title">
                  Agent configuration —{' '}
                  {String((configDetailsRow.definition?.name as string) || configDetailsRow.agentId)}
                </DialogTitle>
                <DialogContent dividers sx={{ pt: 2 }}>
                  <AgentConfigurationDetailsContent row={configDetailsRow} />
                </DialogContent>
                <DialogActions sx={{ px: 3, py: 2 }}>
                  <Button onClick={() => setConfigDetailsAgentId(null)} sx={{ textTransform: 'none' }}>
                    Close
                  </Button>
                </DialogActions>
              </Dialog>
            ) : null}
            {dialogOpen && dialogMinimized ? (
              <Paper
                elevation={4}
                sx={{
                  position: 'fixed',
                  bottom: 16,
                  right: 16,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1,
                  px: 2,
                  py: 1.5,
                  zIndex: 1300,
                  border: '1px solid',
                  borderColor: 'divider',
                  maxWidth: 'min(96vw, 520px)'
                }}
              >
                <Badge
                  badgeContent={openHumanTaskCount}
                  color="warning"
                  max={99}
                  overlap="circular"
                  invisible={openHumanTaskCount === 0}
                  anchorOrigin={{ vertical: 'top', horizontal: 'right' }}
                >
                  <AutonomousAgentsMarkIcon
                    sx={(t: Theme) => ({
                      fontSize: 22,
                      color: hasAgentError || supervisorHaltReason
                        ? t.palette.warning.main
                        : supervisorOn
                          ? t.palette.success.main
                          : t.palette.error.main
                    })}
                    aria-hidden
                  />
                </Badge>
                <Typography variant="body1" sx={{ flex: 1, minWidth: 0 }} noWrap>
                  {listTitle}
                </Typography>
                <Tooltip title="Restore">
                  <IconButton aria-label="Restore" onClick={() => setDialogMinimized(false)} size="small">
                    <OpenInBrowserRounded />
                  </IconButton>
                </Tooltip>
                <Tooltip title="Close">
                  <IconButton
                    aria-label="Close"
                    onClick={() => {
                      setDialogOpen(false);
                      setDialogMinimized(false);
                    }}
                    size="small"
                  >
                    <CloseRounded />
                  </IconButton>
                </Tooltip>
              </Paper>
            ) : null}
          </>,
          document.body
        )}
    </>
  );
}

function AiAssistantAutonomousAssistantsGated(props: Readonly<AiAssistantAutonomousAssistantsProps>) {
  const activeSiteId = useActiveSiteId();
  const siteKey = useMemo(() => effectiveStudioSiteId(activeSiteId), [activeSiteId]);
  const subscribeUi = useCallback(
    (onStoreChange: () => void) => subscribeStudioUiConfigChanged(siteKey, onStoreChange),
    [siteKey]
  );
  const studioUiEpoch = useSyncExternalStore(
    subscribeUi,
    () => getStudioUiConfigEpochSnapshot(siteKey),
    () => 0
  );
  const cfg = useMemo(() => syncReadStudioUiConfig(siteKey), [siteKey, studioUiEpoch]);
  if (cfg.showAutonomousAiAssistantsInSidebar !== true) {
    return null;
  }
  return <AiAssistantAutonomousAssistantsImpl {...props} />;
}

export function AiAssistantAutonomousAssistants(props: Readonly<AiAssistantAutonomousAssistantsProps>) {
  return <AiAssistantAutonomousAssistantsGated {...props} />;
}

export default AiAssistantAutonomousAssistants;
