# Review — Ticket #2 : Journal d'audit — qui a fait quoi, quand

## Verdict

APPROVE

## Critères d'acceptation

| Critère | Statut | Détail |
|---|---|---|
| Chaque paiement enregistré porte l'identifiant du vendeur qui l'a saisi, visible sur le reçu interne et la fiche contrat. | Couvert | `Payment.createdBy` renseigné null-safe via `CurrentUser.username()` dans `onCreate()` (`PaymentTest`). Exposé dans `PaymentResponse` (mapping MapStruct auto-résolu, vérifié dans le code généré `PaymentMapperImpl`). Affiché en colonne « Enregistré par » sur `SaleDetailPage` (fiche contrat) et `PaymentsPage`. Interprétation assumée et documentée dans `spec.md` (Écart #4) : le PDF client `PaymentReceiptGenerator` n'est volontairement pas modifié (confirmé non touché par le diff) ; « reçu interne » = vues internes de l'appli. Interprétation raisonnable, à confirmer avec le demandeur si un besoin explicite de PDF interne existe, mais rien ne le suggère dans le ticket. |
| La suppression d'un client, l'annulation d'un contrat et la modification d'un prix produit sont historisées avec auteur + date. | Couvert | `CustomerService.delete`, `CreditSaleService.cancel/delete`, `ProductService.update` (uniquement si prix change réellement, via `compareTo`) appellent `AuditLogService.record(...)`, avec tests unitaires ciblés (`CustomerServiceTest`, `CreditSaleServiceTest`, `ProductServiceTest`) qui échoueraient si l'appel était retiré. `GET /api/audit-log` permet de consulter auteur + date. |
| La désactivation d'un compte utilisateur ne fait disparaître aucune entrée d'historique existante. | Couvert | Garantie structurelle : `V3__audit_columns.sql` n'introduit aucune FK vers `users.id` (colonnes `VARCHAR(80)` pures sur `customers`, `products`, `credit_sales`, `payments`, `audit_log.actor`), donc aucune suppression en cascade possible. `UserServiceTest#disablesAnotherUsersAccount` (ticket #1, toujours vert) confirme qu'aucun `deleteById` n'est déclenché à la désactivation. |

## Findings

Aucun finding bloquant. Deux remarques mineures, non bloquantes :

1. **Pas de test explicite « non authentifié → 401 » sur `GET /api/audit-log`** (`backend/src/test/java/com/creditflow/audit/web/AuditLogControllerSecurityTest.java`). Le comportement est garanti structurellement par `SecurityConfig#filterChain` (`anyRequest().authenticated()`, pas de `@PreAuthorize` sur le contrôleur — vérifié), mais aucun test ne l'exerce directement. Ceci dit, c'est cohérent avec le reste du projet : aucun `*ControllerSecurityTest` existant (Payment, Product, Sale, Customer) ne teste ce cas non plus — ce n'est pas une régression introduite par ce bolt, juste un gap préexistant du projet.
2. **`CustomerDetailPage` — carte « Historique »** n'affichera en pratique jamais rien tant que le client existe (le seul événement journalisé pour `CUSTOMER` est `DELETE`, qui rend la fiche inaccessible juste après). Comportement documenté explicitement par le spec-writer (spec.md, Écart #2), assumé comme hors périmètre de ce ticket — pas un bug d'implémentation.

## Vérifications ciblées (demandées par l'orchestrateur)

- **`GlobalExceptionHandler` — nouveau `@ExceptionHandler(MissingServletRequestParameterException.class)`** : changement légitime et correctement ciblé. Avant ce fix, n'importe quel `@RequestParam` obligatoire manquant sur n'importe quel contrôleur du projet tombait dans le handler générique `Exception.class` → 500 (vérifié : c'était le seul handler capable d'intercepter cette exception avant le changement, aucun `MissingServletRequestParameterException` handler n'existait dans `master`). Le nouveau handler est un cas standard Spring, ajouté dans la classe `@RestControllerAdvice` déjà globale (donc s'applique naturellement à tout le projet, pas seulement à `/api/audit-log` — c'est le comportement attendu d'un handler global, pas un élargissement de périmètre). Testé indirectement par `AuditLogControllerSecurityTest#missingParameterReturnsBadRequest` (200/400 confirmés en exécutant les tests). Le changement est minimal (7 lignes), n'altère aucun autre handler, et la justification du codeur tient : c'est bien un bug préexistant généralisé, pas une extension de scope.
- **`Auditable.onCreate()/onUpdate()` et `Payment.onCreate()` null-safe** : confirmé. Les deux utilisent `CurrentUser.username()` (`backend/src/main/java/com/creditflow/common/security/CurrentUser.java`), qui retourne `null` si `authentication == null || !authentication.isAuthenticated()`, sans jamais lever d'exception. Testé explicitement avec un `SecurityContextHolder` mocké sans authentification dans `AuditableTest#setsNullWhenUnauthenticated` et `PaymentTest#setsNullWhenUnauthenticated`.
- **`ProductService.update()` ne journalise que si un prix change réellement** : confirmé, comparaison par `compareTo` (pas `equals`) dans `recordPriceChange()`, capture des anciennes valeurs avant mutation. Testé dans les deux sens (`ProductServiceTest#recordsAuditEntryWhenPriceChanges` / `#doesNotRecordAuditEntryWhenPricesUnchanged`).
- **Écriture `audit_log` avant suppression physique, même transaction** : confirmé sur les trois points d'écriture DELETE/CANCEL/PAYMENT_DELETE (`CustomerService.delete`, `CreditSaleService.delete`, `PaymentService.delete`) — l'appel à `auditLogService.record(...)` précède toujours `repository.delete(...)`, dans une méthode déjà `@Transactional`. L'ordre est explicitement vérifié par `PaymentServiceTest#deletingPaymentRecordsAuditEntryOnSale` via `InOrder`.
- **`GET /api/audit-log` accessible à tout rôle authentifié** : confirmé, aucun `@PreAuthorize` sur `AuditLogController`, protégé uniquement par `anyRequest().authenticated()` dans `SecurityConfig`. Testé pour `SELLER` et `ADMIN` (200). Rejet des requêtes non authentifiées non testé explicitement mais garanti structurellement (voir Findings #1).
- **Désactivation de compte / FK-less design** : confirmé, `V3__audit_columns.sql` ne définit aucune contrainte FK, uniquement des `VARCHAR(80)` nullable. Aucune cascade de suppression possible.
- **Cohérence des tests avec le comportement réel** : les tests lus (`AuditableTest`, `PaymentTest`, `AuditLogServiceTest`, `AuditLogControllerSecurityTest`, `CustomerServiceTest`, `CreditSaleServiceTest`, `ProductServiceTest`, `PaymentServiceTest`) vérifient des comportements réels via mocks/captors/InOrder et échoueraient si le code métier associé était retiré — pas de test qui passe pour de mauvaises raisons.

## Build/tests

- `cd backend && mvn -o test` → **BUILD SUCCESS**, 97 tests, 0 échec, 0 erreur (confirmé via `target/surefire-reports/*.txt`).
- `cd frontend && npm run build` (= `tsc --noEmit && vite build`) → **succès**, compilation TypeScript et bundle Vite générés sans erreur (avertissement non bloquant sur la taille du chunk principal, préexistant, hors périmètre de ce ticket).

## Conclusion

Implémentation fidèle à `spec.md`, cohérente avec `design.md` (y compris les écarts documentés et justifiés, en particulier l'inclusion de `PaymentService.delete()` dans l'audit log malgré son absence des critères d'acceptation littéraux). Aucun bug bloquant trouvé sur les points sensibles vérifiés (null-safety, transactionnalité, RBAC, FK-less design, comparaison de prix). Build et tests backend/frontend passent.
