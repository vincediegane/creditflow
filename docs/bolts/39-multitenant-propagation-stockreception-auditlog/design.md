# Design — #39 Multi-tenant 6/10 — Propagation StockReception/AuditLog

## Approche

`StockReception` n'a pas de `shop`/`organization` directs : son organisation se deduit via
`lignes -> product -> shop -> organization` (une chaine plus longue que Customer/Product, qui
n'avaient qu'un saut jusqu'a `shop`). Le patron retenu est identique a #36 (jointure/sous-requete,
pas de denormalisation) applique a cette chaine : ajout d'une `Specification<StockReception>
inOrganization(Long organizationId)` reutilisant le meme mecanisme de sous-requete `EXISTS` sur
`StockReceptionLine` que `inShops` (au lieu d'un simple `.get("shop").get("organization").get("id")`
comme pour Product, impossible ici car `StockReception` n'a pas de `root.get("shop")`), combinee a
`inShops(accessibleShopIds())` dans `StockReceptionService.search`. `getEntity(id)` n'est pas
modifie : il charge sans filtre puis appelle deja `currentShopContext.assertAccessible(...)` sur
chaque ligne, un patron deja sur par construction (meme raisonnement que Customer/Product en #36).
Cote `AuditLogAccessGuard` et `ReminderService`, l'audit conclut qu'aucun changement de code n'est
necessaire (detaille en Decisions cles) : le prix de cette approche est de livrer un ticket presque
entierement documentaire sur ces deux fichiers, avec un seul changement de code reel cote
`StockReceptionSpecifications`/`StockReceptionService`.

## Fichiers/modules impactes

- `backend/src/main/java/com/creditflow/supplier/repository/StockReceptionSpecifications.java` --
  nouvelle `Specification<StockReception> inOrganization(Long organizationId)`, sous-requete `EXISTS`
  sur `StockReceptionLine` avec predicat `line.get("product").get("shop").get("organization")
  .get("id") = organizationId` (meme structure que `inShops`, un `get(...)` de plus dans la chaine).
- `backend/src/main/java/com/creditflow/supplier/service/StockReceptionService.java` -- `search`
  combine desormais `StockReceptionSpecifications.inOrganization(currentShopContext
  .currentOrganizationId())` en plus de `inShops(currentShopContext.accessibleShopIds())`. Aucun
  autre changement (`getEntity`, `receive` restent inchanges, voir Decisions cles).
- `backend/src/main/java/com/creditflow/audit/service/AuditLogAccessGuard.java` -- aucun changement
  de code. Note a documenter pour le spec-writer : le cas `CREDIT_SALE` delegue a
  `CreditSaleService.getEntity()`, deja sur par construction independamment de #37 (voir Decisions
  cles) ; il n'existe pas de cas `STOCK_RECEPTION` et aucun appelant (backend ou frontend) n'en
  emet -- rien a ajouter ici pour ce ticket.
- `backend/src/main/java/com/creditflow/notification/service/ReminderService.java` -- aucun
  changement de code (audit demande par le ticket, conclut deja sur par construction, voir Decisions
  cles).
- Tests : `backend/src/test/java/com/creditflow/supplier/repository/StockReceptionSpecificationsTest.java`
  (nouveau cas `inOrganization`, meme patron mock Root/Subquery/CriteriaBuilder que
  `inShopsFiltersOnLineProductShop` deja en place) ; `backend/src/test/java/com/creditflow/supplier/service/StockReceptionServiceTest.java`
  (verifier que `search` appelle bien `currentOrganizationId()` en plus de `accessibleShopIds()`,
  meme forme que `search_filtersOnAccessibleShops` deja en place). Pas de nouveau test necessaire
  pour `AuditLogAccessGuard`/`ReminderService` (aucun changement de code), sauf si le spec-writer
  souhaite un test de non-regression explicite documentant la conclusion de l'audit.

## Décisions clés

- Sous-requete `EXISTS` sur `StockReceptionLine` pour `inOrganization`, plutot qu'un `.get("shop")`
  direct comme `ProductSpecifications.inOrganization`/`CustomerSpecifications.inOrganization` :
  `StockReception` n'a pas de relation directe vers `Shop` (seulement via ses lignes -> `product` ->
  `shop`), et c'est deja le choix fait pour `inShops` dans ce meme fichier. Reutiliser exactement la
  meme structure de sous-requete (un seul `get(...)` de plus dans la chaine du predicat) minimise le
  risque de divergence de comportement entre les deux filtres qui doivent normalement restreindre le
  meme ensemble de lignes.
- `getEntity(id)` n'est pas modifie : verifie a l'audit (lecture directe du code, pas suppose) que
  `StockReceptionService.getEntity` charge la reception sans filtre puis appelle deja
  `currentShopContext.assertAccessible(line.getProduct().getShop().getId())` pour chaque ligne
  (boucle `forEach`, pas seulement la premiere). Ce patron est deja sur par construction pour la
  meme raison que Customer/Product en #36 : `assertAccessible` verifie l'appartenance a
  `accessibleShopIds()`, deja scope par organisation par transitivite (sous reserve du meme risque
  Shop/User documente en #36, hors perimetre ici aussi). Modifier `getEntity` ajouterait un controle
  redondant sans fermer de faille reelle, et le ticket decoupe explicitement filtrage explicite
  (specifications/recherche) d'un cote et audit de `getEntity`/`assertAccessible` de l'autre --
  meme lecture que #36.
- `receive()` n'est pas modifie : `targetShopId = currentShopContext.shopIdForCreation()` est deja
  scope par organisation par transitivite (meme argument), et la verification
  `product.getShop().getId().equals(targetShopId)` par ligne empeche deja de receptionner un
  produit hors de la boutique cible, donc hors de l'organisation cible. `Supplier` (via
  `supplierService.getEntity(request.supplierId())`) reste volontairement global -- documente
  explicitement dans `SupplierService` (commentaire javadoc du fichier, verifie a l'audit :
  "Les fournisseurs restent volontairement communs a toutes les boutiques ... table sans shop_id,
  ticket #8") -- donc hors perimetre de ce ticket, deja une decision produit anterieure et assumee,
  pas une fuite a corriger.
- `AuditLogAccessGuard.assertReadable` : verifie a l'audit (lecture directe du switch, pas suppose)
  qu'il n'existe aucun cas `STOCK_RECEPTION`. Recherche exhaustive sur le backend et le frontend :
  `AuditLogService.record(...)` n'est jamais appele avec `"STOCK_RECEPTION"` (les seuls types
  ecrits sont `"CUSTOMER"`, `"PRODUCT"`, `"CREDIT_SALE"`), et le type `AuditEntityType` cote
  frontend (`frontend/src/types.ts:512`) est litteralement `'CUSTOMER' | 'CREDIT_SALE' | 'PRODUCT'`
  -- aucune UI n'affiche d'historique d'audit pour une reception de stock. Le
  `default -> throw ResourceNotFoundException` deja en place couvre donc deja `"STOCK_RECEPTION"`
  de maniere sure (refus, pas de fuite). Aucune modification de ce fichier n'est necessaire pour ce
  ticket : ajouter un cas `STOCK_RECEPTION` serait ajouter une fonctionnalite (auditer les
  receptions de stock) non demandee par le ticket, qui porte sur la garde d'acces d'un chemin
  existant, pas sur l'extension du perimetre audite.
- `AuditLogAccessGuard` / cas `CREDIT_SALE` -- nuance demandee explicitement par le prompt : verifie
  par lecture directe de `CreditSaleService.getEntity` sur la branche courante (pre-#37, #37 n'est
  pas mergee ici) que la ligne `currentShopContext.assertAccessible(sale.getShop().getId())` est
  deja presente dans `getEntity`, independamment de tout changement de #37. `CreditSale` porte une
  relation directe `shop` (`@ManyToOne private Shop shop;`), et `CreditSaleService.create` garantit
  `sale.shop == customer.shop` a la creation (verification explicite
  `customer.getShop().getId().equals(targetShopId)`). La delegation de `AuditLogAccessGuard` a
  `creditSaleService.getEntity(entityId)` est donc deja sure par construction sur cette branche,
  avant le merge de #37 -- #37 ajoute un filtrage explicite par organisation sur les
  listes/recherches de `CreditSaleRepository` (defense en profondeur supplementaire, meme patron
  que #36), mais ne change rien a `getEntity`, qui s'appuyait deja sur `assertAccessible` avant #37.
  Consequence directe pour ce ticket : `AuditLogAccessGuard` n'a aucune dependance reelle et
  bloquante sur le merge de #37 pour rester correct -- seulement une dependance documentaire (le
  design de #37, une fois merge, devra confirmer que son propre audit aboutit a la meme conclusion
  sur `getEntity`, ce que ce document ne peut pas garantir a la place de #37).
- `ReminderService` : audit demande explicitement par le ticket, conclusion "aucun changement", pas
  "rien a verifier". Trois chemins identifies par lecture du code :
  1. `prepareForSale(saleId, ...)` charge la vente puis appelle immediatement
     `currentShopContext.assertAccessible(sale.getShop().getId())` -- deja sur, meme patron que
     `CreditSaleService.getEntity`.
  2. `prepareForCustomer(customerId, ...)` charge le client via `customerService.getEntity(customerId)`
     (deja sur par construction, #36), puis `saleRepository.findByCustomer(customerId)` sans filtre
     supplementaire -- verifie que ce n'est pas une fuite reelle : `CreditSale.customer` et
     `CreditSale.shop` sont toujours coherents (`create` refuse un client hors de la boutique
     cible), donc toutes les ventes d'un client donne appartiennent forcement a la meme boutique que
     ce client, deja verifiee accessible. Aucune fuite inter-organisation possible par ce chemin.
  3. `sendAll(...)` utilise `lateCustomerService.lateCustomers(currentShopContext.accessibleShopIds())`
     -- `LateCustomerService.lateCustomers` recoit `shopIds` en parametre et l'utilise directement
     dans `installmentRepository.findLateForShops(today, shopIds)`, sans requete non filtree
     supplementaire. Deja sur par transitivite (meme `accessibleShopIds()` que partout ailleurs).
  Aucun de ces trois chemins n'a de requete large ignorant `shopIds`/`assertAccessible` : le
  changement de #39 sur `ReminderService` est donc purement documentaire (ce paragraphe), pas un
  changement de fichier.

## Risques / points d'attention

- Meme risque residuel que #36/#37/#38, non specifique a ce ticket : toute la chaine de garanties
  (`getEntity`, `assertAccessible`, `inOrganization`, l'audit de `ReminderService`) repose sur le
  fait qu'`accessibleShopIds()` ne contient jamais un `shopId` d'une autre organisation. Cette
  garantie appartient a `UserService`/`ShopService` (#34/#35), pas a ce ticket -- deja documente
  comme faille residuelle en #36, non corrigee sur cette branche a la date de ce design. A rappeler,
  pas a corriger ici.
- Sous-requete `EXISTS` supplementaire dans `inOrganization` : `StockReceptionService.search`
  combinera deux sous-requetes `EXISTS` sur `StockReceptionLine` (une pour `inShops`, une pour
  `inOrganization`) dans la meme requete -- redondant fonctionnellement (`inOrganization` est
  logiquement implique par `inShops` tant que `accessibleShopIds()` n'est pas corrompu, cf. risque
  precedent) mais coherent avec le patron deja retenu en #36/#37/#38 (defense en profondeur ajoutee
  systematiquement aux listes, meme raisonnement applicable ici). A signaler au spec-writer comme
  cout de performance mineur (deux `EXISTS` au lieu d'un), acceptable au vu du volume de donnees
  d'une PME (perimetre documente en `docs/bolts/25-architecture-multi-tenant-saas/design.md`).
- Absence d'infrastructure `@DataJpaTest`/base reelle (verifie, comme en #36) : les tests
  `StockReceptionSpecifications` restent des tests unitaires avec mocks Root/CriteriaBuilder/
  Subquery, pas des tests d'integration -- a fixer explicitement dans la spec pour ne pas exiger une
  infrastructure de test nouvelle hors perimetre.
- Non-regression mono-tenant : une seule organisation en base implique que
  `currentOrganizationId()` est constant pour tous les utilisateurs et que `inOrganization(...)` ne
  restreint jamais l'ensemble de resultats deja retourne par `inShops(...)` -- a couvrir par un test
  explicite (meme raisonnement que #36), notamment sur
  `StockReceptionServiceTest.search_filtersOnAccessibleShops` qui devra continuer a passer sans
  changement de resultat.
- Dependance a #37/#38 : confirme par lecture directe du code de la branche courante qu'aucune
  classe touchee par ce ticket (`StockReceptionService`, `StockReceptionSpecifications`,
  `AuditLogAccessGuard`, `ReminderService`) n'importe ou n'utilise de methode/parametre
  `organizationId` provenant de `CreditSaleRepository`/`InstallmentRepository`/`CreditSaleService`
  ou de `PaymentService`/`PaymentRepository` (modules de #37/#38, non merges sur cette branche). Le
  seul lien avec #37 est documentaire (`AuditLogAccessGuard` delegue a
  `CreditSaleService.getEntity()`, deja sur independamment de #37, voir Decisions cles) : ce ticket
  peut donc etre code, teste et revu integralement sans dependre du merge de #37/#38.

## Hors périmètre

- Ajout d'un cas `STOCK_RECEPTION` dans `AuditLogAccessGuard`/`AuditLogService`/`AuditEntityType`
  (frontend) pour permettre d'auditer les receptions de stock -- fonctionnalite non demandee par ce
  ticket, qui porte sur la garde d'un chemin existant, pas sur l'extension du perimetre audite.
- Scoping par organisation de `Supplier`/`SupplierService`/`SupplierSpecifications` -- decision
  produit deja prise et documentee (fournisseurs volontairement globaux, ticket #8), pas remise en
  cause ici.
- Correction de la faille residuelle Shop/User (assignation de boutique sans verification
  d'organisation) deja identifiee et documentee en #36 -- toujours hors perimetre, appartient a un
  ticket de correction dedie sur #34/#35.
- Tout changement de code dans `ReminderService`/`AuditLogAccessGuard` au-dela de la documentation
  de l'audit -- l'audit du ticket conclut explicitement a l'absence de fuite sur ces deux fichiers.
- Merge ou modification des branches
  `origin/bolt/issue-37-multitenant-propagation-creditsale-installment` et
  `origin/bolt/issue-38-multitenant-propagation-payment` -- consultees uniquement pour le patron,
  non applicables tel quel sur cette branche (voir Risques).
