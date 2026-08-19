import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Snackbar,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import WhatsAppIcon from '@mui/icons-material/WhatsApp';

import { errorMessage } from '../api/client';
import { remindersApi } from '../api/endpoints';

interface Props {
  open: boolean;
  onClose: () => void;
  customerId?: number | null;
  saleId?: number | null;
  customerName?: string;
  phone?: string;
}

/**
 * MVP : aucun envoi automatique. Le message est genere puis copie par le
 * commercant. Le bouton WhatsApp ouvre simplement wa.me avec le texte pre-rempli.
 */
export default function ReminderDialog({
  open,
  onClose,
  customerId,
  saleId,
  customerName,
  phone,
}: Props) {
  const [message, setMessage] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const mutation = useMutation({
    mutationFn: remindersApi.generate,
    onSuccess: (data) => setMessage(data.message),
    onError: (err) => setError(errorMessage(err, 'Impossible de générer la relance')),
  });

  useEffect(() => {
    if (!open) {
      return;
    }
    setMessage('');
    setError(null);
    mutation.mutate({
      saleId: saleId ?? undefined,
      customerId: saleId ? undefined : (customerId ?? undefined),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, saleId, customerId]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(message);
      setCopied(true);
    } catch {
      setError('Copie impossible : sélectionnez le texte et copiez-le manuellement.');
    }
  };

  const openWhatsApp = () => {
    const cleanPhone = (phone ?? '').replace(/[^0-9]/g, '');
    const url = `https://wa.me/${cleanPhone}?text=${encodeURIComponent(message)}`;
    window.open(url, '_blank', 'noopener');
  };

  return (
    <>
      <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
        <DialogTitle>Message de relance {customerName ? `— ${customerName}` : ''}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <Typography variant="body2" color="text.secondary">
              Le message ci-dessous est généré automatiquement. Copiez-le puis envoyez-le par
              WhatsApp ou SMS.
            </Typography>
            <TextField
              multiline
              minRows={6}
              fullWidth
              value={mutation.isPending ? 'Génération du message…' : message}
              onChange={(event) => setMessage(event.target.value)}
            />
            {phone && (
              <Typography variant="caption" color="text.secondary">
                Téléphone : {phone}
              </Typography>
            )}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5, flexWrap: 'wrap', gap: 1 }}>
          <Button onClick={onClose} color="inherit">
            Fermer
          </Button>
          <Button
            startIcon={<WhatsAppIcon />}
            onClick={openWhatsApp}
            disabled={!message || !phone}
            color="success"
          >
            Ouvrir WhatsApp
          </Button>
          <Button
            variant="contained"
            startIcon={<ContentCopyIcon />}
            onClick={copy}
            disabled={!message}
          >
            Copier le message
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={copied}
        autoHideDuration={2500}
        onClose={() => setCopied(false)}
        message="Message copié"
      />
    </>
  );
}
