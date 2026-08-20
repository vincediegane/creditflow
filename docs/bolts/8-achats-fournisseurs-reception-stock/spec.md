# Spec — Achats fournisseurs et réception de stock (#8)

## Résumé

Livrer un module `supplier` (fournisseurs + réceptions de stock) qui incrémente immédiatement `Product.stock` à la validation d'une réception, et un historique `StockMovement` (module `product`) qui trace chaque entrée (achat) et sortie (vente) de stock.

## Tâches

### Migration

- [ ] `backend/src/main/resources/db/migration/V8__suppliers_stock_receptions.sql` — créer les tables `suppliers`, `stock_receptions`, `stock_reception_lines`, `stock_movements` (voir Contrat technique). **Avant de committer**, vérifier dans `backend/src/main/resources/db/migration/` le dernier numéro `V*` réellement présent sur la branche cible du merge et renommer le fichier en conséquence si `V6`/`V7` sont libres (voir Écarts identifiés #1).

### Backend — module `product` (infrastructure des mouvements de stock)

- [ ] `backend/src/main/java/com/creditflow/product/domain/StockMovementType.java` — enum `IN`, `OUT`.
- [ ] `backend/src/main/java/com/creditflow/product/domain/StockSourceType.java` — enum `PURCHASE_RECEPTION`, `SALE`.
- [ ] `backend/src/main/java/com/creditflow/product/domain/StockMovement.java` — entité append-only (calquée sur `AuditLog.java` : pas de `extends Auditable`, `@PrePersist` manuel pour `occurredAt`/`createdBy`).
- [ ] `backend/src/main/java/com/creditflow/product/repository/StockMovementRepository.java` — `findByProductIdOrderByOccurredAtDescIdDesc(Long productId)`.
- [ ] `backend/src/main/java/com/creditflow/product/dto/StockMovementResponse.java` — record de réponse.
- [ ] `backend/src/main/java/com/creditflow/product/service/ProductService.java` — modifier : injecter `StockMovementRepository`, ajouter `increaseStock(Product, int, StockSourceType, Long)`, étendre `decreaseStock(Product, int)` pour tracer un mouvement `OUT`, ajouter `stockMovements(Long productId)`.
- [ ] `backend/src/main/java/com/creditflow/product/web/ProductController.java` — modifier : ajouter `GET /{id}/stock-movements`.

### Backend — module `supplier` (nouveau)

- [ ] `backend/src/main/java/com/creditflow/supplier/domain/Supplier.java` — entité `extends Auditable`, calquée sur `Customer.java`.
- [ ] `backend/src/main/java/com/creditflow/supplier/dto/SupplierRequest.java`
- [ ] `backend/src/main/java/com/creditflow/supplier/dto/SupplierResponse.java`
- [ ] `backend/src/main/java/com/creditflow/supplier/mapper/SupplierMapper.java` — MapStruct, calqué sur `CustomerMapper.java`.
- [ ] `backend/src/main/java/com/creditflow/supplier/repository/SupplierRepository.java`
- [ ] `backend/src/main/java/com/creditflow/supplier/repository/SupplierSpecifications.java` — recherche sur `name`, `contactName`, `phone`, `email`.
- [ ] `backend/src/main/java/com/creditflow/supplier/service/SupplierService.java` — CRUD calqué sur `CustomerService.java` (pas de contrainte d'unicité téléphone/email, contrairement à `Customer`).
- [ ] `backend/src/main/java/com/creditflow/supplier/web/SupplierController.java` — `/api/suppliers`.
- [ ] `backend/src/main/java/com/creditflow/supplier/domain/StockReception.java` — entité `extends Auditable`, relation `OneToMany` vers `StockReceptionLine`.
- [ ] `backend/src/main/java/com/creditflow/supplier/domain/StockReceptionLine.java` — entité, `ManyToOne` vers `StockReception` et `Product`.
- [ ] `backend/src/main/java/com/creditflow/supplier/dto/StockReceptionRequest.java` (+ record imbriqué `StockReceptionLineRequest`).
- [ ] `backend/src/main/java/com/creditflow/supplier/dto/StockReceptionResponse.java` (+ record imbriqué `StockReceptionLineResponse`).
- [ ] `backend/src/main/java/com/creditflow/supplier/mapper/StockReceptionMapper.java`
- [ ] `backend/src/main/java/com/creditflow/supplier/repository/StockReceptionRepository.java` — `findAll(Specification, Pageable)` (via `JpaSpecificationExecutor`), filtre optionnel `supplierId`.
- [ ] `backend/src/main/java/com/creditflow/supplier/service/StockReceptionService.java` — `receive(StockReceptionRequest)` : résout tous les produits avant toute écriture (voir Contrat technique #1), délègue l'incrément de stock à `ProductService.increaseStock`.
- [ ] `backend/src/main/java/com/creditflow/supplier/web/StockReceptionController.java` — `/api/stock-receptions` (`GET` liste paginée, `GET /{id}`, `POST`, pas de `PUT`/`DELETE` — réception immuable une fois créée).

### Backend — tests

- [ ] `backend/src/test/java/com/creditflow/supplier/service/SupplierServiceTest.java`
- [ ] `backend/src/test/java/com/creditflow/supplier/web/SupplierControllerSecurityTest.java` (calqué sur `ProductControllerSecurityTest.java`)
- [ ] `backend/src/test/java/com/creditflow/supplier/service/StockReceptionServiceTest.java`
- [ ] `backend/src/test/java/com/creditflow/supplier/web/StockReceptionControllerSecurityTest.java`
- [ ] `backend/src/test/java/com/creditflow/product/service/ProductServiceTest.java` — modifier : ajouter les cas `increaseStock`/`decreaseStock`/`stockMovements`.
- [ ] `backend/src/test/java/com/creditflow/product/web/ProductControllerSecurityTest.java` — modifier : ajouter le cas `GET /{id}/stock-movements`.
- [ ] `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` — modifier : ajouter une assertion vérifiant qu'un `StockMovement` de type `OUT` est bien créé lors d'une vente (câblage bout en bout, sans modifier `CreditSaleService.java`).

### Frontend

- [ ] `frontend/src/types.ts` — ajouter `Supplier`, `SupplierPayload`, `StockReception`, `StockReceptionPayload`, `StockReceptionLine`, `StockMovement`, `StockMovementType`, `StockSourceType`.
- [ ] `frontend/src/api/endpoints.ts` — ajouter `suppliersApi` (`list`, `select`, `get`, `create`, `update`, `remove`) et `stockReceptionsApi` (`list`, `get`, `create`), ajouter `productsApi.stockMovements(id)`.
- [ ] `frontend/src/pages/SuppliersPage.tsx` — CRUD fournisseurs, calqué sur `CustomersPage.tsx`.
- [ ] `frontend/src/pages/StockReceptionsPage.tsx` — formulaire de réception (sélection fournisseur + lignes produit/quantité dynamiques) et tableau d'historique des réceptions.
- [ ] `frontend/src/components/StockMovementsDialog.tsx` — dialog listant les mouvements d'un produit, entrées et sorties visuellement distinguées (icône/couleur + libellé).
- [ ] `frontend/src/pages/ProductsPage.tsx` — modifier : ajouter une action par ligne (`IconButton` + `Tooltip`, pattern identique à `CustomersPage.tsx`) ouvrant `StockMovementsDialog`.
- [ ] `frontend/src/App.tsx` — ajouter les routes `fournisseurs` (`SuppliersPage`) et `achats` (`StockReceptionsPage`) sous `AppLayout`.
- [ ] `frontend/src/components/AppLayout.tsx` — ajouter deux entrées à `NAV_ITEMS` (`/fournisseurs`, `/achats`).

## Contrat technique

### Schéma SQL (`V8__suppliers_stock_receptions.sql`)

```sql
CREATE TABLE suppliers (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    contact_name VARCHAR(120),
    phone        VARCHAR(30),
    email        VARCHAR(120),
    address      VARCHAR(255),
    notes        TEXT,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP,
    created_by   VARCHAR(80),
    updated_by   VARCHAR(80)
);

CREATE INDEX idx_suppliers_name ON suppliers (LOWER(name));

CREATE TABLE stock_receptions (
    id          BIGSERIAL PRIMARY KEY,
    supplier_id BIGINT    NOT NULL,
    received_at DATE      NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80),
    CONSTRAINT fk_stock_receptions_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

CREATE INDEX idx_stock_receptions_supplier ON stock_receptions (supplier_id);
CREATE INDEX idx_stock_receptions_received_at ON stock_receptions (received_at);

CREATE TABLE stock_reception_lines (
    id           BIGSERIAL PRIMARY KEY,
    reception_id BIGINT  NOT NULL,
    product_id   BIGINT  NOT NULL,
    quantity     INTEGER NOT NULL,
    CONSTRAINT fk_stock_reception_lines_reception FOREIGN KEY (reception_id) REFERENCES stock_receptions (id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_reception_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_stock_reception_lines_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_stock_reception_lines_reception ON stock_reception_lines (reception_id);
CREATE INDEX idx_stock_reception_lines_product ON stock_reception_lines (product_id);

CREATE TABLE stock_movements (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT      NOT NULL,
    type        VARCHAR(10) NOT NULL,
    quantity    INTEGER     NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id   BIGINT,
    occurred_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(80),
    CONSTRAINT fk_stock_movements_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_stock_movements_type CHECK (type IN ('IN', 'OUT')),
    CONSTRAINT chk_stock_movements_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_stock_movements_product ON stock_movements (product_id, occurred_at DESC);
```

### 1. Comportement transactionnel — ligne invalide

- **Quantité invalide (`<= 0` ou absente)** : rejetée par Bean Validation (`@NotNull @Positive` sur `StockReceptionLineRequest.quantity`) → `400 Bad Request` renvoyé par le `@Valid` du controller, **aucune transaction n'est ouverte**.
- **Produit inexistant** : `StockReceptionService.receive(...)` doit **résoudre et charger tous les produits de toutes les lignes AVANT toute écriture** (avant tout `save()`). Si `productService.getEntity(productId)` lève `ResourceNotFoundException` sur une ligne quelconque, l'exception se propage hors de la méthode `@Transactional` → rollback complet automatique (comportement Spring par défaut sur `RuntimeException`) → **aucune** `StockReception`, aucune `StockReceptionLine`, aucun `StockMovement` n'est persisté, **aucun stock n'est modifié**, même si d'autres lignes de la même requête référencent des produits valides. Réponse `404 Not Found`.
- Décision : **rollback total, jamais de traitement partiel.**

```java
@Transactional
public StockReceptionResponse receive(StockReceptionRequest request) {
    Supplier supplier = supplierService.getEntity(request.supplierId());

    StockReception reception = StockReception.builder()
            .supplier(supplier)
            .receivedAt(request.receivedAt())
            .notes(request.notes())
            .build();

    List<Product> resolvedProducts = new ArrayList<>();
    for (var line : request.lines()) {
        Product product = productService.getEntity(line.productId()); // rollback total si absent
        reception.addLine(StockReceptionLine.builder().product(product).quantity(line.quantity()).build());
        resolvedProducts.add(product);
    }

    StockReception saved = stockReceptionRepository.save(reception);

    for (int i = 0; i < request.lines().size(); i++) {
        productService.increaseStock(resolvedProducts.get(i), request.lines().get(i).quantity(),
                StockSourceType.PURCHASE_RECEPTION, saved.getId());
    }

    return stockReceptionMapper.toResponse(saved);
}
```

### 2. Structure JPA `StockReception` ↔ `StockReceptionLine`

Mirroir exact du couple `CreditSale`/`Installment` (`backend/src/main/java/com/creditflow/sale/domain/CreditSale.java` ligne 99, `Installment.java`) :

```java
// StockReception (côté parent)
@OneToMany(mappedBy = "reception", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
@Builder.Default
private List<StockReceptionLine> lines = new ArrayList<>();

public void addLine(StockReceptionLine line) {
    line.setReception(this);
    lines.add(line);
}
```

```java
// StockReceptionLine (côté enfant)
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "reception_id", nullable = false)
private StockReception reception;

@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "product_id", nullable = false)
private Product product;

@Column(nullable = false)
private Integer quantity;
```

`orphanRemoval = true` et `cascade = ALL` : cohérent même si aucune mutation de ligne n'est exposée en API (pas de `PUT`), pour rester au même niveau de robustesse que `CreditSale`.

### 3. `ProductService` — méthodes de mouvement de stock

```java
@Transactional
public void increaseStock(Product product, int quantity, StockSourceType sourceType, Long sourceId) {
    int newStock = product.getStock() + quantity;
    product.setStock(newStock);
    if (newStock > 0 && product.getStatus() == ProductStatus.OUT_OF_STOCK) {
        product.setStatus(ProductStatus.ACTIVE); // symetrique de decreaseStock, ne touche jamais INACTIVE
    }
    productRepository.save(product);
    recordMovement(product, StockMovementType.IN, quantity, sourceType, sourceId);
}

@Transactional
public void decreaseStock(Product product, int quantity) {
    int previousStock = product.getStock();
    int newStock = Math.max(0, previousStock - quantity); // comportement preexistant, inchange
    int actualDecrease = previousStock - newStock;
    product.setStock(newStock);
    if (newStock == 0 && product.getStatus() == ProductStatus.ACTIVE) {
        product.setStatus(ProductStatus.OUT_OF_STOCK);
    }
    productRepository.save(product);
    if (actualDecrease > 0) { // evite d'ecrire un mouvement quantite=0 (viole chk_stock_movements_quantity)
        recordMovement(product, StockMovementType.OUT, actualDecrease, StockSourceType.SALE, null);
    }
}

private void recordMovement(Product product, StockMovementType type, int quantity,
                             StockSourceType sourceType, Long sourceId) {
    stockMovementRepository.save(StockMovement.builder()
            .product(product).type(type).quantity(quantity)
            .sourceType(sourceType).sourceId(sourceId).build());
}
```

`decreaseStock` garde sa signature actuelle (`Product`, `int`) : l'appel existant `productService.decreaseStock(product, 1)` dans `CreditSaleService.java` (ligne ~179) n'est **pas modifié**. Conséquence : `sourceId` sera toujours `null` pour les mouvements `OUT` (voir Écarts identifiés #2).

### 4. `GET /api/products/{id}/stock-movements`

```java
@Transactional(readOnly = true)
public List<StockMovementResponse> stockMovements(Long productId) {
    getEntity(productId); // 404 si produit inconnu
    return stockMovementRepository.findByProductIdOrderByOccurredAtDescIdDesc(productId)
            .stream()
            .map(m -> new StockMovementResponse(m.getId(), m.getProduct().getId(), m.getType(), m.getQuantity(),
                    m.getSourceType(), m.getSourceId(), m.getOccurredAt(), m.getCreatedBy()))
            .toList();
}
```

- Non paginé, `List<StockMovementResponse>`, à l'image de `AuditLogController.list` (`backend/src/main/java/com/creditflow/audit/web/AuditLogController.java`).
- Tri : `occurred_at DESC, id DESC` (le plus récent en premier, `id` comme tie-breaker stable pour les mouvements créés dans la même transaction/même timestamp).
- Champs exposés : `id`, `productId`, `type` (`IN`/`OUT`), `quantity`, `sourceType` (`PURCHASE_RECEPTION`/`SALE`), `sourceId` (nullable), `occurredAt`, `createdBy`.
- Accès : lecture ouverte à tout utilisateur authentifié (ADMIN et SELLER), pas de `@PreAuthorize` — comme `GET /api/products`.

### DTOs

```java
// SupplierRequest
public record SupplierRequest(
        @NotBlank(message = "Le nom du fournisseur est obligatoire") @Size(max = 150) String name,
        @Size(max = 120) String contactName,
        @Size(max = 30) String phone,
        @Size(max = 120) @Email(message = "Email invalide") String email,
        @Size(max = 255) String address,
        String notes,
        Boolean active
) {}

// SupplierResponse
public record SupplierResponse(
        Long id, String name, String contactName, String phone, String email, String address, String notes,
        boolean active, LocalDateTime createdAt, String createdBy, String updatedBy
) {}

// StockReceptionRequest
public record StockReceptionRequest(
        @NotNull(message = "Le fournisseur est obligatoire") Long supplierId,
        @NotNull(message = "La date de reception est obligatoire") LocalDate receivedAt,
        String notes,
        @NotEmpty(message = "Au moins une ligne est requise") @Valid List<StockReceptionLineRequest> lines
) {
    public record StockReceptionLineRequest(
            @NotNull(message = "Le produit est obligatoire") Long productId,
            @NotNull(message = "La quantite est obligatoire")
            @Positive(message = "La quantite doit etre positive") Integer quantity
    ) {}
}

// StockReceptionResponse
public record StockReceptionResponse(
        Long id, Long supplierId, String supplierName, LocalDate receivedAt, String notes,
        List<StockReceptionLineResponse> lines, LocalDateTime createdAt, String createdBy
) {
    public record StockReceptionLineResponse(Long id, Long productId, String productName, Integer quantity) {}
}
```

### Endpoints

| Méthode | Route | Auth | Réponse |
|---|---|---|---|
| GET | `/api/suppliers?search=&page=&size=&sort=` | authentifié | `PageResponse<SupplierResponse>` |
| GET | `/api/suppliers/select` | authentifié | `List<SupplierResponse>` (actifs, triés par `name`) |
| GET | `/api/suppliers/{id}` | authentifié | `SupplierResponse` |
| POST | `/api/suppliers` | `ADMIN` | `201` + `SupplierResponse` |
| PUT | `/api/suppliers/{id}` | `ADMIN` | `SupplierResponse` |
| DELETE | `/api/suppliers/{id}` | `ADMIN` | `204` |
| GET | `/api/stock-receptions?supplierId=&page=&size=&sort=` | authentifié | `PageResponse<StockReceptionResponse>` (défaut `sort=receivedAt,desc`) |
| GET | `/api/stock-receptions/{id}` | authentifié | `StockReceptionResponse` |
| POST | `/api/stock-receptions` | `ADMIN` | `201` + `StockReceptionResponse` |
| GET | `/api/products/{id}/stock-movements` | authentifié | `List<StockMovementResponse>` |

Pas de `PUT`/`DELETE` sur `/api/stock-receptions` : une réception validée est immuable (cohérent avec le flux à une étape décidé par l'architecture — la modifier nécessiterait de recalculer/annuler des mouvements de stock, hors périmètre).

Restriction `ADMIN` sur la création de fournisseurs/réceptions (au lieu du modèle `CustomerController` où seul `DELETE` est restreint) : décision explicite de l'architecte, cohérente avec le fait que le rôle « gérant » du ticket n'existe pas et correspond à `ADMIN` (voir Hors périmètre du design et Écarts identifiés #3).

## Plan de tests

| Critère d'acceptation | Test | Type |
|---|---|---|
| 1. Un gérant peut créer un fournisseur et enregistrer une réception pour un ou plusieurs produits | `SupplierServiceTest.create_createsSupplier` | Unitaire |
| 1. (suite) | `SupplierControllerSecurityTest` : `ADMIN` → `201` sur `POST /api/suppliers`, `SELLER` → `403` | Intégration (`@WebMvcTest`) |
| 1. (suite) | `StockReceptionServiceTest.receive_withMultipleLines_createsReceptionAndLines` : réception avec 2 lignes (2 produits différents) → `StockReception` + 2 `StockReceptionLine` persistées | Unitaire/Intégration |
| 1. (suite) | `StockReceptionControllerSecurityTest` : `ADMIN` → `201` sur `POST /api/stock-receptions`, `SELLER` → `403` | Intégration |
| 1. (suite) | Manuel : créer un fournisseur via `SuppliersPage.tsx`, puis une réception à 2 lignes via `StockReceptionsPage.tsx`, vérifier son apparition dans l'historique | Manuel |
| 2. Le stock produit est mis à jour immédiatement après validation | `StockReceptionServiceTest.receive_increasesProductStockImmediately` : produit à `stock=5`, ligne `quantity=3` → après `receive()`, `product.getStock() == 8` | Unitaire/Intégration |
| 2. (suite) | `ProductServiceTest.increaseStock_reactivatesOutOfStockProduct` : produit `stock=0`/`OUT_OF_STOCK` → `increaseStock(+5)` → `stock=5`, `status=ACTIVE` | Unitaire |
| 2. (suite) | `StockReceptionServiceTest.receive_withUnknownProduct_rollsBackEntirely` : requête mixant une ligne valide + une ligne avec `productId` inexistant → `ResourceNotFoundException`, puis assertion que **ni** la `StockReception` **ni** le stock du produit valide n'ont été modifiés (aucune ligne persistée) | Intégration |
| 2. (suite) | Manuel : créer une réception, recharger `ProductsPage.tsx`, vérifier que le stock affiché a augmenté sans action manuelle supplémentaire | Manuel |
| 3. L'historique distingue entrées (achat) et sorties (vente) | `ProductServiceTest.decreaseStock_recordsOutMovementWithActualDecrease` : vérifie qu'un `StockMovement` `type=OUT`, `sourceType=SALE`, `sourceId=null` est créé avec la quantité réellement décrémentée | Unitaire |
| 3. (suite) | `ProductServiceTest.increaseStock_recordsInMovement` : vérifie qu'un `StockMovement` `type=IN`, `sourceType=PURCHASE_RECEPTION`, `sourceId=<receptionId>` est créé | Unitaire |
| 3. (suite) | `ProductServiceTest.stockMovements_returnsMovementsOrderedMostRecentFirst` : un `IN` puis un `OUT` insérés → `stockMovements(id)` renvoie les deux, triés `occurredAt DESC` | Unitaire |
| 3. (suite) | `ProductControllerSecurityTest` : `SELLER` et `ADMIN` → `200` sur `GET /api/products/{id}/stock-movements` (lecture ouverte) | Intégration |
| 3. (suite) | `CreditSaleServiceTest` (existant, étendu) : après création d'une vente, un `StockMovement` `OUT` existe pour le produit vendu (câblage bout en bout sans toucher `CreditSaleService.java`) | Intégration |
| 3. (suite) | Manuel : sur `ProductsPage.tsx`, ouvrir `StockMovementsDialog.tsx` pour un produit ayant eu une vente et une réception, vérifier que les deux lignes (entrée/sortie) sont visuellement distinguées et triées du plus récent au plus ancien | Manuel |

## Écarts identifiés

1. **Numéro de migration.** Le design fixe `V8` en supposant `V6`/`V7` réservés par les PR non mergées #6/#7. Sur cette branche (`bolt/issue-8-...`), les migrations présentes s'arrêtent à `V5__credit_sale_interest.sql` : `V6` et `V7` n'existent pas encore localement. Ce dépôt a déjà connu une collision réelle de ce type (commit `755a526 fix: resolve Flyway V3 migration filename collision`). **Le codeur doit, juste avant de committer, relire `backend/src/main/resources/db/migration/` sur la branche cible du merge et nommer le fichier avec le premier numéro `V*` libre** (ce sera `V6`, `V7` ou `V8` selon l'état réel à ce moment-là), pas mécaniquement `V8`.

2. **Traçabilité partielle des sorties.** `decreaseStock(Product, int)` garde sa signature actuelle et le call site inchangé dans `CreditSaleService.java` (conforme au design). Résultat : les `StockMovement` de type `OUT` auront systématiquement `sourceId = null` — impossible de relier un mouvement de sortie au contrat de vente précis. Le critère d'acceptation 3 n'exige que la distinction IN/OUT (satisfaite), pas la traçabilité de la vente d'origine. Signalé pour validation explicite avant codage, ce n'est pas un blocage.

3. **Rôle « gérant » = ADMIN.** Le critère d'acceptation 1 dit « un gérant peut créer un fournisseur et enregistrer une réception », mais aucun rôle « gérant » n'existe dans le modèle (`ADMIN`/`SELLER`). Le design (section Hors périmètre) tranche explicitement `gérant = ADMIN` et restreint création/modification/suppression de fournisseurs et réceptions à `ADMIN` via `@PreAuthorize`. Conséquence : un `SELLER` ne pourra pas enregistrer de réception au quotidien. Ce n'est pas un vrai écart avec le ticket (qui ne mentionne pas `SELLER`), mais à confirmer explicitement avant codage si ce n'est pas le comportement métier voulu.
