# Spec — #11 Mode hors-ligne avec synchronisation

Référence : `docs/bolts/11-mode-hors-ligne-synchronisation/design.md`
Branche : `bolt/issue-11-mode-hors-ligne-synchronisation`

## Résumé

Livrer, en quatre phases séquentielles, l'encaissement hors-ligne : idempotence serveur du POST
`/api/payments` (migration V12 + `clientRequestId`), file d'attente IndexedDB rejouée séquentiellement
par l'application React avec remontée explicite des conflits 422, intégration dans le dialogue de
paiement et la navigation, puis PWA installable avec cache de lecture.

**L'ordre des phases est une contrainte de sûreté, pas de confort** : aucun code de rejeu automatique
ne doit exister avant que la phase 1 soit mergée. Un rejeu sans idempotence = double encaissement.
Chaque phase est livrable et vérifiable seule.

---

## Phase 1 — Idempotence serveur

Aucun changement de comportement pour les clients existants : `clientRequestId` est facultatif, et
lorsqu'il est absent le chemin de code est strictement l'actuel.

### Tâches

- [ ] Créer `backend/src/main/resources/db/migration/V12__payment_idempotency.sql` (SQL exact ci-dessous).
      **V12** : V8 est un trou définitif, Flyway est configuré sans `out-of-order`, on ne rebouche pas.
- [ ] `backend/src/main/java/com/creditflow/payment/domain/Payment.java` : ajouter le champ
      `@Column(name = "client_request_id", length = 64, updatable = false) private String clientRequestId;`
      (après `notes`, avant `createdAt`).
- [ ] `backend/src/main/java/com/creditflow/payment/dto/PaymentRequest.java` : ajouter en **dernière**
      position du record `@Size(max = 64) String clientRequestId` (facultatif, jamais `@NotNull`).
- [ ] `backend/src/main/java/com/creditflow/payment/dto/PaymentResponse.java` : ajouter en dernière
      position `String clientRequestId` (permet au client de rapprocher une réponse d'un élément de file).
      MapStruct le mappe automatiquement par nom, aucun `@Mapping` à écrire dans `PaymentMapper`.
- [ ] `backend/src/main/java/com/creditflow/payment/repository/PaymentRepository.java` : ajouter
      `findByClientRequestId` avec `JOIN FETCH` (signature exacte ci-dessous). Sans les `JOIN FETCH`,
      `PaymentMapper.toResponse` déclenche 4 requêtes lazy supplémentaires par rejeu.
- [ ] `backend/src/main/java/com/creditflow/payment/service/PaymentService.java` :
      - déclarer le record imbriqué `RegistrationResult` (à côté de `Receipt`) ;
      - changer la signature de `register` en `RegistrationResult register(PaymentRequest)` ;
      - insérer le court-circuit idempotent **en toute première instruction** de la méthode ;
      - renseigner `.clientRequestId(clientRequestId)` dans le `Payment.builder()`.
- [ ] `backend/src/main/java/com/creditflow/payment/web/PaymentController.java` : `register` renvoie
      **201 + Location** sur création, **200 sans Location** sur rejeu.
- [ ] `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` (ligne ~214) : ajouter
      `null` comme 7ᵉ argument du `new PaymentRequest(...)`. La valeur de retour est déjà ignorée.
- [ ] `backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java` (ligne ~144) :
      idem, ajouter `null` comme 7ᵉ argument.
- [ ] `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java` : adapter les
      constructeurs `PaymentRequest` existants (helpers `request(...)` ligne 248 et `rejectsFutureDate`
      ligne 191) et ajouter les 6 tests de la phase 1 (cf. plan de tests).
- [ ] `backend/src/test/java/com/creditflow/payment/web/PaymentControllerSecurityTest.java` : adapter
      le stub `when(paymentService.register(any())).thenReturn(...)` au nouveau type de retour, adapter
      le helper `request()`/`response()`, et ajouter le test 200 vs 201.

### Contrat technique — Phase 1

**`V12__payment_idempotency.sql`** (contenu exact) :

```sql
-- =====================================================================
-- V12 - Idempotence de l'enregistrement des versements (#11)
-- Un versement saisi hors-ligne peut etre rejoue plusieurs fois au retour
-- du reseau (reprise apres coupure, rechargement du service worker, double
-- tap). client_request_id est un UUID genere par le client a la validation
-- du formulaire : l'index unique garantit qu'un meme encaissement ne peut
-- pas etre insere deux fois.
--
-- Colonne nullable et sans retro-remplissage : les versements historiques
-- n'ont pas d'origine client. Postgres tolere plusieurs NULL dans un index
-- unique, les lignes existantes ne se genent donc pas entre elles.
-- Ne pas reutiliser "reference" : c'est un numero de bordereau metier,
-- souvent vide et legitimement duplicable.
-- =====================================================================

ALTER TABLE payments ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_client_request_id
    ON payments (client_request_id);
```

**`PaymentRepository`** :

```java
@Query("""
        SELECT p FROM Payment p
        JOIN FETCH p.sale s
        JOIN FETCH s.customer
        JOIN FETCH s.product
        JOIN FETCH s.shop
        WHERE p.clientRequestId = :clientRequestId
        """)
Optional<Payment> findByClientRequestId(@Param("clientRequestId") String clientRequestId);
```

**`PaymentService`** — court-circuit idempotent :

```java
/** Un rejeu (replayed = true) n'a rien cree : le versement etait deja en base. */
public record RegistrationResult(PaymentResponse payment, boolean replayed) {
}

@Transactional
public RegistrationResult register(PaymentRequest request) {
    String clientRequestId = blankToNull(request.clientRequestId());
    if (clientRequestId != null) {
        Optional<Payment> existing = paymentRepository.findByClientRequestId(clientRequestId);
        if (existing.isPresent()) {
            Payment already = existing.get();
            currentShopContext.assertAccessible(already.getSale().getShop().getId());
            log.info("Rejeu idempotent : le versement {} existe deja pour clientRequestId={}",
                    already.getId(), clientRequestId);
            return new RegistrationResult(paymentMapper.toResponse(already), true);
        }
    }

    // ... corps actuel inchange, jusqu'au Payment.builder() qui recoit
    //     .clientRequestId(clientRequestId)
    return new RegistrationResult(paymentMapper.toResponse(saved), false);
}
```

Règles précises du court-circuit :

| Point | Décision | Justification |
|---|---|---|
| Emplacement | **Avant** `saleRepository.findDetailById`, avant tout contrôle métier | Le rejeu d'un versement légitime encaissé sur un contrat entre-temps `CANCELLED` ou `COMPLETED` doit renvoyer le versement d'origine, **pas** un 422. L'argent a été pris : on ne peut pas dire au vendeur « conflit » sur un encaissement déjà en base. |
| `clientRequestId` blanc ou absent | Aucun `findByClientRequestId`, aucune colonne renseignée (`null`) | Non-régression stricte pour `DemoDataSeeder` et `LegacyImportService`. |
| Contrôle d'accès sur rejeu | `currentShopContext.assertAccessible(...)` → `ResourceNotFoundException` (404) | On ne révèle jamais l'existence d'un versement d'une autre boutique. |
| Payload divergent pour le même UUID | On renvoie le versement existant tel quel, sans comparer montant/contrat | La collision d'UUID v4 est négligeable ; ne jamais créer de doublon prime sur tout le reste. |
| Course entre deux POST concurrents | L'index unique lève `DataIntegrityViolationException` → `GlobalExceptionHandler` → **409** | Le rejeu frontend est séquentiel ; ce cas n'arrive qu'avec deux onglets. Le traitement client du 409 est spécifié en phase 2. |

**`PaymentController.register`** — la distinction création / rejeu :

```java
@PostMapping
@Operation(summary = "Enregistrer un versement (idempotent via clientRequestId)")
public ResponseEntity<PaymentResponse> register(@Valid @RequestBody PaymentRequest request,
                                                UriComponentsBuilder uriBuilder) {
    PaymentService.RegistrationResult result = paymentService.register(request);
    if (result.replayed()) {
        // 200 sans Location : rien n'a ete cree, le versement etait deja enregistre.
        return ResponseEntity.ok(result.payment());
    }
    return ResponseEntity
            .created(uriBuilder.path("/api/payments/{id}").build(result.payment().id()))
            .body(result.payment());
}
```

Le contrôleur ne devine rien : c'est le service qui porte l'information. Corps de réponse identique
dans les deux cas (`PaymentResponse` complet), seul le statut et l'en-tête `Location` diffèrent. Le
client n'a d'ailleurs **pas** besoin de distinguer 200 de 201 (cf. machine à états : tout 2xx =
`SYNCED`) ; le code de statut est là pour la sémantique HTTP et l'observabilité.

---

## Phase 2 — File d'attente + moteur de rejeu

Modules purs et testables, **sans aucune intégration UI** : à la fin de la phase 2 rien n'est visible
dans l'application, mais `npm run test` passe.

### Tâches

- [ ] `frontend/package.json` : ajouter en `dependencies` `"idb": "8.0.0"` ; en `devDependencies`
      `"vitest": "2.1.3"` et `"fake-indexeddb": "6.0.0"` ; ajouter le script
      `"test": "vitest run"` (et laisser `build` inchangé).
- [ ] `frontend/vite.config.ts` : remplacer `import { defineConfig } from 'vite'` par
      `import { defineConfig } from 'vitest/config'` et ajouter le bloc `test` (ci-dessous).
      Ne rien changer d'autre à ce stade (la config PWA arrive en phase 4).
- [ ] Créer `frontend/vitest.setup.ts` : une seule ligne, `import 'fake-indexeddb/auto';`.
- [ ] `frontend/tsconfig.json` : ajouter `"vitest.setup.ts"` au tableau `include`.
- [ ] `frontend/src/types.ts` : ajouter `QueuedPaymentStatus`, `QueuedPayment` ; ajouter
      `clientRequestId?: string` à `PaymentPayload` **et** à `Payment`.
- [ ] `frontend/src/api/client.ts` : (a) augmentation du module `axios` avec `skipAuthRedirect` ;
      (b) l'intercepteur de requête ne réécrit plus `X-Shop-Id` s'il est déjà positionné ;
      (c) l'intercepteur de réponse n'exécute la purge + redirection 401 que si
      `skipAuthRedirect !== true`.
- [ ] `frontend/src/api/endpoints.ts` : `paymentsApi.create` accepte un `PaymentPayload` portant
      `clientRequestId` (aucun changement de code nécessaire, le type suffit) ; ajouter
      `paymentsApi.replay(payload, shopId)`.
- [ ] Créer `frontend/src/offline/queue.ts` (accès IndexedDB, aucune dépendance à React ni à axios).
- [ ] Créer `frontend/src/offline/sync.ts` (`classify` pure + `flushQueue` avec dépendances injectées).
- [ ] Créer `frontend/src/context/OfflineQueueContext.tsx` (provider + `useOfflineQueue`).
- [ ] Créer `frontend/src/offline/__tests__/queue.test.ts` (6 tests, cf. plan de tests).
- [ ] Créer `frontend/src/offline/__tests__/sync.test.ts` (2 blocs, cf. plan de tests).

### Contrat technique — Phase 2

**IndexedDB — schéma exact**

| Élément | Valeur |
|---|---|
| Base | `creditflow-offline` |
| Version | `1` |
| Object store | `pendingPayments` |
| `keyPath` | `clientRequestId` (l'UUID est la clé naturelle : une seconde mise en file du même UUID écrase au lieu de dupliquer) |
| `autoIncrement` | `false` |
| Index `by-status` | sur `status`, non unique |
| Index `by-createdAt` | sur `createdAt`, non unique — c'est lui qui donne l'ordre FIFO du rejeu |

**`frontend/src/types.ts`** :

```ts
export type QueuedPaymentStatus = 'PENDING' | 'SYNCING' | 'CONFLICT';

/** Un encaissement saisi hors-ligne, en attente de rejeu. C'est de l'argent : rien ici
 *  ne doit dependre du cache React Query ni du localStorage, qui peuvent disparaitre. */
export interface QueuedPayment {
  /** Cle primaire du store. UUID v4 genere a la validation du formulaire. */
  clientRequestId: string;
  /** Corps exact du POST /api/payments, clientRequestId inclus. */
  payload: PaymentPayload;
  /** X-Shop-Id actif au moment de la saisie (null = pas d'en-tete). Fige, jamais recalcule. */
  shopId: number | null;
  /** Compte ayant saisi le versement : un autre utilisateur ne doit pas rejouer ses encaissements. */
  username: string;
  /** Libelles figes pour l'affichage : le cache peut avoir disparu au moment du rejeu. */
  saleReference: string;
  customerName: string;
  status: QueuedPaymentStatus;
  attempts: number;
  /** ISO 8601. Ordre de rejeu. */
  createdAt: string;
  lastAttemptAt: string | null;
  /** Message lisible : motif du conflit, ou derniere erreur reseau. */
  lastError: string | null;
}
```

**`frontend/src/offline/queue.ts` — signatures exactes**

```ts
export const DB_NAME = 'creditflow-offline';
export const DB_VERSION = 1;
export const STORE = 'pendingPayments';

/** UUID v4. crypto.randomUUID n'existe qu'en contexte securise (https ou localhost) :
 *  en mode HTTP sur une IP de reseau local, on retombe sur un tirage Math.random. */
export function newClientRequestId(): string;

export type NewQueuedPayment = Pick<
  QueuedPayment,
  'clientRequestId' | 'payload' | 'shopId' | 'username' | 'saleReference' | 'customerName'
>;

/** Insere avec status PENDING, attempts 0, createdAt = maintenant. Renvoie l'element stocke. */
export function enqueue(item: NewQueuedPayment): Promise<QueuedPayment>;

/** Tous les elements, tries par createdAt croissant (FIFO). */
export function list(): Promise<QueuedPayment[]>;

export function get(clientRequestId: string): Promise<QueuedPayment | undefined>;

/** PENDING -> SYNCING. Ne fait rien si l'element a disparu. */
export function markSyncing(clientRequestId: string): Promise<void>;

/** -> CONFLICT, lastError = message, lastAttemptAt = maintenant. Etat terminal :
 *  le moteur ne reprend jamais un element CONFLICT. */
export function markConflict(clientRequestId: string, message: string): Promise<void>;

/** -> PENDING, attempts + 1, lastError = message, lastAttemptAt = maintenant. */
export function markRetry(clientRequestId: string, message: string): Promise<void>;

export function remove(clientRequestId: string): Promise<void>;

/** Vide integralement la file. Reserve a un bouton d'administration ; n'est JAMAIS
 *  appele par la deconnexion ni par le traitement d'un 401. */
export function clearAll(): Promise<void>;

export function counts(): Promise<{ pending: number; conflict: number }>;

/** Un rechargement (mise a jour du service worker, fermeture d'onglet) peut laisser un
 *  element bloque en SYNCING : il ne serait plus jamais rejoue. A appeler au montage
 *  du provider. Renvoie le nombre d'elements repasses en PENDING. */
export function resetStaleSyncing(): Promise<number>;

/** Type consomme par sync.ts, pour permettre l'injection d'une file factice en test. */
export interface QueueOps {
  list: typeof list;
  markSyncing: typeof markSyncing;
  markConflict: typeof markConflict;
  markRetry: typeof markRetry;
  remove: typeof remove;
}
export const queueOps: QueueOps;
```

`newClientRequestId` — implémentation imposée (le repo a un mode nginx HTTP pur, où
`crypto.randomUUID` est `undefined`) :

```ts
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
```

**`frontend/src/offline/sync.ts` — signatures exactes**

```ts
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
  skipped: number;          // elements d'un autre compte
  /** true = interrompu par un 401 : la file est intacte, il faut se reconnecter. */
  aborted: boolean;
  conflicts_: ConflictNotice[];
}

export interface SyncDeps {
  /** Envoie un element. Par defaut : paymentsApi.replay(item.payload, item.shopId). */
  send: (item: QueuedPayment) => Promise<unknown>;
  /** Compte connecte : seuls ses elements sont rejoues. */
  username: string;
  queue?: QueueOps;                       // defaut : queueOps
  delay?: (ms: number) => Promise<void>;  // defaut : setTimeout ; injecte pour les tests
  /** Pause entre deux elements. Defaut 300 ms. */
  pauseMs?: number;
  /** Nombre d'echecs reseau consecutifs au-dela duquel on arrete le lot. Defaut 3. */
  maxConsecutiveRetries?: number;
}

/** Fonction PURE : classe une issue d'envoi. error === null signifie succes. */
export function classify(error: unknown): SyncOutcome;

/** Rejeu sequentiel FIFO. Reentrance interdite : un second appel pendant un flush en
 *  cours renvoie immediatement un rapport vide. */
export function flushQueue(deps: SyncDeps): Promise<SyncReport>;

/** Expose pour les tests : remet le verrou de reentrance a zero. */
export function __resetFlushLock(): void;
```

**`classify` — règles exhaustives, dans cet ordre** :

```
error === null                     -> 'SYNCED'
!axios.isAxiosError(error)         -> 'RETRY'   (erreur inattendue : ne jamais supprimer)
!error.response                    -> 'RETRY'   (reseau, wifi captif, DNS, timeout)
status === 401                     -> 'ABORTED_UNAUTHORIZED'
status === 409                     -> 'SYNCED'  (l'index unique a gagne la course : la ligne existe)
status === 408 || status === 429   -> 'RETRY'
status >= 500                      -> 'RETRY'
status >= 400                      -> 'CONFLICT'
sinon                              -> 'SYNCED'
```

**Machine à états d'un élément de file** — c'est le cœur de la sûreté :

```
                      enqueue
                         |
                         v
   +---------------- PENDING <-----------------+
   |                     |                     |
   |            markSyncing (debut envoi)      | markRetry (attempts + 1)
   |                     v                     |
   |                 SYNCING --------(RETRY / ABORTED_UNAUTHORIZED)
   |                   |    \
   |     (SYNCED)      |     \  (CONFLICT)
   |                   v      v
   |               [supprime] CONFLICT ---(action explicite du vendeur)---> [supprime]
   +--- resetStaleSyncing (au montage, apres un rechargement brutal) -------+
```

| Issue de l'envoi | Transition | Retenter ? | Supprimer ? | Informer le vendeur ? | Le lot continue ? |
|---|---|---|---|---|---|
| **2xx** (200 rejeu ou 201 création) | `remove()` | — | **oui** | non (compteur décrémenté, toast récapitulatif en fin de lot) | oui |
| **409** | `remove()` | — | **oui** | non | oui |
| **422** (contrat annulé/soldé, montant > reste dû + pénalités) | `markConflict(errorMessage(err))` | **non**, jamais | **non** | **oui**, ligne rouge persistante + message serveur intégral | oui |
| **400 / 403 / 404** et tout autre 4xx | `markConflict(errorMessage(err))` | **non** | **non** | **oui** | oui |
| **401** | `markRetry('Session expirée…')` → repasse **PENDING** | oui, plus tard | **non** | oui, mais bandeau « session expirée », **pas** un conflit | **non — arrêt immédiat du lot** |
| **408 / 429 / 5xx** | `markRetry(errorMessage(err))` | oui | **non** | non (badge « n en attente ») | oui, sauf si `maxConsecutiveRetries` atteint |
| **Erreur sans `error.response`** (réseau) | `markRetry('Serveur injoignable…')` | oui | **non** | non | non au-delà de 3 consécutifs : le réseau est manifestement absent, on arrête le lot |

Deux invariants non négociables, à respecter dans le code et vérifiés par les tests :

1. **Un élément n'est supprimé que sur 2xx ou 409**, c'est-à-dire uniquement quand le serveur a
   confirmé que la ligne existe en base. Toute autre issue conserve l'élément.
2. **`CONFLICT` est terminal** : `flushQueue` filtre sur `status === 'PENDING'` et ne reprend jamais
   un élément en conflit. Seule une action explicite du vendeur le retire.

**Comportement exact sur expiration du JWT (401)** — l'exigence #4 :

`expiration-minutes: 720` (12 h) : une tournée longue dépasse la durée de vie du token. Au retour du
réseau, le premier envoi prend un 401. Le comportement attendu, dans l'ordre :

1. La requête de rejeu est émise avec `skipAuthRedirect: true`. L'intercepteur de réponse de
   `client.ts` **ne purge pas le localStorage et ne fait pas de `window.location.replace('/login')`**.
   Sans cela, la redirection tuerait la page en plein milieu d'une écriture IndexedDB.
2. `classify` renvoie `ABORTED_UNAUTHORIZED`. `flushQueue` fait `markRetry(item, 'Session expirée,
   reconnectez-vous pour terminer la synchronisation.')` — l'élément revient en **PENDING**, il n'est
   **ni supprimé, ni marqué CONFLICT**.
3. `flushQueue` **sort immédiatement de la boucle** (`break`), avec `report.aborted = true`. Les
   éléments suivants ne sont même pas tentés : ils prendraient tous le même 401 (N échecs, N écritures
   IndexedDB inutiles, N incréments d'`attempts`).
4. Le provider positionne `authExpired = true`. Tant qu'il est vrai : **le minuteur de relance de 60 s
   est désarmé**, et l'écouteur `online` ne déclenche plus de flush. C'est ce qui garantit l'absence de
   boucle d'échec.
5. Le bandeau affiche « Session expirée — N paiement(s) en attente. Reconnectez-vous pour les
   synchroniser. » avec un bouton **Se reconnecter** qui navigue vers `/login`.
6. La file survit intégralement : la déconnexion et le traitement du 401 ne touchent que le
   `localStorage` et le cache HTTP, **jamais** IndexedDB. `clearAll()` n'est appelé depuis aucun de ces
   chemins.
7. Après une reconnexion réussie, `authExpired` repasse à `false` (effet sur `isAuthenticated`) et un
   flush est relancé. Si le nouveau compte n'est pas celui qui a saisi les paiements, ils sont
   `skipped` et le bandeau le dit explicitement (cf. `username`).

**`client.ts` — modifications exactes**

```ts
// Augmentation : permet a un appel de se soustraire a la purge/redirection 401.
declare module 'axios' {
  export interface AxiosRequestConfig {
    /** true = ne pas purger la session ni rediriger sur 401 (rejeu de file hors-ligne). */
    skipAuthRedirect?: boolean;
  }
}

// Intercepteur de requete : ne pas ecraser un X-Shop-Id deja pose explicitement.
if (activeShopId && readAccessibleShops().length > 1 && !config.headers[SHOP_HEADER]) {
  config.headers[SHOP_HEADER] = activeShopId;
}

// Intercepteur de reponse :
const skipRedirect = error.config?.skipAuthRedirect === true;
if (status === 401 && !onLoginPage && !skipRedirect) {
  /* purge localStorage + redirection : inchange */
}
```

**`endpoints.ts` — `paymentsApi.replay`**

```ts
/** Rejeu d'un versement mis en file hors-ligne : boutique figee, pas de redirection sur 401. */
replay: (payload: PaymentPayload, shopId: number | null) =>
  api
    .post<Payment>('/payments', payload, {
      skipAuthRedirect: true,
      headers: shopId === null ? undefined : { [SHOP_HEADER]: String(shopId) },
    })
    .then((r) => r.data),
```

**`OfflineQueueContext.tsx` — API du contexte**

```ts
interface OfflineQueueContextValue {
  /** navigator.onLine, tenu a jour par les evenements online/offline. Ment sur wifi captif :
   *  ne sert qu'a l'affichage et au declenchement, jamais a decider d'un conflit. */
  online: boolean;
  items: QueuedPayment[];
  pendingCount: number;
  conflictCount: number;
  syncing: boolean;
  /** Un 401 a interrompu le dernier rejeu : file intacte, relances desarmees. */
  authExpired: boolean;
  enqueuePayment: (
    payload: PaymentPayload,
    labels: { saleReference: string; customerName: string },
  ) => Promise<QueuedPayment>;
  /** Rejeu manuel (bouton « Synchroniser »). */
  sync: () => Promise<SyncReport>;
  /** Retire un element CONFLICT apres lecture du message. Le versement N'A PAS ete
   *  enregistre : le vendeur devra le ressaisir. Confirmation obligatoire. */
  dismissConflict: (clientRequestId: string) => Promise<void>;
  refresh: () => Promise<void>;
}
```

Déclencheurs de `flushQueue`, exhaustivement :

1. au montage du provider, après `resetStaleSyncing()`, si `navigator.onLine` et `isAuthenticated` ;
2. sur l'évènement `window` `online` ;
3. sur passage de `isAuthenticated` à `true` (retour de connexion) ;
4. bouton « Synchroniser » du bandeau ;
5. minuteur de **60 s**, armé uniquement si `pendingCount > 0 && online && !authExpired && !syncing`
   — c'est le filet pour le wifi captif, où l'évènement `online` ne se déclenche jamais.

Aucun autre déclencheur. Pas de flush sur focus de fenêtre, pas de flush par requête.

**`vite.config.ts` — bloc `test`**

```ts
test: {
  environment: 'node',        // pas de jsdom : les modules testes n'ont pas besoin du DOM
  globals: false,             // describe/it/expect importes explicitement depuis 'vitest'
  setupFiles: ['./vitest.setup.ts'],
  include: ['src/offline/**/*.test.ts'],
},
```

---

## Phase 3 — Intégration UI

### Tâches

- [ ] `frontend/src/main.tsx` : insérer `<OfflineQueueProvider>` **à l'intérieur** de `<ShopProvider>`
      et autour de `<App />` (il a besoin de `useAuth` et de `useShop`).
- [ ] Créer `frontend/src/components/OfflineBanner.tsx` : `<Alert>` MUI plein largeur, rendu
      uniquement si `!online || pendingCount > 0 || conflictCount > 0 || authExpired`.
      - hors-ligne : `severity="warning"`, « Mode hors-ligne — les paiements sont enregistrés sur
        l'appareil et seront synchronisés au retour du réseau. » ;
      - en ligne avec attente : `severity="info"`, « N paiement(s) en attente de synchronisation » +
        bouton **Synchroniser** (désactivé si `syncing`) ;
      - `authExpired` : `severity="warning"` + bouton **Se reconnecter** ;
      - conflits : `severity="error"`, « N paiement(s) en conflit — voir la page Paiements ».
- [ ] Créer `frontend/src/components/PendingPaymentsCard.tsx` : `<Card>` listant `items`, une ligne
      par élément (date, client, contrat, montant, `<StatusChip>` du statut, `lastError`). Pour les
      `CONFLICT`, un bouton **J'ai noté, retirer de la file** ouvrant un `<ConfirmDialog>` dont le
      message dit explicitement « Ce versement n'a **pas** été enregistré. Retirez-le seulement après
      l'avoir ressaisi ou noté. ».
- [ ] `frontend/src/components/AppLayout.tsx` : (a) `<OfflineBanner />` dans le `<Box component="main">`,
      juste après le `<Toolbar />` d'espacement, avant `<Outlet />` ; (b) dans la `<Toolbar>` de
      l'`AppBar`, entre le sélecteur de boutique et le `flexGrow`, une `<IconButton>` avec
      `<Badge badgeContent={pendingCount + conflictCount} color={conflictCount ? 'error' : 'warning'}>`
      et l'icône `CloudOff` (hors-ligne) ou `CloudSync` (en ligne avec attente), masquée si les deux
      compteurs sont à 0 et qu'on est en ligne ; le clic navigue vers `/paiements`.
- [ ] `frontend/src/pages/PaymentsPage.tsx` : rendre `<PendingPaymentsCard />` entre le bloc `error`
      et la `<Card variant="outlined">` de la liste serveur. Ne pas mélanger les lignes en attente
      avec les lignes serveur paginées.
- [ ] `frontend/src/components/PaymentDialog.tsx` : bascule hors-ligne (détail ci-dessous).
      Aucun changement dans les 6 pages appelantes (`DashboardPage`, `InstallmentsPage`,
      `LateCustomersPage`, `PaymentsPage`, `SalesPage`, `SaleDetailPage`) : la signature des props
      est inchangée.

### Contrat technique — Phase 3

**`PaymentDialog` — logique de soumission exacte**

```
submit(values):
  1. si !values.saleId -> erreur locale « Selectionnez un contrat », stop.
  2. clientRequestId = newClientRequestId()
  3. payload = { saleId, amount, paymentDate, method, reference?, notes?, clientRequestId }
  4. si !online:
       enqueuePayment(payload, { saleReference, customerName })
       -> fermer le dialogue, onSuccess?.(), afficher « Versement enregistré sur l'appareil.
          Il sera synchronisé au retour du réseau. » (snackbar de la page appelante ou Alert
          du bandeau). Pas d'invalidateQueries.
  5. sinon: mutation.mutate(payload)
       - onSuccess: comportement actuel inchange (recu + invalidateQueries + onClose)
       - onError:
           si !axios.isAxiosError(err) || !err.response:
             -> le serveur est injoignable alors que navigator.onLine dit « true »
                (wifi captif, backend a terre) : BASCULER EN FILE, meme traitement qu'en 4.
           sinon:
             -> comportement actuel : setError(errorMessage(err, ...)), dialogue ouvert.
```

Le point 5/`!err.response` est ce qui rend le critère n°1 réellement tenable : `navigator.onLine`
renvoie `true` sur un wifi captif, et sans cette bascule le vendeur perdrait l'encaissement.

Autres changements du dialogue :

- Case « Éditer le reçu à remettre au client » : `disabled={!online}`, et lorsque `!online` un
  `<FormHelperText>` « Le reçu est produit par le serveur : il sera téléchargeable depuis la page
  Paiements après synchronisation. ». Forcer `printReceipt` à `false` quand `!online`.
- Un `<Alert severity="info">` en tête du dialogue quand `!online` : « Vous êtes hors connexion. Le
  montant proposé provient du dernier chargement et peut être périmé ; si le contrat a été soldé
  entre-temps, le paiement sera signalé en conflit au retour du réseau. »
- Le libellé du bouton passe de « Enregistrer le paiement » à « Enregistrer hors-ligne » quand
  `!online`.

**`PaymentsPage`** : aucune modification de la requête serveur ni de la pagination. Les éléments en
file ne sont pas comptés dans « Total affiché ».

---

## Phase 4 — PWA

### Tâches

- [ ] `frontend/package.json` : ajouter `"vite-plugin-pwa": "0.20.5"` en `devDependencies`.
- [ ] `frontend/tsconfig.json` : ajouter `"vite-plugin-pwa/client"` au tableau `types` (sans quoi
      `import { registerSW } from 'virtual:pwa-register'` casse `tsc --noEmit`, donc `npm run build`).
- [ ] `frontend/vite.config.ts` : ajouter le plugin `VitePWA(...)` (configuration exacte ci-dessous).
- [ ] Créer `frontend/public/icons/icon.svg` : source vectorielle, fond `#21529c` (le `theme-color`
      déjà présent dans `index.html`), monogramme « CF » blanc, viewBox 512×512.
- [ ] Générer `frontend/public/icons/icon-192.png` et `frontend/public/icons/icon-512.png` depuis ce
      SVG — **tâche binaire, non réalisable par un agent de code** (cf. Écarts, E4). Commande de
      référence : `npx sharp-cli -i public/icons/icon.svg -o public/icons/icon-512.png resize 512 512`
      puis idem en 192. Tant que les PNG manquent, l'application reste fonctionnelle mais **non
      installable**.
- [ ] `frontend/index.html` : ajouter `<link rel="manifest" href="/manifest.webmanifest" />` et
      `<link rel="apple-touch-icon" href="/icons/icon-192.png" />` dans le `<head>`. Le
      `<meta name="theme-color" content="#21529c">` est déjà présent, ne pas le dupliquer.
- [ ] `frontend/src/main.tsx` : enregistrement explicite du service worker via
      `registerSW({ immediate: true })` importé de `virtual:pwa-register`, en dehors du rendu React.
- [ ] Créer `frontend/src/offline/httpCache.ts` : constante `API_CACHE_PREFIX` + `purgeApiCache()`.
- [ ] `frontend/src/auth/AuthContext.tsx` : `logout` appelle `purgeApiCache()` (en `.catch(() => undefined)`,
      la déconnexion ne doit jamais échouer pour cette raison). **Ne pas** toucher à la file IndexedDB.
- [ ] `frontend/nginx/locations.conf` : ajouter les trois blocs `location =` ci-dessous, **avant** la
      règle `location ~* \.(js|css|...)$`.

### Contrat technique — Phase 4

**`vite.config.ts` — bloc `VitePWA`**

```ts
VitePWA({
  registerType: 'autoUpdate',
  injectRegister: null,              // enregistrement explicite dans main.tsx
  devOptions: { enabled: false },    // un SW actif en dev masque les modifications de code
  includeAssets: ['icons/icon-192.png', 'icons/icon-512.png'],
  manifest: {
    name: 'CreditFlow — Gestion des ventes à crédit',
    short_name: 'CreditFlow',
    description: "Encaissement et suivi des ventes à crédit, y compris hors connexion",
    lang: 'fr',
    start_url: '/',
    scope: '/',
    display: 'standalone',
    orientation: 'portrait',
    background_color: '#ffffff',
    theme_color: '#21529c',
    icons: [
      { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  },
  workbox: {
    globPatterns: ['**/*.{js,css,html,svg,png,woff,woff2}'],
    navigateFallback: '/index.html',
    // Le SW ne doit jamais repondre index.html a la place du backend.
    navigateFallbackDenylist: [/^\/api/, /^\/uploads/, /^\/swagger-ui/, /^\/v3\//],
    cleanupOutdatedCaches: true,
    runtimeCaching: [
      {
        // Lecture hors-ligne : seuls les GET /api deja consultes depuis l'appareil.
        // Aucun cache sur POST/PUT/PATCH/DELETE.
        urlPattern: ({ url, request }) =>
          request.method === 'GET' && url.pathname.startsWith('/api/'),
        handler: 'NetworkFirst',
        method: 'GET',
        options: {
          cacheName: 'creditflow-api',
          networkTimeoutSeconds: 5,
          expiration: { maxEntries: 200, maxAgeSeconds: 60 * 60 * 24 },
          cacheableResponse: { statuses: [200] },
        },
      },
    ],
  },
})
```

`networkTimeoutSeconds: 5` est ce qui rend l'application utilisable sur un réseau qui répond mal :
sans lui, `NetworkFirst` attend le timeout d'axios avant de servir le cache.

**`httpCache.ts`**

```ts
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
```

Le filtre est un `startsWith` : Workbox suffixe les noms de cache (`creditflow-api-https://…`).

**`nginx/locations.conf` — blocs exacts à insérer entre `location /` et `location ~* \.(js|...)$`**

```nginx
# --- PWA -------------------------------------------------------------------
# La regle generique ci-dessous applique "expires 7d" a TOUT .js, donc au service
# worker : sans ces exceptions, le SW resterait fige une semaine sur le terminal de
# chaque vendeur, y compris apres un deploiement correctif.
# Les locations "=" (correspondance exacte) sont prioritaires sur les locations
# regex "~*", l'ordre d'ecriture n'a donc pas d'incidence, mais on les groupe ici.
# Rappel nginx : un add_header dans un bloc location remplace ceux herites du
# niveau superieur — les en-tetes de securite sont donc repetes.

location = /sw.js {
    add_header Cache-Control "no-cache, no-store, must-revalidate" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "same-origin" always;
    expires -1;
    try_files $uri =404;
}

location = /registerSW.js {
    add_header Cache-Control "no-cache, no-store, must-revalidate" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "same-origin" always;
    expires -1;
    try_files $uri =404;
}

location = /manifest.webmanifest {
    # mime.types de nginx ne connait pas .webmanifest : sans cela le fichier
    # part en application/octet-stream et Chrome refuse l'installation.
    types { }
    default_type application/manifest+json;
    add_header Cache-Control "no-cache" always;
    add_header X-Content-Type-Options "nosniff" always;
    expires -1;
    try_files $uri =404;
}
# --- fin PWA ---------------------------------------------------------------
```

Les fichiers `workbox-<hash>.js` émis par le plugin sont, eux, versionnés par empreinte : la règle
générique `expires 7d` leur convient et **ne doit pas** être neutralisée.

---

## Plan de tests

### Automatisé — backend (Mockito, pattern `PaymentServiceTest` existant)

Fichier `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java` :

| # | Test | Vérifie |
|---|---|---|
| B1 | `replayReturnsExistingPaymentWithoutCreatingDuplicate` | `findByClientRequestId` renvoie un `Payment` → `paymentRepository.save` et `saleRepository.save` **jamais** appelés (`verify(..., never())`), `sale.getAmountPaid()` inchangé, `result.replayed()` vaut `true` |
| B2 | `registerStoresClientRequestId` | `ArgumentCaptor<Payment>` sur `save` : `clientRequestId` égal à celui de la requête, et `result.replayed()` vaut `false` |
| B3 | `blankClientRequestIdIsStoredAsNull` | `"   "` → colonne `null`, et `findByClientRequestId` **jamais** appelé |
| B4 | `registerWithoutClientRequestIdBehavesAsBefore` | `null` → `findByClientRequestId` jamais appelé, versement créé, échéancier mis à jour (non-régression `DemoDataSeeder` / `LegacyImportService`) |
| B5 | `replayOnCancelledSaleReturnsOriginalPaymentInsteadOfConflict` | `sale.setStatus(CANCELLED)` + rejeu → **aucune** `BusinessRuleException`, le versement d'origine est renvoyé (prouve que le court-circuit est bien avant les contrôles métier) |
| B6 | `replayOfPaymentFromAnotherShopIsRejected` | `doThrow(ResourceNotFoundException).when(currentShopContext).assertAccessible(...)` → 404, pas de fuite |

Fichier `backend/src/test/java/com/creditflow/payment/web/PaymentControllerSecurityTest.java` :

| # | Test | Vérifie |
|---|---|---|
| B7 | `newPaymentReturns201WithLocation` | `replayed = false` → `status().isCreated()` + `header().string("Location", "/api/payments/1")` |
| B8 | `replayedPaymentReturns200WithoutLocation` | `replayed = true` → `status().isOk()` + `header().doesNotExist("Location")` |

Les tests existants `rejectsOverpayment`, `rejectsCancelledSale`, `saleIsCompletedWhenFullyPaid`
doivent continuer à passer sans modification de leurs assertions : ils constituent la preuve que la
détection de conflit (→ 422) reste intacte.

### Automatisé — frontend (Vitest, `environment: 'node'`)

`frontend/src/offline/__tests__/queue.test.ts` (IndexedDB fourni par `fake-indexeddb/auto`) :

| # | Test |
|---|---|
| F1 | `enqueue` crée un élément `PENDING`, `attempts: 0`, `createdAt` renseigné, `lastError: null` |
| F2 | deux `enqueue` avec le même `clientRequestId` laissent **un seul** élément en base |
| F3 | `list()` renvoie les éléments triés par `createdAt` croissant (FIFO) |
| F4 | `markConflict` → statut `CONFLICT` + message ; `markRetry` → statut `PENDING` + `attempts` incrémenté |
| F5 | `remove` supprime ; `counts()` renvoie `{ pending, conflict }` corrects avec un mélange des trois statuts |
| F6 | `resetStaleSyncing` repasse un élément `SYNCING` en `PENDING` et laisse les autres intacts |

`frontend/src/offline/__tests__/sync.test.ts` (`send` factice, `delay` no-op) :

| # | Test |
|---|---|
| F7 | `classify` — table exhaustive : `null` → `SYNCED` ; erreur sans `response` → `RETRY` ; 401 → `ABORTED_UNAUTHORIZED` ; 409 → `SYNCED` ; 422 → `CONFLICT` ; 400/403/404 → `CONFLICT` ; 408/429/500/503 → `RETRY` |
| F8 | succès → élément **supprimé**, `report.synced === 1` |
| F9 | 422 → élément **conservé** en `CONFLICT`, message serveur intégral dans `lastError`, `send` appelé **une seule fois** (aucune reprise), `report.conflicts === 1` |
| F10 | **erreur réseau → élément NON supprimé**, statut `PENDING`, `attempts === 1` (le bug qui perdrait de l'argent) |
| F11 | 401 sur le 1ᵉʳ de 3 éléments → `send` appelé **exactement une fois**, élément 1 en `PENDING` avec `attempts === 1`, éléments 2 et 3 intacts et non tentés, `report.aborted === true` |
| F12 | l'ordre des appels à `send` suit `createdAt` croissant |
| F13 | un élément dont `username` diffère de `deps.username` n'est pas envoyé et compte dans `report.skipped` |
| F14 | un `flushQueue` appelé alors qu'un flush est en cours renvoie immédiatement un rapport vide sans appeler `send` |
| F15 | 3 erreurs réseau consécutives arrêtent le lot (`send` appelé 3 fois sur 5 éléments) |

### Couverture des critères d'acceptation

| Critère du ticket | Automatisé | Manuel |
|---|---|---|
| **1. Paiement hors-ligne visible « en attente » puis synchronisé automatiquement au retour du réseau** | F1, F3, F8, F12 (file + rejeu) ; B2, B4 (persistance serveur du `clientRequestId`) | M1 → M4 |
| **2. Conflit signalé au vendeur, pas d'écrasement silencieux** | `rejectsOverpayment` et `rejectsCancelledSale` (existants, → 422) ; F7, F9 (le 422 devient `CONFLICT` terminal, message conservé, aucune reprise) | M5 |
| **3. Application utilisable en lecture sans connexion** | aucun — dépend du service worker, hors du périmètre de test retenu | M6, M7 |
| *(sûreté transverse : pas de double encaissement)* | B1, B3, B5, B6, B7, B8, F10, F14 | M8 |
| *(sûreté transverse : expiration du JWT)* | F11 | M9 |
| *(sûreté transverse : cache authentifié)* | aucun | M10 |

### Manuel — checklist de vérification pas à pas

Prérequis : `npm run build && npm run preview` (le service worker est **désactivé en `npm run dev`**),
sur `http://localhost:4173` ou derrière nginx **en HTTPS** (cf. Écarts, E3). Navigateur Chrome/Edge,
DevTools ouvert. Se connecter avec un compte vendeur ayant au moins un contrat `ACTIVE`.

**M1 — Saisie hors-ligne**
1. Onglet DevTools → **Network** → passer le sélecteur de débit sur **Offline**.
2. Vérifier que le bandeau orange « Mode hors-ligne… » apparaît sous la barre supérieure.
3. Aller sur **Paiements** → *Enregistrer un paiement*, choisir un contrat, saisir un montant
   inférieur au reste dû.
4. Vérifier que la case « Éditer le reçu » est **grisée** avec son message d'explication, et que le
   bouton indique « Enregistrer hors-ligne ». Valider.
5. **Attendu** : le dialogue se ferme, une ligne apparaît dans la carte « Paiements en attente » avec
   le statut *En attente*, et le badge de la barre supérieure affiche `1`.
6. DevTools → **Application → Storage → IndexedDB → creditflow-offline → pendingPayments** :
   l'enregistrement est présent, `status: "PENDING"`, `attempts: 0`, `clientRequestId` renseigné.

**M2 — Survie au rechargement**
1. Toujours hors-ligne, recharger la page (F5).
2. **Attendu** : l'application se charge (shell précaché), la ligne « En attente » est toujours là et
   le badge affiche toujours `1`. Rien n'a été perdu.

**M3 — Synchronisation automatique au retour du réseau**
1. Repasser le sélecteur Network sur **No throttling**.
2. **Attendu, sans aucune action de l'utilisateur** : sous ~1 s le bandeau passe en « Synchronisation
   en cours », puis la ligne en attente disparaît, le badge revient à 0, et le versement apparaît dans
   le tableau serveur des paiements avec le bon montant et le bon contrat.
3. Onglet **Network** : vérifier qu'il y a **exactement un** `POST /api/payments`, réponse **201**.
4. Vérifier que le reste dû du contrat a bien diminué du montant versé.

**M4 — Synchronisation manuelle**
1. Refaire M1, puis revenir en ligne **avant** que le rejeu automatique parte (repasser en ligne puis
   couper immédiatement, ou utiliser le bouton).
2. Cliquer **Synchroniser** dans le bandeau. **Attendu** : même résultat que M3.

**M5 — Conflit provoqué (critère n°2)**
1. Noter le reste dû d'un contrat `ACTIVE`, par exemple 100 000.
2. Passer en **Offline**, saisir un versement de 100 000 sur ce contrat. Ne pas revenir en ligne.
3. Dans un **second navigateur** (ou une fenêtre privée, autre session), en ligne, enregistrer un
   versement de 100 000 sur le même contrat : il passe au statut *Soldé*.
4. Revenir au premier navigateur et repasser **en ligne**.
5. **Attendu** : le rejeu part, le serveur répond **422**, la ligne passe en rouge avec le statut
   *Conflit* et le message serveur exact (« Ce contrat est deja solde » ou « Le montant depasse le
   reste a payer, penalites incluses (…) »). Le bandeau rouge « 1 paiement en conflit » s'affiche.
6. **Vérifier l'absence d'écrasement** : le versement du second navigateur est intact, le contrat est
   soldé une seule fois, aucun versement en double dans le tableau.
7. Onglet Network : vérifier que le `POST` n'est tenté **qu'une seule fois** (pas de boucle de
   reprise sur le 422). Attendre 2 minutes et revérifier : toujours un seul appel.
8. Cliquer **J'ai noté, retirer de la file**, confirmer. La ligne disparaît, le badge revient à 0.

**M6 — Lecture hors-ligne (critère n°3)**
1. **En ligne**, parcourir : Tableau de bord, Clients, Ventes à crédit, le détail d'un contrat,
   Paiements, Échéances.
2. Passer en **Offline**, puis naviguer à nouveau entre ces mêmes écrans.
3. **Attendu** : les données déjà consultées s'affichent (servies par le cache `creditflow-api`),
   la navigation client fonctionne, aucun écran blanc.
4. Ouvrir un écran **jamais consulté** (par exemple Rapports) : il est normal qu'il affiche une
   erreur ou un état vide — c'est le périmètre annoncé, seuls les écrans déjà chargés sont
   disponibles.
5. DevTools → **Application → Cache Storage** : un cache `creditflow-api-…` contient les GET `/api`.

**M7 — Installabilité**
1. En ligne, DevTools → **Application → Manifest** : nom, `theme_color` `#21529c`, `display:
   standalone`, et les icônes 192/512 chargées sans avertissement.
2. **Application → Service Workers** : `sw.js` est *activated and running*, scope `/`.
3. L'icône d'installation apparaît dans la barre d'adresse. Installer l'application.
4. Lancer l'application installée : elle s'ouvre en fenêtre autonome, sans barre d'adresse, et la
   session est conservée.
5. **Application → Service Workers** → *Update* : le SW se met à jour (preuve que la règle nginx
   `no-cache` sur `/sw.js` fonctionne). Vérifier dans l'onglet Network que la réponse de `sw.js` porte
   bien `Cache-Control: no-cache, no-store, must-revalidate` et **pas** `expires 7d`.

**M8 — Non-duplication (idempotence)**
1. En ligne, ouvrir le dialogue de paiement et **double-cliquer** rapidement sur « Enregistrer le
   paiement ». Attendu : un seul versement en base.
2. Hors-ligne, saisir un versement. Revenir en ligne et, pendant le rejeu, recharger brutalement la
   page (F5). Attendu : après rechargement, soit la ligne a disparu (synchronisée), soit elle est
   revenue en *En attente* (jamais bloquée en *Synchronisation*, grâce à `resetStaleSyncing`). Dans
   les deux cas, **un seul** versement en base pour ce contrat.
3. Vérifier en base : `SELECT client_request_id, count(*) FROM payments WHERE client_request_id IS NOT
   NULL GROUP BY 1 HAVING count(*) > 1;` → **0 ligne**.

**M9 — Expiration du JWT pendant un rejeu**
1. Hors-ligne, saisir un versement.
2. Toujours hors-ligne, DevTools → **Application → Local Storage** : remplacer la valeur de
   `creditflow.token` par une chaîne invalide (simule un token expiré).
3. Repasser **en ligne**.
4. **Attendu** : le `POST` reçoit un **401** ; l'application **n'est pas** redirigée brutalement vers
   `/login` ; le bandeau affiche « Session expirée — 1 paiement en attente… » avec un bouton
   *Se reconnecter* ; la ligne reste en *En attente* (pas en *Conflit*).
5. Vérifier dans l'onglet Network qu'**aucune nouvelle tentative** ne part dans les 3 minutes qui
   suivent (pas de boucle d'échec, minuteur désarmé).
6. Vérifier dans IndexedDB que l'enregistrement est toujours présent.
7. Cliquer *Se reconnecter*, se réauthentifier **avec le même compte**. **Attendu** : le rejeu repart
   automatiquement et le versement est enregistré.
8. Refaire les étapes 1-2 puis se reconnecter avec un **compte différent**. **Attendu** : le versement
   n'est pas rejoué, le bandeau indique qu'il a été saisi par un autre compte.

**M10 — Purge du cache à la déconnexion**
1. En ligne, consulter plusieurs écrans (Clients, Ventes) pour peupler le cache.
2. Se déconnecter via le menu du compte.
3. DevTools → **Application → Cache Storage** : **attendu**, plus aucun cache `creditflow-api-…`
   (les caches de shell applicatif `workbox-precache-…` restent, c'est normal : ils ne contiennent
   aucune donnée client).
4. Vérifier au même endroit que **IndexedDB → creditflow-offline existe toujours** avec ses éventuels
   éléments en attente (s'il en restait) : la déconnexion ne doit jamais détruire un encaissement.

---

## Écarts identifiés

**E1 — La justification du `shopId` figé est fausse pour les paiements ; la mesure reste utile pour
une autre raison.**
Le design écrit que sans `shopId` figé, « un vendeur qui change de boutique dans le sélecteur avant la
reconnexion imputerait ses encaissements à la mauvaise boutique ». Lecture du code :
`PaymentService.register` appelle `currentShopContext.assertAccessible(sale.getShop().getId())`, et
`assertAccessible` s'appuie sur `accessibleShopIds()` — qui **ne lit jamais l'en-tête `X-Shop-Id`**
(seuls `resolveReadFilter()` et `shopIdForCreation()` le lisent, et `register` n'appelle ni l'un ni
l'autre). La boutique d'un versement est déterminée par `sale_id`, pas par l'en-tête : l'imputation
erronée décrite est impossible sur cet endpoint.
**Tranché** : on garde `shopId` dans l'enregistrement de file et l'en-tête dans `paymentsApi.replay`
(coût nul, robustesse si `register` évolue, et information d'affichage), mais la spec ne présente plus
cela comme une protection contre une mauvaise imputation. La modification de `client.ts` (ne pas
écraser un `X-Shop-Id` déjà posé) est conservée à titre de précaution.

**E2 — Le design annonce « Vitest en mode Node » pour tester `queue.ts`, qui dépend d'IndexedDB.
IndexedDB n'existe pas dans Node.**
En l'état, les tests de `queue.ts` seraient impossibles sans jsdom — que le design exclut explicitement.
**Tranché** : ajout d'une devDependency non prévue par le design, `fake-indexeddb` (polyfill pur JS,
sans DOM), importée via `frontend/vitest.setup.ts`. `environment` reste `'node'`, aucune concession
sur jsdom ni Testing Library. C'est la plus petite addition qui rende le périmètre de test du design
réalisable.

**E3 — La PWA est inopérante dans le mode nginx HTTP du dépôt.**
`frontend/nginx/http.conf` sert l'application en HTTP pur lorsqu'aucun certificat n'est présent. Or
un service worker ne s'enregistre qu'en contexte sécurisé (HTTPS, ou `localhost`) : sur une IP de
réseau local en `http://`, **il n'y a ni précache, ni lecture hors-ligne, ni installabilité**.
`crypto.randomUUID` est également indisponible dans ce contexte.
**Tranché** : (a) `newClientRequestId()` embarque un repli `Math.random` explicite pour ne pas casser
l'idempotence en HTTP ; (b) la file IndexedDB, elle, fonctionne en HTTP — la saisie hors-ligne et le
rejeu restent donc opérationnels même sans SW, seule la lecture hors-ligne tombe ; (c) le mode HTTPS
(`https.conf`, déjà présent) devient un **prérequis de déploiement documenté** pour bénéficier du
critère n°3. À écrire dans la note de version du ticket.

**E4 — Les icônes PNG 192/512 ne peuvent pas être produites par un agent de code.**
Le manifeste exige des PNG binaires pour l'installabilité. Un agent ne peut écrire que du texte.
**Tranché** : le codeur livre `public/icons/icon.svg` (source vectorielle textuelle) et le manifeste
complet ; la génération des deux PNG est une **tâche d'ops explicite**, avec la commande fournie en
phase 4. Conséquence assumée : entre le merge et l'exécution de cette commande, l'application est
fonctionnelle et le SW s'installe, mais le bouton « Installer » n'apparaît pas (Chrome exige une icône
≥ 192). Le point M7 de la checklist échouera tant que la commande n'a pas été passée.

**E5 — Le design ne dit rien du changement de compte alors que la file survit à la déconnexion.**
La file est délibérément épargnée par `logout()` (elle contient de l'argent), mais rien n'empêchait
l'utilisateur B, se connectant après A sur le même terminal, de rejouer les encaissements de A sous
son propre jeton — `created_by` serait faux, et la traçabilité de caisse cassée.
**Tranché** : ajout du champ `username` à `QueuedPayment`, filtrage dans `flushQueue`
(`report.skipped`), et message dédié dans le bandeau. Champ non prévu par le design.

**E6 — Le design ne traite pas le 409 que sa propre solution rend possible.**
L'index unique de V12 peut lever `DataIntegrityViolationException` si deux requêtes portant le même
`clientRequestId` franchissent le `findByClientRequestId` simultanément (deux onglets ouverts) ;
`GlobalExceptionHandler` la traduit en **409**, code que le design n'attribue à aucune branche.
**Tranché** : `classify` traite `409` comme un **succès** (`SYNCED`, élément supprimé). Le 409 sur ce
POST ne peut provenir que de l'index d'idempotence, donc la ligne existe en base : conserver l'élément
créerait une alerte de conflit mensongère sur un encaissement pourtant enregistré.

**E7 — Un élément peut rester bloqué en `SYNCING`, ce que le design ne couvre pas.**
Le design signale qu'un rechargement `autoUpdate` peut interrompre un flush et que « l'état affiché
doit se reconstruire depuis IndexedDB », mais un élément persisté en `SYNCING` juste avant le
rechargement n'est repris par personne : `flushQueue` ne traite que les `PENDING`. Le versement serait
silencieusement gelé.
**Tranché** : ajout de `resetStaleSyncing()` dans `queue.ts`, appelé au montage du provider avant tout
flush, avec le test F6 et la vérification manuelle M8.2.

**E8 — `navigator.onLine` ne suffit pas à décider de la mise en file, et le design n'en tire pas la
conséquence côté dialogue.**
Le design applique la règle « une erreur sans `error.response` vaut hors-ligne » au seul pilote de
rejeu. Le cas symétrique — saisie en ligne apparente sur wifi captif — perdrait l'encaissement avec un
simple message d'erreur.
**Tranché** : `PaymentDialog.onError` bascule en file d'attente lorsque l'erreur n'a pas de
`error.response`, au lieu d'afficher une erreur. Sans cela le critère n°1 n'est pas tenu dans le cas
d'usage le plus fréquent en tournée (réseau présent mais inutilisable).

**E9 — Le changement de signature de `register` a des impacts que le design ne liste pas.**
Le design mentionne le contrôleur, mais `PaymentRequest` est un `record` : ajouter un composant casse
la compilation de **quatre** call sites non cités — `DemoDataSeeder:214`, `LegacyImportService:144`,
`PaymentServiceTest` (helpers lignes 191 et 248) et `PaymentControllerSecurityTest:39`. Le changement
de type de retour casse en plus le stub `PaymentControllerSecurityTest:57`.
**Tranché** : ces cinq fichiers sont des tâches explicites de la phase 1. `clientRequestId` est placé
en **dernière** position du record pour limiter le risque d'inversion d'arguments, et les appels
internes (seeder, import) passent `null` — leur comportement est strictement inchangé, garanti par le
test B4.
