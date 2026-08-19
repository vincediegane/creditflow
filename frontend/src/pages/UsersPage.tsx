import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  LinearProgress,
  MenuItem,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';

import { errorMessage } from '../api/client';
import { usersApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import ConfirmDialog from '../components/ConfirmDialog';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import type { CreateUserPayload, UserAccount } from '../types';

const EMPTY_FORM: CreateUserPayload = {
  username: '',
  password: '',
  fullName: '',
  role: 'SELLER',
};

export default function UsersPage() {
  const queryClient = useQueryClient();
  const { user: currentUser } = useAuth();

  const [dialogOpen, setDialogOpen] = useState(false);
  const [toDisable, setToDisable] = useState<UserAccount | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { register, control, handleSubmit, reset, formState } = useForm<CreateUserPayload>({
    defaultValues: EMPTY_FORM,
  });

  const query = useQuery({ queryKey: ['users'], queryFn: usersApi.list });

  const createMutation = useMutation({
    mutationFn: (payload: CreateUserPayload) => usersApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      closeDialog();
    },
    onError: (err) => setError(errorMessage(err, "Le compte n'a pas pu être créé")),
  });

  const statusMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) => usersApi.setEnabled(id, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      setToDisable(null);
    },
    onError: (err) => {
      setError(errorMessage(err, "Le statut du compte n'a pas pu être modifié"));
      setToDisable(null);
    },
  });

  const openCreate = () => {
    setError(null);
    reset(EMPTY_FORM);
    setDialogOpen(true);
  };

  const closeDialog = () => {
    setDialogOpen(false);
  };

  const submit = handleSubmit((values) => {
    setError(null);
    createMutation.mutate(values);
  });

  const rows = query.data ?? [];

  return (
    <Box>
      <PageHeader
        title="Utilisateurs"
        subtitle="Comptes vendeur et caissier de la boutique"
        action={
          <Button variant="contained" size="large" startIcon={<AddIcon />} onClick={openCreate}>
            Nouveau compte
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Card variant="outlined">
        {query.isFetching && <LinearProgress />}

        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Nom complet</TableCell>
                <TableCell>Identifiant</TableCell>
                <TableCell>Rôle</TableCell>
                <TableCell>Statut</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((account) => {
                const isSelf = account.id === currentUser?.id;
                return (
                  <TableRow key={account.id} hover>
                    <TableCell sx={{ fontWeight: 600 }}>{account.fullName}</TableCell>
                    <TableCell>{account.username}</TableCell>
                    <TableCell>{account.role === 'ADMIN' ? 'Administrateur' : 'Vendeur'}</TableCell>
                    <TableCell>
                      <Chip
                        label={account.enabled ? 'Actif' : 'Désactivé'}
                        color={account.enabled ? 'success' : 'default'}
                        size="small"
                        variant={account.enabled ? 'filled' : 'outlined'}
                      />
                    </TableCell>
                    <TableCell align="right">
                      {account.enabled ? (
                        <Tooltip
                          title={isSelf ? 'Vous ne pouvez pas désactiver votre propre compte' : ''}
                        >
                          <span>
                            <Button
                              size="small"
                              color="error"
                              disabled={isSelf}
                              onClick={() => setToDisable(account)}
                            >
                              Désactiver
                            </Button>
                          </span>
                        </Tooltip>
                      ) : (
                        <Button
                          size="small"
                          onClick={() => statusMutation.mutate({ id: account.id, enabled: true })}
                        >
                          Activer
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={5} message="Aucun compte utilisateur" />
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      <Dialog open={dialogOpen} onClose={closeDialog} maxWidth="sm" fullWidth>
        <DialogTitle>Nouveau compte</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ mt: 0 }}>
            {error && (
              <Grid item xs={12}>
                <Alert severity="error">{error}</Alert>
              </Grid>
            )}
            <Grid item xs={12}>
              <TextField
                fullWidth
                label="Nom complet"
                {...register('fullName', { required: true })}
                error={Boolean(formState.errors.fullName)}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label="Identifiant"
                {...register('username', { required: true })}
                error={Boolean(formState.errors.username)}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <Controller
                name="role"
                control={control}
                render={({ field }) => (
                  <TextField {...field} select fullWidth label="Rôle">
                    <MenuItem value="SELLER">Vendeur</MenuItem>
                    <MenuItem value="ADMIN">Administrateur</MenuItem>
                  </TextField>
                )}
              />
            </Grid>
            <Grid item xs={12}>
              <TextField
                fullWidth
                type="password"
                label="Mot de passe temporaire"
                helperText="min. 8 caractères"
                {...register('password', { required: true, minLength: 8 })}
                error={Boolean(formState.errors.password)}
              />
            </Grid>
          </Grid>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={closeDialog} color="inherit">
            Annuler
          </Button>
          <Button variant="contained" onClick={submit} disabled={createMutation.isPending}>
            Créer le compte
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={Boolean(toDisable)}
        title="Désactiver le compte"
        message={`Voulez-vous vraiment désactiver le compte de ${toDisable?.fullName} ? Son historique de ventes et paiements est conservé.`}
        confirmLabel="Désactiver"
        loading={statusMutation.isPending}
        onConfirm={() => toDisable && statusMutation.mutate({ id: toDisable.id, enabled: false })}
        onClose={() => setToDisable(null)}
      />
    </Box>
  );
}
