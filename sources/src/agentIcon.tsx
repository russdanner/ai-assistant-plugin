import React from 'react';
import Box from '@mui/material/Box';
import SystemIcon from '@craftercms/studio-ui/components/SystemIcon';
import ChatRounded from '@mui/icons-material/ChatRounded';
import EditRounded from '@mui/icons-material/EditRounded';
import SmartToyRounded from '@mui/icons-material/SmartToyRounded';
import SupportAgentRounded from '@mui/icons-material/SupportAgentRounded';
import PsychologyRounded from '@mui/icons-material/PsychologyRounded';
import AiAssistantIcon from './AiAssistant';

const ICON_MAP: Record<string, React.ComponentType<{ fontSize?: 'small' | 'inherit' | 'medium' | 'large' }>> = {
  '@mui/icons-material/ChatRounded': ChatRounded,
  '@mui/icons-material/EditRounded': EditRounded,
  '@mui/icons-material/SmartToyRounded': SmartToyRounded,
  '@mui/icons-material/SupportAgentRounded': SupportAgentRounded,
  '@mui/icons-material/PsychologyRounded': PsychologyRounded
};

/** Strip obvious script/event vectors from admin-supplied SVG in ui.xml (defense in depth). */
function sanitizeAgentInlineSvg(svg: string): string {
  let s = svg.trim();
  s = s.replace(/<script[\s\S]*?>[\s\S]*?<\/script>/gi, '');
  s = s.replace(/\son\w+\s*=\s*(["']).*?\1/gi, '');
  s = s.replace(/\son\w+\s*=\s*[^\s>]*/gi, '');
  return s;
}

export function getAgentIcon(iconId?: string): React.ReactNode {
  if (!iconId) return <AiAssistantIcon />;
  const raw = iconId.trim();
  if (/^<\s*svg[\s>/]/i.test(raw)) {
    const safe = sanitizeAgentInlineSvg(raw);
    return (
      <Box
        component="span"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 20,
          height: 20,
          flexShrink: 0,
          color: 'inherit',
          '& > svg': { width: '100%', height: '100%', display: 'block' }
        }}
        dangerouslySetInnerHTML={{ __html: safe }}
      />
    );
  }
  const Icon = ICON_MAP[raw];
  if (Icon) return <Icon fontSize="small" />;
  /** Same resolution as the rest of Studio `ui.xml`: {@link SystemIcon} → `components.get(id)` (e.g. `craftercms.icons.*`, `@mui/icons-material/*`). */
  if (raw.startsWith('craftercms.') || raw.startsWith('@mui/')) {
    return <SystemIcon icon={{ id: raw }} svgIconProps={{ fontSize: 'small', sx: { color: 'inherit' } }} />;
  }
  return <AiAssistantIcon />;
}
