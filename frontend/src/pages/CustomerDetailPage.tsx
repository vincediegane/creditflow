import { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Avatar,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
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
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import PhotoCameraIcon from '@mui/icons-material/PhotoCamera';

import { errorMessage } from '../api/client';
import { customersApi } from '../api/endpoints';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import ReminderDialog from '../components/ReminderDialog';
import StatusChip from '../components/StatusChip';
import { PAYMENT_METHOD_LABELS, formatDate, formatMoney, initials } from '../utils/format';

export default function CustomerDetailPage() {
  const { id } = useParams();
  const customerId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);

  const [reminderOpen, setReminderOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['customer-profile', customerId],
    queryFn: () => customersApi.profile(customerId),
    enabled: Number.isFinite(customerId),
  });

  const photoMutation = useMutation({
    mutationFn: (file: File) => customersApi.uploadPhoto(customerId, file),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['customer-profile', customerId] }),
    onError: (err) => setError(errorMessage(err, "La photo n'a pas pu être enregistrée")),
  });

  if (isLoading) {
    return <LinearProgress />;
  }
  if (!data) {
    return <Alert severity="error">Client introuvable.</Alert>;
  }

  const { customer } = data;

  return (
    <Box>
      <PageHeader
        title={customer.fullName}
        subtitle={`Client depuis le ${formatDate(customer.createdAt)}`}
        action={
          <Stack direction="row" spacing={1.5}>
            <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/clients')}>
              Retour
            </Button>
            <Button variant="contained" onClick={() => setReminderOpen(true)}>
              Générer la relance
            </Button>
          </Stack>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Grid container spacing={2.5}>
        <Grid item xs={12} md={4}>
          <Card variant="outlined">
            <CardContent>
              <Stack alignItems="center" spacing={1.5}>
                <Avatar
                  src={customer.photoUrl}
                  sx={{ width: 96, height: 96, bgcolor: 'primary.light', fontSize: 32 }}
                >
                  {initials(customer.fullName)}
                </Avatar>
                <Typography variant="h6">{customer.fullName}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {customer.phone}
                </Typography>
                <input
                  ref={fileInput}
                  type="file"
                  accept="image/*"
                  hidden
                  onChange={(event) => {
                    const file = event.target.files?.[0];
                    if (file) {
                      photoMutation.mutate(file);
                    }
                  }}
                />
                <Button
                  size="small"
                  startIcon={<PhotoCameraIcon />}
                  onClick={() => fileInput.current?.click()}
                  disabled={photoMutation.isPending}
                >
                  {customer.photoUrl ? 'Changer la photo' : 'Ajouter une photo'}
                </Button>
              </Stack>

              <Divider sx={{ my: 2 }} />

              <Stack spacing={1.2}>
                <Detail label="Profession" value={customer.profession} />
                <Detail label="Adresse" value={customer.address} />
                <Detail label="Numéro CNI" value={customer.cniNumber} />
                <Detail label="Observations" value={customer.notes} />
              </Stack>
            </CardContent>
          </Card>

          <Card variant="outlined" sx={{ mt: 2.5 }}>
            <CardContent>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                Situation financière
              </Typography>
              <Stack spacing={1.2}>
                <Detail label="Total acheté" value={formatMoney(data.totalPurchased)} />
                <Detail label="Total payé" value={formatMoney(data.totalPaid)} />
                <Detail label="Reste à payer" value={formatMoney(data.totalRemaining)} />
                <Detail label="Échéances en retard" value={String(data.lateInstallments)} />
              </Stack>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={8}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Historique des achats
              </Typography>
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Contrat</TableCell>
                      <TableCell>Produit</TableCell>
                      <TableCell align="right">Prix</TableCell>
                      <TableCell align="right">Reste</TableCell>
                      <TableCell>Fin prévue</TableCell>
                      <TableCell align="right">Statut</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {data.sales.map((sale) => (
                      <TableRow
                        key={sale.id}
                        hover
                        sx={{ cursor: 'pointer' }}
                        onClick={() => navigate(`/ventes/${sale.id}`)}
                      >
                        <TableCell>{sale.reference}</TableCell>
                        <TableCell>{sale.productName}</TableCell>
                        <TableCell align="right">{formatMoney(sale.totalPrice)}</TableCell>
                        <TableCell align="right">{formatMoney(sale.remainingAmount)}</TableCell>
                        <TableCell>{formatDate(sale.endDate)}</TableCell>
                        <TableCell align="right">
                          <StatusChip status={sale.late ? 'LATE' : sale.status} kind={sale.late ? 'installment' : 'sale'} />
                        </TableCell>
                      </TableRow>
                    ))}
                    {!data.sales.length && <EmptyRow colSpan={6} message="Aucun achat à crédit" />}
                  </TableBody>
                </Table>
              </Box>
            </CardContent>
          </Card>

          <Card variant="outlined" sx={{ mt: 2.5 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Historique des paiements
              </Typography>
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Date</TableCell>
                      <TableCell>Contrat</TableCell>
                      <TableCell>Mode</TableCell>
                      <TableCell>Référence</TableCell>
                      <TableCell align="right">Montant</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {data.payments.map((payment) => (
                      <TableRow key={payment.id} hover>
                        <TableCell>{formatDate(payment.paymentDate)}</TableCell>
                        <TableCell>{payment.saleReference}</TableCell>
                        <TableCell>{PAYMENT_METHOD_LABELS[payment.method]}</TableCell>
                        <TableCell>{payment.reference ?? '—'}</TableCell>
                        <TableCell align="right">{formatMoney(payment.amount)}</TableCell>
                      </TableRow>
                    ))}
                    {!data.payments.length && <EmptyRow colSpan={5} message="Aucun paiement" />}
                  </TableBody>
                </Table>
              </Box>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <ReminderDialog
        open={reminderOpen}
        customerId={customerId}
        customerName={customer.fullName}
        phone={customer.phone}
        onClose={() => setReminderOpen(false)}
      />
    </Box>
  );
}

function Detail({ label, value }: { label: string; value?: string | null }) {
  return (
    <Stack direction="row" justifyContent="space-between" spacing={2}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 600, textAlign: 'right' }}>
        {value || '—'}
      </Typography>
    </Stack>
  );
}
