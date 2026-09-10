# Rapport de validation — #68 Valider empiriquement la Row-Level Security multi-tenant sur un Postgres reel

- Date : 2026-09-10
- Ticket : #68 (repo vincediegane/creditflow), priority: P1
- Auteur : bolt codeur (branche `bolt/issue-68-valider-rls-postgres-reel`)

## Resume du verdict

Docker fonctionne bien au niveau du daemon (T1 confirme), mais l'execution reelle de
`RowLevelSecurityIT` et `RowLevelSecurityHibernateIT` est bloquee par une **incompatibilite de
version entre la bibliotheque cliente Testcontainers/docker-java figee dans `pom.xml`
(`testcontainers 1.21.3` / `docker-java-transport-zerodep 3.4.2`) et Docker Desktop 4.54.0
installe sur cette machine**, qui impose desormais une API Docker minimale `1.44`. Ce n'est ni un
succes pur, ni un defaut d'isolation RLS, ni un defaut des deux fichiers de test : c'est un
blocage d'outillage/environnement, exterieur au perimetre de code du ticket (voir section
"Defauts trouves et correctifs"). En consequence, les criteres d'acceptation ne peuvent pas etre
coches comme Valides sur cette execution, conformement a la clause de repli du design.

## Environnement

- `docker version` (extrait) :
  - Client : Docker Desktop 4.54.0 (212467), Engine version `29.1.2`, `ApiVersion: 1.52`,
    `DefaultAPIVersion: 1.52`.
  - Server : `Docker Desktop 4.54.0 (212467)`, Engine `29.1.2`, `ApiVersion: 1.52`,
    **`MinAPIVersion: 1.44`**, OS `linux` (WSL2, `KernelVersion: 6.6.87.2-microsoft-standard-WSL2`).
- `docker ps` : daemon actif, conteneurs en cours (`infra-postgres-1` sur le port hote `5433`,
  `infra-redis-1`, etc.) — confirme un daemon fonctionnel, sans conflit de port 5432/5433 avec
  Testcontainers (mapping aleatoire par defaut, jamais atteint dans cette execution puisque le
  conteneur Testcontainers n'a jamais pu demarrer).
- `docker images` : `postgres:16-alpine` present en cache local (image id `cf78e76683b9`),
  confirmant l'absence de dependance reseau pour l'image utilisee par les deux tests.
- OS/machine : Windows 11 Pro 10.0.26200, Docker Desktop avec backend WSL2.
- Dependances de test resolues (`mvn -o dependency:tree`) : `org.testcontainers:testcontainers:1.21.3`
  -> `com.github.docker-java:docker-java-transport-zerodep:3.4.2`. Aucune autre version de ces
  artefacts n'est presente dans le cache Maven local (`~/.m2`), et l'execution est mandatee en mode
  hors-ligne (`mvn -o`), donc aucune mise a jour de ces dependances n'est possible dans cet
  environnement.

## Diagnostic detaille (avant classification)

1. `cd backend && mvn -o test -Dtest=RowLevelSecurityIT` (config par defaut, aucune variable
   d'environnement Docker positionnee) :
   `NpipeSocketClientProviderStrategy` echoue avec `BadRequestException (Status 400)` et un corps
   JSON dont tous les champs sont vides/nuls sauf `Labels`, qui contient
   `com.docker.desktop.address=npipe://\\.\pipe\docker_cli`. Testcontainers conclut
   "Could not find a valid Docker environment" et le `@BeforeAll` (`Assumptions.assumeTrue`)
   avorte la classe entiere : `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0`.
2. Meme resultat identique avec `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` (contexte
   Docker actif d'apres `docker context ls`) — la reponse tronquee est strictement identique, ce
   qui exclut un probleme de contexte Docker mal selectionne.
3. En listant les named pipes exposes par Docker Desktop (`Get-ChildItem \\.\pipe\`), un pipe
   distinct `docker_engine_linux` (different de `docker_engine`/`dockerDesktopLinuxEngine`, qui
   semblent rediriger vers le proxy `docker_cli`) permet a la strategie
   `EnvironmentAndSystemPropertyClientProviderStrategy` (qui respecte `DOCKER_HOST`, contrairement
   a `NpipeSocketClientProviderStrategy` qui ignore la variable et retente toujours le pipe par
   defaut) d'atteindre le vrai moteur Docker, avec un message d'erreur exploitable :
   `{"message":"client version 1.32 is too old. Minimum supported API version is 1.44, please
   upgrade your client to a newer version"}`.
4. Tentatives de contournement par variable d'environnement `DOCKER_API_VERSION=1.44` et par
   propriete systeme `-DDOCKER_API_VERSION=1.44` : aucun effet, le message reste identique. Une
   recherche du motif `1.32` dans les classes decompilees de `testcontainers-1.21.3.jar` confirme
   sa presence dans `org/testcontainers/dockerclient/DockerClientProviderStrategy.class` — c'est
   une valeur figee a la compilation de cette version de Testcontainers, non surchargeable par
   configuration externe dans cette release.
5. Meme diagnostic reproduit a l'identique pour `RowLevelSecurityHibernateIT`.

**Conclusion du diagnostic** : Docker Desktop 4.54.0 a releve son `MinAPIVersion` a `1.44` ; la
version de Testcontainers/docker-java figee dans `pom.xml` (deja presente avant ce ticket, non
modifiee ici) ne sait requeter qu'en API `1.32`, rejetee par le moteur. Aucune version compatible
de ces bibliotheques n'est disponible dans le cache Maven local, et l'execution est contrainte au
mode hors-ligne (`mvn -o`, impose par la spec) — donc aucune mise a jour de dependance n'est
possible depuis cet environnement pour lever ce blocage.

## Exécution `RowLevelSecurityIT`

- Commande exacte : `cd backend && mvn -o test -Dtest=RowLevelSecurityIT`
- Resultat brut : `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0` — la classe entiere est
  avortee des `@BeforeAll` (avant l'execution de la moindre methode `@Test`) par
  `Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable())`, qui evalue a
  `false` a cause du blocage diagnostique ci-dessus (et non parce que le daemon Docker serait
  injoignable — voir section Environnement).
- Verdict par methode (aucune n'a pu s'executer, dans l'ordre `@Order`) :
  - `monoTenantNonRegression_seesAllExistingDataUnderTheSingleOrganization` : non execute.
  - `ac1_directSqlAccessIsIsolatedPerOrganization` : non execute.
  - `ac2_noLeakageBetweenConsecutiveTenantsOnTheSamePhysicalConnection` : non execute.
  - `organizationPlanRowsAreIsolatedPerOrganization` : non execute.

## Exécution `RowLevelSecurityHibernateIT`

- Commande exacte : `cd backend && mvn -o test -Dtest=RowLevelSecurityHibernateIT`
- Resultat brut : `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0` — meme cause qu'au-dessus.
  Verifie explicitement (cf. plan de tests) qu'il ne s'agit pas d'un echec de demarrage du contexte
  Spring : le contexte Spring n'est jamais atteint, l'echec survient dans le `@BeforeAll` statique
  au demarrage du conteneur Testcontainers, avant tout chargement de `@SpringBootTest`.
- Verdict : `tenantPropagatesThroughHibernateWithoutLeakingOnThePooledConnection` : non execute.

## Défauts trouvés et correctifs

Aucun defaut de code de production ni de test n'a pu etre identifie ou ecarte, car les tests n'ont
jamais pu s'executer jusqu'a la moindre assertion RLS. Le seul defaut reel identifie est un defaut
d'**environnement d'execution** (version de Testcontainers/docker-java incompatible avec la
version installee de Docker Desktop), pas un defaut du mecanisme RLS (`V15__row_level_security.sql`,
`V16__app_role_grants.sql`, `TenantConnectionConfig`, `AuthService`, `DemoDataSeeder`) ni des deux
fichiers de test. Conformement au design (« ne jamais assouplir une assertion RLS sans avoir
prouve que l'assertion elle-meme etait fausse » et perimetre limite a ce qu'une execution reelle
revele), **aucune tache conditionnelle T6 a T11 de la spec n'est activee** : aucune migration
Flyway n'a ete ajoutee, aucun fichier de production ni de test RLS n'a ete modifie.

Correctif hors-perimetre explicitement non applique ici (aurait necessite soit un acces reseau
pour mettre a jour `testcontainers`/`docker-java` vers une version compatible API >= 1.44, soit
une modification de configuration Docker Desktop elle-meme — les deux sont hors du perimetre de
code de ce ticket et non actionnables en mode `mvn -o`).

## Suite complète (`mvn -o test`)

- Commande exacte : `cd backend && mvn -o test`
- Resultat brut : `BUILD SUCCESS`, `Tests run: 418, Failures: 0, Errors: 0, Skipped: 0`.
- Aucune regression : les deux classes IT contribuent 0 test au decompte (avortees en
  `@BeforeAll` via `Assumptions.assumeTrue`, comportement volontaire et documente dans leur
  Javadoc), exactement comme lors des 4 tentatives precedentes documentees dans
  `docs/bolts/40-multitenant-postgres-rls/review.md`. Aucun autre test n'est affecte.

## Verdict par critère d'acceptation

| Critere (#40 / #68) | Statut | Raison |
|---|---|---|
| AC1 — Acces SQL direct isole par organisation (`ac1_directSqlAccessIsIsolatedPerOrganization`) | Non validé | Test jamais execute : blocage d'environnement (Testcontainers/docker-java 1.21.3 incompatible avec Docker Desktop 4.54.0, `MinAPIVersion=1.44` vs requete client figee en `1.32`), pas un defaut de code. |
| AC2 — Absence de fuite entre deux tenants consecutifs sur la meme connexion physique (`ac2_...`, JDBC brut et Hibernate) | Non validé | Meme cause : les deux tests (`RowLevelSecurityIT` et `RowLevelSecurityHibernateIT`) n'ont jamais depasse leur `@BeforeAll`. |
| Non-regression mono-tenant (`monoTenantNonRegression_...`) | Non validé | Meme cause. |

**Aucun critere d'acceptation n'est coche comme Valide** dans ce rapport, conformement a la clause
de repli du design (« Si l'exécution ne peut toujours pas aboutir ... documenter le fait et
explicitement ne pas cocher les critères d'acceptation »). Il ne s'agit toutefois pas d'un simple
retour au statu quo ante (Docker indisponible comme lors des 4 tentatives precedentes) : cette
execution apporte un diagnostic nouveau et actionnable — la cause precise est desormais identifiee
(version Testcontainers/docker-java trop ancienne pour l'API minimale exigee par Docker Desktop
4.54.0), avec un chemin de resolution clair : mettre a jour `testcontainers`/`docker-java` vers une
version supportant l'API `>= 1.44` (necessite un acces reseau a un depot Maven, indisponible dans
cet environnement `mvn -o`), puis rejouer telles quelles `RowLevelSecurityIT` et
`RowLevelSecurityHibernateIT` sans autre modification.

## Action de suivi recommandée

1. Mettre a jour `org.testcontainers:testcontainers` (et transitivement
   `com.github.docker-java:docker-java-transport-zerodep`) vers une version supportant l'API
   Docker `>= 1.44`, depuis un environnement avec acces reseau a Maven Central.
2. Rejouer `mvn -o test -Dtest=RowLevelSecurityIT` puis
   `mvn -o test -Dtest=RowLevelSecurityHibernateIT` avec la version mise a jour.
3. Si les deux passent au vert sans defaut d'isolation reel, cocher les 3 criteres d'acceptation
   ci-dessus comme Valides dans une mise a jour ulterieure de ce rapport (ou un nouveau ticket de
   suivi) ; si un defaut d'isolation reel apparait alors, appliquer les taches conditionnelles
   T6-T11 de la spec #68 en consequence.
