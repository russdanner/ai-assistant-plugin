import React from 'react';
import { Box, Typography, useTheme } from '@mui/material';
import { keyframes } from '@mui/material/styles';

/** Shifting gradient while {@code GenerateImage} is running (server tool-progress uses an ellipsis row until “finished”). */
const cqGenerateImageAuraShift = keyframes({
  '0%': { backgroundPosition: '0% 40%' },
  '100%': { backgroundPosition: '100% 60%' }
});

/**
 * Blurred shifting gradient stand-in for the incoming chat image; similar footprint to the draggable chat image card.
 */
export default function GenerateImageBlurredPlaceholder() {
  const theme = useTheme();
  const dark = theme.palette.mode === 'dark';
  const a = dark ? '#5e35b1' : '#e1bee7';
  const b = dark ? '#1565c0' : '#bbdefb';
  const c = dark ? '#00695c' : '#b2dfdb';
  const d = dark ? '#4527a0' : '#d1c4e9';
  return (
    <Box
      role="status"
      aria-label="Generating image preview"
      sx={{
        my: 1,
        display: 'block',
        maxWidth: '100%',
        position: 'relative',
        borderRadius: 1,
        border: `1px solid ${theme.palette.mode === 'dark' ? theme.palette.grey[700] : theme.palette.grey[300]}`,
        overflow: 'hidden',
        bgcolor: theme.palette.mode === 'dark' ? theme.palette.grey[900] : theme.palette.grey[50],
        aspectRatio: '3 / 2',
        maxHeight: 320,
        minHeight: 168
      }}
    >
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          inset: -48,
          background: `linear-gradient(118deg, ${a}, ${b}, ${c}, ${d}, ${a})`,
          backgroundSize: '280% 280%',
          filter: 'blur(36px)',
          opacity: dark ? 0.92 : 0.88,
          animation: `${cqGenerateImageAuraShift} 3.2s ease-in-out infinite`,
          '@media (prefers-reduced-motion: reduce)': {
            animation: 'none',
            backgroundPosition: '50% 50%'
          }
        }}
      />
      <Box
        sx={{
          position: 'relative',
          zIndex: 1,
          minHeight: 168,
          maxHeight: 320,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          px: 2,
          py: 2,
          background:
            theme.palette.mode === 'dark' ? 'rgba(0,0,0,0.18)' : 'rgba(255,255,255,0.28)',
          backdropFilter: 'blur(2px)'
        }}
      >
        <Typography variant="caption" color="text.secondary" sx={{ textAlign: 'center', lineHeight: 1.45, opacity: 0.9 }}>
          Generating image…
        </Typography>
      </Box>
    </Box>
  );
}
