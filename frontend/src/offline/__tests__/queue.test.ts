import { beforeEach, describe, expect, it } from 'vitest';

import type { PaymentPayload } from '../../types';
import {
  clearAll,
  counts,
  enqueue,
  get,
  list,
  markConflict,
  markRetry,
  markSyncing,
  newClientRequestId,
  remove,
  resetStaleSyncing,
  type NewQueuedPayment,
} from '../queue';

function payload(saleId = 1): PaymentPayload {
  return { saleId, amount: 5000, paymentDate: '2026-08-20', method: 'CASH' };
}

function item(clientRequestId: string, username = 'fatou'): NewQueuedPayment {
  return {
    clientRequestId,
    payload: { ...payload(), clientRequestId },
    shopId: 1,
    username,
    saleReference: 'VC-2026-00001',
    customerName: 'Amadou Diallo',
  };
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 3));

beforeEach(async () => {
  await clearAll();
});

describe('queue', () => {
  it('enqueue cree un element PENDING vierge de toute tentative', async () => {
    const stored = await enqueue(item('a'));

    expect(stored.status).toBe('PENDING');
    expect(stored.attempts).toBe(0);
    expect(stored.createdAt).toBeTruthy();
    expect(stored.lastError).toBeNull();
    expect(stored.lastAttemptAt).toBeNull();
    expect(await get('a')).toMatchObject({ clientRequestId: 'a', status: 'PENDING' });
  });

  it('deux enqueue du meme clientRequestId ne laissent qu un seul element', async () => {
    await enqueue(item('a'));
    await enqueue(item('a'));

    expect(await list()).toHaveLength(1);
  });

  it('list renvoie les elements en ordre FIFO', async () => {
    await enqueue(item('a'));
    await tick();
    await enqueue(item('b'));
    await tick();
    await enqueue(item('c'));

    expect((await list()).map((entry) => entry.clientRequestId)).toEqual(['a', 'b', 'c']);
  });

  it('markConflict et markRetry positionnent le bon statut', async () => {
    await enqueue(item('a'));

    await markConflict('a', 'Ce contrat est deja solde');
    const conflicted = await get('a');
    expect(conflicted?.status).toBe('CONFLICT');
    expect(conflicted?.lastError).toBe('Ce contrat est deja solde');
    expect(conflicted?.lastAttemptAt).not.toBeNull();

    await markRetry('a', 'Serveur injoignable');
    const retried = await get('a');
    expect(retried?.status).toBe('PENDING');
    expect(retried?.attempts).toBe(1);
    expect(retried?.lastError).toBe('Serveur injoignable');
  });

  it('remove supprime et counts distingue attente et conflit', async () => {
    await enqueue(item('a'));
    await enqueue(item('b'));
    await enqueue(item('c'));
    await markSyncing('b');
    await markConflict('c', 'Conflit');

    expect(await counts()).toEqual({ pending: 2, conflict: 1 });

    await remove('a');
    expect(await list()).toHaveLength(2);
    expect(await counts()).toEqual({ pending: 1, conflict: 1 });
  });

  it('resetStaleSyncing debloque les elements restes en SYNCING', async () => {
    await enqueue(item('a'));
    await enqueue(item('b'));
    await enqueue(item('c'));
    await markSyncing('a');
    await markConflict('c', 'Conflit');

    expect(await resetStaleSyncing()).toBe(1);
    expect((await get('a'))?.status).toBe('PENDING');
    expect((await get('a'))?.attempts).toBe(0);
    expect((await get('b'))?.status).toBe('PENDING');
    expect((await get('c'))?.status).toBe('CONFLICT');
  });

  it('newClientRequestId produit un UUID v4 distinct a chaque appel', () => {
    const first = newClientRequestId();
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(first).not.toBe(newClientRequestId());
  });
});
