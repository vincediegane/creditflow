import { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  IconButton,
  LinearProgress,
  LinearProgress as Progress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import BadgeIcon from '@mui/icons-material/Badge';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DrawIcon from '@mui/icons-material/Draw';
import PaymentsIcon from '@mui/icons-material/Payments';
import ReceiptIcon from '@mui/icons-material/ReceiptLong';

import { errorMessage } from '../api/client';
import { paymentsApi, salesApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import AuditHistoryCard from '../components/AuditHistoryCard';
import ConfirmDialog from '../components/ConfirmDialog';
import EmptyRow from '../components/EmptyRow';
import PageHeader from '../components/PageHeader';
import PaymentDialog from '../components/PaymentDialog';
import ReminderDialog from '../components/ReminderDialog';
import SignaturePad from '../components/SignaturePad';
import StatusChip from '../components/StatusChip';
import type { SaleAttachment } from '../types';
import { PAYMENT_METHOD_LABELS, formatDate, formatMoney } from '../utils/format';

const ATTACHMENT_TYPE_LABELS: Record<SaleAttachment['type'], string> = {
  ID_DOCUMENT: "Pièce d'identité",
  SIGNATURE: 'Signature',
  OTHER: 'Autre',
};

export default function SaleDetailPage() {
  const { id } = useParams();
  const saleId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const [paymentOpen, setPaymentOpen] = useState(false);
  const [reminderOpen, setReminderOpen] = useState(false);
  const [paymentToDelete, setPaymentToDelete] = useState<number | null>(null);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [signatureOpen, setSignatureOpen] = useState(false);
  const [attachmentToDelete, setAttachmentToDelete] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const idDocumentInput = useRef<HTMLInputElement>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['sale-detail', saleId],
    queryFn: () => salesApi.detail(saleId),
    enabled: Number.isFinite(saleId),
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['sale-detail', saleId] });
    queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const deletePaymentMutation = useMutation({
    mutationFn: (paymentId: number) => paymentsApi.remove(paymentId),
    onSuccess: () => {
      refresh();
      setPaymentToDelete(null);
    },
    onError: (err) => {
      setError(errorMessage(err, "Le versement n'a pas pu être annulé"));
      setPaymentToDelete(null);
    },
  });

  const cancelSaleMutation = useMutation({
    mutationFn: () => salesApi.cancel(saleId),
    onSuccess: () => {
      refresh();
      setCancelOpen(false);
    },
    onError: (err) => {
      setError(errorMessage(err, "Le contrat n'a pas pu être annulé"));
      setCancelOpen(false);
    },
  });

  const uploadAttachmentMutation = useMutation({
    mutationFn: ({ type, file }: { type: SaleAttachment['type']; file: File }) =>
      salesApi.uploadAttachment(saleId, type, file),
    onSuccess: () => {
      refresh();
      setSignatureOpen(false);
    },
    onError: (err) => setError(errorMessage(err, "La pièce jointe n'a pas pu être enregistrée")),
  });

  const deleteAttachmentMutation = useMutation({
    mutationFn: (attachmentId: number) => salesApi.removeAttachment(saleId, attachmentId),
    onSuccess: () => {
      refresh();
      setAttachmentToDelete(null);
    },
    onError: (err) => {
      setError(errorMessage(err, "La pièce jointe n'a pas pu être supprimée"));
      setAttachmentToDelete(null);
    },
  });

  if (isLoading) {
    return <LinearProgress />;
  }
  if (!data) {
    return <Alert severity="error">Contrat introuvable.</Alert>;
  }

  const { sale, installments, payments, attachments } = data;
  const progress = sale.financedAmount > 0 ? (sale.amountPaid / sale.financedAmount) * 100 : 0;

  return (
    <Box>
      <PageHeader
        title={`Contrat ${sale.reference}`}
        subtitle={`${sale.customerName} • ${sale.productName}`}
        action={
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/ventes')}>
              Retour
            </Button>
            <Button onClick={() => setReminderOpen(true)}>Générer la relance</Button>
            <Button startIcon={<ReceiptIcon />} onClick={() => salesApi.downloadInvoice(sale.id)}>
              Télécharger la facture
            </Button>
            {sale.status === 'ACTIVE' && (
              <Button
                variant="contained"
                startIcon={<PaymentsIcon />}
                onClick={() => setPaymentOpen(true)}
              >
                Encaisser
              </Button>
            )}
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
              <Stack direction="row" justifyContent="space-between" alignItems="center" mb={2}>
                <Typography variant="h6">Résumé</Typography>
                <StatusChip
                  status={sale.late ? 'LATE' : sale.status}
                  kind={sale.late ? 'installment' : 'sale'}
                />
              </Stack>

              <Progress
                variant="determinate"
                value={Math.min(100, progress)}
                sx={{ height: 10, borderRadius: 5, mb: 2 }}
              />

              <Stack spacing={1.2}>
                <Line label="Client" value={sale.customerName} />
                <Line label="Téléphone" value={sale.customerPhone} />
                <Line label="Produit" value={sale.productName} />
                <Line label="Prix total" value={formatMoney(sale.totalPrice)} />
                <Line
                  label="Intérêt / frais"
                  value={
                    sale.interestRate != null
                      ? `${formatMoney(sale.interestAmount)} (${sale.interestRate}%)`
                      : formatMoney(sale.interestAmount)
                  }
                />
                <Line label="Acompte" value={formatMoney(sale.downPayment)} />
                <Line label="Montant financé" value={formatMoney(sale.financedAmount)} />
                <Line label="Mensualité" value={formatMoney(sale.monthlyAmount)} />
                <Line label="Déjà payé" value={formatMoney(sale.amountPaid)} />
                <Line label="Reste à payer" value={formatMoney(sale.remainingAmount)} />
                <Line label="Pénalités en cours" value={formatMoney(sale.penaltyAmount)} />
                <Line label="Début" value={formatDate(sale.startDate)} />
                <Line label="Fin prévue" value={formatDate(sale.endDate)} />
                <Line
                  label="Échéances"
                  value={`${sale.paidInstallments}/${sale.installmentCount} payées`}
                />
                {sale.late && (
                  <Line
                    label="Retard"
                    value={`${sale.lateInstallments} échéance(s) • ${sale.daysLate} jours`}
                  />
                )}
                {sale.guarantorFullName && (
                  <>
                    <Divider sx={{ my: 1 }} />
                    <Typography variant="subtitle2">Garant</Typography>
                    <Line label="Nom" value={sale.guarantorFullName} />
                    {sale.guarantorPhone && <Line label="Téléphone" value={sale.guarantorPhone} />}
                    {sale.guarantorAddress && <Line label="Adresse" value={sale.guarantorAddress} />}
                    {sale.guarantorCniNumber && <Line label="N° CNI" value={sale.guarantorCniNumber} />}
                  </>
                )}
              </Stack>

              {sale.status === 'ACTIVE' && user?.role === 'ADMIN' && (
                <Button
                  fullWidth
                  color="error"
                  sx={{ mt: 2 }}
                  onClick={() => setCancelOpen(true)}
                >
                  Annuler le contrat
                </Button>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={8}>
          <Card variant="outlined">
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Échéancier
              </Typography>
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>N°</TableCell>
                      <TableCell>Date prévue</TableCell>
                      <TableCell align="right">Montant</TableCell>
                      <TableCell align="right">Payé</TableCell>
                      <TableCell align="right">Reste</TableCell>
                      <TableCell align="right">Pénalité</TableCell>
                      <TableCell>Statut</TableCell>
                      <TableCell align="right">Retard</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {installments.map((installment) => (
                      <TableRow key={installment.id} hover>
                        <TableCell>{installment.number}</TableCell>
                        <TableCell>{formatDate(installment.dueDate)}</TableCell>
                        <TableCell align="right">{formatMoney(installment.amount)}</TableCell>
                        <TableCell align="right">{formatMoney(installment.amountPaid)}</TableCell>
                        <TableCell align="right">{formatMoney(installment.remaining)}</TableCell>
                        <TableCell align="right">{formatMoney(installment.penaltyAmount)}</TableCell>
                        <TableCell>
                          <StatusChip status={installment.displayStatus} kind="installment" />
                        </TableCell>
                        <TableCell align="right">
                          {installment.daysLate > 0 ? `${installment.daysLate} j` : '—'}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Box>
            </CardContent>
          </Card>

          <Card variant="outlined" sx={{ mt: 2.5 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Versements
              </Typography>
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Date</TableCell>
                      <TableCell>Mode</TableCell>
                      <TableCell>Référence</TableCell>
                      <TableCell>Observations</TableCell>
                      <TableCell align="right">Montant</TableCell>
                      <TableCell>Enregistré par</TableCell>
                      <TableCell align="right" />
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {payments.map((payment) => (
                      <TableRow key={payment.id} hover>
                        <TableCell>{formatDate(payment.paymentDate)}</TableCell>
                        <TableCell>{PAYMENT_METHOD_LABELS[payment.method]}</TableCell>
                        <TableCell>{payment.reference ?? '—'}</TableCell>
                        <TableCell>{payment.notes ?? '—'}</TableCell>
                        <TableCell align="right">{formatMoney(payment.amount)}</TableCell>
                        <TableCell>{payment.createdBy ?? '—'}</TableCell>
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
                              <IconButton
                                size="small"
                                color="error"
                                onClick={() => setPaymentToDelete(payment.id)}
                              >
                                <DeleteIcon fontSize="small" />
                              </IconButton>
                            </Tooltip>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                    {!payments.length && (
                      <EmptyRow colSpan={7} message="Aucun versement enregistré" />
                    )}
                  </TableBody>
                </Table>
              </Box>
            </CardContent>
          </Card>

          <Card variant="outlined" sx={{ mt: 2.5 }}>
            <CardContent>
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                justifyContent="space-between"
                alignItems={{ xs: 'flex-start', sm: 'center' }}
                spacing={1.5}
                mb={2}
              >
                <Typography variant="h6">Pièces jointes</Typography>
                <Stack direction="row" spacing={1.5}>
                  <input
                    ref={idDocumentInput}
                    type="file"
                    accept="image/*"
                    capture="environment"
                    hidden
                    onChange={(event) => {
                      const file = event.target.files?.[0];
                      if (file) {
                        uploadAttachmentMutation.mutate({ type: 'ID_DOCUMENT', file });
                      }
                      event.target.value = '';
                    }}
                  />
                  <Button
                    size="small"
                    startIcon={<BadgeIcon />}
                    onClick={() => idDocumentInput.current?.click()}
                    disabled={uploadAttachmentMutation.isPending}
                  >
                    Ajouter une pièce d'identité
                  </Button>
                  <Button
                    size="small"
                    startIcon={<DrawIcon />}
                    onClick={() => setSignatureOpen(true)}
                    disabled={uploadAttachmentMutation.isPending}
                  >
                    Faire signer le client
                  </Button>
                </Stack>
              </Stack>

              <Grid container spacing={1.5}>
                {attachments.map((attachment) => (
                  <Grid item xs={6} sm={4} md={3} key={attachment.id}>
                    <Box
                      sx={{
                        position: 'relative',
                        border: '1px solid',
                        borderColor: 'divider',
                        borderRadius: 1,
                        overflow: 'hidden',
                      }}
                    >
                      <Box
                        component="img"
                        src={attachment.fileUrl}
                        alt={ATTACHMENT_TYPE_LABELS[attachment.type]}
                        sx={{ width: '100%', height: 120, objectFit: 'cover', display: 'block' }}
                      />
                      <Stack
                        direction="row"
                        justifyContent="space-between"
                        alignItems="center"
                        sx={{ px: 1, py: 0.5 }}
                      >
                        <Typography variant="caption" color="text.secondary">
                          {ATTACHMENT_TYPE_LABELS[attachment.type]}
                        </Typography>
                        <IconButton size="small" onClick={() => setAttachmentToDelete(attachment.id)}>
                          <DeleteIcon fontSize="small" />
                        </IconButton>
                      </Stack>
                    </Box>
                  </Grid>
                ))}
                {!attachments.length && (
                  <Grid item xs={12}>
                    <Typography variant="body2" color="text.secondary">
                      Aucune pièce jointe pour ce contrat.
                    </Typography>
                  </Grid>
                )}
              </Grid>
            </CardContent>
          </Card>

          <AuditHistoryCard entityType="CREDIT_SALE" entityId={sale.id} />
        </Grid>
      </Grid>

      <PaymentDialog
        open={paymentOpen}
        sale={sale}
        onClose={() => setPaymentOpen(false)}
        onSuccess={refresh}
      />
      <ReminderDialog
        open={reminderOpen}
        saleId={sale.id}
        customerName={sale.customerName}
        phone={sale.customerPhone}
        onClose={() => setReminderOpen(false)}
      />
      <ConfirmDialog
        open={paymentToDelete !== null}
        title="Annuler le versement"
        message="Le contrat et les échéances seront intégralement recalculés. Continuer ?"
        confirmLabel="Annuler le versement"
        loading={deletePaymentMutation.isPending}
        onConfirm={() => paymentToDelete && deletePaymentMutation.mutate(paymentToDelete)}
        onClose={() => setPaymentToDelete(null)}
      />
      <ConfirmDialog
        open={cancelOpen}
        title="Annuler le contrat"
        message="Le contrat sera marqué comme annulé et n'acceptera plus de versement."
        confirmLabel="Annuler le contrat"
        loading={cancelSaleMutation.isPending}
        onConfirm={() => cancelSaleMutation.mutate()}
        onClose={() => setCancelOpen(false)}
      />
      <SignaturePad
        open={signatureOpen}
        onClose={() => setSignatureOpen(false)}
        onValidate={(file) => uploadAttachmentMutation.mutate({ type: 'SIGNATURE', file })}
      />
      <ConfirmDialog
        open={attachmentToDelete !== null}
        title="Supprimer la pièce jointe"
        message="Cette pièce jointe sera définitivement supprimée."
        confirmLabel="Supprimer"
        loading={deleteAttachmentMutation.isPending}
        onConfirm={() => attachmentToDelete && deleteAttachmentMutation.mutate(attachmentToDelete)}
        onClose={() => setAttachmentToDelete(null)}
      />
    </Box>
  );
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <Stack direction="row" justifyContent="space-between" spacing={2}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 600, textAlign: 'right' }}>
        {value}
      </Typography>
    </Stack>
  );
}
