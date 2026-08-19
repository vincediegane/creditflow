import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
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
  MenuItem,
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
import { productsApi } from '../api/endpoints';
import ConfirmDialog from '../components/ConfirmDialog';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import StatusChip from '../components/StatusChip';
import type { Product, ProductPayload } from '../types';
import { PRODUCT_STATUS_LABELS, formatMoney } from '../utils/format';

const EMPTY_FORM: ProductPayload = {
  name: '',
  category: '',
  cashPrice: 0,
  creditPrice: 0,
  stock: 0,
  description: '',
  status: 'ACTIVE',
};

export default function ProductsPage() {
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [category, setCategory] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Product | null>(null);
  const [toDelete, setToDelete] = useState<Product | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { register, control, handleSubmit, reset, formState } = useForm<ProductPayload>({
    defaultValues: EMPTY_FORM,
  });

  const query = useQuery({
    queryKey: ['products', search, category, page, size],
    queryFn: () =>
      productsApi.list({
        search: search || undefined,
        category: category || undefined,
        page,
        size,
        sort: 'name,asc',
      }),
  });

  const categoriesQuery = useQuery({ queryKey: ['product-categories'], queryFn: productsApi.categories });

  const saveMutation = useMutation({
    mutationFn: (payload: ProductPayload) =>
      editing ? productsApi.update(editing.id, payload) : productsApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['product-categories'] });
      setDialogOpen(false);
      setEditing(null);
    },
    onError: (err) => setError(errorMessage(err, "Le produit n'a pas pu être enregistré")),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => productsApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      setToDelete(null);
    },
    onError: (err) => {
      setError(errorMessage(err, 'Suppression impossible : ce produit est utilisé dans un contrat.'));
      setToDelete(null);
    },
  });

  const openCreate = () => {
    setEditing(null);
    setError(null);
    reset(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (product: Product) => {
    setEditing(product);
    setError(null);
    reset({
      name: product.name,
      category: product.category,
      cashPrice: product.cashPrice,
      creditPrice: product.creditPrice,
      stock: product.stock,
      description: product.description ?? '',
      status: product.status,
    });
    setDialogOpen(true);
  };

  const submit = handleSubmit((values) => {
    setError(null);
    saveMutation.mutate({
      ...values,
      cashPrice: Number(values.cashPrice),
      creditPrice: Number(values.creditPrice),
      stock: Number(values.stock),
      description: values.description || undefined,
    });
  });

  const rows = query.data?.content ?? [];

  return (
    <Box>
      <PageHeader
        title="Produits"
        subtitle="Catalogue des téléphones, ordinateurs et accessoires"
        action={
          <Button variant="contained" size="large" startIcon={<AddIcon />} onClick={openCreate}>
            Nouveau produit
          </Button>
        }
      />

      <Card variant="outlined">
        <Grid container spacing={2} sx={{ p: 2 }}>
          <Grid item xs={12} md={8}>
            <TextField
              fullWidth
              placeholder="Rechercher un produit…"
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
          </Grid>
          <Grid item xs={12} md={4}>
            <TextField
              select
              fullWidth
              label="Catégorie"
              value={category}
              onChange={(event) => {
                setCategory(event.target.value);
                setPage(0);
              }}
            >
              <MenuItem value="">Toutes</MenuItem>
              {(categoriesQuery.data ?? []).map((item) => (
                <MenuItem key={item} value={item}>
                  {item}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
        </Grid>

        {query.isFetching && <LinearProgress />}

        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Produit</TableCell>
                <TableCell>Catégorie</TableCell>
                <TableCell align="right">Prix comptant</TableCell>
                <TableCell align="right">Prix à crédit</TableCell>
                <TableCell align="right">Stock</TableCell>
                <TableCell>Statut</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((product) => (
                <TableRow key={product.id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{product.name}</TableCell>
                  <TableCell>{product.category}</TableCell>
                  <TableCell align="right">{formatMoney(product.cashPrice)}</TableCell>
                  <TableCell align="right">{formatMoney(product.creditPrice)}</TableCell>
                  <TableCell align="right">{product.stock}</TableCell>
                  <TableCell>
                    <StatusChip status={product.status} kind="product" />
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="Modifier">
                      <IconButton onClick={() => openEdit(product)}>
                        <EditIcon />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title="Supprimer">
                      <IconButton color="error" onClick={() => setToDelete(product)}>
                        <DeleteIcon />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={7} message="Aucun produit trouvé" />
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

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? 'Modifier le produit' : 'Nouveau produit'}</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ mt: 0 }}>
            {error && (
              <Grid item xs={12}>
                <Alert severity="error">{error}</Alert>
              </Grid>
            )}
            <Grid item xs={12} sm={7}>
              <TextField
                fullWidth
                label="Nom"
                {...register('name', { required: true })}
                error={Boolean(formState.errors.name)}
              />
            </Grid>
            <Grid item xs={12} sm={5}>
              <TextField
                fullWidth
                label="Catégorie"
                {...register('category', { required: true })}
                error={Boolean(formState.errors.category)}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                type="number"
                label="Prix comptant"
                inputProps={{ min: 0, step: 500 }}
                {...register('cashPrice', { required: true, min: 0 })}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                type="number"
                label="Prix à crédit"
                inputProps={{ min: 0, step: 500 }}
                {...register('creditPrice', { required: true, min: 0 })}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                type="number"
                label="Stock"
                inputProps={{ min: 0 }}
                {...register('stock', { required: true, min: 0 })}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <Controller
                name="status"
                control={control}
                render={({ field }) => (
                  <TextField {...field} select fullWidth label="Statut">
                    {Object.entries(PRODUCT_STATUS_LABELS).map(([value, label]) => (
                      <MenuItem key={value} value={value}>
                        {label}
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              />
            </Grid>
            <Grid item xs={12}>
              <TextField
                fullWidth
                multiline
                minRows={2}
                label="Description"
                {...register('description')}
              />
            </Grid>
          </Grid>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setDialogOpen(false)} color="inherit">
            Annuler
          </Button>
          <Button variant="contained" onClick={submit} disabled={saveMutation.isPending}>
            Enregistrer
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={Boolean(toDelete)}
        title="Supprimer le produit"
        message={`Voulez-vous vraiment supprimer ${toDelete?.name} ?`}
        confirmLabel="Supprimer"
        loading={deleteMutation.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete.id)}
        onClose={() => setToDelete(null)}
      />
    </Box>
  );
}
