import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

import { paymentsApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import * as queue from '../offline/queue';
import { flushQueue, type SyncReport } from '../offline/sync';
import type { PaymentPayload, QueuedPayment } from '../types';
import { useShop } from './ShopContext';

interface OfflineQueueContextValue {
  /** navigator.onLine, tenu a jour par les evenements online/offline. Ment sur wifi captif :
   *  ne sert qu'a l'affichage et au declenchement, jamais a decider d'un conflit. */
  online: boolean;
  items: QueuedPayment[];
  pendingCount: number;
  conflictCount: number;
  syncing: boolean;
  /** Un 401 a interrompu le dernier rejeu : file intacte, relances desarmees. */
  authExpired: boolean;
  enqueuePayment: (
    payload: PaymentPayload,
    labels: { saleReference: string; customerName: string },
  ) => Promise<QueuedPayment>;
  /** Rejeu manuel (bouton « Synchroniser »). */
  sync: () => Promise<SyncReport>;
  /** Retire un element CONFLICT apres lecture du message. Le versement N'A PAS ete
   *  enregistre : le vendeur devra le ressaisir. Confirmation obligatoire. */
  dismissConflict: (clientRequestId: string) => Promise<void>;
  refresh: () => Promise<void>;
}

const OfflineQueueContext = createContext<OfflineQueueContextValue | undefined>(undefined);

const RETRY_INTERVAL_MS = 60_000;

const emptyReport: SyncReport = {
  synced: 0,
  conflicts: 0,
  retried: 0,
  skipped: 0,
  aborted: false,
  conflicts_: [],
};

export function OfflineQueueProvider({ children }: { children: ReactNode }) {
  const { user, isAuthenticated } = useAuth();
  const { activeShopId } = useShop();
  const [online, setOnline] = useState(() => navigator.onLine);
  const [items, setItems] = useState<QueuedPayment[]>([]);
  const [syncing, setSyncing] = useState(false);
  const [authExpired, setAuthExpired] = useState(false);

  const username = user?.username ?? '';
  const syncingRef = useRef(false);

  const refresh = useCallback(async () => {
    setItems(await queue.list());
  }, []);

  const sync = useCallback(async (): Promise<SyncReport> => {
    if (syncingRef.current || !username) {
      return emptyReport;
    }
    syncingRef.current = true;
    setSyncing(true);
    try {
      const report = await flushQueue({
        send: (item) => paymentsApi.replay(item.payload, item.shopId),
        username,
      });
      setAuthExpired(report.aborted);
      await refresh();
      return report;
    } finally {
      syncingRef.current = false;
      setSyncing(false);
    }
  }, [username, refresh]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      await queue.resetStaleSyncing();
      if (cancelled) {
        return;
      }
      await refresh();
      if (!cancelled && navigator.onLine && isAuthenticated) {
        void sync();
      }
    })();
    return () => {
      cancelled = true;
    };
    // Montage uniquement : les autres declencheurs ont leurs propres effets.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const goOnline = () => {
      setOnline(true);
      if (!authExpired && isAuthenticated) {
        void sync();
      }
    };
    const goOffline = () => setOnline(false);
    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
    };
  }, [authExpired, isAuthenticated, sync]);

  useEffect(() => {
    if (isAuthenticated) {
      setAuthExpired(false);
      void sync();
    }
  }, [isAuthenticated, sync]);

  const pendingCount = items.filter((item) => item.status !== 'CONFLICT').length;
  const conflictCount = items.filter((item) => item.status === 'CONFLICT').length;

  useEffect(() => {
    if (!(pendingCount > 0 && online && !authExpired && !syncing)) {
      return undefined;
    }
    // Filet pour le wifi captif, ou l'evenement « online » ne se declenche jamais.
    const timer = window.setInterval(() => void sync(), RETRY_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [pendingCount, online, authExpired, syncing, sync]);

  const enqueuePayment = useCallback(
    async (payload: PaymentPayload, labels: { saleReference: string; customerName: string }) => {
      const stored = await queue.enqueue({
        clientRequestId: payload.clientRequestId ?? queue.newClientRequestId(),
        payload,
        shopId: activeShopId,
        username,
        saleReference: labels.saleReference,
        customerName: labels.customerName,
      });
      await refresh();
      return stored;
    },
    [activeShopId, username, refresh],
  );

  const dismissConflict = useCallback(
    async (clientRequestId: string) => {
      await queue.remove(clientRequestId);
      await refresh();
    },
    [refresh],
  );

  const value = useMemo<OfflineQueueContextValue>(
    () => ({
      online,
      items,
      pendingCount,
      conflictCount,
      syncing,
      authExpired,
      enqueuePayment,
      sync,
      dismissConflict,
      refresh,
    }),
    [
      online,
      items,
      pendingCount,
      conflictCount,
      syncing,
      authExpired,
      enqueuePayment,
      sync,
      dismissConflict,
      refresh,
    ],
  );

  return <OfflineQueueContext.Provider value={value}>{children}</OfflineQueueContext.Provider>;
}

export function useOfflineQueue(): OfflineQueueContextValue {
  const context = useContext(OfflineQueueContext);
  if (!context) {
    throw new Error('useOfflineQueue doit etre utilise dans un OfflineQueueProvider');
  }
  return context;
}
