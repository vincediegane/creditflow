import { useState } from 'react';
import { useForm } from 'react-hook-form';
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
  IconButton,
  LinearProgress,
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
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import EditIcon from '@mui/icons-material/EditOutlined';

import { errorMessage } from '../api/client';
import { shopsApi } from '../api/endpoints';
import ConfirmDialog from '../components/ConfirmDialog';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import type { Shop, ShopPayload } from '../types';

const EMPTY_FORM: ShopPayload = {
  name: '',
  address: '',
  phone: '',
  active: true,
};

export default function ShopsPage() {
  const queryClient = useQueryClient();

  const [editing, setEditing] = useState<Shop | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [toDelete, setToDelete] = useState<Shop | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { register, handleSubmit, reset, formState } = useForm<ShopPayload>({
    defaultValues: EMPTY_FORM,
  });

  const query = useQuery({ queryKey: ['shops'], queryFn: shopsApi.list });

  const saveMutation = useMutation({
    mutationFn: (payload: ShopPayload) =>
      editing ? shopsApi.update(editing.id, payload) : shopsApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['shops'] });
      closeDialog();
    },
    onError: (err) => setError(errorMessage(err, "La boutique n'a pas pu être enregistrée")),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => shopsApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['shops'] });
      setToDelete(null);
    },
    onError: (err) => {
      setError(errorMessage(err, 'Suppression impossible : cette boutique est utilisée par des données existantes.'));
      setToDelete(null);
    },
  });

  const openCreate = () => {
    setEditing(null);
    setError(null);
    reset(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (shop: Shop) => {
    setEditing(shop);
    setError(null);
    reset({
      name: shop.name,
      address: shop.address ?? '',
      phone: shop.phone ?? '',
      active: shop.active,
    });
    setDialogOpen(true);
  };

  const closeDialog = () => {
    setDialogOpen(false);
    setEditing(null);
  };

  const submit = handleSubmit((values) => {
    setError(null);
    saveMutation.mutate({
      ...values,
      address: values.address || undefined,
      phone: values.phone || undefined,
    });
  });

  const rows = query.data ?? [];

  return (
    <Box>
      <PageHeader
        title="Boutiques"
        subtitle="Points de vente rattachés aux clients, produits et contrats"
        action={
          <Button variant="contained" size="large" startIcon={<AddIcon />} onClick={openCreate}>
            Nouvelle boutique
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
                <TableCell>Boutique</TableCell>
                <TableCell>Adresse</TableCell>
                <TableCell>Téléphone</TableCell>
                <TableCell>Statut</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((shop) => (
                <TableRow key={shop.id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{shop.name}</TableCell>
                  <TableCell>{shop.address ?? '—'}</TableCell>
                  <TableCell>{shop.phone ?? '—'}</TableCell>
                  <TableCell>
                    <Chip
                      label={shop.active ? 'Active' : 'Inactive'}
                      color={shop.active ? 'success' : 'default'}
                      size="small"
                      variant={shop.active ? 'filled' : 'outlined'}
                    />
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="Modifier">
                      <IconButton onClick={() => openEdit(shop)}>
                        <EditIcon />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title="Supprimer">
                      <IconButton color="error" onClick={() => setToDelete(shop)}>
                        <DeleteIcon />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && <EmptyRow colSpan={5} message="Aucune boutique" />}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      <Dialog open={dialogOpen} onClose={closeDialog} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? 'Modifier la boutique' : 'Nouvelle boutique'}</DialogTitle>
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
                label="Nom"
                {...register('name', { required: true })}
                error={Boolean(formState.errors.name)}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth label="Adresse" {...register('address')} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth label="Téléphone" {...register('phone')} />
            </Grid>
          </Grid>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={closeDialog} color="inherit">
            Annuler
          </Button>
          <Button variant="contained" onClick={submit} disabled={saveMutation.isPending}>
            Enregistrer
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={Boolean(toDelete)}
        title="Supprimer la boutique"
        message={`Voulez-vous vraiment supprimer ${toDelete?.name} ?`}
        confirmLabel="Supprimer"
        loading={deleteMutation.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete.id)}
        onClose={() => setToDelete(null)}
      />
    </Box>
  );
}
