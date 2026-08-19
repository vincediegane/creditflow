import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Avatar,
  Box,
  Divider,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import AssessmentIcon from '@mui/icons-material/Assessment';
import DashboardIcon from '@mui/icons-material/SpaceDashboard';
import EventIcon from '@mui/icons-material/EventAvailable';
import GroupIcon from '@mui/icons-material/Group';
import Inventory2Icon from '@mui/icons-material/Inventory2';
import LockResetIcon from '@mui/icons-material/LockReset';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import NotificationsActiveIcon from '@mui/icons-material/NotificationsActive';
import PaymentsIcon from '@mui/icons-material/Payments';
import PersonIcon from '@mui/icons-material/Person';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import UploadFileIcon from '@mui/icons-material/UploadFile';

import { useAuth } from '../auth/AuthContext';
import { initials } from '../utils/format';
import ChangePasswordDialog from './ChangePasswordDialog';
import GlobalSearchBar from './GlobalSearchBar';

const DRAWER_WIDTH = 248;

const NAV_ITEMS = [
  { to: '/', label: 'Tableau de bord', icon: <DashboardIcon />, end: true },
  { to: '/clients', label: 'Clients', icon: <GroupIcon /> },
  { to: '/produits', label: 'Produits', icon: <Inventory2Icon /> },
  { to: '/ventes', label: 'Ventes à crédit', icon: <ReceiptLongIcon /> },
  { to: '/paiements', label: 'Paiements', icon: <PaymentsIcon /> },
  { to: '/echeances', label: 'Échéances', icon: <EventIcon /> },
  { to: '/relances', label: 'Relances', icon: <NotificationsActiveIcon /> },
  { to: '/rapports', label: 'Rapports', icon: <AssessmentIcon /> },
  { to: '/reprise', label: 'Reprise de données', icon: <UploadFileIcon /> },
  { to: '/utilisateurs', label: 'Utilisateurs', icon: <PersonIcon />, adminOnly: true },
];

export default function AppLayout() {
  const theme = useTheme();
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'));
  const navigate = useNavigate();
  const { user, logout } = useAuth();

  const [mobileOpen, setMobileOpen] = useState(false);
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);
  const [passwordOpen, setPasswordOpen] = useState(false);

  // Compte livré avec un mot de passe d'installation : on impose son remplacement.
  const forcedPasswordChange = Boolean(user?.mustChangePassword);

  const handleLogout = () => {
    setAnchorEl(null);
    logout();
    navigate('/login', { replace: true });
  };

  const drawerContent = (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Toolbar sx={{ px: 2.5 }}>
        <Box>
          <Typography variant="h6" color="primary" sx={{ lineHeight: 1.2 }}>
            CreditFlow
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Ventes à crédit
          </Typography>
        </Box>
      </Toolbar>
      <Divider />
      <List sx={{ px: 1.5, py: 2, flexGrow: 1 }}>
        {NAV_ITEMS.filter((item) => !item.adminOnly || user?.role === 'ADMIN').map((item) => (
          <ListItemButton
            key={item.to}
            component={NavLink}
            to={item.to}
            end={item.end}
            onClick={() => setMobileOpen(false)}
            sx={{
              borderRadius: 2,
              mb: 0.5,
              py: 1.2,
              '&.active': {
                backgroundColor: 'primary.main',
                color: 'common.white',
                '& .MuiListItemIcon-root': { color: 'common.white' },
                '&:hover': { backgroundColor: 'primary.dark' },
              },
            }}
          >
            <ListItemIcon sx={{ minWidth: 40 }}>{item.icon}</ListItemIcon>
            <ListItemText primary={item.label} primaryTypographyProps={{ fontWeight: 600 }} />
          </ListItemButton>
        ))}
      </List>
      <Divider />
      <Box sx={{ p: 2 }}>
        <Typography variant="caption" color="text.secondary">
          Connecté : {user?.fullName ?? '—'}
        </Typography>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      <AppBar
        position="fixed"
        color="inherit"
        elevation={0}
        sx={{
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          ml: { md: `${DRAWER_WIDTH}px` },
          borderBottom: '1px solid',
          borderColor: 'divider',
        }}
      >
        <Toolbar sx={{ gap: 2 }}>
          <IconButton
            edge="start"
            onClick={() => setMobileOpen(true)}
            sx={{ display: { md: 'none' } }}
            aria-label="Ouvrir le menu"
          >
            <MenuIcon />
          </IconButton>

          <GlobalSearchBar />

          <Box sx={{ flexGrow: 1 }} />

          <Tooltip title="Compte">
            <IconButton onClick={(event) => setAnchorEl(event.currentTarget)}>
              <Avatar sx={{ width: 36, height: 36, bgcolor: 'primary.main', fontSize: 15 }}>
                {initials(user?.fullName ?? 'CF')}
              </Avatar>
            </IconButton>
          </Tooltip>
          <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={() => setAnchorEl(null)}>
            <MenuItem disabled>{user?.username}</MenuItem>
            <Divider />
            <MenuItem
              onClick={() => {
                setAnchorEl(null);
                setPasswordOpen(true);
              }}
            >
              <ListItemIcon>
                <LockResetIcon fontSize="small" />
              </ListItemIcon>
              Changer le mot de passe
            </MenuItem>
            <MenuItem onClick={handleLogout}>
              <ListItemIcon>
                <LogoutIcon fontSize="small" />
              </ListItemIcon>
              Se déconnecter
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>

      <Box component="nav" sx={{ width: { md: DRAWER_WIDTH }, flexShrink: { md: 0 } }}>
        <Drawer
          variant={isDesktop ? 'permanent' : 'temporary'}
          open={isDesktop || mobileOpen}
          onClose={() => setMobileOpen(false)}
          ModalProps={{ keepMounted: true }}
          sx={{
            '& .MuiDrawer-paper': {
              width: DRAWER_WIDTH,
              boxSizing: 'border-box',
              borderRight: '1px solid',
              borderColor: 'divider',
            },
          }}
        >
          {drawerContent}
        </Drawer>
      </Box>

      <Box
        component="main"
        sx={{
          flexGrow: 1,
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          px: { xs: 2, md: 4 },
          pb: 6,
        }}
      >
        <Toolbar />
        <Outlet />
      </Box>

      <ChangePasswordDialog
        open={passwordOpen || forcedPasswordChange}
        forced={forcedPasswordChange}
        onClose={() => setPasswordOpen(false)}
      />
    </Box>
  );
}
