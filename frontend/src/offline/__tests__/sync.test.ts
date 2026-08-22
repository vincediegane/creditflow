import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { PaymentPayload, QueuedPayment } from '../../types';
import { clearAll, enqueue, get, list, type NewQueuedPayment } from '../queue';
import { __resetFlushLock, classify, flushQueue } from '../sync';

const USER = 'fatou';

function config(): InternalAxiosRequestConfig {
  return { headers: new AxiosHeaders() };
}

function httpError(status: number, message?: string): AxiosError {
  const response = {
    status,
    statusText: '',
    data: message ? { message } : {},
    headers: {},
    config: config(),
  } as AxiosResponse;
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config(), {}, response);
}

function networkError(): AxiosError {
  return new AxiosError('Network Error', 'ERR_NETWORK', config(), {});
}

function payload(saleId: number): PaymentPayload {
  return { saleId, amount: 5000, paymentDate: '2026-08-20', method: 'CASH' };
}

function item(clientRequestId: string, username = USER): NewQueuedPayment {
  return {
    clientRequestId,
    payload: { ...payload(1), clientRequestId },
    shopId: 1,
    username,
    saleReference: 'VC-2026-00001',
    customerName: 'Amadou Diallo',
  };
}

const noDelay = () => Promise.resolve();
const tick = () => new Promise((resolve) => setTimeout(resolve, 3));

beforeEach(async () => {
  __resetFlushLock();
  await clearAll();
});

describe('classify', () => {
  it('classe chaque issue d envoi', () => {
    expect(classify(null)).toBe('SYNCED');
    expect(classify(new Error('boum'))).toBe('RETRY');
    expect(classify(networkError())).toBe('RETRY');
    expect(classify(httpError(401))).toBe('ABORTED_UNAUTHORIZED');
    expect(classify(httpError(409))).toBe('SYNCED');
    expect(classify(httpError(422))).toBe('CONFLICT');
    expect(classify(httpError(400))).toBe('CONFLICT');
    expect(classify(httpError(403))).toBe('CONFLICT');
    expect(classify(httpError(404))).toBe('CONFLICT');
    expect(classify(httpError(408))).toBe('RETRY');
    expect(classify(httpError(429))).toBe('RETRY');
    expect(classify(httpError(500))).toBe('RETRY');
    expect(classify(httpError(503))).toBe('RETRY');
  });
});

describe('flushQueue', () => {
  it('supprime l element sur succes', async () => {
    await enqueue(item('a'));

    const report = await flushQueue({ send: () => Promise.resolve(), username: USER, delay: noDelay });

    expect(report.synced).toBe(1);
    expect(await list()).toHaveLength(0);
  });

  it('conserve l element en CONFLICT sur 422 sans jamais le reprendre', async () => {
    await enqueue(item('a'));
    const send = vi.fn(() => Promise.reject(httpError(422, 'Ce contrat est deja solde')));

    const report = await flushQueue({ send, username: USER, delay: noDelay });

    expect(send).toHaveBeenCalledTimes(1);
    expect(report.conflicts).toBe(1);
    expect(report.conflicts_[0].message).toBe('Ce contrat est deja solde');
    const stored = await get('a');
    expect(stored?.status).toBe('CONFLICT');
    expect(stored?.lastError).toBe('Ce contrat est deja solde');

    __resetFlushLock();
    const second = await flushQueue({ send, username: USER, delay: noDelay });
    expect(send).toHaveBeenCalledTimes(1);
    expect(second.conflicts).toBe(0);
  });

  it('ne supprime jamais un element sur erreur reseau', async () => {
    await enqueue(item('a'));

    const report = await flushQueue({
      send: () => Promise.reject(networkError()),
      username: USER,
      delay: noDelay,
    });

    expect(report.retried).toBe(1);
    const stored = await get('a');
    expect(stored).toBeDefined();
    expect(stored?.status).toBe('PENDING');
    expect(stored?.attempts).toBe(1);
  });

  it('un 401 interrompt le lot et laisse les elements suivants intacts', async () => {
    await enqueue(item('a'));
    await tick();
    await enqueue(item('b'));
    await tick();
    await enqueue(item('c'));
    const send = vi.fn(() => Promise.reject(httpError(401)));

    const report = await flushQueue({ send, username: USER, delay: noDelay });

    expect(send).toHaveBeenCalledTimes(1);
    expect(report.aborted).toBe(true);
    const first = await get('a');
    expect(first?.status).toBe('PENDING');
    expect(first?.attempts).toBe(1);
    for (const key of ['b', 'c']) {
      const untouched = await get(key);
      expect(untouched?.status).toBe('PENDING');
      expect(untouched?.attempts).toBe(0);
      expect(untouched?.lastAttemptAt).toBeNull();
    }
  });

  it('envoie les elements dans l ordre FIFO', async () => {
    await enqueue(item('a'));
    await tick();
    await enqueue(item('b'));
    await tick();
    await enqueue(item('c'));
    const sent: string[] = [];
    const send = (queued: QueuedPayment) => {
      sent.push(queued.clientRequestId);
      return Promise.resolve();
    };

    await flushQueue({ send, username: USER, delay: noDelay });

    expect(sent).toEqual(['a', 'b', 'c']);
  });

  it('ignore les elements saisis par un autre compte', async () => {
    await enqueue(item('a', 'moussa'));
    const send = vi.fn(() => Promise.resolve());

    const report = await flushQueue({ send, username: USER, delay: noDelay });

    expect(send).not.toHaveBeenCalled();
    expect(report.skipped).toBe(1);
    expect((await get('a'))?.status).toBe('PENDING');
  });

  it('refuse la reentrance pendant un flush en cours', async () => {
    await enqueue(item('a'));
    let release: () => void = () => undefined;
    const send = vi.fn(() => new Promise<void>((resolve) => {
      release = resolve;
    }));

    const first = flushQueue({ send, username: USER, delay: noDelay });
    await tick();
    const concurrent = await flushQueue({ send, username: USER, delay: noDelay });

    expect(concurrent).toEqual({
      synced: 0,
      conflicts: 0,
      retried: 0,
      skipped: 0,
      aborted: false,
      conflicts_: [],
    });
    expect(send).toHaveBeenCalledTimes(1);

    release();
    await first;
  });

  it('arrete le lot apres trois echecs reseau consecutifs', async () => {
    for (const key of ['a', 'b', 'c', 'd', 'e']) {
      await enqueue(item(key));
      await tick();
    }
    const send = vi.fn(() => Promise.reject(networkError()));

    const report = await flushQueue({ send, username: USER, delay: noDelay });

    expect(send).toHaveBeenCalledTimes(3);
    expect(report.retried).toBe(3);
    expect(await list()).toHaveLength(5);
  });
});
