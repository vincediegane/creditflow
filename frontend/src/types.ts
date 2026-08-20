/** Types miroirs des DTO exposes par l'API CreditFlow. */

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export type Role = 'ADMIN' | 'SELLER';

export interface User {
  id: number;
  username: string;
  fullName: string;
  role: Role;
  /** Impose l'écran de changement de mot de passe avant toute autre action. */
  mustChangePassword: boolean;
}

export interface UserAccount {
  id: number;
  username: string;
  fullName: string;
  role: Role;
  enabled: boolean;
  mustChangePassword: boolean;
}

export interface CreateUserPayload {
  username: string;
  password: string;
  fullName: string;
  role: Role;
}

export interface ChangePasswordPayload {
  currentPassword: string;
  newPassword: string;
}

export interface ImportRowError {
  line: number;
  column: string;
  value: string;
  reason: string;
}

export interface ImportRowPreview {
  line: number;
  customer: string;
  phone: string;
  customerIsNew: boolean;
  product: string;
  productIsNew: boolean;
  totalPrice: number;
  downPayment: number;
  installmentCount: number;
  startDate: string;
  alreadyPaid: number;
  remaining: number;
}

export interface ImportReport {
  dryRun: boolean;
  applied: boolean;
  totalRows: number;
  validRows: number;
  newCustomers: number;
  existingCustomers: number;
  newProducts: number;
  createdSales: number;
  recordedPayments: number;
  totalFinanced: number;
  totalAlreadyPaid: number;
  totalRemaining: number;
  errors: ImportRowError[];
  preview: ImportRowPreview[];
  message?: string;
}

export interface AuthResponse {
  token: string;
  tokenType: string;
  expiresAt: string;
  user: User;
}

export interface Customer {
  id: number;
  firstName: string;
  lastName: string;
  fullName: string;
  phone: string;
  address?: string;
  cniNumber?: string;
  profession?: string;
  photoUrl?: string;
  notes?: string;
  active: boolean;
  createdAt: string;
  createdBy?: string;
  updatedBy?: string;
}

export interface CustomerPayload {
  firstName: string;
  lastName: string;
  phone: string;
  address?: string;
  cniNumber?: string;
  profession?: string;
  notes?: string;
  active?: boolean;
}

export type ProductStatus = 'ACTIVE' | 'INACTIVE' | 'OUT_OF_STOCK';

export interface Product {
  id: number;
  name: string;
  category: string;
  cashPrice: number;
  creditPrice: number;
  stock: number;
  description?: string;
  status: ProductStatus;
  sellable: boolean;
  createdAt: string;
  createdBy?: string;
  updatedBy?: string;
}

export interface ProductPayload {
  name: string;
  category: string;
  cashPrice: number;
  creditPrice: number;
  stock: number;
  description?: string;
  status: ProductStatus;
}

export type StockMovementType = 'IN' | 'OUT';
export type StockSourceType = 'PURCHASE_RECEPTION' | 'SALE';

export interface StockMovement {
  id: number;
  productId: number;
  type: StockMovementType;
  quantity: number;
  sourceType: StockSourceType;
  sourceId?: number;
  occurredAt: string;
  createdBy?: string;
}

export interface Supplier {
  id: number;
  name: string;
  contactName?: string;
  phone?: string;
  email?: string;
  address?: string;
  notes?: string;
  active: boolean;
  createdAt: string;
  createdBy?: string;
  updatedBy?: string;
}

export interface SupplierPayload {
  name: string;
  contactName?: string;
  phone?: string;
  email?: string;
  address?: string;
  notes?: string;
  active?: boolean;
}

export interface StockReceptionLine {
  id: number;
  productId: number;
  productName: string;
  quantity: number;
}

export interface StockReception {
  id: number;
  supplierId: number;
  supplierName: string;
  receivedAt: string;
  notes?: string;
  lines: StockReceptionLine[];
  createdAt: string;
  createdBy?: string;
}

export interface StockReceptionPayload {
  supplierId: number;
  receivedAt: string;
  notes?: string;
  lines: { productId: number; quantity: number }[];
}

export type SaleStatus = 'ACTIVE' | 'COMPLETED' | 'CANCELLED';

export interface Sale {
  id: number;
  reference: string;
  customerId: number;
  customerName: string;
  customerPhone: string;
  productId: number;
  productName: string;
  totalPrice: number;
  interestRate?: number;
  interestAmount: number;
  downPayment: number;
  financedAmount: number;
  installmentCount: number;
  monthlyAmount: number;
  amountPaid: number;
  remainingAmount: number;
  startDate: string;
  endDate: string;
  status: SaleStatus;
  late: boolean;
  lateInstallments: number;
  daysLate: number;
  nextDueDate?: string;
  nextDueAmount?: number;
  paidInstallments: number;
  notes?: string;
  createdAt: string;
  createdBy?: string;
  updatedBy?: string;
  penaltyAmount: number;
}

export interface CreateSalePayload {
  customerId: number;
  productId: number;
  totalPrice: number;
  downPayment: number;
  interestRate?: number;
  interestFee?: number;
  installmentCount: number;
  startDate: string;
  notes?: string;
}

export interface SalePreview {
  totalPrice: number;
  interestAmount: number;
  financedAmount: number;
  monthlyAmount: number;
  endDate: string;
  lines: { number: number; dueDate: string; amount: number }[];
}

export type InstallmentStatus = 'PENDING' | 'PARTIAL' | 'PAID';
export type InstallmentDisplayStatus = 'PENDING' | 'PARTIAL' | 'PAID' | 'LATE';

export interface Installment {
  id: number;
  saleId: number;
  saleReference: string;
  customerId: number;
  customerName: string;
  customerPhone: string;
  productName: string;
  number: number;
  dueDate: string;
  amount: number;
  amountPaid: number;
  remaining: number;
  status: InstallmentStatus;
  displayStatus: InstallmentDisplayStatus;
  late: boolean;
  daysLate: number;
  paidAt?: string;
  penaltyAmount: number;
}

export type PaymentMethod = 'CASH' | 'MOBILE_MONEY' | 'BANK_TRANSFER' | 'CHECK' | 'CARD';

export interface Payment {
  id: number;
  saleId: number;
  saleReference: string;
  customerId: number;
  customerName: string;
  customerPhone: string;
  productName: string;
  amount: number;
  paymentDate: string;
  method: PaymentMethod;
  reference?: string;
  notes?: string;
  saleRemainingAmount: number;
  createdAt: string;
  createdBy?: string;
}

export interface PaymentPayload {
  saleId: number;
  amount: number;
  paymentDate: string;
  method: PaymentMethod;
  reference?: string;
  notes?: string;
}

export type SaleAttachmentType = 'ID_DOCUMENT' | 'SIGNATURE' | 'OTHER';

export interface SaleAttachment {
  id: number;
  saleId: number;
  type: SaleAttachmentType;
  fileUrl: string;
  originalFilename?: string;
  contentType?: string;
  createdAt: string;
  createdBy?: string;
}

export interface SaleDetail {
  sale: Sale;
  installments: Installment[];
  payments: Payment[];
  attachments: SaleAttachment[];
}

export interface CustomerProfile {
  customer: Customer;
  sales: Sale[];
  payments: Payment[];
  totalPurchased: number;
  totalPaid: number;
  totalRemaining: number;
  lateInstallments: number;
}

export interface LateCustomer {
  customerId: number;
  customerName: string;
  phone: string;
  productNames: string;
  lateInstallments: number;
  daysLate: number;
  oldestDueDate: string;
  lateAmount: number;
  remainingAmount: number;
  primarySaleId: number;
  primarySaleReference: string;
  monthlyAmount: number;
  penaltyAmount: number;
}

export interface DashboardMetrics {
  totalCustomers: number;
  totalSales: number;
  activeSales: number;
  completedSales: number;
  totalRemaining: number;
  collectedThisMonth: number;
  collectedToday: number;
  todayPaymentsCount: number;
  lateCustomersCount: number;
  lateInstallmentsCount: number;
  lateAmount: number;
  upcomingInstallmentsCount: number;
}

export interface Dashboard {
  referenceDate: string;
  metrics: DashboardMetrics;
  todayPayments: Payment[];
  upcomingInstallments: Installment[];
  lateCustomers: LateCustomer[];
}

export interface Reminder {
  customerId: number;
  customerName: string;
  phone: string;
  amount: number;
  message: string;
  channel: string;
  sent: boolean;
}

export interface ReminderSettings {
  channel: string;
  template: string;
  windowStartDay: number;
  windowEndDay: number;
  shopName: string;
  currency: string;
}

export interface BulkReminderResult {
  total: number;
  sent: number;
  failed: number;
  results: Reminder[];
}

export type ReportType = 'DAILY_PAYMENTS' | 'MONTHLY_PAYMENTS' | 'LATE_CUSTOMERS' | 'OUTSTANDING';

export type ReportColumnType = 'TEXT' | 'NUMBER' | 'MONEY' | 'DATE';

export interface ReportData {
  type: ReportType;
  title: string;
  period: string;
  columns: { label: string; type: ReportColumnType }[];
  rows: (string | number)[][];
  totals: { label: string; value: string | number; type: ReportColumnType }[];
  generatedAt: string;
}

export interface GlobalSearchResult {
  query: string;
  customers: Customer[];
  products: Product[];
  sales: Sale[];
  totalResults: number;
}

export type AuditEntityType = 'CUSTOMER' | 'CREDIT_SALE' | 'PRODUCT';

export interface AuditLogEntry {
  id: number;
  entityType: AuditEntityType;
  entityId: number;
  entityLabel: string;
  action: string;
  details?: string;
  actor?: string;
  createdAt: string;
}

export type PenaltyRateType = 'FIXED' | 'PERCENT';
export type PenaltyPeriod = 'DAY' | 'WEEK';

export interface PenaltySettings {
  enabled: boolean;
  rateType: PenaltyRateType;
  rate: number;
  period: PenaltyPeriod;
  capPercent?: number;
  updatedAt?: string;
  updatedBy?: string;
}

export interface PenaltySettingsPayload {
  enabled: boolean;
  rateType: PenaltyRateType;
  rate: number;
  period: PenaltyPeriod;
  capPercent?: number;
}
