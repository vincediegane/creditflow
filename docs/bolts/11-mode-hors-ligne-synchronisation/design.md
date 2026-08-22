# Design — #11 Mode hors-ligne avec synchronisation

## Constat préalable (vérifié dans le code)

- **Aucun `@Version` dans le dépôt** (`grep -rn "@Version" backend/src/main/java` -> 0 résultat). La
  dépendance annoncée par le ticket (« bénéficie du verrou optimiste ») **n'est pas acquise**.
- **Aucune notion d'idempotence** : `payments` (V1) n'a que `reference VARCHAR(60)`, champ libre saisi
  par le vendeur, non unique. Rien n'empêche aujourd'hui un double POST d'encaisser deux fois.
- **Frontend sans aucune brique PWA** : `frontend/package.json` ne contient ni `vite-plugin-pwa`, ni
  `workbox-*`, ni `idb` ; `vite.config.ts` n'a que `@vitejs/plugin-react` ; pas de dossier `public/`,
  pas de manifeste, pas de lien manifest dans `index.html`.
- État réel des migrations : V1 à V7, V9 à V11 (V8 est un trou définitif). Flyway est configuré sans
  `out-of-order` -> **la prochaine version libre est V12**, on ne rebouche pas V8.
- Tests : backend = unitaires Mockito (`PaymentServiceTest`) + `@WebMvcTest` de sécurité
  (`AbstractWebMvcSecurityTest`), **aucun `@SpringBootTest`/H2/Testcontainers**. Frontend = **rien**.

## Approche

Première itération volontairement réduite à l'**enregistrement de paiement**, comme le ticket
l'autorise. Trois briques indépendantes :

1. **Idempotence côté serveur d'abord** (préalable non négociable) : un `clientRequestId` (UUID)
   généré par le frontend **à chaque enregistrement, en ligne comme hors-ligne**, porté par
   `PaymentRequest`, stocké en colonne `UNIQUE` sur `payments`. `PaymentService.register` commence par
   un `findByClientRequestId` : si le paiement existe déjà, il renvoie la réponse existante au lieu de
   créer un doublon. Un rejeu (ou un double-tap en ligne) devient inoffensif.
2. **File d'attente locale en IndexedDB**, rejouée **par l'application React** (événement `online` +
   au démarrage + bouton « Synchroniser »), pas par le service worker : le rejeu a besoin du JWT, de
   l'en-tête `X-Shop-Id` d'origine, de l'invalidation React Query et surtout de remonter les conflits
   à l'écran — hors de portée d'un `SyncEvent` Workbox (et la Background Sync API n'existe que sur
   Chromium). Prix assumé : **la synchronisation n'a lieu que si l'application est ouverte**.
3. **PWA installable + lecture hors-ligne** via `vite-plugin-pwa` : précache du shell applicatif, et
   runtime caching `NetworkFirst` sur les GET `/api` de consultation de contrats. Aucun cache sur les
   méthodes non-GET.

**Pas de `@Version` dans ce ticket.** Le conflit visé par le critère d'acceptation n°2 (« montant
restant modifié entre-temps ») est déjà détecté par `PaymentService.register`, qui relit le contrat
dans la transaction et lève un `BusinessRuleException` -> **422 avec message lisible** dans trois cas :
contrat `CANCELLED`, contrat `COMPLETED`, montant supérieur au reste à payer pénalités incluses. Le
rejeu de file étant séquentiel, un verrou optimiste n'y ajoute rien ; il traiterait un autre problème
(course entre deux caisses simultanées), non testable ici faute d'infra d'intégration, et lèverait un
`ObjectOptimisticLockingFailureException` que `GlobalExceptionHandler` transformerait en **500**. À
traiter dans un ticket de concurrence dédié.

## Fichiers/modules impactés

**Backend**
- Créé : `backend/src/main/resources/db/migration/V12__payment_idempotency.sql`
  (`ALTER TABLE payments ADD COLUMN client_request_id VARCHAR(64)` + index unique ; les lignes
  existantes restent à `NULL`, Postgres tolère les `NULL` multiples dans un index unique).
- Modifié : `payment/domain/Payment.java` (champ `clientRequestId`),
  `payment/dto/PaymentRequest.java` (+ `@Size(max = 64) String clientRequestId`, **facultatif** pour
  ne pas casser les appels existants), `payment/dto/PaymentResponse.java` +
  `payment/mapper/PaymentMapper.java` (exposer `clientRequestId` pour le rapprochement côté client),
  `payment/repository/PaymentRepository.java` (`findByClientRequestId`),
  `payment/service/PaymentService.java` (court-circuit idempotent en tête de `register`).
- Modifié : `payment/web/PaymentController.java` — un rejeu idempotent doit renvoyer **200 OK** et non
  `201 Created` (le service expose l'information « déjà enregistré »).

**Frontend**
- Créé : `frontend/src/offline/queue.ts` (accès IndexedDB : `enqueue`, `list`, `markSynced`,
  `markConflict`, `remove` — module **pur et testable**), `frontend/src/offline/sync.ts` (moteur de
  rejeu séquentiel), `frontend/src/context/OfflineQueueContext.tsx` (provider + `useOfflineQueue`,
  état en ligne/hors ligne, compteurs), `frontend/src/components/OfflineBanner.tsx`,
  `frontend/src/components/PendingPaymentsCard.tsx`.
- Créé : `frontend/public/manifest.webmanifest` + icônes 192/512 (le dossier `public/` n'existe pas).
- Modifié : `frontend/vite.config.ts` (`VitePWA`), `frontend/index.html` (lien manifeste),
  `frontend/package.json` (`vite-plugin-pwa`, `idb`, + Vitest cf. plus bas),
  `frontend/src/main.tsx` (`OfflineQueueProvider` sous `ShopProvider`, enregistrement du SW),
  `frontend/src/api/client.ts` (ne plus écraser `X-Shop-Id` s'il est déjà positionné sur la requête ;
  ne pas déclencher la purge 401 pendant un rejeu de file),
  `frontend/src/api/endpoints.ts` (`paymentsApi.create` accepte `clientRequestId`),
  `frontend/src/components/PaymentDialog.tsx` (bascule enqueue si hors-ligne),
  `frontend/src/components/AppLayout.tsx` (bandeau + badge « en attente »),
  `frontend/src/pages/PaymentsPage.tsx` (lignes en attente / en conflit),
  `frontend/src/auth/AuthContext.tsx` (purge du cache HTTP `/api` à la déconnexion),
  `frontend/src/types.ts` (`QueuedPayment`, statut `PENDING | SYNCING | CONFLICT`).
- Modifié : `frontend/nginx/locations.conf` — **piège réel** : la règle
  `location ~* \.(js|css|woff2?|png|jpg|jpeg|svg|webp)$` applique `expires 7d` à tout `.js`, donc à
  `sw.js` et `registerSW.js`. Il faut une règle `no-cache` explicite pour `/sw.js`, `/registerSW.js`
  et `/manifest.webmanifest`, sinon le service worker reste figé une semaine chez chaque vendeur.

## Décisions clés

- **`clientRequestId` séparé plutôt que réutiliser `reference`** : `reference` est un champ métier
  libre (numéro de bordereau), souvent vide ou légitimement dupliqué ; le rendre unique casserait des
  saisies existantes.
- **UUID généré à la validation du formulaire, toujours** (pas seulement hors-ligne) : un seul chemin
  de code, et protection gratuite contre le double-clic en ligne.
- **Colonne nullable + index unique**, pas de rétro-remplissage : les paiements historiques n'ont pas
  d'origine client, `NOT NULL` imposerait un backfill artificiel.
- **IndexedDB (`idb`) plutôt que localStorage** : le `localStorage` est balayé par les mêmes purges
  que le token (déconnexion, « effacer les données du site ») et est évincé plus agressivement sous
  pression de stockage. De l'argent encaissé ne doit pas vivre là. Prix : une dépendance (~1 ko) et du
  code asynchrone.
- **Rejeu séquentiel, un paiement à la fois**, avec backoff : évite d'empiler N requêtes sur un réseau
  qui vient à peine de revenir, et rend l'affectation FIFO des échéances déterministe.
- **Le reçu PDF n'est pas produit hors-ligne** (généré côté serveur par `PaymentReceiptGenerator`). Le
  dialogue désactive la case « Éditer le reçu » hors-ligne et propose le téléchargement après
  synchronisation. À énoncer clairement au vendeur.
- **Lecture hors-ligne = cache HTTP du service worker**, pas de persister React Query : un seul
  mécanisme de cache, pas deux à faire concorder. Conséquence : seuls les écrans déjà consultés depuis
  l'appareil sont disponibles hors connexion. (Le service worker intercepte bien les requêtes XHR
  d'axios via l'événement `fetch`.)
- **Contexte boutique figé à la mise en file** : l'élément stocke le `shopId` actif au moment de la
  saisie et le rejoue tel quel. Sans cela, un vendeur qui change de boutique dans le sélecteur avant
  la reconnexion imputerait ses encaissements à la mauvaise boutique.
- **Infra de test frontend : oui, mais minimale.** Vitest en mode Node uniquement, pour couvrir
  `src/offline/queue.ts` et la machine à états de `sync.ts` (dédoublonnage, marquage conflit,
  non-suppression d'un élément en erreur réseau) — c'est le code où un bug perd ou double de l'argent.
  **Pas** de jsdom, pas de Testing Library, pas de test de composant ni de service worker : ce serait
  un chantier à part entière.

## Risques / points d'attention

- **Double encaissement** : risque n°1. Tout repose sur l'ordre de livraison — la migration V12 et le
  court-circuit idempotent doivent être en place **avant** que le moindre rejeu automatique existe.
- **Expiration du JWT** : `expiration-minutes: 720` (12 h). Une tournée longue peut dépasser la durée
  de vie du token ; au retour du réseau, le rejeu prend un **401** et l'intercepteur de
  `src/api/client.ts` purge le stockage et redirige vers `/login`. La file (IndexedDB) survit, mais
  elle doit être **explicitement épargnée** et le rejeu doit s'interrompre proprement puis reprendre
  après reconnexion — sinon les éléments bouclent en échec.
- **Cache d'API authentifiée sur le disque** : les réponses `/api` mises en cache par le SW restent
  lisibles par l'utilisateur suivant du même profil navigateur. La purge du cache à la déconnexion
  n'est pas un détail cosmétique.
- **`navigator.onLine` ment** (renvoie `true` sur un wifi captif sans Internet). Le pilote de rejeu ne
  doit pas s'y fier seul : une erreur sans `error.response` (cas déjà distingué dans `errorMessage`)
  vaut « toujours hors-ligne », on retente plus tard sans marquer de conflit.
- **Distinguer erreur réseau et refus métier** : 422 = conflit -> on arrête de retenter et on informe ;
  réseau/5xx -> on retente. Confondre les deux fait soit boucler à l'infini, soit perdre un
  encaissement légitime.
- **Le montant proposé par défaut peut être périmé** : `PaymentDialog` pré-remplit avec
  `min(monthlyAmount, remainingAmount)` issu du cache. Hors-ligne cette valeur peut dater ; c'est
  précisément la source du conflit 422 « le montant dépasse le reste à payer ».
- **Suppression d'un paiement par un ADMIN** entre la saisie et le rejeu : le rejeu recréera le
  paiement (le `clientRequestId` a disparu avec la ligne). Cas limite accepté, à documenter.
- **Mise à jour du service worker** : un `autoUpdate` qui recharge la page pendant un flush peut
  interrompre le rejeu. La reprise est idempotente par construction (UUID), mais l'état affiché doit
  se reconstruire depuis IndexedDB, jamais depuis un état React perdu.
- **`vite-plugin-pwa` en dev** : un SW actif pendant `npm run dev` masque les modifications de code.
  Le laisser désactivé hors build, avec une procédure de vérification manuelle documentée.
- **`npm run build` = `tsc --noEmit && vite build`** : les nouveaux types (`QueuedPayment`, options
  `VitePWA`, `vite-plugin-pwa/client`) doivent être déclarés proprement, sinon le build casse.

## Périmètre retenu / hors périmètre

**Dans le périmètre** : PWA installable, lecture hors-ligne des écrans déjà consultés, file d'attente
des **paiements** avec statut visible, synchronisation automatique au retour du réseau, message de
conflit explicite, idempotence serveur.

**Hors périmètre** :
- Création de **contrat, client, produit ou réception de stock** hors-ligne (le ticket la reporte
  explicitement) ; authentification hors-ligne (il faut s'être connecté et avoir un token valide).
- Ajout de `@Version` / verrou optimiste et gestion des courses entre deux caisses simultanées ->
  ticket de concurrence dédié.
- Résolution automatique ou fusion de conflit : on **informe**, on ne décide pas à la place du vendeur.
- Reçu PDF hors-ligne, notifications push, Background Sync, mode hors-ligne des rapports et du tableau
  de bord (agrégats serveur).
- Toute infra de test frontend au-delà de Vitest en mode Node sur les modules purs `src/offline/*` :
  pas de test de composant, pas de test du service worker, pas d'E2E. PWA, installabilité et bascule
  online/offline resteront **vérifiées manuellement** (checklist à fournir par le spec-writer).
