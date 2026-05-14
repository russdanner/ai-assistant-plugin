import { AppBarProps, Box, paperClasses, Popover, PopoverProps, useTheme } from '@mui/material';
import React, { useState } from 'react';
import MinimizedBar from '@craftercms/studio-ui/components/MinimizedBar';
import DialogHeader from '@craftercms/studio-ui/components/DialogHeader/DialogHeader';
import AlertDialog from '@craftercms/studio-ui/components/AlertDialog';
import PrimaryButton from '@craftercms/studio-ui/components/PrimaryButton/PrimaryButton';
import SecondaryButton from '@craftercms/studio-ui/components/SecondaryButton/SecondaryButton';
import AiAssistantChat from './AiAssistantChat';
import type { ExpertSkillConfig } from './agentConfig';

export interface AiAssistantPopoverProps extends PopoverProps {
  appBarTitle?: string;
  agentLabel?: string;
  width?: number | string;
  height?: number | string;
  appBarProps?: AppBarProps;
  isMinimized?: boolean;
  onMinimize?: () => void;
  onMaximize?: () => void;
  enableCustomModel?: boolean;
  agentId?: string;
  llm?: string;
  llmModel?: string;
  imageModel?: string;
  imageGenerator?: string;
  /** Testing only — prefer server env. See docs/using-and-extending/llm-configuration.md */
  openAiApiKey?: string;
  /** Prompts to show above the chat (quick message buttons). Overrides API quick messages when set. */
  prompts?: Array<{ userText: string; additionalContext?: string }>;
  /** When false, server omits OpenAI function tools. */
  enableTools?: boolean;
  enabledBuiltInTools?: string[];
  expertSkills?: ExpertSkillConfig[];
  translateBatchConcurrency?: number;
  crafterQBearerToken?: string;
  crafterQBearerTokenEnv?: string;
}

function AiAssistantPopover(props: Readonly<AiAssistantPopoverProps>) {
  const theme = useTheme();
  const {
    open,
    onClose,
    isMinimized = false,
    onMinimize,
    onMaximize,
    appBarTitle,
    agentLabel,
    width = 492,
    height = 595,
    hideBackdrop,
    enableCustomModel = true,
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
    translateBatchConcurrency,
    crafterQBearerToken,
    crafterQBearerTokenEnv,
    anchorPosition: anchorPositionProp,
    ...popoverProps
  } = props;

  const title = agentLabel ?? appBarTitle ?? 'Studio AI Assistant';
  const anchorPosition = anchorPositionProp ?? { top: 100, left: 100 };

  const [openAlertDialog, setOpenAlertDialog] = useState(false);

  const handleClose = () => {
    // setModelMenuAnchorEl(null);
  };

  return (
    <>
      <Popover
        open={open && !isMinimized}
        onClose={(e, reason) => onClose?.(e, reason ?? 'backdropClick')}
        keepMounted={isMinimized}
        anchorReference="none"
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        anchorPosition={anchorPosition}
        BackdropProps={{
          invisible: true,
          sx: { backgroundColor: 'rgba(0, 0, 0, 0.5)' }
        }}
        {...popoverProps}
        sx={{
          [`> .${paperClasses.root}`]: {
            width: '817px',
            height: '90%',
            display: 'flex',
            flexDirection: 'column',
            bottom: 10,
            right: 10
          },
          ...popoverProps?.sx
        }}
      >
        <DialogHeader
          title={title}
          sxs={{
            root: { boxShadow: theme.shadows[4], borderBottom: 'none' },
            subtitleWrapper: {
              width: '100%'
            }
          }}
          onMinimizeButtonClick={() => onMinimize?.()}
          onCloseButtonClick={(e) => onClose(e, null)}
        >
        </DialogHeader>
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
          {...(crafterQBearerTokenEnv?.trim() ? { crafterQBearerTokenEnv: crafterQBearerTokenEnv.trim() } : {})}
          {...(crafterQBearerToken?.trim() ? { crafterQBearerToken: crafterQBearerToken.trim() } : {})}
        />
        
      </Popover>
      <MinimizedBar open={isMinimized} onMaximize={onMaximize} title={title} />
      <AlertDialog
        disableBackdropClick
        disableEscapeKeyDown
        open={openAlertDialog}
        title="Close this chat?"
        body="The current conversation will be lost."
        buttons={
          <>
            <PrimaryButton
              onClick={(e) => {
                setOpenAlertDialog(false);
                onClose(e, null);
              }}
              autoFocus
              fullWidth
              size="large"
            >
              Close
            </PrimaryButton>
            <SecondaryButton
              onClick={() => {
                setOpenAlertDialog(false);
                onMinimize?.();
              }}
              fullWidth
              size="large"
            >
              Minimize
            </SecondaryButton>
            <SecondaryButton onClick={() => setOpenAlertDialog(false)} fullWidth size="large">
              Cancel
            </SecondaryButton>
          </>
        }
      />
    </>
  );
}

export default AiAssistantPopover;
