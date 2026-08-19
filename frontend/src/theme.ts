import { createTheme } from '@mui/material/styles';
import { frFR } from '@mui/material/locale';

/**
 * Mode clair uniquement, contrastes eleves et typographie large :
 * l'interface doit rester lisible pour un utilisateur non informaticien.
 */
export const theme = createTheme(
  {
    palette: {
      mode: 'light',
      primary: { main: '#21529c', light: '#4b7ac8', dark: '#153a72' },
      secondary: { main: '#0f9d58' },
      success: { main: '#188038' },
      warning: { main: '#e37400' },
      error: { main: '#c5221f' },
      background: { default: '#f4f6fa', paper: '#ffffff' },
      text: { primary: '#1c2331', secondary: '#5f6b7c' },
    },
    shape: { borderRadius: 12 },
    typography: {
      fontFamily: '"Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
      h4: { fontWeight: 700 },
      h5: { fontWeight: 700 },
      h6: { fontWeight: 600 },
      button: { textTransform: 'none', fontWeight: 600 },
    },
    components: {
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: { root: { borderRadius: 10, paddingInline: 18 } },
      },
      MuiPaper: {
        styleOverrides: {
          root: { backgroundImage: 'none' },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          head: { fontWeight: 700, backgroundColor: '#eef2f8', whiteSpace: 'nowrap' },
        },
      },
      MuiChip: {
        styleOverrides: { root: { fontWeight: 600 } },
      },
      MuiTextField: {
        defaultProps: { size: 'small' },
      },
    },
  },
  frFR,
);
