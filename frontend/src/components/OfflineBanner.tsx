import { useNavigate } from 'react-router-dom';
import { Alert, Button } from '@mui/material';

import { useOfflineQueue } from '../context/OfflineQueueContext';

export default function OfflineBanner() {
  const navigate = useNavigate();
  const { online, pendingCount, conflictCount, syncing, authExpired, sync } = useOfflineQueue();

  if (!online) {
    return (
      <Alert severity="warning" sx={{ mb: 2 }}>
        Mode hors-ligne — les paiements sont enregistrés sur l'appareil et seront synchronisés au
        retour du réseau.
      </Alert>
    );
  }

  if (authExpired) {
    return (
      <Alert
        severity="warning"
        sx={{ mb: 2 }}
        action={
          <Button color="inherit" size="small" onClick={() => navigate('/login')}>
            Se reconnecter
          </Button>
        }
      >
        Session expirée — {pendingCount} paiement(s) en attente. Reconnectez-vous pour les
        synchroniser.
      </Alert>
    );
  }

  if (conflictCount > 0) {
    return (
      <Alert severity="error" sx={{ mb: 2 }}>
        {conflictCount} paiement(s) en conflit — voir la page Paiements.
      </Alert>
    );
  }

  if (pendingCount > 0) {
    return (
      <Alert
        severity="info"
        sx={{ mb: 2 }}
        action={
          <Button color="inherit" size="small" disabled={syncing} onClick={() => void sync()}>
            Synchroniser
          </Button>
        }
      >
        {pendingCount} paiement(s) en attente de synchronisation
      </Alert>
    );
  }

  return null;
}
