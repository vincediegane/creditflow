# Design — #68 Valider empiriquement la Row-Level Security multi-tenant sur un Postgres reel

## Approche

Docker est maintenant fonctionnel dans cet environnement (`docker ps` retourne des conteneurs
actifs) et l'image `postgres:16-alpine` utilisee par les deux tests est deja presente en cache
local (`docker images`) -- donc pas de dependance reseau qui pourrait reproduire le blocage
documente 4 fois dans `docs/bolts/40-multitenant-postgres-rls/review.md`. Ce ticket ne redesign
pas RLS : le mecanisme (`V15__row_level_security.sql`, `V16__app_role_grants.sql`,
`TenantConnectionConfig`) a deja ete relu statiquement et juge coherent par #40 ; l'objectif ici
est purement empirique -- executer `RowLevelSecurityIT` puis `RowLevelSecurityHibernateIT`,
interpreter le resultat, et ne corriger que ce qu'une execution reelle revele comme defaut reel,
sans elargir le perimetre aux decisions deja actees par #40 (jointures shops/organization_id,
choix du role applicatif dedie, etc.). Prix assume : si le test echoue pour une raison qui n'est
pas un defaut d'isolation (bug du test lui-meme, hypothese fausse sur l'environnement d'execution),
la tentation sera de faire passer le test a tout prix -- le design impose donc explicitement de
distinguer les deux cas avant de toucher au code de production.

Methodologie d'execution recommandee, dans cet ordre, pour isoler la source d'un echec eventuel :

1. `RowLevelSecurityIT` seul (JDBC brut, sans Spring) -- valide le mecanisme SQL pur (policies,
   role applicatif, `set_config`).
2. `RowLevelSecurityHibernateIT` seul (`@SpringBootTest`, pool Hikari taille 1) -- valide
   l'integration Hibernate/`MultiTenantConnectionProvider` en conditions reelles.
3. Suite complete existante (`mvn -o test`, sans exclusion) -- verifie l'absence de regression sur
   les 387 tests Mockito deja verts, y compris si un correctif a ete applique a
   `TenantConnectionConfig`/`AuthService`.

Si un test echoue, la methode d'investigation est : reproduire l'echec, lire le message et la
stacktrace exacts, puis trancher explicitement entre trois cas avant d'agir :
- Succes pur : rien a corriger, documenter et cocher les criteres d'acceptation.
- Defaut reel d'isolation (policy manquante ou inversee, connexion qui ne reset pas le tenant,
  role applicatif superuser ou proprietaire par erreur) : corriger au plus pres de la cause
  (migration SQL, `TenantConnectionConfig`, `AuthService`), re-executer les deux IT puis la suite
  complete, documenter le defaut et le correctif.
- Defaut du test lui-meme (hypothese sur l'etat seed, ordre d'`@Order`, specificite Windows du
  chemin JDBC/Testcontainers, timing du conteneur) : corriger uniquement le test, en documentant
  explicitement pourquoi ce n'etait pas un risque de securite reel -- ne jamais assouplir une
  assertion RLS pour la faire passer sans avoir prouve que l'assertion elle-meme etait fausse.

## Fichiers/modules impactes

Executes, sans modification attendue a priori (a confirmer par l'execution) :
- `backend/src/test/java/com/creditflow/security/rls/RowLevelSecurityIT.java`
- `backend/src/test/java/com/creditflow/security/rls/RowLevelSecurityHibernateIT.java`

Modifiables uniquement si un defaut reel est revele par l'execution (a ne pas toucher sinon) :
- `backend/src/main/resources/db/migration/V15__row_level_security.sql` -- si une policy est
  manquante ou incorrecte (nouvelle migration `V17__...sql` a ajouter, jamais d'edition d'une
  migration Flyway deja versionnee/appliquee).
- `backend/src/main/resources/db/migration/V16__app_role_grants.sql` -- meme regle (nouvelle
  migration si un GRANT manque).
- `backend/src/main/java/com/creditflow/config/TenantConnectionConfig.java` -- si
  `getConnection`/`releaseConnection` ne repositionne ou ne reset pas correctement
  `app.current_org_id`.
- `backend/src/main/java/com/creditflow/auth/service/AuthService.java` -- si le decoupage en deux
  etapes transactionnelles (deja en place selon `docs/bolts/40-multitenant-postgres-rls/design.md`)
  s'avere incomplet en conditions reelles.
- `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` -- si le seed echoue sous
  `FORCE ROW LEVEL SECURITY` faute de `TenantContext` positionne.
- Les deux fichiers de test eux-memes, seulement si le defaut identifie est dans le test (voir
  Approche), jamais pour masquer un defaut reel.

Documentation, a creer/modifier (certain, quel que soit le resultat) :
- `docs/bolts/68-valider-rls-postgres-reel/design.md` (ce fichier).
- `docs/bolts/68-valider-rls-postgres-reel/validation-report.md` (nouveau, a produire par le
  codeur) -- rapport trace et date : environnement (version Docker, image Postgres utilisee),
  commande exacte executee, resultat brut (tests verts/rouges, nombre), defauts trouves et
  correctifs appliques le cas echeant, verdict final par critere d'acceptation (AC1/AC2/AC3 de
  #40).
- `docs/bolts/40-multitenant-postgres-rls/review.md` -- ajout d'un pointeur bref en fin de fichier
  (pas de reecriture du contenu existant, qui est un document de revue cloture avec un verdict
  APPROVE) renvoyant vers `validation-report.md` pour lever explicitement le point residuel
  non-bloquant qu'il documente.

## Decisions cles

- Le rapport de validation vit dans un nouveau fichier du dossier `68-...`
  (`validation-report.md`), pas par reecriture de
  `docs/bolts/40-multitenant-postgres-rls/review.md` : ce dernier est un document de revue deja
  cloture (verdict APPROVE, 174 lignes d'analyse) qu'il ne faut pas alterer retroactivement ; on y
  ajoute seulement quelques lignes de pointeur, conformement a la formulation du ticket qui
  autorise un nouveau document de suivi.
- Ordre d'execution IT JDBC brut avant IT Spring/Hibernate : isole si un echec vient du mecanisme
  SQL pur (policies, role) ou de l'integration Hibernate -- evite un diagnostic confus qui
  attribuerait a tort un probleme d'integration Spring a une policy RLS, ou l'inverse.
- Correctif eventuel par nouvelle migration Flyway (`V17`), jamais edition de `V15`/`V16` : regle
  deja etablie par le projet (migrations versionnees immuables une fois mergees) -- s'applique
  meme si `V15`/`V16` n'ont jamais ete executees avec succes en pratique avant ce ticket.
- Ne pas mettre en place de CI pour rejouer ces tests automatiquement : aucune CI n'existe dans ce
  repo (dossier `.github` absent, confirme) ; l'ajouter serait un changement d'infrastructure hors
  perimetre du ticket, qui ne demande qu'une execution ponctuelle et tracee.
- Si les deux IT passent du premier coup sans aucun correctif : le rapport le documente quand meme
  explicitement avec la sortie de commande complete (pas juste un statut OK) -- c'est la preuve
  demandee par le ticket, pas une formalite.

## Risques / points d'attention

- Etat partage entre methodes de test : `RowLevelSecurityIT` utilise
  `@TestMethodOrder(OrderAnnotation.class)` avec des champs statiques (`organizationAId`,
  `shopAId`) peuples en `@BeforeAll` et reutilises ou etendus test apres test (`ac1_...` insere
  Organisation B, reutilisee par `ac2_...` et `organizationPlanRowsAreIsolatedPerOrganization`).
  Un echec sur un `@Order` donne peut faire echouer en cascade les suivants sans rapport avec la
  cause reelle -- lire le premier echec dans l'ordre, pas le dernier.
- `RowLevelSecurityHibernateIT` est un `@SpringBootTest` complet : un echec peut venir d'un
  probleme de demarrage de contexte Spring sans rapport avec RLS (bean manquant, config manquante
  sous les proprietes dynamiques injectees par `@DynamicPropertySource`) plutot que d'un vrai
  defaut d'isolation -- verifier la nature de l'exception (echec de demarrage de contexte vs echec
  d'assertion) avant de conclure a un defaut RLS.
- Port 5432 deja occupe par le conteneur `infra-postgres-1` (stack infra deja demarree dans cet
  environnement) : `PostgreSQLContainer` de Testcontainers n'expose pas ce port en dur dans le code
  des deux tests (mapping aleatoire par defaut) donc pas de conflit attendu, mais a confirmer au
  premier lancement -- si echec de demarrage de conteneur, ne pas conclure a tort a un defaut RLS.
- Ne pas regresser la suite existante : tout correctif touchant `TenantConnectionConfig`, les
  migrations RLS ou `AuthService` doit etre revalide contre la suite complete (`mvn -o test`, 387
  tests actuellement verts), y compris `TenantConnectionConfigTest` et `AuthServiceTest` qui
  encodent le comportement Mockito actuellement attendu.
- Ne pas elargir le perimetre aux lacunes deja actees et documentees comme hors-perimetre par #40
  (`suppliers` partage entre organisations, `penalty_settings` global, `audit_log` et
  `users`/`organizations` hors RLS) -- une execution reelle peut rendre ces lacunes plus visibles
  mais ce ticket ne les corrige pas, elles restent un risque connu documente ailleurs.
- Mot de passe applicatif en dur dans les tests (`APP_PASSWORD = creditflow_app`, deja present
  dans le code, pas introduit par ce ticket) -- usage strictement local a un conteneur
  Testcontainers ephemere, pas une fuite de secret de production ; a ne pas corriger hors
  perimetre.

## Hors perimetre

- Refonte de l'architecture RLS actee par #40 (schema de jointures shops/organization_id,
  mecanisme `MultiTenantConnectionProvider`, choix du role applicatif dedie) -- ce ticket valide et
  corrige des bugs reels reveles par l'execution, il ne remet pas en cause les decisions
  structurantes deja prises, sauf si un test le prouve objectivement faux.
- Mise en place d'une CI qui execute systematiquement ces deux IT a chaque build -- aucune CI
  n'existe aujourd'hui dans ce repo, l'introduire est un changement d'infrastructure hors du
  perimetre explicite du ticket.
- Correction des lacunes multi-tenant deja connues et deja documentees comme hors-perimetre par #40
  (`suppliers`, `penalty_settings`, `audit_log`, `users`/`organizations` hors RLS) -- inchangees
  ici.
- Toute preparation concrete du deploiement mutualisant plusieurs organisations dans une meme
  instance evoque par le ticket comme contexte futur -- #68 est un prealable a ce deploiement, pas
  ce deploiement lui-meme.
- Optimisation de performance des policies a deux jointures (`installments`, `payments`,
  `stock_movements`) -- deja actee comme cout accepte par #40 ; ce ticket ne la remet en cause que
  si l'execution revele une erreur de correction, pas de performance.
