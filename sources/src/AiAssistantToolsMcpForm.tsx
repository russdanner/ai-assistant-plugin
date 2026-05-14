import AddRounded from '@mui/icons-material/AddRounded';
import DeleteOutlineRounded from '@mui/icons-material/DeleteOutlineRounded';
import {
  Autocomplete,
  Button,
  Chip,
  FormControlLabel,
  IconButton,
  Link,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography
} from '@mui/material';
import type { McpServerFormRow, ToolsPolicyFormState } from './aiAssistantToolsMcpUiModel';
import { BUILTIN_TOOL_NAME_OPTIONS } from './aiAssistantToolsMcpUiModel';

function emptyMcpServerRow(): McpServerFormRow {
  return { id: '', url: '', readTimeoutMs: '', headerPairs: [{ key: '', value: '' }] };
}

export interface AiAssistantToolsMcpFormProps {
  value: ToolsPolicyFormState;
  onChange: (next: ToolsPolicyFormState) => void;
}

export default function AiAssistantToolsMcpForm(props: AiAssistantToolsMcpFormProps) {
  const { value, onChange } = props;

  const setMcpEnabled = (mcpEnabled: boolean) => {
    onChange({ ...value, mcpEnabled });
  };

  const updateServer = (index: number, row: McpServerFormRow) => {
    const mcpServers = value.mcpServers.map((r, i) => (i === index ? row : r));
    onChange({ ...value, mcpServers });
  };

  const addServer = () => {
    onChange({ ...value, mcpServers: [...value.mcpServers, emptyMcpServerRow()] });
  };

  const removeServer = (index: number) => {
    onChange({ ...value, mcpServers: value.mcpServers.filter((_, i) => i !== index) });
  };

  return (
    <Stack spacing={4}>
      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography variant="subtitle2" gutterBottom>
          Built-In CMS Tools:
        </Typography>
        <Typography variant="body2" color="text.secondary" paragraph>
          Optional lists use exact wire names (see product docs). If <strong>Whitelist</strong> is non-empty, only those
          built-ins stay; <code>InvokeSiteUserTool</code> and dynamic <code>mcp_*</code> tools still register unless you
          disable them below or in <strong>Hide MCP tools</strong>.
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

      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
          <Typography variant="subtitle2">MCP (Streamable HTTP):</Typography>
          <FormControlLabel
            control={<Switch checked={value.mcpEnabled} onChange={(_, c) => setMcpEnabled(c)} size="small" />}
            label="Enable MCP client"
          />
        </Stack>
        <Typography variant="body2" color="text.secondary" paragraph>
          When enabled, each server below is contacted on chat requests to list and call remote tools. URLs must pass the
          same outbound rules as FetchHttpUrl.
        </Typography>
        <Typography variant="body2" color="text.secondary" paragraph>
          Example (streamable HTTP, optional headers, read-only URL patterns):{' '}
          <Link
            href="https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md"
            target="_blank"
            rel="noopener noreferrer"
          >
            GitHub MCP Server — remote-server.md
          </Link>
          .
        </Typography>
        {value.mcpEnabled ? (
          <>
            <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
              <Typography variant="body2">MCP servers</Typography>
              <Button size="small" startIcon={<AddRounded />} onClick={addServer}>
                Add server
              </Button>
            </Stack>
            {value.mcpServers.length === 0 ? (
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                No servers yet. Use Add server to register a Streamable HTTP MCP endpoint.
              </Typography>
            ) : (
              <Table size="small" sx={{ border: 1, borderColor: 'divider', borderRadius: 1, mb: 1 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>Server id</TableCell>
                    <TableCell>MCP URL and optional headers</TableCell>
                    <TableCell width={120}>Timeout (ms)</TableCell>
                    <TableCell align="right" width={88}>
                      {' '}
                    </TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {value.mcpServers.map((row, si) => (
                    <TableRow key={si}>
                      <TableCell sx={{ verticalAlign: 'top' }}>
                        <TextField
                          size="small"
                          fullWidth
                          value={row.id}
                          onChange={(e) => updateServer(si, { ...row, id: e.target.value })}
                          placeholder="e.g. docs"
                        />
                      </TableCell>
                      <TableCell sx={{ verticalAlign: 'top' }}>
                        <TextField
                          size="small"
                          fullWidth
                          value={row.url}
                          onChange={(e) => updateServer(si, { ...row, url: e.target.value })}
                          placeholder="https://host/…/mcp"
                        />
                        <Stack spacing={0.5} sx={{ mt: 1 }}>
                          <Typography variant="caption" color="text.secondary">
                            Optional headers
                          </Typography>
                          {row.headerPairs.map((hp, hi) => (
                            <Stack key={hi} direction="row" spacing={0.5} alignItems="center">
                              <TextField
                                size="small"
                                label="Name"
                                value={hp.key}
                                onChange={(e) => {
                                  const headerPairs = row.headerPairs.map((p, j) =>
                                    j === hi ? { ...p, key: e.target.value } : p
                                  );
                                  updateServer(si, { ...row, headerPairs });
                                }}
                                sx={{ flex: 1 }}
                              />
                              <TextField
                                size="small"
                                label="Value"
                                type={hp.key.trim().toLowerCase() === 'authorization' ? 'password' : 'text'}
                                autoComplete="off"
                                value={hp.value}
                                onChange={(e) => {
                                  const headerPairs = row.headerPairs.map((p, j) =>
                                    j === hi ? { ...p, value: e.target.value } : p
                                  );
                                  updateServer(si, { ...row, headerPairs });
                                }}
                                sx={{ flex: 2 }}
                              />
                              <IconButton
                                size="small"
                                aria-label="Remove header"
                                onClick={() => {
                                  const headerPairs = row.headerPairs.filter((_, j) => j !== hi);
                                  updateServer(si, {
                                    ...row,
                                    headerPairs: headerPairs.length ? headerPairs : [{ key: '', value: '' }]
                                  });
                                }}
                              >
                                <DeleteOutlineRounded fontSize="small" />
                              </IconButton>
                            </Stack>
                          ))}
                          <Button
                            size="small"
                            onClick={() =>
                              updateServer(si, {
                                ...row,
                                headerPairs: [...row.headerPairs, { key: '', value: '' }]
                              })
                            }
                          >
                            Add header
                          </Button>
                        </Stack>
                      </TableCell>
                      <TableCell sx={{ verticalAlign: 'top' }}>
                        <TextField
                          size="small"
                          fullWidth
                          value={row.readTimeoutMs}
                          onChange={(e) => updateServer(si, { ...row, readTimeoutMs: e.target.value })}
                          placeholder="120000"
                        />
                      </TableCell>
                      <TableCell align="right" sx={{ verticalAlign: 'top' }}>
                        <Button size="small" color="error" startIcon={<DeleteOutlineRounded />} onClick={() => removeServer(si)}>
                          Remove
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            <Autocomplete
              multiple
              freeSolo
              options={[]}
              value={value.disabledMcpTools}
              onChange={(_, v) => onChange({ ...value, disabledMcpTools: v.map(String) })}
              renderTags={(tagValue, getTagProps) =>
                tagValue.map((option, index) => (
                  <Chip variant="outlined" label={option} size="small" {...getTagProps({ index })} key={`${option}-${index}`} />
                ))
              }
              renderInput={(params) => (
                <TextField {...params} label="Hide MCP wire tools" placeholder="e.g. mcp_docs_search" size="small" />
              )}
            />
          </>
        ) : null}
      </Paper>
    </Stack>
  );
}
