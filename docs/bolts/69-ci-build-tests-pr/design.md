# Design — #69 Mettre en place une CI (build + tests) declenchee sur chaque pull request

## Approche

Ajouter un unique workflow GitHub Actions, `.github/workflows/ci.yml`, avec deux jobs independants
executes en parallele : `backend` (`mvn test` sous JDK 21) et `frontend` (`tsc --noEmit`, `npm run
build`, `npm run test` sous Node 20). Declencheurs : `pull_request` (toutes branches cible) et `push`
sur `master`, conformement au perimetre du ticket (et idealement sur push vers master). Aucun
service Docker/Postgres nest necessaire dans le workflow : verifie que `mvn test` (sans le plugin
Failsafe, absent du `pom.xml`) nexecute que les `*Test.java` par la convention Surefire par defaut,
et que le seul test annote `@SpringBootTest` du repo est justement `RowLevelSecurityHibernateIT`
(suffixe `IT`, donc exclu) : les deux IT necessitant Testcontainers ne tournent pas dans ce
workflow, coherent avec le perimetre explicitement pose par le ticket. Prix assume : ce filet CI ne
couvre donc pas les tests `*IT.java` (RLS Postgres) ; un futur ticket separe serait necessaire pour
les integrer (service Postgres ou Testcontainers-in-CI), non demande ici.

Le workflow ne modifie aucun fichier source ni `pom.xml`/`package.json` existant : il consomme les
scripts deja en place (`mvn test` depuis `backend/`, `npm run build` = `tsc --noEmit && vite build`,
`npm run test` = `vitest run` depuis `frontend/`). Statut retenu pour ce premier jalon : CI
informative (visible sur la PR via les checks GitHub), pas de required status check. Verifie via
`gh api repos/.../branches/master/protection` : aucune protection de branche nexiste aujourdhui
sur `master` ; lactiver est un reglage de repo (parametres GitHub, pas un fichier versionne) hors
perimetre de ce changement de code, laisse au choix ulterieur dun admin du repo (voir Decisions
cles et Hors perimetre).

## Fichiers/modules impactes

- `.github/workflows/ci.yml` (nouveau, seul fichier cree). Aucun repertoire `.github/` nexiste
  actuellement dans le repo (verifie).
- Aucun fichier existant modifie : ni `backend/pom.xml`, ni `frontend/package.json`, ni
  `docker-compose.yml`. Les scripts `mvn test`, `npm run build`, `npm run test` sont deja
  fonctionnels tels quels (confirmes par les revues precedentes, ex. `docs/bolts/28-.../review.md`).

## Decisions cles

- Deux jobs paralleles (`backend`, `frontend`) dans un seul fichier de workflow, plutot que deux
  fichiers separes ou un job sequentiel unique. La parallelisation reduit le temps total de feedback
  sur la PR ; un seul fichier reste simple a lire/maintenir pour un repo de cette taille (pas besoin
  de `workflow_call` ou de composite actions ici).
- Versions figees sur les versions reellement utilisees en prod/dev, pas sur "latest" : JDK 21
  (`actions/setup-java` avec `distribution: temurin`, coherent avec `backend/Dockerfile` qui utilise
  `maven:3.9-eclipse-temurin-21` / `eclipse-temurin:21-jre-alpine`) et Node 20
  (`actions/setup-node`, coherent avec `frontend/Dockerfile` qui utilise `node:20-alpine`). Evite un
  ecart silencieux entre lenvironnement CI et lenvironnement de build/deploiement reel.
- `tsc --noEmit` execute en etape separee, en plus de `npm run build` (qui linclut deja via
  `"build": "tsc --noEmit && vite build"`), pour suivre litteralement le decoupage en trois etapes du
  ticket (section "Perimetre propose") et isoler visiblement une erreur de typage dune erreur de
  bundling dans linterface des checks PR. Prix assume : `tsc` tourne deux fois par run frontend
  (redondance de quelques secondes, negligeable), au benefice dun diagnostic plus lisible en cas
  dechec.
- Cache des dependances active : `actions/setup-java` avec `cache: maven` (cle basee sur
  `backend/pom.xml`) et `actions/setup-node` avec `cache: npm` plus
  `cache-dependency-path: frontend/package-lock.json`. `npm ci` (pas `npm install`) pour un
  environnement reproductible a partir du lockfile deja versionne (`frontend/package-lock.json`).
- CI informative, pas de required status check dans ce bolt. Le ticket laisse le choix ouvert
  (a trancher) et aucune protection de branche nexiste aujourdhui sur `master` (verifie via lAPI
  GitHub). Activer un required check est un reglage de repository (Settings > Branches), pas un
  changement versionnable dans ce depot ; le laisser hors perimetre de code evite de pretendre
  livrer quelque chose qui nest pas verifiable par une revue de diff. A signaler explicitement au
  spec-writer et au reviewer comme action manuelle residuelle si la mention "sans bloquer
  necessairement le merge dans un premier temps" du ticket doit un jour devenir bloquante.
- Pas de matrice de versions (une seule version de JDK, une seule version de Node) : le repo ne
  cible quun environnement de production unique (versions figees dans les Dockerfiles), une matrice
  ajouterait du temps de CI sans valeur pour ce projet.

## Risques / points dattention

- Le critere dacceptation "une regression volontaire fait echouer la CI de facon visible" ne peut
  pas etre verifie par simple lecture du fichier YAML : il faudra que le codeur ou le reviewer du
  bolt pousse une branche de test avec une regression deliberee (par exemple une assertion cassee)
  et observe le check rouge sur la PR reelle GitHub, une fois le workflow merge sur la branche par
  defaut (les workflows declenches par `pull_request` doivent exister sur la base du merge,
  generalement `master`, pour sexecuter sur les PR ulterieures).
- `npm run test` (Vitest) est actuellement peu couvrant (4 fichiers de test seulement, cote
  frontend) : la CI naugmente pas la couverture existante, elle automatise seulement ce qui est deja
  execute manuellement en review de bolt. Ne pas presenter ce ticket comme ameliorant la couverture
  de tests.
- Aucun secret ni service externe requis pour `mvn test` : le seul test manipulant un client S3
  (`S3DocumentStorageTest`) mocke `S3Client`, aucune variable AWS reelle nest necessaire ; a
  reconfirmer si de nouveaux tests dintegration sont ajoutes plus tard.
- `actions/setup-java` avec `cache: maven` hache tous les `pom.xml` trouves dans le repo pour la
  cle de cache ; comme il ny a quun seul module Maven (`backend/pom.xml`, pas de `pom.xml` racine),
  le comportement par defaut est correct sans configuration `cache-dependency-path` supplementaire :
  a verifier lors du premier run reel (cache miss attendu au premier lancement, hit ensuite).
- Pas de branch protection existante : tant que le check nest pas rendu required manuellement par
  un admin (hors perimetre code), une PR peut techniquement etre fusionnee malgre un statut CI rouge.
  Le ticket accepte explicitement ce compromis pour un premier jalon.

## Hors perimetre

- Executer les tests `*IT.java` (`RowLevelSecurityIT`, `RowLevelSecurityHibernateIT`) en CI
  (necessiterait Docker/Testcontainers ou un service Postgres dedie dans le workflow) : explicitement
  ecarte par le contexte fourni avec le ticket.
- Activer un required status check ou une regle de protection de branche sur `master` dans les
  parametres GitHub du repository : reglage hors code, a decider et appliquer separement par un
  admin du repo.
- Ajouter un job de lint (ESLint) ou de couverture de code (JaCoCo, coverage Vitest) : non demande
  par le ticket, qui liste explicitement `mvn test`, `tsc --noEmit`, `npm run build`, `npm run test`.
- Notification externe (Slack, email) en cas dechec CI : le ticket demande uniquement une visibilite
  sur la pull request elle-meme (statut GitHub natif), pas un canal supplementaire.
- Deploiement automatique (CD) declenche par ce workflow : hors perimetre, ce ticket couvre
  uniquement le build et les tests.
