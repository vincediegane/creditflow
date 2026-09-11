# Review — #69 Mettre en place une CI (build + tests) declenchee sur chaque pull request

## Verdict

APPROVE (avec réserve documentée — voir section Findings)

## Contexte de la review

- Base de comparaison : `master`. Branche : `bolt/issue-69-ci-build-tests-pr`.
- Commit unique du codeur : `c5248dc` — `git show --stat c5248dc` confirme qu'il ne touche **que** `.github/workflows/ci.yml` (58 lignes ajoutées, aucun autre fichier modifié). Conforme à la contrainte de périmètre strict de `spec.md` ("Committer uniquement `.github/workflows/ci.yml`").
- `git diff master...HEAD -- .github/workflows/ci.yml` comparé caractère par caractère au bloc **Contrat technique** de `spec.md` (lignes 21-80) : contenu **identique** (déclencheurs `pull_request` sans filtre de branche + `push` restreint à `master`, deux jobs `backend`/`frontend` indépendants sans `needs:`, `defaults.run.working-directory`, JDK 21 temurin + cache maven avec `cache-dependency-path: backend/pom.xml`, Node 20 + cache npm avec `cache-dependency-path: frontend/package-lock.json`, `npm ci` puis `npx tsc --noEmit` puis `npm run build` puis `npm run test`). Aucune divergence.
- Les deux fichiers de cache référencés existent bien : `backend/pom.xml` et `frontend/package-lock.json`.
- `frontend/package.json` expose bien `"build": "tsc --noEmit && vite build"` et `"test": "vitest run"`, conformément à ce qu'affirme la spec.

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Une pull request déclenche automatiquement le build et les tests backend et frontend | **Couvert** — `ci.yml` définit `on.pull_request` (sans filtre de branche cible) avec deux jobs parallèles `backend` (`mvn test`) et `frontend` (`tsc --noEmit`, `npm run build`, `npm run test`), tous deux déclenchés sans action manuelle. |
| 2 | Le résultat (succès/échec) est visible directement sur la pull request | **Couvert** — mécanisme standard GitHub Actions (`actions/checkout@v4` + jobs nommés explicitement `Backend (build + tests)` / `Frontend (typecheck + build + tests)`), aucune configuration ne masque le statut ; comportement de plateforme, pas de code applicatif à tester. |
| 3 | Une régression volontaire introduite dans une branche de test fait échouer la CI de façon visible | **Couvert structurellement, validation finale manuelle post-merge requise** — chaque step utilise `run:` sans `continue-on-error`, donc tout échec de `mvn test`/`tsc`/`vite build`/`vitest run` fait échouer le job. Le plan de tests de `spec.md` prévoit à raison une validation manuelle sur une vraie PR une fois `ci.yml` fusionné sur `master` (impossible à automatiser ni à vérifier avant fusion). Rien dans le workflow n'empêche ce comportement. |
| (implicite) | Le workflow ne casse pas le pipeline existant pour un push sans régression | **Partiel — voir Findings #1.** Le job `backend` est vert de façon déterministe. Le job `frontend` est **flaky** à cause d'un test préexistant sans lien avec ce bolt (`sync.test.ts`), pas d'un problème introduit par `ci.yml`. |

## Findings

### 1. (Non bloquant, à documenter dans la PR) `frontend/src/offline/__tests__/sync.test.ts` — test flaky préexistant, hors périmètre de ce bolt

**Fichier concerné** : `frontend/src/offline/sync.ts` (comportement) et `frontend/src/offline/__tests__/sync.test.ts:166-189` (test `refuse la reentrance pendant un flush en cours`).

**Investigation menée** :
- `git log --oneline --all -- frontend/src/offline/__tests__/sync.test.ts` → un seul commit, `8b76e9e bolt(#11): phase 2 - file IndexedDB et moteur de rejeu`. Le fichier n'a jamais été touché par la branche `bolt/issue-69-ci-build-tests-pr` (confirmé aussi par `git show --stat c5248dc`, qui ne mentionne que `ci.yml`).
- Lecture de `sync.ts:73-98` (fonction `flushQueue`) : le verrou de réentrance (`flushing`) est posé **de façon synchrone**, avant tout `await`, dès l'appel à `flushQueue`. Ce n'est donc pas un bug de logique de verrouillage — la réentrance est correctement bloquée dès le premier tick JS. L'assertion `expect(concurrent).toEqual({...})` (rapport vide retourné par le deuxième appel) passe systématiquement.
- En revanche, `expect(send).toHaveBeenCalledTimes(1)` (`sync.test.ts:185`) suppose que le **premier** `flushQueue` a déjà atteint l'appel à `deps.send(item)` au moment de cette assertion. Entre l'acquisition du verrou et l'appel à `send`, `flushQueue` effectue deux opérations IndexedDB asynchrones non déterministes en durée (`await queue.list()` puis `await queue.markSyncing(...)`, `sync.ts:101` et `:112`). Le test ne synchronise sur rien de réel : il attend un simple `setTimeout(resolve, 3)` (`tick()`, `sync.test.ts:45`) en espérant que ces deux allers-retours IndexedDB (via `fake-indexeddb`) se terminent en moins de 3 ms. C'est une hypothèse de timing non garantie, donc un test intrinsèquement flaky — pas un bug fonctionnel de `sync.ts`.
- Reproduction locale : `cd frontend && npm run test -- --run` exécuté **3 fois de suite** sur cette branche :
  - Run 1 : **échec** — `src/offline/__tests__/sync.test.ts > flushQueue > refuse la reentrance pendant un flush en cours` → `AssertionError: expected "spy" to be called 1 times, but got 0 times` (`sync.test.ts:185`).
  - Run 2 : succès (22/22 tests passés).
  - Run 3 : succès (22/22 tests passés).

  Ceci confirme objectivement le diagnostic du codeur : comportement **flaky** (dépendant du timing machine), pas une régression déterministe liée à ce bolt.

**Scénario de déclenchement** : sur un runner GitHub Actions (souvent plus lent/chargé qu'un poste de dev), la probabilité que les deux opérations IndexedDB dépassent 3 ms est significative. Conséquence concrète : le job `Frontend (typecheck + build + tests)` de la toute première PR introduisant `ci.yml` — et de PRs ultérieures sans rapport avec `sync.ts` — a une chance réelle d'apparaître rouge par intermittence, ce qui peut être mal interprété comme une régression liée à la PR en cours.

**Décision et justification** :
- Le périmètre de ce bolt, verrouillé par `spec.md` ("Committer uniquement `.github/workflows/ci.yml`... ne modifier aucun autre fichier"), exclut explicitement toute correction de code applicatif ou de test. Élargir ce bolt pour corriger `sync.test.ts` violerait ce contrat sans repasser par l'architecte, et mélangerait deux préoccupations indépendantes (mise en place de la CI vs. correction d'un test flaky préexistant du ticket #11).
- Le rôle même de ce ticket est de mettre en lumière ce type de problème resté invisible faute de CI systématique — c'est un signal que la CI fonctionne comme prévu, pas un défaut de son introduction.
- Le design (`spec.md:108`) a explicitement tranché pour une CI **informative**, pas un required check bloquant le merge : un job frontend rouge par intermittence sur cette cause précise ne bloque donc aucune PR, ce qui limite l'impact concret de cette réserve.
- **Recommandation, à reprendre explicitement dans la description de la PR finale** : signaler cette flakiness connue (test `sync.test.ts:166`, code `sync.ts`, héritée du ticket #11) et ouvrir un ticket de suivi dédié pour la corriger (remplacer le `setTimeout(resolve, 3)` fixe par une synchronisation déterministe sur l'état réel du flush, par exemple des fake timers ou l'attente explicite d'un signal émis juste avant l'appel à `send`). Ne pas laisser cette information disparaître dans l'historique des commits.

Aucun autre finding bloquant identifié : le fichier `ci.yml` est conforme à la lettre au contrat technique, ne modifie aucun fichier hors périmètre, et les deux jobs backend/frontend sont corrects et fonctionnellement indépendants.

## Build/tests

- `cd backend && mvn -q test` → **succès** (exit code 0), aucune erreur ni test en échec dans les logs applicatifs.
- `cd frontend && npx tsc --noEmit` → **succès** (exit code 0, aucune sortie).
- `cd frontend && npm run build` (inclut `tsc --noEmit && vite build`) → **succès** (exit code 0, build Vite + PWA generateSW OK).
- `cd frontend && npm run test -- --run` (`vitest run`) → exécuté 3 fois :
  - Run 1 : **1 test en échec** (`sync.test.ts > flushQueue > refuse la reentrance pendant un flush en cours`, `sync.test.ts:185`, `AssertionError: expected "spy" to be called 1 times, but got 0 times`), 21/22 tests OK.
  - Run 2 et Run 3 : 22/22 tests OK, aucun échec.
  - Conclusion : comportement flaky confirmé, isolé au fichier `sync.test.ts` (préexistant, ticket #11, hors périmètre de #69). Voir Finding #1 pour l'analyse complète et la recommandation.

## Conclusion

`ci.yml` respecte à la lettre le contrat technique de `spec.md`, ne modifie aucun fichier hors périmètre, et couvre les trois critères d'acceptation du ticket (le troisième nécessitant par nature une validation manuelle post-merge sur une vraie PR). Le seul point d'attention est une flakiness préexistante et non liée à ce bolt dans `frontend/src/offline/__tests__/sync.test.ts`, qui rendra le job `frontend` rouge de façon intermittente sur les premières PR après fusion. Cette réserve est documentée ici pour être reprise mot pour mot dans la description de la PR finale, avec recommandation d'ouvrir un ticket de suivi séparé pour corriger le test. Elle ne justifie pas de `CHANGES_REQUESTED` : elle est hors périmètre du contrat technique verrouillé par la spec, n'affecte pas le statut informatif (non bloquant) de la CI, et constitue précisément le type de signal que ce ticket avait pour but de révéler.
