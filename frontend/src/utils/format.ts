const CURRENCY = 'FCFA';

const numberFormatter = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 });

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '0';
  }
  return numberFormatter.format(value);
}

export function formatMoney(value: number | null | undefined): string {
  return `${formatNumber(value)} ${CURRENCY}`;
}

export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value.length <= 10 ? `${value}T00:00:00` : value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return date.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return date.toLocaleString('fr-FR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Date du jour au format attendu par les champs <input type="date"> et l'API. */
export function today(): string {
  return new Date().toISOString().slice(0, 10);
}

export function firstDayOfMonth(): string {
  const date = new Date();
  return new Date(date.getFullYear(), date.getMonth(), 1).toISOString().slice(0, 10);
}

export const PAYMENT_METHOD_LABELS: Record<string, string> = {
  CASH: 'Espèces',
  MOBILE_MONEY: 'Mobile Money',
  BANK_TRANSFER: 'Virement',
  CHECK: 'Chèque',
  CARD: 'Carte',
};

export const SALE_STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'En cours',
  COMPLETED: 'Soldé',
  CANCELLED: 'Annulé',
};

export const INSTALLMENT_STATUS_LABELS: Record<string, string> = {
  PENDING: 'À venir',
  PARTIAL: 'Partiel',
  PAID: 'Payée',
  LATE: 'En retard',
};

export const PRODUCT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'Disponible',
  INACTIVE: 'Retiré',
  OUT_OF_STOCK: 'Rupture',
};

export function initials(fullName: string): string {
  return fullName
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');
}
