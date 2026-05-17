import {
  FormControlLabel,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography
} from '@mui/material';
import type { IntentRecipeRoutingFormState } from './aiAssistantToolsMcpUiModel';

export interface AiAssistantIntentRecipeRoutingFieldsProps {
  value: IntentRecipeRoutingFormState;
  onChange: (next: IntentRecipeRoutingFormState) => void;
}

export default function AiAssistantIntentRecipeRoutingFields(props: AiAssistantIntentRecipeRoutingFieldsProps) {
  const { value, onChange } = props;
  const patch = (partial: Partial<IntentRecipeRoutingFormState>) => onChange({ ...value, ...partial });

  return (
    <Paper variant="outlined" sx={{ p: 2.5 }}>
      <Typography variant="subtitle2" gutterBottom>
        Intent recipe routing
      </Typography>
      <Typography variant="body2" color="text.secondary" paragraph>
        Optional pre-tools router: classifies the author message, runs read-only prefetch steps from a matched recipe,
        then prepends guidance to the main CMS tool loop.
      </Typography>
      <Stack spacing={2}>
        <FormControlLabel
          control={<Switch checked={value.enabled} onChange={(_, c) => patch({ enabled: c })} size="small" />}
          label="Enable intent recipe router"
        />
        <FormControlLabel
          control={
            <Switch
              checked={value.engineEnabled}
              onChange={(_, c) => patch({ engineEnabled: c })}
              size="small"
              disabled={!value.enabled}
            />
          }
          label="Prefetch engine (server read-only tool steps before tools loop)"
        />
        <TextField
          label="Min confidence (0–1)"
          value={value.minConfidence}
          onChange={(ev) => patch({ minConfidence: ev.target.value })}
          fullWidth
          size="small"
          disabled={!value.enabled}
          helperText="Router must meet this confidence to apply a recipe (default 0.55)."
        />
        <FormControlLabel
          control={
            <Switch
              checked={value.requestClarificationOnUnmatched}
              onChange={(_, c) => patch({ requestClarificationOnUnmatched: c })}
              size="small"
              disabled={!value.enabled}
            />
          }
          label="Tools-off clarification when unmatched"
        />
        <TextField
          label="Custom recipes path (optional)"
          value={value.customRecipesPath}
          onChange={(ev) => patch({ customRecipesPath: ev.target.value })}
          fullWidth
          size="small"
          disabled={!value.enabled}
          placeholder="/scripts/aiassistant/config/intent-recipes.json"
          helperText="Studio module path; merged over bundled defaults by recipe id."
        />
      </Stack>
    </Paper>
  );
}
