# Review #2 (finale) — #10 Consolidation multi-boutiques

Branche : `bolt/issue-10-consolidation-multi-boutiques` — base `master`
Perimetre relu : `git diff master...HEAD` (90 fichiers) + les 4 commits de la passe de
correction (`670dc3d..HEAD`).

## Verdict

**CHANGES_REQUESTED**

Les **3 findings bloquants de la premiere revue sont tous correctement corriges**, avec
des tests qui echouent reellement sans le correctif. Le blocage restant tient a **un seul
finding nouveau** : un endpoint de lecture non cloisonne (`GET /api/audit-log`) qui laisse
un vendeur mono-boutique lire les noms de clients des autres boutiques — meme classe de
defaut que le finding 3 de la premiere revue (receptions de stock), et violation directe
de l'AC1. Tout le reste du chantier est conforme.

## Statut des 3 findings de la premiere revue

| # | Finding initial | Commit correctif | Statut |
|---|---|---|---|
| 1 | Backend ne demarre plus sur base vierge (`DemoDataSeeder` hors contexte de securite) | `3e1cdde` | **Corrige** |
| 2 | `POST /api/auth/login` en 404 systematique (`accessibleShops()` sur un `SecurityContext` non peuple) | `73b40bf` | **Corrige** |
| 3 | Fuite inter-boutiques via `GET /api/stock-receptions` | `2479492` | **Corrige** |

### Finding 1 — corrige

`bootstrap/DemoDataSeeder.java:80-107`. Le seeder s'authentifie comme l'administrateur
bootstrap (`properties.getAdmin().getUsername()`, role `ROLE_ADMIN`) avant d'appeler les
services de creation, et **restaure via `SecurityContextHolder.clearContext()` dans un
`finally`** : pas de fuite de contexte, y compris si le seeding leve.
Verifications faites :
- ordre correct : `AdminInitializer` est `@Order(1)`, le seeder `@Order(2)` — le compte
  admin existe donc toujours quand `CurrentShopContext.currentUser()` le resout ;
- l'admin bootstrap n'a aucune boutique assignee, donc branche super-admin
  d'`accessibleShopsOf()`, donc toutes les boutiques actives, donc `shopIdForCreation()`
  renvoie l'unique boutique ;
- installation multi-boutiques : garde explicite `shops.size() != 1`
  (`DemoDataSeeder.java:80-84`), qui evite l'ambiguite de `shopIdForCreation()` ;
- `DemoDataSeederTest.seedingRunsUnderTechnicalAdminIdentity` assere a la fois l'identite
  vue depuis `CurrentUser.username()` **pendant** le seeding et le fait que le contexte est
  vide **apres** ; le test echoue sans le correctif (identite `null`).

### Finding 2 — corrige

`common/security/CurrentShopContext.java:34-62` + `auth/service/AuthService.java:47-50`.
La regle de resolution a ete **factorisee dans un unique `accessibleShopsOf(User)`
prive**, appele par les quatre points d'entree (`accessibleShopIds()`,
`accessibleShopIds(user)`, `accessibleShops()`, `accessibleShops(user)`) : aucune
duplication divergente. La regle "ADMIN sans boutique assignee = toutes les boutiques
actives" (`CurrentShopContext.java:58-60`) est preservee a l'identique, ainsi que le tri
par nom et le `BusinessRuleException` pour un non-ADMIN sans boutique.

Le test de non-regression est reel, pas un mock complaisant :
`AuthServiceTest.loginResolvesAccessibleShopsWhileStillAnonymous` pose un
`AnonymousAuthenticationToken` dans le `SecurityContextHolder` et instancie un **vrai**
`CurrentShopContext` (pas le mock). Sans le correctif, `currentUser()` chercherait
"anonymousUser", non stubbe dans le mock de `UserRepository`, donc `Optional.empty()`,
donc `ResourceNotFoundException` : le test tombe si le code est retire.

Pas de piege de chargement paresseux : `UserRepository.findByUsernameIgnoreCase` porte
`@EntityGraph(attributePaths = "shops")` et `login` est `@Transactional(readOnly = true)`
alors que `open-in-view: false` — `user.getShops()` est bien resolu.

### Finding 3 — corrige

`supplier/service/StockReceptionService.java:41-46` (`search`) et `:53-60` (`getEntity`) :
**les deux** chemins sont desormais filtres, plus une garde a la creation (`:62-79`, refus
d'une ligne dont le produit n'appartient pas a la boutique cible).

- La sous-requete `StockReceptionSpecifications.inShops`
  (`supplier/repository/StockReceptionSpecifications.java:27-37`) utilise un `EXISTS`
  correle sur `StockReceptionLine` : pas de duplication de ligne dans la page, contrairement
  a une jointure `lines.product.shop.id IN (...)`. Correct sur une reception multi-lignes.
- Asymetrie residuelle : `search` retient une reception des qu'**une** ligne est accessible,
  alors que `getEntity` exige que **toutes** les lignes le soient. Non exploitable en
  pratique : `receive()` interdit desormais de melanger deux boutiques dans une meme
  reception, et V10 rattache toutes les donnees anterieures a la boutique par defaut, donc
  aucune reception multi-boutiques ne peut exister. A surveiller seulement si une reprise
  de donnees venait a en creer.
- `lines` est `@NotEmpty` (`supplier/dto/StockReceptionRequest.java:19-22`) : pas de
  reception a zero ligne qui echapperait au `forEach` de `getEntity`.
- Fournisseurs volontairement globaux : la decision est **documentee dans le code** et pas
  seulement dans le message de commit — javadoc de classe sur
  `supplier/service/SupplierService.java:22-27`. Argument coherent (la fiche fournisseur
  n'expose aucune donnee commerciale par boutique, `suppliers` n'a pas de `shop_id` depuis
  le ticket 8) et aligne sur `design.md:120-122`.

## Commit hors perimetre de la revue — `a949b87` : conforme

`backend/src/main/resources/db/migration/V11__users_audit_columns.sql`.

- **Nouvelle** migration : aucune migration deja jouee n'est modifiee retroactivement.
- Numero libre : `master` s'arrete a V9, la branche ajoute V10 puis V11 — aucune collision
  (le trou V8 est sans consequence pour Flyway).
- Le bug corrige est reel et anterieur au ticket : `User extends Auditable`
  (`auth/domain/User.java:34`) et `Auditable` mappe `created_by`/`updated_by`, mais
  `V1__create_schema.sql:7-17` ne les cree pas sur `users` et `V3__audit_columns.sql` ne
  les ajoute qu'aux tables metier. Toute insertion dans `users` echouait.
- `ADD COLUMN IF NOT EXISTS` : rejouable et non destructif.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| AC1 | Un utilisateur rattache a une seule boutique ne voit que les donnees de celle-ci | **Partiel** — voir finding N1 |
| AC2 | Un gerant multi-boutiques accede a un tableau de bord consolide et peut filtrer par boutique | **Couvert** |
| AC3 | Les rapports et exports acceptent un filtre boutique sans regression mono-boutique | **Couvert** |

**AC1** — couvert sur tous les modules metier : listes et recherches filtrees
(`CustomerService:47,57,67`, `ProductService:56,64,77,86`, `CreditSaleService:80`,
`PaymentService:68`, `InstallmentService:49,59,79`, `ReminderService:72`,
`StockReceptionService:44`), gardes `assertAccessible` sur tous les acces directs par
identifiant, recherche globale filtree transitivement (`GlobalSearchService` ne fait que
deleguer aux `quickSearch`/`search` deja filtres). Reste le trou `GET /api/audit-log`
decrit au finding N1, qui empeche de declarer le critere couvert.

**AC2** — `DashboardService.overview` (`dashboard/service/DashboardService.java:48`) resout
`resolveReadFilter()` une seule fois et propage le meme `shopIds` a tous les agregats ainsi
qu'a `installmentService.upcomingForShops`. Selecteur de boutique cote frontend
(`frontend/src/context/ShopContext.tsx`, `frontend/src/components/AppLayout.tsx`) avec vue
consolidee representee par `activeShopId: null`.

**AC3** — `ReportService.build` (`report/service/ReportService.java:36-52`) resout
`resolveReadFilter()` une seule fois et le passe aux 4 types de rapport ; l'export
(`ReportController:54-67`) reutilise le meme `build`, donc le filtre s'applique aussi aux
exports PDF et Excel. Non-regression mono-boutique garantie cote frontend :
`frontend/src/api/client.ts:30-33` n'ajoute `X-Shop-Id` que si `activeShopId` est present
**et** `readAccessibleShops().length > 1`.

Convention de filtrage coherente et conforme a `design.md:193-196` : les listes standard
utilisent `accessibleShopIds()` (scoping transparent), seuls les ecrans consolidables
(dashboard, rapports) honorent `X-Shop-Id` via `resolveReadFilter()`.

## Findings

### [BLOQUANT N1] Fuite inter-boutiques via le journal d'audit

`backend/src/main/java/com/creditflow/audit/web/AuditLogController.java:23-27`
`backend/src/main/java/com/creditflow/audit/service/AuditLogService.java:31-45`

L'endpoint `GET /api/audit-log` (parametres `entityType` et `entityId`) n'applique **aucun
filtre boutique** et **aucune restriction de role** : la classe `AuditLogController` ne
porte pas de `@PreAuthorize`, et `SecurityConfig.java:56` se contente de
`anyRequest().authenticated()`. `AuditLogService.list` interroge directement
`auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)`
sans jamais resoudre la boutique de l'entite ciblee.

La reponse (`audit/dto/AuditLogResponse.java`) expose `entityLabel`, `details` et `actor`.
Ces libelles sont des donnees nominatives appartenant a d'autres boutiques :

- `notification/service/ReminderService.java:112` enregistre
  `record("CUSTOMER", customer.getId(), customer.getFullName(), ...)` a **chaque relance
  envoyee**, donc pour la quasi-totalite des clients en retard de toutes les boutiques ;
- `customer/service/CustomerService.java:123` : nom complet du client supprime ;
- `product/service/ProductService.java:153` : nom du produit ainsi que ancien et nouveau
  prix dans `details` (action `PRICE_UPDATE`) ;
- `sale/service/CreditSaleService.java:228,242,267,279` : reference de contrat sur
  `CANCEL`, `DELETE` et `ATTACHMENT_ADD`/`ATTACHMENT_REMOVE`.

**Scenario declencheur** : un `SELLER` rattache a la seule boutique A, authentifie
normalement, appelle en boucle `/api/audit-log?entityType=CUSTOMER&entityId=1`, puis 2,
puis 3, etc. Les identifiants sont sequentiels (`BIGSERIAL`), l'enumeration est triviale.
Il recupere les noms complets des clients de la boutique B ainsi que l'historique des
changements de prix du catalogue de B. Aucune contrainte de role ne l'en empeche, aucun
`assertAccessible` n'est traverse.

C'est exactement la meme classe de defaut que le finding 3 de la premiere revue (module non
instrumente par le ticket, endpoint de lecture laisse ouvert) et une violation directe de
l'AC1. Ni `spec.md` ni la section "Hors perimetre" de `design.md:179-200` ne mentionnent le
journal d'audit : ce n'est pas un arbitrage assume, c'est un oubli.

**Correction attendue** (une quinzaine de lignes, mecanique) : dans `AuditLogService.list`,
resoudre la boutique de l'entite ciblee selon `entityType` et deleguer la garde aux services
deja instrumentes — `CUSTOMER` vers `customerService.getEntity(entityId)`, `PRODUCT` vers
`productService.getEntity(entityId)`, `CREDIT_SALE` vers `creditSaleService.getEntity(entityId)`
(chacun appelle deja `assertAccessible` et leve `ResourceNotFoundException` sans reveler
l'existence de la ressource) ; `PENALTY_SETTINGS` reserve a `ADMIN` (parametre global assume
par `design.md:128-131`). Ajouter un test qui echoue sans la garde, sur le modele de
`StockReceptionServiceTest.getEntity_rejectsReceptionFromAnotherShop`. A defaut, documenter
explicitement la decision inverse dans `design.md` — mais exposer des noms de clients d'une
autre boutique a un vendeur est difficilement defendable.

### [MINEUR N2] Aucune requete de filtrage par boutique n'est exercee contre une base

Le module backend ne contient **aucun** test d'integration : ni `@SpringBootTest`, ni
`@DataJpaTest`, et `backend/pom.xml` ne declare ni H2 ni Testcontainers. Toutes les requetes
ajoutees par ce ticket (`findLateForShops`, `findBetweenForShops`, les
`quickSearch(..., shopIds, ...)`, les `inShops` de chaque `*Specifications`) ne sont donc
jamais executees contre un vrai moteur SQL.

Le cas le plus expose est `StockReceptionSpecifications.inShops`
(`supplier/repository/StockReceptionSpecifications.java:27-37`) : du Criteria nouvellement
ecrit, avec une sous-requete correlee (`cb.equal(line.get("reception"), root)`). Son seul
test, `StockReceptionSpecificationsTest.inShopsFiltersOnLineProductShop`, mocke
integralement `CriteriaBuilder`, `Subquery` et `Root` : il verifie la **forme des appels**,
pas que le SQL genere soit valide ni qu'il filtre reellement. Si le predicat correle etait
mal forme, `GET /api/stock-receptions` renverrait 500 en production sans qu'aucun test ne le
detecte.

Limite structurelle preexistante de la strategie de test du depot, pas une regression
introduite par ce bolt, d'ou le classement en mineur. Mais avant mise en production, un
passage manuel sur `GET /api/stock-receptions`, le dashboard et les rapports avec deux
boutiques peuplees est indispensable.

### [MINEUR N3] `assertAccessible` par ligne de reception : N resolutions evitables

`backend/src/main/java/com/creditflow/supplier/service/StockReceptionService.java:57-58`

`getEntity` appelle `currentShopContext.assertAccessible(...)` **une fois par ligne**, et
chaque appel refait `accessibleShopIds()`, donc `currentUser()`, donc une lecture
`userRepository.findByUsernameIgnoreCase` (aucun cache, cf. `design.md:171-177`). Une
reception de 40 lignes declenche 40 resolutions identiques. Sans consequence fonctionnelle,
mais un `assertAccessible` sur l'ensemble distinct des boutiques des lignes serait
equivalent et a cout constant. Non bloquant.

## Points verifies conformes (rappel et non-regression de la passe de correction)

La passe de correction (`670dc3d..HEAD`) ne touche que `AuthService`, `CurrentShopContext`,
`DemoDataSeeder`, `StockReception*`, `SupplierService` (javadoc seule) et la nouvelle `V11` :
**aucun des points valides en premiere revue n'a ete modifie**, la non-regression est acquise
par construction. Re-verifie malgre tout :

- `V10__shops.sql` : numero libre, retro-remplissage `UPDATE ... SET shop_id` avant
  `SET NOT NULL`, FK et index poses ensuite, boutique par defaut inseree avant usage,
  `user_shops` en `ON DELETE CASCADE`.
- Gardes `assertAccessible` sur tous les acces directs par identifiant
  (`CustomerService:82`, `ProductService:96`, `CreditSaleService:116-120`,
  `PaymentService:75,97,169`, `InstallmentService.bySale`, `ReminderService.prepareForSale`),
  couvrant transitivement pieces jointes de contrat, mouvements de stock et profil client.
- `CreditSaleService.create` rejette un client ou un produit hors de la boutique cible.
- `DashboardService.overview:48` : `resolveReadFilter()` resolu une seule fois.
- `frontend/src/api/client.ts:30-33` : `X-Shop-Id` jamais envoye en mono-boutique ; les cles
  `creditflow.accessibleShops` et `creditflow.activeShop` sont purgees au logout comme au
  401 (`AuthContext.tsx`, `client.ts:45-50`).
- `AdminInitializer` intact : ne reference ni `Shop` ni `CurrentShopContext`, le compte
  bootstrap reste sans boutique assignee et beneficie du mode super-admin.
- `ShopController` et `UserController` : `@PreAuthorize("hasRole('ADMIN')")` au niveau
  classe, conforme au tableau RBAC de `spec.md:428`.
- `ShopService.delete` sans verification manuelle : conforme a la decision explicite de
  `spec.md:35` (la contrainte FK remonte en 409 via `GlobalExceptionHandler:53-58`).

## Reserve de la premiere revue — levee

Relecture ligne a ligne de `git diff master...HEAD -- backend/src/test/java` : **aucune
assertion existante n'a ete supprimee ni relachee** pour faire passer la suite. Les seules
lignes retirees de tests preexistants sont des adaptations de signature ou de fixture :

- `CustomerServiceTest` : `verify(customerRepository, never()).quickSearch(any(), any())`
  devient la variante a trois arguments (meme force d'assertion) ; les fixtures recoivent
  une boutique.
- `ProductServiceTest`, `PaymentServiceTest`, `CreditSaleServiceTest`,
  `StockReceptionServiceTest`, `ReminderServiceTest` : constructeurs de service elargis
  (`CurrentShopContext`, `ShopRepository`) et fixtures dotees d'une boutique.
- `UserControllerTest` : `UserRequest` et `UserResponse` gagnent `shopIds`/`shops` ; tous
  les `andExpect(status()...)` sont inchanges, et deux tests RBAC sont ajoutes sur
  `PATCH /api/users/{id}/shops`.

Aucun fichier de test supprime, aucun `@Disabled` introduit.

## Build et tests

Executes par l'orchestrateur avant cette revue, non relances (la valeur ajoutee de ce
passage est la lecture critique du code) :

| Commande | Resultat |
|---|---|
| `mvn -o test` (backend) | **BUILD SUCCESS** — 264 tests, 0 failure, 0 error (254 avant la passe de correction, soit +10) |
| `npm run build` (frontend, `tsc --noEmit && vite build`) | **Succes**, aucune erreur TypeScript |

Le build vert n'est pas en cause dans ce verdict : le blocage est fonctionnel (AC1), pas
technique.

## Ce qu'il reste pour passer en APPROVE

Un seul point : **finding N1**. Le reste du chantier est conforme et les 3 findings initiaux
sont proprement corriges. N2 et N3 sont des remarques a traiter hors de ce ticket.
