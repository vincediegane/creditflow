import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  LinearProgress,
  ListItemText,
  MenuItem,
  Select,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import StoreIcon from '@mui/icons-material/Store';

import { errorMessage } from '../api/client';
import { shopsApi, usersApi } from '../api/endpoints';
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
  shopIds: [],
};

export default function UsersPage() {
  const queryClient = useQueryClient();
  const { user: currentUser } = useAuth();

  const [dialogOpen, setDialogOpen] = useState(false);
  const [toDisable, setToDisable] = useState<UserAccount | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editingShopsFor, setEditingShopsFor] = useState<UserAccount | null>(null);
  const [shopsError, setShopsError] = useState<string | null>(null);
  const [selectedShopIds, setSelectedShopIds] = useState<number[]>([]);

  const { register, control, handleSubmit, reset, watch, formState } = useForm<CreateUserPayload>({
    defaultValues: EMPTY_FORM,
  });

  const role = watch('role');

  const query = useQuery({ queryKey: ['users'], queryFn: usersApi.list });
  const shopsQuery = useQuery({ queryKey: ['shops'], queryFn: shopsApi.list });
  const shops = shopsQuery.data ?? [];

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

  const shopsMutation = useMutation({
    mutationFn: ({ id, shopIds }: { id: number; shopIds: number[] }) => usersApi.updateShops(id, shopIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      setEditingShopsFor(null);
    },
    onError: (err) => setShopsError(errorMessage(err, "L'assignation des boutiques n'a pas pu être enregistrée")),
  });

  const openCreate = () => {
    setError(null);
    reset(EMPTY_FORM);
    setDialogOpen(true);
  };

  const closeDialog = () => {
    setDialogOpen(false);
  };

  const openShopsEdit = (account: UserAccount) => {
    setShopsError(null);
    setSelectedShopIds(account.shops.map((s) => s.id));
    setEditingShopsFor(account);
  };

  const submit = handleSubmit((values) => {
    setError(null);
    if (values.role === 'SELLER' && !(values.shopIds && values.shopIds.length > 0)) {
      setError('Un vendeur doit être rattaché à au moins une boutique');
      return;
    }
    createMutation.mutate(values);
  });

  const submitShops = () => {
    if (!editingShopsFor) {
      return;
    }
    if (editingShopsFor.role === 'SELLER' && selectedShopIds.length === 0) {
      setShopsError('Un vendeur doit être rattaché à au moins une boutique');
      return;
    }
    setShopsError(null);
    shopsMutation.mutate({ id: editingShopsFor.id, shopIds: selectedShopIds });
  };

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
                <TableCell>Boutiques</TableCell>
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
                      {account.shops.length ? (
                        account.shops.map((shop) => (
                          <Chip key={shop.id} label={shop.name} size="small" sx={{ mr: 0.5, mb: 0.5 }} />
                        ))
                      ) : (
                        <Typography variant="body2" color="text.secondary">
                          {account.role === 'ADMIN' ? 'Toutes (super-admin)' : '—'}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <Chip
                        label={account.enabled ? 'Actif' : 'Désactivé'}
                        color={account.enabled ? 'success' : 'default'}
                        size="small"
                        variant={account.enabled ? 'filled' : 'outlined'}
                      />
                    </TableCell>
                    <TableCell align="right">
                      <Tooltip title="Modifier les boutiques">
                        <Button size="small" startIcon={<StoreIcon />} onClick={() => openShopsEdit(account)}>
                          Boutiques
                        </Button>
                      </Tooltip>
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
                <EmptyRow colSpan={6} message="Aucun compte utilisateur" />
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
            <Grid item xs={12}>
              <Controller
                name="shopIds"
                control={control}
                render={({ field }) => (
                  <Select
                    {...field}
                    multiple
                    fullWidth
                    displayEmpty
                    value={field.value ?? []}
                    onChange={(event) => {
                      const value = event.target.value;
                      field.onChange(typeof value === 'string' ? value.split(',').map(Number) : value);
                    }}
                    renderValue={(selected) =>
                      (selected as number[]).length
                        ? shops
                            .filter((shop) => (selected as number[]).includes(shop.id))
                            .map((shop) => shop.name)
                            .join(', ')
                        : role === 'SELLER'
                          ? 'Boutique(s) — obligatoire pour un vendeur'
                          : 'Boutique(s) — laisser vide pour super-admin'
                    }
                  >
                    {shops.map((shop) => (
                      <MenuItem key={shop.id} value={shop.id}>
                        <Checkbox checked={(field.value ?? []).includes(shop.id)} />
                        <ListItemText primary={shop.name} />
                      </MenuItem>
                    ))}
                  </Select>
                )}
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

      <Dialog open={Boolean(editingShopsFor)} onClose={() => setEditingShopsFor(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Boutiques de {editingShopsFor?.fullName}</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ mt: 0 }}>
            {shopsError && (
              <Grid item xs={12}>
                <Alert severity="error">{shopsError}</Alert>
              </Grid>
            )}
            <Grid item xs={12}>
              <Select
                multiple
                fullWidth
                value={selectedShopIds}
                onChange={(event) => {
                  const value = event.target.value;
                  setSelectedShopIds(typeof value === 'string' ? value.split(',').map(Number) : value);
                }}
                renderValue={(selected) =>
                  shops
                    .filter((shop) => (selected as number[]).includes(shop.id))
                    .map((shop) => shop.name)
                    .join(', ')
                }
              >
                {shops.map((shop) => (
                  <MenuItem key={shop.id} value={shop.id}>
                    <Checkbox checked={selectedShopIds.includes(shop.id)} />
                    <ListItemText primary={shop.name} />
                  </MenuItem>
                ))}
              </Select>
            </Grid>
          </Grid>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setEditingShopsFor(null)} color="inherit">
            Annuler
          </Button>
          <Button variant="contained" onClick={submitShops} disabled={shopsMutation.isPending}>
            Enregistrer
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
