import { describe, expect, it } from 'vitest';

import { MAX_UPLOAD_FILE_SIZE_BYTES, validateMaxFileSize } from '../fileValidation';

function fileOfSize(bytes: number): File {
  return new File([new Uint8Array(bytes)], 'photo.jpg', { type: 'image/jpeg' });
}

describe('validateMaxFileSize', () => {
  it('accepte un fichier sous la limite', () => {
    expect(validateMaxFileSize(fileOfSize(MAX_UPLOAD_FILE_SIZE_BYTES - 1))).toBeNull();
  });

  it('accepte un fichier exactement a la limite', () => {
    expect(validateMaxFileSize(fileOfSize(MAX_UPLOAD_FILE_SIZE_BYTES))).toBeNull();
  });

  it('refuse un fichier au-dessus de la limite avec un message explicite', () => {
    const message = validateMaxFileSize(fileOfSize(MAX_UPLOAD_FILE_SIZE_BYTES + 1));

    expect(message).not.toBeNull();
    expect(message).toContain('10 Mo');
  });
});
