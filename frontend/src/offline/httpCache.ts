export const API_CACHE_PREFIX = 'creditflow-api';

/** Les reponses /api mises en cache par le service worker restent lisibles par
 *  l'utilisateur suivant du meme profil navigateur. Purge obligatoire a la deconnexion.
 *  Ne touche PAS a la file IndexedDB : les encaissements en attente doivent survivre. */
export async function purgeApiCache(): Promise<void> {
  if (typeof caches === 'undefined') {
    return;
  }
  const names = await caches.keys();
  await Promise.all(
    names.filter((name) => name.startsWith(API_CACHE_PREFIX)).map((name) => caches.delete(name)),
  );
}
