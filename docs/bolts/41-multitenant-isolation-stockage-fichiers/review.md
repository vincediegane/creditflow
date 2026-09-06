# Review — #41 Multi-tenant 8/10 — Isolation du stockage fichiers

## Verdict

APPROVE

## Résumé de la vérification

Diff réel inspecté (`git diff master..HEAD`, 4 commits : design, spec, code, tests). Le diff de code
est strictement limité à deux lignes :

- `CustomerService.uploadPhoto()` : `documentStorage.store(file, "customers")` →
  `documentStorage.store(file, "org-" + currentShopContext.currentOrganizationId() + "/customers")`.
- `CreditSaleService.uploadAttachment()` : `documentStorage.store(file, "sales/" + saleId)` →
  `documentStorage.store(file, "org-" + currentShopContext.currentOrganizationId() + "/sales/" + saleId)`.

Confirmé qu'aucun autre fichier du périmètre backend `main` n'est touché
(`git diff master..HEAD --name-only -- backend/src/main` ne retourne que ces deux fichiers) : ni
`DocumentStorage`, `LocalDiskStorage`, `S3DocumentStorage`, ni `CustomerController`/`SaleController`,
ni `SecurityConfig`, ni de migration Flyway, ni de code frontend. Conforme à `spec.md`.

## Critères d'acceptation

| # | Critère | Statut | Justification |
|---|---|---|---|
| AC1 | Fichier uploadé non accessible sans authentification | Couvert (code préexistant, non retouché ici) | Vérifié moi-même, indépendamment du rapport du codeur/design : `SecurityConfig.PUBLIC_ENDPOINTS` ne contient pas `/uploads/**` ni les endpoints fichiers ; `anyRequest().authenticated()` s'applique donc à `GET /api/customers/{id}/photo` et `GET /api/sales/{id}/attachments/{attachmentId}/file`. Ticket #45 (mergé) a bien supprimé l'exposition statique. |
| AC2 | Pas d'accès inter-organisation même en connaissant l'UUID | Couvert (code préexistant, non retouché ici) | Vérifié moi-même : `CustomerService.resolvePhoto`/`CreditSaleService.resolveAttachment` passent tous deux par `getEntity(id)`, qui appelle `currentShopContext.assertAccessible(shop.getId())`. `assertAccessible` compare à `accessibleShopIds()`, qui pour un utilisateur non-admin est dérivé de `user.getShops()` (boutiques assignées, forcément dans son organisation) et pour un ADMIN de `shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(organizationId)` — un shop d'une autre organisation ne peut donc jamais être "accessible". Renforcé indépendamment par le RLS Postgres du ticket #40 (non revérifié en détail ici, hors périmètre du diff, mais cohérent avec le design). |
| AC3 | Instance mono-tenant : fichiers existants restent accessibles après migration | Couvert, sans migration | Vérifié moi-même dans le code : `LocalDiskStorage.resolve(key)`/`.delete(key)` et `S3DocumentStorage.resolve(key)`/`.delete(key)` utilisent littéralement la clé/chemin passé en paramètre (celle stockée dans `customers.photo_url` / `sale_attachments.file_url`), sans aucune dérivation depuis `currentShopContext.currentOrganizationId()` ou reconstruction du chemin. Un fichier ancien stocké sous `customers/<uuid>.png` (sans préfixe `org-`) reste donc résolvable tel quel via les endpoints authentifiés existants — seuls les nouveaux uploads utilisent le préfixe `org-{id}/`. Aucune tâche de migration nécessaire, cohérent avec `spec.md`. |

## Jugement sur la décision de périmètre

La décision de ne pas dupliquer/ajouter un mécanisme d'autorisation (puisque AC1/AC2 sont déjà
couverts par #45 + #40) est bien fondée. Je l'ai vérifiée moi-même par lecture directe du code
(`SecurityConfig`, `CustomerService.getEntity`/`resolvePhoto`, `CreditSaleService.getEntity`/
`resolveAttachment`, `CurrentShopContext.assertAccessible`/`accessibleShopIds`/
`currentOrganizationId`), pas seulement en faisant confiance à `design.md`/`spec.md`. Le seul
changement de code proposé (préfixe `org-{organizationId}/` sur la convention de dossier physique)
est une défense en profondeur cohérente avec le périmètre explicite du ticket ("dossiers scopés par
organisation"), sans reformuler un problème d'autorisation déjà résolu.

## Vérification des tests

- Les deux tests existants adaptés (`CreditSaleServiceTest.accumulatesIdDocumentAttachments`,
  `.replacesExistingSignature`) stubbent désormais `currentShopContext.currentOrganizationId()` →
  `100L` et vérifient `documentStorage.store(file, "org-100/sales/1")` : ils échoueraient si le
  préfixe n'était pas appliqué (pas de test tautologique).
- Le nouveau test `CustomerServiceTest.uploadPhotoStoresFileUnderOrganizationScopedFolder` stub et
  vérifie `documentStorage.store(file, "org-100/customers")` (organisation stubbée à `100L` dans le
  `@BeforeEach` partagé) : échouerait de la même façon si le préfixe disparaissait.
- Les tests d'autorisation existants (`getEntityRejectsCustomerFromAnotherShop`,
  `resolvePhotoRejectsCustomerFromAnotherShop` dans `CustomerServiceTest`,
  `resolveAttachmentRejectsSaleFromAnotherShop` dans `CreditSaleServiceTest`) ne sont pas modifiés
  dans le diff — confirmé par `git diff`, cohérent avec le fait qu'aucune garde d'autorisation n'est
  touchée.
- Aucun test de régression n'est cassé par le changement de valeur de `folder` : les tests qui
  stubbent `documentStorage` avec `any()`/mocks non contraints par la valeur exacte de `folder`
  continuent de passer (les échecs auraient nécessairement montré des `UnnecessaryStubbingException`
  ou des mismatches Mockito, absents du run complet).

## Build/tests

Commande exécutée moi-même (pas seulement le rapport du codeur) :

```
cd backend && mvn -B test
```

Résultat : `BUILD SUCCESS`, `Tests run: 408, Failures: 0, Errors: 0, Skipped: 0` (toutes classes de
test, y compris `CustomerServiceTest` — 13/13 — et `CreditSaleServiceTest` — 22/22 — qui contiennent
les tests modifiés/ajoutés par ce ticket).

## Conclusion

Diff minimal, strictement conforme à `spec.md`, sans effet de bord sur le mécanisme d'autorisation
existant. AC1/AC2 vérifiés indépendamment comme déjà couverts par du code mergé (#45, #40). AC3
vérifié comme garanti par la façon dont `resolve`/`delete` traitent la clé stockée, sans migration
nécessaire. Tests nouveaux/adaptés non tautologiques. Build et suite de tests complète au vert.

APPROVE.
