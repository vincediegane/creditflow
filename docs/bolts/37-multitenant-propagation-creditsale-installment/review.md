# Review

## Verdict

APPROVE

## Resume

Le diff (master..HEAD, 3 commits 6b4207e/c1d49e8/f730ad6 + design/spec) est strictement
scope au perimetre annonce.

## Criteres d'acceptation

| Critere | Statut |
|---|---|
| Aucun endpoint contrat/echeancier n'expose de donnee d'une autre organisation, y compris via les methodes d'agregation par client | Couvert |
| Instance mono-tenant : comportement strictement identique a aujourd'hui | Couvert |

Detail agregation par client : sumTotalPriceByCustomer, sumRemainingByCustomer,
countLateByCustomer, findByCustomer restent sans filtre organisation direct, mais verification
independante (grep exhaustif, pas seulement confiance dans la spec) confirme que leurs deux seuls
appelants (CustomerProfileService.profile ligne 36, ReminderService.prepareForCustomer ligne 152)
appellent bien customerService.findById/getEntity en amont, et qu'aucun controleur n'invoque ces
methodes de repository ou CreditSaleService.findByCustomer directement.

Non-regression mono-tenant : le predicat ajoute est un simple equal combine en AND ; avec une
organisation constante il ne restreint jamais le resultat par rapport a inShops seul. Confirme par
les tests de service existants (DashboardServiceTest/ReportServiceTest) qui restent verts avec les
memes assertions fonctionnelles, seule la signature d'appel change.

## Verifications techniques effectuees

1. Alias JPQL (InstallmentRepository.java) : confirme correct pour les 4 requetes modifiees.
   findUpcomingForShops/findLateForShops utilisent s.shop.organization.id (alias s existant via
   JOIN FETCH i.sale s) ; countLateForShops/sumLateAmountForShops utilisent le chemin complet
   i.sale.shop.organization.id (pas d'alias s dans ces requetes) -- exactement le piege signale par
   le design, correctement evite.
2. Grep exhaustif des appelants sur sumRemainingByStatusForShops, findAllDetailedForShops,
   findUpcomingForShops, findLateForShops, countLateForShops, sumLateAmountForShops dans tout
   backend/src : 10 fichiers trouves, tous attendus (4 source + 4 test deja migres dans le diff, 2
   fichiers repository eux-memes). Aucun appelant oublie, aucune regression de compilation.
3. CreditSaleService.search / InstallmentService.search : inOrganization(...) bien ajoute a
   Specs.combine(...) en plus de inShops(...).
4. LateCustomerService : injection CurrentShopContext via @RequiredArgsConstructor (champ ajoute en
   4e position), findLateForShops recoit bien currentOrganizationId().
5. DashboardService/ReportService : tous les appels adaptes (3 dans ReportService pour
   findAllDetailedForShops, 1 pour findLateForShops ; 3 dans DashboardService). Les appels inchanges
   (countByStatusAndShop_IdIn, countByShop_IdIn, installmentService.upcomingForShops,
   lateCustomerService.lateCustomers) sont bien ceux dont la signature publique n'a pas change,
   conformement a la decision actee.
6. Code mort confirme : CreditSaleService.installmentsOf -- grep sur tout backend/src confirme zero
   appelant, coherent avec la decision de ne pas y toucher dans ce ticket.
7. Tests non tautologiques : les tests inOrganization (Sale/Installment Specifications) suivent le
   patron mock Root/CriteriaBuilder deja valide en #36, verifient chaque saut de jointure
   (root.get("sale") -> salePath.get("shop") -> ...) et le predicate retourne -- pas de test qui se
   contenterait de verifier l'absence d'exception. InstallmentServiceTest (nouveau fichier) verifie
   par verify(...) les arguments exacts transmis a findUpcomingForShops/findLateForShops, y compris
   la distinction upcoming() (resout accessibleShopIds() en interne) vs upcomingForShops(days,
   shopIds) (shopIds fourni, organizationId resolu en interne) -- couvre bien le comportement
   annonce.
8. Build/tests reels relances (pas seulement confiance dans le rapport du codeur) : voir section
   Build/tests ci-dessous, tout est vert.

## Findings mineurs (non bloquants)

- backend/src/main/java/com/creditflow/sale/repository/CreditSaleRepository.java:44 --
  findByCustomer n'a pas recu le javadoc de contrat contrairement a sumTotalPriceByCustomer et
  sumRemainingByCustomer qui, elles, l'ont. Incoherence mineure de documentation (le contrat
  s'applique identiquement aux trois methodes selon le design), pas un probleme de securite --
  findByCustomer a le meme appelant protege (CreditSaleService.findByCustomer ->
  CustomerProfileService.profile / ReminderService). A corriger dans un futur commit de nettoyage
  documentaire si souhaite, ne bloque pas ce ticket.

Aucun finding bloquant identifie : pas de bug de logique, pas de regression, pas d'endpoint non
securise, pas de migration Flyway (aucune n'etait attendue -- decision actee de ne pas denormaliser).

## Build/tests

- cd backend && mvn -q -Dtest=SaleSpecificationsTest,InstallmentSpecificationsTest,CreditSaleServiceTest,InstallmentServiceTest,DashboardServiceTest,ReportServiceTest,LateCustomerServiceTest test
  -> vert, aucune erreur de test dans la sortie (uniquement logs applicatifs attendus).
- cd backend && mvn -q test (suite complete) -> exit code 0 (BUILD SUCCESS).

## Fichiers revus

- backend/src/main/java/com/creditflow/sale/repository/SaleSpecifications.java
- backend/src/main/java/com/creditflow/sale/repository/InstallmentSpecifications.java
- backend/src/main/java/com/creditflow/sale/repository/CreditSaleRepository.java
- backend/src/main/java/com/creditflow/sale/repository/InstallmentRepository.java
- backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java
- backend/src/main/java/com/creditflow/sale/service/InstallmentService.java
- backend/src/main/java/com/creditflow/notification/service/LateCustomerService.java
- backend/src/main/java/com/creditflow/dashboard/service/DashboardService.java
- backend/src/main/java/com/creditflow/report/service/ReportService.java
- backend/src/main/java/com/creditflow/customer/service/CustomerProfileService.java (lecture seule, audit)
- backend/src/main/java/com/creditflow/notification/service/ReminderService.java (lecture seule, audit)
- backend/src/test/java/com/creditflow/sale/repository/SaleSpecificationsTest.java
- backend/src/test/java/com/creditflow/sale/repository/InstallmentSpecificationsTest.java
- backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java
- backend/src/test/java/com/creditflow/sale/service/InstallmentServiceTest.java
- backend/src/test/java/com/creditflow/dashboard/service/DashboardServiceTest.java
- backend/src/test/java/com/creditflow/report/service/ReportServiceTest.java
- backend/src/test/java/com/creditflow/notification/service/LateCustomerServiceTest.java
- docs/bolts/37-multitenant-propagation-creditsale-installment/spec.md
- docs/bolts/37-multitenant-propagation-creditsale-installment/design.md
