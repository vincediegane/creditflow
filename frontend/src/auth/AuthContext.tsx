import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

import { ACCESSIBLE_SHOPS_KEY, ACTIVE_SHOP_KEY, TOKEN_KEY, USER_KEY } from '../api/client';
import { authApi } from '../api/endpoints';
import type { ShopSummary, User } from '../types';

interface AuthContextValue {
  user: User | null;
  isAuthenticated: boolean;
  accessibleShops: ShopSummary[];
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  /** Met à jour le profil en mémoire (après un changement de mot de passe). */
  refreshUser: (user: User) => void;
  /** Met à jour la liste des boutiques accessibles (après un rattachement modifié par un admin). */
  refreshAccessibleShops: (shops: ShopSummary[]) => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function readStoredUser(): User | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as User;
  } catch {
    return null;
  }
}

function readStoredAccessibleShops(): ShopSummary[] {
  const raw = localStorage.getItem(ACCESSIBLE_SHOPS_KEY);
  if (!raw) {
    return [];
  }
  try {
    return JSON.parse(raw) as ShopSummary[];
  } catch {
    return [];
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => readStoredUser());
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_KEY));
  const [accessibleShops, setAccessibleShops] = useState<ShopSummary[]>(() => readStoredAccessibleShops());

  const login = useCallback(async (username: string, password: string) => {
    const response = await authApi.login(username, password);
    localStorage.setItem(TOKEN_KEY, response.token);
    localStorage.setItem(USER_KEY, JSON.stringify(response.user));
    localStorage.setItem(ACCESSIBLE_SHOPS_KEY, JSON.stringify(response.accessibleShops));
    setToken(response.token);
    setUser(response.user);
    setAccessibleShops(response.accessibleShops);
  }, []);

  const logout = useCallback(() => {
    authApi.logout().catch(() => undefined);
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(ACCESSIBLE_SHOPS_KEY);
    localStorage.removeItem(ACTIVE_SHOP_KEY);
    setToken(null);
    setUser(null);
    setAccessibleShops([]);
  }, []);

  const refreshUser = useCallback((updated: User) => {
    localStorage.setItem(USER_KEY, JSON.stringify(updated));
    setUser(updated);
  }, []);

  const refreshAccessibleShops = useCallback((shops: ShopSummary[]) => {
    localStorage.setItem(ACCESSIBLE_SHOPS_KEY, JSON.stringify(shops));
    setAccessibleShops(shops);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: Boolean(token),
      accessibleShops,
      login,
      logout,
      refreshUser,
      refreshAccessibleShops,
    }),
    [user, token, accessibleShops, login, logout, refreshUser, refreshAccessibleShops],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth doit etre utilise dans un AuthProvider');
  }
  return context;
}
