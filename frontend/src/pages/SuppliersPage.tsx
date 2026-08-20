import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  IconButton,
  InputAdornment,
  LinearProgress,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Tooltip,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import EditIcon from '@mui/icons-material/EditOutlined';
import SearchIcon from '@mui/icons-material/Search';

import { errorMessage } from '../api/client';
import { suppliersApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import ConfirmDialog from '../components/ConfirmDialog';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import type { Supplier, SupplierPayload } from '../types';

const EMPTY_FORM: SupplierPayload = {
  name: '',
  contactName: '',
  phone: '',
  email: '',
  address: '',
  notes: '',
  active: true,
};

export default function SuppliersPage() {
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [editing, setEditing] = useState<Supplier | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [toDelete, setToDelete] = useState<Supplier | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { register, handleSubmit, reset, formState } = useForm<SupplierPayload>({
    defaultValues: EMPTY_FORM,
  });

  const query = useQuery({
    queryKey: ['suppliers', search, page, size],
    queryFn: () => suppliersApi.list({ search: search || undefined, page, size, sort: 'name,asc' }),
  });

  const saveMutation = useMutation({
    mutationFn: (payload: SupplierPayload) =>
      editing ? suppliersApi.update(editing.id, payload) : suppliersApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['suppliers'] });
      closeDialog();
    },
    onError: (err) => setError(errorMessage(err, "Le fournisseur n'a pas pu être enregistré")),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => suppliersApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['suppliers'] });
      setToDelete(null);
    },
    onError: (err) => {
      setError(errorMessage(err, 'Suppression impossible : ce fournisseur est utilisé dans des réceptions.'));
      setToDelete(null);
    },
  });

  const openCreate = () => {
    setEditing(null);
    setError(null);
    reset(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (supplier: Supplier) => {
    setEditing(supplier);
    setError(null);
    reset({
      name: supplier.name,
      contactName: supplier.contactName ?? '',
      phone: supplier.phone ?? '',
      email: supplier.email ?? '',
      address: supplier.address ?? '',
      notes: supplier.notes ?? '',
      active: supplier.active,
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
      contactName: values.contactName || undefined,
      phone: values.phone || undefined,
      email: values.email || undefined,
      address: values.address || undefined,
      notes: values.notes || undefined,
    });
  });

  const rows = query.data?.content ?? [];

  return (
    <Box>
      <PageHeader
        title="Fournisseurs"
        subtitle="Contacts fournisseurs pour les réceptions de stock"
        action={
          user?.role === 'ADMIN' && (
            <Button variant="contained" size="large" startIcon={<AddIcon />} onClick={openCreate}>
              Nouveau fournisseur
            </Button>
          )
        }
      />

      <Card variant="outlined">
        <Box sx={{ p: 2 }}>
          <TextField
            fullWidth
            placeholder="Rechercher par nom, contact, téléphone ou email…"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            }}
          />
        </Box>

        {query.isFetching && <LinearProgress />}

        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Fournisseur</TableCell>
                <TableCell>Contact</TableCell>
                <TableCell>Téléphone</TableCell>
                <TableCell>Email</TableCell>
                <TableCell>Adresse</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((supplier) => (
                <TableRow key={supplier.id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{supplier.name}</TableCell>
                  <TableCell>{supplier.contactName ?? '—'}</TableCell>
                  <TableCell>{supplier.phone ?? '—'}</TableCell>
                  <TableCell>{supplier.email ?? '—'}</TableCell>
                  <TableCell>{supplier.address ?? '—'}</TableCell>
                  <TableCell align="right">
                    {user?.role === 'ADMIN' && (
                      <>
                        <Tooltip title="Modifier">
                          <IconButton onClick={() => openEdit(supplier)}>
                            <EditIcon />
                          </IconButton>
                        </Tooltip>
                        <Tooltip title="Supprimer">
                          <IconButton color="error" onClick={() => setToDelete(supplier)}>
                            <DeleteIcon />
                          </IconButton>
                        </Tooltip>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={6} message="Aucun fournisseur trouvé" />
              )}
            </TableBody>
          </Table>
        </TableContainer>

        <TablePagination
          component="div"
          count={query.data?.totalElements ?? 0}
          page={page}
          onPageChange={(_, next) => setPage(next)}
          rowsPerPage={size}
          onRowsPerPageChange={(event) => {
            setSize(Number(event.target.value));
            setPage(0);
          }}
          rowsPerPageOptions={[10, 20, 50]}
          labelRowsPerPage="Lignes par page"
        />
      </Card>

      <Dialog open={dialogOpen} onClose={closeDialog} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? 'Modifier le fournisseur' : 'Nouveau fournisseur'}</DialogTitle>
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
              <TextField fullWidth label="Contact" {...register('contactName')} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth label="Téléphone" {...register('phone')} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth label="Email" {...register('email')} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth label="Adresse" {...register('address')} />
            </Grid>
            <Grid item xs={12}>
              <TextField fullWidth multiline minRows={2} label="Observations" {...register('notes')} />
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
        title="Supprimer le fournisseur"
        message={`Voulez-vous vraiment supprimer ${toDelete?.name} ?`}
        confirmLabel="Supprimer"
        loading={deleteMutation.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete.id)}
        onClose={() => setToDelete(null)}
      />
    </Box>
  );
}
