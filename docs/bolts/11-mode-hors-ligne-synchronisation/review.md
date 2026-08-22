# Review — #11 Mode hors-ligne avec synchronisation

## Verdict

APPROVE

Seconde et derniere passe, ciblee sur le commit correctif `f3c8bdb` (garde anti double-tap) et sa
non-regression. Le reste du chantier (phases 1-4, idempotence serveur, machine a etats de la file,
integration UI, PWA) a deja ete audite integralement en premiere revue et n'a pas ete retouche par ce
commit (diff `020b90c..f3c8bdb` limite a `frontend/src/components/PaymentDialog.tsx`, 15
insertions / 4 suppressions) : non repris ici.

## Finding initial (premiere revue) — statut : CORRIGE

**Rappel du probleme** : `queuePendingPayment()` (chemin hors-ligne de `submit()`) n'etait protege par
aucun etat "en cours". Un double-tap sur « Enregistrer hors-ligne » declenchait deux `submit()`, donc
deux `clientRequestId` distincts (genere a l'interieur du callback), donc deux entrees IndexedDB non
deduplicables, rejouees comme deux paiements legitimes distincts au retour du reseau — double
encaissement reel, angle mort de l'idempotence serveur puisque les ID different par construction.

**Verification du correctif** (lecture du fichier final post-`f3c8bdb`, pas seulement le diff) :

1. `const [queuing, setQueuing] = useState(false);` ajoute (ligne 55).
2. `queuePendingPayment()` (lignes 118-131) : `setQueuing(true)` est bien la toute premiere instruction
   du corps de la fonction, synchrone, avant tout `await` — donc aucune fenetre ne subsiste entre
   l'appel de la fonction et la pose de l'etat React (contrairement a un `setQueuing` place apres un
   premier `await`, qui aurait laisse la faille ouverte). Le `try { await enqueuePayment(...); onSuccess?.(); onClose(); } finally { setQueuing(false); }`
   couvre bien succes et echec de `enqueuePayment` (toute exception releve le `finally` avant de se
   propager).
3. Bouton « Enregistrer » : `disabled={mutation.isPending || queuing}` (ligne 314) — desormais
   desactive pendant toute la duree de la mise en file, pas seulement pendant la mutation en ligne.
4. **Point d'entree unique confirme independamment** : grep du fichier entier pour `<form`, `onSubmit`,
   `onKeyDown`/`onKeyUp`/`onKeyPress` — aucune occurrence. Le composant n'a pas de balise `<form>` (le
   formulaire react-hook-form est monte directement dans le `<Dialog>`/`<DialogContent>` de MUI), et
   `onClick={submit}` sur le bouton (ligne 313) est le seul declencheur de `submit()`. Pas de
   contournement par la touche Entree ni par un `onSubmit` cache.
5. **Bonus (fenetre sur le chemin `onError`) verifie reel, pas seulement affirme** : `mutation.onError`
   (lignes 148-156) appelle `void queuePendingPayment()` quand l'erreur reseau n'a pas de
   `err.response` (backend injoignable alors que `navigator.onLine` est vrai). C'est la meme fonction,
   donc la meme garde `queuing` s'applique : au moment ou `onError` se declenche, `mutation.isPending`
   est deja retombe a `false` (la mutation est reglee), mais `queuing` passe a `true` des l'entree dans
   `queuePendingPayment()` et maintient le bouton desactive le temps de l'ecriture IndexedDB — la
   fenetre plus etroite decrite en premiere revue est bien fermee par le meme correctif.
6. **Non-regression du chemin en ligne** : `mutation.isPending` n'est pas modifie dans son
   fonctionnement ; `queuing` reste `false` sur tout le chemin en ligne reussi (jamais mis a `true` sauf
   entree dans `queuePendingPayment`). Pas de risque de bouton bloque durablement : `queuing` repasse a
   `false` dans le `finally`, y compris quand `onClose()` a deja ete appele juste avant dans le `try`
   (le `PaymentDialog` reste monte entre deux ouvertures — verifie dans `PaymentsPage.tsx`,
   `<PaymentDialog open={dialogOpen} onClose={() => setDialogOpen(false)} />` — l'etat `open` bascule
   sans demonter le composant, donc pas de `setState` post-demontage).

**Conclusion** : le trou est reellement ferme, sur les deux chemins qui partagent
`queuePendingPayment()`, sans regression identifiee sur le chemin en ligne existant.

## Etendue reelle du diff correctif

`git diff 020b90c..f3c8bdb --stat` :
```
 frontend/src/components/PaymentDialog.tsx | 19 +++++++++++++++----
 1 file changed, 15 insertions(+), 4 deletions(-)
```
Strictement localise au fichier attendu. `queue.ts`, `sync.ts`, le backend, les migrations Flyway :
aucun n'apparait — conforme a l'engagement du codeur.

## Test automatise manquant — limite acceptee, verifiee

Le codeur affirme ne pas avoir pu ajouter de test automatise faute d'infrastructure de test de
composants React dans ce depot. Verifie directement :
- `frontend/package.json` : ni `@testing-library/react`, ni `jsdom` en dependance.
- `frontend/vite.config.ts`, bloc `test` : `environment: 'node'` et surtout
  `include: ['src/offline/**/*.test.ts']` — le runner Vitest n'inclut meme pas les fichiers hors de
  `src/offline/`, donc un test de `PaymentDialog.tsx` ne serait pas execute sans modifier la
  configuration globale du projet.
- Seuls tests presents : `src/offline/__tests__/queue.test.ts` (7) et `sync.test.ts` (9), aucun test de
  composant nulle part dans le repo.

L'affirmation est donc exacte, pas une esquive. Etendre l'infrastructure de test (jsdom +
Testing Library + elargissement de l'`include`) pour permettre un test de regression sur ce genre de
garde UI serait une amelioration legitime, mais c'est un chantier d'outillage transverse qui depasse le
perimetre d'un fix cible sur un ticket deja passe par une premiere correction — non bloquant ici.

## Criteres d'acceptation du ticket #11

| # | Critere | Statut |
|---|---|---|
| 1 | Paiement hors-ligne visible localement en « en attente », synchronise automatiquement au retour du reseau | Couvert (valide en premiere revue, phases 1-4 non retouchees). Le double-tap qui aurait pu produire deux entrees "en attente" pour un seul paiement reel est desormais neutralise (voir ci-dessus). |
| 2 | Conflit signale au vendeur, pas d'ecrasement silencieux | Couvert (valide en premiere revue, non retouche par ce commit). |
| 3 | Application utilisable en lecture (contrats deja charges) sans connexion | Couvert (valide en premiere revue, non retouche par ce commit ; re-verifie ci-dessous via le build). |

## Build/tests (executes par le reviewer, pas repris du rapport du codeur)

**Backend** — `cd backend && mvn -o test`
```
Total tests: 294  Failures: 0  Errors: 0  Skipped: 0
```
Agrege directement depuis `target/surefire-reports/*.txt` (55 fichiers). Identique a la reference de
premiere revue — attendu, le backend n'a pas bouge sur ce commit.

**Frontend — tests** — `cd frontend && npm run test`
```
Test Files  2 passed (2)
     Tests  16 passed (16)
```
Identique a la reference — attendu, le fix touche un composant hors du perimetre `include` de Vitest.

**Frontend — build** — `cd frontend && npm run build` (= `tsc --noEmit && vite build`)
```
built in 8.09s
PWA v0.20.5 -- mode generateSW -- precache 9 entries (775.79 KiB)
files generated: dist/sw.js, dist/workbox-efbd304a.js
```
`dist/manifest.webmanifest` present. Les trois artefacts attendus (`dist/sw.js`,
`dist/workbox-*.js`, `dist/manifest.webmanifest`) sont bien generes. `tsc --noEmit` passe sans erreur
(le nouvel etat `queuing` est correctement type).

## Fichiers cles consultes (cette passe)

- `frontend/src/components/PaymentDialog.tsx` (fichier complet post-fix, pas seulement le diff)
- `frontend/src/pages/PaymentsPage.tsx` (verification que le dialogue reste monte entre deux ouvertures)
- `frontend/package.json`, `frontend/vite.config.ts` (verification de l'absence d'infra de test composant)
- `git show f3c8bdb`, `git diff 020b90c..f3c8bdb --stat`
