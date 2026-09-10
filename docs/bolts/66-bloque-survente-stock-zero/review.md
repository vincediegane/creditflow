# Review — #66 Empecher la survente : bloquer la vente a credit quand le stock produit est a 0

## Verdict

APPROVE

## Critères d'acceptation

| Critère | Statut | Preuve |
|---|---|---|
| Une tentative de création de vente à crédit sur un produit à stock 0 est bloquée, jamais silencieusement acceptée | Couvert | `CreditSaleService.create()` charge le produit via `getEntityForUpdate` (verrou `PESSIMISTIC_WRITE`) puis lève `BusinessRuleException` si `stock == null \|\| stock <= 0`, **avant** les contrôles de boutique. Tests `createRejectsWhenStockIsZero` et `createRejectsWhenStockIsNull` vérifient l'exception et l'absence d'appel à `decreaseStock`/`saveAndFlush`. |
| Le vendeur reçoit un message clair expliquant pourquoi la vente ne peut pas être créée | Couvert | Message exact `"Stock epuise pour ce produit : la vente ne peut pas etre creee"`, mappé en HTTP 422 par `GlobalExceptionHandler.handleBusiness` (déjà existant, non modifié), affiché tel quel dans l'`Alert severity="error"` de `NewSalePage.tsx`. Côté formulaire, suffixe `" — rupture de stock"` + item grisé (`opacity: 0.5`) sans `disabled`, donc le vendeur peut toujours sélectionner le produit et voir le message backend. |
| Non-régression : vente sur stock suffisant continue de fonctionner et de décrémenter le stock | Couvert | `decreaseStock(product, 1)` devenu inconditionnel (atteignable uniquement si stock > 0 grâce à la garde amont, sans duplication de condition). Nouveau test `createDecreasesStockWhenStockIsPositive` (stock 5, `verify(productService).decreaseStock(product, 1)`), plus tous les tests existants de création (`createSucceedsWhenCustomerAndProductMatchTargetShop`, `createCapturesGuarantorFields`, `createWithoutGuarantorLeavesFieldsNull`, `create_recordsOutStockMovementForSoldProduct`) verts après correction des fixtures. |

## Vérifications techniques ciblées

1. **Ordre des vérifications dans `create()`** — confirmé : `getEntityForUpdate` → garde de stock → contrôles de boutique (customer puis produit). Conforme au contrat de la spec ; les tests `createRejectsWhenCustomerBelongsToAnotherShop` / `createRejectsWhenProductBelongsToAnotherShop` utilisent désormais un stock positif explicite (`.stock(5)`) pour échouer sur le bon motif.
2. **`ProductRepository.findByIdForUpdate`** — porte bien `@Lock(LockModeType.PESSIMISTIC_WRITE)` associé à une `@Query("select p from Product p where p.id = :id")` explicite (requis, Spring Data ne supporte pas `@Lock` sur une méthode dérivée sans requête).
3. **`getEntity(Long id)` original** — intact, non modifié (`ProductService.java:97-103`). `getEntityForUpdate` ajouté juste après (lignes 105-111), copie fidèle utilisant `findByIdForUpdate`.
4. **Signature `stubCreationDependencies()` (void → Product)** — les trois appelants existants qui ignorent la valeur de retour (lignes 198, 216, 268 de `CreditSaleServiceTest.java`) restent valides en Java ; seul le nouveau test `createDecreasesStockWhenStockIsPositive` exploite le retour. Pas de régression.
5. **Décrément de stock inconditionnel** — pas de duplication de la condition ; l'ancien `if (product.getStock() != null && product.getStock() > 0)` a bien été supprimé (diff vérifié), la garde de stock en amont rend le chemin sûr.
6. **`NewSalePage.tsx`** — pas de `disabled` ni `getOptionDisabled` ajouté ; `Box` déjà importé ; `sellable: boolean` déjà présent dans `types.ts:166`. Le produit en rupture reste sélectionnable, conforme à la spec.
7. **Périmètre** — diff limité à `ProductRepository.java`, `ProductService.java`, `CreditSaleService.java`, `CreditSaleServiceTest.java`, `ProductServiceTest.java`, `NewSalePage.tsx` (+ `design.md`/`spec.md`). Rien hors périmètre touché. Aucune migration Flyway ajoutée, conforme (le contrat ne l'exigeait pas).

## Limite documentée (non bloquante)

Comme identifié par la spec : la fermeture réelle de la fenêtre de concurrence (verrou pessimiste) n'est pas testable unitairement dans ce dépôt (pas d'infrastructure `@DataJpaTest`/PostgreSQL réelle en CI). Un test manuel exploratoire (deux créations concurrentes sur un produit à stock 1) est recommandé avant merge en prod, mais ne bloque pas cette review compte tenu de l'absence d'infrastructure existante pour ce type de test dans le dépôt.

## Build/tests

- Backend : `cd backend && mvn -o test` → **BUILD SUCCESS**, 423 tests, 0 échec, 0 erreur, 0 skip (agrégat `target/surefire-reports/*.txt`). `CreditSaleServiceTest` : 25/25 verts. `ProductServiceTest` : 12/12 verts.
- Frontend : `cd frontend && npx tsc --noEmit` → exit 0, aucune erreur de type.
- Frontend : `cd frontend && npx vitest run` → 4 fichiers, 22 tests, tous verts (aucun test dédié à `NewSalePage.tsx`, cohérent avec l'absence d'infrastructure de test de rendu pour cette page, documentée dans la spec).

## Conclusion

Le code respecte fidèlement le contrat technique de la spec (ordre des contrôles, verrou pessimiste avec `@Query` explicite, message d'erreur exact, décrément inconditionnel, rendu frontend non bloquant). Les fixtures de test identifiées par la spec comme cassées ont bien été corrigées. Le changement de signature de `stubCreationDependencies()` ne casse aucun appelant. Build et tests passent intégralement côté backend et frontend. Aucun finding bloquant.
