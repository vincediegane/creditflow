# Design — #40 Multi-tenant 7/10 — Défense en profondeur Postgres Row-Level Security

## Approche

Le schema existant (verifie table par table dans `backend/src/main/resources/db/migration/`) porte
deja `organization_id` uniquement sur `shops` et `users` (V13). Les tables metier n'ont que
`shop_id` (`customers`, `products`, `credit_sales`, V10) ou une chaine de relations plus longue
(`installments`/`payments` via `sale_id` -> `credit_sales.shop_id` ; `stock_movements`/
`stock_reception_lines` via `product_id` -> `products.shop_id`). Les policies RLS sont donc ecrites
comme des sous-requetes/jointures vers `shops`, jamais comme une colonne `organization_id`
denormalisee sur ces tables -- coherent avec la decision deja actee et repetee par #36/#37/#38/#39.
Seule exception, forcee par une contrainte technique et non par confort : `stock_receptions` (table
d'en-tete) n'a aucune ligne enfant au moment ou Postgres evalue la policy `WITH CHECK` sur l'INSERT
de l'en-tete (les lignes `stock_reception_lines` sont inserees apres, dans le meme flush Hibernate,
une fois l'`id` genere) -- une policy derivee par jointure via les lignes bloquerait donc toute
creation de reception, y compris en mono-tenant. Elle recoit donc une colonne `shop_id` propre
(meme convention que `Customer`/`Product`/`CreditSale`), renseignee par
`StockReceptionService.receive()` au moment de la construction de l'entite.

Le point dur du ticket -- propager `app.current_org_id` a chaque emprunt de connexion, pas
seulement a la creation physique -- est resolu par le SPI natif d'Hibernate 6 (deja la version
livree par `spring-boot-starter-parent:3.5.6`) : un `MultiTenantConnectionProvider` qui enveloppe
le `DataSource` HikariCP existant et execute `SELECT set_config('app.current_org_id', ?, false)` a
chaque `getConnection(tenantId)` et un `set_config(..., '', false)` (RESET) a chaque
`releaseConnection`, combine a un `CurrentTenantIdentifierResolver` qui lit un `ThreadLocal` peuple
par un nouveau filtre servlet place juste apres `JwtAuthenticationFilter`. C'est la seule mecanique
qui s'aligne sur la granularite reelle du probleme : Hibernate resout le tenant et emprunte la
connexion une fois par `Session` (= une fois par frontiere `@Transactional`, avec
`open-in-view: false` deja en place), donc une fois par emprunt logique -- un simple
`connection-init-sql`/`ConnectionCustomizer` HikariCP ne s'execute qu'a la creation physique de la
connexion et ne suffit pas, comme le ticket le pointe explicitement. Prix assume : c'est un
mecanisme qu'aucun code existant n'utilise dans ce backend (aucun `MultiTenantConnectionProvider`
aujourd'hui), donc une zone de risque reelle a valider tot (spike) plutot qu'a decouvrir en fin de
ticket.

Sans role Postgres dedie a l'application, RLS est un theatre : Postgres ignore les policies pour le
proprietaire des tables et pour tout role `SUPERUSER`, et `docker-compose.yml` fait aujourd'hui de
`DB_USERNAME` (`creditflow`) a la fois le role d'amorcage du cluster (donc superuser via
`POSTGRES_USER` de l'image officielle), le proprietaire des tables (Flyway les cree avec ce role)
et le role de connexion applicative. Ce ticket introduit donc un second role Postgres, non
superuser, non proprietaire, utilise uniquement par le pool applicatif (Flyway continue avec le
role actuel). C'est un changement d'infrastructure de deploiement (docker-compose, `.env`), pas
seulement de code, mais sans lui le critere d'acceptation n1 (une requete SQL directe ne doit pas
contourner l'isolement) est faux par construction sur l'installation Docker Compose reellement
livree.

Enfin, les criteres d'acceptation (acces direct SQL bloque, absence de fuite sur reutilisation de
connexion) ne sont verifiables par aucun test existant (suite 100% Mockito, confirme par lecture de
`backend/src/test/java` et deja note par #34/#36/#37). Ce ticket introduit Testcontainers
(`org.testcontainers:postgresql`), la seule option qui exerce reellement Postgres et RLS plutot que
de documenter une procedure manuelle non reproductible pour un controle de securite -- mais ces
tests doivent pouvoir etre ignores proprement (pas echouer en dur) si Docker n'est pas disponible
dans l'environnement d'execution, aucune CI n'existant aujourd'hui (`.github` absent) pour garantir
sa presence partout.

## Fichiers/modules impactes

Migrations (nouvelles, suite de V13) :
- `backend/src/main/resources/db/migration/V14__stock_receptions_shop_id.sql` -- colonne `shop_id`
  sur `stock_receptions` (BIGINT, backfill depuis `stock_reception_lines` -> `products.shop_id`,
  NOT NULL, FK vers `shops`, index -- meme patron que V10/V13).
- `backend/src/main/resources/db/migration/V15__row_level_security.sql` -- fonction SQL
  `app_current_org_id()` (`current_setting('app.current_org_id', true)`, `NULLIF`, cast `BIGINT`,
  `STABLE`) ; `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + `CREATE
  POLICY` sur : `shops` (`organization_id = app_current_org_id()`), `customers`, `products`,
  `credit_sales`, `stock_receptions` (toutes : `shop_id IN (SELECT id FROM shops WHERE
  organization_id = app_current_org_id())`), `installments`, `payments`, `sale_attachments` (via
  `sale_id IN (SELECT cs.id FROM credit_sales cs JOIN shops s ON s.id = cs.shop_id WHERE
  s.organization_id = app_current_org_id())`), `stock_reception_lines`, `stock_movements` (via
  `product_id IN (SELECT p.id FROM products p JOIN shops s ON s.id = p.shop_id WHERE
  s.organization_id = app_current_org_id())`), `user_shops` (via `shop_id`, meme forme que
  `customers`).
- `backend/src/main/resources/db/migration/V16__app_role_grants.sql` -- `GRANT SELECT, INSERT,
  UPDATE, DELETE` (tables listees explicitement, pas `ALL TABLES IN SCHEMA public` pour ne pas
  inclure `flyway_schema_history`) + `GRANT USAGE, SELECT ON ALL SEQUENCES` au role applicatif
  `creditflow_app` -- ce role est cree hors Flyway (voir ci-dessous), cette migration suppose son
  existence prealable (voir Risques).
- `db/init/01-create-app-role.sh` (nouveau, monte dans le service `db` de `docker-compose.yml` sur
  `/docker-entrypoint-initdb.d/`) -- cree le role `creditflow_app LOGIN` avec le mot de passe fourni
  par les variables d'environnement `DB_APP_USERNAME`/`DB_APP_PASSWORD` (jamais un mot de passe en
  dur dans un fichier versionne).

Backend -- nouveau code :
- `backend/src/main/java/com/creditflow/common/security/TenantContext.java` -- `ThreadLocal<Long>`
  (organisation courante), `set`/`get`/`clear`.
- `backend/src/main/java/com/creditflow/common/security/TenantContextFilter.java` --
  `OncePerRequestFilter`, resout `currentShopContext.currentOrganizationId()` si un utilisateur est
  authentifie, peuple `TenantContext`, `clear()` en `finally`.
- `backend/src/main/java/com/creditflow/config/TenantConnectionConfig.java` -- bean
  `MultiTenantConnectionProvider` (enveloppe le `DataSource` Hikari existant, `set_config`/`RESET`
  parametres, pas de concatenation de chaine) et `CurrentTenantIdentifierResolver` (lit
  `TenantContext`, valeur sentinelle si absent) ; un `HibernatePropertiesCustomizer` qui les injecte
  dans les proprietes Hibernate (`AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER`/
  `TENANT_IDENTIFIER_RESOLVER`).

Backend -- modifies :
- `backend/src/main/java/com/creditflow/config/SecurityConfig.java` -- enregistre
  `TenantContextFilter` via `addFilterAfter(tenantContextFilter, JwtAuthenticationFilter.class)`.
- `backend/src/main/java/com/creditflow/auth/service/AuthService.java` -- `login()` doit etre
  scinde en deux etapes transactionnelles : (1) authentification + chargement de `User` (ne touche
  que `users`, hors RLS) puis positionnement de `TenantContext` ; (2) un second appel
  transactionnel (nouvelle `Session`/connexion) pour `currentShopContext.accessibleShops(user)`, qui
  touche `shops` (sous RLS). Aujourd'hui tout est dans une seule `@Transactional`, donc une seule
  connexion/tenant resolus avant que l'organisation soit connue -- voir Risques.
- `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` -- s'execute en
  `ApplicationRunner` au demarrage, hors requete HTTP, donc hors `TenantContextFilter`, et ecrit
  pourtant dans `customers`/`products`/`credit_sales`/`payments` (RLS). Doit positionner
  explicitement `TenantContext.set(...)` (organisation par defaut, resolue comme
  `AdminInitializer`) avant de seeder, et le nettoyer apres.
- `backend/src/main/java/com/creditflow/supplier/domain/StockReception.java` -- ajoute
  `@ManyToOne @JoinColumn(name = "shop_id", nullable = false) private Shop shop;` (meme patron que
  `CreditSale.shop`).
- `backend/src/main/java/com/creditflow/supplier/service/StockReceptionService.java` -- `receive()`
  renseigne `.shop(...)` (deja resolu via `targetShopId`) sur le `StockReception` construit.
- `backend/pom.xml` -- ajoute `org.testcontainers:postgresql` et `org.testcontainers:junit-jupiter`
  en scope `test` (absents aujourd'hui, confirme par lecture integrale du fichier).

Infra/deploiement :
- `docker-compose.yml` -- service `db` : montage de `db/init/01-create-app-role.sh` ; service
  `backend` : `DB_USERNAME`/`DB_PASSWORD` pointent desormais vers le role `creditflow_app`
  restreint, deux nouvelles variables `DB_MIGRATION_USERNAME`/`DB_MIGRATION_PASSWORD` (role
  superuser actuel) injectees pour Flyway.
- `backend/src/main/resources/application.yml` -- `spring.flyway.url`/`user`/`password` explicites
  (role superuser/proprietaire), `spring.datasource.*` conserve pour le role applicatif restreint --
  aujourd'hui les deux partagent la meme config par defaut.
- `.env.example`, `.env.production.example` -- nouvelles variables `DB_APP_USERNAME`,
  `DB_APP_PASSWORD`, `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD` (le role actuel `DB_USERNAME`
  devient de facto le role de migration).

Tests :
- `backend/src/test/java/com/creditflow/security/rls/RowLevelSecurityIT.java` (nouveau,
  `@Testcontainers`, `PostgreSQLContainer`, Flyway applique contre le conteneur, connexions JDBC
  brutes avec `SET app.current_org_id` variable) -- couvre AC1 (organisation B ne voit pas les
  donnees de l'organisation A par SQL direct) et AC2 (deux requetes consecutives sur la meme
  connexion physique, tenants differents, pas de fuite -- simule en reutilisant litteralement le
  meme `java.sql.Connection` sans le fermer entre les deux `SET`).
- `backend/src/test/java/com/creditflow/config/TenantConnectionConfigTest.java` -- test unitaire du
  provider/resolver (mock du `DataSource`, verifie l'appel `set_config` a `getConnection`/
  `releaseConnection`).
- Adapter `AuthServiceTest` a la nouvelle forme de `login()` (deux transactions).

## Decisions cles

- **Jointure vers `shops`/`organizations`, aucune colonne `organization_id` denormalisee sur les
  tables metier existantes** (customers/products/credit_sales/installments/payments/stock_*) --
  coherent avec #36-#39. Prix : les policies a deux sauts (`installments`, `payments`,
  `stock_movements`, `stock_reception_lines`) contiennent une sous-requete a deux jointures ; sur le
  volume attendu (PME, quelques boutiques par organisation, `shops`/`credit_sales` indexes sur
  `shop_id`/`organization_id` depuis V10/V13) c'est un cout accepte, pas un probleme de performance
  mesure ni mesurable a ce stade.
- **`stock_receptions` recoit un `shop_id` propre (derogation ciblee et justifiee a la regle
  ci-dessus)** : impossible de faire autrement sans casser la creation de reception -- la policy
  `WITH CHECK` s'evalue sur la ligne d'en-tete au moment de son propre INSERT, avant que ses lignes
  filles n'existent. Ce n'est pas une remise en cause de #39 (Specification applicative
  `EXISTS`-via-lignes, non mergee, non modifiee ici) : les deux mecanismes coexistent, RLS n'a
  simplement pas le luxe temporel d'attendre les lignes.
- **`users` et `organizations` restent hors RLS.** `users` est interroge par nom d'utilisateur
  (`uk_users_username`, unique globalement, pas par organisation) avant meme que l'organisation ne
  soit connue -- c'est le cas du login et de `JwtAuthenticationFilter`/`AppUserDetailsService` a
  chaque requete. Activer RLS dessus casserait l'authentification elle-meme (poule/oeuf : il faut
  lire `users` pour connaitre l'organisation, donc `app.current_org_id` ne peut pas etre positionne
  avant cette lecture). `organizations` n'a aujourd'hui aucun endpoint de gestion (#34 le confirme),
  donc aucune fuite observable a couvrir. Consequence assumee : la defense en profondeur de ce
  ticket ne couvre pas une fuite qui listerait des utilisateurs d'une autre organisation depuis un
  futur endpoint d'administration mal filtre -- ce cas reste sous la seule garde applicative.
- **`suppliers` et `penalty_settings` restent hors RLS**, pas par oubli : `suppliers` est documente
  comme volontairement commun a toutes les boutiques y compris entre organisations
  (`SupplierService`, commentaire de classe, table sans `shop_id` depuis #8) et `penalty_settings`
  est une ligne unique globale (`id = 1`, #4). Ce sont des lacunes multi-tenant preexistantes,
  anterieures a #34, jamais corrigees par #35-#39 : ce ticket ne les corrige pas non plus (aucune
  colonne d'organisation a joindre), il les documente comme risque connu a traiter par un ticket
  dedie si le produit decide un jour que les fournisseurs/penalites doivent etre isoles par
  organisation.
- **`audit_log` reste hors RLS** : table polymorphe (`entity_type`/`entity_id`), sans cle etrangere,
  sans chemin de jointure generique vers `shops` sans un `CASE` par type d'entite. La garde reste
  `AuditLogAccessGuard` (applicatif). Documente comme risque, pas traite ici (hors du perimetre
  propose par le ticket, qui ne cite pas `audit_log`).
- **Mecanisme de propagation : `MultiTenantConnectionProvider` + `CurrentTenantIdentifierResolver`
  Hibernate**, pas un `HikariCP ConnectionCustomizer`/`connection-init-sql` (ne s'execute qu'a la
  creation physique, pas a chaque emprunt -- insuffisant, le ticket le souligne) ni un
  `StatementInspector` (ne permet pas d'executer une commande SQL separee avant la requete sans hack
  multi-statements fragile cote driver JDBC PostgreSQL). Le `ThreadLocal` alimente par un filtre
  servlet est necessaire car le tenant doit etre connu avant que Hibernate n'ouvre la
  `Session`/emprunte la connexion pour la transaction metier -- donc resolu en amont dans le filtre,
  pas dans un intercepteur au niveau de la requete SQL elle-meme.
- **`set_config('app.current_org_id', ?, false)` parametre**, jamais une concatenation de chaine
  dans un `SET app.current_org_id = '...'` litteral -- evite toute injection, meme si la valeur
  provient d'un `Long` interne. `RESET` (valeur vide) explicite a `releaseConnection`, pour qu'une
  connexion physique rendue au pool HikariCP ne conserve jamais la variable de session du tenant
  precedent tant qu'un nouvel emprunt ne l'a pas repositionnee -- c'est litteralement le critere
  d'acceptation n2.
- **Role Postgres applicatif dedie (`creditflow_app`), separe du role de migration/proprietaire**,
  provisionne par un script d'initialisation Postgres (`docker-entrypoint-initdb.d`), pas par une
  migration Flyway versionnee (un mot de passe ne doit jamais atterrir dans un fichier SQL commite).
  Sans ce second role, RLS ne s'applique a personne : le role actuel (`DB_USERNAME=creditflow`) est
  a la fois superuser du cluster (comportement par defaut de `POSTGRES_USER` sur l'image officielle
  `postgres`) et proprietaire des tables (Flyway les cree avec ce role) -- les deux cas ou Postgres
  ignore purement et simplement les policies RLS, `FORCE ROW LEVEL SECURITY` compris pour le
  superuser.
- **Testcontainers introduit comme dependance de test**, plutot qu'une procedure manuelle
  documentee : les criteres d'acceptation (acces direct SQL, reutilisation de connexion) ne sont
  verifiables qu'avec un vrai Postgres, et laisser cette verification a une procedure manuelle pour
  un controle de securite dont la raison d'etre est justement d'attraper des regressions
  silencieuses serait incoherent. Cout assume : necessite Docker localement pour executer ces tests
  (pas de CI existante pour l'imposer uniformement) -- les nouveaux tests doivent donc etre concus
  pour etre ignores proprement (pas echoues) si Docker est absent de l'environnement d'execution.

## Risques / points d'attention

- **`AuthService.login()` est aujourd'hui une seule methode `@Transactional`** qui charge
  l'utilisateur (`users`, hors RLS) et resout les boutiques accessibles (`shops`, sous RLS) dans la
  meme transaction/connexion -- donc avec un seul tenant resolu, au moment ou l'organisation n'est
  pas encore connue (le login n'est pas derriere `JwtAuthenticationFilter`/`TenantContextFilter`, il
  est dans `PUBLIC_ENDPOINTS`). Sans scission en deux etapes transactionnelles (voir Decisions
  cles), la reponse de connexion aurait une liste de boutiques vide pour tout le monde. C'est le
  seul endpoint qui a besoin de ce traitement particulier -- verifie explicitement par lecture de
  `AuthService.java` et de `SecurityConfig.PUBLIC_ENDPOINTS`.
- **`DemoDataSeeder` (profil demo, `ApplicationRunner`) ecrit dans des tables sous RLS en dehors de
  toute requete HTTP** -- c'est l'illustration concrete, deja presente dans ce depot, du risque
  "job/batch sans contexte HTTP" cite par le ticket. Si `TenantContext` n'est pas positionne
  manuellement autour de son execution, le seed echoue au demarrage en profil demo des que `FORCE
  ROW LEVEL SECURITY` est actif. Aucun `@Scheduled` n'existe aujourd'hui dans le backend (verifie
  par recherche exhaustive) -- c'est actuellement le seul cas reel de ce risque, pas une precaution
  theorique.
- **Migrations existantes sur une base deja en production (installations Docker Compose deja
  deployees, cf. `.env.production.example`)** : le script `docker-entrypoint-initdb.d` ne s'execute
  qu'a la toute premiere initialisation du volume Postgres -- il ne se relance jamais sur un volume
  deja initialise. Toute installation existante doit creer le role `creditflow_app` manuellement
  (procedure a documenter explicitement par le spec-writer) avant de mettre a jour `.env` et de
  redeployer ; sans cette etape manuelle, `V16__app_role_grants.sql` echoue au demarrage (`GRANT ...
  TO creditflow_app` sur un role inexistant), bloquant le demarrage du backend. C'est un point de
  rupture de compatibilite ascendante a traiter comme une etape de runbook, pas comme un defaut du
  code.
- **`V14__stock_receptions_shop_id.sql` (backfill) suppose qu'une reception a toujours au moins une
  ligne** -- si une reception historique sans ligne existe (le modele ne l'interdit pas en base,
  seule la validation applicative de `StockReceptionRequest` pourrait l'empecher, non verifie ici),
  le backfill laisse `shop_id` NULL et la contrainte NOT NULL echoue. A verifier sur les donnees
  reelles avant de rendre la colonne NOT NULL (meme prudence que V10/V13, qui n'ont jamais eu ce
  probleme faute de ligne enfant).
- **Mecanisme Hibernate non eprouve dans ce code base** : aucun `MultiTenantConnectionProvider`
  n'existe aujourd'hui dans ce backend ; son integration avec l'auto-configuration Spring Boot
  (`HibernatePropertiesCustomizer`) et avec la version d'Hibernate 6.x livree par
  `spring-boot-starter-parent:3.5.6` doit etre validee par un spike cible avant d'ecrire la spec en
  detail -- c'est le composant qui porte toute la garantie de securite du ticket (AC2 en
  particulier), une supposition erronee ici invaliderait silencieusement toute la protection.
- **Instance mono-tenant : une seule ligne `organizations`, donc `app_current_org_id()` est
  constante** -- les policies ne doivent rien changer d'observable (meme raisonnement deja valide en
  #35-#39), mais c'est justement le cas le plus dangereux a tester en premier : toute erreur de
  policy (mauvaise colonne, jointure inversee) casserait silencieusement tout acces aux donnees
  existantes des l'activation de `FORCE ROW LEVEL SECURITY`, y compris en mono-tenant -- a couvrir
  explicitement par un test de non-regression Testcontainers avant tout test multi-organisation.
- **`GRANT ... ON ALL TABLES IN SCHEMA public`** inclurait `flyway_schema_history` si elle n'est pas
  explicitement exclue -- le role applicatif n'a aucun besoin d'y acceder ; a enumerer les tables
  plutot que d'utiliser le raccourci `ALL TABLES`.
- **Role proprietaire toujours capable de desactiver RLS** (`ALTER TABLE ... NO FORCE ROW LEVEL
  SECURITY`, `DROP POLICY`) : `FORCE ROW LEVEL SECURITY` + role applicatif non-proprietaire protege
  contre un oubli de filtre cote code applicatif (l'objet de ce ticket), pas contre une
  compromission du role de migration/proprietaire lui-meme -- a documenter comme limite assumee,
  pas comme faille a corriger ici.

## Hors perimetre

- Toute correction des lacunes multi-tenant deja connues et non liees a RLS : `suppliers` partage
  entre organisations (#8), `penalty_settings` global (#4), `UserService.resolveShops`/
  `ShopService` sans filtre d'organisation sur l'assignation (deja signale par #36-#39). RLS ne peut
  pas combler ces lacunes sans colonne de jointure vers l'organisation, et ce n'est pas la demande
  du ticket.
- RLS sur `audit_log` (table polymorphe, pas de chemin de jointure generique) et sur
  `users`/`organizations` (necessaire a l'authentification elle-meme) -- decisions documentees
  ci-dessus, pas des oublis.
- Toute finalisation ou fusion des branches #37/#38/#39 (propagation applicative
  CreditSale/Installment/Payment/StockReception/AuditLog) -- ce ticket ne depend que du schema deja
  merge (#34/#36) et n'attend pas ces PR pour etre pose, coherent avec l'enonce du ticket.
- Migration des installations existantes deja deployees vers le nouveau role applicatif (script de
  bascule automatise, zero-downtime) : seule la procedure manuelle est identifiee comme risque ici ;
  l'outillage eventuel de bascule est un choix du spec-writer/codeur, pas tranche ici.
- Toute UI ou endpoint de gestion des organisations : aucun n'existe, aucun n'est demande par ce
  ticket.
