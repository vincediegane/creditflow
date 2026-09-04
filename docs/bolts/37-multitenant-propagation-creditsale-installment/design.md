# Design — #37 Multi-tenant 4/10 — Propagation CreditSale/Installment

## Approche

`CreditSale` porte `shop` en relation directe (`@JoinColumn(name = "shop_id", nullable = false)`,
`CreditSale.java:60-61`), exactement comme `Customer`/`Product` en #36. `Installment` n'a pas de
`shop` propre : il ne porte que `sale` (`Installment.sale`), donc l'organisation se resout via
`sale.shop.organization.id` (un saut de jointure de plus que Customer/Product, anticipe dans le
design #36). Le patron retenu est identique a #36, sans reinvention : ajouter une
`Specification<T> inOrganization(Long organizationId)` dans `SaleSpecifications` et
`InstallmentSpecifications` (jointure, pas de denormalisation), la combiner avec `inShops(...)`
dans les methodes de recherche des services, et ajouter `AND ... organization.id = :organizationId`
aux `@Query` qui prennent deja une liste `shopIds`. L'audit confirme que le point d'attention du
ticket (`sumTotalPriceByCustomer`, `sumRemainingByCustomer`, et par extension
`countLateByCustomer`, `findByCustomer`) suit exactement le meme schema deja valide par #36 pour
`Customer`/`Product` : ces requetes filtrent uniquement par `customerId`/`saleId` sans filtre
organisation direct, mais leurs deux seuls appelants (`CustomerProfileService.profile`,
`ReminderService.prepareForCustomer`) appellent systematiquement
`customerService.findById`/`getEntity(customerId)` avant de les invoquer, ce qui leve deja
`ResourceNotFoundException` si le client n'est pas dans `accessibleShopIds()` (scope organisation
garanti depuis #35/#36). Aucun controleur n'expose ces methodes de repository ou
`CreditSaleService.findByCustomer` directement avec un `customerId` non valide en amont. Le prix de
cette approche : elle laisse ces requetes elles-memes ouvertes (sans filtre organisation propre),
dependantes de la discipline des deux appelants existants, un futur appelant qui oublierait de
valider le client en amont recreerait la faille. C'est le meme compromis que #36, documente comme
risque plutot que corrige, pour rester dans le perimetre du ticket.

## Fichiers/modules impactes

- `backend/src/main/java/com/creditflow/sale/repository/SaleSpecifications.java` -- nouvelle
  `Specification<CreditSale> inOrganization(Long organizationId)` (jointure
  `root.get("shop").get("organization").get("id")`), meme forme que
  `CustomerSpecifications.inOrganization`/`ProductSpecifications.inOrganization`.
- `backend/src/main/java/com/creditflow/sale/repository/InstallmentSpecifications.java` -- nouvelle
  `Specification<Installment> inOrganization(Long organizationId)`, jointure
  `root.get("sale").get("shop").get("organization").get("id")` (un niveau de plus que
  `SaleSpecifications`, cf. Decisions cles).
- `backend/src/main/java/com/creditflow/sale/repository/CreditSaleRepository.java` -- parametre
  `organizationId` ajoute aux `@Query` qui recoivent deja `shopIds` :
  `sumRemainingByStatusForShops`, `findAllDetailedForShops` (utilisees respectivement par
  `DashboardService` et `ReportService`, deja alimentees en `shopIds` scopes organisation via
  `resolveReadFilter()`/`accessibleShopIds()` -- l'ajout est une defense en profondeur, pas une
  correction de faille, symetrique au traitement de `CustomerRepository.quickSearch`/
  `ProductRepository.quickSearch` en #36). `countByStatusAndShop_IdIn`/`countByShop_IdIn` (derivees
  Spring Data, pas de `@Query`) laissees inchangees, meme raisonnement que
  `CustomerRepository.countByShop_IdIn` en #36 (risque documente, pas corrige).
  `sumTotalPriceByCustomer`/`sumRemainingByCustomer`/`findByCustomer` : aucun changement
  de signature (voir Approche/Decisions cles) -- seul un commentaire javadoc explicite le contrat
  (l'appelant doit avoir valide `customerId` via `assertAccessible` en amont).
- `backend/src/main/java/com/creditflow/sale/repository/InstallmentRepository.java` -- meme
  traitement : `organizationId` ajoute a `findUpcomingForShops`, `findLateForShops`,
  `countLateForShops`, `sumLateAmountForShops` (toutes deja alimentees en `shopIds`).
  `countLateByCustomer` inchangee (meme raisonnement que `sumTotalPriceByCustomer`, javadoc
  explicite).
- `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` -- `search` combine
  desormais `SaleSpecifications.inOrganization(currentShopContext.currentOrganizationId())` en plus
  de `inShops(...)`. Les appels a `sumRemainingByStatusForShops`/`findAllDetailedForShops` restent
  dans `DashboardService`/`ReportService`, pas dans ce fichier, mais leur signature change (voir
  plus bas).
- `backend/src/main/java/com/creditflow/sale/service/InstallmentService.java` -- `search` combine
  `InstallmentSpecifications.inOrganization(currentShopContext.currentOrganizationId())` avec
  `inShops(...)` ; `upcoming`, `late` passent `currentOrganizationId()` aux nouvelles requetes
  scopees. `upcomingForShops(days, shopIds)` (utilisee par le dashboard consolide) recoit aussi
  `currentOrganizationId()` -- coherent avec le reste, meme si `shopIds` provient deja de
  `resolveReadFilter()`.
- `backend/src/main/java/com/creditflow/dashboard/service/DashboardService.java` -- adapte les
  appels aux methodes de repository dont la signature gagne `organizationId`
  (`sumRemainingByStatusForShops`, `countLateForShops`, `sumLateAmountForShops`) en passant
  `currentShopContext.currentOrganizationId()`.
- `backend/src/main/java/com/creditflow/report/service/ReportService.java` -- meme adaptation pour
  les 3 appels a `findAllDetailedForShops` et l'appel a `findLateForShops`.
- `backend/src/main/java/com/creditflow/notification/service/LateCustomerService.java` -- adapte
  l'appel a `findLateForShops` (nouveau parametre `organizationId`).
- Tests : `backend/src/test/java/com/creditflow/sale/repository/SaleSpecificationsTest.java`,
  `InstallmentSpecificationsTest.java` (nouveau cas `inOrganization`, patron mock
  Root/CriteriaBuilder deja en place, cf. `inShopsFiltersOnShopId` existant) ;
  `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java`,
  `InstallmentServiceTest.java` -- verifier que `search` combine bien `currentOrganizationId()` ;
  `DashboardServiceTest`/`ReportServiceTest`/`LateCustomerServiceTest` si existants -- verifier la
  nouvelle signature des appels de repository (a confirmer par le spec-writer selon ce qui existe
  reellement).

## Decisions cles

- Meme choix qu'en #36 : jointure vers `Organization` via `shop`, pas de colonne `organization_id`
  denormalisee sur `credit_sales`/`installments`, pas de migration Flyway. Coherent avec la decision
  actee en #36 pour ce ticket precisement.
- `InstallmentSpecifications.inOrganization` traverse deux relations (`sale.shop.organization.id`)
  au lieu d'une (`shop.organization.id` pour `CreditSale`) -- anticipe explicitement dans les
  Risques de #36 (un saut de jointure supplementaire). Alternative ecartee : ajouter un raccourci
  `shop`/`organization` directement sur `Installment` -- rejetee, `Installment` n'a et n'a jamais eu
  de relation `Shop` propre dans le modele existant, l'introduire serait une denormalisation hors
  perimetre pour ce seul filtre.
- `sumTotalPriceByCustomer`, `sumRemainingByCustomer`, `countLateByCustomer`, `findByCustomer`,
  `CreditSaleService.findByCustomer` restent filtrees uniquement par `customerId`, aucun filtre
  organisation direct ajoute -- meme decision que `CustomerService.getEntity(id)` en #36 : le
  filtre organisation appartient a la validation d'acces au client en amont
  (`CustomerService.findById`/`getEntity`, deja scopee par #35/#36), pas a re-dupliquer dans chaque
  requete d'agregation qui en depend. Verifie exhaustivement a l'audit : les deux seuls appelants
  (`CustomerProfileService.profile`, `ReminderService.prepareForCustomer`) valident deja le client
  avant d'appeler ces methodes, et aucun controleur n'expose ces methodes de repository ou
  `CreditSaleService.findByCustomer` sans validation prealable. C'est litteralement le perimetre
  demande par le ticket (audit specifique des methodes qui s'appuient sur `assertAccessible` en
  amont) : l'audit conclut qu'aucun changement de code n'est necessaire ici, seulement une
  documentation explicite du contrat (javadoc) pour eviter qu'un futur appelant casse l'invariant.
- `CreditSaleService.getEntity(id)`/`InstallmentService.bySale(saleId)` restent inchangees (chargent
  sans filtre puis `assertAccessible(sale.getShop().getId())`) -- meme raisonnement que
  `CustomerService.getEntity(id)` en #36, deja correct par transitivite via `accessibleShopIds()`.
- `organizationId` est ajoute comme parametre explicite aux `@Query` qui recoivent deja `shopIds`
  (`sumRemainingByStatusForShops`, `findAllDetailedForShops`, `findUpcomingForShops`,
  `findLateForShops`, `countLateForShops`, `sumLateAmountForShops`) plutot que laisse tel quel :
  defense en profondeur symetrique a celle deja actee pour `CustomerRepository.quickSearch`/
  `ProductRepository.quickSearch` en #36, meme si le risque reel est faible (tous les appelants
  actuels passent deja un `shopIds` issu de `accessibleShopIds()`/`resolveReadFilter()`).
  `countByStatusAndShop_IdIn`/`countByShop_IdIn` (methodes derivees Spring Data, pas de `@Query`
  a modifier facilement sans les reecrire en `@Query` explicite) sont laissees inchangees et
  documentees en Risques, comme `CustomerRepository.countByShop_IdIn` en #36 -- a n'ajouter que si
  le spec-writer juge la coherence de patron plus importante que le cout de reecriture.

## Risques / points d'attention

- Meme faille deja documentee en #36 (hors perimetre de #37, deja signalee) :
  `UserService.resolveShops`/`ShopService` n'appliquent aucun filtre d'organisation sur
  l'assignation boutique/utilisateur ni sur la gestion des boutiques elle-meme. Si elle se
  materialise, `accessibleShopIds()` d'un utilisateur peut contenir un `shopId` etranger, et tous
  les filtres de ce design (y compris les nouveaux `inOrganization`, coherents avec la boutique mal
  assignee) laissent passer les contrats/echeanciers de cette boutique. A rappeler, pas a corriger
  ici (deja signale pour un ticket dedie en #36).
- Le contrat implicite (`customerId` doit etre valide via `assertAccessible` avant d'appeler
  `sumTotalPriceByCustomer`/`sumRemainingByCustomer`/`countLateByCustomer`/`findByCustomer`) n'est
  pas verifiable par le compilateur : un futur appelant (nouveau endpoint, nouveau service) qui
  invoquerait ces methodes directement avec un `customerId` de requete recreerait la faille evoquee
  par #25/#37. Le javadoc ajoute documente le contrat mais ne l'impose pas. A signaler si le
  spec-writer souhaite un test de regression grep/architecture (ex. verifier qu'aucun controleur
  n'appelle directement ces methodes de repository) -- probablement hors budget de ce ticket.
- `CreditSaleService.installmentsOf(Long saleId)` (ligne 318) n'a aucun appelant trouve dans le
  code actuel (grep exhaustif sur `backend/src/main/java`) -- pas de filtre organisation ni
  `assertAccessible`. Methode morte a l'audit ; a signaler au spec-writer plutot qu'a modifier
  silencieusement (peut etre du code mort a supprimer, hors perimetre strict de propagation
  multi-tenant).
- Instance mono-tenant : une seule ligne `organizations`, donc `currentOrganizationId()` est
  constant et `inOrganization(...)` ne restreint jamais rien -- meme raisonnement deja valide en
  #35/#36. A verifier par un test de non-regression explicite (recherche contrats/echeanciers avec
  une seule organisation retourne exactement les memes resultats qu'avant #37).
- Pas d'infrastructure `@DataJpaTest`/base embarquee dans ce backend (confirme, comme en #36) :
  les nouveaux cas `inOrganization` dans `SaleSpecificationsTest`/`InstallmentSpecificationsTest`
  seront des tests unitaires avec mocks Root/CriteriaBuilder/Predicate, pas des tests d'integration
  contre une vraie base -- a fixer explicitement par la spec, pas a etendre l'infrastructure de
  test.
- Signatures de `@Query` modifiees (`organizationId` ajoute) impactent tous les appelants existants
  (`DashboardService`, `ReportService`, `LateCustomerService`, `InstallmentService`) : a traiter
  comme un changement mecanique et exhaustif (grep sur chaque nom de methode avant de considerer le
  ticket termine), pas seulement les fichiers listes explicitement par le ticket #37 -- risque
  d'oubli de compilation si un appelant est manque.

## Hors perimetre

- Correction de `UserService.resolveShops`/`ShopService` (deja signalee en #36, meme faille).
- Ajout d'un filtre organisation direct sur `sumTotalPriceByCustomer`, `sumRemainingByCustomer`,
  `countLateByCustomer`, `findByCustomer` -- l'audit conclut qu'ils sont deja surs par construction
  via leurs appelants ; les modifier reviendrait a dupliquer une logique de validation qui
  appartient a `CustomerService`, pas a `CreditSale`/`Installment` (meme raisonnement que
  `CustomerService.getEntity(id)` en #36).
- Suppression ou modification de `CreditSaleService.installmentsOf` (code mort trouve a l'audit) --
  a signaler, pas a traiter dans ce ticket de propagation.
- Reecriture de `countByStatusAndShop_IdIn`/`countByShop_IdIn` en `@Query` explicite pour y ajouter
  `organizationId` -- documente en Risques/Decisions cles, laisse au jugement du spec-writer.
- Toute denormalisation (colonne `organization_id` directe sur `credit_sales`/`installments`) et
  migration Flyway associee -- ecarte en Approche, coherent avec #36.
- Propagation a Payment/StockReception/AuditLog -- tickets #38/#39, qui devront adapter le meme
  principe a des chaines de relations encore plus longues (Payment -> CreditSale -> Shop), pas
  traite ici.
- Postgres Row-Level Security et autre defense en profondeur base de donnees -- ticket de suivi
  numero 7 de `docs/bolts/25-architecture-multi-tenant-saas/design.md`.
