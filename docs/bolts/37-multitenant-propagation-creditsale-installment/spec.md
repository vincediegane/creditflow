# Spec — #37 Multi-tenant 4/10 — Propagation CreditSale/Installment

## Résumé

Propager le filtrage par organisation (déjà en place pour Customer/Product en #36) aux contrats
`CreditSale` et à leurs échéanciers `Installment`, en ajoutant une jointure explicite vers
`Organization` dans les specifications et les `@Query` de recherche/agrégation par boutiques,
sans dénormalisation ni migration.

## Tâches

### Repository — Specifications

- [ ] `backend/src/main/java/com/creditflow/sale/repository/SaleSpecifications.java` — ajouter :
  ```java
  public static Specification<CreditSale> inOrganization(Long organizationId) {
      if (organizationId == null) {
          return null;
      }
      return (root, query, cb) -> cb.equal(root.get("shop").get("organization").get("id"), organizationId);
  }
  ```
- [ ] `backend/src/main/java/com/creditflow/sale/repository/InstallmentSpecifications.java` — ajouter :
  ```java
  public static Specification<Installment> inOrganization(Long organizationId) {
      if (organizationId == null) {
          return null;
      }
      return (root, query, cb) ->
              cb.equal(root.get("sale").get("shop").get("organization").get("id"), organizationId);
  }
  ```

### Repository — `@Query` scopées par boutiques

- [ ] `backend/src/main/java/com/creditflow/sale/repository/CreditSaleRepository.java` :
  - `sumRemainingByStatusForShops` : ajouter le paramètre `organizationId` et la condition
    `AND s.shop.organization.id = :organizationId`.
    ```java
    @Query("SELECT COALESCE(SUM(s.remainingAmount), 0) FROM CreditSale s "
            + "WHERE s.status = :status AND s.shop.id IN :shopIds AND s.shop.organization.id = :organizationId")
    BigDecimal sumRemainingByStatusForShops(@Param("status") SaleStatus status, @Param("shopIds") List<Long> shopIds,
                                             @Param("organizationId") Long organizationId);
    ```
  - `findAllDetailedForShops` : même traitement.
    ```java
    @Query("SELECT s FROM CreditSale s JOIN FETCH s.customer JOIN FETCH s.product "
            + "WHERE s.shop.id IN :shopIds AND s.shop.organization.id = :organizationId ORDER BY s.createdAt DESC")
    List<CreditSale> findAllDetailedForShops(@Param("shopIds") List<Long> shopIds,
                                              @Param("organizationId") Long organizationId);
    ```
  - `countByStatusAndShop_IdIn`, `countByShop_IdIn` : **inchangées** (décision, voir Écarts identifiés).
  - `sumTotalPriceByCustomer`, `sumRemainingByCustomer`, `findByCustomer` : **signature inchangée**,
    ajouter un javadoc explicite au-dessus de chaque méthode :
    ```java
    /**
     * Filtre uniquement par customerId, sans filtre organisation direct : le contrat implicite
     * est que l'appelant a deja valide l'acces au client (CustomerService.findById/getEntity,
     * deja scope organisation par #35/#36) avant d'invoquer cette methode. Ne jamais exposer
     * directement a un controleur avec un customerId de requete sans validation prealable
     * (cf. docs/bolts/37-.../design.md, section Risques).
     */
    ```
- [ ] `backend/src/main/java/com/creditflow/sale/repository/InstallmentRepository.java` :
  - `findUpcomingForShops` : ajouter `organizationId` et `AND s.shop.organization.id = :organizationId`
    (alias `s` déjà présent via `JOIN FETCH i.sale s`).
  - `findLateForShops` : même traitement (alias `s` déjà présent).
  - `countLateForShops` : ajouter `organizationId` et `AND i.sale.shop.organization.id = :organizationId`
    (pas d'alias `s` dans cette requête, utiliser le chemin complet `i.sale.shop.organization.id`).
  - `sumLateAmountForShops` : même traitement (`i.sale.shop.organization.id = :organizationId`).
  - `countLateByCustomer` : **signature inchangée**, même javadoc de contrat que
    `sumTotalPriceByCustomer` (contrat garanti par `CustomerProfileService.profile` qui appelle
    `customerService.findById` avant).

### Services

- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` :
  - `search(...)` : ajouter `SaleSpecifications.inOrganization(currentShopContext.currentOrganizationId())`
    dans `Specs.combine(...)`, en plus de `inShops(...)`.
  - `installmentsOf(Long saleId)` (ligne ~318) : **laissée inchangée dans ce ticket** — voir
    Écarts identifiés pour la décision documentée.
  - Aucun autre changement (`getEntity`, `create`, `findByCustomer`, etc. restent tels quels).
- [ ] `backend/src/main/java/com/creditflow/sale/service/InstallmentService.java` :
  - `search(...)` : ajouter `InstallmentSpecifications.inOrganization(currentShopContext.currentOrganizationId())`
    dans `Specs.combine(...)`.
  - `upcoming(int days)` : passer `currentShopContext.currentOrganizationId()` en 3ᵉ argument de
    `installmentRepository.findUpcomingForShops(...)`.
  - `upcomingForShops(int days, List<Long> shopIds)` : **signature publique inchangée** (le
    dashboard continue d'appeler `installmentService.upcomingForShops(days, shopIds)` sans
    changement) ; en interne, passer `currentShopContext.currentOrganizationId()` (déjà injecté
    dans ce service) en 3ᵉ argument de `findUpcomingForShops(...)`.
  - `late()` : passer `currentShopContext.currentOrganizationId()` en 3ᵉ argument de
    `installmentRepository.findLateForShops(...)`.
  - `bySale(Long saleId)` : inchangée (déjà scopée via `assertAccessible`).
- [ ] `backend/src/main/java/com/creditflow/notification/service/LateCustomerService.java` :
  - Injecter `CurrentShopContext` comme nouvelle dépendance du constructeur (`@RequiredArgsConstructor`
    génère le paramètre automatiquement — ajouter le champ `private final CurrentShopContext currentShopContext;`).
  - `lateCustomers(List<Long> shopIds)` : **signature publique inchangée** (`DashboardService` et
    `ReportService` continuent d'appeler `lateCustomerService.lateCustomers(shopIds)` sans
    changement) ; en interne, passer `currentShopContext.currentOrganizationId()` en 3ᵉ argument
    de `installmentRepository.findLateForShops(...)`.

### Appelants dont l'appel de repository change de signature

- [ ] `backend/src/main/java/com/creditflow/dashboard/service/DashboardService.java` :
  - `saleRepository.sumRemainingByStatusForShops(SaleStatus.ACTIVE, shopIds)` →
    `sumRemainingByStatusForShops(SaleStatus.ACTIVE, shopIds, currentShopContext.currentOrganizationId())`.
  - `installmentRepository.countLateForShops(today, shopIds)` →
    `countLateForShops(today, shopIds, currentShopContext.currentOrganizationId())`.
  - `installmentRepository.sumLateAmountForShops(today, shopIds)` →
    `sumLateAmountForShops(today, shopIds, currentShopContext.currentOrganizationId())`.
  - `saleRepository.countByStatusAndShop_IdIn(...)`, `saleRepository.countByShop_IdIn(...)`,
    `customerRepository.countByShop_IdIn(...)`, `installmentService.upcomingForShops(...)`,
    `lateCustomerService.lateCustomers(...)` : appels **inchangés** (organizationId résolu en
    interne par ces méthodes, ou méthode dérivée volontairement non modifiée).
- [ ] `backend/src/main/java/com/creditflow/report/service/ReportService.java` :
  - Les 3 appels à `saleRepository.findAllDetailedForShops(shopIds)` (méthodes privées `outstanding`,
    `defaultRate`, `sellerPerformance`) → `findAllDetailedForShops(shopIds, currentShopContext.currentOrganizationId())`.
  - L'appel à `installmentRepository.findLateForShops(LocalDate.now(), shopIds)` dans la méthode
    privée `lateInstallments(shopIds)` → ajouter `currentShopContext.currentOrganizationId()`.
  - L'appel à `lateCustomerService.lateCustomers(shopIds)` : **inchangé**.

### Grep de fermeture (avant de considérer le ticket terminé)

- [ ] Grep exhaustif sur `sumRemainingByStatusForShops`, `findAllDetailedForShops`,
  `findUpcomingForShops`, `findLateForShops`, `countLateForShops`, `sumLateAmountForShops` dans
  `backend/src/main/java` et `backend/src/test/java` pour confirmer que tous les appelants ont été
  mis à jour (le design signale ce risque explicitement — la liste ci-dessus a déjà été vérifiée
  exhaustivement lors de la rédaction de cette spec, à revérifier après implémentation).

## Contrat technique

| Méthode | Avant | Après |
|---|---|---|
| `CreditSaleRepository.sumRemainingByStatusForShops` | `(SaleStatus status, List<Long> shopIds)` | `(SaleStatus status, List<Long> shopIds, Long organizationId)` |
| `CreditSaleRepository.findAllDetailedForShops` | `(List<Long> shopIds)` | `(List<Long> shopIds, Long organizationId)` |
| `InstallmentRepository.findUpcomingForShops` | `(LocalDate from, LocalDate to, List<Long> shopIds)` | `(LocalDate from, LocalDate to, List<Long> shopIds, Long organizationId)` |
| `InstallmentRepository.findLateForShops` | `(LocalDate reference, List<Long> shopIds)` | `(LocalDate reference, List<Long> shopIds, Long organizationId)` |
| `InstallmentRepository.countLateForShops` | `(LocalDate reference, List<Long> shopIds)` | `(LocalDate reference, List<Long> shopIds, Long organizationId)` |
| `InstallmentRepository.sumLateAmountForShops` | `(LocalDate reference, List<Long> shopIds)` | `(LocalDate reference, List<Long> shopIds, Long organizationId)` |
| `SaleSpecifications.inOrganization` | n'existe pas | `static Specification<CreditSale> inOrganization(Long organizationId)` |
| `InstallmentSpecifications.inOrganization` | n'existe pas | `static Specification<Installment> inOrganization(Long organizationId)` |
| `LateCustomerService` constructeur | `(InstallmentRepository, PenaltySettingsService, PenaltyCalculator)` | `(InstallmentRepository, PenaltySettingsService, PenaltyCalculator, CurrentShopContext)` |

Signatures **inchangées** (décision actée, pas une omission) : `CreditSaleRepository.sumTotalPriceByCustomer`,
`sumRemainingByCustomer`, `findByCustomer`, `countByStatusAndShop_IdIn`, `countByShop_IdIn` ;
`InstallmentRepository.countLateByCustomer` ; `InstallmentService.upcomingForShops(int, List<Long>)` ;
`LateCustomerService.lateCustomers(List<Long>)` ; `CreditSaleService.installmentsOf(Long)`.

## Plan de tests

Rappel : pas d'infrastructure `@DataJpaTest`/base embarquée dans ce backend — tous les nouveaux cas
`inOrganization` sont des tests unitaires avec mocks `Root`/`CriteriaBuilder`/`Predicate`, sur le
patron exact de `CustomerSpecificationsTest.inOrganizationFiltersOnShopOrganizationId` (déjà
existant, écrit en #36).

- [ ] `backend/src/test/java/com/creditflow/sale/repository/SaleSpecificationsTest.java` :
  - `inOrganizationReturnsNullWhenNull()` : `assertThat(SaleSpecifications.inOrganization(null)).isNull();`
  - `inOrganizationFiltersOnShopOrganizationId()` : mock `root.get("shop")` → `shopPath`,
    `shopPath.get("organization")` → `organizationPath`, `organizationPath.get("id")` → `idPath`,
    `cb.equal(idPath, 1L)` → `predicate` ; vérifier `toPredicate(...)` retourne `predicate` et que
    `root.get("shop")`, `shopPath.get("organization")`, `organizationPath.get("id")`,
    `cb.equal(idPath, 1L)` sont bien invoqués (copie conforme de `CustomerSpecificationsTest`).
- [ ] `backend/src/test/java/com/creditflow/sale/repository/InstallmentSpecificationsTest.java` :
  - `inOrganizationReturnsNullWhenNull()`.
  - `inOrganizationFiltersOnSaleShopOrganizationId()` : mock `root.get("sale")` → `salePath`,
    `salePath.get("shop")` → `shopPath`, `shopPath.get("organization")` → `organizationPath`,
    `organizationPath.get("id")` → `idPath`, `cb.equal(idPath, 1L)` → `predicate` ; mêmes
    vérifications (un saut de jointure de plus que `SaleSpecifications`).
- [ ] `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` — ajouter :
  - `searchCombinesCurrentOrganizationFilter()` : stub `currentShopContext.accessibleShopIds()` et
    `currentShopContext.currentOrganizationId()`, stub `saleRepository.findAll(any(Specification.class), any(Pageable.class))`
    → `Page.empty()`, appeler `creditSaleService.search(null, null, null, Pageable.unpaged())`,
    `verify(currentShopContext).currentOrganizationId();` (aucun test `search` n'existe
    actuellement dans ce fichier — nouveau test complet, pas une modification).
- [ ] `backend/src/test/java/com/creditflow/sale/service/InstallmentServiceTest.java` — **fichier à
  créer** (n'existe pas actuellement, seuls `InstallmentScheduleGeneratorTest` et
  `InstallmentSpecificationsTest` existent dans `sale`). Structure Mockito standard
  (`@ExtendWith(MockitoExtension.class)`, `@Mock` pour `InstallmentRepository`,
  `CreditSaleRepository`, `SaleMapper`, `PenaltySettingsService`, `CurrentShopContext`,
  `@InjectMocks` pour `InstallmentService`), couvrant :
  - `search(...)` combine `currentOrganizationId()` (même patron que le test ajouté sur
    `CreditSaleService`, avec `installmentRepository.findAll(any(Specification.class), any(Pageable.class))` → `Page.empty()`).
  - `upcoming(days)` transmet `accessibleShopIds()` et `currentOrganizationId()` à
    `findUpcomingForShops` (`verify(installmentRepository).findUpcomingForShops(any(), any(), eq(shopIds), eq(organizationId));`).
  - `upcomingForShops(days, shopIds)` transmet le `shopIds` fourni en paramètre et
    `currentOrganizationId()` (résolu en interne) à `findUpcomingForShops`.
  - `late()` transmet `accessibleShopIds()` et `currentOrganizationId()` à `findLateForShops`.
  - `bySale(saleId)` : non-régression — comportement identique à l'existant (charge via
    `saleRepository.findDetailById`, `assertAccessible`, lève `ResourceNotFoundException` si
    absent ou non accessible).
- [ ] `backend/src/test/java/com/creditflow/dashboard/service/DashboardServiceTest.java` — modifier :
  - Ajouter `when(currentShopContext.currentOrganizationId()).thenReturn(100L);` dans `setUp()`.
  - Étendre les stubs à 3 arguments : `saleRepository.sumRemainingByStatusForShops(any(SaleStatus.class), any(), any())`,
    `installmentRepository.countLateForShops(any(), any(), any())`,
    `installmentRepository.sumLateAmountForShops(any(), any(), any())`.
  - Dans chacun des 3 tests existants (`monoShop_isNotConsolidated`, `multiShopWithoutHeader_isConsolidated`,
    `multiShopWithHeader_isRestrictedToRequestedShop`), étendre les `verify(...)` correspondants
    avec `100L` en 3ᵉ argument, ex. `verify(saleRepository).sumRemainingByStatusForShops(SaleStatus.ACTIVE, List.of(1L), 100L);`.
  - `saleRepository.countByStatusAndShop_IdIn`, `saleRepository.countByShop_IdIn`,
    `customerRepository.countByShop_IdIn` : stubs/verify **inchangés**.
- [ ] `backend/src/test/java/com/creditflow/report/service/ReportServiceTest.java` — modifier :
  - Ajouter `when(currentShopContext.currentOrganizationId()).thenReturn(100L);` dans `setUp()`.
  - Étendre `installmentRepository.findLateForShops(any(), any())` → `findLateForShops(any(), any(), any())`.
  - Étendre les stubs `saleRepository.findAllDetailedForShops(...)` à 2 arguments partout
    (`List.of(1L)` devient `List.of(1L), 100L` ou `any(), any()` selon le test).
  - Mettre à jour les `verify(...)` : `outstanding_usesResolvedShopIds`,
    `defaultRate_usesResolvedShopIds`, `sellerPerformance_usesResolvedShopIds` →
    `verify(saleRepository).findAllDetailedForShops(List.of(1L), 100L);` et
    `verify(installmentRepository).findLateForShops(any(), eq(List.of(1L)), eq(100L));`.
- [ ] `backend/src/test/java/com/creditflow/notification/service/LateCustomerServiceTest.java` — modifier :
  - Ajouter `@Mock private CurrentShopContext currentShopContext;` (le constructeur généré par
    `@RequiredArgsConstructor` gagne ce paramètre, `@InjectMocks` le résout automatiquement).
  - Ajouter `when(currentShopContext.currentOrganizationId()).thenReturn(100L);` dans `setUp()`.
  - Étendre les stubs/`verify` de `installmentRepository.findLateForShops(any(), eq(shopIds))` →
    `findLateForShops(any(), eq(shopIds), eq(100L))` dans `lateCustomersDelegatesToShopIds` et
    `groupsLateInstallmentsByCustomer`.

### Correspondance critères d'acceptation → tests

| Critère d'acceptation | Test(s) |
|---|---|
| Aucun endpoint contrat/échéancier n'expose de donnée d'une autre organisation | `SaleSpecificationsTest.inOrganizationFiltersOnShopOrganizationId`, `InstallmentSpecificationsTest.inOrganizationFiltersOnSaleShopOrganizationId`, `CreditSaleServiceTest.searchCombinesCurrentOrganizationFilter`, `InstallmentServiceTest.search/upcoming/upcomingForShops/late` (nouveau fichier), `DashboardServiceTest` (verify avec `100L`), `ReportServiceTest` (verify avec `100L`), `LateCustomerServiceTest` (verify avec `100L`) |
| ... y compris via les méthodes d'agrégation par client | Pas de nouveau test automatisé (décision actée : `sumTotalPriceByCustomer`/`sumRemainingByCustomer`/`countLateByCustomer`/`findByCustomer` restent non modifiées, protégées par validation en amont du client). Couverture = revue manuelle du javadoc ajouté + confirmation par grep (déjà faite dans cette spec) qu'aucun contrôleur n'appelle ces méthodes de repository directement. Aucun test `CustomerProfileServiceTest`/`ReminderServiceTest` n'existe actuellement pour ce chemin ; ne pas en créer dans ce ticket (hors périmètre, fichiers non listés comme impactés) |
| Instance mono-tenant : comportement strictement identique à aujourd'hui | Manuel/structurel : les tests `inOrganization` ci-dessus montrent que le prédicat est un simple `equal` qui, avec un id d'organisation constant (mono-tenant), ne restreint jamais le résultat par rapport à `inShops` seul — pas de test d'intégration possible faute d'infrastructure `@DataJpaTest`. Les tests de service (`DashboardServiceTest`, `ReportServiceTest`) avec `currentOrganizationId()` fixe couvrent la non-régression du câblage service→repository |

## Écarts identifiés

- **`countByStatusAndShop_IdIn` / `countByShop_IdIn`** (`CreditSaleRepository`, méthodes dérivées
  Spring Data sans `@Query`) : décision — **laissées inchangées**, pas réécrites en `@Query`
  explicite. Cohérent avec le précédent `CustomerRepository.countByShop_IdIn` en #36. Risque
  documenté (pas corrigé) : ces deux méthodes ne filtrent que par `shopIds`, sans défense en
  profondeur organisation ; elles restent sûres tant que `shopIds` provient de
  `accessibleShopIds()`/`resolveReadFilter()` (déjà scopés organisation par #35/#36).
- **`CreditSaleService.installmentsOf(Long saleId)`** : confirmé code mort à l'audit (grep
  exhaustif sur `backend/src/main/java` et `backend/src/test/java` — zéro appelant, y compris dans
  les tests). Décision — **laissée telle quelle dans ce ticket**, non supprimée : le périmètre de
  #37 est la propagation multi-tenant, pas le nettoyage de code mort, et le design classe
  explicitement cette suppression comme hors périmètre. À signaler pour un ticket de nettoyage
  dédié plutôt qu'à traiter incidemment ici.
- **Contrat implicite non vérifiable par le compilateur** (`sumTotalPriceByCustomer`,
  `sumRemainingByCustomer`, `countLateByCustomer`, `findByCustomer` dépendent d'une validation en
  amont du `customerId`) : le javadoc ajouté documente le contrat mais ne l'impose pas. Aucun test
  de régression architecture (grep automatisé sur les appels directs depuis un contrôleur) n'est
  ajouté dans ce ticket — jugé hors budget, comme signalé par le design.
- **`LateCustomerService` gagne une nouvelle dépendance (`CurrentShopContext`)**, non listée
  explicitement dans le design comme "nouvelle injection" mais nécessaire pour que
  `lateCustomers(List<Long> shopIds)` conserve sa signature publique actuelle (appelée par
  `DashboardService` et `ReportService` sans autre changement) tout en résolvant
  `currentOrganizationId()` en interne. Alternative écartée : ajouter un paramètre `organizationId`
  au niveau du service `lateCustomers(shopIds, organizationId)`, ce qui aurait obligé à modifier
  les deux appelants pour un gain nul (les deux ont déjà `CurrentShopContext` injecté) — l'injection
  directe est plus cohérente avec le patron déjà utilisé par `InstallmentService`.
- **`upcomingForShops(int days, List<Long> shopIds)`** (`InstallmentService`) : le design évoque
  "reçoit aussi `currentOrganizationId()`" sans préciser si la signature publique change. Décision —
  signature publique **inchangée** (résolution interne via `currentShopContext` déjà injecté dans
  ce service), pour ne pas modifier l'appel depuis `DashboardService.overview()` sans bénéfice
  (le service a déjà accès à `CurrentShopContext`).
