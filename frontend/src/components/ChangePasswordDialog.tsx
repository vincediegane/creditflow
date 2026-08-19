import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';

import { errorMessage } from '../api/client';
import { authApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';

interface FormValues {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

interface Props {
  open: boolean;
  /** Changement imposé : la fenêtre ne peut pas être fermée. */
  forced?: boolean;
  onClose: () => void;
}

export default function ChangePasswordDialog({ open, forced = false, onClose }: Props) {
  const { refreshUser } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const { register, handleSubmit, reset, watch, formState } = useForm<FormValues>({
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });

  useEffect(() => {
    if (open) {
      setError(null);
      setDone(false);
      reset({ currentPassword: '', newPassword: '', confirmPassword: '' });
    }
  }, [open, reset]);

  const mutation = useMutation({
    mutationFn: authApi.changePassword,
    onSuccess: (user) => {
      refreshUser(user);
      setDone(true);
      setTimeout(onClose, 900);
    },
    onError: (err) => setError(errorMessage(err, 'Le mot de passe n’a pas pu être modifié')),
  });

  const submit = handleSubmit((values) => {
    setError(null);
    if (values.newPassword !== values.confirmPassword) {
      setError('Les deux nouveaux mots de passe ne sont pas identiques');
      return;
    }
    mutation.mutate({
      currentPassword: values.currentPassword,
      newPassword: values.newPassword,
    });
  });

  const newPassword = watch('newPassword');

  return (
    <Dialog
      open={open}
      onClose={forced ? undefined : onClose}
      maxWidth="xs"
      fullWidth
      disableEscapeKeyDown={forced}
    >
      <DialogTitle>
        {forced ? 'Choisissez votre mot de passe' : 'Changer le mot de passe'}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {forced && (
            <Alert severity="warning">
              Ce compte utilise encore le mot de passe fourni à l&apos;installation. Définissez le
              vôtre pour que personne d&apos;autre n&apos;ait accès à vos données.
            </Alert>
          )}
          {error && <Alert severity="error">{error}</Alert>}
          {done && <Alert severity="success">Mot de passe modifié.</Alert>}

          <TextField
            label="Mot de passe actuel"
            type="password"
            fullWidth
            autoFocus
            {...register('currentPassword', { required: true })}
            error={Boolean(formState.errors.currentPassword)}
          />
          <TextField
            label="Nouveau mot de passe"
            type="password"
            fullWidth
            {...register('newPassword', { required: true, minLength: 8 })}
            error={Boolean(formState.errors.newPassword)}
            helperText="8 caractères minimum"
          />
          <TextField
            label="Confirmer le nouveau mot de passe"
            type="password"
            fullWidth
            {...register('confirmPassword', { required: true })}
            error={Boolean(formState.errors.confirmPassword)}
          />

          {newPassword && newPassword.length < 8 && (
            <Typography variant="caption" color="warning.main">
              Encore {8 - newPassword.length} caractère(s).
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        {!forced && (
          <Button onClick={onClose} color="inherit">
            Annuler
          </Button>
        )}
        <Button variant="contained" onClick={submit} disabled={mutation.isPending || done}>
          Enregistrer
        </Button>
      </DialogActions>
    </Dialog>
  );
}
