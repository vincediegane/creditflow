import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Box,
  Button,
  Card,
  Chip,
  Grid,
  IconButton,
  InputAdornment,
  LinearProgress,
  MenuItem,
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
import PaymentsIcon from '@mui/icons-material/Payments';
import SearchIcon from '@mui/icons-material/Search';
import VisibilityIcon from '@mui/icons-material/VisibilityOutlined';

import { salesApi } from '../api/endpoints';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import PaymentDialog from '../components/PaymentDialog';
import StatusChip from '../components/StatusChip';
import type { Sale, SaleStatus } from '../types';
import { formatDate, formatMoney } from '../utils/format';

export default function SalesPage() {
  const navigate = useNavigate();

  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<SaleStatus | ''>('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [paymentSale, setPaymentSale] = useState<Sale | null>(null);

  const query = useQuery({
    queryKey: ['sales', search, status, page, size],
    queryFn: () =>
      salesApi.list({
        search: search || undefined,
        status: status || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      }),
  });

  const rows = query.data?.content ?? [];

  return (
    <Box>
      <PageHeader
        title="Ventes à crédit"
        subtitle="Contrats, échéanciers et soldes restants"
        action={
          <Button
            variant="contained"
            size="large"
            startIcon={<AddIcon />}
            onClick={() => navigate('/ventes/nouvelle')}
          >
            Nouvelle vente
          </Button>
        }
      />

      <Card variant="outlined">
        <Grid container spacing={2} sx={{ p: 2 }}>
          <Grid item xs={12} md={8}>
            <TextField
              fullWidth
              placeholder="Client, téléphone, produit ou référence de contrat…"
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
              label="Statut"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as SaleStatus | '');
                setPage(0);
              }}
            >
              <MenuItem value="">Tous</MenuItem>
              <MenuItem value="ACTIVE">En cours</MenuItem>
              <MenuItem value="COMPLETED">Soldés</MenuItem>
              <MenuItem value="CANCELLED">Annulés</MenuItem>
            </TextField>
          </Grid>
        </Grid>

        {query.isFetching && <LinearProgress />}

        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Contrat</TableCell>
                <TableCell>Client</TableCell>
                <TableCell>Produit</TableCell>
                <TableCell align="right">Mensualité</TableCell>
                <TableCell align="right">Payé / Total</TableCell>
                <TableCell align="right">Reste</TableCell>
                <TableCell>Prochaine échéance</TableCell>
                <TableCell>Statut</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((sale) => (
                <TableRow key={sale.id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{sale.reference}</TableCell>
                  <TableCell>{sale.customerName}</TableCell>
                  <TableCell>{sale.productName}</TableCell>
                  <TableCell align="right">{formatMoney(sale.monthlyAmount)}</TableCell>
                  <TableCell align="right">
                    {sale.paidInstallments}/{sale.installmentCount}
                  </TableCell>
                  <TableCell align="right">{formatMoney(sale.remainingAmount)}</TableCell>
                  <TableCell>
                    {formatDate(sale.nextDueDate)}
                    {sale.late && (
                      <Chip
                        label={`${sale.daysLate} j`}
                        color="error"
                        size="small"
                        sx={{ ml: 1 }}
                      />
                    )}
                  </TableCell>
                  <TableCell>
                    <StatusChip status={sale.status} kind="sale" />
                  </TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                      {sale.status === 'ACTIVE' && (
                        <Tooltip title="Encaisser un versement">
                          <IconButton color="primary" onClick={() => setPaymentSale(sale)}>
                            <PaymentsIcon />
                          </IconButton>
                        </Tooltip>
                      )}
                      <Tooltip title="Voir le contrat">
                        <IconButton onClick={() => navigate(`/ventes/${sale.id}`)}>
                          <VisibilityIcon />
                        </IconButton>
                      </Tooltip>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={9} message="Aucun contrat trouvé" />
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

      <PaymentDialog
        open={Boolean(paymentSale)}
        sale={paymentSale}
        onClose={() => setPaymentSale(null)}
      />
    </Box>
  );
}
