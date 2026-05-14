import { useLayoutEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import ChevronLeftRounded from '@mui/icons-material/ChevronLeftRounded';
import ChevronRightRounded from '@mui/icons-material/ChevronRightRounded';
import { Box, GlobalStyles, IconButton, Tooltip, useMediaQuery, useTheme } from '@mui/material';
import AiAssistantFormControlPanel from './AiAssistantFormControlPanel';
import type { AuthoringFormContextSnapshot } from './aiAssistantFormAuthoringTypes';
import type { AgentConfig } from './agentConfig';

/** Studio / bridge may pass agents as a numeric-keyed object instead of a real array. */
function normalizeAgentsProp(raw: AgentConfig[] | Record<string, unknown> | null | undefined): AgentConfig[] {
  if (raw == null) return [];
  if (Array.isArray(raw)) return raw;
  if (typeof raw === 'object') {
    const vals = Object.values(raw as Record<string, unknown>).filter(
      (v): v is AgentConfig => v != null && typeof v === 'object' && 'label' in (v as object)
    );
    if (vals.length > 0) return vals;
  }
  return [];
}

/**
 * Shell only: portal, body inset, props. Agent list + expand/collapse chat UI is **AiAssistantFormControlPanel.tsx** —
 * do not replace with a single shared chat strip (product contract; see docs/internals/spec.md and cursor rule).
 */
export type { AuthoringFormContextSnapshot } from './aiAssistantFormAuthoringTypes';

/** Must match fixed panel `width` at `sm` and up. */
const FORM_PANEL_WIDTH_PX = 400;
/** Collapsed drawer width on desktop. */
const FORM_PANEL_COLLAPSED_WIDTH_PX = 44;
/** Reserved on the right beyond panel width (shadow / layout slack) for `body` padding. */
const FORM_PANEL_RIGHT_CLEARANCE_PX = 48;
/** Existing Studio split button sits outside panel; keep parity with prior 419px expanded offset. */
const FORM_PANEL_SPLIT_BUTTON_RIGHT_GAP_PX = 19;

let formPanelInsetRefCount = 0;

function acquireFormPanelBodyInset(insetPx: string, panelWidthPx: string): void {
  formPanelInsetRefCount += 1;
  if (formPanelInsetRefCount === 1) {
    document.documentElement.classList.add('aiassistant-form-panel-active');
    document.documentElement.style.setProperty('--aiassistant-form-panel-inset', insetPx);
    document.documentElement.style.setProperty('--aiassistant-form-panel-width', panelWidthPx);
  }
}

function releaseFormPanelBodyInset(): void {
  formPanelInsetRefCount = Math.max(0, formPanelInsetRefCount - 1);
  if (formPanelInsetRefCount === 0) {
    document.documentElement.classList.remove('aiassistant-form-panel-active');
    document.documentElement.style.removeProperty('--aiassistant-form-panel-inset');
    document.documentElement.style.removeProperty('--aiassistant-form-panel-width');
  }
}

export interface AiAssistantFormControlProps {
  /**
   * Enabled agents only (legacy `cqVisibleAgentsFromProperties`). Do not add parallel `enabledAgentKeys`
   * filtering here — it can hide agents when keys diverge from properties.
   */
  agents?: AgentConfig[] | Record<string, unknown> | null;
  /** When true (Studio form read-only / view mode), do not mount the portaled panel or body inset. */
  readOnly?: boolean;
  /** Called on every message send so the agent gets up-to-date form definition + field values. */
  getAuthoringFormContext?: () => AuthoringFormContextSnapshot;
}

/**
 * Form Engine control: fixed right-hand panel (portaled). Legacy shell passes only agents enabled in Form Properties.
 */
export default function AiAssistantFormControl(props: Readonly<AiAssistantFormControlProps>) {
  const { agents: agentsProp, getAuthoringFormContext, readOnly: readOnlyProp } = props;
  const isReadOnly = Boolean(readOnlyProp);
  const theme = useTheme();
  const reserveBodyInset = useMediaQuery(theme.breakpoints.up('sm'));
  const [collapsed, setCollapsed] = useState(false);

  const visibleAgents = normalizeAgentsProp(agentsProp);
  const panelWidthPx = collapsed ? FORM_PANEL_COLLAPSED_WIDTH_PX : FORM_PANEL_WIDTH_PX;
  const panelInsetPx = panelWidthPx + FORM_PANEL_RIGHT_CLEARANCE_PX;

  useLayoutEffect(() => {
    if (isReadOnly || !reserveBodyInset || typeof document === 'undefined') return;
    acquireFormPanelBodyInset(`${panelInsetPx}px`, `${panelWidthPx}px`);
    return () => {
      releaseFormPanelBodyInset();
    };
  }, [isReadOnly, reserveBodyInset, panelInsetPx, panelWidthPx]);

  if (isReadOnly) {
    return null;
  }

  const panel = (
    <Box
      data-aiassistant-form-panel="true"
      sx={{
        position: 'fixed',
        top: 0,
        right: 0,
        height: '100vh',
        width: { xs: '100%', sm: `${panelWidthPx}px` },
        maxWidth: '100vw',
        zIndex: 100002,
        display: 'flex',
        flexDirection: 'column',
        transition: (muiTheme) =>
          muiTheme.transitions.create(['width'], {
            duration: muiTheme.transitions.duration.shorter,
            easing: muiTheme.transitions.easing.easeOut
          }),
        boxShadow: 6,
        borderLeft: 1,
        borderColor: 'divider',
        bgcolor: 'background.paper',
        overflow: 'hidden'
      }}
    >
      <Box
        sx={{
          minHeight: 44,
          display: 'flex',
          alignItems: 'center',
          justifyContent: collapsed ? 'center' : 'flex-end',
          px: 0.5,
          borderBottom: collapsed ? 0 : 1,
          borderColor: 'divider',
          bgcolor: 'background.paper'
        }}
      >
        <Tooltip title={collapsed ? 'Expand assistant drawer' : 'Collapse assistant drawer'}>
          <IconButton
            size="small"
            aria-label={collapsed ? 'Expand assistant drawer' : 'Collapse assistant drawer'}
            onClick={() => setCollapsed((prev) => !prev)}
          >
            {collapsed ? <ChevronLeftRounded fontSize="small" /> : <ChevronRightRounded fontSize="small" />}
          </IconButton>
        </Tooltip>
      </Box>
      {!collapsed ? (
        <AiAssistantFormControlPanel
          visibleAgents={visibleAgents}
          getAuthoringFormContext={getAuthoringFormContext}
        />
      ) : null}
    </Box>
  );

  return (
    <>
      <GlobalStyles
        styles={{
          [`@media (min-width: ${theme.breakpoints.values.sm ?? 600}px)`]: {
            'html.aiassistant-form-panel-active body': {
              paddingLeft: `${theme.spacing(2)} !important`,
              paddingRight: `calc(var(--aiassistant-form-panel-inset) + ${theme.spacing(3)}) !important`,
              boxSizing: 'border-box',
              transition: theme.transitions.create(['padding-left', 'padding-right'], {
                duration: theme.transitions.duration.shorter,
                easing: theme.transitions.easing.easeOut
              })
            },
            'html.aiassistant-form-panel-active .cstudio-form-controls-button-container #splitButtonContainer': {
              marginLeft: '15px !important',
              marginRight: `calc(var(--aiassistant-form-panel-width) + ${FORM_PANEL_SPLIT_BUTTON_RIGHT_GAP_PX}px) !important`
            }
          }
        }}
      />
      <Box
        className="aiassistant-form-control-sentinel"
        sx={{ width: 0, height: 0, position: 'relative', overflow: 'visible', pointerEvents: 'none' }}
        aria-hidden
      />
      {typeof document !== 'undefined' ? createPortal(panel, document.body) : null}
    </>
  );
}
