import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid,
  LinearProgress,
  Snackbar,
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
import SendIcon from '@mui/icons-material/Send';

import { errorMessage } from '../api/client';
import { remindersApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import PaymentDialog from '../components/PaymentDialog';
import ReminderDialog from '../components/ReminderDialog';
import { formatDate, formatMoney } from '../utils/format';

export default function LateCustomersPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';

  const [paymentSaleId, setPaymentSaleId] = useState<number | null>(null);
  const [reminder, setReminder] = useState<{
    customerId: number;
    name: string;
    phone: string;
  } | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const query = useQuery({ queryKey: ['late-customers'], queryFn: remindersApi.lateCustomers });
  const settings = useQuery({ queryKey: ['reminder-settings'], queryFn: remindersApi.settings });

  const rows = query.data ?? [];
  const totalLate = rows.reduce((sum, row) => sum + row.lateAmount, 0);

  const automaticChannelActive = settings.data?.channel !== 'MANUAL_COPY';
  const canSendAutomatically = isAdmin && automaticChannelActive;

  const invalidateAfterSend = () => {
    queryClient.invalidateQueries({ queryKey: ['late-customers'] });
    queryClient.invalidateQueries({ queryKey: ['audit-log'] });
  };

  const sendOneMutation = useMutation({
    mutationFn: remindersApi.send,
    onSuccess: (data) => {
      invalidateAfterSend();
      setFeedback(data.sent ? `Relance envoyée à ${data.customerName}` : `Échec de l'envoi à ${data.customerName}`);
    },
    onError: (err) => setFeedback(errorMessage(err, "Impossible d'envoyer la relance")),
  });

  const sendAllMutation = useMutation({
    mutationFn: () => remindersApi.sendAll(),
    onSuccess: (data) => {
      invalidateAfterSend();
      setFeedback(`${data.sent} relance(s) envoyée(s), ${data.failed} échec(s) sur ${data.total}`);
    },
    onError: (err) => setFeedback(errorMessage(err, "Impossible d'envoyer les relances")),
  });

  const handleSendAll = () => {
    if (!window.confirm(`Envoyer une relance automatique aux ${rows.length} client(s) en retard ?`)) {
      return;
    }
    sendAllMutation.mutate();
  };

  return (
    <Box>
      <PageHeader
        title="Relances"
        subtitle="Clients en retard et génération des messages à copier"
        action={
          canSendAutomatically ? (
            <Button
              variant="contained"
              startIcon={<SendIcon />}
              disabled={sendAllMutation.isPending || rows.length === 0}
              onClick={handleSendAll}
            >
              Envoyer les relances
            </Button>
          ) : undefined
        }
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

      {!automaticChannelActive && (
        <Alert severity="info" sx={{ mb: 2.5 }}>
          Aucun message n'est envoyé automatiquement dans cette version. Cliquez sur « Générer la
          relance », copiez le texte, puis collez-le dans WhatsApp ou par SMS. Le branchement de
          WhatsApp Cloud API est déjà prévu côté serveur.
        </Alert>
      )}

      {automaticChannelActive && isAdmin && settings.data?.channel === 'WHATSAPP_CLOUD_API' && (
        <Alert severity="info" sx={{ mb: 2.5 }}>
          Les relances automatiques WhatsApp utilisent un modèle de message pré-approuvé par
          Meta. En dehors des 24h suivant le dernier message du client, seul ce modèle peut être
          envoyé — configurez-le dans Meta Business Manager avant d'activer ce canal.
        </Alert>
      )}

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
                <TableCell align="right">Pénalité</TableCell>
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
                  <TableCell align="right">{formatMoney(row.penaltyAmount)}</TableCell>
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
                      {canSendAutomatically && (
                        <Button
                          size="small"
                          variant="outlined"
                          color="success"
                          startIcon={<SendIcon />}
                          disabled={
                            sendOneMutation.isPending &&
                            sendOneMutation.variables?.customerId === row.customerId
                          }
                          onClick={() => sendOneMutation.mutate({ customerId: row.customerId })}
                        >
                          Envoyer automatiquement
                        </Button>
                      )}
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && !query.isLoading && (
                <EmptyRow colSpan={10} message="Aucun client en retard 🎉" />
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

      <Snackbar
        open={Boolean(feedback)}
        autoHideDuration={4000}
        onClose={() => setFeedback(null)}
        message={feedback}
      />
    </Box>
  );
}
