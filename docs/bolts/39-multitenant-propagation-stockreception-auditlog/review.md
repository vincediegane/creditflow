# Review — #39 Multi-tenant 6/10 — Propagation StockReception/AuditLog

APPROVE

## Résumé de la vérification

Diff `master..HEAD` (4 commits : design, spec, code, tests) relu intégralement, pas seulement le rapport du codeur. Périmètre exactement conforme à `spec.md`/`design.md` : 5 fichiers de code touchés (2 production + 3 tests), aucun fichier hors périmètre.

```
backend/.../repository/StockReceptionSpecifications.java   | +19
backend/.../service/StockReceptionService.java              | +3 -1
backend/.../audit/service/AuditLogAccessGuardTest.java       | +9
backend/.../repository/StockReceptionSpecificationsTest.java| +47
backend/.../service/StockReceptionServiceTest.java           | +5 -1
```

## Critères d'acceptation

| Critère | Statut | Détail |
|---|---|---|
| Aucun endpoint réception de stock n'expose de donnée d'une autre organisation | **Couvert** | `StockReceptionSpecifications.inOrganization` (sous-requête `EXISTS` corrélée) ajoutée et combinée à `inShops` dans `StockReceptionService.search`. `getEntity`/`receive` vérifiés indépendamment (voir ci-dessous) — déjà sûrs par construction, non modifiés, conformément au design. Testé par `StockReceptionSpecificationsTest.inOrganizationFiltersOnLineProductShopOrganization`, `StockReceptionServiceTest.search_filtersOnAccessibleShopsAndOrganization`, `getEntity_rejectsReceptionFromAnotherShop`, `receive_rejectsProductFromAnotherShop` (ces deux derniers déjà existants et toujours verts). |
| Aucun endpoint journal d'audit n'expose de donnée d'une autre organisation | **Couvert** | Vérifié moi-même par lecture directe de `AuditLogAccessGuard.java` (inchangé) : le `switch` ne contient aucun cas `STOCK_RECEPTION`, le `default -> throw ResourceNotFoundException` s'applique donc. Le cas `CREDIT_SALE` délègue à `CreditSaleService.getEntity()`, dont j'ai confirmé par lecture directe qu'il appelle `currentShopContext.assertAccessible(sale.getShop().getId())`, et que `create()` garantit `customer.shop == sale.shop`. Testé par `AuditLogAccessGuardTest` (nouveau cas optionnel `rejectsStockReceptionEntityType` + `rejectsGlobalOrUnknownEntityType` déjà existant qui couvre déjà génériquement ce chemin). |
| Instance mono-tenant : comportement strictement identique | **Couvert** | `inOrganization` ne restreint jamais un ensemble déjà filtré par `inShops` tant qu'une seule organisation existe (`currentOrganizationId()` constant). Pas de changement de signature d'endpoint/DTO. `AuditLogAccessGuard`/`ReminderService` non touchés → comportement mono-tenant inchangé par construction. |

## Vérifications techniques faites (pas prises pour argent comptant)

1. **Corrélation de la sous-requête `EXISTS`** — `StockReceptionSpecifications.inOrganization` (fichier, L46-58) utilise exactement le même patron que `inShops` (L27-39) déjà en place : `lines.where(cb.equal(line.get("reception"), root), cb.equal(line.get("product").get("shop").get("organization").get("id"), organizationId))`. La corrélation `line.reception = root` est bien présente — pas de sous-requête non corrélée, pas de produit cartésien. Vérifié aussi que la chaîne d'entités existe réellement : `StockReceptionLine.product` (non nullable), `Product.shop` (non nullable), `Shop.organization` (non nullable) — pas de risque de `NullPointerException`/jointure invalide côté Hibernate.
2. **`AuditLogAccessGuard.java` / `ReminderService.java`** — `git diff master..HEAD` confirmé : **aucune ligne** dans ces deux fichiers de production (seul un test a été ajouté sur `AuditLogAccessGuardTest`). Conforme à la contrainte explicite du ticket/design.
3. **`ReminderService`** — relu les 3 chemins indépendamment du design : `prepareForSale` appelle `assertAccessible` immédiatement après chargement ; `prepareForCustomer` passe par `customerService.getEntity` (sûr) puis charge les ventes du client sans filtre supplémentaire, ce qui est sûr car `CreditSaleService.create` garantit `customer.shop == sale.shop` (vérifié L180 de `CreditSaleService.java`) ; `sendAll` passe `accessibleShopIds()` à `lateCustomerService.lateCustomers`. Les trois confirment l'analyse du design, pas de faille trouvée.
4. **Tests** — vérifiés qu'ils testent réellement ce qu'ils prétendent :
   - `inOrganizationFiltersOnLineProductShopOrganization` : mocks Root/Subquery/CriteriaBuilder, assertion `verify(subquery).where(sameReception, organizationPredicate)` où `sameReception = cb.equal(receptionPath, root)` — vérifie bien la corrélation, pas seulement l'appel à `cb.equal(...)` sur l'organisation.
   - `search_filtersOnAccessibleShopsAndOrganization` : vérifie `verify(currentShopContext).currentOrganizationId()` en plus de `accessibleShopIds()` — un retrait de `inOrganization(...)` dans `search` ferait échouer ce test (Mockito `verify` échoue si la méthode n'est jamais appelée).
   - `rejectsStockReceptionEntityType` (nouveau, optionnel) : redondant avec `rejectsGlobalOrUnknownEntityType` mais ne casse rien, documentaire comme annoncé.
5. **RBAC** — `StockReceptionController` inchangé, `@PreAuthorize("hasRole('ADMIN')")` sur la création toujours en place ; hors périmètre du ticket, pas de régression constatée.
6. **Pas d'effet de bord ailleurs** — seul `StockReceptionService.search` utilise `stockReceptionRepository.findAll(...)` avec Specification ; `getEntity`/`receive` (les deux autres points d'accès) utilisent `findById`/`save` et restent protégés par `assertAccessible`/vérification de boutique cible, non modifiés.

## Findings

Aucun. Rien à signaler au-delà des points déjà documentés comme acceptés dans le design (coût mineur de deux sous-requêtes `EXISTS` redondantes tant que `accessibleShopIds()` n'est pas corrompu — défense en profondeur assumée, cohérente avec #36/#37/#38).

## Build/tests

- `cd backend && mvn -Dtest=StockReceptionSpecificationsTest,StockReceptionServiceTest,AuditLogAccessGuardTest test` → succès (0 échec).
- `cd backend && mvn test` (suite complète) → **BUILD SUCCESS** (exit code 0). Agrégation des rapports Surefire (`target/surefire-reports/*.txt`) : **389 tests, 0 failure, 0 error, 0 skipped**.
  - `StockReceptionSpecificationsTest` : 4/4 verts (2 existants + 2 nouveaux).
  - `StockReceptionServiceTest` : 6/6 verts (test étendu/renommé inclus).
  - `AuditLogAccessGuardTest` : 4/4 verts (3 existants + 1 nouveau optionnel).

Verdict : **APPROVE**.
