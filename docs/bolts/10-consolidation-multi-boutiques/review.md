# Review — #10 Consolidation multi-boutiques

## Verdict

**CHANGES_REQUESTED**

3 findings bloquants : 2 regressions de demarrage/connexion, 1 fuite de donnees
inter-boutiques (violation directe de l'AC1).

## Findings

### [BLOQUANT 1] Le backend ne demarre plus sur une base vierge (seeder de demo)

`backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java`

`DemoDataSeeder` s'execute comme `ApplicationRunner` au demarrage, hors contexte de
securite. Il appelle `customerService.create(...)` / `productService.create(...)` /
`creditSaleService.create(...)` qui invoquent desormais
`CurrentShopContext.shopIdForCreation()` → `currentUser()` →
`CurrentUser.username()` retourne `null` →
`IllegalStateException("Aucun utilisateur authentifie")`
(`CurrentShopContext.java:118-125`).

`app.demo.seed` vaut `true` par defaut (`application.yml` : `seed: ${DEMO_SEED:true}`)
et le seeder ne s'active que si `customerRepository.count() == 0` : **le backend echoue
donc au demarrage sur une base vierge en configuration par defaut**. Les tests ne le
detectent pas (le seeder n'y tourne pas).

Correction attendue : faire resoudre explicitement la boutique par le seeder (par ex.
`shopRepository.findAllByOrderByNameAsc()` puis construction directe des entites, ou
execution du seeder dans un contexte d'authentification technique), sans dependre de
`CurrentShopContext`.

### [BLOQUANT 2] `POST /api/auth/login` echoue systematiquement (404)

`backend/src/main/java/com/creditflow/auth/service/AuthService.java:37-49`

`login()` appelle `currentShopContext.accessibleShops()` (ligne 48), qui resout
l'utilisateur courant via `CurrentUser.username()`
(`common/security/CurrentShopContext.java:118-125`). Or
`authenticationManager.authenticate(...)` (ligne 38) **ne peuple pas**
`SecurityContextHolder` : `ProviderManager.authenticate()` se contente de renvoyer
l'`Authentication`, que `login()` ignore. L'hypothese inverse ecrite dans la spec
(Phase 2, tache `AuthService`) est fausse.

Consequence a l'execution de `POST /api/auth/login` (endpoint `permitAll`, aucun
`Authorization: Bearer`) :
- `AnonymousAuthenticationFilter` (actif par defaut, `SecurityConfig` ne le desactive
  pas) a place un `AnonymousAuthenticationToken` dans le contexte →
  `CurrentUser.username()` retourne `"anonymousUser"` →
  `userRepository.findByUsernameIgnoreCase("anonymousUser")` vide →
  `ResourceNotFoundException("Utilisateur introuvable")` → **404 a chaque connexion** ;
- si l'anonymous etait desactive, `username == null` → `IllegalStateException` → 500.

Dans les deux cas plus personne ne peut se connecter a l'application. Aucun test ne le
detecte : `AuthServiceTest.loginIncludesAccessibleShops` mocke `CurrentShopContext`
(`backend/src/test/java/com/creditflow/auth/service/AuthServiceTest.java:112-121`) et le
backend ne contient aucun test d'integration (`@SpringBootTest` absent de
`backend/src/test/java`), donc la chaine de filtres reelle n'est jamais exercee.

Correction attendue : construire `accessibleShops` a partir de l'entite `user` deja
chargee (meme logique que `CurrentShopContext.accessibleShops()` mais sur un `User`
passe en parametre), ou poser explicitement l'`Authentication` retournee par
`authenticate(...)` dans le `SecurityContextHolder` avant l'appel. Ajouter un test qui
echouerait sans le correctif.

### [BLOQUANT 3] Fuite inter-boutiques via les receptions de stock

`backend/src/main/java/com/creditflow/supplier/service/StockReceptionService.java:38-53`

`search(supplierId, pageable)` (ligne 38) et `getEntity(id)` (ligne 50) n'appliquent
aucun filtre boutique. `StockReceptionResponse` expose
`lines[].productId` / `lines[].productName` / `quantity`
(`supplier/dto/StockReceptionResponse.java`), et
`StockReceptionController` (`supplier/web/StockReceptionController.java:36-46`) laisse
`GET /api/stock-receptions` et `GET /api/stock-receptions/{id}` ouverts a tout
utilisateur authentifie (seul `POST` est `@PreAuthorize("hasRole('ADMIN')")`).

Scenario : un `SELLER` rattache a la seule boutique A appelle
`GET /api/stock-receptions` ; il obtient les receptions de la boutique B avec le nom,
l'identifiant et les quantites recues des produits de B. Violation directe de l'AC1
(« un utilisateur mono-boutique ne voit que les donnees de sa boutique »).

Note : la creation est correctement gardee (`receive()` passe par
`productService.getEntity(...)`, ligne 67, qui appelle `assertAccessible`).

Correction attendue : filtrer `search`/`getEntity` sur la boutique des produits des
lignes (jointure `lines.product.shop.id IN :shopIds`), ou documenter explicitement une
decision produit contraire.

## Points verifies conformes

- Migration `V10__shops.sql` : numero libre, aucune collision dans
  `db/migration/` (V1-V7, V9, V10) ; retro-remplissage `UPDATE ... SET shop_id`
  **avant** `SET NOT NULL` pour les 3 tables ; FK + index poses apres ; boutique par
  defaut inseree avant usage ; rattachement des `SELLER` existants a la boutique par
  defaut ; `user_shops` en `ON DELETE CASCADE`. Conforme au contrat de la spec.
- Gardes d'acces direct par identifiant :
  `CustomerService.getEntity` (`customer/service/CustomerService.java:81-85`),
  `ProductService.getEntity` (`product/service/ProductService.java:94-99`),
  `CreditSaleService.getEntity` (`sale/service/CreditSaleService.java:116-120`),
  `PaymentService.findById` / `receipt` / `findBySale` / `register` / `delete`,
  `InstallmentService.bySale`, `ReminderService.prepareForSale` — tous appellent
  `assertAccessible`. Les pieces jointes de contrat (`uploadAttachment` ligne 249,
  `deleteAttachment` ligne 273) et `ProductService.stockMovements` (ligne 198) passent
  par `getEntity` : gardes transitivement. `CustomerProfileService.profile` passe par
  `customerService.findById` → `getEntity` : garde.
- `CreditSaleService.create` (`sale/service/CreditSaleService.java:149-160`) rejette un
  client ou un produit n'appartenant pas a la boutique cible.
- `DashboardService.overview` (`dashboard/service/DashboardService.java:48`) resout
  `resolveReadFilter()` **une seule fois** en tete de methode et propage le meme
  `shopIds` a tous les agregats ainsi qu'a `installmentService.upcomingForShops`
  (ligne 58) : pas de confusion avec `accessibleShopIds()`, l'en-tete `X-Shop-Id` est
  donc bien respecte sur le dashboard (AC2).
- Non-regression mono-boutique cote frontend (`frontend/src/api/client.ts:28-38`) :
  l'en-tete `X-Shop-Id` n'est ajoute que si `activeShopId` est present **et**
  `readAccessibleShops().length > 1`. Un utilisateur mono-boutique n'envoie jamais
  l'en-tete (AC3 preserve).
- `AdminInitializer` (`auth/bootstrap/AdminInitializer.java`) ne reference ni `Shop` ni
  `CurrentShopContext` : le compte admin bootstrap reste cree sans boutique assignee et
  beneficie donc du mode super-admin d'`accessibleShopIds()`. Pas de regression.

## Reserve de couverture

Le point 7 de la mission de revue (verification exhaustive que les assertions des suites
de tests existantes n'ont pas ete relachees pour faire passer le build) n'a pas pu etre
audite ligne a ligne. Les 254 tests passent et aucune suppression de fichier de test
n'est visible au diff, mais une relecture ciblee des diffs de
`CustomerServiceTest` / `ProductServiceTest` / `PaymentServiceTest` reste souhaitable
lors de la passe de correction.
