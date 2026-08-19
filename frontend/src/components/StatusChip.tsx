import { Chip } from '@mui/material';

import {
  INSTALLMENT_STATUS_LABELS,
  PRODUCT_STATUS_LABELS,
  SALE_STATUS_LABELS,
} from '../utils/format';

type Kind = 'sale' | 'installment' | 'product';

const COLORS: Record<string, 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info'> = {
  ACTIVE: 'primary',
  COMPLETED: 'success',
  CANCELLED: 'default',
  PENDING: 'info',
  PARTIAL: 'warning',
  PAID: 'success',
  LATE: 'error',
  INACTIVE: 'default',
  OUT_OF_STOCK: 'warning',
};

const LABELS: Record<Kind, Record<string, string>> = {
  sale: SALE_STATUS_LABELS,
  installment: INSTALLMENT_STATUS_LABELS,
  product: PRODUCT_STATUS_LABELS,
};

interface Props {
  status: string;
  kind: Kind;
  size?: 'small' | 'medium';
}

export default function StatusChip({ status, kind, size = 'small' }: Props) {
  const label = LABELS[kind][status] ?? status;
  const color = kind === 'product' && status === 'ACTIVE' ? 'success' : (COLORS[status] ?? 'default');
  return <Chip label={label} color={color} size={size} variant={color === 'default' ? 'outlined' : 'filled'} />;
}
