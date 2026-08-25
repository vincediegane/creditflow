# Design — #25 Architecture multi-tenant SaaS (note d'architecture)

## Approche

Ce ticket est un chantier d'architecture de fond, explicitement qualifie par son propre texte
de prealable (« ticket a recadrer en spec technique une fois ce choix tranche »), pas une
fonctionnalite bornee. Le livrable de ce bolt est donc cette note elle-meme : elle tranche la
strategie d'isolation multi-tenant (row-level tenant_id avec Postgres RLS en defense en
profondeur, cf. section dediee) et recommande explicitement de ne livrer aucun code dans ce
ticket (voir « Recommandation de perimetre »), au profit de tickets de suivi correctement
scopes. Le compromis assume : un cycle en deux temps (decision, puis implementation etalee sur
plusieurs tickets) ralentit l'obtention d'un systeme multi-tenant fonctionnel par rapport a une
tentative en un seul bolt, mais evite le risque contraire — une migration de donnees a moitie
faite ou une regression de securite (fuite inter-organisation) introduite par un pipeline
automatise codeur→reviewer en une seule passe sur un changement qui touche une dizaine de
modules et le modele de donnees de production. Le prix : ce ticket ne produit, a l'issue de ce
bolt, aucune valeur fonctionnelle nouvelle pour un client — seulement une decision documentee et
un plan d'execution.

## Recommandation de perimetre pour ce ticket

**Recommandation : zero ligne de code dans ce bolt.** Le seul livrable est
`docs/bolts/25-architecture-multi-tenant-saas/design.md` (ce fichier). Aucun fichier source
(`backend/`, `frontend/`, migrations Flyway, `docker-compose.yml`, `.env*`) n'est cree ni modifie.

Justification :
- Meme la version « minimale » evoquee dans le brief (entite `Organization` seule, sans migration
  de donnees, derriere un flag desactive par defaut) implique une migration Flyway sur une base de
  production existante (`shops`, potentiellement `users`), une nouvelle entite JPA avec ses
  relations, et un risque de regression sur `V10__shops.sql`/`CurrentShopContext` que le reviewer
  d'un seul bolt automatise n'a pas le temps de challenger serieusement — pour un gain nul tant
  que rien ne consomme cette entite.
- Le critere d'acceptation n°1 (« une note d'architecture documente et tranche la strategie
  d'isolation ») est le seul que ce ticket peut raisonnablement satisfaire seul. Les criteres 2 a 4
  (aucune fuite inter-organisation, `ADMIN` scope, instances existantes non cassees) ne peuvent pas
  etre verifies sereinement sans l'implementation complete du scoping — les livrer partiellement
  creerait une fausse impression de securite (une entite `Organization` qui existe mais qu'aucune
  requete ne filtre est pire que son absence, car elle laisse croire que le sujet est traite).
- Priorite P2, aucun client identifie qui le rend bloquant (dixit le ticket) : rien ne justifie de
  prendre le risque d'un chantier de plusieurs semaines compresse dans un seul cycle
  codeur→reviewer.

Tickets de suivi a creer (scope precis, a traiter dans cet ordre — chacun borne pour un seul
cycle bolt) :

1. **Fondation de donnees — entite `Organization`, sans changement de comportement.**
   Migration Flyway ajoutant la table `organizations` et une colonne `organization_id` (nullable
   dans un premier temps, ou NOT NULL avec backfill d'une organisation « defaut » unique — a
   trancher dans la spec de ce ticket) sur `shops`. Entite JPA `Organization`. Aucune requete
   existante n'est modifiee, `CurrentShopContext` inchange, `Role.ADMIN` reste global. Objectif :
   poser le socle de donnees sans toucher a la logique d'acces, testable independamment.
2. **Scoping `ADMIN` par organisation + `CurrentShopContext`.**
   `ShopRepository.findAllByActiveTrueOrderByNameAsc()` remplace par une variante filtree par
   `organization_id` ; `CurrentShopContext.accessibleShopsOf` scope la branche `ADMIN`. Tests de
   non-regression explicites : une instance mono-tenant (une seule organisation en base) doit
   observer un comportement strictement identique a aujourd'hui.
3. **Propagation aux repositories/`Specifications` metier restants** (`Customer`, `Product`,
   `CreditSale`, `Payment`, `Installment`, `StockReception`, `AuditLog`) et audit des methodes qui
   filtrent aujourd'hui uniquement par `customerId`/`saleId` en s'appuyant sur un
   `assertAccessible` en amont (ex. `PaymentRepository.findByCustomer`,
   `CreditSaleRepository.sumRemainingByCustomer`) — ces chemins doivent rester surs une fois
   `assertAccessible` scope par organisation. Probablement a re-decouper par module tant le
   perimetre (une dizaine de repositories) depasse un seul bolt.
4. **Defense en profondeur base de donnees — Postgres Row-Level Security.**
   Policies RLS sur les tables metier, activees via une variable de session
   (`SET app.current_org_id`) positionnee en debut de requete (filtre servlet ou intercepteur
   Hibernate), pour que l'isolation ne repose pas uniquement sur le code applicatif.
5. **Isolation du stockage fichiers.**
   `FileStorageService` (dossiers scopes par organisation), remplacement de l'exposition statique
   publique actuelle (`WebConfig` + `/uploads/**` en `permitAll` dans `SecurityConfig`) par un
   endpoint authentifie qui verifie l'appartenance du fichier a une organisation accessible.
6. **Modele de plan par tenant en base.**
   Table `organization_plan` (ou equivalent), migration du gating actuel par variables
   d'environnement (`AppProperties.Plan`, #24) vers une lecture par tenant, en conservant
   `AppProperties.Plan` comme valeur par defaut/fallback pour les instances mono-tenant qui ne
   passent pas par cette table.
7. **Strategie de bascule / coexistence + outillage d'exploitation par tenant.**
   Decision et outillage pour les instances mono-tenant existantes (migration vers le SaaS
   mutualise, ou coexistence durable) ; adaptation de `scripts/backup-loop.sh` (aujourd'hui un
   `pg_dump` de toute la base) pour permettre un export/suppression par organisation, requis des
   que plusieurs organisations partagent une base.

## Note d'architecture — strategie d'isolation retenue

**Contexte de deploiement actuel** (verifie dans le repo) : une instance Docker Compose isolee
par client (`docker-compose.yml`), une base PostgreSQL dediee par instance, migrations Flyway
(`backend/src/main/resources/db/migration/V1`...`V12`), sauvegarde par `pg_dump` complet de la base
(`scripts/backup-loop.sh`). C'est deja un modele « DB-per-tenant » — mais realise au niveau infra
(un conteneur/une base par client), pas au niveau applicatif : le code lui-meme ne sait pas ce
qu'est un tenant, il n'a jamais eu besoin de le savoir tant qu'une base ne contient qu'un client.

**Options evaluees :**

| Option | Cout operationnel | Complexite applicative | Isolation | Compatible avec l'existant |
|---|---|---|---|---|
| **DB-per-tenant** (generaliser le modele actuel) | Eleve et croissant lineairement avec le nombre de clients (une base, des migrations, une sauvegarde a orchestrer par client) — c'est exactement le cout que le ticket veut reduire | Nulle : le code applicatif reste inchange | Maximale (isolation physique) | Totale — c'est deja le modele en place |
| **Schema-per-tenant** (un schema Postgres par organisation dans une base partagee) | Moyen : une seule base a sauvegarder/administrer, mais le nombre de schemas croit avec les clients et Flyway doit rejouer les migrations par schema | Moyenne : routage de schema par requete (`SET search_path`), toujours pas de risque de fuite par requete mal filtree mais risque de fuite si le routage de schema echoue | Forte | Migration progressive possible mais outillage a construire |
| **Row-level `tenant_id`** (une colonne, des filtres applicatifs + RLS) | Faible et ne croit pas avec le nombre de clients : une seule base, un seul jeu de migrations, une seule sauvegarde | Elevee : chaque requete, chaque `Specification`, chaque service doit filtrer par tenant — un oubli est une fuite de donnees silencieuse | Depend entierement de la rigueur d'execution, a moins de la doubler d'une garde base de donnees (RLS) | Migration incrementale la plus simple a river sur le code existant (le pattern `shop_id` deja en place — `customers.shop_id`, `products.shop_id`, `credit_sales.shop_id`, cf. `V10__shops.sql` — est directement transposable a `organization_id`) |

**Decision retenue : row-level `tenant_id` (`organization_id`), double de policies Postgres Row-
Level Security en defense en profondeur.**

Justification :
- C'est l'option la moins chere a exploiter a grande echelle (une seule base, un seul pipeline
  Flyway, une seule instance applicative pour N clients), ce qui est l'objectif explicite du
  ticket (« reduire le cout d'exploitation... par rapport au modele actuel »). Schema-per-tenant et
  DB-per-tenant reproduisent, a des degres divers, le probleme que ce ticket veut resoudre.
- Le code existant filtre deja systematiquement par `shop.id` dans les repositories/`Specifications`
  (`CustomerSpecifications.inShops`, `ProductRepository.quickSearch`,
  `CreditSaleRepository.countByStatusAndShop_IdIn`, `PaymentRepository.sumBetweenForShops`, etc. —
  25 fichiers backend referencent `shopId`) : le pattern « filtrer par identifiant de portee dans
  chaque requete » est deja la convention du repo pour `Shop`. Ajouter `organization_id`
  au-dessus suit exactement la meme discipline, avec `Organization` comme parent de `Shop` (comme
  `Shop` est aujourd'hui parent de `Customer`/`Product`/`CreditSale`).
- Le prix assume — et c'est le point le plus important de cette note — est que l'isolation
  row-level ne vaut que ce que vaut la discipline de code : l'audit du repo montre que plusieurs
  requetes ne filtrent **pas** directement par `shop.id` mais par `customerId`/`saleId`, en
  s'appuyant sur un `CurrentShopContext.assertAccessible(...)` deja execute en amont dans le
  service appelant (ex. `PaymentRepository.findByCustomer`, `findBySale`,
  `CreditSaleRepository.sumTotalPriceByCustomer`, `sumRemainingByCustomer`,
  `AuditLogAccessGuard.assertReadable` qui delegue a `getEntity()` des services metier). C'est
  exactement la classe de bug que ce ticket decrit pour `ADMIN` (`ShopRepository.
  findAllByActiveTrueOrderByNameAsc()` sans filtre) : un point d'entree non garde suffit a exposer
  les donnees d'une autre organisation. Une isolation row-level fiable exige donc un audit
  exhaustif de **tous** les points d'entree (pas seulement des requetes qui referencent `shop_id`),
  ce qui est precisement le travail du ticket de suivi n°3.
- Recommandation : ne pas se reposer uniquement sur le filtrage applicatif. Ajouter des policies
  PostgreSQL Row-Level Security sur les tables metier (`shops`, `customers`, `products`,
  `credit_sales`, `payments`, `installments`, etc.), activees via une variable de session
  (`SET app.current_org_id = ...`) positionnee en tout debut de requete. Cout d'implementation
  non negligeable (ticket de suivi n°4) mais c'est la seule garde qui protege contre un oubli de
  filtre dans une `Specification` ou une requete JPQL ecrite manuellement — la classe de bug la
  plus probable et la plus difficile a detecter en revue de code humaine, a fortiori en revue
  automatisee.
- JWT/claims : le JWT actuel (`JwtService.generateToken`) ne porte que `username` et `role` ; le
  tenant de l'utilisateur courant est resolu a chaque requete par rechargement depuis la base
  (`CurrentShopContext.currentUser()` via `UserRepository.findByUsernameIgnoreCase`, et
  `AppUserDetailsService` cote `JwtAuthenticationFilter`) — jamais fait confiance au contenu du
  JWT au-dela du nom d'utilisateur. Le scoping par organisation peut suivre exactement le meme
  patron (charger `User.organization` a chaque requete) sans qu'un claim de tenant dans le JWT
  soit strictement necessaire a la correction du systeme. Un claim `org_id` peut neanmoins etre
  ajoute en defense en profondeur secondaire (detecter un jeton emis avant un changement
  d'organisation d'un utilisateur), mais ce n'est pas le mecanisme d'application principal — a
  documenter explicitement dans la spec du ticket de suivi n°2 pour eviter la confusion entre
  « porteur d'information » et « source de verite ».

**Coexistence avec le modele actuel (instances mono-tenant existantes) :** la decision row-level
n'impose pas de migrer les instances existantes vers le SaaS mutualise. Une instance mono-tenant
reste une base avec une seule ligne `organizations` (creee par la migration de fondation, ticket de
suivi n°1) — le comportement observable est strictement identique a aujourd'hui puisque tout
filtrage par `organization_id` sur une base a une seule organisation ne restreint jamais rien.
Le SaaS mutualise (plusieurs organisations dans une meme base) devient un second mode de
deploiement possible, pas un remplacement obligatoire du premier — coherent avec le critere
d'acceptation « les instances single-tenant existantes ne sont pas cassees ».

**Evolution d'`AppProperties.Plan` (#24) vers un modele par tenant :** aujourd'hui la formule est
une configuration par instance (`app.plan.multi-shop`, `app.plan.whatsapp-auto`, variables
d'environnement `PLAN_MULTI_SHOP`/`PLAN_WHATSAPP_AUTO`, validee au demarrage par
`PlanConfigValidator`). Dans une base mutualisee, la formule doit devenir un attribut de
l'organisation en base (table dediee, ticket de suivi n°6), resolue par requete au lieu d'etre
figee au demarrage du conteneur — sauf pour `whatsappAuto`, qui restera necessairement une
contrainte au niveau de l'instance tant que le canal WhatsApp reste selectionne par bean Spring
(`@ConditionalOnProperty` sur `app.notification.channel`, une seule valeur par processus JVM) :
une base mutualisee ne peut pas avoir un sous-ensemble d'organisations avec WhatsApp automatique
et d'autres non, sans revoir aussi l'architecture d'envoi de notifications (canal resolu par
organisation a l'execution plutot que par bean unique) — point a traiter explicitement dans la
spec du ticket de suivi n°6, pas suppose resolu par la seule table de plan.

## Fichiers/modules impactes

Pour ce ticket : uniquement `docs/bolts/25-architecture-multi-tenant-saas/design.md` (ce fichier),
nouveau. Aucun autre fichier cree ou modifie.

Inventaire (pour information, a charge des tickets de suivi listes ci-dessus — ne pas traiter ici) :
- `backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java` — scoping `ADMIN`.
- `backend/src/main/java/com/creditflow/auth/domain/User.java`,
  `backend/src/main/java/com/creditflow/shop/domain/Shop.java` — nouvelle relation vers
  `Organization`.
- `backend/src/main/java/com/creditflow/shop/repository/ShopRepository.java` — requetes scopees
  par organisation.
- Repositories/`Specifications` metier : `customer/repository/CustomerSpecifications.java`,
  `customer/repository/CustomerRepository.java`, `product/repository/ProductRepository.java`,
  `product/repository/ProductSpecifications.java`, `sale/repository/CreditSaleRepository.java`,
  `sale/repository/SaleSpecifications.java`, `sale/repository/InstallmentRepository.java`,
  `sale/repository/InstallmentSpecifications.java`, `payment/repository/PaymentRepository.java`,
  `payment/repository/PaymentSpecifications.java`,
  `supplier/repository/StockReceptionSpecifications.java`.
- Services qui appellent `CurrentShopContext.assertAccessible` :
  `customer/service/CustomerService.java`, `payment/service/PaymentService.java`,
  `sale/service/InstallmentService.java`, `sale/service/CreditSaleService.java`,
  `supplier/service/StockReceptionService.java`,
  `notification/service/ReminderService.java`, `product/service/ProductService.java`,
  `audit/service/AuditLogAccessGuard.java`.
- `backend/src/main/java/com/creditflow/common/storage/FileStorageService.java`,
  `backend/src/main/java/com/creditflow/config/WebConfig.java`,
  `backend/src/main/java/com/creditflow/config/SecurityConfig.java` (`/uploads/**` public) —
  isolation du stockage fichiers.
- `backend/src/main/java/com/creditflow/auth/security/JwtService.java`,
  `JwtAuthenticationFilter.java` — claim de tenant optionnel (defense en profondeur secondaire).
- `backend/src/main/java/com/creditflow/config/AppProperties.java`,
  `backend/src/main/java/com/creditflow/config/PlanConfigValidator.java` — evolution du plan par
  tenant.
- `backend/src/main/resources/db/migration/` — nouvelles migrations Flyway (`Organization`,
  `organization_id`, policies RLS).
- `scripts/backup-loop.sh` — export/suppression par tenant.
- Frontend : aucun impact direct identifie pour la note elle-meme ; les tickets de suivi
  toucheront potentiellement `frontend/src/auth/AuthContext.tsx` (selecteur de boutique deja
  scope cote backend, pas de changement structurel attendu cote UI pour le row-level tenant_id).

## Decisions cles

- **Strategie d'isolation : row-level `tenant_id` (`organization_id`) + Postgres RLS en defense en
  profondeur**, plutot que schema-per-tenant ou DB-per-tenant. Detail et justification en section
  dediee ci-dessus.
- **Livrable de ce ticket limite a la note d'architecture, zero code.** Detail et justification en
  section « Recommandation de perimetre » ci-dessus.
- **Coexistence durable** des instances mono-tenant existantes (une organisation par base) et du
  futur mode SaaS mutualise, plutot qu'une migration forcee : aucune regression fonctionnelle sur
  le parc existant, le SaaS mutualise est un mode de deploiement additionnel.
- **Le JWT n'est pas la source de verite du tenant** : le tenant de l'utilisateur courant continue
  d'etre resolu par rechargement depuis la base a chaque requete (patron deja en place pour
  `role`/`shops` via `CurrentShopContext`/`AppUserDetailsService`), pas depuis un claim JWT. Un
  claim `org_id` est envisageable en defense en profondeur secondaire seulement.
- **`app.plan.whatsapp-auto` reste une contrainte au niveau instance**, pas un attribut par
  organisation, tant que le canal de notification est selectionne par bean Spring unique au
  demarrage — la mutualisation complete de cet aspect est explicitement hors perimetre des
  tickets de suivi listes, a traiter separement si elle devient necessaire.

## Risques / points d'attention

- **Classe de bug principale a anticiper pour les tickets de suivi** : des requetes qui filtrent
  aujourd'hui par `customerId`/`saleId` en s'appuyant sur un `assertAccessible` execute en amont
  (ex. `PaymentRepository.findByCustomer`, `findBySale`, `sumByCustomer`,
  `CreditSaleRepository.sumTotalPriceByCustomer`, `sumRemainingByCustomer`,
  `findByCustomer`) ne filtrent *pas* directement par `shop_id`/`organization_id`. Le scoping par
  organisation doit couvrir ces gardes en amont autant que les filtres directs, sinon
  l'isolation a des trous invisibles aux tests qui ne couvrent que les requetes filtrant
  explicitement par identifiant de portee.
- **Le stockage fichiers n'a aujourd'hui aucun controle d'acces applicatif** :
  `/uploads/**` est servi de facon statique et publique (`SecurityConfig.java`, dans les
  `PUBLIC_ENDPOINTS`), la seule protection est l'imprevisibilite du nom de fichier (UUID). C'est un
  risque preexistant, independant de ce ticket, mais qui devient un vrai risque de fuite
  inter-organisation des qu'une base mutualisee expose plusieurs clients derriere la meme URL de
  base — a traiter explicitement dans le ticket de suivi n°5, pas comme un simple ajout de
  filtrage applicatif.
- **`scripts/backup-loop.sh` fait un `pg_dump` complet de la base**, sans notion de tenant. En mode
  SaaS mutualise, ceci empeche toute sauvegarde, export ou suppression ciblee d'un seul client
  (obligation contractuelle ou reglementaire potentielle) sans outillage dedie — a traiter dans le
  ticket de suivi n°7 avant d'onboarder un premier client reel en mode mutualise.
- **RLS Postgres a un cout de test** : les policies RLS changent le comportement selon la session
  applicative (pool de connexions, variable de session `SET app.current_org_id`) — le pool de
  connexions JPA/Hibernate doit garantir que cette variable est repositionnee a chaque emprunt de
  connexion, sinon une connexion reutilisee sans re-`SET` fuiterait silencieusement les donnees du
  tenant precedent. Point de vigilance explicite pour le ticket de suivi n°4.
- **Aucun code n'a ete ecrit ni modifie pour produire cette note** : les decisions ci-dessus sont
  fondees sur une lecture du code actuel (fichiers cites), pas sur un prototype. Le ticket de
  suivi n°1 (fondation de donnees) devra revalider en pratique que le pattern `shop_id` deja en
  place se transpose sans surprise a `organization_id`.

## Hors perimetre

- Toute creation, modification de fichier source, migration Flyway, ou modification de
  `docker-compose.yml`/`.env*` dans ce ticket — voir « Recommandation de perimetre ».
- L'implementation de l'entite `Organization`, du scoping `ADMIN`, de la propagation aux
  repositories, de la defense RLS, de l'isolation du stockage fichiers, du modele de plan par
  tenant, et de l'outillage de bascule/coexistence : tous delegues aux tickets de suivi listes
  ci-dessus.
- Le choix definitif entre backfill NOT NULL vs colonne nullable temporaire pour
  `shops.organization_id`, et le detail du schema de la table `organizations` (champs, contraintes)
  : a trancher dans la spec du ticket de suivi n°1, pas dans cette note d'architecture.
- La mutualisation complete du canal de notification WhatsApp par organisation (canal resolu a
  l'execution plutot que par bean Spring unique) : non demandee par ce ticket, non necessaire tant
  qu'`app.plan.whatsapp-auto` reste une contrainte au niveau instance (voir Decisions cles).
- Toute UI de gestion des organisations (creation, facturation, self-service evoque « a terme »
  par le ticket) : explicitement hors perimetre de ce chantier initial.
