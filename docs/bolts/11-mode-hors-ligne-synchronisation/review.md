# Review — #11 Mode hors-ligne avec synchronisation

## Verdict

CHANGES_REQUESTED

Le travail est globalement de tres haute qualite et suit la spec quasi au mot pres sur les quatre
phases (idempotence serveur, machine a etats de la file, integration UI, PWA). Backend et frontend
compilent et tous les tests annonces passent reellement (voir Build/tests). Un defaut reel et
demontrable subsiste neanmoins sur le point le plus sensible du ticket -- la garde anti double-tap de
`PaymentDialog.tsx` cote hors-ligne -- qui rouvre exactement le risque de double encaissement que
toute l'architecture (V12 + `clientRequestId`) vise a fermer. Voir Finding #1.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Paiement hors-ligne visible localement en « en attente », synchronise automatiquement au retour du reseau | Couvert -- `queue.ts`/`sync.ts`/`OfflineQueueContext` implementent exactement la machine a etats specifiee, testes (F1, F3, F8, F12) ; declencheurs (mount, `online`, `isAuthenticated`, minuteur 60s, bouton) tous presents dans `OfflineQueueContext.tsx`. Reserve : voir Finding #1 (double-tap non garde en hors-ligne). |
| 2 | Conflit signale au vendeur, pas d'ecrasement silencieux | Couvert -- le court-circuit idempotent est bien la premiere instruction de `PaymentService.register` (avant `findDetailById` et tout controle metier, confirme par le test B5) ; `classify()` route 422/400/403/404 vers `CONFLICT` (terminal, jamais repris -- F9, F7), le message serveur integral est conserve et affiche (`PendingPaymentsCard.tsx`), retrait uniquement via confirmation explicite du vendeur (`ConfirmDialog`). |
| 3 | Application utilisable en lecture (contrats deja charges) sans connexion | Couvert -- `VitePWA` avec `runtimeCaching` `NetworkFirst` sur les GET `/api/*`, `networkTimeoutSeconds: 5`, `navigateFallbackDenylist` correct pour ne jamais court-circuiter le backend ; build produit bien `dist/sw.js`, `dist/workbox-*.js`, `dist/manifest.webmanifest`, icones PNG 192/512 valides et de la bonne taille (verifie binairement, pas seulement declaratif) ; blocs nginx `location =` bien places avant la regle generique `expires 7d`. Non automatise (assume et documente dans la spec, M6/M7 sont manuels). |

## Findings

### 1 -- [Priorite 4 / risque financier direct] Aucune garde anti double-tap sur le chemin de mise en file de `PaymentDialog.tsx`

**Fichier** : `frontend/src/components/PaymentDialog.tsx`, fonction `submit` lignes 153-179, et bouton
ligne 304-306 :

```tsx
const submit = handleSubmit((values) => {
  ...
  const payload: PaymentPayload = { ..., clientRequestId: newClientRequestId() };
  pendingRef.current = { payload, labels: {...} };
  if (!online) {
    void queuePendingPayment();   // pas de await, pas d'etat "en cours"
    return;
  }
  mutation.mutate(payload);
});
...
<Button variant="contained" size="large" onClick={submit} disabled={mutation.isPending}>
```

`queuePendingPayment` (lignes 117-125) est asynchrone (`await enqueuePayment(...)`, une ecriture
IndexedDB) et ne ferme le dialogue (`onClose()`) qu'apres resolution. Pendant cette fenetre, le bouton
n'est desactive par rien : `mutation.isPending` ne reflete que le chemin `mutation.mutate` (en ligne),
jamais utilise pour la bascule hors-ligne, et `formState.isSubmitting` de react-hook-form ne se declenche
pas non plus puisque le callback fait `void queuePendingPayment(); return;` sans renvoyer la promesse.

**Scenario concret** : vendeur hors-ligne, tape rapidement deux fois sur « Enregistrer hors-ligne »
(double-tap tactile, plausible sur les terminaux d'entree de gamme vises par l'app). `submit()` s'execute
deux fois : chaque appel genere un `clientRequestId` different (`newClientRequestId()` est appele a
l'interieur du callback, pas memorise en dehors), donc `enqueuePayment` cree deux entrees distinctes
dans `pendingPayments` (la deduplication par `keyPath` ne joue pas puisque les cles different). Au retour
du reseau, `flushQueue` rejoue les deux elements avec des `clientRequestId` distincts : l'idempotence
serveur ne peut rien detecter, les deux POST creent deux versements legitimes distincts, ce qui est un
double encaissement reel -- exactement le risque numero 1 que toute l'architecture V12/`clientRequestId`
doit fermer.

Le meme trou existe, en plus etroit, sur la bascule en file depuis l'echec reseau du chemin en ligne
(`onError`, lignes 142-150) : une fois la mutation reglee en erreur, `mutation.isPending` redevient
`false` alors que `queuePendingPayment()` est encore en cours, reactivant le bouton.

Noter que le chemin en ligne reussi n'a pas ce probleme : `mutation.isPending` protege correctement
(comportement inchange depuis avant #11, valide par le scenario manuel M8.1 de la spec). C'est
specifiquement le nouveau code de la phase 3 (mise en file) qui n'a pas de garde equivalente.

**Correction suggeree** : un etat local (`submitting`, ou un `useRef` verrouille de maniere synchrone au
premier caractere de `submit()`) desactivant le bouton pendant toute la duree de `queuePendingPayment()`,
symetrique a `mutation.isPending`. Alternative complementaire : memoriser le `clientRequestId` par
`useRef` en dehors de `submit()` (regenere uniquement a l'ouverture du dialogue, pas a chaque soumission)
de sorte qu'un double-tap reutilise le meme UUID et retombe sur la deduplication du `keyPath` IndexedDB.
La desactivation du bouton reste la protection la plus directe et suffisante seule.

**Portee** : fenetre de course etroite (duree d'une ecriture IndexedDB, generalement quelques
millisecondes), donc peu probable en usage normal mais reelle et non nulle sur un appareil charge ou lent
-- exactement le contexte terrain vise (vendeurs en tournee, terminaux d'entree de gamme). Le plan de
tests manuel de la spec (M8) ne couvre que le double-clic en ligne et le rechargement pendant un rejeu,
pas le double-tap hors-ligne : c'est un angle mort du plan de test autant que du code.

Aucun autre defaut reel n'a ete trouve sur les priorites 1 a 8 (idempotence serveur, invariants de la
machine a etats, comportement 401, reentrance/FIFO, purge a la deconnexion, `resetStaleSyncing`,
changement de compte) : le code correspond ligne a ligne aux signatures et a la table de decision de la
spec, et les tests F1-F15/B1-B8 exercent effectivement les cas limites qu'ils pretendent couvrir (verifie
en lisant les assertions, pas seulement les titres).

### Points mineurs (non bloquants)

- `frontend/nginx/locations.conf` inclut un bloc `location = /registerSW.js`, mais `VitePWA` est
  configure avec `injectRegister: null` et l'enregistrement se fait via l'import direct de
  `virtual:pwa-register` dans `main.tsx` : le plugin n'emet alors pas de fichier `registerSW.js`
  autonome (verifie : absent de `dist/` apres build). Le bloc nginx est donc inerte (il retournera 404
  sans effet de bord), mais merite d'etre note comme vestige sans utilite reelle plutot qu'un vrai bug.

## Build/tests (executes par le reviewer, pas repris du rapport du codeur)

**Backend** -- `cd backend && mvn -o test`
```
Total tests: 294  Failures: 0  Errors: 0  Skipped: 0
```
Confirme par agregation directe de `target/surefire-reports/*.txt` (55 fichiers de rapport). Correspond
au chiffre annonce par le codeur (294 = 286 + 8 nouveaux B1-B8).

**Frontend -- tests** -- `cd frontend && npm run test`
```
Test Files  2 passed (2)
     Tests  16 passed (16)
```
`src/offline/__tests__/queue.test.ts` (7 tests) + `src/offline/__tests__/sync.test.ts` (9 tests).
Assertions relues integralement (pas seulement les titres) : F7 (table `classify` exhaustive), F9 (422
conserve en `CONFLICT`, `send` appele une seule fois), F10 (erreur reseau jamais supprime), F11 (401,
un seul `send`, elements suivants intacts), F14 (reentrance), F15 (arret apres 3 echecs reseau
consecutifs) correspondent tous fidelement aux garanties qu'ils pretendent verifier.

**Frontend -- build** -- `cd frontend && npm run build` (= `tsc --noEmit && vite build`)
```
built in 7.52s
PWA v0.20.5 -- mode generateSW -- precache 9 entries (775.74 KiB)
files generated: dist/sw.js, dist/workbox-efbd304a.js
```
`dist/manifest.webmanifest`, `dist/icons/icon-192.png` (192x192 PNG RGBA valide) et
`dist/icons/icon-512.png` (512x512 PNG RGBA valide) presents et verifies par lecture binaire de l'en-tete
PNG (signature + dimensions IHDR), pas seulement par leur presence sur le disque -- l'affirmation du
codeur sur la generation reelle des PNG via `sharp-cli` est donc confirmee.

## Fichiers cles consultes

- `backend/src/main/java/com/creditflow/payment/service/PaymentService.java`
- `backend/src/main/java/com/creditflow/payment/web/PaymentController.java`
- `backend/src/main/resources/db/migration/V12__payment_idempotency.sql`
- `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java`
- `backend/src/test/java/com/creditflow/payment/web/PaymentControllerSecurityTest.java`
- `frontend/src/offline/queue.ts`, `frontend/src/offline/sync.ts`
- `frontend/src/offline/__tests__/queue.test.ts`, `frontend/src/offline/__tests__/sync.test.ts`
- `frontend/src/context/OfflineQueueContext.tsx`
- `frontend/src/components/PaymentDialog.tsx` (Finding #1)
- `frontend/src/components/OfflineBanner.tsx`, `frontend/src/components/PendingPaymentsCard.tsx`
- `frontend/src/api/client.ts`, `frontend/src/api/endpoints.ts`, `frontend/src/auth/AuthContext.tsx`
- `frontend/nginx/locations.conf`, `frontend/vite.config.ts`
- `frontend/public/icons/icon-192.png`, `frontend/public/icons/icon-512.png`
