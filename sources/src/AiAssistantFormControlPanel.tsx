import * as React from 'react';
import { useEffect, useState } from 'react';
import { Box, Typography } from '@mui/material';
import ExpandMoreRounded from '@mui/icons-material/ExpandMoreRounded';
import AiAssistantChat from './AiAssistantChat';
import { agentStableKey, type AgentConfig } from './agentConfig';
import { getAgentIcon } from './agentIcon';
import type { AuthoringFormContextSnapshot } from './aiAssistantFormAuthoringTypes';

export type { AuthoringFormContextSnapshot };

const LAST_OPEN_AGENT_STORAGE_PREFIX = 'aiassistant.formPanel.lastOpenAgent.';

function formPanelSiteSegment(): string {
  try {
    const ctx = (window as unknown as { CStudioAuthoringContext?: { site?: string } }).CStudioAuthoringContext;
    const s = ctx?.site?.trim();
    if (s) return s.replace(/[^a-zA-Z0-9_-]/g, '_');
  } catch {
    /* ignore */
  }
  return '_';
}

function readLastOpenAgentKey(): string {
  try {
    return window.localStorage.getItem(LAST_OPEN_AGENT_STORAGE_PREFIX + formPanelSiteSegment())?.trim() || '';
  } catch {
    return '';
  }
}

function writeLastOpenAgentKey(key: string): void {
  if (!key) return;
  try {
    window.localStorage.setItem(LAST_OPEN_AGENT_STORAGE_PREFIX + formPanelSiteSegment(), key);
  } catch {
    /* quota / private mode */
  }
}

/**
 * LOCKED PRODUCT UX — do not replace with a single shared chat below a list of buttons.
 * Implementation uses explicit expand/collapse (not MUI Accordion) because some Studio runtimes
 * do not expose Accordion on craftercms.libs.MaterialUI, which previously broke the panel entirely.
 *
 * Contract: one row per enabled agent; exactly one expanded; AiAssistantChat only inside the expanded section.
 * Legacy passes visibleAgents only (see control/ai-assistant/main.js).
 *
 * Do not remove or redesign this pattern without explicit written approval from the project maintainer.
 */
export interface AiAssistantFormControlPanelProps {
  visibleAgents: AgentConfig[];
  getAuthoringFormContext?: () => AuthoringFormContextSnapshot;
}

export default function AiAssistantFormControlPanel(props: AiAssistantFormControlPanelProps) {
  const { visibleAgents, getAuthoringFormContext } = props;
  const [openKey, setOpenKey] = useState<string>(() => {
    if (visibleAgents.length === 0) return '';
    const saved = typeof window !== 'undefined' ? readLastOpenAgentKey() : '';
    if (saved && visibleAgents.some((a) => agentStableKey(a) === saved)) return saved;
    return agentStableKey(visibleAgents[0]);
  });

  const setOpenKeyPersist = (key: string) => {
    setOpenKey(key);
    if (key) writeLastOpenAgentKey(key);
  };

  useEffect(() => {
    if (visibleAgents.length === 0) {
      setOpenKey('');
      return;
    }
    const first = agentStableKey(visibleAgents[0]);
    setOpenKey((prev) => {
      if (!prev) {
        const saved = readLastOpenAgentKey();
        if (saved && visibleAgents.some((a) => agentStableKey(a) === saved)) return saved;
        return first;
      }
      const still = visibleAgents.some((a) => agentStableKey(a) === prev);
      if (still) return prev;
      const saved = readLastOpenAgentKey();
      if (saved && visibleAgents.some((a) => agentStableKey(a) === saved)) return saved;
      return first;
    });
  }, [visibleAgents]);

  const activateAgent = (key: string) => {
    setOpenKeyPersist(key);
  };

  const onHeaderClick = (key: string) => {
    if (openKey !== key) {
      activateAgent(key);
      return;
    }
    if (visibleAgents.length <= 1) return;
    const idx = visibleAgents.findIndex((a) => agentStableKey(a) === key);
    if (idx < 0) return;
    const next = visibleAgents[(idx + 1) % visibleAgents.length];
    setOpenKeyPersist(agentStableKey(next));
  };

  if (visibleAgents.length === 0) {
    return (
      <Box sx={{ p: 2 }}>
        <Typography variant="body2" color="text.secondary">
          No agents are enabled for this field. Open the content type in Studio → Form → select this field →
          Properties, and enable each agent you want here (agents are listed from
          Project Configuration → UI, Studio AI Assistant / Helper).
        </Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <Box
        sx={{
          px: 1.5,
          py: 1,
          borderBottom: 1,
          borderColor: 'divider',
          flexShrink: 0
        }}
      >
        <Typography variant="subtitle2" component="h2">
          Studio AI assistant
        </Typography>
      </Box>
      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', WebkitOverflowScrolling: 'touch' }}>
        {visibleAgents.map((agent) => {
          const key = agentStableKey(agent);
          const expanded = openKey === key;
          return (
            <Box
              key={key}
              sx={{
                borderBottom: 1,
                borderColor: 'divider',
                bgcolor: expanded ? 'action.selected' : 'background.paper'
              }}
            >
              <Box
                component="button"
                type="button"
                aria-expanded={expanded}
                aria-controls={`aiassistant-agent-panel-${key}`}
                id={`aiassistant-agent-header-${key}`}
                onClick={() => onHeaderClick(key)}
                sx={{
                  width: '100%',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1,
                  minHeight: 48,
                  px: 1.5,
                  py: 1,
                  m: 0,
                  border: 0,
                  cursor: 'pointer',
                  textAlign: 'left',
                  bgcolor: expanded ? 'action.selected' : 'action.hover',
                  color: 'text.primary',
                  '&:hover': {
                    filter: 'brightness(0.97)'
                  }
                }}
              >
                <ExpandMoreRounded
                  fontSize="small"
                  sx={{
                    flexShrink: 0,
                    color: 'text.secondary',
                    transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)',
                    transition: (theme) =>
                      theme.transitions.create('transform', { duration: theme.transitions.duration.shorter })
                  }}
                />
                <Box sx={{ display: 'flex', color: 'text.secondary', flexShrink: 0 }}>{getAgentIcon(agent.icon)}</Box>
                <Typography variant="body2" noWrap sx={{ flex: 1, minWidth: 0 }} title={agent.label}>
                  {agent.label}
                </Typography>
              </Box>
              {expanded ? (
                <Box
                  id={`aiassistant-agent-panel-${key}`}
                  role="region"
                  aria-labelledby={`aiassistant-agent-header-${key}`}
                  sx={{
                    minHeight: 280,
                    maxHeight: 'min(58vh, 520px)',
                    display: 'flex',
                    flexDirection: 'column',
                    bgcolor: 'action.hover',
                    overflow: 'hidden',
                    borderTop: 1,
                    borderColor: 'divider'
                  }}
                >
                  <AiAssistantChat
                    agentId={agent.id?.trim() || ''}
                    llm={agent.llm}
                    llmModel={agent.llmModel}
                    imageModel={agent.imageModel}
                    imageGenerator={agent.imageGenerator}
                    openAiApiKey={agent.openAiApiKey}
                    enableTools={agent.enableTools}
                    enabledBuiltInTools={agent.enabledBuiltInTools}
                    expertSkills={agent.expertSkills}
                    configPrompts={agent.prompts}
                    embedTarget="default"
                    getAuthoringFormContext={getAuthoringFormContext}
                    formEngineClientJsonApply
                    {...(agent.translateBatchConcurrency != null
                      ? { translateBatchConcurrency: agent.translateBatchConcurrency }
                      : {})}
                    {...(agent.crafterQBearerTokenEnv?.trim()
                      ? { crafterQBearerTokenEnv: agent.crafterQBearerTokenEnv.trim() }
                      : {})}
                    {...(agent.crafterQBearerToken?.trim()
                      ? { crafterQBearerToken: agent.crafterQBearerToken.trim() }
                      : {})}
                  />
                </Box>
              ) : null}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}
