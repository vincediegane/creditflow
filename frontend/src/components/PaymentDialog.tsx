import { useEffect, useMemo, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Grid,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';

import { errorMessage } from '../api/client';
import { paymentsApi, salesApi } from '../api/endpoints';
import type { PaymentMethod, Sale } from '../types';
import { PAYMENT_METHOD_LABELS, formatMoney, today } from '../utils/format';

interface FormValues {
  saleId: number | '';
  amount: number | '';
  paymentDate: string;
  method: PaymentMethod;
  reference: string;
  notes: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** Contrat pre-selectionne : permet d'encaisser en 2 clics depuis une liste. */
  sale?: Sale | null;
  /** Utilise quand seul l'identifiant du contrat est connu (tableau des retards). */
  saleId?: number | null;
  onSuccess?: () => void;
}

export default function PaymentDialog({ open, onClose, sale, saleId, onSuccess }: Props) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [printReceipt, setPrintReceipt] = useState(true);

  const preselectedId = sale?.id ?? saleId ?? null;

  // Liste des contrats actifs, chargee uniquement quand l'utilisateur doit choisir.
  const salesQuery = useQuery({
    queryKey: ['sales', 'active-for-payment'],
    queryFn: () => salesApi.list({ status: 'ACTIVE', size: 200, sort: 'createdAt,desc' }),
    enabled: open && !preselectedId,
  });

  const preselectedQuery = useQuery({
    queryKey: ['sale', preselectedId],
    queryFn: () => salesApi.get(preselectedId as number),
    enabled: open && Boolean(preselectedId) && !sale,
  });

  const currentSale = sale ?? preselectedQuery.data ?? null;

  const { control, handleSubmit, register, reset, setValue, watch, formState } =
    useForm<FormValues>({
      defaultValues: {
        saleId: '',
        amount: '',
        paymentDate: today(),
        method: 'CASH',
        reference: '',
        notes: '',
      },
    });

  const selectedSaleId = watch('saleId');
  const options = salesQuery.data?.content ?? [];

  const activeSale = useMemo(() => {
    if (currentSale) {
      return currentSale;
    }
    return options.find((option) => option.id === selectedSaleId) ?? null;
  }, [currentSale, options, selectedSaleId]);

  useEffect(() => {
    if (!open) {
      return;
    }
    setError(null);
    reset({
      saleId: currentSale?.id ?? '',
      amount: currentSale ? Math.min(currentSale.monthlyAmount, currentSale.remainingAmount) : '',
      paymentDate: today(),
      method: 'CASH',
      reference: '',
      notes: '',
    });
  }, [open, currentSale, reset]);

  const mutation = useMutation({
    mutationFn: paymentsApi.create,
    onSuccess: async (payment) => {
      // Le client attend sa preuve de paiement : le reçu part avec l'encaissement.
      if (printReceipt) {
        try {
          await paymentsApi.downloadReceipt(payment.id);
        } catch {
          setError('Le versement est enregistré, mais le reçu n’a pas pu être téléchargé.');
        }
      }
      queryClient.invalidateQueries();
      onSuccess?.();
      onClose();
    },
    onError: (err) => setError(errorMessage(err, "Le versement n'a pas pu être enregistré")),
  });

  const submit = handleSubmit((values) => {
    if (!values.saleId) {
      setError('Sélectionnez un contrat');
      return;
    }
    mutation.mutate({
      saleId: Number(values.saleId),
      amount: Number(values.amount),
      paymentDate: values.paymentDate,
      method: values.method,
      reference: values.reference || undefined,
      notes: values.notes || undefined,
    });
  });

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Enregistrer un versement</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          {!preselectedId && (
            <Autocomplete
              options={options}
              loading={salesQuery.isLoading}
              getOptionLabel={(option) =>
                `${option.customerName} — ${option.productName} (${option.reference})`
              }
              onChange={(_, value) => {
                setValue('saleId', value?.id ?? '');
                setValue(
                  'amount',
                  value ? Math.min(value.monthlyAmount, value.remainingAmount) : '',
                );
              }}
              renderInput={(params) => (
                <TextField {...params} label="Contrat" placeholder="Client, produit ou référence" />
              )}
            />
          )}

          {activeSale && (
            <Box sx={{ bgcolor: 'action.hover', borderRadius: 2, p: 2 }}>
              <Typography variant="subtitle2">
                {activeSale.customerName} — {activeSale.productName}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Contrat {activeSale.reference} • mensualité {formatMoney(activeSale.monthlyAmount)}{' '}
                • reste {formatMoney(activeSale.remainingAmount)}
              </Typography>
            </Box>
          )}

          <Grid container spacing={2}>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                autoFocus
                label="Montant versé"
                type="number"
                inputProps={{ min: 1, step: 500 }}
                {...register('amount', { required: true, min: 1 })}
                error={Boolean(formState.errors.amount)}
                helperText={formState.errors.amount ? 'Montant obligatoire' : ' '}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                label="Date"
                type="date"
                InputLabelProps={{ shrink: true }}
                inputProps={{ max: today() }}
                {...register('paymentDate', { required: true })}
                helperText=" "
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <Controller
                name="method"
                control={control}
                render={({ field }) => (
                  <TextField {...field} select fullWidth label="Mode de paiement">
                    {Object.entries(PAYMENT_METHOD_LABELS).map(([value, label]) => (
                      <MenuItem key={value} value={value}>
                        {label}
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField fullWidth label="Référence (facultatif)" {...register('reference')} />
            </Grid>
            <Grid item xs={12}>
              <TextField
                fullWidth
                multiline
                minRows={2}
                label="Observations (facultatif)"
                {...register('notes')}
              />
            </Grid>
            <Grid item xs={12}>
              <FormControlLabel
                control={
                  <Checkbox
                    checked={printReceipt}
                    onChange={(event) => setPrintReceipt(event.target.checked)}
                  />
                }
                label="Éditer le reçu à remettre au client"
              />
            </Grid>
          </Grid>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onClose} color="inherit">
          Annuler
        </Button>
        <Button variant="contained" size="large" onClick={submit} disabled={mutation.isPending}>
          Enregistrer le paiement
        </Button>
      </DialogActions>
    </Dialog>
  );
}
