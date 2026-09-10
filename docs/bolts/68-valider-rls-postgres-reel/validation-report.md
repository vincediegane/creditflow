# Rapport de validation — #68 Valider empiriquement la Row-Level Security multi-tenant sur un Postgres reel

- Date : 2026-09-10
- Ticket : #68 (repo vincediegane/creditflow), priority: P1
- Auteur : bolt codeur (branche `bolt/issue-68-valider-rls-postgres-reel`)

## Resume du verdict

`RowLevelSecurityIT` (4 tests) et `RowLevelSecurityHibernateIT` (1 test) ont ete executes avec
succes contre un vrai Postgres 16 (Testcontainers), **sans aucun echec, sans skip**. Un premier
passage (voir historique ci-dessous) s'est heurte a un blocage d'outillage — non a un defaut de
RLS — corrige par une mise a jour de dependance dans `backend/pom.xml`. Apres ce correctif, les
deux IT passent au vert et la suite complete (`mvn test`) ne montre aucune regression. **Les 3
criteres d'acceptation sont Valides.**

## Environnement

- `docker version` : Docker Desktop 4.54.0 (212467), Engine `29.1.2`, `ApiVersion: 1.52`,
  `MinAPIVersion: 1.44`, OS `linux` (WSL2, `KernelVersion: 6.6.87.2-microsoft-standard-WSL2`).
- `docker ps` : daemon actif, conteneurs en cours (`infra-postgres-1` sur port hote `5433`,
  `infra-redis-1`, etc.) — pas de conflit de port avec Testcontainers (mapping aleatoire, confirme
  a l'execution : `jdbc:postgresql://localhost:56716/creditflow_it` pour `RowLevelSecurityIT`).
- `docker images` : `postgres:16-alpine` present en cache local (image id `cf78e76683b9`).
- Image `testcontainers/ryuk:0.12.0` (conteneur de nettoyage de Testcontainers) : absente du
  cache local, telechargee a l'execution (9 Mo, ~6 s) — la seule dependance reseau necessaire au
  demarrage du conteneur de test, distincte de l'image Postgres elle-meme.
- OS/machine : Windows 11 Pro 10.0.26200, Docker Desktop avec backend WSL2.
- Acces reseau a Maven Central : confirme disponible (`curl -sI https://repo1.maven.org/maven2/`
  -> `HTTP/1.1 200 OK`), contrairement a l'hypothese initiale de la spec (`mvn -o` heritee sans
  verification du pipeline #40 precedent). Cette correction d'hypothese, transmise par
  l'orchestrateur en cours de bolt, a permis de debloquer la resolution de dependance ci-dessous.

## Défauts trouvés et correctifs

### 1. Blocage initial (outillage, pas RLS) et correctif applique

**Cause racine** : `pom.xml` ne fixait pas explicitement `testcontainers.version`, laissant la
valeur geree par `spring-boot-starter-parent:3.5.6` s'appliquer, soit `org.testcontainers:*:1.21.3`
avec `com.github.docker-java:docker-java-transport-zerodep:3.4.2` en transitif. Cette version de
Testcontainers negocie sa premiere requete avec l'API Docker `1.32` codee en dur dans
`org/testcontainers/dockerclient/DockerClientProviderStrategy.class` (confirme par decompilation
et recherche du motif `1.32` dans le bytecode). Docker Desktop 4.54.0 impose desormais
`MinAPIVersion: 1.44` et rejette cette requete avec `HTTP 400 : "client version 1.32 is too old.
Minimum supported API version is 1.44"`. Consequence observee : `NpipeSocketClientProviderStrategy`
echouait avec une reponse tronquee (`BadRequestException Status 400`), `DockerClientFactory
.isDockerAvailable()` retournait `false`, et `Assumptions.assumeTrue(...)` dans les deux
`@BeforeAll` avortait les classes de test avant la moindre assertion (`Tests run: 0`) — sans
rapport avec un defaut d'isolation RLS.

Diagnostic mene avant correctif (pour eliminer les fausses pistes) :
- Reproductible a l'identique avec 3 named pipes Docker Desktop differents
  (`docker_engine`, `dockerDesktopLinuxEngine`, `docker_engine_linux`).
- `DOCKER_API_VERSION` (variable d'environnement et propriete systeme `-D`) sans effet : la
  valeur `1.32` est figee a la compilation de `testcontainers:1.21.3`, non surchargeable en
  configuration externe dans cette version.
- Confirme qu'il ne s'agissait ni d'un daemon Docker injoignable (`docker ps`/`docker version`
  fonctionnels), ni d'un conflit de port 5432 (`infra-postgres-1` mappe sur `5433`), ni d'un
  probleme specifique a `RowLevelSecurityHibernateIT` (`@SpringBootTest`) : meme cause racine
  reproduite a l'identique dans les deux classes.

**Correctif** : ajout d'un override de version dans `backend/pom.xml`
(`<properties><testcontainers.version>1.21.4</testcontainers.version></properties>`), suivant la
convention deja en place dans ce fichier pour les autres dependances tierces (`mapstruct.version`,
`lombok.version`, etc.). `1.21.4` est la derniere version stable de la ligne `1.21.x` publiee sur
Maven Central (verifiee via `maven-metadata.xml` ; une ligne majeure `2.0.x` existe egalement mais
n'a pas ete retenue — voir "Decision" ci-dessous). Fichier modifie : `backend/pom.xml` (seule
modification de fichier de ce ticket ; aucune migration Flyway, aucun fichier
`TenantConnectionConfig`/`AuthService`/`DemoDataSeeder` touche — aucune des taches conditionnelles
T6-T11 de la spec n'etait applicable, le defaut n'etant pas dans le mecanisme RLS).

**Decision — `1.21.4` plutot que `2.0.5`** : la derniere version stable disponible sur Maven
Central au moment de l'execution est `2.0.5` (changement de version majeure). Par prudence, le
correctif retenu est le pin minimal necessaire (`1.21.4`, meme ligne mineure que la version deja
geree par `spring-boot-starter-parent:3.5.6`, donc a priori deja validee comme compatible par
Spring Boot) plutot qu'un saut de version majeure non teste, qui aurait introduit un risque de
regression plus large sans necessite averee : `1.21.4` suffit a resoudre le probleme observe (voir
resultats ci-dessous), sans qu'il soit necessaire d'aller plus loin.

**Verification de non-regression du correctif** : `mvn test` (suite complete, voir section
dediee) reste a `418 tests, 0 echec` apres le bump de version — aucune regression detectee
ailleurs dans le projet suite a ce changement de dependance.

### 2. Defaut reel d'isolation RLS

Aucun. Les deux IT passent integralement des le premier essai une fois le blocage d'outillage
leve (voir sections d'execution ci-dessous) : aucune policy manquante, aucune fuite de tenant
entre connexions, aucun GRANT manquant n'a ete observe.

## Exécution `RowLevelSecurityIT`

- Commande exacte : `cd backend && mvn test -Dtest=RowLevelSecurityIT`
- Resultat brut : `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.80 s`.
  `BUILD SUCCESS`. Flyway a applique les 19 migrations existantes (jusqu'a `V19__users_email.sql`)
  avant execution — au moment de cette execution, une `V20__users_login_lockout.sql` existe deja
  dans le depot (issue d'un autre ticket, non lie a #68), confirmant que `V20` n'etait plus
  disponible pour un eventuel correctif RLS ; sans consequence ici puisque aucune migration
  correctrice n'a ete necessaire.
- Verdict par methode (ordre `@Order`), toutes vertes :
  - `monoTenantNonRegression_seesAllExistingDataUnderTheSingleOrganization` : passe — visibilite
    complete des donnees existantes sous l'organisation par defaut, comportement RLS transparent
    en mono-tenant.
  - `ac1_directSqlAccessIsIsolatedPerOrganization` : passe — isolation confirmee sur `shops`,
    `customers`, `products`, `credit_sales`, `installments` entre organisation A et organisation B.
  - `ac2_noLeakageBetweenConsecutiveTenantsOnTheSamePhysicalConnection` : passe — aucune fuite
    observee en reutilisant la meme `java.sql.Connection` entre deux `SET
    app.current_org_id` consecutifs (simulation HikariCP), y compris apres reinitialisation du
    tenant a vide (0 ligne visible).
  - `organizationPlanRowsAreIsolatedPerOrganization` : passe — `organization_plan` isole
    correctement `multi_shop` par organisation.

## Exécution `RowLevelSecurityHibernateIT`

- Commande exacte : `cd backend && mvn test -Dtest=RowLevelSecurityHibernateIT`
- Resultat brut : `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 19.66 s`.
  `BUILD SUCCESS`. Contexte Spring demarre normalement (pool HikariCP taille 1, `spring.flyway
  .enabled=false` car le schema est deja migre par le role proprietaire avant le demarrage du
  contexte) — echec de demarrage de contexte explicitement ecarte comme cause potentielle avant
  correctif (voir section precedente), confirme non pertinent maintenant que le test passe.
- Verdict : `tenantPropagatesThroughHibernateWithoutLeakingOnThePooledConnection` : passe —
  `customerRepository.findAll()` via Hibernate ne retourne que les clients de l'organisation
  active (`TenantContext`), et une connexion brute empruntee directement au pool juste apres la
  fin de la transaction precedente confirme que `releaseConnection()` a bien reinitialise
  `app.current_org_id` (`current_setting(..., true)` vide) avant qu'un deuxieme tenant ne
  l'emprunte.

## Suite complète (`mvn test`)

- Commande exacte : `cd backend && mvn test`
- Resultat brut : `BUILD SUCCESS`, `Tests run: 418, Failures: 0, Errors: 0, Skipped: 0`.
- `RowLevelSecurityIT` et `RowLevelSecurityHibernateIT` ne figurent pas dans ce decompte : le
  plugin Surefire n'inclut par defaut que `**/*Test.java`/`**/*Tests.java`/`**/*TestCase.java`
  (aucune configuration `<includes>` personnalisee dans `pom.xml`), pas la convention `*IT.java`
  (habituellement reservee au plugin Failsafe, non utilise dans ce projet). C'est un comportement
  volontaire preexistant — deja constate identiquement dans
  `docs/bolts/40-multitenant-postgres-rls/review.md` (base de 387 tests, elle aussi hors ces deux
  IT, via la commande explicite `-Dtest="!RowLevelSecurityIT,!RowLevelSecurityHibernateIT"`) — pas
  une regression introduite par ce ticket : les deux IT restent des tests d'integration a
  declenchement explicite (`-Dtest=...`), le reste de la suite s'execute sans dependance a Docker.
  Aucune regression detectee sur les 418 tests suite au bump de `testcontainers.version`.

## Verdict par critère d'acceptation

| Critere (#40 / #68) | Statut | Raison |
|---|---|---|
| AC1 — Acces SQL direct isole par organisation (`ac1_directSqlAccessIsIsolatedPerOrganization`) | **Valide** | `RowLevelSecurityIT` execute avec succes contre un Postgres 16 reel, 4/4 tests verts, isolation confirmee sur `shops`, `customers`, `products`, `credit_sales`, `installments`. |
| AC2 — Absence de fuite entre deux tenants consecutifs sur la meme connexion physique (`ac2_...`, JDBC brut et Hibernate) | **Valide** | Confirme a la fois en JDBC brut (`RowLevelSecurityIT.ac2_...`) et via Hibernate/HikariCP taille 1 (`RowLevelSecurityHibernateIT`), y compris verification directe du `RESET` de `app.current_org_id` sur la connexion physique reutilisee. |
| Non-regression mono-tenant (`monoTenantNonRegression_...`) | **Valide** | RLS n'introduit aucun changement observable de comportement sur l'instance mono-tenant existante (organisation/boutique par defaut). |

## Historique de resolution (trace du bolt)

1. Premiere passe (testcontainers 1.21.3, `pom.xml` non modifie) : blocage d'outillage documente
   dans ce meme fichier (voir commit `630e40e`), en supposant `mvn -o` comme contrainte reelle de
   l'environnement (hypothese heritee de la spec, non verifiee a l'epoque).
2. Correction d'hypothese transmise par l'orchestrateur : acces reseau a Maven Central confirme
   disponible dans cet environnement — la contrainte `-o` de la spec n'etait pas fondee.
3. Deuxieme passe : override `testcontainers.version=1.21.4` dans `backend/pom.xml`, resolution de
   dependance via `mvn test` (sans `-o`), re-execution des deux IT — succes complet, suite
   complete revalidee sans regression. Ce rapport reflete ce resultat final.
