# Design — #36 Multi-tenant 3/10 — Propagation Customer/Product

## Approche

`Customer` et `Product` n'ont pas de colonne `organization_id` directe : ils portent uniquement
`shop_id` (`@JoinColumn(name = "shop_id", nullable = false)`), et c'est `Shop` qui porte
`organization_id` depuis #34 (`Shop.organization`, `@ManyToOne(optional = false)`). L'audit du code
montre que `CustomerService`/`ProductService` scopent deja systematiquement leurs lectures par
`currentShopContext.accessibleShopIds()` (liste, elle-meme deja scopee par organisation pour un
`ADMIN` sans boutique assignee depuis #35 -- `ShopRepository.
findAllByActiveTrueAndOrganizationIdOrderByNameAsc`) et leur acces par id via `assertAccessible
(shop.getId())`. Le filtrage organisation est donc deja correct par transitivite pour la quasi
totalite des chemins, a deux exceptions pres trouvees a l'audit (detaillees en Decisions cles).

## Fichiers/modules impactes

- `backend/src/main/java/com/creditflow/customer/repository/CustomerSpecifications.java` --
  nouvelle `Specification<Customer> inOrganization(Long organizationId)` (jointure
  `shop.organization.id`).
- `backend/src/main/java/com/creditflow/customer/repository/CustomerRepository.java` -- parametre
  `organizationId` ajoute a la requete `@Query` `quickSearch` (`AND c.shop.organization.id =
  :organizationId`).
- `backend/src/main/java/com/creditflow/product/repository/ProductRepository.java` -- meme
  traitement pour `quickSearch` et `findAllCategories` ; remplacement de
  `findFirstByNameIgnoreCase(String)` (non scopee, cf. Decisions cles) par
  `findFirstByNameIgnoreCaseAndShop_Id(String name, Long shopId)`.
- `backend/src/main/java/com/creditflow/product/repository/ProductSpecifications.java` -- nouvelle
  `Specification<Product> inOrganization(Long organizationId)`, meme patron que Customer.
- `backend/src/main/java/com/creditflow/customer/service/CustomerService.java` -- `search`,
  `quickSearch`, `findAllForSelect` combinent desormais `inOrganization(currentShopContext.currentOrganizationId())`
  en plus de `inShops(...)`.
- `backend/src/main/java/com/creditflow/product/service/ProductService.java` -- meme changement
  pour `search`, `quickSearch`, `findAllForSelect`, `categories`.
- `backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java` --
  `resolveProduct`/`findProductByName` appellent la nouvelle methode de repository scopee par
  `targetShopId` au lieu de `findFirstByNameIgnoreCase(name)` (fix d'une fuite inter-organisation
  reelle trouvee a l'audit, voir Decisions cles).
- `backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java` -- ajout mineur et
  additif d'une methode `currentOrganizationId()` (retourne `currentUser().getOrganization().getId()`),
  aucune modification du comportement existant (`accessibleShopIds`, `assertAccessible`,
  `shopIdForCreation` inchanges -- ce fichier reste sous la responsabilite de #35, deja merge sur
  cette branche).
- Tests : `backend/src/test/java/com/creditflow/customer/repository/CustomerSpecificationsTest.java`,
  `backend/src/test/java/com/creditflow/product/repository/ProductSpecificationsTest.java` (nouveau
  cas `inOrganization`, patron mock Root/CriteriaBuilder deja en place) ;
  `backend/src/test/java/com/creditflow/customer/service/CustomerServiceTest.java`,
  `backend/src/test/java/com/creditflow/product/service/ProductServiceTest.java` (verifier que
  `search`/`quickSearch`/`findAllForSelect` appellent bien `currentOrganizationId()` et combinent le
  filtre) ; `backend/src/test/java/com/creditflow/dataimport/service/LegacyImportServiceTest.java`
  (deja existant) pour le cas "produit du meme nom existant dans une autre boutique/organisation
  n'est pas reutilise".

## Decisions cles

- Jointure (`shop.organization.id`) plutot que denormalisation (colonne `organization_id` directe
  plus migration Flyway sur `customers`/`products`). Detaille en Approche. Decision valable pour
  #37 (CreditSale a deja `shop` en relation directe, meme patron immediat) ; pour #38/#39 (Payment,
  StockReception n'ont pas de `shop` direct, seulement via `sale`/`customer`), le meme principe
  s'applique mais avec un saut de jointure supplementaire -- a documenter explicitement dans leurs
  specs respectives, pas ici.
- Le filtre `organization_id` explicite est ajoute aux methodes de repository/`Specifications` qui
  prennent une liste `shopIds` en parametre (recherche, listes), pas aux methodes `getEntity(id)`
  de `CustomerService`/`ProductService`. Ces dernieres chargent l'entite sans filtre puis appellent
  `currentShopContext.assertAccessible(shop.getId())` juste apres -- un patron deja correct par
  construction si `accessibleShopIds()` ne contient jamais un `shopId` d'une autre organisation.
  Cette garantie est portee par `CurrentShopContext`/`UserService`/`ShopService` (hors perimetre de
  ce ticket, voir Risques), pas par `Customer`/`Product`. Modifier `getEntity(id)` pour y ajouter un
  controle d'organisation redondant aurait duplique une logique qui n'appartient pas a ce module,
  sans fermer la vraie faille identifiee a l'audit (voir Risques). Ce choix suit litteralement le
  decoupage du ticket : "CustomerSpecifications, CustomerRepository... : filtrage explicite" d'un
  cote, "CustomerService... : audit des methodes qui s'appuient sur assertAccessible" de l'autre --
  l'audit conclut qu'aucun changement n'est necessaire cote `getEntity`, contrairement a
  `findFirstByNameIgnoreCase` (voir point suivant).
- `ProductRepository.findFirstByNameIgnoreCase(String)` est une fuite inter-organisation reelle,
  trouvee a l'audit, pas seulement theorique. Utilisee uniquement par
  `LegacyImportService.resolveProduct` lors de la reprise de donnees papier : si un produit du meme
  nom existe deja dans n'importe quelle boutique de n'importe quelle organisation, il est reutilise
  tel quel (son prix, son stock, son association shop restent ceux du produit trouve) au lieu d'en
  creer un nouveau pour la boutique cible -- contrairement a `resolveCustomer` juste au-dessus, qui
  verifie explicitement `customer.getShop().getId().equals(targetShopId)` avant reutilisation.
  Corrige en remplacant l'appel par une methode derivee scopee
  (`findFirstByNameIgnoreCaseAndShop_Id(name, targetShopId)`), symetrique au traitement deja
  applique a `Customer`.
- `currentOrganizationId()` est ajoute a `CurrentShopContext` plutot que recalcule dans chaque
  service. Alternative ecartee : faire porter l'id d'organisation par `Shop` uniquement et le
  deduire d'une des boutiques accessibles (`accessibleShopIds().get(0)` puis charger son shop) --
  rejetee car plus indirecte, coute une requete ou un aller-retour supplementaire, et echoue
  silencieusement pour un `ADMIN` sans boutique accessible (liste vide), alors que
  `currentOrganizationId()` reste toujours defini (l'utilisateur authentifie a toujours une
  organisation, `User.organization` est `@ManyToOne(optional = false)`).

## Risques / points d'attention

- Faille trouvee a l'audit, hors perimetre de ce ticket mais qui affaiblit la garantie sur laquelle
  `getEntity(id)` s'appuie : `UserService.resolveShops` (appelee par `UserService.create`/
  `updateShops`) accepte n'importe quel `shopId` transmis par un `ADMIN` sans jamais verifier qu'il
  appartient a l'organisation de cet `ADMIN` (`shopRepository.findById(shopId)`, aucun filtre
  d'organisation). De meme, `ShopService.list()`/`findById()`/`update()`/`delete()`
  (`ShopRepository.findAllByOrderByNameAsc()`, `findById()`) ne filtrent par organisation nulle
  part -- un `ADMIN` de n'importe quelle organisation peut aujourd'hui lister, consulter, modifier
  ou supprimer les boutiques de toutes les organisations, et un `ADMIN` peut assigner a un
  utilisateur de son organisation une boutique appartenant a une autre organisation. Si cela se
  produit, `accessibleShopIds()` de cet utilisateur contient un `shopId` etranger, et tous les
  filtres decrits dans ce design (y compris le nouveau `inOrganization`, qui verifie
  `shop.organization.id` -- coherent avec la boutique mal assignee, pas avec l'utilisateur) laissent
  passer les clients/produits de cette boutique. Ce n'est pas un defaut de ce ticket : c'est un
  defaut de Shop/User (territoire de #34/#35, deja merge sur cette branche) que l'audit de #36 a mis
  en evidence en creusant le chemin reel des donnees. A signaler explicitement pour un ticket de
  correction dedie (`ShopRepository`/`ShopService`/`UserService`) -- ne pas tenter de le corriger
  dans ce bolt, ce serait sortir des fichiers listes par le ticket #36 et melanger deux perimetres
  de revue. L'ajout de `inOrganization(...)` sur les listes reste une reelle defense en profondeur
  pour la plupart des cas : elle protege contre un `shopId` errant qui ne correspondrait a aucune
  boutique de l'organisation courante, mais ne peut rien contre une resolution
  d'`accessibleShopIds()` deja corrompue en amont par cette faille Shop/User.
- `Customer.phone` et `Customer.cniNumber` sont uniques globalement (`@Column(unique = true)`,
  aucune contrainte composite avec `shop`/`organization`) : `existsByPhone`/`existsByCniNumber`
  empechent deux organisations differentes d'enregistrer un client avec le meme numero, et la
  reponse booleenne constitue une fuite d'information mineure (revele qu'un numero est deja utilise
  quelque part sur l'instance, sans reveler par qui). Comportement deja present avant #36, pas un
  changement de ce ticket ; le corriger changerait une regle metier (scope de l'unicite) qui
  depasse la portee technique de ce ticket -- a documenter, pas a corriger ici.
- `CustomerRepository.countByShop_IdIn` et son equivalent cote `ProductRepository` (via
  `DashboardService`) ne recoivent pas le filtre `organizationId` supplementaire : ces methodes sont
  deja appelees uniquement avec des `shopIds` issus de `currentShopContext.accessibleShopIds()`/
  `resolveReadFilter()`, le meme raisonnement que pour `getEntity(id)` s'applique -- a n'ajouter que
  si le spec-writer juge la coherence de patron plus importante que le risque reel additionnel
  (faible, aucun `shopIds` fourni par un appelant externe non maitrise ici).
- Instance mono-tenant : une seule ligne `organizations`, donc `currentOrganizationId()` est
  constant pour tous les utilisateurs et `inOrganization(...)` ne restreint jamais rien --
  comparable au raisonnement deja valide pour le scoping ADMIN en #35. A verifier explicitement par
  un test de non-regression (recherche/liste avec une seule organisation retourne exactement les
  memes resultats qu'avant #36).
- Pas d'infrastructure de test `@DataJpaTest`/base reelle dans ce backend (verifie : aucun test du
  repo n'utilise `@DataJpaTest` ou une base embarquee) -- tous les tests `Specifications` mockent
  `Root`/`CriteriaBuilder`/`Predicate` (cf. `CustomerSpecificationsTest`), et tous les tests service
  mockent `CurrentShopContext`. Le test "acces inter-organisation" sur le modele des tests
  inter-boutique deja en place (`CustomerServiceTest.getEntityRejectsCustomerFromAnotherShop`,
  `CurrentShopContextTest.accessibleShopIdsForAdminWithoutAssignmentIsIsolatedByOrganization`) sera
  donc lui aussi un test unitaire avec mocks, pas un test d'integration bout-en-bout contre une
  vraie base Postgres -- la spec doit fixer ce niveau de couverture (coherent avec le reste du
  backend) et ne pas exiger une infrastructure `@DataJpaTest` nouvelle qui serait hors perimetre.

## Hors perimetre

- Correction de `UserService.resolveShops` et `ShopService` (absence de filtre par organisation lors
  de l'assignation boutique/utilisateur, et de la gestion des boutiques elle-meme) -- faille reelle
  documentee en Risques, mais appartient au module Shop/User (#34/#35), pas a Customer/Product. A
  signaler pour un ticket dedie.
- Changement du scope d'unicite de `Customer.phone`/`Customer.cniNumber` (globalement unique
  aujourd'hui) vers un scope par organisation -- decision produit, pas demandee par ce ticket.
- Toute denormalisation (ajout d'une colonne `organization_id` sur `customers`/`products`) et toute
  migration Flyway associee -- ecarte en Approche.
- Postgres Row-Level Security et toute autre defense en profondeur base de donnees -- traite par le
  ticket de suivi numero 7 liste dans `docs/bolts/25-architecture-multi-tenant-saas/design.md`.
- Propagation a CreditSale/Installment/Payment/StockReception/AuditLog -- tickets #37 a #39, qui
  devront adapter le meme principe (jointure vers Shop/Organization, pas de denormalisation) a des
  chaines de relations plus longues (Payment -> Sale -> Shop, pas de relation directe).
