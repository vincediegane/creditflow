import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
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
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import ReceiptIcon from '@mui/icons-material/ReceiptLong';
import SearchIcon from '@mui/icons-material/Search';

import { errorMessage } from '../api/client';
import { paymentsApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import ConfirmDialog from '../components/ConfirmDialog';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import PaymentDialog from '../components/PaymentDialog';
import type { PaymentMethod } from '../types';
import { PAYMENT_METHOD_LABELS, formatDate, formatMoney } from '../utils/format';

export default function PaymentsPage() {
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const [search, setSearch] = useState('');
  const [method, setMethod] = useState<PaymentMethod | ''>('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [toDelete, setToDelete] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ['payments', search, method, from, to, page, size],
    queryFn: () =>
      paymentsApi.list({
        search: search || undefined,
        method: method || undefined,
        from: from || undefined,
        to: to || undefined,
        page,
        size,
        sort: 'paymentDate,desc',
      }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => paymentsApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries();
      setToDelete(null);
    },
    onError: (err) => {
      setError(errorMessage(err, "Le versement n'a pas pu être annulé"));
      setToDelete(null);
    },
  });

  const rows = query.data?.content ?? [];
  const total = rows.reduce((sum, payment) => sum + payment.amount, 0);

  return (
    <Box>
      <PageHeader
        title="Paiements"
        subtitle="Historique des versements et encaissement rapide"
        action={
          <Button
            variant="contained"
            size="large"
            startIcon={<AddIcon />}
            onClick={() => setDialogOpen(true)}
          >
            Enregistrer un paiement
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Card variant="outlined">
        <Grid container spacing={2} sx={{ p: 2 }}>
          <Grid item xs={12} md={4}>
            <TextField
              fullWidth
              placeholder="Client, contrat, produit ou référence…"
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
          <Grid item xs={12} sm={4} md={3}>
            <TextField
              select
              fullWidth
              label="Mode"
              value={method}
              onChange={(event) => {
                setMethod(event.target.value as PaymentMethod | '');
                setPage(0);
              }}
            >
              <MenuItem value="">Tous</MenuItem>
              {Object.entries(PAYMENT_METHOD_LABELS).map(([value, label]) => (
                <MenuItem key={value} value={value}>
                  {label}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
          <Grid item xs={6} sm={4} md={2.5}>
            <TextField
              fullWidth
              type="date"
              label="Du"
              InputLabelProps={{ shrink: true }}
              value={from}
              onChange={(event) => setFrom(event.target.value)}
            />
          </Grid>
          <Grid item xs={6} sm={4} md={2.5}>
            <TextField
              fullWidth
              type="date"
              label="Au"
              InputLabelProps={{ shrink: true }}
              value={to}
              onChange={(event) => setTo(event.target.value)}
            />
          </Grid>
        </Grid>

        {query.isFetching && <LinearProgress />}

        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>Client</TableCell>
                <TableCell>Contrat</TableCell>
                <TableCell>Produit</TableCell>
                <TableCell>Mode</TableCell>
                <TableCell>Référence</TableCell>
                <TableCell align="right">Montant</TableCell>
                <TableCell align="right">Reste contrat</TableCell>
                <TableCell align="right" />
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((payment) => (
                <TableRow key={payment.id} hover>
                  <TableCell>{formatDate(payment.paymentDate)}</TableCell>
                  <TableCell>{payment.customerName}</TableCell>
                  <TableCell>{payment.saleReference}</TableCell>
                  <TableCell>{payment.productName}</TableCell>
                  <TableCell>{PAYMENT_METHOD_LABELS[payment.method]}</TableCell>
                  <TableCell>{payment.reference ?? '—'}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600 }}>
                    {formatMoney(payment.amount)}
                  </TableCell>
                  <TableCell align="right">{formatMoney(payment.saleRemainingAmount)}</TableCell>
                  <TableCell align="right">
                    <Tooltip title="Imprimer le reçu du client">
                      <IconButton
                        size="small"
                        onClick={() => paymentsApi.downloadReceipt(payment.id)}
                      >
                        <ReceiptIcon fontSize="small" />
                      </IconButton>
                    </Tooltip>
                    {user?.role === 'ADMIN' && (
                      <Tooltip title="Annuler ce versement">
                        <IconButton size="small" color="error" onClick={() => setToDelete(payment.id)}>
                          <DeleteIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={9} message="Aucun paiement trouvé" />
              )}
            </TableBody>
          </Table>
        </TableContainer>

        <Box sx={{ px: 2, py: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Total affiché : <strong>{formatMoney(total)}</strong>
          </Typography>
        </Box>

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

      <PaymentDialog open={dialogOpen} onClose={() => setDialogOpen(false)} />

      <ConfirmDialog
        open={toDelete !== null}
        title="Annuler le versement"
        message="Le contrat et les échéances seront intégralement recalculés. Continuer ?"
        confirmLabel="Annuler le versement"
        loading={deleteMutation.isPending}
        onConfirm={() => toDelete && deleteMutation.mutate(toDelete)}
        onClose={() => setToDelete(null)}
      />
    </Box>
  );
}
