import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid,
  LinearProgress,
  Stack,
  Step,
  StepLabel,
  Stepper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DownloadIcon from '@mui/icons-material/Download';
import UploadFileIcon from '@mui/icons-material/UploadFile';

import { errorMessage } from '../api/client';
import { importApi } from '../api/endpoints';
import PageHeader from '../components/PageHeader';
import type { ImportReport } from '../types';
import { validateMaxFileSize } from '../utils/fileValidation';
import { formatDate, formatMoney } from '../utils/format';

const STEPS = ['Télécharger le modèle', 'Simuler', 'Confirmer'];

export default function ImportPage() {
  const queryClient = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);

  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const simulated = Boolean(report && report.dryRun);
  const applied = Boolean(report && report.applied);
  const blocking = report?.errors.length ?? 0;
  const activeStep = applied ? 3 : simulated ? 2 : file ? 1 : 0;

  const mutation = useMutation({
    mutationFn: ({ target, dryRun }: { target: File; dryRun: boolean }) =>
      importApi.legacySales(target, dryRun),
    onSuccess: (data) => {
      setReport(data);
      setError(null);
      if (data.applied) {
        queryClient.invalidateQueries();
      }
    },
    onError: (err) => {
      setError(errorMessage(err, "L'import n'a pas pu être traité"));
      setReport(null);
    },
  });

  const chooseFile = (selected: File | null, event: HTMLInputElement | null) => {
    if (selected) {
      const validationError = validateMaxFileSize(selected);
      if (validationError) {
        setError(validationError);
        if (event) {
          event.value = '';
        }
        return;
      }
    }
    setFile(selected);
    setReport(null);
    setError(null);
  };

  return (
    <Box>
      <PageHeader
        title="Reprise de données"
        subtitle="Importez les crédits déjà en cours, issus de votre cahier ou d'un tableur"
      />

      <Stepper activeStep={activeStep} sx={{ mb: 3 }}>
        {STEPS.map((label) => (
          <Step key={label}>
            <StepLabel>{label}</StepLabel>
          </Step>
        ))}
      </Stepper>

      {error && (
        <Alert severity="error" sx={{ mb: 2.5 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Grid container spacing={2.5}>
        <Grid item xs={12} md={5}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="h6" gutterBottom>
                1. Préparez votre fichier
              </Typography>
              <Typography variant="body2" color="text.secondary" paragraph>
                Le modèle contient les colonnes attendues et deux exemples. Une ligne = un crédit
                en cours. La colonne <strong>deja_paye</strong> correspond au total déjà versé par
                le client à ce jour.
              </Typography>
              <Button
                variant="outlined"
                startIcon={<DownloadIcon />}
                onClick={() => importApi.downloadTemplate()}
              >
                Télécharger le modèle
              </Button>

              <Typography variant="h6" sx={{ mt: 4 }} gutterBottom>
                2. Simulez
              </Typography>
              <Typography variant="body2" color="text.secondary" paragraph>
                Rien n&apos;est enregistré tant que vous n&apos;avez pas confirmé. La simulation
                signale les lignes à corriger.
              </Typography>

              <input
                ref={fileInput}
                type="file"
                accept=".csv,.xlsx,.txt"
                hidden
                onChange={(event) => chooseFile(event.target.files?.[0] ?? null, event.target)}
              />
              <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap">
                <Button
                  variant="outlined"
                  startIcon={<UploadFileIcon />}
                  onClick={() => fileInput.current?.click()}
                >
                  Choisir un fichier
                </Button>
                <Button
                  variant="contained"
                  disabled={!file || mutation.isPending}
                  onClick={() => file && mutation.mutate({ target: file, dryRun: true })}
                >
                  Simuler
                </Button>
              </Stack>
              {file && (
                <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
                  Fichier sélectionné : {file.name}
                </Typography>
              )}

              <Typography variant="h6" sx={{ mt: 4 }} gutterBottom>
                3. Confirmez
              </Typography>
              <Typography variant="body2" color="text.secondary" paragraph>
                L&apos;import est en tout ou rien : si une seule ligne est invalide, rien
                n&apos;est enregistré.
              </Typography>
              <Button
                variant="contained"
                color="success"
                disabled={!simulated || blocking > 0 || mutation.isPending || applied}
                onClick={() => file && mutation.mutate({ target: file, dryRun: false })}
              >
                Confirmer l&apos;import
              </Button>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={7}>
          {mutation.isPending && <LinearProgress sx={{ mb: 2 }} />}

          {!report && !mutation.isPending && (
            <Alert severity="info">
              <AlertTitle>Aucune simulation lancée</AlertTitle>
              Choisissez un fichier puis cliquez sur « Simuler » pour voir ce qui sera importé.
            </Alert>
          )}

          {report && (
            <>
              {applied ? (
                <Alert severity="success" icon={<CheckCircleIcon />} sx={{ mb: 2.5 }}>
                  <AlertTitle>Reprise terminée</AlertTitle>
                  {report.message}
                </Alert>
              ) : blocking > 0 ? (
                <Alert severity="error" sx={{ mb: 2.5 }}>
                  <AlertTitle>{blocking} ligne(s) à corriger</AlertTitle>
                  Corrigez votre fichier puis relancez la simulation. Aucune donnée n&apos;a été
                  modifiée.
                </Alert>
              ) : (
                <Alert severity="success" sx={{ mb: 2.5 }}>
                  <AlertTitle>Simulation réussie</AlertTitle>
                  {report.message} Vérifiez le détail ci-dessous, puis confirmez.
                </Alert>
              )}

              <Card variant="outlined" sx={{ mb: 2.5 }}>
                <CardContent>
                  <Grid container spacing={2}>
                    <Summary label="Lignes lues" value={String(report.totalRows)} />
                    <Summary label="Contrats" value={String(report.createdSales)} />
                    <Summary label="Nouveaux clients" value={String(report.newCustomers)} />
                    <Summary label="Clients existants" value={String(report.existingCustomers)} />
                    <Summary label="Nouveaux produits" value={String(report.newProducts)} />
                    <Summary label="Versements repris" value={String(report.recordedPayments)} />
                    <Summary label="Total financé" value={formatMoney(report.totalFinanced)} />
                    <Summary label="Déjà payé" value={formatMoney(report.totalAlreadyPaid)} />
                    <Summary label="Reste à récupérer" value={formatMoney(report.totalRemaining)} />
                  </Grid>
                </CardContent>
              </Card>

              {blocking > 0 && (
                <Card variant="outlined" sx={{ mb: 2.5 }}>
                  <CardContent>
                    <Typography variant="h6" gutterBottom color="error">
                      Lignes à corriger
                    </Typography>
                    <TableContainer sx={{ maxHeight: 320 }}>
                      <Table size="small" stickyHeader>
                        <TableHead>
                          <TableRow>
                            <TableCell>Ligne</TableCell>
                            <TableCell>Colonne</TableCell>
                            <TableCell>Valeur</TableCell>
                            <TableCell>Problème</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {report.errors.map((rowError, index) => (
                            // eslint-disable-next-line react/no-array-index-key
                            <TableRow key={`${rowError.line}-${rowError.column}-${index}`}>
                              <TableCell>
                                <Chip label={rowError.line} size="small" color="error" />
                              </TableCell>
                              <TableCell>{rowError.column}</TableCell>
                              <TableCell>{rowError.value || '—'}</TableCell>
                              <TableCell>{rowError.reason}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  </CardContent>
                </Card>
              )}

              {report.preview.length > 0 && (
                <Card variant="outlined">
                  <CardContent>
                    <Typography variant="h6" gutterBottom>
                      {applied ? 'Contrats importés' : 'Aperçu de ce qui sera créé'}
                    </Typography>
                    <TableContainer sx={{ maxHeight: 420 }}>
                      <Table size="small" stickyHeader>
                        <TableHead>
                          <TableRow>
                            <TableCell>Client</TableCell>
                            <TableCell>Produit</TableCell>
                            <TableCell align="right">Prix</TableCell>
                            <TableCell align="right">Mens.</TableCell>
                            <TableCell>Début</TableCell>
                            <TableCell align="right">Déjà payé</TableCell>
                            <TableCell align="right">Reste</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {report.preview.map((row) => (
                            <TableRow key={row.line} hover>
                              <TableCell>
                                {row.customer}
                                {row.customerIsNew && (
                                  <Chip label="nouveau" size="small" color="info" sx={{ ml: 1 }} />
                                )}
                              </TableCell>
                              <TableCell>
                                {row.product}
                                {row.productIsNew && (
                                  <Chip label="nouveau" size="small" color="info" sx={{ ml: 1 }} />
                                )}
                              </TableCell>
                              <TableCell align="right">{formatMoney(row.totalPrice)}</TableCell>
                              <TableCell align="right">{row.installmentCount}</TableCell>
                              <TableCell>{formatDate(row.startDate)}</TableCell>
                              <TableCell align="right">{formatMoney(row.alreadyPaid)}</TableCell>
                              <TableCell align="right">{formatMoney(row.remaining)}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  </CardContent>
                </Card>
              )}
            </>
          )}
        </Grid>
      </Grid>
    </Box>
  );
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <Grid item xs={6} sm={4}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
        {value}
      </Typography>
    </Grid>
  );
}
