# Design — #38 Multi-tenant 5/10 — Propagation Payment

## Approche

`Payment` n'a pas de relation directe vers `Shop` : il ne porte que `sale`
(`Payment.sale`, `@ManyToOne(optional = false)`, `Payment.java:40-42`), et `CreditSale` porte
`shop` en relation directe (`CreditSale.shop`, deja utilise par
`s.shop.id IN :shopIds`/`p.sale.shop.id IN :shopIds` dans le code existant). L'organisation se
resout donc via `payment.sale.shop.organization.id` : deux sauts de jointure, exactement le meme
nombre que `Installment.sale.shop.organization.id` en #37 (pas un saut de plus comme le ticket le
suggerait au conditionnel — verifie par lecture du modele reel, pas suppose). Le patron retenu est
donc litteralement celui de #36/#37, sans adaptation structurelle : une
`Specification<Payment> inOrganization(Long organizationId)` dans `PaymentSpecifications`
(jointure, pas de denormalisation), combinee a `inShops(...)` dans `PaymentService.search`, et un
parametre `organizationId` ajoute aux `@Query` de `PaymentRepository` qui recoivent deja `shopIds`.
L'audit specifiquement demande par le ticket (`findByCustomer`, `findBySale`,
`sumBetweenForShops` et equivalents) aboutit a la meme conclusion qu'en #37 pour les methodes
filtrees uniquement par `customerId`/`saleId` : elles sont deja sures par construction via leurs
appelants, qui valident l'acces au client/contrat en amont — a documenter (javadoc), pas a
modifier. Le prix de cette approche est identique a celui deja assume en #36/#37 : les methodes
`*ByCustomer`/`*BySale` restent un contrat implicite non verifiable par le compilateur.

## Fichiers/modules impactes

- `backend/src/main/java/com/creditflow/payment/repository/PaymentSpecifications.java` — nouvelle
  `Specification<Payment> inOrganization(Long organizationId)`, jointure
  `root.get("sale").get("shop").get("organization").get("id")`, meme forme que
  `InstallmentSpecifications.inOrganization` de #37 (verifier son existence reelle au moment du
  merge avant de dupliquer le nom exact — cf. Risques, #37 n'est pas mergee sur cette branche).
- `backend/src/main/java/com/creditflow/payment/repository/PaymentRepository.java` — parametre
  `organizationId` ajoute aux `@Query` qui recoivent deja `shopIds` : `findBetweenForShops`,
  `sumBetweenForShops`, `countBetweenForShops` (`AND s.shop.organization.id = :organizationId` ou
  `AND p.sale.shop.organization.id = :organizationId` selon l'alias deja present dans chaque
  requete). `findByCustomer`, `findBySale`, `sumByCustomer`,
  `findBySaleIdOrderByPaymentDateAscIdAsc`, `findByClientRequestId` : aucun changement de
  signature (voir Decisions cles) — javadoc ajoutee pour expliciter le contrat d'appel.
- `backend/src/main/java/com/creditflow/payment/service/PaymentService.java` — `search` combine
  desormais `PaymentSpecifications.inOrganization(currentShopContext.currentOrganizationId())`
  avec `inShops(...)`. `findByCustomer`, `findBySale`, `receipt`, `register` (chemin idempotent),
  `delete` : aucun changement de logique, deja surs par construction (voir Decisions cles) — au
  plus un commentaire javadoc.
- `backend/src/main/java/com/creditflow/dashboard/service/DashboardService.java` — les 3 appels a
  `paymentRepository.findBetweenForShops`/`sumBetweenForShops` (x2)/`countBetweenForShops` gagnent
  `currentShopContext.currentOrganizationId()` en parametre supplementaire.
- `backend/src/main/java/com/creditflow/report/service/ReportService.java` — l'appel a
  `paymentRepository.findBetweenForShops` (methode privee `payments(...)`, ligne 72) gagne le meme
  parametre ; `build(...)` devra donc passer `currentShopContext.currentOrganizationId()` en plus
  de `shopIds` a `payments(...)`.
- Tests : `backend/src/test/java/com/creditflow/payment/repository/PaymentSpecificationsTest.java`
  — nouveau cas `inOrganization`, meme patron mock Root/CriteriaBuilder/Predicate que
  `inShopsFiltersOnSaleShopId` deja en place (verifie, fichier lu integralement) ;
  `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java` — verifier que
  `search` combine bien `currentOrganizationId()` ; tests `DashboardServiceTest`/`ReportServiceTest`
  si existants, a adapter a la nouvelle signature (a confirmer par le spec-writer selon ce qui
  existe reellement — non verifie dans cette note).

## Decisions cles

- Meme choix qu'en #36/#37 : jointure vers `Organization` via `sale.shop`, aucune colonne
  `organization_id` denormalisee sur `payments`, aucune migration Flyway. `payment.sale` est
  `optional = false` (`Payment.java:40`) et `CreditSale.shop`/`Shop.organization` sont eux aussi
  non-nullables — la jointure ne peut donc jamais produire de ligne orpheline (contrairement a un
  `LEFT JOIN` qui masquerait silencieusement des lignes).
- `findByCustomer(customerId)`, `findBySale(saleId)`, `sumByCustomer(customerId)` : aucun filtre
  organisation direct ajoute, memes decision et raisonnement qu'en #37 pour les methodes
  equivalentes de `CreditSaleRepository`/`InstallmentRepository`. Audit exhaustif des appelants
  (fait pour ce ticket, pas suppose) :
  - `PaymentService.findByCustomer` n'a qu'un seul appelant, `CustomerProfileService.profile`, qui
    appelle `customerService.findById(customerId)` juste avant (leve `ResourceNotFoundException`
    si le client n'est pas dans `accessibleShopIds()`, scope organisation garanti depuis #35/#36) —
    aucun controleur n'expose `PaymentService.findByCustomer` ni
    `PaymentRepository.findByCustomer` directement.
  - `PaymentService.findBySale` valide deja l'acces lui-meme, a l'interieur de la methode
    (`saleRepository.findDetailById(saleId)` puis
    `currentShopContext.assertAccessible(sale.getShop().getId())`, avant l'appel au repository) —
    plus fort que le patron #37 (qui s'appuyait sur une validation faite par l'appelant, pas par la
    methode elle-meme). C'est cette methode de service qui est appelee par
    `SaleController.payments()` (`GET /api/sales/{id}/payments`).
  - `PaymentRepository.findBySale` est aussi appele directement (en contournant
    `PaymentService.findBySale` et sa garde) par `CreditSaleService.findDetail`/`delete` — mais
    ces deux methodes appellent deja `getEntity(id)` (charge le contrat puis
    `assertAccessible(sale.getShop().getId())`) avant d'invoquer `paymentRepository.findBySale`.
    Sur par construction, meme raisonnement.
  - `PaymentRepository.sumByCustomer` n'a qu'un appelant, `CustomerProfileService.profile`, protege
    par la meme validation `customerService.findById` que `findByCustomer`.
  Conclusion identique a #37 : documenter le contrat par javadoc, ne pas dupliquer un controle
  d'acces qui appartient a `CustomerService`/`CreditSaleService`, pas a `Payment`.
- `findByClientRequestId` (chemin d'idempotence de `register`) : deja protege par un
  `assertAccessible(already.getSale().getShop().getId())` explicite juste apres la lecture
  (`PaymentService.java:121-122`), avant tout retour au client — aucun changement necessaire.
- `organizationId` ajoute comme parametre explicite aux `@Query` qui recoivent deja `shopIds`
  (`findBetweenForShops`, `sumBetweenForShops`, `countBetweenForShops`) plutot que laisse tel quel :
  defense en profondeur symetrique a celle actee pour les methodes `*ForShops` en #36/#37, meme si
  le risque reel est faible (les deux seuls appelants, `DashboardService`/`ReportService`, passent
  deja un `shopIds` issu de `currentShopContext.resolveReadFilter()`, deja scope organisation par
  transitivite depuis #35).

## Risques / points d'attention

- Dependance a #35 (merge confirme sur master/branche courante : `CurrentShopContext
  .currentOrganizationId()` existe deja, verifie par lecture directe du fichier). En revanche, la
  pretention du ticket selon laquelle #37 (CreditSale/Installment) serait deja mergee sur master
  ne se verifie pas dans l'etat reel du depot : la branche courante forke de `master` au merge de
  #36 (commit `897d954`), et `bolt/issue-37-multitenant-propagation-creditsale-installment` n'est
  pas un ancetre de HEAD (verifie via `git merge-base --is-ancestor`). Consequence concrete :
  `CreditSaleRepository`/`InstallmentRepository` sur cette branche n'ont pas encore les parametres
  `organizationId` decrits dans le design de #37 (lu directement sur sa branche pour cette note,
  absent de la branche courante). Cela ne bloque pas ce ticket : `CreditSale.shop` existe deja
  independamment de #37 (relation anterieure au chantier multi-tenant), donc la jointure
  `payment.sale.shop.organization.id` fonctionne sans dependre du merge de #37. Mais cela cree un
  risque de sequencement/rebase a signaler explicitement : si #38 est mergee avant #37, ou
  l'inverse, l'une des deux branches devra rebaser sur l'autre avant integration finale — a traiter
  au niveau du pipeline, pas dans ce document.
- Faille deja documentee en #36/#37, non corrigee, hors perimetre : `UserService.resolveShops`/
  `ShopService` n'appliquent aucun filtre d'organisation sur l'assignation boutique/utilisateur ni
  sur la gestion des boutiques. Si elle se materialise, `accessibleShopIds()` d'un utilisateur peut
  contenir un `shopId` etranger, et `inOrganization(...)` (coherent avec la boutique mal assignee,
  pas avec l'utilisateur) ne bloque rien dans ce cas precis.
- Le contrat implicite sur `findByCustomer`/`findBySale`/`sumByCustomer` (l'appelant doit valider
  l'acces au client/contrat en amont) n'est pas verifiable par le compilateur. Un futur endpoint qui
  invoquerait `PaymentRepository.findByCustomer`/`sumByCustomer` directement avec un `customerId` de
  requete, sans passer par `CustomerService.findById` au prealable, recreerait la faille identifiee
  par #25. Le javadoc documente le contrat sans l'imposer.
- Instance mono-tenant : une seule ligne `organizations`, donc `currentOrganizationId()` est
  constant et `inOrganization(...)` ne restreint jamais rien — a verifier explicitement par un test
  de non-regression (recherche/dashboard/rapports avec une seule organisation retournent exactement
  les memes resultats qu'avant #38), meme exigence deja actee en #35/#36/#37.
- Pas d'infrastructure `@DataJpaTest`/base embarquee dans ce backend (confirme par lecture de
  `PaymentSpecificationsTest.java`, deja sur mocks Root/CriteriaBuilder/Predicate) : le nouveau cas
  `inOrganization` sera lui aussi un test unitaire avec mocks, pas un test d'integration contre une
  vraie base.
- Signatures de `@Query` modifiees (`organizationId` ajoute) impactent tous les appelants existants
  (`DashboardService`, `ReportService`) : a traiter comme un changement mecanique et exhaustif (grep
  sur chaque nom de methode avant de considerer le ticket termine), pas seulement les fichiers
  listes explicitement par le ticket.

## Hors perimetre

- Merge ou rebase entre les branches #37 et #38 — signale en Risques comme un probleme reel de
  sequencement, mais sa resolution (ordre de merge, rebase) est une decision d'integration, pas une
  decision de design de ce ticket.
- Correction de `UserService.resolveShops`/`ShopService` (faille deja signalee en #36/#37, meme
  perimetre hors de Payment).
- Ajout d'un filtre organisation direct sur `findByCustomer`, `findBySale`, `sumByCustomer` —
  l'audit conclut qu'ils sont deja surs par construction via leurs appelants ; les modifier
  dupliquerait une validation qui appartient a `CustomerService`/`CreditSaleService`, pas a
  `Payment` (meme raisonnement que #36/#37).
- Toute denormalisation (colonne `organization_id` directe sur `payments`) et migration Flyway
  associee — ecarte en Approche, coherent avec #36/#37.
- Propagation a StockReception/AuditLog — ticket #39, qui devra adapter le meme principe a d'autres
  chaines de relations, pas traite ici.
- Postgres Row-Level Security et autre defense en profondeur base de donnees — ticket de suivi
  numero 7 de `docs/bolts/25-architecture-multi-tenant-saas/design.md`.
- Tout changement frontend : le ticket et son perimetre technique ne listent que des fichiers
  backend (`PaymentRepository`, `PaymentSpecifications`, `PaymentService`) ; aucun contrat d'API
  (forme des requetes/reponses) ne change, seul le filtrage cote serveur est renforce.
