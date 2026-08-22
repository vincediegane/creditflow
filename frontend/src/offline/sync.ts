import axios from 'axios';

import { errorMessage } from '../api/client';
import type { QueuedPayment } from '../types';
import { queueOps, type QueueOps } from './queue';

export type SyncOutcome = 'SYNCED' | 'CONFLICT' | 'RETRY' | 'ABORTED_UNAUTHORIZED';

export interface ConflictNotice {
  clientRequestId: string;
  saleReference: string;
  customerName: string;
  message: string;
}

export interface SyncReport {
  synced: number;
  conflicts: number;
  retried: number;
  skipped: number;
  /** true = interrompu par un 401 : la file est intacte, il faut se reconnecter. */
  aborted: boolean;
  conflicts_: ConflictNotice[];
}

export interface SyncDeps {
  /** Envoie un element. Par defaut : paymentsApi.replay(item.payload, item.shopId). */
  send: (item: QueuedPayment) => Promise<unknown>;
  /** Compte connecte : seuls ses elements sont rejoues. */
  username: string;
  queue?: QueueOps;
  delay?: (ms: number) => Promise<void>;
  /** Pause entre deux elements. Defaut 300 ms. */
  pauseMs?: number;
  /** Nombre d'echecs reseau consecutifs au-dela duquel on arrete le lot. Defaut 3. */
  maxConsecutiveRetries?: number;
}

const AUTH_EXPIRED_MESSAGE =
  'Session expirée, reconnectez-vous pour terminer la synchronisation.';

/** Fonction PURE : classe une issue d'envoi. error === null signifie succes. */
export function classify(error: unknown): SyncOutcome {
  if (error === null) {
    return 'SYNCED';
  }
  if (!axios.isAxiosError(error)) {
    return 'RETRY';
  }
  if (!error.response) {
    return 'RETRY';
  }
  const status = error.response.status;
  if (status === 401) {
    return 'ABORTED_UNAUTHORIZED';
  }
  if (status === 409) {
    // L'index unique d'idempotence a gagne la course : la ligne existe deja en base.
    return 'SYNCED';
  }
  if (status === 408 || status === 429) {
    return 'RETRY';
  }
  if (status >= 500) {
    return 'RETRY';
  }
  if (status >= 400) {
    return 'CONFLICT';
  }
  return 'SYNCED';
}

let flushing = false;

/** Expose pour les tests : remet le verrou de reentrance a zero. */
export function __resetFlushLock(): void {
  flushing = false;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

function emptyReport(): SyncReport {
  return { synced: 0, conflicts: 0, retried: 0, skipped: 0, aborted: false, conflicts_: [] };
}

/** Rejeu sequentiel FIFO. Reentrance interdite : un second appel pendant un flush en
 *  cours renvoie immediatement un rapport vide. */
export async function flushQueue(deps: SyncDeps): Promise<SyncReport> {
  const report = emptyReport();
  if (flushing) {
    return report;
  }
  flushing = true;

  const queue = deps.queue ?? queueOps;
  const delay = deps.delay ?? sleep;
  const pauseMs = deps.pauseMs ?? 300;
  const maxConsecutiveRetries = deps.maxConsecutiveRetries ?? 3;

  try {
    const items = (await queue.list()).filter((item) => item.status === 'PENDING');
    let consecutiveRetries = 0;

    for (let index = 0; index < items.length; index += 1) {
      const item = items[index];

      if (item.username !== deps.username) {
        report.skipped += 1;
        continue;
      }

      await queue.markSyncing(item.clientRequestId);

      let error: unknown = null;
      try {
        await deps.send(item);
      } catch (caught) {
        error = caught ?? new Error('Echec inconnu');
      }

      const outcome = classify(error);

      if (outcome === 'SYNCED') {
        await queue.remove(item.clientRequestId);
        report.synced += 1;
        consecutiveRetries = 0;
      } else if (outcome === 'CONFLICT') {
        const message = errorMessage(error, 'Ce versement a été refusé par le serveur.');
        await queue.markConflict(item.clientRequestId, message);
        report.conflicts += 1;
        report.conflicts_.push({
          clientRequestId: item.clientRequestId,
          saleReference: item.saleReference,
          customerName: item.customerName,
          message,
        });
        consecutiveRetries = 0;
      } else if (outcome === 'ABORTED_UNAUTHORIZED') {
        await queue.markRetry(item.clientRequestId, AUTH_EXPIRED_MESSAGE);
        report.retried += 1;
        report.aborted = true;
        break;
      } else {
        await queue.markRetry(item.clientRequestId, errorMessage(error, 'Serveur injoignable.'));
        report.retried += 1;
        consecutiveRetries += 1;
        if (consecutiveRetries >= maxConsecutiveRetries) {
          break;
        }
      }

      if (index < items.length - 1) {
        await delay(pauseMs);
      }
    }
  } finally {
    flushing = false;
  }

  return report;
}
