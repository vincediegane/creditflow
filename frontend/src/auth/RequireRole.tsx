import { Navigate, Outlet } from 'react-router-dom';

import { useAuth } from './AuthContext';
import type { Role } from '../types';

export default function RequireRole({ role }: { role: Role }) {
  const { user } = useAuth();
  if (user?.role !== role) {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}
