import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  FormControlLabel,
  Grid,
  LinearProgress,
  MenuItem,
  Switch,
  TextField,
  Typography,
} from '@mui/material';

import { errorMessage } from '../api/client';
import { penaltySettingsApi } from '../api/endpoints';
import PageHeader from '../components/PageHeader';
import type { PenaltySettingsPayload } from '../types';

const EMPTY_FORM: PenaltySettingsPayload = {
  enabled: false,
  rateType: 'FIXED',
  rate: 0,
  period: 'DAY',
  capPercent: undefined,
};

export default function PenaltySettingsPage() {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const query = useQuery({ queryKey: ['penalty-settings'], queryFn: penaltySettingsApi.get });

  const { register, control, handleSubmit, reset, watch, formState } = useForm<PenaltySettingsPayload>({
    defaultValues: EMPTY_FORM,
  });

  useEffect(() => {
    if (query.data) {
      reset({
        enabled: query.data.enabled,
        rateType: query.data.rateType,
        rate: query.data.rate,
        period: query.data.period,
        capPercent: query.data.capPercent,
      });
    }
  }, [query.data, reset]);

  const updateMutation = useMutation({
    mutationFn: (payload: PenaltySettingsPayload) => penaltySettingsApi.update(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['penalty-settings'] });
      setSuccess(true);
      setError(null);
    },
    onError: (err) => {
      setError(errorMessage(err, "Les reglages n'ont pas pu etre enregistres"));
      setSuccess(false);
    },
  });

  const rateType = watch('rateType');

  const submit = handleSubmit((values) => {
    setSuccess(false);
    updateMutation.mutate({
      ...values,
      capPercent: values.capPercent === undefined || values.capPercent === null || `${values.capPercent}` === ''
        ? undefined
        : Number(values.capPercent),
    });
  });

  return (
    <Box>
      <PageHeader
        title="Pénalités de retard"
        subtitle="Taux appliqué aux échéances en retard, imputé avant le principal"
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {success && (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setSuccess(false)}>
          Réglages enregistrés
        </Alert>
      )}

      <Card variant="outlined">
        {query.isFetching && <LinearProgress />}
        <CardContent>
          <Grid container spacing={2.5}>
            <Grid item xs={12}>
              <Controller
                name="enabled"
                control={control}
                render={({ field }) => (
                  <FormControlLabel
                    control={<Switch checked={field.value} onChange={(event) => field.onChange(event.target.checked)} />}
                    label="Activer la pénalité de retard"
                  />
                )}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <Controller
                name="rateType"
                control={control}
                render={({ field }) => (
                  <TextField {...field} select fullWidth label="Type de taux">
                    <MenuItem value="FIXED">Fixe (montant)</MenuItem>
                    <MenuItem value="PERCENT">Pourcentage de l'échéance</MenuItem>
                  </TextField>
                )}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <Controller
                name="period"
                control={control}
                render={({ field }) => (
                  <TextField {...field} select fullWidth label="Périodicité">
                    <MenuItem value="DAY">Par jour</MenuItem>
                    <MenuItem value="WEEK">Par semaine</MenuItem>
                  </TextField>
                )}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                type="number"
                label={rateType === 'PERCENT' ? 'Taux par période (%)' : 'Montant par période (FCFA)'}
                {...register('rate', { required: true, valueAsNumber: true, min: 0 })}
                error={Boolean(formState.errors.rate)}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                type="number"
                label="Plafond (% de l'échéance, laisser vide = pas de plafond)"
                {...register('capPercent', { valueAsNumber: true })}
              />
            </Grid>
          </Grid>

          {query.data?.updatedAt && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
              Dernière mise à jour le {query.data.updatedAt} par {query.data.updatedBy ?? '—'}
            </Typography>
          )}

          <Button
            variant="contained"
            sx={{ mt: 3 }}
            onClick={submit}
            disabled={updateMutation.isPending}
          >
            Enregistrer
          </Button>
        </CardContent>
      </Card>
    </Box>
  );
}
