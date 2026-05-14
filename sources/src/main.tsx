import React, { useMemo, useState } from 'react';
import ReactDOM from 'react-dom/client';
import { Box, createTheme, IconButton, InputAdornment, TextField, Tooltip, useMediaQuery } from '@mui/material';
import AiAssistantLogo from './AiAssistantLogo';
import GlobalStyles from '@craftercms/studio-ui/components/GlobalStyles';
import CrafterThemeProvider from '@craftercms/studio-ui/components/CrafterThemeProvider';
import I18nProvider from '@craftercms/studio-ui/components/I18nProvider';

function AppWrap() {
  const prefersDarkMode = useMediaQuery('(prefers-color-scheme: dark)');
  const theme = useMemo(() => {
    const mode = prefersDarkMode ? 'dark' : 'light';
    return createTheme({
      palette: { mode },
      components: {
        MuiButton: { styleOverrides: { root: { textTransform: 'none' } } }
      }
    });
  }, [prefersDarkMode]);
  const [value, setValue] = useState('');
  const [anchorEl, setAnchorEl] = useState(null);
  return (
    <CrafterThemeProvider themeOptions={theme}>
      <I18nProvider>
        <Box sx={{ p: 5 }}>
          <div style={{ width: "100%", height: "500px" }}>
            <div>Roy was here</div>
          </div>
          <GlobalStyles cssBaseline={true} />
        </Box>
      </I18nProvider>
    </CrafterThemeProvider>
  );
}
