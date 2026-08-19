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
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import NotificationsActiveIcon from '@mui/icons-material/NotificationsActive';
import PaymentsIcon from '@mui/icons-material/Payments';

import { remindersApi } from '../api/endpoints';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import PaymentDialog from '../components/PaymentDialog';
import ReminderDialog from '../components/ReminderDialog';
import { formatDate, formatMoney } from '../utils/format';

export default function LateCustomersPage() {
  const navigate = useNavigate();

  const [paymentSaleId, setPaymentSaleId] = useState<number | null>(null);
  const [reminder, setReminder] = useState<{
    customerId: number;
    name: string;
    phone: string;
  } | null>(null);

  const query = useQuery({ queryKey: ['late-customers'], queryFn: remindersApi.lateCustomers });
  const settings = useQuery({ queryKey: ['reminder-settings'], queryFn: remindersApi.settings });

  const rows = query.data ?? [];
  const totalLate = rows.reduce((sum, row) => sum + row.lateAmount, 0);

  return (
    <Box>
      <PageHeader
        title="Relances"
        subtitle="Clients en retard et génération des messages à copier"
      />

      {query.isLoading && <LinearProgress sx={{ mb: 2 }} />}

      <Grid container spacing={2.5} sx={{ mb: 2.5 }}>
        <Grid item xs={12} md={4}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="body2" color="text.secondary">
                Clients en retard
              </Typography>
              <Typography variant="h4">{rows.length}</Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid item xs={12} md={4}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="body2" color="text.secondary">
                Montant en retard
              </Typography>
              <Typography variant="h4">{formatMoney(totalLate)}</Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid item xs={12} md={4}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="body2" color="text.secondary">
                Canal de relance
              </Typography>
              <Typography variant="h6" sx={{ mt: 0.5 }}>
                {settings.data?.channel === 'MANUAL_COPY'
                  ? 'Copie manuelle (WhatsApp / SMS)'
                  : (settings.data?.channel ?? '—')}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                Versement attendu du {settings.data?.windowStartDay ?? 1} au{' '}
                {settings.data?.windowEndDay ?? 10} du mois
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Alert severity="info" sx={{ mb: 2.5 }}>
        Aucun message n'est envoyé automatiquement dans cette version. Cliquez sur « Générer la
        relance », copiez le texte, puis collez-le dans WhatsApp ou par SMS. Le branchement de
        WhatsApp Cloud API est déjà prévu côté serveur.
      </Alert>

      <Card variant="outlined">
        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Client</TableCell>
                <TableCell>Téléphone</TableCell>
                <TableCell>Produit(s)</TableCell>
                <TableCell>Échéance la plus ancienne</TableCell>
                <TableCell align="right">Jours de retard</TableCell>
                <TableCell align="right">Échéances</TableCell>
                <TableCell align="right">Montant en retard</TableCell>
                <TableCell align="right">Reste à payer</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.customerId} hover>
                  <TableCell>
                    <Button size="small" onClick={() => navigate(`/clients/${row.customerId}`)}>
                      {row.customerName}
                    </Button>
                  </TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>{row.phone}</TableCell>
                  <TableCell>{row.productNames}</TableCell>
                  <TableCell>{formatDate(row.oldestDueDate)}</TableCell>
                  <TableCell align="right">
                    <Chip label={`${row.daysLate} j`} color="error" size="small" />
                  </TableCell>
                  <TableCell align="right">{row.lateInstallments}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600 }}>
                    {formatMoney(row.lateAmount)}
                  </TableCell>
                  <TableCell align="right">{formatMoney(row.remainingAmount)}</TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={1} justifyContent="flex-end">
                      <Button
                        size="small"
                        variant="outlined"
                        startIcon={<PaymentsIcon />}
                        onClick={() => setPaymentSaleId(row.primarySaleId)}
                      >
                        Encaisser
                      </Button>
                      <Button
                        size="small"
                        variant="contained"
                        startIcon={<NotificationsActiveIcon />}
                        onClick={() =>
                          setReminder({
                            customerId: row.customerId,
                            name: row.customerName,
                            phone: row.phone,
                          })
                        }
                      >
                        Générer la relance
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={9} message="Aucun client en retard 🎉" />
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      <PaymentDialog
        open={paymentSaleId !== null}
        saleId={paymentSaleId}
        onClose={() => setPaymentSaleId(null)}
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
