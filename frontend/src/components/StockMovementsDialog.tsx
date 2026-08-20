import { useQuery } from '@tanstack/react-query';
import {
  Box,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
} from '@mui/material';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import CloseIcon from '@mui/icons-material/Close';

import { productsApi } from '../api/endpoints';
import type { Product } from '../types';
import { formatDateTime } from '../utils/format';
import EmptyRow from './EmptyRow';

const SOURCE_LABELS: Record<string, string> = {
  PURCHASE_RECEPTION: 'Réception fournisseur',
  SALE: 'Vente',
};

interface Props {
  product: Product | null;
  onClose: () => void;
}

export default function StockMovementsDialog({ product, onClose }: Props) {
  const query = useQuery({
    queryKey: ['stock-movements', product?.id],
    queryFn: () => productsApi.stockMovements(product!.id),
    enabled: Boolean(product),
  });

  const movements = query.data ?? [];

  return (
    <Dialog open={Boolean(product)} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        Mouvements de stock — {product?.name}
        <IconButton onClick={onClose}>
          <CloseIcon />
        </IconButton>
      </DialogTitle>
      <DialogContent>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>Mouvement</TableCell>
                <TableCell>Origine</TableCell>
                <TableCell align="right">Quantité</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {movements.map((movement) => (
                <TableRow key={movement.id} hover>
                  <TableCell>{formatDateTime(movement.occurredAt)}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      icon={movement.type === 'IN' ? <ArrowUpwardIcon /> : <ArrowDownwardIcon />}
                      label={movement.type === 'IN' ? 'Entrée' : 'Sortie'}
                      color={movement.type === 'IN' ? 'success' : 'warning'}
                      variant="filled"
                    />
                  </TableCell>
                  <TableCell>{SOURCE_LABELS[movement.sourceType] ?? movement.sourceType}</TableCell>
                  <TableCell align="right">{movement.quantity}</TableCell>
                </TableRow>
              ))}
              {!movements.length && !query.isLoading && (
                <EmptyRow colSpan={4} message="Aucun mouvement de stock" />
              )}
            </TableBody>
          </Table>
        </TableContainer>
        <Box sx={{ height: 8 }} />
      </DialogContent>
    </Dialog>
  );
}
