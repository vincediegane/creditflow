# Spec — #38 Multi-tenant 5/10 — Propagation Payment

## Résumé
Ajout d'un filtrage explicite par organisation (`inOrganization`) sur la recherche de paiements et sur les agrégations `*ForShops`, sans changement de contrat API ni migration, en documentant par javadoc le contrat implicite des méthodes `*ByCustomer`/`*BySale` déjà sûres par construction.

## Tâches

- [ ] `backend/src/main/java/com/creditflow/payment/repository/PaymentSpecifications.java` — ajouter `Specification<Payment> inOrganization(Long organizationId)`.
- [ ] `backend/src/main/java/com/creditflow/payment/repository/PaymentRepository.java` — ajouter le paramètre `organizationId` à `findBetweenForShops`, `sumBetweenForShops`, `countBetweenForShops` ; ajouter la javadoc de contrat sur `findByCustomer`, `findBySale`, `sumByCustomer`, `findBySaleIdOrderByPaymentDateAscIdAsc`, `findByClientRequestId`.
- [ ] `backend/src/main/java/com/creditflow/payment/service/PaymentService.java` — `search` combine `PaymentSpecifications.inOrganization(currentShopContext.currentOrganizationId())` ; javadoc de contrat sur `findByCustomer`, `findBySale`, `receipt`, `register` (chemin idempotent), `delete`.
- [ ] `backend/src/main/java/com/creditflow/dashboard/service/DashboardService.java` — `overview()` résout `organizationId` une fois et le passe aux 4 appels (`findBetweenForShops` x1, `sumBetweenForShops` x2, `countBetweenForShops` x1).
- [ ] `backend/src/main/java/com/creditflow/report/service/ReportService.java` — `build(...)` résout `organizationId` une fois ; `payments(...)` (méthode privée) reçoit `organizationId` en paramètre supplémentaire et le transmet à `paymentRepository.findBetweenForShops`.
- [ ] `backend/src/test/java/com/creditflow/payment/repository/PaymentSpecificationsTest.java` — deux nouveaux tests pour `inOrganization` (cas null, cas jointure).
- [ ] `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java` — nouveau test vérifiant que `search` invoque `currentShopContext.currentOrganizationId()`.
- [ ] `backend/src/test/java/com/creditflow/dashboard/service/DashboardServiceTest.java` — adapter les stubs/`verify` des 3 tests existants (arité `+1`, argument `organizationId`).
- [ ] `backend/src/test/java/com/creditflow/report/service/ReportServiceTest.java` — adapter les stubs/`verify` de `dailyPayments_usesResolvedShopIds` et `monthlyPayments_usesResolvedShopIds` (arité `+1`, argument `organizationId`) ; adapter le stub générique de `findBetweenForShops` si utilisé ailleurs dans le fichier.

Note de séquencement (cf. design.md, section Risques) : #37 n'est pas mergée sur cette branche ; aucune tâche ci-dessus ne dépend d'une signature `organizationId` sur `CreditSaleRepository`/`InstallmentRepository`. Ne pas introduire une telle dépendance pendant le codage.

## Contrat technique

### 1. `PaymentSpecifications.java` — nouvelle méthode

```java
public static Specification<Payment> inOrganization(Long organizationId) {
    if (organizationId == null) {
        return null;
    }
    return (root, query, cb) ->
            cb.equal(root.get("sale").get("shop").get("organization").get("id"), organizationId);
}
```
(`cb.equal`, pas `.in()` : `organizationId` est une valeur unique, contrairement à `inShops` qui reçoit une liste.)

### 2. `PaymentRepository.java` — signatures avant/après

Avant :
```java
@Query("""
        SELECT p FROM Payment p
        JOIN FETCH p.sale s
        JOIN FETCH s.customer
        JOIN FETCH s.product
        WHERE p.paymentDate BETWEEN :from AND :to
          AND s.shop.id IN :shopIds
        ORDER BY p.paymentDate DESC, p.id DESC
        """)
List<Payment> findBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                   @Param("shopIds") List<Long> shopIds);

@Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p "
        + "WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds")
BigDecimal sumBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                               @Param("shopIds") List<Long> shopIds);

@Query("SELECT COUNT(p) FROM Payment p "
        + "WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds")
long countBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                           @Param("shopIds") List<Long> shopIds);
```

Après (attention à l'alias : `findBetweenForShops` a déjà l'alias `s` via `JOIN FETCH p.sale s` → utiliser `s.shop.organization.id` ; `sumBetweenForShops`/`countBetweenForShops` n'ont pas d'alias `s` → utiliser `p.sale.shop.organization.id`) :

```java
@Query("""
        SELECT p FROM Payment p
        JOIN FETCH p.sale s
        JOIN FETCH s.customer
        JOIN FETCH s.product
        WHERE p.paymentDate BETWEEN :from AND :to
          AND s.shop.id IN :shopIds
          AND s.shop.organization.id = :organizationId
        ORDER BY p.paymentDate DESC, p.id DESC
        """)
List<Payment> findBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                   @Param("shopIds") List<Long> shopIds,
                                   @Param("organizationId") Long organizationId);

@Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p "
        + "WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds "
        + "AND p.sale.shop.organization.id = :organizationId")
BigDecimal sumBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                               @Param("shopIds") List<Long> shopIds,
                               @Param("organizationId") Long organizationId);

@Query("SELECT COUNT(p) FROM Payment p "
        + "WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds "
        + "AND p.sale.shop.organization.id = :organizationId")
long countBetweenForShops(@Param("from") LocalDate from, @Param("to") LocalDate to,
                           @Param("shopIds") List<Long> shopIds,
                           @Param("organizationId") Long organizationId);
```

Javadoc à ajouter (aucun changement de signature) sur les 5 méthodes suivantes, formulation type :
```java
/**
 * Aucun filtre organisation direct : l'appelant doit garantir l'accès au client
 * en amont (voir PaymentService.findByCustomer / CustomerProfileService.profile).
 */
List<Payment> findByCustomer(@Param("customerId") Long customerId);
```
(adapter le texte pour `findBySale`, `sumByCustomer`, `findBySaleIdOrderByPaymentDateAscIdAsc` — appelé uniquement après un `assertAccessible` déjà exécuté dans `PaymentService.delete` — et `findByClientRequestId` — protégé par `assertAccessible` juste après lecture dans `PaymentService.register`).

### 3. `PaymentService.java` — `search`

Avant :
```java
Page<Payment> page = paymentRepository.findAll(
        Specs.combine(
                PaymentSpecifications.matches(search),
                PaymentSpecifications.hasMethod(method),
                PaymentSpecifications.paidFrom(from),
                PaymentSpecifications.paidTo(to),
                PaymentSpecifications.forCustomer(customerId),
                PaymentSpecifications.inShops(currentShopContext.accessibleShopIds())),
        pageable);
```

Après :
```java
Page<Payment> page = paymentRepository.findAll(
        Specs.combine(
                PaymentSpecifications.matches(search),
                PaymentSpecifications.hasMethod(method),
                PaymentSpecifications.paidFrom(from),
                PaymentSpecifications.paidTo(to),
                PaymentSpecifications.forCustomer(customerId),
                PaymentSpecifications.inShops(currentShopContext.accessibleShopIds()),
                PaymentSpecifications.inOrganization(currentShopContext.currentOrganizationId())),
        pageable);
```

### 4. `DashboardService.java` — `overview()`

Avant (extrait) :
```java
List<Long> shopIds = currentShopContext.resolveReadFilter();
...
List<PaymentResponse> todayPayments = paymentRepository.findBetweenForShops(today, today, shopIds).stream()
...
paymentRepository.sumBetweenForShops(monthStart, monthEnd, shopIds),
paymentRepository.sumBetweenForShops(today, today, shopIds),
paymentRepository.countBetweenForShops(today, today, shopIds),
```

Après :
```java
List<Long> shopIds = currentShopContext.resolveReadFilter();
Long organizationId = currentShopContext.currentOrganizationId();
...
List<PaymentResponse> todayPayments = paymentRepository.findBetweenForShops(today, today, shopIds, organizationId).stream()
...
paymentRepository.sumBetweenForShops(monthStart, monthEnd, shopIds, organizationId),
paymentRepository.sumBetweenForShops(today, today, shopIds, organizationId),
paymentRepository.countBetweenForShops(today, today, shopIds, organizationId),
```

### 5. `ReportService.java` — `build(...)` et `payments(...)`

Avant :
```java
List<Long> shopIds = currentShopContext.resolveReadFilter();
return switch (type) {
    case DAILY_PAYMENTS -> payments(ReportType.DAILY_PAYMENTS, "Paiements du jour",
            defaultDate(from), defaultDate(from), shopIds);
    case MONTHLY_PAYMENTS -> {
        ...
        yield payments(ReportType.MONTHLY_PAYMENTS, "Paiements du mois",
                from == null ? month.atDay(1) : from,
                to == null ? month.atEndOfMonth() : to, shopIds);
    }
    ...
};
```
```java
private ReportData payments(ReportType type, String title, LocalDate from, LocalDate to, List<Long> shopIds) {
    List<Payment> payments = paymentRepository.findBetweenForShops(from, to, shopIds);
    ...
}
```

Après :
```java
List<Long> shopIds = currentShopContext.resolveReadFilter();
Long organizationId = currentShopContext.currentOrganizationId();
return switch (type) {
    case DAILY_PAYMENTS -> payments(ReportType.DAILY_PAYMENTS, "Paiements du jour",
            defaultDate(from), defaultDate(from), shopIds, organizationId);
    case MONTHLY_PAYMENTS -> {
        ...
        yield payments(ReportType.MONTHLY_PAYMENTS, "Paiements du mois",
                from == null ? month.atDay(1) : from,
                to == null ? month.atEndOfMonth() : to, shopIds, organizationId);
    }
    ...
};
```
```java
private ReportData payments(ReportType type, String title, LocalDate from, LocalDate to,
                             List<Long> shopIds, Long organizationId) {
    List<Payment> payments = paymentRepository.findBetweenForShops(from, to, shopIds, organizationId);
    ...
}
```

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Aucun endpoint paiement n'expose de donnée d'une autre organisation, y compris via `findByCustomer`/`findBySale` | Unitaire : `PaymentSpecificationsTest.inOrganizationFiltersOnSaleShopOrganizationId` (nouveau) vérifie la jointure `sale.shop.organization.id`. Unitaire : nouveau test `PaymentServiceTest` vérifiant que `search` appelle `currentShopContext.currentOrganizationId()`. Pour `findByCustomer`/`findBySale` : couverture déjà existante et suffisante — `PaymentServiceTest.findBySaleRejectsWhenSaleNotAccessible` (existant, inchangé) prouve que `findBySale` reste bloqué en amont par `assertAccessible` ; aucun test dédié supplémentaire requis puisque ces méthodes ne changent pas (documenté par javadoc, pas par un nouveau test — cf. design.md, Décisions clés). |
| Instance mono-tenant : comportement strictement identique | Unitaire : les 3 tests existants de `DashboardServiceTest` et les tests `ReportServiceTest` (`dailyPayments_usesResolvedShopIds`, `monthlyPayments_usesResolvedShopIds`, `outstanding_monoShopExcludesOtherShopRows`) doivent continuer à passer une fois adaptés à la nouvelle arité — avec `currentOrganizationId()` stubbé à une valeur constante, les résultats vérifiés restent identiques à avant #38. Aucune vraie base disponible (`PaymentSpecificationsTest` déjà 100% mock, confirmé) : pas de test d'intégration possible ici, cette garantie reste structurelle (une seule valeur d'organisation ⇒ `inOrganization`/le filtre SQL ne retire jamais de ligne). |

Détail des tests à ajouter/modifier :

1. **`PaymentSpecificationsTest.java`** (nouveaux tests, même patron mock Root/CriteriaBuilder/Predicate que `inShopsFiltersOnSaleShopId`) :
   - `inOrganizationReturnsNullWhenNull` : `assertThat(PaymentSpecifications.inOrganization(null)).isNull();`
   - `inOrganizationFiltersOnSaleShopOrganizationId` : mock `root.get("sale")` → `salePath`, `salePath.get("shop")` → `shopPath`, `shopPath.get("organization")` → `orgPath`, `orgPath.get("id")` → `idPath`, `cb.equal(idPath, 5L)` → `predicate` ; vérifier que `specification.toPredicate(root, query, cb)` retourne `predicate` et que chaque `.get(...)`/`cb.equal(...)` a été appelé.

2. **`PaymentServiceTest.java`** (nouveau test — `search` n'a aujourd'hui aucun test dans ce fichier) :
   - `search_combinesOrganizationFilter` : stub `currentShopContext.accessibleShopIds()` → `List.of(1L)`, `currentShopContext.currentOrganizationId()` → `10L`, `paymentRepository.findAll(any(Specification.class), any(Pageable.class))` → `Page.empty()` (import `org.springframework.data.domain.Page`/`PageRequest`) ; appeler `paymentService.search(null, null, null, null, null, PageRequest.of(0, 10))` ; `verify(currentShopContext).currentOrganizationId();`.

3. **`DashboardServiceTest.java`** — adapter arité (compilation) :
   - `setUp()` : `findBetweenForShops(any(), any(), any())` → `findBetweenForShops(any(), any(), any(), any())` ; idem `sumBetweenForShops`/`countBetweenForShops` (ajouter un 4ᵉ `any()`).
   - Ajouter `when(currentShopContext.currentOrganizationId()).thenReturn(100L);` dans `setUp()` (couvre les 3 tests, `MockitoSettings.LENIENT` déjà en place).
   - `monoShop_isNotConsolidated` : `verify(paymentRepository).findBetweenForShops(any(), any(), eq(List.of(1L)), eq(100L));`.

4. **`ReportServiceTest.java`** — adapter arité (compilation) :
   - `setUp()` : ajouter `when(currentShopContext.currentOrganizationId()).thenReturn(200L);`.
   - `dailyPayments_usesResolvedShopIds` : `when(paymentRepository.findBetweenForShops(any(), any(), eq(List.of(1L)), eq(200L)))...` et `verify(paymentRepository).findBetweenForShops(any(), any(), eq(List.of(1L)), eq(200L));`.
   - `monthlyPayments_usesResolvedShopIds` : même adaptation avec `eq(List.of(1L, 2L))` et `eq(200L)`.

## Écarts identifiés
Aucun écart entre `design.md` et le ticket #38 : le design couvre les deux critères d'acceptation, l'audit des appelants de `findByCustomer`/`findBySale`/`sumByCustomer` est exhaustif et vérifié par lecture directe du code sur cette branche, et la mise en garde sur la non-dépendance à #37 est confirmée par l'état réel du dépôt (`InstallmentSpecifications` présent sur la branche mais sans méthode `inOrganization`, `CreditSaleRepository`/`InstallmentRepository` sans paramètre `organizationId`).
