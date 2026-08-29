# Spec — #36 Multi-tenant 3/10 — Propagation Customer/Product

## Résumé

Ajouter un filtre explicite par organisation (`shop.organization.id`) aux méthodes de recherche/liste de `Customer`/`Product` et corriger la fuite inter-organisation réelle de `LegacyImportService.resolveProduct`, sans toucher au comportement mono-tenant existant.

## Tâches

- [ ] `backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java` — ajouter une méthode publique `currentOrganizationId()` qui retourne `currentUser().getOrganization().getId()`. Purement additif, aucune méthode existante modifiée.
- [ ] `backend/src/main/java/com/creditflow/customer/repository/CustomerSpecifications.java` — ajouter `public static Specification<Customer> inOrganization(Long organizationId)` sur le modèle exact de `inShops` (retourne `null` si `organizationId == null`, sinon `(root, query, cb) -> cb.equal(root.get("shop").get("organization").get("id"), organizationId)`).
- [ ] `backend/src/main/java/com/creditflow/product/repository/ProductSpecifications.java` — même ajout `inOrganization(Long organizationId)`, même patron.
- [ ] `backend/src/main/java/com/creditflow/customer/repository/CustomerRepository.java` — ajouter le paramètre `organizationId` à la requête `@Query` `quickSearch` : `AND c.shop.id IN :shopIds AND c.shop.organization.id = :organizationId`, nouveau paramètre `@Param("organizationId") Long organizationId` en 3ᵉ position (avant `Pageable`).
- [ ] `backend/src/main/java/com/creditflow/product/repository/ProductRepository.java` :
  - ajouter le même paramètre `organizationId` à `quickSearch` (même patron que Customer) ;
  - ajouter `organizationId` à `findAllCategories` (`AND p.shop.organization.id = :organizationId`) ;
  - remplacer `Optional<Product> findFirstByNameIgnoreCase(String name)` par `Optional<Product> findFirstByNameIgnoreCaseAndShop_Id(String name, Long shopId)` (dérivée Spring Data, même convention de nommage que `countByShop_IdIn` déjà présente dans `CustomerRepository`).
- [ ] `backend/src/main/java/com/creditflow/customer/service/CustomerService.java` — dans `search`, `quickSearch`, `findAllForSelect`, combiner `CustomerSpecifications.inOrganization(currentShopContext.currentOrganizationId())` (via `Specs.combine(...)` pour `search`/`findAllForSelect`) et passer `currentShopContext.currentOrganizationId()` en argument de `customerRepository.quickSearch(...)`.
- [ ] `backend/src/main/java/com/creditflow/product/service/ProductService.java` — même changement pour `search`, `quickSearch`, `findAllForSelect`, et `categories` (passer `currentOrganizationId()` à `productRepository.findAllCategories(...)`).
- [ ] `backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java` — modifier la signature privée `findProductByName(String name)` en `findProductByName(String name, Long targetShopId)`, implémentée par `productRepository.findFirstByNameIgnoreCaseAndShop_Id(name.trim(), targetShopId)`. Mettre à jour les deux points d'appel : ligne ~82 (`importLegacySales`, `targetShopId` déjà résolu ligne 63) et ligne ~184 (`resolveProduct`, `targetShopId` déjà en paramètre).
- [ ] `backend/src/test/java/com/creditflow/customer/repository/CustomerSpecificationsTest.java` — ajouter `inOrganizationReturnsNullWhenNull` et `inOrganizationFiltersOnShopOrganizationId` (mock `Root`/`Path` pour `shop.organization.id`, patron identique aux tests `inShops` existants).
- [ ] `backend/src/test/java/com/creditflow/product/repository/ProductSpecificationsTest.java` — mêmes deux tests pour `ProductSpecifications.inOrganization`.
- [ ] `backend/src/test/java/com/creditflow/customer/service/CustomerServiceTest.java` — ajouter `when(currentShopContext.currentOrganizationId()).thenReturn(...)` dans `setUp()` (nécessaire dès que `search`/`quickSearch`/`findAllForSelect` l'invoquent) ; ajouter un test `quickSearchPassesOrganizationIdToRepository` vérifiant `verify(customerRepository).quickSearch(eq(search), eq(shopIds), eq(organizationId), any())`.
- [ ] `backend/src/test/java/com/creditflow/product/service/ProductServiceTest.java` — même ajout de stub `currentOrganizationId()` dans `setUp()` (nécessaire même pour les tests existants qui n'appellent pas `search`/`categories`, à cause de `@MockitoSettings(strictness = Strictness.LENIENT)` déjà en place — pas bloquant mais à faire par cohérence) ; ajouter `quickSearchPassesOrganizationIdToRepository` et `categoriesPassesOrganizationIdToRepository`.
- [ ] `backend/src/test/java/com/creditflow/dataimport/service/LegacyImportServiceTest.java` — remplacer les deux stubs `productRepository.findFirstByNameIgnoreCase(...)` (lignes ~92 et ~129) par `productRepository.findFirstByNameIgnoreCaseAndShop_Id(name, 1L)` ; ajouter un nouveau test `doesNotReuseProductWithSameNameFromAnotherShop` : un produit "Tecno Spark" existe en base pour `shopId=2` (autre boutique), `targetShopId=1` ; stubber `findFirstByNameIgnoreCaseAndShop_Id("Tecno Spark", 1L)` → `Optional.empty()` ; vérifier qu'un nouveau `Product` est créé et sauvegardé avec `shop.getId() == 1L` (pas de réutilisation du produit de la boutique 2).

## Contrat technique

```java
// CurrentShopContext.java (ajout)
public Long currentOrganizationId() {
    return currentUser().getOrganization().getId();
}
```

```java
// CustomerSpecifications.java (ajout)
public static Specification<Customer> inOrganization(Long organizationId) {
    if (organizationId == null) {
        return null;
    }
    return (root, query, cb) -> cb.equal(root.get("shop").get("organization").get("id"), organizationId);
}
```

```java
// ProductSpecifications.java (ajout) — identique, remplacer Customer par Product
public static Specification<Product> inOrganization(Long organizationId) { /* même corps */ }
```

```java
// CustomerRepository.java — quickSearch modifiée
@Query("""
        SELECT c FROM Customer c
        WHERE (LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE LOWER(CONCAT('%', :search, '%'))
           OR c.phone LIKE CONCAT('%', :search, '%'))
          AND c.shop.id IN :shopIds
          AND c.shop.organization.id = :organizationId
        ORDER BY c.lastName ASC
        """)
List<Customer> quickSearch(@Param("search") String search, @Param("shopIds") List<Long> shopIds,
                            @Param("organizationId") Long organizationId, Pageable pageable);
```

```java
// ProductRepository.java — modifications
Optional<Product> findFirstByNameIgnoreCaseAndShop_Id(String name, Long shopId);

@Query("SELECT DISTINCT p.category FROM Product p WHERE p.shop.id IN :shopIds "
     + "AND p.shop.organization.id = :organizationId ORDER BY p.category")
List<String> findAllCategories(@Param("shopIds") List<Long> shopIds, @Param("organizationId") Long organizationId);

@Query("""
        SELECT p FROM Product p
        WHERE (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(p.category) LIKE LOWER(CONCAT('%', :search, '%')))
          AND p.shop.id IN :shopIds
          AND p.shop.organization.id = :organizationId
        ORDER BY p.name
        """)
List<Product> quickSearch(@Param("search") String search, @Param("shopIds") List<Long> shopIds,
                           @Param("organizationId") Long organizationId, Pageable pageable);
```

```java
// LegacyImportService.java
private Optional<Product> findProductByName(String name, Long targetShopId) {
    return productRepository.findFirstByNameIgnoreCaseAndShop_Id(name.trim(), targetShopId);
}
// appels : findProductByName(row.productName(), targetShopId) dans importLegacySales() et resolveProduct()
```

### Décision — `countByShop_IdIn` (Customer)

**Tranché : ne pas ajouter de filtre `organizationId`.** Contrairement à `search`/`quickSearch`/`findAllForSelect`/`categories`, `countByShop_IdIn` :
- ne retourne qu'un agrégat (compteur), aucune donnée client/produit individuelle n'est exposée en cas d'erreur amont sur `shopIds` ;
- n'est appelé que depuis `DashboardService.overview()` avec `currentShopContext.resolveReadFilter()`, garanti scopé par organisation par construction (même invariant que celui sur lequel s'appuie déjà `getEntity(id)`/`assertAccessible`, porté par #34/#35, hors périmètre de ce ticket) ;
- ajouter le filtre imposerait de faire évoluer une signature utilisée uniquement pour un compteur de tableau de bord, pour un gain de défense en profondeur marginal comparé aux méthodes de recherche qui, elles, retournent directement des enregistrements `Customer`/`Product`.

**Correction du design** : `design.md` mentionne un « équivalent Product (via `DashboardService`) » à `countByShop_IdIn` — vérification faite, il n'existe pas. `DashboardService.overview()` (`backend/src/main/java/com/creditflow/dashboard/service/DashboardService.java`, lignes 61-73) n'appelle que `customerRepository.countByShop_IdIn(shopIds)` et `saleRepository.countByShop_IdIn(shopIds)` / `countByStatusAndShop_IdIn(...)` — aucun compteur `Product`. Aucune action requise côté `ProductRepository` pour ce point.

## Plan de tests

| Critère d'acceptation | Test | Niveau |
|---|---|---|
| Aucun endpoint client/produit n'expose de donnée d'une autre organisation | `CustomerSpecificationsTest.inOrganizationFiltersOnShopOrganizationId` / `inOrganizationReturnsNullWhenNull` | Unitaire (mock `Root`/`CriteriaBuilder`, patron déjà en place) |
| idem | `ProductSpecificationsTest.inOrganizationFiltersOnShopOrganizationId` / `inOrganizationReturnsNullWhenNull` | Unitaire |
| idem | `CustomerServiceTest.quickSearchPassesOrganizationIdToRepository` — vérifie que `search`/`quickSearch`/`findAllForSelect` invoquent `currentShopContext.currentOrganizationId()` et le propagent au repository/à la `Specification` | Unitaire (mock `CurrentShopContext`) |
| idem | `ProductServiceTest.quickSearchPassesOrganizationIdToRepository` / `categoriesPassesOrganizationIdToRepository` | Unitaire |
| idem (fuite réelle corrigée) | `LegacyImportServiceTest.doesNotReuseProductWithSameNameFromAnotherShop` — un produit homonyme existant dans une autre boutique n'est jamais réutilisé, un nouveau produit est créé sur la boutique cible | Unitaire (mock `ProductRepository`) |
| Instance mono-tenant : comportement strictement identique | Aucun test dédié nouveau à isoler : la garantie repose sur (a) l'intégralité de la suite `CustomerServiceTest`/`ProductServiceTest`/`CustomerSpecificationsTest`/`ProductSpecificationsTest`/`LegacyImportServiceTest` existante restant verte après le changement (comportement shop-scopé inchangé), et (b) le fait que `inOrganization(...)` n'ajoute qu'un `AND` supplémentaire sur `shop.organization.id` — en mono-tenant, tous les enregistrements partagent la même organisation, donc ce prédicat est toujours vrai et ne restreint jamais le résultat. Ce raisonnement est structurel (une seule `Organization` en base) et n'est pas vérifiable par un test unitaire à base de mocks sans réintroduire une base réelle (`@DataJpaTest`), explicitement hors périmètre. **Vérification manuelle recommandée avant merge** : lancer `search`/`quickSearch`/`findAllForSelect`/`categories` sur l'environnement mono-tenant existant (une seule organisation en base) et confirmer que les résultats sont inchangés par rapport à avant #36. |

**Niveau de couverture assumé** : comme le reste du backend, aucun test `@DataJpaTest` n'est introduit. Tous les tests `Specifications` restent des mocks `Root`/`CriteriaBuilder`/`Predicate` ; tous les tests service restent des mocks `CurrentShopContext`/`*Repository`. Le test « accès inter-organisation » est donc, comme prévu par le design, un test unitaire vérifiant que le bon prédicat/paramètre est construit et transmis — pas un test d'intégration bout-en-bout avec deux organisations réelles en base.

## Écarts identifiés

- **Inexactitude du design corrigée** : `design.md` affirme que `CustomerRepository.countByShop_IdIn` a un « équivalent Product (via `DashboardService`) ». Vérification faite dans `DashboardService.overview()` : il n'existe aucun compteur `Product` — seuls `Customer` et `CreditSale` sont comptés. Aucune modification `ProductRepository` liée à ce point.
- **Faille hors périmètre, à ne PAS corriger dans ce ticket** (signalée par le design, rappelée ici pour visibilité du codeur/reviewer) : `UserService.resolveShops` et `ShopService` (list/findById/update/delete) ne filtrent par organisation nulle part. Un `ADMIN` peut donc assigner à un utilisateur de son organisation une boutique appartenant à une autre organisation, ce qui corromprait la garantie sur laquelle `CurrentShopContext.accessibleShopIds()` s'appuie (et donc, transitivement, tout le patron `getEntity(id)` + `assertAccessible` de `CustomerService`/`ProductService`, ainsi que la décision de ce ticket de ne pas filtrer `getEntity(id)` par organisation). C'est un territoire #34/#35, pas de ce ticket — ticket de correction dédié à prévoir, ne pas traiter ici.
- Pour rappel (déjà noté par le design, non actionnable ici) : `Customer.phone`/`Customer.cniNumber` restent uniques globalement (fuite d'information mineure préexistante) ; hors périmètre.

## Hors périmètre (rappel du design)

- Correction de `UserService.resolveShops` et `ShopService` (cf. Écarts identifiés).
- Changement du scope d'unicité de `Customer.phone`/`Customer.cniNumber`.
- Toute dénormalisation (colonne `organization_id` directe sur `Customer`/`Product`) et migration Flyway associée.
- Postgres Row-Level Security.
- Propagation à CreditSale/Installment/Payment/StockReception/AuditLog — tickets #37 à #39.
