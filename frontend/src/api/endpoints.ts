import { api } from './client';
import type {
  AuthResponse,
  ChangePasswordPayload,
  Customer,
  CustomerPayload,
  ImportReport,
  CustomerProfile,
  Dashboard,
  GlobalSearchResult,
  Installment,
  InstallmentStatus,
  LateCustomer,
  PageResponse,
  Payment,
  PaymentMethod,
  PaymentPayload,
  Product,
  ProductPayload,
  ProductStatus,
  Reminder,
  ReminderSettings,
  ReportData,
  ReportType,
  Sale,
  SaleDetail,
  SalePreview,
  SaleStatus,
  CreateSalePayload,
  User,
} from '../types';

/* ----------------------------- Téléchargements ---------------------------- */

/** Déclenche l'enregistrement d'un fichier reçu de l'API. */
function downloadBlob(data: BlobPart, filename: string): void {
  const url = window.URL.createObjectURL(new Blob([data]));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

/** Extrait le nom de fichier de l'en-tête Content-Disposition. */
function filenameFromHeaders(headers: unknown, fallback: string): string {
  const disposition = String(
    (headers as Record<string, unknown> | undefined)?.['content-disposition'] ?? '',
  );
  return disposition.match(/filename="?([^";]+)"?/)?.[1] ?? fallback;
}

/* ------------------------------- Auth ------------------------------- */

export const authApi = {
  login: (username: string, password: string) =>
    api.post<AuthResponse>('/auth/login', { username, password }).then((r) => r.data),
  me: () => api.get<User>('/auth/me').then((r) => r.data),
  logout: () => api.post('/auth/logout').then(() => undefined),
  changePassword: (payload: ChangePasswordPayload) =>
    api.put<User>('/auth/password', payload).then((r) => r.data),
};

/* --------------------------- Reprise de données --------------------------- */

export const importApi = {
  /** dryRun = true : simulation, aucune écriture en base. */
  legacySales: (file: File, dryRun: boolean) => {
    const form = new FormData();
    form.append('file', file);
    return api
      .post<ImportReport>('/import/legacy-sales', form, {
        params: { dryRun },
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data);
  },
  downloadTemplate: async () => {
    const response = await api.get('/import/template', { responseType: 'blob' });
    downloadBlob(response.data, 'modele-reprise-creditflow.csv');
  },
};

/* ------------------------------ Clients ----------------------------- */

export interface PageParams {
  page?: number;
  size?: number;
  sort?: string;
}

export const customersApi = {
  list: (params: PageParams & { search?: string }) =>
    api.get<PageResponse<Customer>>('/customers', { params }).then((r) => r.data),
  select: () => api.get<Customer[]>('/customers/select').then((r) => r.data),
  get: (id: number) => api.get<Customer>(`/customers/${id}`).then((r) => r.data),
  profile: (id: number) => api.get<CustomerProfile>(`/customers/${id}/profile`).then((r) => r.data),
  create: (payload: CustomerPayload) =>
    api.post<Customer>('/customers', payload).then((r) => r.data),
  update: (id: number, payload: CustomerPayload) =>
    api.put<Customer>(`/customers/${id}`, payload).then((r) => r.data),
  remove: (id: number) => api.delete(`/customers/${id}`).then(() => undefined),
  uploadPhoto: (id: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api
      .post<Customer>(`/customers/${id}/photo`, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data);
  },
};

/* ------------------------------ Produits ---------------------------- */

export const productsApi = {
  list: (params: PageParams & { search?: string; category?: string; status?: ProductStatus }) =>
    api.get<PageResponse<Product>>('/products', { params }).then((r) => r.data),
  select: () => api.get<Product[]>('/products/select').then((r) => r.data),
  categories: () => api.get<string[]>('/products/categories').then((r) => r.data),
  create: (payload: ProductPayload) => api.post<Product>('/products', payload).then((r) => r.data),
  update: (id: number, payload: ProductPayload) =>
    api.put<Product>(`/products/${id}`, payload).then((r) => r.data),
  remove: (id: number) => api.delete(`/products/${id}`).then(() => undefined),
};

/* ------------------------------- Ventes ----------------------------- */

export const salesApi = {
  list: (params: PageParams & { search?: string; status?: SaleStatus; customerId?: number }) =>
    api.get<PageResponse<Sale>>('/sales', { params }).then((r) => r.data),
  get: (id: number) => api.get<Sale>(`/sales/${id}`).then((r) => r.data),
  detail: (id: number) => api.get<SaleDetail>(`/sales/${id}/detail`).then((r) => r.data),
  preview: (payload: {
    totalPrice: number;
    downPayment: number;
    installmentCount: number;
    startDate: string;
  }) => api.post<SalePreview>('/sales/preview', payload).then((r) => r.data),
  create: (payload: CreateSalePayload) => api.post<Sale>('/sales', payload).then((r) => r.data),
  cancel: (id: number) => api.post<Sale>(`/sales/${id}/cancel`).then((r) => r.data),
  remove: (id: number) => api.delete(`/sales/${id}`).then(() => undefined),
};

/* ------------------------------ Echeances --------------------------- */

export const installmentsApi = {
  list: (
    params: PageParams & {
      search?: string;
      status?: InstallmentStatus;
      from?: string;
      to?: string;
      onlyLate?: boolean;
    },
  ) => api.get<PageResponse<Installment>>('/installments', { params }).then((r) => r.data),
  upcoming: (days = 30) =>
    api.get<Installment[]>('/installments/upcoming', { params: { days } }).then((r) => r.data),
  late: () => api.get<Installment[]>('/installments/late').then((r) => r.data),
};

/* ------------------------------ Paiements --------------------------- */

export const paymentsApi = {
  list: (
    params: PageParams & {
      search?: string;
      method?: PaymentMethod;
      from?: string;
      to?: string;
      customerId?: number;
    },
  ) => api.get<PageResponse<Payment>>('/payments', { params }).then((r) => r.data),
  create: (payload: PaymentPayload) => api.post<Payment>('/payments', payload).then((r) => r.data),
  remove: (id: number) => api.delete(`/payments/${id}`).then(() => undefined),
  /** Reçu PDF à remettre au client. */
  downloadReceipt: async (id: number) => {
    const response = await api.get(`/payments/${id}/receipt`, { responseType: 'blob' });
    downloadBlob(response.data, filenameFromHeaders(response.headers, `recu-${id}.pdf`));
  },
};

/* ------------------------------ Tableau de bord --------------------- */

export const dashboardApi = {
  overview: () => api.get<Dashboard>('/dashboard').then((r) => r.data),
};

/* ------------------------------- Relances --------------------------- */

export const remindersApi = {
  generate: (payload: { saleId?: number; customerId?: number; template?: string }) =>
    api.post<Reminder>('/reminders/generate', payload).then((r) => r.data),
  lateCustomers: () => api.get<LateCustomer[]>('/reminders/late-customers').then((r) => r.data),
  settings: () => api.get<ReminderSettings>('/reminders/settings').then((r) => r.data),
};

/* ------------------------------- Rapports --------------------------- */

export const reportsApi = {
  get: (type: ReportType, params: { from?: string; to?: string }) =>
    api.get<ReportData>(`/reports/${type}`, { params }).then((r) => r.data),
  download: async (
    type: ReportType,
    format: 'pdf' | 'excel',
    params: { from?: string; to?: string },
  ) => {
    const response = await api.get(`/reports/${type}/export`, {
      params: { ...params, format },
      responseType: 'blob',
    });

    const fallback = `${type.toLowerCase()}.${format === 'pdf' ? 'pdf' : 'xlsx'}`;
    downloadBlob(response.data, filenameFromHeaders(response.headers, fallback));
  },
};

/* ------------------------------ Recherche --------------------------- */

export const searchApi = {
  global: (q: string) =>
    api.get<GlobalSearchResult>('/search', { params: { q } }).then((r) => r.data),
};
