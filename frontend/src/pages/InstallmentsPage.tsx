import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Box,
  Button,
  Card,
  Chip,
  FormControlLabel,
  Grid,
  InputAdornment,
  LinearProgress,
  MenuItem,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';

import { installmentsApi } from '../api/endpoints';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import PaymentDialog from '../components/PaymentDialog';
import StatusChip from '../components/StatusChip';
import type { InstallmentStatus } from '../types';
import { formatDate, formatMoney } from '../utils/format';

export default function InstallmentsPage() {
  const navigate = useNavigate();

  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<InstallmentStatus | ''>('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [onlyLate, setOnlyLate] = useState(false);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [paymentSaleId, setPaymentSaleId] = useState<number | null>(null);

  const query = useQuery({
    queryKey: ['installments', search, status, from, to, onlyLate, page, size],
    queryFn: () =>
      installmentsApi.list({
        search: search || undefined,
        status: status || undefined,
        from: from || undefined,
        to: to || undefined,
        onlyLate,
        page,
        size,
        sort: 'dueDate,asc',
      }),
  });

  const rows = query.data?.content ?? [];

  return (
    <Box>
      <PageHeader title="Échéances" subtitle="Toutes les échéances, avec recherche et filtres" />

      <Card variant="outlined">
        <Grid container spacing={2} sx={{ p: 2 }} alignItems="center">
          <Grid item xs={12} md={4}>
            <TextField
              fullWidth
              placeholder="Client, téléphone, produit ou contrat…"
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
          <Grid item xs={12} sm={4} md={2}>
            <TextField
              select
              fullWidth
              label="Statut"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as InstallmentStatus | '');
                setPage(0);
              }}
            >
              <MenuItem value="">Tous</MenuItem>
              <MenuItem value="PENDING">À venir</MenuItem>
              <MenuItem value="PARTIAL">Partiel</MenuItem>
              <MenuItem value="PAID">Payées</MenuItem>
            </TextField>
          </Grid>
          <Grid item xs={6} sm={4} md={2}>
            <TextField
              fullWidth
              type="date"
              label="Du"
              InputLabelProps={{ shrink: true }}
              value={from}
              onChange={(event) => setFrom(event.target.value)}
            />
          </Grid>
          <Grid item xs={6} sm={4} md={2}>
            <TextField
              fullWidth
              type="date"
              label="Au"
              InputLabelProps={{ shrink: true }}
              value={to}
              onChange={(event) => setTo(event.target.value)}
            />
          </Grid>
          <Grid item xs={12} md={2}>
            <FormControlLabel
              control={
                <Switch
                  checked={onlyLate}
                  onChange={(event) => {
                    setOnlyLate(event.target.checked);
                    setPage(0);
                  }}
                />
              }
              label="En retard"
            />
          </Grid>
        </Grid>

        {query.isFetching && <LinearProgress />}

        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Client</TableCell>
                <TableCell>Produit</TableCell>
                <TableCell>Contrat</TableCell>
                <TableCell align="right">N°</TableCell>
                <TableCell>Date prévue</TableCell>
                <TableCell align="right">Montant</TableCell>
                <TableCell align="right">Reste</TableCell>
                <TableCell align="right">Pénalité</TableCell>
                <TableCell>Statut</TableCell>
                <TableCell align="right">Retard</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((installment) => (
                <TableRow key={installment.id} hover>
                  <TableCell>{installment.customerName}</TableCell>
                  <TableCell>{installment.productName}</TableCell>
                  <TableCell>
                    <Button size="small" onClick={() => navigate(`/ventes/${installment.saleId}`)}>
                      {installment.saleReference}
                    </Button>
                  </TableCell>
                  <TableCell align="right">
                    {installment.number}
                  </TableCell>
                  <TableCell>{formatDate(installment.dueDate)}</TableCell>
                  <TableCell align="right">{formatMoney(installment.amount)}</TableCell>
                  <TableCell align="right">{formatMoney(installment.remaining)}</TableCell>
                  <TableCell align="right">{formatMoney(installment.penaltyAmount)}</TableCell>
                  <TableCell>
                    <StatusChip status={installment.displayStatus} kind="installment" />
                  </TableCell>
                  <TableCell align="right">
                    {installment.daysLate > 0 ? (
                      <Chip label={`${installment.daysLate} j`} color="error" size="small" />
                    ) : (
                      '—'
                    )}
                  </TableCell>
                  <TableCell align="right">
                    {installment.displayStatus !== 'PAID' && (
                      <Button
                        size="small"
                        variant="contained"
                        onClick={() => setPaymentSaleId(installment.saleId)}
                      >
                        Encaisser
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={11} message="Aucune échéance trouvée" />
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
          rowsPerPageOptions={[10, 20, 50, 100]}
          labelRowsPerPage="Lignes par page"
        />
      </Card>

      <PaymentDialog
        open={paymentSaleId !== null}
        saleId={paymentSaleId}
        onClose={() => setPaymentSaleId(null)}
      />
    </Box>
  );
}
