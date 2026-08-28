export const MAX_UPLOAD_FILE_SIZE_BYTES = 10 * 1024 * 1024;

export function validateMaxFileSize(file: File, maxBytes = MAX_UPLOAD_FILE_SIZE_BYTES): string | null {
  if (file.size > maxBytes) {
    const maxMb = maxBytes / (1024 * 1024);
    return `Fichier trop volumineux (max ${maxMb} Mo).`;
  }
  return null;
}
