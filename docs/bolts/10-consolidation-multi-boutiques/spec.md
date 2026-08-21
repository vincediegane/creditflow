# Spec — #10 Consolidation multi-boutiques

## Résumé

Introduire une entité `Shop`, rattacher `Customer`/`Product`/`CreditSale` à une boutique et `User` à un ensemble de boutiques, puis filtrer transversalement (recherche, lecture directe par id, agrégats, dashboard, rapports, relances, reprise de données) via un composant `CurrentShopContext`, de façon strictement transparente pour un utilisateur mono-boutique.

## Sommaire des phases

- Phase 1 — Entité `Shop`, migration, CRUD boutiques (ADMIN)
- Phase 2 — `User` ↔ `Shop`, `CurrentShopContext`, réponses d'authentification
- Phase 3 — Filtrage Customer / Product / CreditSale / Payment / Installment / Relances / Recherche globale
- Phase 4 — Dashboard consolidé
- Phase 5 — Rapports
- Phase 6 — Reprise de données (dataimport) + Frontend

Chaque phase est livrable et testable indépendamment, dans cet ordre (chaque phase suivante dépend des précédentes).

---

## Phase 1 — Entité Shop, migration, CRUD boutiques

### Tâches

- [ ] `backend/src/main/resources/db/migration/V10__shops.sql` — nouvelle migration (V10 est le prochain numéro libre : V1 à V7, V9 existent déjà, **pas de V8**, ne pas essayer de le recréer). Contenu exact en section Contrat technique ci-dessous.
- [ ] `backend/src/main/java/com/creditflow/shop/domain/Shop.java` — entité `@Entity @Table(name = "shops")`, étend `Auditable` (comme `Customer`/`Product`), champs `id`, `name` (unique, obligatoire, longueur 120), `address` (nullable, 255), `phone` (nullable, 30), `active` (boolean, défaut `true`). Pattern Lombok identique à `Customer.java` (`@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`).
- [ ] `backend/src/main/java/com/creditflow/shop/repository/ShopRepository.java` — `extends JpaRepository<Shop, Long>` avec :
  - `List<Shop> findAllByOrderByNameAsc()`
  - `boolean existsByNameIgnoreCase(String name)`
  - `boolean existsByNameIgnoreCaseAndIdNot(String name, Long id)`
  - `List<Shop> findAllByActiveTrueOrderByNameAsc()` (utilisé par `CurrentShopContext` pour le mode super-admin)
- [ ] `backend/src/main/java/com/creditflow/shop/dto/ShopSummary.java` — `record ShopSummary(Long id, String name)`. Placé dans `shop.dto` mais importé par `auth.dto` (même pattern que `dashboard.dto` important `notification.dto.LateCustomerResponse`).
- [ ] `backend/src/main/java/com/creditflow/shop/dto/ShopRequest.java` — `record ShopRequest(@NotBlank @Size(max=120) String name, @Size(max=255) String address, @Size(max=30) String phone, Boolean active)`, pattern identique à `ProductRequest`.
- [ ] `backend/src/main/java/com/creditflow/shop/dto/ShopResponse.java` — `record ShopResponse(Long id, String name, String address, String phone, boolean active, LocalDateTime createdAt, String createdBy, String updatedBy)`.
- [ ] `backend/src/main/java/com/creditflow/shop/mapper/ShopMapper.java` — MapStruct, pattern identique à `ProductMapper` (`@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)`, `toEntity`, `updateEntity` avec `@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)`, `toResponse`).
- [ ] `backend/src/main/java/com/creditflow/shop/service/ShopService.java` — `list()` (`findAllByOrderByNameAsc`), `findById(id)`/`getEntity(id)` (`ResourceNotFoundException.of("Boutique", id)`), `create(request)` (rejette nom dupliqué via `BusinessRuleException` — message `"Une boutique utilise déjà le nom " + name"`), `update(id, request)`, `delete(id)` (laisser la contrainte FK protéger : une boutique encore référencée par `customers`/`products`/`credit_sales`/`user_shops` remonte une `DataIntegrityViolationException` déjà gérée en 409 par `GlobalExceptionHandler` — ne pas ajouter de vérification manuelle redondante).
- [ ] `backend/src/main/java/com/creditflow/shop/web/ShopController.java` — `@RequestMapping("/api/shops")`, `@PreAuthorize("hasRole('ADMIN')")` sur la classe (pattern identique à `UserController`), endpoints `GET` (list), `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`.
- [ ] `backend/src/test/java/com/creditflow/shop/web/ShopControllerSecurityTest.java` — pattern identique à `ProductControllerSecurityTest` (étend `AbstractWebMvcSecurityTest`, `@WebMvcTest(ShopController.class)`) : un `SELLER` reçoit `403` sur `POST`/`PUT`/`DELETE`/`GET`.
- [ ] `backend/src/test/java/com/creditflow/shop/service/ShopServiceTest.java` — pattern identique à `CustomerServiceTest` (Mockito, `@ExtendWith(MockitoExtension.class)`) : refuse un nom dupliqué, crée une boutique valide, signale une boutique introuvable.

Cette phase est livrable seule : elle n'introduit aucune colonne `shop_id` sur les tables existantes et ne change le comportement d'aucun endpoint existant.

---

## Phase 2 — User ↔ Shop, CurrentShopContext, authentification

### Tâches

- [ ] `backend/src/main/java/com/creditflow/auth/domain/User.java` — ajouter :
  ```java
  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(name = "user_shops",
          joinColumns = @JoinColumn(name = "user_id"),
          inverseJoinColumns = @JoinColumn(name = "shop_id"))
  @Builder.Default
  private Set<Shop> shops = new HashSet<>();
  ```
- [ ] `backend/src/main/java/com/creditflow/auth/repository/UserRepository.java` — ajouter `@EntityGraph(attributePaths = "shops") Optional<User> findByUsernameIgnoreCase(String username)` (override, évite le N+1 à chaque résolution de `CurrentShopContext`, appelée sur quasi tout endpoint).
- [ ] `backend/src/main/java/com/creditflow/auth/dto/UserRequest.java` — ajouter `List<Long> shopIds` (nullable côté JSON, validé en service, pas en annotation Bean Validation car la règle dépend du `role`).
- [ ] `backend/src/main/java/com/creditflow/auth/dto/UserResponse.java` — ajouter `List<ShopSummary> shops` (les boutiques explicitement assignées, liste vide pour un ADMIN super-admin).
- [ ] `backend/src/main/java/com/creditflow/auth/dto/AuthResponse.java` — ajouter `List<ShopSummary> accessibleShops` (champ frère de `user`, pas dans `UserResponse` : c'est la liste **résolue** — cf. `CurrentShopContext.accessibleShops()` — utilisée par le frontend pour peupler le sélecteur de boutique juste après connexion).
- [ ] `backend/src/main/java/com/creditflow/auth/service/UserService.java` :
  - `create(request)` : si `request.role() == Role.SELLER`, `request.shopIds()` doit être non nul et non vide (`BusinessRuleException("Un vendeur doit être rattaché à au moins une boutique")`). Résoudre chaque id via `ShopRepository.findById`, `ResourceNotFoundException.of("Boutique", id)` si absent. Un ADMIN peut avoir une liste vide (super-admin) ou une liste non vide (admin de boutique).
  - nouvelle méthode `updateShops(Long id, List<Long> shopIds, String currentUsername)` : même validation (SELLER → non vide), assigne `user.setShops(...)`, sauvegarde, retourne `UserResponse`.
  - `toResponse(user)` inclut désormais `user.getShops().stream().map(s -> new ShopSummary(s.getId(), s.getName())).sorted(...).toList()`.
- [ ] `backend/src/main/java/com/creditflow/auth/dto/UserShopsRequest.java` — nouveau, `record UserShopsRequest(@NotNull List<Long> shopIds)`.
- [ ] `backend/src/main/java/com/creditflow/auth/web/UserController.java` — nouvel endpoint `PATCH /api/users/{id}/shops` → `userService.updateShops(id, request.shopIds(), principal.getUsername())`, `@PreAuthorize` hérité de la classe (déjà `hasRole('ADMIN')`).
- [ ] `backend/src/main/java/com/creditflow/auth/service/AuthService.java` — `toResponse(user)` inchangé (garde `UserResponse` tel quel avec `shops`) ; `login(...)` construit `AuthResponse` avec le nouveau champ `accessibleShops` via `currentShopContext.accessibleShops()` **après** authentification réussie (le `SecurityContext` est déjà peuplé à ce stade par `authenticationManager.authenticate(...)`, donc `CurrentUser.username()` répond correctement) ; `currentUser(username)` (`GET /api/auth/me`, à vérifier dans `AuthController`) doit aussi inclure `accessibleShops` — si `AuthController.me()` renvoie un `UserResponse` et non un `AuthResponse`, ajouter un nouvel endpoint ou élargir la réponse existante en cohérence avec ce qui existe (vérifier `AuthController.java` avant d'implémenter, ne pas dupliquer de logique).
- [ ] `backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java` — nouveau composant, signatures exactes :
  ```java
  package com.creditflow.common.security;

  @Component
  @RequiredArgsConstructor
  public class CurrentShopContext {

      public static final String SHOP_HEADER = "X-Shop-Id";

      private final UserRepository userRepository;
      private final ShopRepository shopRepository;

      /** Boutiques accessibles à l'utilisateur authentifié courant (jamais vide pour un compte valide). */
      public List<Long> accessibleShopIds();

      /** Résumés (id + nom) des boutiques accessibles — utilisé par AuthResponse. */
      public List<ShopSummary> accessibleShops();

      /**
       * Filtre de lecture pour les écrans consolidables (dashboard, rapports) :
       * - si l'en-tête X-Shop-Id est présent et pointe vers une boutique accessible : liste à un élément ;
       * - si l'en-tête est présent mais non accessible : BusinessRuleException (422)
       *   "La boutique demandée (X-Shop-Id=<id>) n'est pas accessible pour votre compte." ;
       * - si l'en-tête est absent : accessibleShopIds() (vue consolidée si plusieurs boutiques).
       */
      public List<Long> resolveReadFilter();

      /**
       * Boutique cible pour une création (client, produit, vente, import) :
       * - en-tête X-Shop-Id si présent (doit être accessible, sinon BusinessRuleException 422 identique à resolveReadFilter) ;
       * - sinon, s'il n'existe qu'une seule boutique accessible, celle-ci (cas mono-boutique — comportement inchangé) ;
       * - sinon BusinessRuleException (422) :
       *   "Vous êtes rattaché à plusieurs boutiques : précisez la boutique cible via l'en-tête X-Shop-Id avant de créer cette ressource."
       */
      public Long shopIdForCreation();

      /**
       * Garde d'accès direct par identifiant : lève ResourceNotFoundException("Ressource introuvable")
       * si shopId n'appartient pas à accessibleShopIds() — ne révèle jamais si la ressource existe
       * dans une autre boutique.
       */
      public void assertAccessible(Long shopId);
  }
  ```
  Détail d'implémentation attendu pour `accessibleShopIds()` :
  1. `String username = CurrentUser.username();` — si `null`, lever `IllegalStateException` (ne doit jamais arriver derrière `SecurityConfig`, tous les endpoints consommateurs sont authentifiés).
  2. `User user = userRepository.findByUsernameIgnoreCase(username).orElseThrow(...)`.
  3. Si `user.getShops().isEmpty()` :
     - si `user.getRole() == Role.ADMIN` → `shopRepository.findAllByActiveTrueOrderByNameAsc()` (mode super-admin, toutes les boutiques actives) ;
     - sinon (`SELLER` sans boutique assignée — ne devrait pas arriver grâce à la validation `UserService`, mais defensive) → `BusinessRuleException("Aucune boutique n'est assignée à votre compte. Contactez votre administrateur.")`.
  4. Sinon → les ids de `user.getShops()`.
  - Lecture de l'en-tête `X-Shop-Id` via `RequestContextHolder.getRequestAttributes()` casté en `ServletRequestAttributes`, `null`-safe (retourne `Optional.empty()` hors contexte de requête HTTP, ce qui couvre les tests unitaires et les traitements batch). Un en-tête présent mais non numérique → `BusinessRuleException("En-tête X-Shop-Id invalide")`.
- [ ] `backend/src/test/java/com/creditflow/common/security/CurrentShopContextTest.java` — nouveau, Mockito sur `UserRepository`/`ShopRepository`, sans requête HTTP réelle (`RequestContextHolder` non initialisé → en-tête toujours absent) :
  - `accessibleShopIds()` retourne les boutiques assignées pour un `SELLER` avec une boutique.
  - `accessibleShopIds()` retourne toutes les boutiques actives pour un `ADMIN` sans assignation.
  - `accessibleShopIds()` retourne uniquement les boutiques assignées pour un `ADMIN` avec assignation explicite (admin de boutique, pas super-admin).
  - `shopIdForCreation()` retourne l'unique boutique accessible pour un utilisateur mono-boutique.
  - `shopIdForCreation()` lève `BusinessRuleException` pour un utilisateur multi-boutiques sans en-tête (utiliser `RequestContextHolder.setRequestAttributes(null)` explicitement dans le test pour simuler l'absence d'en-tête, ou mocker `RequestContextHolder` via une requête `MockHttpServletRequest` sans en-tête).
  - `resolveReadFilter()` retourne la liste complète (vue consolidée) en l'absence d'en-tête pour un utilisateur multi-boutiques.
  - `assertAccessible(shopId)` lève `ResourceNotFoundException` pour une boutique hors périmètre.

### Contrat technique — migration V10 (SQL exact)

```sql
-- =====================================================================
-- V10 - Consolidation multi-boutiques (#10)
-- =====================================================================

CREATE TABLE shops (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    address     VARCHAR(255),
    phone       VARCHAR(30),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    CONSTRAINT uk_shops_name UNIQUE (name)
);

-- Boutique par defaut : recoit toutes les donnees existantes lors du retro-remplissage.
INSERT INTO shops (name, active, created_at) VALUES ('Boutique principale', TRUE, NOW());

CREATE TABLE user_shops (
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, shop_id),
    CONSTRAINT fk_user_shops_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_shops_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE CASCADE
);

-- Rattache les vendeurs existants a la boutique par defaut (regle metier : un SELLER
-- doit toujours avoir au moins une boutique). Les ADMIN restent sans assignation :
-- ils deviennent automatiquement super-admin (acces a toutes les boutiques).
INSERT INTO user_shops (user_id, shop_id)
SELECT u.id, (SELECT id FROM shops ORDER BY id LIMIT 1)
FROM users u
WHERE u.role = 'SELLER';

-- customers.shop_id
ALTER TABLE customers ADD COLUMN shop_id BIGINT;
UPDATE customers SET shop_id = (SELECT id FROM shops ORDER BY id LIMIT 1);
ALTER TABLE customers ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE customers ADD CONSTRAINT fk_customers_shop FOREIGN KEY (shop_id) REFERENCES shops (id);
CREATE INDEX idx_customers_shop ON customers (shop_id);

-- products.shop_id
ALTER TABLE products ADD COLUMN shop_id BIGINT;
UPDATE products SET shop_id = (SELECT id FROM shops ORDER BY id LIMIT 1);
ALTER TABLE products ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE products ADD CONSTRAINT fk_products_shop FOREIGN KEY (shop_id) REFERENCES shops (id);
CREATE INDEX idx_products_shop ON products (shop_id);

-- credit_sales.shop_id
ALTER TABLE credit_sales ADD COLUMN shop_id BIGINT;
UPDATE credit_sales SET shop_id = (SELECT id FROM shops ORDER BY id LIMIT 1);
ALTER TABLE credit_sales ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE credit_sales ADD CONSTRAINT fk_credit_sales_shop FOREIGN KEY (shop_id) REFERENCES shops (id);
CREATE INDEX idx_credit_sales_shop ON credit_sales (shop_id);
```

`payments` et `installments` ne reçoivent **aucune** colonne : ils restent filtrés par jointure vers `credit_sales.shop_id` (décision architecture confirmée).

---

## Phase 3 — Filtrage Customer / Product / CreditSale / Payment / Installment / Relances / Recherche globale

### 3.1 — Entités et Specifications

- [ ] `backend/src/main/java/com/creditflow/customer/domain/Customer.java` — ajouter `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "shop_id", nullable = false) private Shop shop;`
- [ ] `backend/src/main/java/com/creditflow/product/domain/Product.java` — même ajout (`shop`, `shop_id`, `optional = false`).
- [ ] `backend/src/main/java/com/creditflow/sale/domain/CreditSale.java` — même ajout.
- [ ] `backend/src/main/java/com/creditflow/customer/repository/CustomerSpecifications.java` — ajouter :
  ```java
  public static Specification<Customer> inShops(List<Long> shopIds) {
      if (shopIds == null || shopIds.isEmpty()) {
          return null;
      }
      return (root, query, cb) -> root.get("shop").get("id").in(shopIds);
  }
  ```
- [ ] `backend/src/main/java/com/creditflow/product/repository/ProductSpecifications.java` — même méthode `inShops`.
- [ ] `backend/src/main/java/com/creditflow/sale/repository/SaleSpecifications.java` — même méthode `inShops`.
- [ ] `backend/src/main/java/com/creditflow/payment/repository/PaymentSpecifications.java` — variante jointure :
  ```java
  public static Specification<Payment> inShops(List<Long> shopIds) {
      if (shopIds == null || shopIds.isEmpty()) {
          return null;
      }
      return (root, query, cb) -> root.get("sale").get("shop").get("id").in(shopIds);
  }
  ```
- [ ] `backend/src/main/java/com/creditflow/sale/repository/InstallmentSpecifications.java` — même méthode `inShops` via `root.get("sale").get("shop").get("id")`.
- [ ] `backend/src/test/java/com/creditflow/customer/repository/CustomerSpecificationsTest.java` (et équivalents Product/Sale/Payment/Installment si absents) — pattern Mockito Criteria identique à `SaleSpecificationsTest.java` : `inShops(null)` et `inShops(List.of())` retournent `null` ; `inShops(List.of(1L,2L))` génère un prédicat `IN` sur le bon chemin (`root.get("shop").get("id")` ou `root.get("sale").get("shop").get("id")`).

### 3.2 — Repositories : agrégats hors Specification

- [ ] `backend/src/main/java/com/creditflow/customer/repository/CustomerRepository.java` :
  - `quickSearch(String search, Pageable pageable)` → remplacer par `quickSearch(String search, List<Long> shopIds, Pageable pageable)`, ajouter `AND c.shop.id IN :shopIds` à la requête JPQL.
  - ajouter `long countByShop_IdIn(List<Long> shopIds)` (dashboard).
- [ ] `backend/src/main/java/com/creditflow/product/repository/ProductRepository.java` :
  - `quickSearch(...)` → même traitement avec `shopIds`.
  - `findAllCategories()` → `findAllCategories(List<Long> shopIds)`, ajouter `WHERE p.shop.id IN :shopIds`.
- [ ] `backend/src/main/java/com/creditflow/sale/repository/CreditSaleRepository.java` :
  - `countByStatus(SaleStatus status)` → `long countByStatusAndShop_IdIn(SaleStatus status, List<Long> shopIds)` (nom de méthode dérivée Spring Data, pas de `@Query` nécessaire).
  - ajouter `long countByShop_IdIn(List<Long> shopIds)` (dashboard, total contrats).
  - `sumRemainingByStatus(SaleStatus status)` → `sumRemainingByStatusForShops(SaleStatus status, List<Long> shopIds)`, `@Query("SELECT COALESCE(SUM(s.remainingAmount), 0) FROM CreditSale s WHERE s.status = :status AND s.shop.id IN :shopIds")`.
  - `findAllDetailed()` → `findAllDetailedForShops(List<Long> shopIds)`, `@Query("SELECT s FROM CreditSale s JOIN FETCH s.customer JOIN FETCH s.product WHERE s.shop.id IN :shopIds ORDER BY s.createdAt DESC")`.
  - `sumTotalPriceByCustomer` / `sumRemainingByCustomer` / `findByCustomer` : **inchangés** — un `customerId` valide implique déjà une boutique unique, et l'accès au client est vérifié en amont (voir 3.3).
- [ ] `backend/src/main/java/com/creditflow/payment/repository/PaymentRepository.java` :
  - `findBetween(from, to)` → `findBetweenForShops(LocalDate from, LocalDate to, List<Long> shopIds)`, ajouter `AND s.shop.id IN :shopIds`.
  - `sumBetween` → `sumBetweenForShops(..., List<Long> shopIds)`, `@Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.paymentDate BETWEEN :from AND :to AND p.sale.shop.id IN :shopIds")`.
  - `countBetween` → `countBetweenForShops(..., List<Long> shopIds)`, même principe.
  - `findByCustomer` / `findBySale` / `sumByCustomer` / `findBySaleIdOrderByPaymentDateAscIdAsc` : **inchangés** (mêmes raisons que ci-dessus, accès vérifié en amont).
- [ ] `backend/src/main/java/com/creditflow/sale/repository/InstallmentRepository.java` :
  - `findUpcoming(from, to)` → `findUpcomingForShops(from, to, List<Long> shopIds)`, ajouter `AND s.shop.id IN :shopIds`.
  - `findLate(reference)` → `findLateForShops(reference, List<Long> shopIds)`, même principe.
  - `countLate(reference)` → `countLateForShops(reference, List<Long> shopIds)`.
  - `sumLateAmount(reference)` → `sumLateAmountForShops(reference, List<Long> shopIds)`.
  - `countLateByCustomer` / `findBySaleIdOrderByNumberAsc` : **inchangés**.

### 3.3 — Services : injection du filtre + garde d'accès direct

- [ ] `backend/src/main/java/com/creditflow/customer/service/CustomerService.java` — injecter `CurrentShopContext currentShopContext` :
  - `search(...)` : ajouter `CustomerSpecifications.inShops(currentShopContext.accessibleShopIds())` à `Specs.combine(...)`.
  - `quickSearch(...)` : passer `currentShopContext.accessibleShopIds()` à `customerRepository.quickSearch(...)`.
  - `findAllForSelect()` : remplacer `customerRepository.findAll(Sort...)` par `customerRepository.findAll(Specs.combine(CustomerSpecifications.inShops(currentShopContext.accessibleShopIds())), Sort.by("lastName", "firstName"))` (`JpaSpecificationExecutor` supporte `findAll(Specification, Sort)`, aucune nouvelle méthode de repository nécessaire).
  - `getEntity(id)` : après le `findById`, ajouter `currentShopContext.assertAccessible(customer.getShop().getId());` avant de retourner l'entité — **ce garde couvre transitivement `findById`, `update`, `delete`, `uploadPhoto`, et `CustomerProfileService.profile` (qui appelle `customerService.findById` en premier)**.
  - `create(request)` : résoudre `Shop shop = shopRepository.getReferenceById(currentShopContext.shopIdForCreation());` (injecter `ShopRepository`), l'assigner via `customer.setShop(shop)` avant `save` (le mapper ignore le champ `shop`, non exposé dans `CustomerRequest` — c'est voulu, la boutique n'est jamais choisie par le formulaire client).
- [ ] `backend/src/main/java/com/creditflow/product/service/ProductService.java` — même traitement (`search`, `quickSearch`, `findAllForSelect`, `categories()` → passe `currentShopContext.accessibleShopIds()` à `findAllCategories`, `getEntity(id)` avec `assertAccessible`, `create(request)` avec `shopIdForCreation()`).
- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` :
  - `search(...)` : ajouter `SaleSpecifications.inShops(currentShopContext.accessibleShopIds())`.
  - `getEntity(id)` : après `findDetailById`, `currentShopContext.assertAccessible(sale.getShop().getId());` — couvre `findById`, `findDetail`, `cancel`, `delete`, `uploadAttachment`, `deleteAttachment`.
  - `findByCustomer(customerId)` : inchangé (le client est déjà vérifié en amont par l'appelant, cf. `CustomerProfileService`).
  - `create(request)` :
    1. `Long targetShopId = currentShopContext.shopIdForCreation();`
    2. `Customer customer = customerService.getEntity(request.customerId());` (déjà vérifié shop-accessible par 3.3 ci-dessus — mais **pas nécessairement égal à `targetShopId`** si l'ADMIN est super-admin multi-boutiques : ajouter la vérification explicite ci-dessous).
    3. `Product product = productService.getEntity(request.productId());` idem.
    4. **Cohérence boutique (point 4 de la mission)** : avant construction du `CreditSale`,
       ```java
       if (!customer.getShop().getId().equals(targetShopId)) {
           throw new BusinessRuleException(
               "Le client sélectionné n'appartient pas à la boutique cible de cette vente");
       }
       if (!product.getShop().getId().equals(targetShopId)) {
           throw new BusinessRuleException(
               "Le produit sélectionné n'appartient pas à la boutique cible de cette vente");
       }
       ```
    5. `sale.setShop(shopRepository.getReferenceById(targetShopId));` avant `saveAndFlush`.
- [ ] `backend/src/main/java/com/creditflow/payment/service/PaymentService.java` — injecter `CurrentShopContext` :
  - `search(...)` : ajouter `PaymentSpecifications.inShops(currentShopContext.accessibleShopIds())`.
  - `findById(id)` : après `paymentRepository.findById`, `currentShopContext.assertAccessible(payment.getSale().getShop().getId());`.
  - `receipt(id)` : même garde (charge le `Payment` avant de générer le PDF).
  - `findBySale(saleId)` : ajouter en tête `CreditSale sale = saleRepository.findDetailById(saleId).orElseThrow(() -> ResourceNotFoundException.of("Contrat", saleId)); currentShopContext.assertAccessible(sale.getShop().getId());` avant `paymentRepository.findBySale(saleId)`.
  - `register(request)` : après le chargement de `sale` via `saleRepository.findDetailById`, ajouter `currentShopContext.assertAccessible(sale.getShop().getId());`.
  - `delete(id)` : après le chargement de `payment` et de `sale`, ajouter `currentShopContext.assertAccessible(sale.getShop().getId());`.
  - `findByCustomer(customerId)` : inchangé (accès client vérifié en amont).
- [ ] `backend/src/main/java/com/creditflow/sale/service/InstallmentService.java` — injecter `CurrentShopContext` :
  - `search(...)` : ajouter `InstallmentSpecifications.inShops(currentShopContext.accessibleShopIds())`.
  - `upcoming(days)` : `installmentRepository.findUpcomingForShops(today, today.plusDays(days), currentShopContext.accessibleShopIds())`.
  - `late()` : `installmentRepository.findLateForShops(today, currentShopContext.accessibleShopIds())`.
  - `bySale(saleId)` : ajouter en tête la même garde explicite que `PaymentService.findBySale` (charger le `CreditSale`, `assertAccessible`) avant `installmentRepository.findBySaleIdOrderByNumberAsc(saleId)`.
- [ ] `backend/src/main/java/com/creditflow/notification/service/LateCustomerService.java` — changer la signature `lateCustomers()` → `lateCustomers(List<Long> shopIds)`, propager `shopIds` à `installmentRepository.findLateForShops(today, shopIds)`. **Chaque appelant choisit désormais explicitement sa résolution** (voir tâches suivantes).
- [ ] `backend/src/main/java/com/creditflow/notification/web/ReminderController.java` — injecter `CurrentShopContext`, `lateCustomers()` → `lateCustomerService.lateCustomers(currentShopContext.accessibleShopIds())`.
- [ ] `backend/src/main/java/com/creditflow/notification/service/ReminderService.java` — injecter `CurrentShopContext` :
  - `sendAll(template)` → `lateCustomerService.lateCustomers(currentShopContext.accessibleShopIds())`.
  - `prepareForSale(saleId, template)` : après `saleRepository.findDetailById`, ajouter `currentShopContext.assertAccessible(sale.getShop().getId());`.
  - `prepareForCustomer(customerId, template)` : inchangé (`customerService.getEntity` déjà gardé).
- [ ] `backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java` — voir Phase 6 (dépend de `shopIdForCreation()` mais regroupé avec la reprise de données pour cohérence de revue).
- [ ] Vérifier `backend/src/main/java/com/creditflow/search/service/GlobalSearchService.java` — **aucune modification de code requise** : il délègue à `customerService.quickSearch`, `productService.quickSearch`, `creditSaleService.search`, tous déjà filtrés ci-dessus. Ajouter uniquement un test de non-régression (3.4).

### 3.4 — Tests unitaires (service) à adapter/ajouter

- [ ] `backend/src/test/java/com/creditflow/customer/service/CustomerServiceTest.java` — ajouter un mock `CurrentShopContext` (`@Mock`), stubber `accessibleShopIds()` retournant `List.of(1L)` et `shopIdForCreation()` retournant `1L` sur les tests existants (`@MockitoSettings(strictness = Strictness.LENIENT)` déjà en place, donc les stubs non utilisés par un test donné ne cassent rien). Ajouter :
  - `getEntity` lève `ResourceNotFoundException` si `currentShopContext.assertAccessible` lève (boutique différente).
  - `create` assigne `shop` reçu de `shopIdForCreation()`.
- [ ] `backend/src/test/java/com/creditflow/product/service/ProductServiceTest.java` — même adaptation.
- [ ] `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` — même adaptation + nouveau test **explicite pour le point 4 de la mission** :
  - `createRejectsWhenCustomerBelongsToAnotherShop()` : `customer.getShop().getId() = 2L`, `shopIdForCreation() = 1L` → `BusinessRuleException` contenant `"boutique cible"`.
  - `createRejectsWhenProductBelongsToAnotherShop()` : symétrique.
  - `createSucceedsWhenCustomerAndProductMatchTargetShop()`.
- [ ] `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java` — même adaptation + test `findBySaleRejectsWhenSaleNotAccessible()`.
- [ ] Nouveau `backend/src/test/java/com/creditflow/notification/service/LateCustomerServiceTest.java` (créer si absent) — `lateCustomers(shopIds)` ne délègue qu'à `findLateForShops` avec les `shopIds` fournis (vérifier via `verify(installmentRepository).findLateForShops(any(), eq(shopIds))`).
- [ ] `backend/src/test/java/com/creditflow/search/service/GlobalSearchServiceTest.java` (créer si absent) — vérifie que `search()` délègue bien à `customerService.quickSearch`/`productService.quickSearch`/`creditSaleService.search` (test de non-régression, pas de logique boutique propre à ce service).

---

## Phase 4 — Dashboard consolidé

### Tâches

- [ ] `backend/src/main/java/com/creditflow/dashboard/dto/DashboardResponse.java` — ajouter deux champs au record principal (pas dans `Metrics`) :
  ```java
  public record DashboardResponse(
          LocalDate referenceDate,
          boolean consolidated,
          List<ShopSummary> accessibleShops,
          Metrics metrics,
          List<PaymentResponse> todayPayments,
          List<InstallmentResponse> upcomingInstallments,
          List<LateCustomerResponse> lateCustomers
  ) { ... }
  ```
  `consolidated = shopIds.size() > 1` où `shopIds` est le résultat de `currentShopContext.resolveReadFilter()`.
- [ ] `backend/src/main/java/com/creditflow/dashboard/service/DashboardService.java` — injecter `CurrentShopContext currentShopContext`, `CustomerRepository`, calculer `List<Long> shopIds = currentShopContext.resolveReadFilter();` en tête de `overview()`, puis :
  - `customerRepository.countByShop_IdIn(shopIds)` au lieu de `customerRepository.count()`.
  - `saleRepository.countByShop_IdIn(shopIds)` au lieu de `saleRepository.count()`.
  - `saleRepository.countByStatusAndShop_IdIn(SaleStatus.ACTIVE, shopIds)` / `.countByStatusAndShop_IdIn(SaleStatus.COMPLETED, shopIds)`.
  - `saleRepository.sumRemainingByStatusForShops(SaleStatus.ACTIVE, shopIds)`.
  - `paymentRepository.findBetweenForShops(today, today, shopIds)`, `sumBetweenForShops(monthStart, monthEnd, shopIds)`, `sumBetweenForShops(today, today, shopIds)`, `countBetweenForShops(today, today, shopIds)`.
  - `installmentRepository.countLateForShops(today, shopIds)`, `sumLateAmountForShops(today, shopIds)`.
  - `lateCustomerService.lateCustomers(shopIds)`.
  - `upcoming` : **ne pas** réutiliser `installmentService.upcoming(days)` tel quel (il utilise `accessibleShopIds()`, pas `resolveReadFilter()` — ce sont deux résolutions potentiellement différentes si un en-tête `X-Shop-Id` est fourni). Ajouter une méthode dédiée `InstallmentService.upcomingForShops(int days, List<Long> shopIds)` réutilisant `installmentRepository.findUpcomingForShops(...)`, appelée par `DashboardService` avec `shopIds = resolveReadFilter()`.
  - Construire `DashboardResponse` avec `consolidated = shopIds.size() > 1` et `accessibleShops = currentShopContext.accessibleShops()` (toujours la liste complète, indépendamment du filtre actif, pour peupler le sélecteur frontend).
- [ ] `backend/src/main/java/com/creditflow/sale/service/InstallmentService.java` — ajouter `upcomingForShops(int days, List<Long> shopIds)` (voir ci-dessus), à côté de `upcoming(int days)` existant (qui reste utilisé par `InstallmentController`/`ReminderService` avec `accessibleShopIds()`).
- [ ] `backend/src/main/java/com/creditflow/dashboard/web/DashboardController.java` — **inchangé** (aucun paramètre de requête ajouté ; le filtre transite uniquement par l'en-tête `X-Shop-Id` lu à l'intérieur de `DashboardService` via `CurrentShopContext`).
- [ ] `backend/src/test/java/com/creditflow/dashboard/service/DashboardServiceTest.java` — **nouveau fichier** (aucun test dashboard n'existe actuellement malgré ce que suggérait la note d'architecture — à créer intégralement). Pattern Mockito identique à `CustomerServiceTest`/`CreditSaleServiceTest` :
  - mono-boutique : `accessibleShopIds()`/`resolveReadFilter()` retournent `List.of(1L)`, `consolidated = false`, tous les repository sont appelés avec `List.of(1L)`.
  - multi-boutique sans en-tête : `resolveReadFilter()` retourne `List.of(1L, 2L)`, `consolidated = true`.
  - multi-boutique avec en-tête `X-Shop-Id` valide : `resolveReadFilter()` retourne `List.of(2L)`, `consolidated = false`.

---

## Phase 5 — Rapports

### Tâches

> **Attention conflit de merge** : `backend/src/main/java/com/creditflow/report/service/ReportService.java` et `frontend/src/pages/ReportsPage.tsx` sont potentiellement modifiés en parallèle par la branche `bolt/issue-9-statistiques-defaut-performance-vendeur` (PR #21, non mergée à ce jour). Le codeur travaille depuis `master` à jour (sans #9) : rien à faire de particulier ici, mais ne pas être surpris si un conflit apparaît plus tard à la fusion — c'est un problème d'orchestration, pas de code à anticiper dans cette spec.

- [ ] `backend/src/main/java/com/creditflow/report/service/ReportService.java` — injecter `CurrentShopContext currentShopContext` :
  - `build(type, from, to)` : calculer `List<Long> shopIds = currentShopContext.resolveReadFilter();` en tête, le propager aux trois méthodes privées (`payments`, `lateCustomers`, `outstanding`) qui devront chacune l'accepter en paramètre.
  - `payments(type, title, from, to, shopIds)` : `paymentRepository.findBetweenForShops(from, to, shopIds)` au lieu de `findBetween(from, to)`.
  - `lateCustomers(shopIds)` : `lateCustomerService.lateCustomers(shopIds)` au lieu de `lateCustomerService.lateCustomers()`.
  - `outstanding(shopIds)` : `saleRepository.findAllDetailedForShops(shopIds)` au lieu de `findAllDetailed()`.
- [ ] `backend/src/main/java/com/creditflow/report/web/ReportController.java` — **inchangé** (même principe que `DashboardController` : le filtre transite par l'en-tête `X-Shop-Id`, pas par un nouveau `@RequestParam`), y compris pour `export(...)`.
- [ ] `backend/src/test/java/com/creditflow/report/service/ReportServiceTest.java` — **nouveau fichier** (aucun test n'existe actuellement) :
  - `DAILY_PAYMENTS`/`MONTHLY_PAYMENTS`/`LATE_CUSTOMERS`/`OUTSTANDING` appellent bien les repositories avec le résultat de `resolveReadFilter()`.
  - non-régression mono-boutique : avec une seule boutique accessible, le contenu du rapport est strictement identique à l'appel équivalent sans filtre (mêmes lignes, mêmes totaux) — comparer avec un jeu de données de fixture partagé entre boutique 1 et boutique 2, vérifier qu'aucune ligne de la boutique 2 n'apparaît.

---

## Phase 6 — Reprise de données (dataimport) + Frontend

### 6.1 — Backend : reprise de données

- [ ] `backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java` — injecter `CurrentShopContext currentShopContext` et `ShopRepository shopRepository` :
  - `resolveCustomer(row)` : avant de construire un nouveau `Customer`, si `customerRepository.findByPhone(row.phone())` retourne un client **existant dont la boutique diffère** de `currentShopContext.shopIdForCreation()`, lever `BusinessRuleException("Le téléphone %s appartient déjà à un client d'une autre boutique".formatted(row.phone()))` — évite une erreur opaque plus tard dans `CreditSaleService.create` (contrainte d'unicité globale de `customers.phone`, décision d'architecture confirmée). Sinon, assigner `.shop(shopRepository.getReferenceById(currentShopContext.shopIdForCreation()))` au nouveau client.
  - `resolveProduct(row)` : assigner `.shop(shopRepository.getReferenceById(currentShopContext.shopIdForCreation()))` au nouveau produit créé (aucune contrainte d'unicité de nom, donc pas de garde symétrique nécessaire).
  - `importLegacySales(...)` : résoudre `currentShopContext.shopIdForCreation()` **une seule fois en tête de méthode** (avant la boucle) et le réutiliser — lève l'erreur multi-boutique 422 immédiatement plutôt qu'après avoir déjà traité une partie du fichier (cohérent avec le principe « tout ou rien » déjà documenté dans cette classe).
- [ ] `backend/src/main/java/com/creditflow/dataimport/web/ImportController.java` — **inchangé** (l'en-tête `X-Shop-Id` est lu par `CurrentShopContext`, pas par le contrôleur).
- [ ] `backend/src/test/java/com/creditflow/dataimport/service/LegacyImportServiceTest.java` (créer si absent — vérifier d'abord s'il existe déjà, seul `LegacyImportParserTest` a été repéré) :
  - import réussi assigne la boutique résolue aux nouveaux clients/produits/ventes créés.
  - rejet clair si un téléphone déjà connu appartient à une autre boutique.
  - rejet 422 si l'utilisateur est multi-boutiques sans en-tête `X-Shop-Id`.

### 6.2 — Frontend

- [ ] `frontend/src/types.ts` :
  - `export interface Shop { id: number; name: string; address?: string; phone?: string; active: boolean; createdAt: string; createdBy?: string; updatedBy?: string; }`
  - `export interface ShopPayload { name: string; address?: string; phone?: string; active?: boolean; }`
  - `export interface ShopSummary { id: number; name: string; }`
  - `User` : ajouter `shops: ShopSummary[]` (assignation brute, pour l'écran `UsersPage`).
  - `AuthResponse` : ajouter `accessibleShops: ShopSummary[]`.
  - `UserAccount`/`CreateUserPayload` : ajouter `shopIds?: number[]`.
  - `Dashboard` : ajouter `consolidated: boolean` et `accessibleShops: ShopSummary[]`.
- [ ] `frontend/src/api/endpoints.ts` (ou fichier équivalent listant les appels API — vérifier le nom exact avant modification) — ajouter `shopApi` (`list`, `create`, `update`, `remove`) sur `/api/shops`, ajouter `updateUserShops(id, shopIds)` sur `/api/users/{id}/shops`.
- [ ] `frontend/src/context/ShopContext.tsx` — nouveau, calqué sur le pattern `TOKEN_KEY`/`USER_KEY` de `api/client.ts` :
  ```ts
  export const ACTIVE_SHOP_KEY = 'creditflow.activeShop';
  ```
  Expose `activeShopId: number | null` (`null` = vue consolidée), `setActiveShopId`, `accessibleShops: ShopSummary[]` (initialisé depuis `AuthResponse.accessibleShops` à la connexion, mis à jour via `refreshUser`/un nouvel appel dédié). Persisté en `localStorage` sous `ACTIVE_SHOP_KEY`. Si `accessibleShops.length <= 1`, le contexte n'expose pas de sélection possible (comportement mono-boutique inchangé, aucun en-tête envoyé).
- [ ] `frontend/src/api/client.ts` — intercepteur : si un `activeShopId` est présent en `localStorage` (`ACTIVE_SHOP_KEY`) **et** que l'utilisateur a plus d'une boutique accessible, ajouter l'en-tête `config.headers['X-Shop-Id'] = activeShopId` à chaque requête (pattern identique à l'ajout actuel de `Authorization`). Ne jamais envoyer l'en-tête pour un utilisateur mono-boutique (évite tout risque de régression sur AC3).
- [ ] `frontend/src/auth/AuthContext.tsx` — stocker `accessibleShops` (issu de `AuthResponse`) au login, exposer via le contexte ou via `ShopContext` consommé séparément (choix laissé au codeur selon la structure existante, mais la donnée doit être disponible dès la connexion sans appel réseau supplémentaire).
- [ ] `frontend/src/components/AppLayout.tsx` :
  - ajouter un sélecteur de boutique dans la `Toolbar` (entre `GlobalSearchBar` et le menu compte), **rendu uniquement si `accessibleShops.length > 1`** (comportement mono-boutique strictement inchangé sinon).
  - ajouter une entrée `{ to: '/boutiques', label: 'Boutiques', icon: <StoreIcon />, adminOnly: true }` à `NAV_ITEMS`.
- [ ] `frontend/src/pages/ShopsPage.tsx` — nouveau, CRUD minimal (liste, création, modification, suppression) sur le modèle de `SuppliersPage.tsx` (à consulter pour le pattern exact de tableau + dialog).
- [ ] `frontend/src/App.tsx` — ajouter `<Route path="boutiques" element={<ShopsPage />} />` **à l'intérieur** du bloc `<Route element={<RequireRole role="ADMIN" />}>`.
- [ ] `frontend/src/pages/UsersPage.tsx` — ajouter la sélection multiple des boutiques à la création d'un compte (`shopIds`, obligatoire si rôle `SELLER`) et un moyen de modifier l'assignation d'un compte existant (nouvel appel `PATCH /api/users/{id}/shops`).
- [ ] `frontend/src/pages/DashboardPage.tsx` — afficher un bandeau/indicateur « Vue consolidée (N boutiques) » ou « Boutique : {nom} » selon `dashboard.consolidated` et `activeShopId`.
- [ ] `frontend/src/pages/ReportsPage.tsx` — même indicateur (pas de changement du contenu du rapport lui-même, cf. Phase 5).

---

## Contrat technique (synthèse)

| Élément | Détail |
|---|---|
| `shops.id` | `BIGSERIAL PK` |
| `shops.name` | `VARCHAR(120) NOT NULL UNIQUE` |
| `customers.shop_id`, `products.shop_id`, `credit_sales.shop_id` | `BIGINT NOT NULL`, FK vers `shops.id`, ajoutés nullable puis passés `NOT NULL` après rétro-remplissage vers la boutique par défaut |
| `user_shops` | table de jointure `(user_id, shop_id)` PK composite, `ON DELETE CASCADE` des deux côtés |
| En-tête HTTP | `X-Shop-Id` (entier), optionnel, interprété uniquement par `CurrentShopContext`, consommé par Dashboard/Rapports (`resolveReadFilter`) et par les créations (`shopIdForCreation`) |
| Code HTTP rejet ambiguïté création | `422 UNPROCESSABLE_ENTITY` via `BusinessRuleException`, message : *"Vous êtes rattaché à plusieurs boutiques : précisez la boutique cible via l'en-tête X-Shop-Id avant de créer cette ressource."* |
| Code HTTP boutique non accessible (en-tête invalide) | `422 UNPROCESSABLE_ENTITY`, message : *"La boutique demandée (X-Shop-Id=&lt;id&gt;) n'est pas accessible pour votre compte."* |
| Code HTTP accès direct par id hors périmètre | `404 NOT_FOUND` (via `ResourceNotFoundException("Ressource introuvable")`, ne révèle pas l'existence dans une autre boutique) |
| `PATCH /api/users/{id}/shops` | body `{ "shopIds": [1,2] }` → `UserResponse` |
| `GET/POST/PUT/DELETE /api/shops[/{id}]` | `ADMIN` uniquement |

---

## Plan de tests

| Critère d'acceptation | Couverture |
|---|---|
| **AC1** — Un utilisateur mono-boutique ne voit que les données de sa boutique | `CurrentShopContextTest` (résolution mono-boutique) ; `CustomerServiceTest`/`ProductServiceTest`/`CreditSaleServiceTest`/`PaymentServiceTest` (garde `getEntity`/`assertAccessible`, 404 sur id hors périmètre) ; `CustomerSpecificationsTest`/`ProductSpecificationsTest`/`SaleSpecificationsTest`/`PaymentSpecificationsTest`/`InstallmentSpecificationsTest` (prédicat `inShops` correct) ; `DashboardServiceTest` et `ReportServiceTest` (cas mono-boutique = comportement non filtré identique) ; test manuel : se connecter avec un compte `SELLER` mono-boutique de démo, vérifier qu'aucune donnée de l'autre boutique n'apparaît dans clients/produits/ventes/paiements/échéances/relances/recherche globale/dashboard/rapports. |
| **AC2** — Un gérant multi-boutiques a un dashboard consolidé et peut filtrer par boutique | `DashboardServiceTest` (cas multi-boutiques sans en-tête → `consolidated=true`, agrégats sommés sur toutes les boutiques accessibles ; cas avec en-tête `X-Shop-Id` → `consolidated=false`, agrégats limités à la boutique demandée) ; `CurrentShopContextTest` (`resolveReadFilter`) ; test manuel : connecter un `ADMIN` super-admin (ou un compte assigné à 2 boutiques de démo), vérifier le sélecteur de boutique dans `AppLayout`, basculer entre vue consolidée et boutique unique, vérifier la cohérence des totaux affichés. |
| **AC3** — Rapports/exports existants acceptent un filtre boutique sans régression mono-boutique | `ReportServiceTest` (non-régression : sortie strictement identique pour un utilisateur mono-boutique avec/sans passage explicite du filtre) ; test manuel : générer chaque type de rapport (`DAILY_PAYMENTS`, `MONTHLY_PAYMENTS`, `LATE_CUSTOMERS`, `OUTSTANDING`) et chaque export (`pdf`, `excel`) avant et après la migration avec un compte mono-boutique, comparer le contenu (aucune différence attendue) ; avec un compte multi-boutiques, vérifier qu'un rapport filtré via `X-Shop-Id` n'affiche que les lignes de la boutique choisie. |
| Non-régression transverse | `ShopControllerSecurityTest`, `UserControllerTest` (mis à jour pour `shopIds`), tests existants de `CustomerControllerSecurityTest`/`ProductControllerSecurityTest`/`SaleControllerSecurityTest`/`PaymentControllerSecurityTest` **inchangés dans leurs assertions** (aucun nouveau paramètre de requête n'apparaît sur ces contrôleurs — seule l'implémentation interne du service change) ; suite complète `mvn test` au vert avant fusion. |

---

## Écarts identifiés

- **Le design ne couvre explicitement que les méthodes de recherche paginée (`search`/`quickSearch`) via les `Specifications`, pas l'accès direct par identifiant (`getEntity`/`findById`)**. Or `CustomerService.getEntity`, `ProductService.getEntity`, `CreditSaleService.getEntity`, `PaymentService.findById`/`receipt`/`findBySale`, `InstallmentService.bySale`, `ReminderService.prepareForSale` chargent des entités uniquement par id, sans filtre boutique dans la requête. Sans garde supplémentaire, un utilisateur mono-boutique connaissant (ou devinant) l'id d'une ressource d'une autre boutique pourrait y accéder directement — violation frontale de l'AC1. Cette spec étend donc le filtrage à ces méthodes via `CurrentShopContext.assertAccessible(...)` (Phase 3.3). **À confirmer avant codage** : ce périmètre est plus large que celui listé dans `design.md`, mais indispensable pour respecter le critère d'acceptation n°1.
- **`ReminderController` (`/api/reminders/late-customers`) et `ReminderService.sendAll` ne figurent pas dans la liste des fichiers impactés de `design.md`**, alors qu'ils appellent directement `LateCustomerService.lateCustomers()`. Sans modification, l'écran « Relances » et l'envoi groupé de relances resteraient non filtrés par boutique pour un utilisateur mono-boutique. Ajoutés en Phase 3.3.
- **Cas non traité par le design : un client déjà existant (téléphone unique globalement) dans une boutique B, réimporté via la reprise de données par un utilisateur de la boutique A.** La contrainte d'unicité globale de `customers.phone` (décision confirmée : reste inchangée) rend cet import structurellement impossible pour cette ligne. Le design ne précise pas le comportement attendu ; cette spec choisit de le détecter tôt avec un message explicite plutôt que de laisser échouer `CreditSaleService.create` avec une erreur de cohérence boutique moins lisible (Phase 6.1). À valider par le produit : faut-il au contraire permettre le rattachement transparent (le client existant est utilisé tel quel, dans sa boutique d'origine, sans bloquer l'import) ? Cette spec retient le rejet explicite par défaut, cohérent avec le principe « tout ou rien » déjà en vigueur dans `LegacyImportService`.
- **`design.md` indique que `DashboardServiceTest` et `ReportServiceTest` « devront tous être adaptés »** : en réalité, **aucun des deux fichiers n'existe actuellement** dans `backend/src/test/java` — ils sont à créer intégralement, pas à adapter (Phases 4 et 5).
- **Ajout de `shopId`/visibilité de la boutique sur les réponses `Customer`/`Product`/`Sale` non traité explicitement par le design.** Pour qu'un gérant multi-boutiques puisse distinguer, dans une vue consolidée, à quelle boutique appartient une ligne (client, produit, vente), il est recommandé d'ajouter `shopId`/`shopName` optionnels aux réponses concernées (`CustomerResponse`, `ProductResponse`, `SaleResponse`) et de les afficher côté frontend en mode consolidé uniquement. Cette extension n'est pas indispensable pour satisfaire strictement les 3 critères d'acceptation (le dashboard reste agrégé, sans ventilation par boutique — explicitement hors périmètre) : **à trancher par le codeur/orchestrateur selon le temps disponible**, elle n'est pas incluse dans la checklist de tâches ci-dessus pour ne pas alourdir davantage un périmètre déjà large.
