import {
  Autocomplete,
  Chip,
  Paper,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import type { ToolsPolicyFormState } from './aiAssistantToolsMcpUiModel';
import { BUILTIN_TOOL_NAME_OPTIONS } from './aiAssistantToolsMcpUiModel';
import AiAssistantIntentRecipeRoutingFields from './AiAssistantIntentRecipeRoutingFields';

export interface AiAssistantSiteOrchestrationToolsFormProps {
  value: ToolsPolicyFormState;
  onChange: (next: ToolsPolicyFormState) => void;
}

/**
 * Site-wide {@code tools.json} policy: built-in tool hide/whitelist and intent recipe routing.
 */
export default function AiAssistantSiteOrchestrationToolsForm(props: AiAssistantSiteOrchestrationToolsFormProps) {
  const { value, onChange } = props;

  return (
    <Stack spacing={3}>
      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography variant="subtitle2" gutterBottom>
          Built-In CMS Tools
        </Typography>
        <Typography variant="body2" color="text.secondary" paragraph>
          Optional lists use exact wire names. If <strong>Whitelist</strong> is non-empty, only those built-ins stay;
          <code> InvokeSiteUserTool</code> and dynamic <code>mcp_*</code> tools still register unless disabled under MCP.
        </Typography>
        <Stack spacing={2}>
          <Autocomplete
            multiple
            freeSolo
            options={[...BUILTIN_TOOL_NAME_OPTIONS]}
            value={value.disabledBuiltInTools}
            onChange={(_, v) => onChange({ ...value, disabledBuiltInTools: v.map(String) })}
            renderTags={(tagValue, getTagProps) =>
              tagValue.map((option, index) => (
                <Chip variant="outlined" label={option} size="small" {...getTagProps({ index })} key={`${option}-${index}`} />
              ))
            }
            renderInput={(params) => (
              <TextField {...params} label="Hide built-in tools" placeholder="e.g. GenerateImage" size="small" />
            )}
          />
          <Autocomplete
            multiple
            freeSolo
            options={[...BUILTIN_TOOL_NAME_OPTIONS]}
            value={value.enabledBuiltInTools}
            onChange={(_, v) => onChange({ ...value, enabledBuiltInTools: v.map(String) })}
            renderTags={(tagValue, getTagProps) =>
              tagValue.map((option, index) => (
                <Chip variant="outlined" label={option} size="small" {...getTagProps({ index })} key={`${option}-${index}`} />
              ))
            }
            renderInput={(params) => (
              <TextField
                {...params}
                label="Whitelist built-in tools (optional)"
                placeholder="Leave empty for no whitelist"
                size="small"
              />
            )}
          />
        </Stack>
      </Paper>
      <AiAssistantIntentRecipeRoutingFields
        value={value.intentRecipeRouting}
        onChange={(intentRecipeRouting) => onChange({ ...value, intentRecipeRouting })}
      />
    </Stack>
  );
}
