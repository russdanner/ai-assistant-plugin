import { useMemo } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { StreamLanguage } from '@codemirror/language';
import { groovy } from '@codemirror/legacy-modes/mode/groovy';
import { EditorView } from '@codemirror/view';
import { useTheme } from '@mui/material/styles';
import Box from '@mui/material/Box';
import type { Extension } from '@codemirror/state';

/** Language modes used by Studio sandbox / script paths. */
export type AiAssistantStudioCodeEditorLanguage = 'groovy' | 'markdown' | 'json';

export function inferStudioSandboxEditorLanguage(studioPath: string): AiAssistantStudioCodeEditorLanguage {
  const p = (studioPath ?? '').toLowerCase();
  if (p.endsWith('.json')) {
    return 'json';
  }
  if (p.endsWith('.md')) {
    return 'markdown';
  }
  return 'groovy';
}

export interface AiAssistantStudioCodeEditorProps {
  value: string;
  onChange?: (next: string) => void;
  language: AiAssistantStudioCodeEditorLanguage;
  /** When true, user cannot edit (still syntax-highlighted). */
  readOnly?: boolean;
  /** Minimum editor height in pixels (ignored when **flexFill** is true). */
  minHeightPx?: number;
  /** Fill available height in a flex parent (set **minHeight: 0** on ancestors). */
  flexFill?: boolean;
  /** Optional id for tests / accessibility. */
  id?: string;
}

/**
 * CodeMirror 6 editor for Groovy, Markdown, and JSON — used for site script sandboxes (replacing plain multiline
 * {@code TextField}s) while staying theme-aligned with MUI.
 */
export default function AiAssistantStudioCodeEditor(props: Readonly<AiAssistantStudioCodeEditorProps>) {
  const { value, onChange, language, readOnly, minHeightPx = 280, flexFill, id } = props;
  const theme = useTheme();

  const themeExtension: Extension = useMemo(
    () =>
      EditorView.theme(
        {
          '&': {
            backgroundColor: theme.palette.mode === 'dark' ? theme.palette.grey[900] : theme.palette.background.paper
          },
          '.cm-editor': {
            backgroundColor: theme.palette.mode === 'dark' ? theme.palette.grey[900] : theme.palette.background.paper,
            borderRadius: 1,
            border: `1px solid ${theme.palette.divider}`
          },
          '.cm-editor.cm-focused': { outline: 'none' },
          '.cm-scroller': {
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
            fontSize: 13,
            /** Required when the editor lives in a flex dialog — otherwise the document grows and never scrolls. */
            overflow: 'auto !important',
            overscrollBehavior: 'contain'
          },
          '.cm-gutters': {
            backgroundColor: theme.palette.mode === 'dark' ? theme.palette.grey[800] : theme.palette.grey[100],
            borderRight: `1px solid ${theme.palette.divider}`,
            color: theme.palette.text.secondary
          },
          '.cm-activeLineGutter': { backgroundColor: 'transparent' }
        },
        { dark: theme.palette.mode === 'dark' }
      ),
    [
      theme.palette.mode,
      theme.palette.background.paper,
      theme.palette.divider,
      theme.palette.grey,
      theme.palette.text.secondary
    ]
  );

  const languageExtension: Extension = useMemo(() => {
    switch (language) {
      case 'json':
        return json();
      case 'markdown':
        return markdown();
      default:
        return StreamLanguage.define(groovy);
    }
  }, [language]);

  const extensions = useMemo(() => [themeExtension, languageExtension], [themeExtension, languageExtension]);

  const heightStyle = flexFill ? '100%' : `${Math.max(200, minHeightPx)}px`;

  return (
    <Box
      id={id}
      sx={
        flexFill
          ? {
              flex: '1 1 auto',
              minHeight: 0,
              alignSelf: 'stretch',
              display: 'flex',
              flexDirection: 'column',
              /**
               * @uiw/react-codemirror mounts a single root div (cm-theme-*). Give it a bounded flex height so
               * CodeMirror’s internal `height: 100%` / `.cm-scroller` can scroll instead of growing the dialog.
               */
              '& > div': {
                flex: '1 1 auto',
                minHeight: 0,
                display: 'flex',
                flexDirection: 'column',
                overflow: 'hidden'
              }
            }
          : undefined
      }
    >
      <CodeMirror
        value={value}
        height={heightStyle}
        theme="none"
        extensions={extensions}
        readOnly={readOnly}
        editable={!readOnly}
        indentWithTab
        basicSetup={{
          foldGutter: true,
          highlightActiveLine: !readOnly,
          highlightActiveLineGutter: !readOnly
        }}
        onChange={readOnly || !onChange ? undefined : (v) => onChange(v)}
      />
    </Box>
  );
}
