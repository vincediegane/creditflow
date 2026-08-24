import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Controller, useForm, useWatch } from 'react-hook-form';
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
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';

import { errorMessage } from '../api/client';
import { customersApi, productsApi, salesApi } from '../api/endpoints';
import PageHeader from '../components/PageHeader';
import type { Customer, SalePreview } from '../types';
import { formatDate, formatMoney, today } from '../utils/format';

interface FormValues {
  customerId: number | '';
  productId: number | '';
  totalPrice: number | '';
  downPayment: number | '';
  interestRate: number | '';
  interestFee: number | '';
  installmentCount: number | '';
  startDate: string;
  notes: string;
  guarantorFullName: string;
  guarantorPhone: string;
  guarantorAddress: string;
  guarantorCniNumber: string;
}

export default function NewSalePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const customerIdParam = searchParams.get('customerId');
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<SalePreview | null>(null);
  const [selectedCustomer, setSelectedCustomer] = useState<Customer | null>(null);
  const preselectionResolved = useRef(false);

  const customersQuery = useQuery({ queryKey: ['customers', 'select'], queryFn: customersApi.select });
  const productsQuery = useQuery({ queryKey: ['products', 'select'], queryFn: productsApi.select });

  const { control, register, handleSubmit, setValue, formState } = useForm<FormValues>({
    defaultValues: {
      customerId: '',
      productId: '',
      totalPrice: '',
      downPayment: 0,
      interestRate: '',
      interestFee: '',
      installmentCount: 6,
      startDate: today(),
      notes: '',
      guarantorFullName: '',
      guarantorPhone: '',
      guarantorAddress: '',
      guarantorCniNumber: '',
    },
  });

  const values = useWatch({ control });

  useEffect(() => {
    if (customerIdParam == null || customersQuery.data === undefined || preselectionResolved.current) {
      return;
    }
    preselectionResolved.current = true;
    const parsedId = Number(customerIdParam);
    if (Number.isNaN(parsedId)) {
      return;
    }
    const match = customersQuery.data.find((customer) => customer.id === parsedId);
    if (match) {
      setValue('customerId', match.id);
      setSelectedCustomer(match);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customersQuery.data]);

  const previewMutation = useMutation({
    mutationFn: salesApi.preview,
    onSuccess: setPreview,
    onError: () => setPreview(null),
  });

  const createMutation = useMutation({
    mutationFn: salesApi.create,
    onSuccess: (sale) => {
      queryClient.invalidateQueries({ queryKey: ['customer-profile', sale.customerId] });
      if (customerIdParam != null) {
        navigate(`/clients/${customerIdParam}`);
      } else {
        navigate(`/ventes/${sale.id}`);
      }
    },
    onError: (err) => setError(errorMessage(err, "La vente n'a pas pu être enregistrée")),
  });

  const canPreview = useMemo(
    () =>
      Number(values.totalPrice) > 0 &&
      Number(values.installmentCount) > 0 &&
      Number(values.downPayment ?? 0) < Number(values.totalPrice) &&
      Boolean(values.startDate),
    [values.totalPrice, values.installmentCount, values.downPayment, values.startDate],
  );

  // Simulation automatique de l'echeancier des que les montants sont coherents.
  useEffect(() => {
    if (!canPreview) {
      setPreview(null);
      return;
    }
    const timer = setTimeout(() => {
      previewMutation.mutate({
        totalPrice: Number(values.totalPrice),
        downPayment: Number(values.downPayment ?? 0),
        interestRate: values.interestRate === '' || values.interestRate == null ? undefined : Number(values.interestRate),
        interestFee: values.interestFee === '' || values.interestFee == null ? undefined : Number(values.interestFee),
        installmentCount: Number(values.installmentCount),
        startDate: String(values.startDate),
      });
    }, 350);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    canPreview,
    values.totalPrice,
    values.downPayment,
    values.interestRate,
    values.interestFee,
    values.installmentCount,
    values.startDate,
  ]);

  const submit = handleSubmit((form) => {
    setError(null);
    if (!form.customerId || !form.productId) {
      setError('Sélectionnez un client et un produit');
      return;
    }

    const guarantorName = form.guarantorFullName.trim();
    const guarantorPhone = form.guarantorPhone.trim();
    if (Boolean(guarantorName) !== Boolean(guarantorPhone)) {
      setError('Le nom et le téléphone du garant doivent être renseignés ensemble');
      return;
    }

    createMutation.mutate({
      customerId: Number(form.customerId),
      productId: Number(form.productId),
      totalPrice: Number(form.totalPrice),
      downPayment: Number(form.downPayment || 0),
      interestRate: form.interestRate === '' || form.interestRate == null ? undefined : Number(form.interestRate),
      interestFee: form.interestFee === '' || form.interestFee == null ? undefined : Number(form.interestFee),
      installmentCount: Number(form.installmentCount),
      startDate: form.startDate,
      notes: form.notes || undefined,
      guarantorFullName: guarantorName || undefined,
      guarantorPhone: guarantorPhone || undefined,
      guarantorAddress: form.guarantorAddress.trim() || undefined,
      guarantorCniNumber: form.guarantorCniNumber.trim() || undefined,
    });
  });

  return (
    <Box>
      <PageHeader
        title="Nouvelle vente à crédit"
        subtitle="Le système calcule automatiquement les échéances, la mensualité et la date de fin"
        action={
          <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/ventes')}>
            Retour
          </Button>
        }
      />

      <Grid container spacing={2.5}>
        <Grid item xs={12} md={6}>
          <Card variant="outlined">
            <CardContent>
              <Stack spacing={2.5}>
                {error && <Alert severity="error">{error}</Alert>}

                {customerIdParam != null && !customersQuery.isLoading && selectedCustomer === null && (
                  <Alert severity="warning">
                    Client demandé introuvable — sélectionnez-le manuellement ci-dessous.
                  </Alert>
                )}

                <Controller
                  name="customerId"
                  control={control}
                  render={() => (
                    <Autocomplete
                      options={customersQuery.data ?? []}
                      getOptionLabel={(option) => `${option.fullName} — ${option.phone}`}
                      value={selectedCustomer}
                      isOptionEqualToValue={(option, value) => option.id === value.id}
                      onChange={(_, value) => {
                        setValue('customerId', value?.id ?? '');
                        setSelectedCustomer(value ?? null);
                      }}
                      renderInput={(params) => (
                        <TextField {...params} label="Client" placeholder="Nom ou téléphone" />
                      )}
                    />
                  )}
                />

                <Controller
                  name="productId"
                  control={control}
                  render={() => (
                    <Autocomplete
                      options={productsQuery.data ?? []}
                      getOptionLabel={(option) =>
                        `${option.name} — ${formatMoney(option.creditPrice)} (stock ${option.stock})`
                      }
                      onChange={(_, value) => {
                        setValue('productId', value?.id ?? '');
                        if (value) {
                          setValue('totalPrice', value.creditPrice);
                        }
                      }}
                      renderInput={(params) => (
                        <TextField {...params} label="Produit" placeholder="Nom ou catégorie" />
                      )}
                    />
                  )}
                />

                <Divider />

                <Grid container spacing={2}>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      type="number"
                      label="Prix de vente"
                      inputProps={{ min: 1, step: 500 }}
                      {...register('totalPrice', { required: true, min: 1 })}
                      error={Boolean(formState.errors.totalPrice)}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      type="number"
                      label="Acompte"
                      inputProps={{ min: 0, step: 500 }}
                      {...register('downPayment', { min: 0 })}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      type="number"
                      label="Taux d'intérêt %"
                      inputProps={{ min: 0, max: 100, step: 0.5 }}
                      {...register('interestRate', { min: 0, max: 100 })}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      type="number"
                      label="Frais de dossier (FCFA)"
                      inputProps={{ min: 0, step: 500 }}
                      {...register('interestFee', { min: 0 })}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      type="number"
                      label="Nombre de mensualités"
                      inputProps={{ min: 1, max: 60 }}
                      {...register('installmentCount', { required: true, min: 1, max: 60 })}
                      error={Boolean(formState.errors.installmentCount)}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      type="date"
                      label="Date de la première échéance"
                      InputLabelProps={{ shrink: true }}
                      {...register('startDate', { required: true })}
                    />
                  </Grid>
                  <Grid item xs={12}>
                    <TextField
                      fullWidth
                      multiline
                      minRows={2}
                      label="Observations"
                      {...register('notes')}
                    />
                  </Grid>
                </Grid>

                <Divider />

                <Typography variant="subtitle2">Garant (optionnel)</Typography>

                <Autocomplete
                  options={customersQuery.data ?? []}
                  getOptionLabel={(option) => `${option.fullName} — ${option.phone}`}
                  onChange={(_, value) => {
                    if (value) {
                      setValue('guarantorFullName', value.fullName);
                      setValue('guarantorPhone', value.phone);
                      setValue('guarantorAddress', value.address ?? '');
                    }
                  }}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Choisir un client comme garant (optionnel)"
                      placeholder="Nom ou téléphone"
                    />
                  )}
                />

                <Grid container spacing={2}>
                  <Grid item xs={12} sm={6}>
                    <TextField fullWidth label="Nom du garant" {...register('guarantorFullName')} />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField fullWidth label="Téléphone du garant" {...register('guarantorPhone')} />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField fullWidth label="Adresse du garant" {...register('guarantorAddress')} />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField fullWidth label="N° CNI du garant" {...register('guarantorCniNumber')} />
                  </Grid>
                </Grid>

                <Button
                  variant="contained"
                  size="large"
                  onClick={submit}
                  disabled={createMutation.isPending || !canPreview}
                >
                  Enregistrer la vente
                </Button>
              </Stack>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={6}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Échéancier calculé
              </Typography>

              {!preview && (
                <Alert severity="info">
                  Renseignez le prix, l'acompte et le nombre de mensualités pour voir l'échéancier.
                </Alert>
              )}

              {preview && (
                <>
                  <Grid container spacing={2} sx={{ mb: 2 }}>
                    <Summary label="Prix comptant" value={formatMoney(preview.totalPrice)} />
                    <Summary label="Intérêt / frais" value={formatMoney(preview.interestAmount)} />
                    <Summary label="Montant à financer" value={formatMoney(preview.financedAmount)} />
                    <Summary label="Mensualité" value={formatMoney(preview.monthlyAmount)} />
                    <Summary label="Date de fin" value={formatDate(preview.endDate)} />
                    <Summary label="Nombre d'échéances" value={String(preview.lines.length)} />
                  </Grid>

                  <Box sx={{ maxHeight: 380, overflow: 'auto' }}>
                    <Table size="small" stickyHeader>
                      <TableHead>
                        <TableRow>
                          <TableCell>N°</TableCell>
                          <TableCell>Date prévue</TableCell>
                          <TableCell align="right">Montant</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {preview.lines.map((line) => (
                          <TableRow key={line.number}>
                            <TableCell>{line.number}</TableCell>
                            <TableCell>{formatDate(line.dueDate)}</TableCell>
                            <TableCell align="right">{formatMoney(line.amount)}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </Box>
                </>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <Grid item xs={6}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
        {value}
      </Typography>
    </Grid>
  );
}
