import { useState } from 'react';
import {
  Button,
  Card,
  CardContent,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { useOfflineQueue } from '../context/OfflineQueueContext';
import { PAYMENT_METHOD_LABELS, formatDateTime, formatMoney } from '../utils/format';
import ConfirmDialog from './ConfirmDialog';
import StatusChip from './StatusChip';

export default function PendingPaymentsCard() {
  const { items, dismissConflict } = useOfflineQueue();
  const [toDismiss, setToDismiss] = useState<string | null>(null);
  const [dismissing, setDismissing] = useState(false);

  if (!items.length) {
    return null;
  }

  const handleDismiss = async () => {
    if (!toDismiss) {
      return;
    }
    setDismissing(true);
    try {
      await dismissConflict(toDismiss);
      setToDismiss(null);
    } finally {
      setDismissing(false);
    }
  };

  return (
    <Card variant="outlined" sx={{ mb: 3 }}>
      <CardContent>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>
          Paiements en attente
        </Typography>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>Client</TableCell>
                <TableCell>Contrat</TableCell>
                <TableCell align="right">Montant</TableCell>
                <TableCell>Mode</TableCell>
                <TableCell>Statut</TableCell>
                <TableCell>Détail</TableCell>
                <TableCell align="right" />
              </TableRow>
            </TableHead>
            <TableBody>
              {items.map((item) => (
                <TableRow key={item.clientRequestId} hover>
                  <TableCell>{formatDateTime(item.createdAt)}</TableCell>
                  <TableCell>{item.customerName}</TableCell>
                  <TableCell>{item.saleReference}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600 }}>
                    {formatMoney(item.payload.amount)}
                  </TableCell>
                  <TableCell>{PAYMENT_METHOD_LABELS[item.payload.method]}</TableCell>
                  <TableCell>
                    <StatusChip status={item.status} kind="queuedPayment" />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {item.lastError ?? '—'}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    {item.status === 'CONFLICT' && (
                      <Button
                        size="small"
                        color="error"
                        onClick={() => setToDismiss(item.clientRequestId)}
                      >
                        J'ai noté, retirer de la file
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </CardContent>

      <ConfirmDialog
        open={toDismiss !== null}
        title="Retirer ce versement de la file"
        message="Ce versement n'a pas été enregistré. Retirez-le seulement après l'avoir ressaisi ou noté."
        confirmLabel="Retirer de la file"
        loading={dismissing}
        onConfirm={() => void handleDismiss()}
        onClose={() => setToDismiss(null)}
      />
    </Card>
  );
}
