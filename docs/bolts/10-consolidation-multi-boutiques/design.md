# Design — #10 Consolidation multi-boutiques

## Constat sur l'ampleur

Le schéma actuel n'a strictement aucune notion de boutique : aucune colonne
`shop_id`, aucune spécification filtrée par boutique, et surtout une bonne
partie des requêtes utilisées par le dashboard et les rapports **contournent
déjà les `*Specifications`** via des méthodes `@Query`/dérivées directes sur
les repositories (`CreditSaleRepository.countByStatus`,
`CreditSaleRepository.sumRemainingByStatus`, `CreditSaleRepository.findAllDetailed`,
`PaymentRepository.sumBetween`/`countBetween`/`findBetween`,
`InstallmentRepository.countLate`/`sumLateAmount`/`findUpcoming`/`findLate`).
`DashboardService` et `ReportService` s'appuient uniquement sur ces méthodes,
jamais sur `Specification`. Le ticket est donc plus large qu'« ajouter une
colonne + un filtre » : il faut aussi convertir ces agrégats en variantes
« filtrables par boutique ». J'ai exploré Customer/Product/CreditSale/Payment/
Installment, User/Role/SecurityConfig, Dashboard, les migrations Flyway
existantes (V1→V9, toutes déjà mergées) et le frontend (AuthContext,
AppLayout, ReportsPage) avant de figer l'approche ci-dessous, qui **circonscrit
volontairement le périmètre** conformément à l'autorisation du ticket (P2,
« à livrer seulement si un client se présente »).

## Approche

Filtrage transverse résolu **au niveau service**, pas au niveau des
contrôleurs REST : un composant `CurrentShopContext` calcule, pour
l'utilisateur authentifié, l'ensemble des `shop_id` accessibles (ses boutiques
assignées, ou « toutes » pour un ADMIN sans assignation explicite) et
l'injecte automatiquement dans les `Specifications`/requêtes existantes des
services Customer/Product/Sale/Payment/Installment. Un client mono-boutique
n'a donc **aucun changement d'API perceptible** : son unique boutique est
injectée silencieusement. Un gérant multi-boutiques peut restreindre l'un ou
l'autre endpoint (dashboard, rapports) à une boutique précise via un en-tête
`X-Shop-Id` optionnel, plutôt qu'un paramètre de requête à ajouter partout —
ce choix limite le changement à `api/client.ts` + un composant `ShopContext`
côté frontend, au prix de ne pas exposer le filtre boutique comme paramètre
d'URL explicite (moins RESTful, mais bien plus petit à coder et tester pour
environ quinze endpoints de liste). `shop_id` est ajouté en **NOT NULL** (pas
nullable) sur `customers`, `products`, `credit_sales` après rétro-remplissage
vers une boutique par défaut créée en migration — un `shop_id` nullable
aurait obligé à traiter « pas de boutique » comme un cas à filtrer
explicitement partout (risque de fuite de données si un COALESCE est
oublié) ; NOT NULL supprime cette classe de bug au prix d'une migration qui
doit être irréprochable sur le rétro-remplissage. `payments` et
`installments` ne reçoivent pas leur propre colonne `shop_id` (contrairement
au libellé exact du ticket) : ils sont filtrés par jointure vers
`credit_sales.shop_id`, ce qui évite une dénormalisation à synchroniser et
est cohérent avec le fait qu'un paiement n'existe jamais hors du contexte
d'une vente.

## Fichiers/modules impactés

Backend — nouveaux :
- `backend/src/main/resources/db/migration/V10__shops.sql` (prochaine version
  libre ; V8 n'existe sur aucune branche mergée, V9 est déjà sur master)
- `backend/src/main/java/com/creditflow/shop/domain/Shop.java`
- `backend/src/main/java/com/creditflow/shop/repository/ShopRepository.java`
- `backend/src/main/java/com/creditflow/shop/dto/ShopRequest.java` et `ShopResponse.java`
- `backend/src/main/java/com/creditflow/shop/service/ShopService.java`
- `backend/src/main/java/com/creditflow/shop/web/ShopController.java` (CRUD minimal, ADMIN only)
- `backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java`
  (résout `accessibleShopIds()` et `resolveFilter(Long requestedShopId)` à
  partir de `CurrentUser` et de l'en-tête `X-Shop-Id`, lève
  `BusinessRuleException` si la boutique demandée n'est pas accessible)

Backend — modifiés (ajout du filtrage par boutique, sans changer les
signatures des contrôleurs sauf dashboard et rapports) :
- `customer/domain/Customer.java`, `product/domain/Product.java`, `sale/domain/CreditSale.java` (relation ManyToOne vers Shop)
- `customer/repository/CustomerSpecifications.java`, `product/repository/ProductSpecifications.java`, `sale/repository/SaleSpecifications.java` (nouvelle spec inShops)
- `payment/repository/PaymentSpecifications.java`, `sale/repository/InstallmentSpecifications.java` (spec via jointure vers sale.shop.id)
- `customer/service/CustomerService.java`, `product/service/ProductService.java`, `sale/service/CreditSaleService.java`, `payment/service/PaymentService.java`, `sale/service/InstallmentService.java` (application du filtre et assignation du shop_id a la creation)
- `sale/repository/CreditSaleRepository.java`, `payment/repository/PaymentRepository.java`, `sale/repository/InstallmentRepository.java` (variantes filtrees par boutique pour les agregats count/sum utilises hors Specification)
- `notification/service/LateCustomerService.java` (retards filtres par boutique)
- `dashboard/service/DashboardService.java`, `dashboard/dto/DashboardResponse.java`, `dashboard/web/DashboardController.java` (parametre optionnel, vue consolidee si plusieurs boutiques accessibles et aucune selection)
- `report/service/ReportService.java`, `report/web/ReportController.java` (meme logique de resolution)
- `auth/domain/User.java` (relation ManyToMany vers Shop via table user_shops), `auth/repository/UserRepository.java`
- `auth/dto/UserRequest.java`, `UserResponse.java`, `AuthResponse.java`, `auth/service/AuthService.java`, `auth/service/UserService.java`, `auth/web/UserController.java` (assignation des boutiques a un utilisateur, boutiques accessibles renvoyees au login)
- `dataimport/service/LegacyImportParser.java`, `dataimport/web/ImportController.java` (les clients/ventes importes heritent de la boutique courante de l'utilisateur qui importe)
- Tests unitaires/integration existants de customer, product, sale, payment, dashboard, report (fixtures avec boutique par defaut) — churn important, a anticiper dans la spec.

Frontend :
- `frontend/src/types.ts` (type Shop, extension User/AuthResponse)
- `frontend/src/auth/AuthContext.tsx` (expose les boutiques accessibles)
- `frontend/src/context/ShopContext.tsx` (nouveau, selection persistee en localStorage, miroir du pattern TOKEN_KEY)
- `frontend/src/api/client.ts` (interceptor ajoutant l'en-tete X-Shop-Id si une boutique est selectionnee)
- `frontend/src/components/AppLayout.tsx` (selecteur de boutique dans la barre d'outils, visible seulement si plusieurs boutiques accessibles)
- `frontend/src/pages/DashboardPage.tsx`, `frontend/src/pages/ReportsPage.tsx` (indicateur vue consolidee / boutique active)
- `frontend/src/pages/UsersPage.tsx` (assignation des boutiques a un utilisateur, ADMIN only)
- `frontend/src/pages/ShopsPage.tsx` (nouveau, gestion minimale des boutiques, ADMIN only) + route dans `frontend/src/App.tsx` et entree NAV_ITEMS dans AppLayout.tsx

## Decisions cles

- **shop_id NOT NULL apres retro-remplissage**, plutot que nullable comme
  suggere en piste dans le ticket : elimine le risque de fuite de donnees
  par oubli de filtre explicite, au prix d'une migration de retro-remplissage
  qui doit s'executer dans une seule transaction avant la contrainte NOT
  NULL.
- **payments et installments sans colonne shop_id propre** : filtrage par
  jointure vers credit_sales.shop_id. Deviation assumee du libelle litteral
  du ticket (qui liste payments parmi les tables a modifier), justifiee par
  l'absence de risque d'incoherence (un paiement ne peut pas exister sans
  vente) et l'economie d'une colonne denormalisee a maintenir.
- **Filtrage injecte cote service, pas expose en parametre de requete** sur
  les listes standard (clients, produits, ventes, paiements, echeances) : un
  utilisateur ne voit jamais que ses boutiques accessibles, sans changement
  de signature d'API sur les endpoints de liste. Seuls le dashboard et les
  rapports exposent un choix explicite (une boutique vs consolide), via
  l'en-tete X-Shop-Id plutot qu'un query param — plus simple a brancher cote
  frontend (un seul interceptor) mais moins decouvrable/RESTful qu'un
  parametre shopId.
- **User et Shop en many-to-many** (table user_shops) pour porter le cas
  gerant multi-boutiques. Un ADMIN sans boutique assignee est traite comme
  ayant acces a toutes les boutiques (comportement de super-admin), ce qui
  preserve le compte admin bootstrap existant (AdminInitializer) sans
  migration de donnees supplementaire sur users.
- **Un produit appartient a une seule boutique** (catalogue non partage
  entre boutiques), plutot qu'un catalogue central avec stock/prix par
  boutique. C'est l'interpretation la plus simple de "colonne shop_id sur
  products" et elle evite une refonte du modele de stock (Product.stock,
  StockMovement, Supplier, StockReception du ticket 8 restent inchanges,
  implicitement scopes via le produit). Un catalogue partage multi-boutiques
  est une evolution distincte, plus lourde, a traiter seulement si demandee.
- **Unicite customers.phone / customers.cni_number et credit_sales.reference
  restent globales** (contraintes UNIQUE de V1 inchangees), pas recalculees
  par boutique. Le ticket ne demande pas de les assouplir ; les changer
  aurait un impact fonctionnel (un meme client pourrait exister deux fois
  avec le meme telephone dans deux boutiques) hors perimetre du ticket.
- **PenaltySettings reste un singleton global** (ligne unique id=1,
  backend/.../penalty/service/PenaltySettingsService.java) : pas de
  penalites par boutique dans cette iteration, non demande par les criteres
  d'acceptation.
- **app.shop.name et app.shop.currency (application.yml:83) restent des
  constantes globales de configuration**, pas des attributs par Shop. La
  devise reste unique pour toute l'installation ; l'entite Shop porte
  seulement name et active pour l'affichage et le filtrage.

## Risques / points d'attention

- **Ampleur des tests existants** : DashboardServiceTest, ReportServiceTest,
  et les tests de service Customer/Product/Sale/Payment/Installment
  s'appuient sur les repositories actuels sans notion de boutique ; ils
  devront tous etre adaptes pour injecter une boutique par defaut. C'est le
  plus gros poste de travail du chantier, a ne pas sous-estimer dans la spec.
- **Repositories a reecrire, pas seulement etendre** : les methodes
  d'agregation utilisees par DashboardService et ReportService
  (countByStatus, sumRemainingByStatus, sumBetween, countBetween, countLate,
  sumLateAmount, findAllDetailed, findBetween, findUpcoming, findLate)
  n'utilisent pas Specification : il faut soit les dupliquer avec une clause
  de filtrage par boutique, soit les remplacer integralement. Risque de
  regression silencieuse sur ces indicateurs si un seul de ces appels est
  oublie lors du filtrage.
- **Le ticket 9 (statistiques defaut / performance vendeur) n'est pas
  mergee** sur master a ce jour (branche
  bolt/issue-9-statistiques-defaut-performance-vendeur, modifie
  report/service/ReportService.java et frontend/src/pages/ReportsPage.tsx) :
  un conflit de merge est a prevoir entre ce chantier et le notre sur ces
  memes fichiers. Rien a faire maintenant, mais l'orchestrateur doit en
  tenir compte a la fusion finale de ce bolt.
- **Coherence boutique a la creation d'une vente** : CreditSale reference un
  Customer et un Product qui doivent appartenir a la meme boutique que la
  vente elle-meme ; sans validation explicite, il devient possible de creer
  une vente incoherente (client boutique A, produit boutique B). A valider
  dans CreditSaleService.create.
- **Utilisateur multi-boutiques sans boutique selectionnee** sur un endpoint
  de creation (client, produit, vente) : ambigu sur la boutique cible.
  Decision a documenter precisement dans la spec (rejet explicite avec
  message clair plutot que choix implicite silencieux).
- **Reprise de donnees (dataimport)** : les lignes legacy importees n'ont
  pas de notion de boutique dans le format actuel ; elles devront etre
  rattachees a la boutique courante de l'utilisateur qui lance l'import.
- **Contexte requete pour CurrentShopContext** : necessite un acces a
  HttpServletRequest (en-tete X-Shop-Id) hors du filtre JWT actuel
  (JwtAuthenticationFilter ne charge que username et role, pas les
  boutiques) — implique une requete DB supplementaire par appel pour
  resoudre les boutiques accessibles de l'utilisateur (UserRepository avec
  shops chargees), sans cache ; acceptable au vu du volume actuel, mais a
  surveiller si le nombre de boutiques par gerant grandit.

## Hors perimetre (cette iteration)

- Catalogue produit partage entre boutiques avec stock/prix differencies
  (le modele retenu : un produit appartient a une seule boutique).
- Penalites de retard configurables par boutique (PenaltySettings reste
  global).
- Devise/nom de boutique dans les templates de relance
  (app.reminder.default-template, placeholder boutique) : continue
  d'utiliser app.shop.name global, pas le nom de l'entite Shop selectionnee
  — incoherence mineure assumee, a corriger dans une iteration ulterieure
  si des clients multi-boutiques l'utilisent reellement.
- Reassignation d'une boutique a un client/produit/vente existant apres
  creation (pas d'UI ni d'endpoint pour deplacer un enregistrement d'une
  boutique a une autre) ; shop_id est fixe a la creation uniquement.
- Filtre boutique expose comme parametre d'URL explicite sur les listes
  standard (clients, produits, ventes, paiements, echeances) — le filtrage
  y est transparent (scoping automatique par utilisateur), non pilotable
  finement page par page au-dela du selecteur global.
- Statistiques consolidees avec ventilation par boutique dans un meme
  tableau (ex. barres comparatives boutique A vs boutique B) : la vue
  consolidee de cette iteration agrege un total global, elle ne decompose
  pas par boutique dans l'UI.
