import { openDB, type DBSchema, type IDBPDatabase } from 'idb';

import type { QueuedPayment } from '../types';

export const DB_NAME = 'creditflow-offline';
export const DB_VERSION = 1;
export const STORE = 'pendingPayments';

interface OfflineDB extends DBSchema {
  pendingPayments: {
    key: string;
    value: QueuedPayment;
    indexes: { 'by-status': string; 'by-createdAt': string };
  };
}

let dbPromise: Promise<IDBPDatabase<OfflineDB>> | null = null;

function db(): Promise<IDBPDatabase<OfflineDB>> {
  if (!dbPromise) {
    dbPromise = openDB<OfflineDB>(DB_NAME, DB_VERSION, {
      upgrade(database) {
        const store = database.createObjectStore(STORE, {
          keyPath: 'clientRequestId',
          autoIncrement: false,
        });
        store.createIndex('by-status', 'status');
        store.createIndex('by-createdAt', 'createdAt');
      },
    });
  }
  return dbPromise;
}

/** UUID v4. crypto.randomUUID n'existe qu'en contexte securise (https ou localhost) :
 *  en mode HTTP sur une IP de reseau local, on retombe sur un tirage Math.random. */
export function newClientRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // Contexte non securise (http:// sur une IP LAN) : crypto.randomUUID est indisponible.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export type NewQueuedPayment = Pick<
  QueuedPayment,
  'clientRequestId' | 'payload' | 'shopId' | 'username' | 'saleReference' | 'customerName'
>;

/** Insere avec status PENDING, attempts 0, createdAt = maintenant. Renvoie l'element stocke. */
export async function enqueue(item: NewQueuedPayment): Promise<QueuedPayment> {
  const stored: QueuedPayment = {
    ...item,
    status: 'PENDING',
    attempts: 0,
    createdAt: new Date().toISOString(),
    lastAttemptAt: null,
    lastError: null,
  };
  await (await db()).put(STORE, stored);
  return stored;
}

/** Tous les elements, tries par createdAt croissant (FIFO). */
export async function list(): Promise<QueuedPayment[]> {
  return (await db()).getAllFromIndex(STORE, 'by-createdAt');
}

export async function get(clientRequestId: string): Promise<QueuedPayment | undefined> {
  return (await db()).get(STORE, clientRequestId);
}

async function update(
  clientRequestId: string,
  mutate: (item: QueuedPayment) => QueuedPayment,
): Promise<void> {
  const database = await db();
  const tx = database.transaction(STORE, 'readwrite');
  const current = await tx.store.get(clientRequestId);
  if (current) {
    await tx.store.put(mutate(current));
  }
  await tx.done;
}

/** PENDING -> SYNCING. Ne fait rien si l'element a disparu. */
export async function markSyncing(clientRequestId: string): Promise<void> {
  await update(clientRequestId, (item) => ({ ...item, status: 'SYNCING' }));
}

/** -> CONFLICT, lastError = message, lastAttemptAt = maintenant. Etat terminal :
 *  le moteur ne reprend jamais un element CONFLICT. */
export async function markConflict(clientRequestId: string, message: string): Promise<void> {
  await update(clientRequestId, (item) => ({
    ...item,
    status: 'CONFLICT',
    lastError: message,
    lastAttemptAt: new Date().toISOString(),
  }));
}

/** -> PENDING, attempts + 1, lastError = message, lastAttemptAt = maintenant. */
export async function markRetry(clientRequestId: string, message: string): Promise<void> {
  await update(clientRequestId, (item) => ({
    ...item,
    status: 'PENDING',
    attempts: item.attempts + 1,
    lastError: message,
    lastAttemptAt: new Date().toISOString(),
  }));
}

export async function remove(clientRequestId: string): Promise<void> {
  await (await db()).delete(STORE, clientRequestId);
}

/** Vide integralement la file. Reserve a un bouton d'administration ; n'est JAMAIS
 *  appele par la deconnexion ni par le traitement d'un 401. */
export async function clearAll(): Promise<void> {
  await (await db()).clear(STORE);
}

/** SYNCING compte comme « en attente » : sinon le badge tomberait a zero pendant un
 *  rejeu, donnant a croire que l'encaissement a disparu. */
export async function counts(): Promise<{ pending: number; conflict: number }> {
  const items = await list();
  return {
    pending: items.filter((item) => item.status !== 'CONFLICT').length,
    conflict: items.filter((item) => item.status === 'CONFLICT').length,
  };
}

/** Un rechargement (mise a jour du service worker, fermeture d'onglet) peut laisser un
 *  element bloque en SYNCING : il ne serait plus jamais rejoue. A appeler au montage
 *  du provider. Renvoie le nombre d'elements repasses en PENDING. */
export async function resetStaleSyncing(): Promise<number> {
  const database = await db();
  const tx = database.transaction(STORE, 'readwrite');
  const stale = await tx.store.index('by-status').getAll('SYNCING');
  for (const item of stale) {
    await tx.store.put({ ...item, status: 'PENDING' });
  }
  await tx.done;
  return stale.length;
}

/** Type consomme par sync.ts, pour permettre l'injection d'une file factice en test. */
export interface QueueOps {
  list: typeof list;
  markSyncing: typeof markSyncing;
  markConflict: typeof markConflict;
  markRetry: typeof markRetry;
  remove: typeof remove;
}

export const queueOps: QueueOps = { list, markSyncing, markConflict, markRetry, remove };
