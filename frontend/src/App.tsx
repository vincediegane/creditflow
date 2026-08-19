import { Navigate, Route, Routes } from 'react-router-dom';

import ProtectedRoute from './auth/ProtectedRoute';
import RequireRole from './auth/RequireRole';
import AppLayout from './components/AppLayout';
import CustomerDetailPage from './pages/CustomerDetailPage';
import CustomersPage from './pages/CustomersPage';
import DashboardPage from './pages/DashboardPage';
import ImportPage from './pages/ImportPage';
import InstallmentsPage from './pages/InstallmentsPage';
import LateCustomersPage from './pages/LateCustomersPage';
import LoginPage from './pages/LoginPage';
import NewSalePage from './pages/NewSalePage';
import PaymentsPage from './pages/PaymentsPage';
import ProductsPage from './pages/ProductsPage';
import ReportsPage from './pages/ReportsPage';
import SaleDetailPage from './pages/SaleDetailPage';
import SalesPage from './pages/SalesPage';
import SearchPage from './pages/SearchPage';
import UsersPage from './pages/UsersPage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route index element={<DashboardPage />} />
          <Route path="clients" element={<CustomersPage />} />
          <Route path="clients/:id" element={<CustomerDetailPage />} />
          <Route path="produits" element={<ProductsPage />} />
          <Route path="ventes" element={<SalesPage />} />
          <Route path="ventes/nouvelle" element={<NewSalePage />} />
          <Route path="ventes/:id" element={<SaleDetailPage />} />
          <Route path="paiements" element={<PaymentsPage />} />
          <Route path="echeances" element={<InstallmentsPage />} />
          <Route path="relances" element={<LateCustomersPage />} />
          <Route path="rapports" element={<ReportsPage />} />
          <Route path="reprise" element={<ImportPage />} />
          <Route path="recherche" element={<SearchPage />} />
          <Route element={<RequireRole role="ADMIN" />}>
            <Route path="utilisateurs" element={<UsersPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
