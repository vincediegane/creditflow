import { useEffect, useState } from 'react';

import { api } from '../api/client';
import { toAuthenticatedFetchPath } from '../utils/apiFileUrl';

interface AuthenticatedFile {
  url: string | undefined;
  isLoading: boolean;
}

/**
 * Recupere un document authentifie (photo client, piece jointe de vente) et l'expose
 * comme ObjectURL. Pas de cache persistant : l'URL est re-recuperee a chaque changement
 * d'`apiUrl` et revoquee au demontage, pour ne jamais afficher une URL signee S3 expiree.
 */
export function useAuthenticatedFile(apiUrl?: string | null): AuthenticatedFile {
  const [url, setUrl] = useState<string | undefined>(undefined);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    if (!apiUrl) {
      setUrl(undefined);
      return;
    }

    let objectUrl: string | undefined;
    let cancelled = false;
    setIsLoading(true);

    api
      .get(toAuthenticatedFetchPath(apiUrl), { responseType: 'blob' })
      .then((response) => {
        if (cancelled) {
          return;
        }
        objectUrl = URL.createObjectURL(response.data as Blob);
        setUrl(objectUrl);
      })
      .finally(() => {
        if (!cancelled) {
          setIsLoading(false);
        }
      });

    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [apiUrl]);

  return { url, isLoading };
}
