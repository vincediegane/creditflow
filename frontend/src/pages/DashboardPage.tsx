import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid,
  LinearProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import EventIcon from '@mui/icons-material/EventAvailable';
import GroupIcon from '@mui/icons-material/Group';
import PaymentsIcon from '@mui/icons-material/Payments';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import SavingsIcon from '@mui/icons-material/Savings';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';

import { dashboardApi } from '../api/endpoints';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import PaymentDialog from '../components/PaymentDialog';
import ReminderDialog from '../components/ReminderDialog';
import StatCard from '../components/StatCard';
import StatusChip from '../components/StatusChip';
import { PAYMENT_METHOD_LABELS, formatDate, formatMoney, formatNumber } from '../utils/format';

export default function DashboardPage() {
  const navigate = useNavigate();
  const [paymentOpen, setPaymentOpen] = useState(false);
  const [paymentSaleId, setPaymentSaleId] = useState<number | null>(null);
  const [reminder, setReminder] = useState<{ customerId: number; name: string; phone: string } | null>(
    null,
  );

  const { data, isLoading, isError } = useQuery({
    queryKey: ['dashboard'],
    queryFn: dashboardApi.overview,
    refetchInterval: 60_000,
  });

  const metrics = data?.metrics;

  return (
    <Box>
      <PageHeader
        title="Tableau de bord"
        subtitle={data ? `Situation au ${formatDate(data.referenceDate)}` : 'Chargement…'}
        action={
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <Button
              variant="contained"
              size="large"
              startIcon={<PaymentsIcon />}
              onClick={() => {
                setPaymentSaleId(null);
                setPaymentOpen(true);
              }}
            >
              Encaisser un paiement
            </Button>
            <Button
              variant="outlined"
              size="large"
              startIcon={<AddIcon />}
              onClick={() => navigate('/ventes/nouvelle')}
            >
              Nouvelle vente
            </Button>
          </Stack>
        }
      />

      {isLoading && <LinearProgress sx={{ mb: 2 }} />}
      {isError && <Alert severity="error">Impossible de charger le tableau de bord.</Alert>}

      <Grid container spacing={2.5}>
        <Grid item xs={12} sm={6} lg={3}>
          <StatCard
            label="Clients"
            value={formatNumber(metrics?.totalCustomers)}
            icon={<GroupIcon />}
            onClick={() => navigate('/clients')}
          />
        </Grid>
        <Grid item xs={12} sm={6} lg={3}>
          <StatCard
            label="Ventes à crédit"
            value={formatNumber(metrics?.totalSales)}
            hint={`${formatNumber(metrics?.activeSales)} en cours • ${formatNumber(metrics?.completedSales)} soldées`}
            icon={<ReceiptLongIcon />}
            color="secondary"
            onClick={() => navigate('/ventes')}
          />
        </Grid>
        <Grid item xs={12} sm={6} lg={3}>
          <StatCard
            label="Reste à récupérer"
            value={formatMoney(metrics?.totalRemaining)}
            icon={<SavingsIcon />}
            color="warning"
            onClick={() => navigate('/rapports')}
          />
        </Grid>
        <Grid item xs={12} sm={6} lg={3}>
          <StatCard
            label="Encaissé ce mois"
            value={formatMoney(metrics?.collectedThisMonth)}
            hint={`Aujourd'hui : ${formatMoney(metrics?.collectedToday)}`}
            icon={<PaymentsIcon />}
            color="success"
            onClick={() => navigate('/paiements')}
          />
        </Grid>
        <Grid item xs={12} sm={6} lg={3}>
          <StatCard
            label="Clients en retard"
            value={formatNumber(metrics?.lateCustomersCount)}
            hint={`${formatNumber(metrics?.lateInstallmentsCount)} échéances • ${formatMoney(metrics?.lateAmount)}`}
            icon={<WarningAmberIcon />}
            color="error"
            onClick={() => navigate('/relances')}
          />
        </Grid>
        <Grid item xs={12} sm={6} lg={3}>
          <StatCard
            label="Paiements du jour"
            value={formatNumber(metrics?.todayPaymentsCount)}
            hint={formatMoney(metrics?.collectedToday)}
            icon={<PaymentsIcon />}
            onClick={() => navigate('/paiements')}
          />
        </Grid>
        <Grid item xs={12} sm={6} lg={3}>
          <StatCard
            label="Échéances à 30 jours"
            value={formatNumber(metrics?.upcomingInstallmentsCount)}
            icon={<EventIcon />}
            color="secondary"
            onClick={() => navigate('/echeances')}
          />
        </Grid>
      </Grid>

      <Grid container spacing={2.5} sx={{ mt: 0.5 }}>
        <Grid item xs={12} lg={7}>
          <Card variant="outlined">
            <CardContent>
              <Stack direction="row" justifyContent="space-between" alignItems="center" mb={1}>
                <Typography variant="h6">Clients en retard</Typography>
                <Button size="small" onClick={() => navigate('/relances')}>
                  Voir tout
                </Button>
              </Stack>
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Client</TableCell>
                      <TableCell>Téléphone</TableCell>
                      <TableCell align="right">Retard</TableCell>
                      <TableCell align="right">Montant dû</TableCell>
                      <TableCell align="right">Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {(data?.lateCustomers ?? []).map((row) => (
                      <TableRow key={row.customerId} hover>
                        <TableCell>{row.customerName}</TableCell>
                        <TableCell>{row.phone}</TableCell>
                        <TableCell align="right">
                          <Chip label={`${row.daysLate} j`} color="error" size="small" />
                        </TableCell>
                        <TableCell align="right">{formatMoney(row.lateAmount)}</TableCell>
                        <TableCell align="right">
                          <Stack direction="row" spacing={1} justifyContent="flex-end">
                            <Button
                              size="small"
                              variant="contained"
                              onClick={() => {
                                setPaymentSaleId(row.primarySaleId);
                                setPaymentOpen(true);
                              }}
                            >
                              Encaisser
                            </Button>
                            <Button
                              size="small"
                              onClick={() =>
                                setReminder({
                                  customerId: row.customerId,
                                  name: row.customerName,
                                  phone: row.phone,
                                })
                              }
                            >
                              Relancer
                            </Button>
                          </Stack>
                        </TableCell>
                      </TableRow>
                    ))}
                    {!data?.lateCustomers.length && (
                      <EmptyRow colSpan={5} message="Aucun client en retard 🎉" />
                    )}
                  </TableBody>
                </Table>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} lg={5}>
          <Card variant="outlined" sx={{ mb: 2.5 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Paiements du jour
              </Typography>
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Client</TableCell>
                      <TableCell>Mode</TableCell>
                      <TableCell align="right">Montant</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {(data?.todayPayments ?? []).map((payment) => (
                      <TableRow key={payment.id} hover>
                        <TableCell>{payment.customerName}</TableCell>
                        <TableCell>{PAYMENT_METHOD_LABELS[payment.method]}</TableCell>
                        <TableCell align="right">{formatMoney(payment.amount)}</TableCell>
                      </TableRow>
                    ))}
                    {!data?.todayPayments.length && (
                      <EmptyRow colSpan={3} message="Aucun paiement enregistré aujourd'hui" />
                    )}
                  </TableBody>
                </Table>
              </Box>
            </CardContent>
          </Card>

          <Card variant="outlined">
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Prochaines échéances
              </Typography>
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Client</TableCell>
                      <TableCell>Date prévue</TableCell>
                      <TableCell align="right">Montant</TableCell>
                      <TableCell align="right">Statut</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {(data?.upcomingInstallments ?? []).map((installment) => (
                      <TableRow key={installment.id} hover>
                        <TableCell>{installment.customerName}</TableCell>
                        <TableCell>{formatDate(installment.dueDate)}</TableCell>
                        <TableCell align="right">{formatMoney(installment.remaining)}</TableCell>
                        <TableCell align="right">
                          <StatusChip status={installment.displayStatus} kind="installment" />
                        </TableCell>
                      </TableRow>
                    ))}
                    {!data?.upcomingInstallments.length && (
                      <EmptyRow colSpan={4} message="Aucune échéance dans les 30 prochains jours" />
                    )}
                  </TableBody>
                </Table>
              </Box>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <PaymentDialog
        open={paymentOpen}
        saleId={paymentSaleId}
        onClose={() => setPaymentOpen(false)}
      />
      <ReminderDialog
        open={Boolean(reminder)}
        customerId={reminder?.customerId}
        customerName={reminder?.name}
        phone={reminder?.phone}
        onClose={() => setReminder(null)}
      />
    </Box>
  );
}
