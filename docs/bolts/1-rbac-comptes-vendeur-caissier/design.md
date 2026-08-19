# Design — RBAC comptes vendeur/caissier (#1)

## Approche

L'essentiel de l'infrastructure existe déjà et n'a pas besoin d'être créé : `Role` (`ADMIN`, `SELLER`), le champ `User.enabled`, `User.mustChangePassword`, le claim `role` dans le JWT, et l'`AccessDeniedException` déjà mappée en 403 par `GlobalExceptionHandler` — `@EnableMethodSecurity` est même déjà actif sur `SecurityConfig`. Le travail se réduit donc à trois axes : (1) un CRUD `/api/users` restreint à `ADMIN` pour créer/lister/désactiver des comptes `SELLER`, (2) des annotations `@PreAuthorize("hasRole('ADMIN')")` sur les endpoints sensibles des contrôleurs existants, (3) un écran frontend de gestion des utilisateurs + masquage des actions interdites selon le rôle. On réutilise `SELLER` (déjà présent dans l'enum) au lieu d'ajouter une valeur `VENDEUR` distincte, pour éviter une migration de données et une divergence de nommage inutile — le prix est que le ticket parle de "VENDEUR" alors que le code dira "SELLER" (à assumer, cohérent avec le reste du code déjà en anglais). Aucune désactivation "cascade" n'est nécessaire : l'historique des ventes/paiements ne référence pas `User`, donc il reste consultable de fait dès qu'on ne supprime jamais la ligne `users` (désactivation = `enabled=false`, jamais de `DELETE`).

## Fichiers/modules impactés

Backend :
- `backend/src/main/java/com/creditflow/auth/dto/UserRequest.java` (nouveau) — création d'un compte (username, password temporaire, fullName, role).
- `backend/src/main/java/com/creditflow/auth/dto/UserStatusRequest.java` (nouveau) — activer/désactiver.
- `backend/src/main/java/com/creditflow/auth/dto/UserResponse.java` (existant, réutilisé tel quel).
- `backend/src/main/java/com/creditflow/auth/service/UserService.java` (nouveau) — `list()`, `create()`, `setEnabled()`. Suit le style de `AuthService`/`CustomerService` (pas de mapper MapStruct dédié, mapping manuel comme dans `AuthService.toResponse`).
- `backend/src/main/java/com/creditflow/auth/web/UserController.java` (nouveau) — `GET/POST /api/users`, `PATCH /api/users/{id}/status`, tout en `@PreAuthorize("hasRole('ADMIN')")` au niveau classe.
- `backend/src/main/java/com/creditflow/auth/repository/UserRepository.java` — ajouter `findAllByOrderByFullNameAsc()` ou équivalent pour lister.
- `backend/src/main/java/com/creditflow/customer/web/CustomerController.java` — `@PreAuthorize("hasRole('ADMIN')")` sur `delete()`.
- `backend/src/main/java/com/creditflow/product/web/ProductController.java` — `@PreAuthorize("hasRole('ADMIN')")` sur `create()`, `update()`, `delete()`.
- `backend/src/main/java/com/creditflow/sale/web/SaleController.java` — `@PreAuthorize("hasRole('ADMIN')")` sur `cancel()` et `delete()`.
- `backend/src/main/java/com/creditflow/payment/web/PaymentController.java` — `@PreAuthorize("hasRole('ADMIN')")` sur `delete()`.
- `backend/src/main/resources/db/migration/V3__seller_role_no_change.sql` — **pas nécessaire** (voir Décisions clés) : à confirmer qu'aucune migration n'est requise, colonne `role` déjà `VARCHAR(30)`.
- Tests : `backend/src/test/java/com/creditflow/auth/web/UserControllerTest.java` (nouveau, `@WebMvcTest`) et un test `@WebMvcTest` par contrôleur sensible impacté (ou un test paramétré regroupant les 4), avec `@WithMockUser(roles = "SELLER")` pour vérifier le 403.

Frontend :
- `frontend/src/types.ts` — ajouter `UserAccount`/`CreateUserPayload` types.
- `frontend/src/api/endpoints.ts` — ajouter `usersApi` (`list`, `create`, `setEnabled`).
- `frontend/src/pages/UsersPage.tsx` (nouveau) — liste + création + activation/désactivation, visible seulement `ADMIN`.
- `frontend/src/App.tsx` — route `utilisateurs`, protégée par rôle (nouveau garde, voir décisions).
- `frontend/src/components/AppLayout.tsx` — entrée de navigation "Utilisateurs" affichée seulement si `user.role === 'ADMIN'`.
- `frontend/src/auth/ProtectedRoute.tsx` ou nouveau `frontend/src/auth/RequireRole.tsx` — garde de route par rôle.
- `frontend/src/pages/CustomersPage.tsx`, `frontend/src/pages/ProductsPage.tsx`, `frontend/src/pages/SalesPage.tsx`/`SaleDetailPage.tsx`, `frontend/src/pages/PaymentsPage.tsx` — masquer les boutons de suppression/annulation/modification produit si `user.role !== 'ADMIN'`.

## Décisions clés

- **Pas de nouvelle valeur d'enum** : réutilisation de `Role.SELLER` existant plutôt que d'ajouter `VENDEUR`. Alternative écartée : ajouter une valeur distincte aurait dupliqué la sémantique sans bénéfice, et le enum est déjà utilisé par `AdminInitializer`/JWT.
- **Pas de migration Flyway** : la colonne `role VARCHAR(30)` accepte déjà toute valeur de l'enum existant ; aucune colonne nouvelle n'est nécessaire pour la création de compte (réutilise `mustChangePassword`, `enabled`, déjà en base depuis V1/V2).
- **Désactivation, jamais suppression physique d'un `User`** : `PATCH /api/users/{id}/status` bascule `enabled`. Pas de `DELETE /api/users/{id}` implémenté — le ticket cite "`POST/PUT/DELETE /api/users` réservés à `ADMIN`" comme énoncé général de sécurité sur le endpoint, pas comme exigence de suppression physique ; supprimer un `User` casserait potentiellement de futurs FK d'audit (#audit-log, prérequis explicitement cité). Un `PUT /api/users/{id}` (édition fullName/role) est ajouté par cohérence REST mais reste optionnel si le spec-writer juge que ce n'est pas couvert par les critères d'acceptation.
- **Portée des `@PreAuthorize`** : le ticket ne liste explicitement que `CustomerController.delete`, `SaleController.cancel`, `PaymentController.delete`, et "`ProductController`" (sans préciser quelle méthode). Décision : restreindre **toutes** les méthodes mutatrices de `ProductController` (`create`, `update`, `delete`) à `ADMIN`, conformément à "pas de modification de produit/prix" dans le périmètre. Décision additionnelle non explicitement listée : `SaleController.delete` (suppression physique d'un contrat) est traitée comme suppression sensible au même titre que `cancel`, donc restreinte à `ADMIN` — à confirmer par le spec-writer si le produit veut l'inverse.
- **Création de vente/paiement reste ouverte à `SELLER`** : `SaleController.create`, `PaymentController.register`, et tous les `GET` restent sans restriction de rôle (accessibles à tout utilisateur authentifié), conformément au périmètre ("création de vente, enregistrement de paiement, consultation").
- **Mot de passe temporaire** : réutilisation du mécanisme `mustChangePassword` déjà câblé (`AdminInitializer` → `ChangePasswordDialog` côté front). `UserService.create()` force `mustChangePassword=true` systématiquement, pas de champ optionnel.
- **Garde de route frontend par rôle** : ajout d'un composant dédié (`RequireRole`) plutôt que d'étendre `ProtectedRoute` pour ne pas complexifier le cas simple existant (authentification) — décision de composition, pas de réécriture.

## Risques / points d'attention

- **Auto-verrouillage** : rien n'empêche aujourd'hui un `ADMIN` de se désactiver lui-même via `PATCH /api/users/{id}/status`, ce qui bloquerait la boutique si c'est le seul admin. Le ticket ne l'exige pas explicitement mais c'est un risque réel à couvrir (garde `if (targetId == principal.id) reject`).
- **`@EnableMethodSecurity` déjà actif** : bon point, mais aucun test `@WebMvcTest` n'existe dans le projet aujourd'hui (`backend/src/test` n'a que des tests de service). Le spec-writer doit prévoir la configuration `@WebMvcTest` + `spring-security-test` (déjà en dépendance Maven, `backend/pom.xml`) + mock des services, from scratch.
- **`AppUserDetailsService`/`JwtAuthenticationFilter`** gèrent déjà `enabled=false` → connexion refusée et token existant ignoré (`disabled(!user.isEnabled())` + vérif `userDetails.isEnabled()` dans le filtre) : pas de nouveau code de sécurité à écrire ici, seulement à vérifier par test que le comportement tient pour un compte désactivé après coup (token émis avant désactivation).
- **Masquage UI seul insuffisant** : les critères d'acceptation exigent le 403 côté API en plus du masquage — bien vérifier que le masquage frontend ne remplace jamais l'annotation backend (défense en profondeur déjà couverte par le plan ci-dessus, mais à surveiller en revue).
- **Frontend sans état de rôle centralisé** : `AuthContext`/`User` (`frontend/src/types.ts`) expose déjà `role: string` (non typé en union) — introduire un type `Role = 'ADMIN' | 'SELLER'` reste optionnel mais réduit le risque de faute de frappe dans les comparaisons de rôle disséminées dans plusieurs pages.
- **Pas de lien `Sale`/`Payment` → `User`** : confirmé par exploration (`grep` sur `createdBy`/`userId` dans `sale`/`payment` : aucun résultat). Le critère "historique reste consultable" est donc satisfait par construction (rien n'est supprimé), mais il n'y a aujourd'hui aucune traçabilité "qui a fait quoi" — c'est exactement la dépendance vers #audit-log mentionnée par le ticket, pas ce ticket-ci.

## Hors périmètre

- Statistiques par vendeur (mentionnées comme dépendance future, pas comme exigence ici).
- Traçabilité/audit-log des actions (`#audit-log`, ticket séparé et prérequis en aval, pas en amont).
- Ajout d'un champ `created_by`/`seller_id` sur `Sale`/`Payment`/`Customer`.
- Rôles supplémentaires au-delà de `ADMIN`/`SELLER` (pas de rôle "caissier" distinct du vendeur — le ticket les traite comme équivalents fonctionnels).
- Réinitialisation de mot de passe libre-service ("mot de passe oublié") — hors périmètre, l'admin recrée/réactive au besoin.
- Suppression physique d'un compte utilisateur.
- Toute modification du flux de login/JWT existant (déjà conforme aux besoins du ticket).
