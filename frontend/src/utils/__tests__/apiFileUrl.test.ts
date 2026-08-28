import { describe, expect, it } from 'vitest';

import { toAuthenticatedFetchPath } from '../apiFileUrl';

describe('toAuthenticatedFetchPath', () => {
  it('retire le prefixe /api pour une photo client', () => {
    expect(toAuthenticatedFetchPath('/api/customers/1/photo')).toBe('/customers/1/photo');
  });

  it('retire le prefixe /api pour une piece jointe de vente', () => {
    expect(toAuthenticatedFetchPath('/api/sales/1/attachments/2/file')).toBe('/sales/1/attachments/2/file');
  });

  it('laisse une entree deja sans prefixe /api inchangee', () => {
    expect(toAuthenticatedFetchPath('/customers/1/photo')).toBe('/customers/1/photo');
  });
});
