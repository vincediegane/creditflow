import { useState } from 'react';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';

import { errorMessage } from '../api/client';
import { productsApi, stockReceptionsApi, suppliersApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import { formatDate, today } from '../utils/format';

interface LineFormValues {
  productId: number | '';
  quantity: number | '';
}

interface FormValues {
  supplierId: number | '';
  receivedAt: string;
  notes: string;
  lines: LineFormValues[];
}

const EMPTY_LINE: LineFormValues = { productId: '', quantity: 1 };

export default function StockReceptionsPage() {
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const canCreate = user?.role === 'ADMIN';

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [error, setError] = useState<string | null>(null);

  const suppliersQuery = useQuery({ queryKey: ['suppliers', 'select'], queryFn: suppliersApi.select });
  const productsQuery = useQuery({ queryKey: ['products', 'select'], queryFn: productsApi.select });

  const historyQuery = useQuery({
    queryKey: ['stock-receptions', page, size],
    queryFn: () => stockReceptionsApi.list({ page, size, sort: 'receivedAt,desc' }),
  });

  const { control, register, handleSubmit, reset, formState } = useForm<FormValues>({
    defaultValues: {
      supplierId: '',
      receivedAt: today(),
      notes: '',
      lines: [EMPTY_LINE],
    },
  });

  const { fields, append, remove } = useFieldArray({ control, name: 'lines' });

  const createMutation = useMutation({
    mutationFn: stockReceptionsApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['stock-receptions'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      reset({ supplierId: '', receivedAt: today(), notes: '', lines: [EMPTY_LINE] });
    },
    onError: (err) => setError(errorMessage(err, "La réception n'a pas pu être enregistrée")),
  });

  const submit = handleSubmit((values) => {
    setError(null);
    if (!values.supplierId) {
      setError('Sélectionnez un fournisseur');
      return;
    }
    const lines = values.lines
      .filter((line) => line.productId && Number(line.quantity) > 0)
      .map((line) => ({ productId: Number(line.productId), quantity: Number(line.quantity) }));
    if (!lines.length) {
      setError('Ajoutez au moins une ligne avec un produit et une quantité');
      return;
    }
    createMutation.mutate({
      supplierId: Number(values.supplierId),
      receivedAt: values.receivedAt,
      notes: values.notes || undefined,
      lines,
    });
  });

  const rows = historyQuery.data?.content ?? [];

  return (
    <Box>
      <PageHeader
        title="Achats fournisseurs"
        subtitle="Réceptionner du stock et consulter l'historique des achats"
      />

      <Grid container spacing={2.5}>
        {canCreate && (
        <Grid item xs={12} md={5}>
          <Card variant="outlined">
            <CardContent>
              <Stack spacing={2.5}>
                <Typography variant="h6">Nouvelle réception</Typography>
                {error && <Alert severity="error">{error}</Alert>}

                <Controller
                  name="supplierId"
                  control={control}
                  render={({ field }) => (
                    <Autocomplete
                      options={suppliersQuery.data ?? []}
                      getOptionLabel={(option) => option.name}
                      onChange={(_, value) => field.onChange(value?.id ?? '')}
                      renderInput={(params) => (
                        <TextField {...params} label="Fournisseur" placeholder="Nom du fournisseur" />
                      )}
                    />
                  )}
                />

                <TextField
                  fullWidth
                  type="date"
                  label="Date de réception"
                  InputLabelProps={{ shrink: true }}
                  {...register('receivedAt', { required: true })}
                  error={Boolean(formState.errors.receivedAt)}
                />

                <Divider />

                <Typography variant="subtitle2">Lignes reçues</Typography>

                <Stack spacing={2}>
                  {fields.map((field, index) => (
                    <Stack key={field.id} direction="row" spacing={1.5} alignItems="center">
                      <Controller
                        name={`lines.${index}.productId`}
                        control={control}
                        render={({ field: productField }) => (
                          <Autocomplete
                            sx={{ flexGrow: 1 }}
                            options={productsQuery.data ?? []}
                            getOptionLabel={(option) => `${option.name} (stock ${option.stock})`}
                            onChange={(_, value) => productField.onChange(value?.id ?? '')}
                            renderInput={(params) => (
                              <TextField {...params} label="Produit" placeholder="Nom du produit" />
                            )}
                          />
                        )}
                      />
                      <TextField
                        type="number"
                        label="Quantité"
                        sx={{ width: 120 }}
                        inputProps={{ min: 1 }}
                        {...register(`lines.${index}.quantity` as const, { min: 1 })}
                      />
                      <IconButton
                        color="error"
                        disabled={fields.length === 1}
                        onClick={() => remove(index)}
                      >
                        <DeleteIcon />
                      </IconButton>
                    </Stack>
                  ))}
                </Stack>

                <Button
                  startIcon={<AddIcon />}
                  onClick={() => append(EMPTY_LINE)}
                  sx={{ alignSelf: 'flex-start' }}
                >
                  Ajouter une ligne
                </Button>

                <TextField fullWidth multiline minRows={2} label="Observations" {...register('notes')} />

                <Button
                  variant="contained"
                  size="large"
                  onClick={submit}
                  disabled={createMutation.isPending}
                >
                  Enregistrer la réception
                </Button>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
        )}

        <Grid item xs={12} md={canCreate ? 7 : 12}>
          <Card variant="outlined">
            <Box sx={{ p: 2, pb: 0 }}>
              <Typography variant="h6">Historique des réceptions</Typography>
            </Box>

            <TableContainer>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Date</TableCell>
                    <TableCell>Fournisseur</TableCell>
                    <TableCell>Produits reçus</TableCell>
                    <TableCell align="right">Quantité totale</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((reception) => (
                    <TableRow key={reception.id} hover>
                      <TableCell>{formatDate(reception.receivedAt)}</TableCell>
                      <TableCell>{reception.supplierName}</TableCell>
                      <TableCell>
                        {reception.lines.map((line) => line.productName).join(', ')}
                      </TableCell>
                      <TableCell align="right">
                        {reception.lines.reduce((total, line) => total + line.quantity, 0)}
                      </TableCell>
                    </TableRow>
                  ))}
                  {!rows.length && !historyQuery.isLoading && (
                    <EmptyRow colSpan={4} message="Aucune réception enregistrée" />
                  )}
                </TableBody>
              </Table>
            </TableContainer>

            <TablePagination
              component="div"
              count={historyQuery.data?.totalElements ?? 0}
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
        </Grid>
      </Grid>
    </Box>
  );
}
