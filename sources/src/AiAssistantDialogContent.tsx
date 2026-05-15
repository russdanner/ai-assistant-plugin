import * as React from 'react';
import { useLayoutEffect, useRef } from 'react';
import { Box, GlobalStyles } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import AiAssistantChat from './AiAssistantChat';
import type { ExpertSkillConfig } from './agentConfig';

/** Same scroll-port detection as {@link AiAssistantChat} — ICE `ResizeableDrawer` drawerBody is `overflow-y: auto`. */
function getScrollParent(node: HTMLElement | null): HTMLElement | null {
  let el: HTMLElement | null = node?.parentElement ?? null;
  while (el) {
    const { overflowY, overflow } = getComputedStyle(el);
    const oy = overflowY || overflow;
    if (oy === 'auto' || oy === 'scroll' || oy === 'overlay') return el;
    el = el.parentElement;
  }
  return null;
}

const ICE_STICKY_HEADER_CLASS = 'aiassistant-ice-sticky-panel-header';

/**
 * ICE embed wrapper — keep overflow visible so `position: sticky` on the composer can attach to
 * Studio’s ICE `ResizeableDrawer` scroll body (drawerBody uses overflow-y: auto).
 *
 * Studio renders {@link ToolPanel}’s title row as a real `<header>` inside that same scroll
 * section, so it scrolls away by default. While this shell is mounted we tag the scroll root and
 * pin that header (and hide the redundant MUI divider under it) with global styles.
 */
export function AiAssistantIceChatShell(props: Readonly<{ children: React.ReactNode }>) {
  const { children } = props;
  const theme = useTheme();
  const rootRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const node = rootRef.current;
    if (!node) return;
    const scrollRoot = getScrollParent(node);
    if (!scrollRoot) return;
    scrollRoot.classList.add(ICE_STICKY_HEADER_CLASS);
    return () => {
      scrollRoot.classList.remove(ICE_STICKY_HEADER_CLASS);
    };
  }, []);

  return (
    <Box
      ref={rootRef}
      sx={{
        width: '100%',
        minWidth: 0,
        alignSelf: 'stretch',
        minHeight: 'min(48dvh, 480px)',
        overflow: 'visible'
      }}
    >
      <GlobalStyles
        styles={{
          [`.${ICE_STICKY_HEADER_CLASS} header`]: {
            position: 'sticky',
            top: 0,
            zIndex: 8,
            backgroundColor: theme.palette.background.default,
            borderBottom: `1px solid ${theme.palette.divider}`
          },
          // PanelHeader already draws a divider; we replace it with borderBottom on the sticky header.
          [`.${ICE_STICKY_HEADER_CLASS} header + .MuiDivider-root`]: {
            display: 'none'
          }
        }}
      />
      {children}
    </Box>
  );
}

export interface AiAssistantDialogContentProps {
  agentId?: string;
  llm?: string;
  llmModel?: string;
  imageModel?: string;
  imageGenerator?: string;
  /** Testing only — prefer server env. See docs/using-and-extending/llm-configuration.md */
  openAiApiKey?: string;
  prompts?: Array<{ userText: string; additionalContext?: string }>;
  enableTools?: boolean;
  enabledBuiltInTools?: string[];
  expertSkills?: ExpertSkillConfig[];
  translateBatchConcurrency?: number;
}

/**
 * Body widget for Studio's WidgetDialog. Renders only the chat.
 * Used when opening the AI Assistant via dispatch(showWidgetDialog(...)).
 */
function AiAssistantDialogContent(props: Readonly<AiAssistantDialogContentProps>) {
  const {
    agentId = '019c7237-478b-7f98-9a5c-87144c3fb010',
    llm,
    llmModel,
    imageModel,
    imageGenerator,
    openAiApiKey,
    prompts,
    enableTools,
    enabledBuiltInTools,
    expertSkills,
    translateBatchConcurrency
  } = props;
  return (
    <AiAssistantChat
      agentId={agentId}
      llm={llm}
      llmModel={llmModel}
      imageModel={imageModel}
      imageGenerator={imageGenerator}
      openAiApiKey={openAiApiKey}
      enableTools={enableTools}
      enabledBuiltInTools={enabledBuiltInTools}
      expertSkills={expertSkills}
      configPrompts={prompts}
      {...(translateBatchConcurrency != null ? { translateBatchConcurrency } : {})}
    />
  );
}

export default AiAssistantDialogContent;
