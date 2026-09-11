# Spec — #69 Mettre en place une CI (build + tests) declenchee sur chaque pull request

## Résumé

Créer le fichier `.github/workflows/ci.yml` (nouveau, seul livrable) qui exécute en parallèle, sur chaque pull request et sur chaque push vers `master`, la suite backend (`mvn test` sous JDK 21) et la suite frontend (`tsc --noEmit`, `npm run build`, `npm run test` sous Node 20), avec statut visible sur la PR (CI informative, non required).

## Tâches

- [ ] Créer le répertoire `.github/workflows/` (inexistant à ce jour) et le fichier `.github/workflows/ci.yml` avec le contenu exact fourni dans la section **Contrat technique**.
- [ ] Vérifier localement (ou par lecture) que les scripts consommés par le workflow existent bien tels quels et sans modification requise :
  - `backend/pom.xml` répond à `mvn test` (aucun plugin Failsafe présent, donc seuls les `*Test.java` sont exécutés par la convention Surefire par défaut).
  - `frontend/package.json` expose `"build": "tsc --noEmit && vite build"` et `"test": "vitest run"`.
- [ ] Committer uniquement `.github/workflows/ci.yml` — ne modifier aucun autre fichier (`backend/pom.xml`, `frontend/package.json`, `docker-compose.yml` restent inchangés).
- [ ] Pousser la branche `bolt/issue-69-ci-build-tests-pr` et ouvrir/mettre à jour la pull request pour déclencher le workflow sur GitHub Actions (le workflow doit exister sur la branche de base — généralement `master` — pour s'exécuter correctement sur les PR ; sur la première PR qui l'introduit, GitHub Actions exécute néanmoins le workflow tel que défini dans la branche de la PR elle-même pour l'événement `pull_request`).
- [ ] Validation manuelle post-merge (voir **Plan de tests**) : pousser une branche de test avec une régression volontaire (backend ou frontend) et constater le check rouge sur une PR réelle, une fois `ci.yml` fusionné sur `master`.

## Contrat technique

Contenu exact et complet de `.github/workflows/ci.yml` :

```yaml
name: CI

on:
  pull_request:
  push:
    branches:
      - master

jobs:
  backend:
    name: Backend (build + tests)
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: backend
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
          cache-dependency-path: backend/pom.xml

      - name: mvn test
        run: mvn test

  frontend:
    name: Frontend (typecheck + build + tests)
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Node 20
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - name: npm ci
        run: npm ci

      - name: tsc --noEmit
        run: npx tsc --noEmit

      - name: npm run build
        run: npm run build

      - name: npm run test
        run: npm run test
```

Points de contrat à respecter à la lettre (imposés par le design, ne pas diverger sans repasser par l'architecte) :

- Déclencheurs : `pull_request` (toutes branches cible, pas de filtre `branches:` sur cet événement) et `push` restreint à `branches: [master]`.
- Deux jobs indépendants `backend` et `frontend`, exécutés en parallèle (pas de `needs:` entre eux).
- `working-directory: backend` / `working-directory: frontend` via `defaults.run` pour éviter de préfixer chaque commande par `cd`.
- JDK 21 via `actions/setup-java@v4`, `distribution: temurin`, `cache: maven`.
- Node 20 via `actions/setup-node@v4`, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`.
- `npm ci` (pas `npm install`) pour un build reproductible à partir de `frontend/package-lock.json`.
- `npx tsc --noEmit` exécuté en étape séparée et explicite, en plus de `npm run build` (qui inclut déjà `tsc --noEmit` via son script) — redondance assumée pour isoler une erreur de typage d'une erreur de bundling, conformément au design.
- Aucun service `postgres`/`docker` déclaré dans le workflow (hors périmètre : cf. Écarts identifiés ci-dessous).
- Aucune modification de `backend/pom.xml`, `frontend/package.json`, `frontend/package-lock.json`, `docker-compose.yml`.

## Plan de tests

| Critère d'acceptation (ticket #69) | Vérification |
|---|---|
| Une pull request déclenche automatiquement le build et les tests backend et frontend. | Manuel : après merge de `ci.yml` sur `master`, ouvrir une PR quelconque (même triviale) et vérifier dans l'onglet "Checks" de GitHub que les jobs `Backend (build + tests)` et `Frontend (typecheck + build + tests)` démarrent automatiquement sans action manuelle. |
| Le résultat (succès/échec) est visible directement sur la pull request. | Manuel : sur la même PR, vérifier que le statut agrégé (coche verte / croix rouge) apparaît en bas de la conversation de la PR et dans la liste des checks, avec le détail par job accessible en un clic. |
| Une régression volontaire introduite dans une branche de test fait échouer la CI de façon visible. | Manuel, à exécuter une fois `ci.yml` mergé sur `master` : créer une branche `test/ci-regression` (jetable, à supprimer après validation), y introduire une régression triviale et réversible (ex. `assertEquals` changé pour échouer dans un test backend existant, ou une erreur de typage volontaire dans un fichier `.ts` frontend), ouvrir une PR, constater le job concerné (`backend` ou `frontend`) en échec rouge visible sur la PR, puis fermer la PR et supprimer la branche sans merger. |
| (Non-régression implicite) Le workflow ne casse pas le pipeline existant pour un push sans régression. | Manuel : sur la PR d'introduction de `ci.yml` elle-même, vérifier que les deux jobs passent au vert (le code du repo à l'état actuel doit déjà satisfaire `mvn test`, `tsc --noEmit`, `npm run build`, `npm run test`, puisque ce sont les mêmes commandes que le reviewer du pipeline `/bolt` exécute déjà manuellement). |

Aucun test automatisé n'a de sens pour ce ticket : le livrable est lui-même un fichier de configuration CI, sa validation est par nature l'observation de son exécution sur GitHub Actions (pas de test unitaire possible sur un YAML de déclenchement d'événements GitHub).

## Écarts identifiés

- Le design exclut explicitement les tests `*IT.java` (`RowLevelSecurityIT`, `RowLevelSecurityHibernateIT`) de la CI faute de service Postgres/Testcontainers dans le workflow. Le ticket ne mentionne que "tests backend" sans préciser leur périmètre exact ; cette exclusion est cohérente avec les références techniques du ticket (`mvn test`, pas `mvn verify`/Failsafe) et est documentée comme prix assumé dans le design — aucune action requise, mais le codeur ne doit pas tenter d'ajouter un service Postgres dans `ci.yml` pour ce bolt : c'est hors périmètre, à traiter dans un ticket séparé si besoin.
- Le ticket pose au conditionnel la question "statut informatif vs required check" ("à trancher"). Le design tranche explicitement pour un statut informatif (pas de required check, pas de modification de la protection de branche `master`). Ce choix est cohérent avec le périmètre P1 "peu coûteux à mettre en place" et n'entre pas en contradiction avec les critères d'acceptation listés (aucun des trois ne mentionne le blocage du merge) — aucune action requise, mais à signaler explicitement dans la description de la PR d'implémentation pour que ce soit une décision visible et non un oubli.
