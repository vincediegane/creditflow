# Spec — #66 Empecher la survente : bloquer la vente a credit quand le stock produit est a 0

## Résumé

Ajout d'une garde de stock bloquante et d'un verrou pessimiste dans `CreditSaleService.create()` pour empêcher la création d'une vente à crédit sur un produit à stock ≤ 0, avec message d'erreur explicite et indicateur visuel côté formulaire de vente.

## Tâches

- [ ] `backend/src/main/java/com/creditflow/product/repository/ProductRepository.java` : ajouter la méthode `findByIdForUpdate(Long id)` avec `@Lock(LockModeType.PESSIMISTIC_WRITE)` et `@Query("select p from Product p where p.id = :id")`, imports `jakarta.persistence.LockModeType` et `org.springframework.data.jpa.repository.Lock`.
- [ ] `backend/src/main/java/com/creditflow/product/service/ProductService.java` : ajouter la méthode `@Transactional public Product getEntityForUpdate(Long id)`, placée juste après `getEntity(Long id)` (ligne 97-103), reprenant le même corps mais appelant `productRepository.findByIdForUpdate(id)` au lieu de `findById(id)`. Ne pas toucher `getEntity(id)`.
- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java`, méthode `create()` :
  - Remplacer `Product product = productService.getEntity(request.productId());` (ligne 179) par `Product product = productService.getEntityForUpdate(request.productId());`.
  - Juste après ce chargement (avant les contrôles de boutique lignes 181-188), ajouter la garde :
    ```java
    if (product.getStock() == null || product.getStock() <= 0) {
        throw new BusinessRuleException(
                "Stock epuise pour ce produit : la vente ne peut pas etre creee");
    }
    ```
  - Rendre le décrément inconditionnel : remplacer le bloc lignes 238-240
    ```java
    if (product.getStock() != null && product.getStock() > 0) {
        productService.decreaseStock(product, 1);
    }
    ```
    par
    ```java
    productService.decreaseStock(product, 1);
    ```
- [ ] `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` — mettre à jour et ajouter :
  - Dans `stubCreationDependencies()` (lignes 278-293) : changer `Product.builder().id(1L).name("iPhone 13").stock(0).shop(shop).build()` en un stock strictement positif (ex. `.stock(5)`), et remplacer le stub `when(productService.getEntity(1L)).thenReturn(product);` par `when(productService.getEntityForUpdate(1L)).thenReturn(product);`.
  - Dans `createRejectsWhenCustomerBelongsToAnotherShop` (ligne 233-246) : le `product` construit ligne 236 n'a pas de stock explicite (défaut `Product.builder` → `null`, cf. `Product.java` ligne 51 `@Builder.Default private Integer stock = 0`, donc stock = 0 par défaut) — donner un stock positif explicite (ex. `.stock(5)`) pour que ce test échoue bien sur le contrôle de boutique et non sur la garde de stock ; remplacer `when(productService.getEntity(1L))` par `when(productService.getEntityForUpdate(1L))`.
  - Dans `createRejectsWhenProductBelongsToAnotherShop` (ligne 249-263) : même correction (stock positif explicite sur le `product` ligne 253, `getEntity` → `getEntityForUpdate`).
  - Dans `create_recordsOutStockMovementForSoldProduct` (lignes 301-345), qui utilise un vrai `ProductService` avec `ProductRepository` mocké : remplacer `when(productRepository.findById(1L)).thenReturn(Optional.of(product));` par `when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));` (le produit a déjà `.stock(3)`, pas de changement de valeur nécessaire).
  - Ajouter un test `createRejectsWhenStockIsZero` : produit avec `.stock(0)`, `productService.getEntityForUpdate(1L)` stubbé pour le retourner, `customerService.getEntity(1L)` stubbé avec un client de la bonne boutique ; assert `assertThatThrownBy(() -> creditSaleService.create(request)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("Stock epuise")`, puis `verify(productService, never()).decreaseStock(any(), anyInt());` et `verify(saleRepository, never()).saveAndFlush(any());`.
  - Ajouter un test `createRejectsWhenStockIsNull` : même schéma avec `.stock(null)`, même assertions.
  - Ajouter un test `createDecreasesStockWhenStockIsPositive` : produit `.stock(5)`, création réussie (réutiliser `stubCreationDependencies()`), puis `verify(productService).decreaseStock(product, 1);`.
- [ ] `backend/src/test/java/com/creditflow/product/service/ProductServiceTest.java` : ajouter un test `getEntityForUpdateRejectsProductFromAnotherShop`, symétrique de `getEntityRejectsProductFromAnotherShop` (lignes 174-182), stubant `productRepository.findByIdForUpdate(1L)` et vérifiant l'appel à `currentShopContext.assertAccessible`. Ajouter aussi un test `getEntityForUpdateReturnsProductWhenAccessible` vérifiant que `productRepository.findByIdForUpdate(1L)` est bien appelé (et non `findById`).
- [ ] `frontend/src/pages/NewSalePage.tsx` : dans l'`Autocomplete` du champ produit (lignes 232-249), utiliser `option.sellable` (type `Product` de `frontend/src/types.ts:166`) pour :
  - ajouter le suffixe `" — rupture de stock"` au libellé retourné par `getOptionLabel` quand `!option.sellable` (garder le libellé actuel `${option.name} — ${formatMoney(option.creditPrice)} (stock ${option.stock})` pour les produits vendables) ;
  - griser visuellement l'option correspondante dans `renderOption` (ajouter cette prop à l'`Autocomplete`, absente actuellement) via `sx={{ opacity: option.sellable ? 1 : 0.5 }}` sur l'item, sans désactiver la sélection (les produits `!sellable` restent sélectionnables — pas de flux d'override, mais le blocage se fait déjà côté backend avec message clair).

## Contrat technique

- **`ProductRepository.findByIdForUpdate(Long id): Optional<Product>`** — verrou `PESSIMISTIC_WRITE` (SQL `SELECT ... FOR UPDATE` sur PostgreSQL), même contrat de retour que `findById`.
- **`ProductService.getEntityForUpdate(Long id): Product`** — `@Transactional` (nécessite une transaction déjà ouverte côté appelant pour que le verrou soit tenu jusqu'au commit ; `CreditSaleService.create()` est déjà `@Transactional`), lève `ResourceNotFoundException` si absent, puis `currentShopContext.assertAccessible(...)`, identique à `getEntity`.
- **Garde de stock dans `CreditSaleService.create()`** : condition `product.getStock() == null || product.getStock() <= 0`, levée avant les contrôles de boutique existants (donc avant toute autre validation métier de `create()`).
- **Message d'erreur exact** : `"Stock epuise pour ce produit : la vente ne peut pas etre creee"` (sans accents, cohérent avec le style existant des autres messages de `BusinessRuleException` dans ce fichier). Mappé par `GlobalExceptionHandler.handleBusiness` en HTTP `422 UNPROCESSABLE_ENTITY`, champ JSON `message` de `ApiError`, consommé tel quel par `errorMessage()` côté frontend (`frontend/src/api/client.ts:78-80`) et affiché dans l'`Alert severity="error"` de `NewSalePage.tsx` (ligne 201).
- **`CreditSaleService.create()` — décrément de stock** : `productService.decreaseStock(product, 1)` devient un appel inconditionnel (plus de garde `if (product.getStock() != null && product.getStock() > 0)`), atteignable uniquement si le stock est strictement positif grâce à la garde amont.
- **Frontend — pas de changement de type** : `Product.sellable: boolean` existe déjà (`frontend/src/types.ts:166`), aucune modification de DTO ni d'endpoint requise.
- **Aucune migration Flyway** : `products.stock` reste `integer not null`, aucune contrainte CHECK ajoutée.

## Plan de tests

| Critère d'acceptation du ticket | Test |
|---|---|
| Une tentative de création de vente à crédit sur un produit à stock 0 est bloquée, jamais silencieusement acceptée | `CreditSaleServiceTest.createRejectsWhenStockIsZero` (nouveau) : stock 0 → `BusinessRuleException`, `saleRepository.saveAndFlush` jamais appelé, `decreaseStock` jamais appelé. Complété par `createRejectsWhenStockIsNull` (nouveau) pour le cas `stock == null`. |
| Le vendeur reçoit un message clair expliquant pourquoi la vente ne peut pas être créée | `createRejectsWhenStockIsZero` / `createRejectsWhenStockIsNull` : assertion `hasMessageContaining("Stock epuise")`. Côté HTTP/UI : test manuel — créer une vente sur un produit à stock 0 via `NewSalePage`, vérifier que l'`Alert severity="error"` affiche le message exact remonté par l'API (422). |
| Non-régression : la création de vente sur un produit en stock suffisant continue de fonctionner et de décrémenter le stock | `CreditSaleServiceTest.createDecreasesStockWhenStockIsPositive` (nouveau) : stock 5 → création réussie, `verify(productService).decreaseStock(product, 1)`. Complété par les tests existants déjà corrigés : `createSucceedsWhenCustomerAndProductMatchTargetShop`, `createCapturesGuarantorFields`, `createWithoutGuarantorLeavesFieldsNull`, `create_recordsOutStockMovementForSoldProduct` (celui-ci vérifie déjà, via un vrai `ProductService`, qu'un `StockMovement` de type `OUT` est enregistré). |
| Contrôles de boutique existants non régressés par le changement de méthode de chargement (`getEntity` → `getEntityForUpdate`) | `createRejectsWhenCustomerBelongsToAnotherShop` et `createRejectsWhenProductBelongsToAnotherShop` (mis à jour) : toujours `BusinessRuleException` avec `"boutique cible"`, désormais avec stock positif explicite et stub sur `getEntityForUpdate`. |
| Accès produit hors boutique refusé sur le nouveau chemin verrouillé | `ProductServiceTest.getEntityForUpdateRejectsProductFromAnotherShop` (nouveau), symétrique de `getEntityRejectsProductFromAnotherShop`. |
| Indicateur visuel de rupture de stock dans le formulaire de vente | Pas de test automatisé (aucune infrastructure de test frontend existante dans `frontend/src/pages`) — test manuel : ouvrir `NewSalePage`, vérifier qu'un produit à stock 0 apparaît dans l'`Autocomplete` avec le suffixe "— rupture de stock" et un rendu grisé, tout en restant sélectionnable (la garde backend bloque ensuite la soumission avec message clair). |
| Fermeture de la fenêtre de concurrence (deux vendeurs sur le dernier exemplaire) | Non testable unitairement avec Mockito (le verrou `PESSIMISTIC_WRITE` n'a d'effet qu'avec une vraie base) — pas d'infrastructure `@DataJpaTest` existante dans le dépôt. Test manuel/exploratoire recommandé avant merge : deux requêtes de création concomitantes sur le même produit à stock 1, vérifier qu'une seule aboutit et que l'autre échoue proprement (soit via la garde de stock si elle s'exécute après le commit de la première, soit en observant que les transactions sont sérialisées). À signaler au reviewer comme non couvert par la CI. |

## Écarts identifiés

- Le design mentionne uniquement `stubCreationDependencies()` comme fixture à corriger, mais deux autres tests (`createRejectsWhenCustomerBelongsToAnotherShop`, `createRejectsWhenProductBelongsToAnotherShop`) construisent leur propre `Product` sans stock explicite, ce qui vaut `stock = 0` par défaut (`Product.java:51`, `@Builder.Default`). Avec la nouvelle garde, ces deux tests échoueraient sur `BusinessRuleException("Stock epuise...")` au lieu de celle attendue sur la boutique, puisque la garde de stock est placée avant les contrôles de boutique. Ces deux tests doivent donc être corrigés en même temps que la garde est ajoutée, pas seulement `stubCreationDependencies()`. Traité dans la section Tâches ci-dessus.
- Le test `create_recordsOutStockMovementForSoldProduct` construit un vrai `ProductService` avec un `ProductRepository` mocké et stubbe `findById` directement ; ce stub doit devenir `findByIdForUpdate` sans quoi le test échouera avec un `NullPointerException`/`ResourceNotFoundException` après le changement de méthode d'accès. Non mentionné dans le design, ajouté ici.
- Aucun test automatisé ne peut couvrir l'effet réel du verrou pessimiste (nécessite une vraie base PostgreSQL) : le plan de tests couvre le comportement fonctionnel (blocage stock 0, message, non-régression) mais pas la garantie de concurrence en soi. À signaler explicitement au reviewer comme limite acceptée, cohérente avec l'absence d'infrastructure `@DataJpaTest` dans le dépôt actuel.
