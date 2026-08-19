import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Avatar,
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
  Stack,
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
import VisibilityIcon from '@mui/icons-material/VisibilityOutlined';

import { errorMessage } from '../api/client';
import { customersApi } from '../api/endpoints';
import ConfirmDialog from '../components/ConfirmDialog';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import type { Customer, CustomerPayload } from '../types';
import { formatDate, initials } from '../utils/format';

const EMPTY_FORM: CustomerPayload = {
  firstName: '',
  lastName: '',
  phone: '',
  address: '',
  cniNumber: '',
  profession: '',
  notes: '',
  active: true,
};

export default function CustomersPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [editing, setEditing] = useState<Customer | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [toDelete, setToDelete] = useState<Customer | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { register, handleSubmit, reset, formState } = useForm<CustomerPayload>({
    defaultValues: EMPTY_FORM,
  });

  const query = useQuery({
    queryKey: ['customers', search, page, size],
    queryFn: () => customersApi.list({ search: search || undefined, page, size, sort: 'lastName,asc' }),
  });

  const saveMutation = useMutation({
    mutationFn: (payload: CustomerPayload) =>
      editing ? customersApi.update(editing.id, payload) : customersApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      closeDialog();
    },
    onError: (err) => setError(errorMessage(err, "Le client n'a pas pu être enregistré")),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => customersApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      setToDelete(null);
    },
    onError: (err) => {
      setError(errorMessage(err, 'Suppression impossible : ce client a des contrats.'));
      setToDelete(null);
    },
  });

  const openCreate = () => {
    setEditing(null);
    setError(null);
    reset(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (customer: Customer) => {
    setEditing(customer);
    setError(null);
    reset({
      firstName: customer.firstName,
      lastName: customer.lastName,
      phone: customer.phone,
      address: customer.address ?? '',
      cniNumber: customer.cniNumber ?? '',
      profession: customer.profession ?? '',
      notes: customer.notes ?? '',
      active: customer.active,
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
      cniNumber: values.cniNumber || undefined,
      profession: values.profession || undefined,
      notes: values.notes || undefined,
    });
  });

  const rows = query.data?.content ?? [];

  return (
    <Box>
      <PageHeader
        title="Clients"
        subtitle="Fiches clients, historiques d'achats et de paiements"
        action={
          <Button variant="contained" size="large" startIcon={<AddIcon />} onClick={openCreate}>
            Nouveau client
          </Button>
        }
      />

      <Card variant="outlined">
        <Box sx={{ p: 2 }}>
          <TextField
            fullWidth
            placeholder="Rechercher par nom, téléphone, CNI ou profession…"
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
                <TableCell>Client</TableCell>
                <TableCell>Téléphone</TableCell>
                <TableCell>Profession</TableCell>
                <TableCell>Adresse</TableCell>
                <TableCell>CNI</TableCell>
                <TableCell>Créé le</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((customer) => (
                <TableRow key={customer.id} hover>
                  <TableCell>
                    <Stack direction="row" spacing={1.5} alignItems="center">
                      <Avatar
                        src={customer.photoUrl}
                        sx={{ width: 36, height: 36, bgcolor: 'primary.light', fontSize: 14 }}
                      >
                        {initials(customer.fullName)}
                      </Avatar>
                      <Box sx={{ fontWeight: 600 }}>{customer.fullName}</Box>
                    </Stack>
                  </TableCell>
                  <TableCell>{customer.phone}</TableCell>
                  <TableCell>{customer.profession ?? '—'}</TableCell>
                  <TableCell>{customer.address ?? '—'}</TableCell>
                  <TableCell>{customer.cniNumber ?? '—'}</TableCell>
                  <TableCell>{formatDate(customer.createdAt)}</TableCell>
                  <TableCell align="right">
                    <Tooltip title="Fiche complète">
                      <IconButton onClick={() => navigate(`/clients/${customer.id}`)}>
                        <VisibilityIcon />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title="Modifier">
                      <IconButton onClick={() => openEdit(customer)}>
                        <EditIcon />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title="Supprimer">
                      <IconButton color="error" onClick={() => setToDelete(customer)}>
                        <DeleteIcon />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={7} message="Aucun client trouvé" />
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
        <DialogTitle>{editing ? 'Modifier le client' : 'Nouveau client'}</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ mt: 0 }}>
            {error && (
              <Grid item xs={12}>
                <Alert severity="error">{error}</Alert>
              </Grid>
            )}
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label="Prénom"
                {...register('firstName', { required: true })}
                error={Boolean(formState.errors.firstName)}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label="Nom"
                {...register('lastName', { required: true })}
                error={Boolean(formState.errors.lastName)}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label="Téléphone"
                {...register('phone', { required: true })}
                error={Boolean(formState.errors.phone)}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth label="Numéro CNI" {...register('cniNumber')} />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth label="Profession" {...register('profession')} />
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
        title="Supprimer le client"
        message={`Voulez-vous vraiment supprimer ${toDelete?.fullName} ? Cette action est irréversible.`}
        confirmLabel="Supprimer"
        loading={deleteMutation.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete.id)}
        onClose={() => setToDelete(null)}
      />
    </Box>
  );
}
