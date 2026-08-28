# Design — #34 Multi-tenant 1/10 — Fondation de donnees (entite Organization)

## Approche

Reproduire exactement le patron deja utilise pour introduire `Shop` en #10
(`V10__shops.sql`) : une migration Flyway qui cree la table `organizations`, insere une
unique ligne "organisation par defaut", puis ajoute `organization_id` sur `shops` et sur
`users`, retro-rempli avec cette organisation, **NOT NULL** (pas de colonne nullable
temporaire).
Compromis assume : cela deborde legerement du perimetre "aucun fichier hors migration/entite"
suggere par le ticket, mais NOT NULL sans exception evite qu'un `organization_id` nul se
propage dans les tickets suivants (#35 a #43) qui doivent tous pouvoir compter sur sa
presence pour filtrer sans code de contournement — l'alternative (colonne nullable) aurait
reporte ce cout, avec interet, sur la chaine de tickets suivante.

## Fichiers/modules impactes

Nouveaux :
- `backend/src/main/resources/db/migration/V13__organizations.sql` — table `organizations`,
  insertion de l'organisation par defaut, colonnes `organization_id` sur `shops` et `users`
  (NOT NULL, FK, index), sur le modele de `V10__shops.sql`.
- `backend/src/main/java/com/creditflow/organization/domain/Organization.java` — entite JPA,
  meme structure que `Shop.java` (`extends Auditable`, `@Builder`).
- `backend/src/main/java/com/creditflow/organization/repository/OrganizationRepository.java` —
  `JpaRepository<Organization, Long>` + une methode de resolution de l'organisation par defaut
  (ex. `findFirstByOrderByIdAsc()`), consommee uniquement par les trois points d'insertion
  ci-dessous, pas par une logique de scoping (hors perimetre, cf. #35).

Modifies (uniquement pour attacher l'organisation par defaut a la creation, aucune requete
de lecture existante n'est touchee) :
- `backend/src/main/java/com/creditflow/shop/domain/Shop.java` — ajout
  `@ManyToOne private Organization organization;`.
- `backend/src/main/java/com/creditflow/auth/domain/User.java` — ajout
  `@ManyToOne private Organization organization;`.
- `backend/src/main/java/com/creditflow/shop/mapper/ShopMapper.java` /
  `backend/src/main/java/com/creditflow/shop/service/ShopService.java` — `create()` doit
  renseigner `organization` sur le `Shop` construit (l'organisation par defaut resolue via
  `OrganizationRepository`) avant `save`.
- `backend/src/main/java/com/creditflow/auth/service/UserService.java` — `create()` doit
  renseigner `organization` sur le `User` construit, meme mecanisme.
- `backend/src/main/java/com/creditflow/auth/bootstrap/AdminInitializer.java` —
  `createDefaultAdmin()` doit renseigner `organization` sur l'admin cree au demarrage.

Non modifies (confirme par lecture du code, a rappeler explicitement au spec-writer pour
eviter toute derive de perimetre) : `CurrentShopContext.java`, `ShopRepository.java`,
tout repository/`Specification` metier (`CustomerRepository`, `ProductRepository`,
`CreditSaleRepository`, `PaymentRepository`, etc.), tout controller, tout endpoint, le
frontend. Aucun de ces fichiers ne doit changer dans ce ticket.

## Decisions cles

- **`organization_id` NOT NULL sur `shops` et `users`, avec backfill vers une organisation
  par defaut unique** (pas de colonne nullable temporaire). Justification : les tickets #35+
  ont besoin d'une valeur toujours presente pour filtrer sans code de contournement (cf.
  `docs/bolts/25-architecture-multi-tenant-saas/design.md`, decision "porter l'organisation
  directement sur `User`... ne depend d'aucune hypothese fragile"). Le prix : les 3 points
  d'insertion (`ShopService.create`, `UserService.create`, `AdminInitializer`) doivent etre
  touches dans ce ticket pour continuer a fonctionner apres la migration — sinon la creation
  d'une boutique ou d'un compte casse immediatement (violation NOT NULL), ce qui contredirait
  le critere d'acceptation "aucun comportement observable ne change".
- **Schema minimal pour `organizations`** : `id BIGSERIAL PRIMARY KEY`, `name VARCHAR(120)
  NOT NULL`, colonnes d'audit standard (`created_at`, `updated_at`, `created_by`,
  `updated_by`, cf. `Auditable`). Pas de colonne `active`, pas de champs de facturation/plan :
  rien de tout cela n'est consomme par ce ticket ni par les criteres d'acceptation ; les
  ajouter maintenant serait de la speculation (le ticket de suivi n9 introduira une table
  dediee au plan par organisation).
- **Organisation par defaut inseree par la migration elle-meme** (`INSERT INTO organizations
  ...` dans `V13`), pas par un `ApplicationRunner` au demarrage — meme mecanisme que
  `V10__shops.sql` pour "Boutique principale". Garantit qu'une seule ligne existe des la
  migration, sans dependre de l'ordre de demarrage des beans Spring.
- **Nom du module/package : `com.creditflow.organization`**, calque sur la structure du
  package `com.creditflow.shop` (`domain/`, `repository/`) mais sans `dto/`, `service/`,
  `web/` — rien ne les consomme dans ce ticket, les ajouter serait premature. Les tickets
  suivants les creeront si necessaire.
- **Pas de nouvelle table de jonction `user_organizations`** : contrairement a
  `Shop`/`User` (`user_shops`, relation many-to-many parce qu'un `SELLER` peut etre rattache
  a plusieurs boutiques), la relation `User` vers `Organization` est `@ManyToOne` simple.
  C'est explicitement la donnee que le ticket demande (« un ADMIN sans boutique... n'a par
  definition aucune boutique dont deriver une organisation ») : chaque utilisateur appartient
  a exactement une organisation, jamais plusieurs — coherent avec le modele SaaS retenu en #25
  (un utilisateur n'existe que dans le tenant qui l'a cree).

## Risques / points d'attention

- **Trois points d'insertion identifies par lecture du code** (pas une supposition) :
  `ShopService.create` (`backend/.../shop/service/ShopService.java`, lignes 47-55, via
  `ShopMapper.toEntity`), `UserService.create`
  (`backend/.../auth/service/UserService.java`, lignes 41-62), et `AdminInitializer.
  createDefaultAdmin` (`backend/.../auth/bootstrap/AdminInitializer.java`, lignes 29-53)
  construisent aujourd'hui un `Shop`/`User` sans aucune notion d'organisation. Si la
  migration rend `organization_id` NOT NULL sans que ces trois points soient mis a jour dans
  le meme ticket, la premiere creation de boutique ou de compte apres deploiement echoue en
  base (violation de contrainte), et `AdminInitializer` empeche meme le demarrage de
  l'application sur une base fraiche (aucune ligne `organizations` pre-existante hors celle
  inseree par la migration elle-meme). A couvrir explicitement par la spec et les tests du
  codeur.
- **Aucun test automatise n'exerce Flyway aujourd'hui** : la suite de tests backend est
  entierement composee de tests unitaires Mockito (`@ExtendWith(MockitoExtension.class)`,
  confirme par lecture — aucun `@SpringBootTest`/`@DataJpaTest` trouve dans
  `backend/src/test/java`). Le critere d'acceptation « la migration s'applique proprement sur
  une base existante » ne peut donc pas etre verifie par `mvn test` seul : il faut une
  verification manuelle contre une base Postgres reelle (ex. `docker-compose up` avec les
  migrations V1 a V12 deja appliquees, puis V13). A signaler explicitement dans la spec pour
  que le codeur/reviewer ne s'appuie pas a tort sur le vert de la suite unitaire comme preuve
  suffisante.
- **Ordre des migrations** : V8 est absente de la sequence existante (V1-V7, V9-V12) — trou
  deja present avant ce ticket, sans impact connu (Flyway tolere les trous dans la sequence
  tant qu'elle est strictement croissante). La nouvelle migration doit s'appeler `V13__*.sql`.
- **`AdminInitializer` s'execute a `@Order(1)`** avant tout seed de donnees de demo
  (`DemoDataSeeder`) : si la resolution de l'organisation par defaut echoue (base fraiche sans
  migration appliquee, ou requete mal ecrite), le demarrage de l'application est bloque des le
  premier `ApplicationRunner`. A tester explicitement en conditions de demarrage a froid, pas
  seulement en test unitaire isole du service.
- **`Shop.organization` et `User.organization` sont des relations `@ManyToOne` obligatoires
  (non nullable) au niveau JPA** pour rester coherentes avec la contrainte NOT NULL en base ;
  tout code de test qui construit `Shop.builder()...build()` ou `User.builder()...build()`
  sans `organization` continue de compiler (Lombok ne valide rien a la construction) mais
  echouerait si jamais persiste reellement en base sans le champ — situation deja evitee
  puisque la suite de tests actuelle est entierement mockee (cf. risque ci-dessus), donc sans
  impact immediat, mais a garder en tete si un futur test d'integration est introduit.

## Hors perimetre

- Toute logique de scoping/filtrage par organisation (repositories, `Specifications`,
  `CurrentShopContext`) : ticket de suivi #35.
- Endpoints, DTO, service ou ecran de gestion des organisations (creation, edition,
  facturation) : aucun besoin identifie par ce ticket, `organizations` n'a qu'une seule ligne
  utile tant que le SaaS mutualise n'existe pas.
- Postgres Row-Level Security et variable de session `app.current_org_id` : ticket de suivi
  #7 de la chaine (design #25).
- Isolation du stockage fichiers, modele de plan par organisation, outillage de sauvegarde
  par tenant : tickets de suivi ulterieurs (design #25), non concernes par la fondation de
  donnees.
- Ajout d'une colonne `active`, de champs de facturation, ou de toute autre donnee
  speculative sur `organizations` non requise par les criteres d'acceptation de ce ticket.
