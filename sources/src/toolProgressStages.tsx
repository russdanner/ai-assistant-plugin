import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { useLayoutEffect, useRef } from 'react';

import { MarkdownMessage } from './MarkdownMessage';

export type ToolPipelineStage = 'main' | 'verification' | 'summary';

export type ToolProgressSegment = {
  text: string;
  stage: ToolPipelineStage;
};

const STAGE_ORDER: ToolPipelineStage[] = ['main', 'verification', 'summary'];

export function isToolPipelineStage(v: unknown): v is ToolPipelineStage {
  return v === 'main' || v === 'verification' || v === 'summary';
}

/** Prefer server {@code metadata.pipelineStage}; fall back to tool name heuristics. */
export function toolPipelineStageFromMetadata(meta: Record<string, unknown> | undefined): ToolPipelineStage {
  if (meta && isToolPipelineStage(meta.pipelineStage)) {
    return meta.pipelineStage;
  }
  const tool = String(meta?.tool ?? '').trim();
  const phase = String(meta?.phase ?? '').trim().toLowerCase();
  if (phase === 'debug') return 'summary';
  if (tool === 'GetPreviewHtml' || tool === 'analyze_template') return 'verification';
  if (tool === 'Tools-loop chat') return 'summary';
  return 'main';
}

function segmentJoin(prior: string, chunk: string): string {
  if (!prior) return chunk;
  const join = prior.endsWith('\n\n') ? (chunk.startsWith('\n') ? '' : '\n') : '\n\n';
  return prior + join + chunk;
}

export function appendToolProgressSegment(
  segments: ToolProgressSegment[] | undefined,
  chunk: string,
  stage: ToolPipelineStage
): ToolProgressSegment[] {
  if (!chunk) return segments ?? [];
  const list = segments?.length ? [...segments] : [];
  const last = list[list.length - 1];
  if (last && last.stage === stage) {
    list[list.length - 1] = { stage, text: segmentJoin(last.text, chunk) };
  } else {
    list.push({ stage, text: chunk });
  }
  return list;
}

export function toolProgressSegmentsFromLegacyText(text: string | undefined): ToolProgressSegment[] {
  const t = text?.trim();
  if (!t) return [];
  return [{ stage: 'main', text: t }];
}

function stageTitle(stage: ToolPipelineStage): string {
  switch (stage) {
    case 'verification':
      return 'Verification';
    case 'summary':
      return 'Summary';
    default:
      return 'Main work';
  }
}

function stagePanelSx(stage: ToolPipelineStage, theme: ReturnType<typeof useTheme>) {
  switch (stage) {
    case 'verification':
      return {
        mb: 1,
        px: 1,
        py: 0.75,
        borderRadius: 1,
        border: '1px solid',
        borderColor: alpha(theme.palette.info.main, 0.45),
        bgcolor: alpha(theme.palette.info.main, theme.palette.mode === 'dark' ? 0.14 : 0.08)
      };
    case 'summary':
      return {
        mb: 1,
        px: 1,
        py: 0.75,
        borderRadius: 1,
        border: '1px solid',
        borderColor: alpha(theme.palette.secondary.main, 0.4),
        bgcolor: alpha(theme.palette.secondary.main, theme.palette.mode === 'dark' ? 0.16 : 0.09)
      };
    default:
      return {
        mb: 1,
        px: 1,
        py: 0.75,
        borderRadius: 1,
        border: '1px solid',
        borderColor: 'divider',
        bgcolor: alpha(theme.palette.action.hover, theme.palette.mode === 'dark' ? 0.35 : 1)
      };
  }
}

function stageTitleColor(stage: ToolPipelineStage, theme: ReturnType<typeof useTheme>) {
  switch (stage) {
    case 'verification':
      return theme.palette.info.main;
    case 'summary':
      return theme.palette.secondary.main;
    default:
      return theme.palette.text.secondary;
  }
}

/** Grouped tool-progress strip: main / verification / summary panels. */
export function ToolProgressScrollArea(
  props: Readonly<{ segments?: ToolProgressSegment[]; text?: string }>
) {
  const ref = useRef<HTMLDivElement>(null);
  const theme = useTheme();
  const mainSx = stagePanelSx('main', theme);
  const verificationSx = stagePanelSx('verification', theme);
  const summarySx = stagePanelSx('summary', theme);
  const sxByStage: Record<ToolPipelineStage, ReturnType<typeof stagePanelSx>> = {
    main: mainSx,
    verification: verificationSx,
    summary: summarySx
  };
  const segments =
    props.segments && props.segments.length > 0
      ? props.segments
      : toolProgressSegmentsFromLegacyText(props.text);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [segments]);

  if (!segments.length) return null;

  const ordered = STAGE_ORDER.flatMap((stage) => segments.filter((s) => s.stage === stage));

  return (
    <Box
      ref={ref}
      sx={{
        maxHeight: 240,
        overflowY: 'auto',
        mb: 1
      }}
    >
      {ordered.map((seg, i) => (
        <Box key={`${seg.stage}-${i}`} sx={sxByStage[seg.stage]}>
          <Typography
            variant="caption"
            component="div"
            sx={{
              display: 'block',
              fontWeight: 600,
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              fontSize: '0.65rem',
              mb: 0.5,
              color: stageTitleColor(seg.stage, theme)
            }}
          >
            {stageTitle(seg.stage)}
          </Typography>
          <MarkdownMessage text={seg.text} />
        </Box>
      ))}
    </Box>
  );
}

/** Matches {@link ToolProgressScrollArea} summary panel styling. */
export function PipelineSummaryPhaseBanner() {
  const theme = useTheme();
  return (
    <Box
      sx={{
        mt: 0.75,
        mb: 0,
        px: 1,
        py: 0.75,
        borderRadius: 1,
        border: '1px solid',
        borderColor: alpha(theme.palette.secondary.main, 0.4),
        bgcolor: alpha(theme.palette.secondary.main, theme.palette.mode === 'dark' ? 0.16 : 0.09)
      }}
    >
      <Typography
        variant="caption"
        sx={{
          display: 'block',
          fontWeight: 600,
          textTransform: 'uppercase',
          letterSpacing: '0.06em',
          fontSize: '0.65rem',
          mb: 0.25,
          color: theme.palette.secondary.main
        }}
      >
        Summary
      </Typography>
      <Typography variant="caption" component="p" sx={{ color: 'text.secondary', fontStyle: 'italic', mb: 0 }}>
        Summarizing results…
      </Typography>
    </Box>
  );
}
