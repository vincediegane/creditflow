# Review — RBAC comptes vendeur/caissier (#1)

APPROVE

## Résumé

Le diff (`git diff master...HEAD -- backend frontend`, 28 fichiers) suit `spec.md` presque au mot près : CRUD `/api/users` réservé `ADMIN`, gardes `@PreAuthorize("hasRole('ADMIN')")` sur `CustomerController.delete`, `ProductController.create/update/delete`, `SaleController.cancel/delete`, `PaymentController.delete`, garde anti auto-désactivation dans `UserService.setEnabled`, écran `UsersPage`, garde de route `RequireRole`, masquage des actions ADMIN-only dans `CustomersPage`/`ProductsPage`/`SaleDetailPage`/`PaymentsPage`. Aucun écart non justifié trouvé. `@EnableMethodSecurity` est bien présent sur `SecurityConfig` (sans quoi tous les `@PreAuthorize` seraient silencieusement ignorés) — vérifié explicitement, pas supposé.

## Critères d'acceptation

| Critère | Statut | Test |
|---|---|---|
| Un `ADMIN` peut créer un compte `VENDEUR` avec nom, identifiant, mot de passe temporaire à changer à la première connexion. | Couvert | `UserServiceTest.createsSellerWithMustChangePasswordForced` (vérifie `role=SELLER`, `mustChangePassword=true` forcé, mot de passe BCrypt-encodé, jamais stocké en clair) + `UserControllerTest.adminCanCreateUser` (`201`). Retirer le `mustChangePassword(true)` forcé dans `UserService.create` fait échouer le premier test. |
| Un `VENDEUR` connecté ne peut pas supprimer un client ni un produit (403 côté API, action masquée côté UI). | Couvert | `CustomerControllerSecurityTest.sellerCannotDeleteCustomer`, `ProductControllerSecurityTest.sellerCannotDeleteProduct` (+ `sellerCannotCreateProduct`, `sellerCannotUpdateProduct`) — retirer le `@PreAuthorize` correspondant fait passer ces tests de `403` à `204`/`200`, donc les tests sont bien couvrants. Masquage UI vérifié par lecture de code (`CustomersPage.tsx:219`, `ProductsPage.tsx:149,225`) — pas de test automatisé côté frontend, mais le repo n'a aucune infra de test frontend existante (`package.json` ne référence ni vitest ni jest), donc ce n'est pas une régression introduite par ce bolt. |
| Les routes sensibles refusent explicitement un rôle insuffisant (test d'intégration `@WebMvcTest` avec un utilisateur `VENDEUR`). | Couvert | 5 classes `@WebMvcTest` dédiées (`UserControllerTest`, `CustomerControllerSecurityTest`, `ProductControllerSecurityTest`, `SaleControllerSecurityTest`, `PaymentControllerSecurityTest`), chacune avec un cas `roles=SELLER → 403` et un cas `roles=ADMIN → succès` pour prouver que la garde ne bloque pas le bon rôle. `AbstractWebMvcSecurityTest` fonctionne comme prévu par la spec (les 5 sous-classes héritent bien de `@Import`, confirmé à l'exécution). |
| Un `ADMIN` peut désactiver un compte vendeur sans supprimer son historique de ventes/paiements. | Couvert | `UserServiceTest.disablesAnotherUsersAccount` : `enabled=false`, `userRepository.save` appelé, `deleteById` jamais appelé. Vérifié par ailleurs qu'aucune entité `Sale`/`Payment` ne référence `User` (pas de cascade possible). `AppUserDetailsService.loadUserByUsername` positionne `.disabled(!user.isEnabled())`, et `JwtAuthenticationFilter` recharge l'utilisateur à chaque requête (pas de cache) — un compte désactivé perd donc l'accès immédiatement, même avec un JWT déjà émis, sans test dédié mais comportement pré-existant inchangé, vérifié par lecture. |

Point hors AC mais tranché dans la spec (garde anti auto-désactivation) : couvert par `UserServiceTest.rejectsSelfDisable` + désactivation du bouton `UsersPage.tsx:152` (`disabled={isSelf}`), garde serveur réelle dans `UserService.setEnabled` (comparaison `equalsIgnoreCase`, cohérente avec `findByUsernameIgnoreCase`).

## Build/tests

- Backend : `cd backend && mvn -q -B test` → **BUILD SUCCESS**, 79 tests exécutés (incluant les 5 nouvelles classes `@WebMvcTest` et `UserServiceTest`), 0 échec / 0 erreur.
- Frontend : `cd frontend && npm run build` (= `tsc --noEmit && vite build`) → **succès**, aucune erreur TypeScript (donc les nouveaux types `Role`/`UserAccount`/`CreateUserPayload` et l'usage de `user?.role` sont cohérents partout), bundle généré en 13.4s.

## Points relevés (non bloquants)

- `UsersPage.tsx` permet de créer un compte `ADMIN` en plus de `SELLER` (select avec deux options). Ce n'est pas une exigence du ticket, mais ce n'est pas non plus interdit par un AC, et la spec l'a explicitement documenté comme écart à confirmer en revue de produit (section « Écarts identifiés »). Pas de garde manquante : l'endpoint reste `ADMIN`-only de toute façon.
- Aucune infra de test frontend (vitest/jest) n'existe dans ce repo — le masquage UI n'est donc vérifiable qu'à la lecture/manuellement, comme documenté dans le plan de tests de la spec. Pré-existant, pas introduit par ce bolt.

## Verdict

**APPROVE** — code conforme à la spec faisant foi, tous les critères d'acceptation du ticket sont couverts par un test qui échouerait si la garde correspondante était retirée, build backend et frontend verts.
