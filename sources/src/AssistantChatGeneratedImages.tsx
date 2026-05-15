import React, { useMemo } from 'react';
import { Box, Stack } from '@mui/material';
import StudioDraggableImage from './StudioDraggableImage';

/**
 * Renders GenerateImage output as {@link StudioDraggableImage} tiles. Sources come from
 * {@link combineGeneratedImageSources} (SSE metadata + same-turn text recovery for large {@code data:} URLs).
 */
export default function AssistantChatGeneratedImages(props: Readonly<{ sources: string[] }>) {
  const uniq = useMemo(
    () => [...new Set(props.sources.filter((s) => typeof s === 'string' && s.trim().length > 12).map((s) => s.trim()))],
    [props.sources]
  );
  if (!uniq.length) return null;
  return (
    <Stack spacing={1.25} sx={{ my: 1, maxWidth: '100%' }} data-aiassistant-generated-images>
      {uniq.map((src, i) => (
        <Box key={`${i}-${src.slice(0, 64)}`} sx={{ alignSelf: 'flex-start', maxWidth: '100%' }}>
          <StudioDraggableImage src={src} alt="" />
        </Box>
      ))}
    </Stack>
  );
}
