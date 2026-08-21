import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

import { ACTIVE_SHOP_KEY } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { ShopSummary } from '../types';

interface ShopContextValue {
  /** null = vue consolidée (toutes les boutiques accessibles). */
  activeShopId: number | null;
  setActiveShopId: (shopId: number | null) => void;
  accessibleShops: ShopSummary[];
  /** true si l'utilisateur a accès à plus d'une boutique (le sélecteur doit s'afficher). */
  canSelectShop: boolean;
}

const ShopContext = createContext<ShopContextValue | undefined>(undefined);

function readStoredActiveShopId(): number | null {
  const raw = localStorage.getItem(ACTIVE_SHOP_KEY);
  if (!raw) {
    return null;
  }
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

export function ShopProvider({ children }: { children: ReactNode }) {
  const { accessibleShops } = useAuth();
  const [activeShopId, setActiveShopIdState] = useState<number | null>(() => readStoredActiveShopId());

  const canSelectShop = accessibleShops.length > 1;

  const setActiveShopId = useCallback((shopId: number | null) => {
    if (shopId === null) {
      localStorage.removeItem(ACTIVE_SHOP_KEY);
    } else {
      localStorage.setItem(ACTIVE_SHOP_KEY, String(shopId));
    }
    setActiveShopIdState(shopId);
  }, []);

  const value = useMemo<ShopContextValue>(
    () => ({
      activeShopId: canSelectShop ? activeShopId : null,
      setActiveShopId,
      accessibleShops,
      canSelectShop,
    }),
    [activeShopId, setActiveShopId, accessibleShops, canSelectShop],
  );

  return <ShopContext.Provider value={value}>{children}</ShopContext.Provider>;
}

export function useShop(): ShopContextValue {
  const context = useContext(ShopContext);
  if (!context) {
    throw new Error('useShop doit etre utilise dans un ShopProvider');
  }
  return context;
}
