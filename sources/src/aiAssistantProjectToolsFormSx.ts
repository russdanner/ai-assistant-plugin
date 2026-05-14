import type { SxProps, Theme } from '@mui/material/styles';

/**
 * Descendant styles for Project Tools tab bodies: clearer headings/legends and slightly
 * denser hierarchy without affecting portaled dialogs (they render outside this subtree).
 */
export const aiAssistantProjectToolsPanelContentSx: SxProps<Theme> = {
  '& .MuiTypography-h5': {
    fontSize: '1.5rem',
    fontWeight: 700,
    color: 'text.primary',
    letterSpacing: '-0.02em',
    lineHeight: 1.25
  },
  '& .MuiTypography-h6': {
    fontSize: '1.25rem',
    fontWeight: 700,
    color: 'text.primary',
    letterSpacing: '-0.015em',
    lineHeight: 1.3
  },
  '& .MuiTypography-subtitle1': {
    fontSize: '1.0625rem',
    fontWeight: 600,
    color: 'text.primary',
    letterSpacing: '-0.01em',
    lineHeight: 1.35
  },
  '& .MuiTypography-subtitle2': {
    fontSize: '1rem',
    fontWeight: 600,
    color: 'text.primary',
    letterSpacing: '-0.01em',
    lineHeight: 1.35
  },
  '& fieldset > legend.MuiFormLabel-root': {
    fontSize: '1.05rem',
    fontWeight: 600,
    color: 'text.primary',
    lineHeight: 1.5
  }
};
