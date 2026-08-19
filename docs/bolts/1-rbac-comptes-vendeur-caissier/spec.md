# Spec — RBAC comptes vendeur/caissier (#1)

## Résumé

Ajout d'un CRUD `/api/users` restreint à `ADMIN` (créer, lister, activer/désactiver un compte `SELLER`), de gardes `@PreAuthorize("hasRole('ADMIN')")` sur les endpoints sensibles de `CustomerController`, `ProductController`, `SaleController`, `PaymentController`, et d'un écran frontend "Utilisateurs" (+ masquage des actions interdites) visible seulement par `ADMIN`.

## Tranchés (points laissés ouverts par le design)

1. **Pas de `PUT /api/users/{id}`** : hors périmètre de ce bolt. Les critères d'acceptation du ticket ne demandent que la création et la désactivation d'un compte — pas l'édition de `fullName`/`role`. On n'implémente ni endpoint ni UI d'édition. Si un besoin d'édition apparaît, il fera l'objet d'un ticket séparé.
2. **`SaleController.delete()` est `ADMIN`-only**, au même titre que `cancel()`. La suppression physique d'un contrat est au moins aussi sensible qu'une annulation ; le périmètre du ticket ("je garde le contrôle des opérations sensibles... annulation de contrat") couvre implicitement la suppression. Décision confirmée, pas de garde différenciée entre `cancel` et `delete`.
3. **Le garde anti auto-désactivation est dans le périmètre de ce bolt.** Bien que non listé dans les critères d'acceptation, un `ADMIN` unique qui se désactive lui-même bloquerait totalement l'accès à la boutique (aucun autre compte ne pourrait le réactiver) — c'est un risque de disponibilité disproportionné pour une règle d'une ligne. `UserService.setEnabled()` doit rejeter la désactivation de son propre compte avec une erreur métier explicite (422, pas 403 : ce n'est pas un problème de droits mais une règle de cohérence). Si le produit préfère l'écarter, le retirer explicitement de `UserServiceTest` et documenter pourquoi dans la PR — ne pas le laisser en silence.

## Tâches

### Backend — comptes utilisateurs

- [ ] `backend/src/main/java/com/creditflow/auth/dto/UserResponse.java` — ajouter le champ `boolean enabled` au record (nécessaire pour que l'écran Utilisateurs affiche le statut actif/désactivé ; changement additif, ne casse pas `/api/auth/me` ni `/api/auth/login`).
- [ ] `backend/src/main/java/com/creditflow/auth/service/AuthService.java` — mettre à jour `toResponse(User)` pour passer `user.isEnabled()` au constructeur de `UserResponse`.
- [ ] `backend/src/main/java/com/creditflow/auth/dto/UserRequest.java` (nouveau) — DTO de création : `username` (`@NotBlank`, `@Size(max = 80)`), `password` (`@NotBlank`, `@Size(min = 8, max = 72)`, message "Le mot de passe doit contenir au moins 8 caracteres" — même contrainte que `ChangePasswordRequest`), `fullName` (`@NotBlank`, `@Size(max = 150)`), `role` (`@NotNull`, type `Role`).
- [ ] `backend/src/main/java/com/creditflow/auth/dto/UserStatusRequest.java` (nouveau) — DTO d'activation/désactivation : `enabled` (`@NotNull Boolean`).
- [ ] `backend/src/main/java/com/creditflow/auth/repository/UserRepository.java` — ajouter `List<User> findAllByOrderByFullNameAsc()`.
- [ ] `backend/src/main/java/com/creditflow/auth/service/UserService.java` (nouveau) — `@Service @RequiredArgsConstructor`, dépend de `UserRepository` et `PasswordEncoder` (mapping manuel, pas de MapStruct, comme `AuthService`) :
  - `List<UserResponse> list()` → `userRepository.findAllByOrderByFullNameAsc()` mappé.
  - `UserResponse create(UserRequest request)` → si `userRepository.existsByUsernameIgnoreCase(request.username())` lève `BusinessRuleException("Ce nom d'utilisateur est deja utilise")` ; sinon construit un `User` avec `password = passwordEncoder.encode(...)`, `enabled = true`, `mustChangePassword = true` (toujours forcé, pas de champ optionnel), sauvegarde, retourne la réponse.
  - `UserResponse setEnabled(Long id, boolean enabled, String currentUsername)` → charge la cible via `userRepository.findById(id)` (`ResourceNotFoundException` sinon) ; si `!enabled` et que la cible correspond au principal courant (comparer par `username` avec `currentUsername`, insensible à la casse — cohérent avec `findByUsernameIgnoreCase`), lève `BusinessRuleException("Vous ne pouvez pas desactiver votre propre compte")` ; sinon `user.setEnabled(enabled)`, sauvegarde, retourne la réponse.
  - `toResponse(User)` privé statique, même forme que `AuthService.toResponse` mais avec `enabled`.
- [ ] `backend/src/main/java/com/creditflow/auth/web/UserController.java` (nouveau) — `@RestController @RequestMapping("/api/users") @RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")` au niveau classe :
  - `GET /api/users` → `List<UserResponse> list()`.
  - `POST /api/users` → `ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request, UriComponentsBuilder uriBuilder)`, retourne `201 Created` avec `Location: /api/users/{id}` (même pattern que `CustomerController.create`).
  - `PATCH /api/users/{id}/status` → `UserResponse setEnabled(@PathVariable Long id, @Valid @RequestBody UserStatusRequest request, @AuthenticationPrincipal UserDetails principal)`, délègue à `userService.setEnabled(id, request.enabled(), principal.getUsername())`.

### Backend — gardes d'autorisation sur les contrôleurs existants

- [ ] `backend/src/main/java/com/creditflow/customer/web/CustomerController.java` — ajouter `@PreAuthorize("hasRole('ADMIN')")` sur `delete(Long id)`.
- [ ] `backend/src/main/java/com/creditflow/product/web/ProductController.java` — ajouter `@PreAuthorize("hasRole('ADMIN')")` sur `create(...)`, `update(...)` et `delete(Long id)` (les `GET`/`select`/`categories` restent accessibles à tout utilisateur authentifié).
- [ ] `backend/src/main/java/com/creditflow/sale/web/SaleController.java` — ajouter `@PreAuthorize("hasRole('ADMIN')")` sur `cancel(Long id)` et `delete(Long id)` (voir décision tranchée n°2 ; `create` et tous les `GET` restent ouverts).
- [ ] `backend/src/main/java/com/creditflow/payment/web/PaymentController.java` — ajouter `@PreAuthorize("hasRole('ADMIN')")` sur `delete(Long id)` (`register` et les `GET`/`receipt` restent ouverts).

### Backend — tests

- [ ] `backend/src/test/java/com/creditflow/auth/service/UserServiceTest.java` (nouveau, `@ExtendWith(MockitoExtension.class)`, style `CustomerServiceTest`) — couvre : création réussie (`mustChangePassword=true` forcé, mot de passe encodé), refus si `username` déjà utilisé (`BusinessRuleException`), refus de désactivation de son propre compte (`BusinessRuleException`), désactivation réussie d'un autre compte, `ResourceNotFoundException` si l'id cible n'existe pas.
- [ ] `backend/src/test/java/com/creditflow/config/AbstractWebMvcSecurityTest.java` (nouveau, `abstract class`) — support partagé pour les tests `@WebMvcTest` ci-dessous. Nécessaire car aucun test `@WebMvcTest` n'existe encore dans le projet et `SecurityConfig` a des dépendances (`JwtAuthenticationFilter`, `AppProperties`, `ObjectMapper`) qui ne sont pas résolues automatiquement dans une tranche `@WebMvcTest`. Recette concrète (à adapter si un détail Spring diffère à l'exécution, mais partir de cette base) :

  ```java
  @Import({ SecurityConfig.class, JwtAuthenticationFilter.class, AbstractWebMvcSecurityTest.TestSecurityBeans.class })
  public abstract class AbstractWebMvcSecurityTest {

      @MockBean protected JwtService jwtService;
      @MockBean protected AppUserDetailsService appUserDetailsService;

      @TestConfiguration
      static class TestSecurityBeans {
          @Bean
          AppProperties appProperties() {
              AppProperties properties = new AppProperties();
              properties.getCors().setAllowedOrigins(List.of("http://localhost:5173"));
              return properties;
          }
      }
  }
  ```

  Avec `@WithMockUser` et aucune en-tête `Authorization` dans la requête de test, `JwtAuthenticationFilter.doFilterInternal` sort au tout début (`header == null`) sans jamais toucher `jwtService`/`appUserDetailsService` — les mocks n'ont donc pas besoin d'être configurés, ils servent seulement à satisfaire l'injection de dépendances du filtre. Si `@Import` sur la classe abstraite n'est pas hérité correctement par une sous-classe (comportement Spring à vérifier à l'exécution), répéter le même `@Import` directement sur chaque classe de test concrète plutôt que bloquer dessus.
- [ ] `backend/src/test/java/com/creditflow/auth/web/UserControllerTest.java` (nouveau, `@WebMvcTest(UserController.class)` + `extends AbstractWebMvcSecurityTest`, `@MockBean UserService`) — `@WithMockUser(roles = "SELLER")` sur `GET/POST /api/users` et `PATCH /api/users/1/status` → `403`. `@WithMockUser(roles = "ADMIN")` sur les mêmes routes avec `userService` mocké → statut de succès attendu (`200`/`201`).
- [ ] `backend/src/test/java/com/creditflow/customer/web/CustomerControllerSecurityTest.java` (nouveau, `@WebMvcTest(CustomerController.class)` + `extends AbstractWebMvcSecurityTest`, `@MockBean CustomerService`, `@MockBean CustomerProfileService`) — `@WithMockUser(roles = "SELLER")` sur `DELETE /api/customers/1` → `403`. `@WithMockUser(roles = "ADMIN")` sur la même route → `204`.
- [ ] `backend/src/test/java/com/creditflow/product/web/ProductControllerSecurityTest.java` (nouveau, `@WebMvcTest(ProductController.class)` + `extends AbstractWebMvcSecurityTest`, `@MockBean ProductService`) — `@WithMockUser(roles = "SELLER")` sur `POST /api/products`, `PUT /api/products/1`, `DELETE /api/products/1` → `403` chacun. `@WithMockUser(roles = "ADMIN")` sur les mêmes routes → succès.
- [ ] `backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java` (nouveau, `@WebMvcTest(SaleController.class)` + `extends AbstractWebMvcSecurityTest`, `@MockBean CreditSaleService`, `@MockBean InstallmentService`, `@MockBean PaymentService`) — `@WithMockUser(roles = "SELLER")` sur `POST /api/sales/1/cancel` et `DELETE /api/sales/1` → `403` chacun. `@WithMockUser(roles = "SELLER")` sur `POST /api/sales` (création) → succès (pas restreint). `@WithMockUser(roles = "ADMIN")` sur `cancel`/`delete` → succès.
- [ ] `backend/src/test/java/com/creditflow/payment/web/PaymentControllerSecurityTest.java` (nouveau, `@WebMvcTest(PaymentController.class)` + `extends AbstractWebMvcSecurityTest`, `@MockBean PaymentService`) — `@WithMockUser(roles = "SELLER")` sur `DELETE /api/payments/1` → `403`. `@WithMockUser(roles = "SELLER")` sur `POST /api/payments` (enregistrement) → succès (pas restreint). `@WithMockUser(roles = "ADMIN")` sur `DELETE /api/payments/1` → `204`.

### Frontend — types et API

- [ ] `frontend/src/types.ts` — ajouter `export type Role = 'ADMIN' | 'SELLER';`, changer `role: string` en `role: Role` dans `interface User`. Ajouter :
  ```ts
  export interface UserAccount {
    id: number;
    username: string;
    fullName: string;
    role: Role;
    enabled: boolean;
    mustChangePassword: boolean;
  }
  export interface CreateUserPayload {
    username: string;
    password: string;
    fullName: string;
    role: Role;
  }
  ```
- [ ] `frontend/src/api/endpoints.ts` — ajouter :
  ```ts
  export const usersApi = {
    list: () => api.get<UserAccount[]>('/users').then((r) => r.data),
    create: (payload: CreateUserPayload) =>
      api.post<UserAccount>('/users', payload).then((r) => r.data),
    setEnabled: (id: number, enabled: boolean) =>
      api.patch<UserAccount>(`/users/${id}/status`, { enabled }).then((r) => r.data),
  };
  ```

### Frontend — garde de route et navigation

- [ ] `frontend/src/auth/RequireRole.tsx` (nouveau) — composant de garde par rôle, calqué sur `ProtectedRoute.tsx` (Outlet + Navigate) :
  ```tsx
  import { Navigate, Outlet } from 'react-router-dom';
  import { useAuth } from './AuthContext';
  import type { Role } from '../types';

  export default function RequireRole({ role }: { role: Role }) {
    const { user } = useAuth();
    if (user?.role !== role) {
      return <Navigate to="/" replace />;
    }
    return <Outlet />;
  }
  ```
- [ ] `frontend/src/App.tsx` — importer `UsersPage` et `RequireRole`, ajouter la route protégée :
  ```tsx
  <Route element={<RequireRole role="ADMIN" />}>
    <Route path="utilisateurs" element={<UsersPage />} />
  </Route>
  ```
  imbriquée sous le `<Route element={<AppLayout />}>` existant, au même niveau que les autres routes métier.
- [ ] `frontend/src/components/AppLayout.tsx` — ajouter une entrée `{ to: '/utilisateurs', label: 'Utilisateurs', icon: <PersonIcon /> (ou icône équivalente déjà importée type `GroupIcon`) }` dans `NAV_ITEMS`, affichée seulement si `user?.role === 'ADMIN'` (filtrer le tableau `NAV_ITEMS` rendu, pas de nouvel état).

### Frontend — écran de gestion des utilisateurs

- [ ] `frontend/src/pages/UsersPage.tsx` (nouveau) — calqué sur la structure de `CustomersPage.tsx`/`ProductsPage.tsx` (React Query + MUI Table + Dialog) :
  - Liste (`useQuery(['users'], usersApi.list)`) : colonnes Nom complet, Identifiant, Rôle, Statut (`StatusChip`-like ou `Chip` "Actif"/"Désactivé"), Actions.
  - Bouton "Nouveau compte" ouvrant un `Dialog` avec formulaire (`react-hook-form`) : `fullName`, `username`, `password` (temporaire, `type="password"`, aide "min. 8 caractères"), `role` (`select` ADMIN/SELLER, défaut SELLER). Soumission via `useMutation(usersApi.create)`, invalide `['users']`, affiche l'erreur serveur via `errorMessage()` (ex. nom d'utilisateur déjà pris → `409`/`422` selon la contrainte déclenchée).
  - Par ligne : bouton "Activer"/"Désactiver" (`useMutation(({id, enabled}) => usersApi.setEnabled(id, enabled))`), avec `ConfirmDialog` pour la désactivation (pas nécessaire pour la réactivation). Si le compte ciblé est celui de l'utilisateur connecté (`row.id === currentUser.id`) et est actif, désactiver visuellement le bouton "Désactiver" (tooltip "Vous ne pouvez pas désactiver votre propre compte") en plus de la garde serveur — défense en profondeur cohérente avec le reste du plan.

### Frontend — masquage des actions interdites (défense en profondeur, le 403 API reste la garde réelle)

- [ ] `frontend/src/pages/CustomersPage.tsx` — le bouton `IconButton` "Supprimer" (icône `DeleteIcon`, ~ligne 217-221) n'est rendu que si `user?.role === 'ADMIN'` (import `useAuth`).
- [ ] `frontend/src/pages/ProductsPage.tsx` — le bouton d'action "Nouveau produit" du `PageHeader` (~ligne 146-150), le `IconButton` "Modifier" et le `IconButton` "Supprimer" (~ligne 220-230) ne sont rendus que si `user?.role === 'ADMIN'` (import `useAuth`) — cohérent avec la restriction backend sur `create`/`update`/`delete`.
- [ ] `frontend/src/pages/SaleDetailPage.tsx` — le bouton "Annuler le contrat" (~ligne 167-176) et le `IconButton` "Annuler ce versement" dans le tableau des paiements (~ligne 256-264) ne sont rendus que si `user?.role === 'ADMIN'` (import `useAuth`). Pas de bouton de suppression physique de contrat à masquer : `salesApi.remove`/`DELETE /api/sales/{id}` n'est déjà câblé sur aucun bouton de l'UI actuelle.
- [ ] `frontend/src/pages/PaymentsPage.tsx` — le `IconButton` "Annuler ce versement" (~ligne 203) n'est rendu que si `user?.role === 'ADMIN'` (import `useAuth`).

## Contrat technique

### `POST /api/users` — `ADMIN` uniquement

Requête :
```json
{ "username": "fatou.diop", "password": "TempPass2026!", "fullName": "Fatou Diop", "role": "SELLER" }
```
Réponse `201 Created`, `Location: /api/users/{id}` :
```json
{ "id": 4, "username": "fatou.diop", "fullName": "Fatou Diop", "role": "SELLER", "mustChangePassword": true }
```
Erreurs : `400` (validation), `409` (username déjà utilisé, via la contrainte unique + `DataIntegrityViolationException`, ou `422` si intercepté en amont par `UserService.create` via `BusinessRuleException` — choisir `BusinessRuleException`/`422` pour un message explicite plutôt que de laisser remonter la contrainte SQL), `403` (rôle insuffisant).

### `GET /api/users` — `ADMIN` uniquement

Réponse `200` : tableau de `UserResponse` (avec le nouveau champ `enabled`), trié par `fullName` ascendant.

### `PATCH /api/users/{id}/status` — `ADMIN` uniquement

Requête :
```json
{ "enabled": false }
```
Réponse `200` : `UserResponse` mis à jour. Erreurs : `404` (id inconnu), `422` (auto-désactivation refusée), `403` (rôle insuffisant).

### `UserResponse` (mis à jour)

```java
public record UserResponse(
    Long id, String username, String fullName, String role,
    boolean mustChangePassword,
    boolean enabled   // nouveau champ
) { }
```
Champ additif en fin de record — ne casse pas les consommateurs existants (`/api/auth/login`, `/api/auth/me`, `/api/auth/password`) tant que le frontend désérialise en objet (ce qui est le cas, `interface User` en TypeScript).

### Annotations `@PreAuthorize`

Toutes au format `@PreAuthorize("hasRole('ADMIN')")`, sur la méthode (pas au niveau classe pour les contrôleurs existants, puisque seules certaines méthodes sont restreintes) :
- `CustomerController.delete`
- `ProductController.create`, `ProductController.update`, `ProductController.delete`
- `SaleController.cancel`, `SaleController.delete`
- `PaymentController.delete`

`UserController` : annotation au niveau classe (toutes les méthodes sont `ADMIN`-only).

## Plan de tests

| Critère d'acceptation du ticket | Test |
|---|---|
| Un `ADMIN` peut créer un compte `VENDEUR` avec nom, identifiant, mot de passe temporaire à changer à la première connexion. | `UserServiceTest.createsSellerWithMustChangePasswordForced` (unitaire) : vérifie `role=SELLER`, `mustChangePassword=true`, mot de passe encodé (pas stocké en clair). `UserControllerTest` (`@WebMvcTest`, `roles=ADMIN`) : `POST /api/users` retourne `201`. Manuel : créer un compte vendeur via l'écran Utilisateurs, se déconnecter, se connecter avec ce compte, vérifier que `ChangePasswordDialog` s'ouvre en mode forcé (réutilise le mécanisme existant, déjà testé côté `mustChangePassword`). |
| Un `VENDEUR` connecté ne peut pas supprimer un client ni un produit (403 côté API, action masquée côté UI). | `CustomerControllerSecurityTest.sellerCannotDeleteCustomer` et `ProductControllerSecurityTest.sellerCannotDeleteOrMutateProduct` (`@WebMvcTest`, `roles=SELLER` → `403`). Manuel/visuel : se connecter en `SELLER`, vérifier l'absence des boutons Supprimer sur `/clients` et `/produits` (et Nouveau/Modifier sur `/produits`). |
| Les routes sensibles refusent explicitement un rôle insuffisant (test d'intégration `@WebMvcTest` avec un utilisateur `VENDEUR`). | `CustomerControllerSecurityTest`, `ProductControllerSecurityTest`, `SaleControllerSecurityTest`, `PaymentControllerSecurityTest`, `UserControllerTest` — chacun avec `@WithMockUser(roles = "SELLER")` sur la route restreinte correspondante → `403`, et un cas `roles = "ADMIN"` positif pour prouver que la garde ne bloque pas le rôle attendu. |
| Un `ADMIN` peut désactiver un compte vendeur sans supprimer son historique de ventes/paiements. | `UserServiceTest.disablesAnotherUsersAccount` (unitaire) : `enabled` passe à `false`, `userRepository.save` appelé, jamais de `deleteById`. Manuel : désactiver un compte vendeur via l'écran Utilisateurs, vérifier que ses ventes/paiements passés restent visibles dans `/ventes` et `/paiements` (aucun lien `Sale`/`Payment` → `User` n'existe, donc rien ne peut être supprimé en cascade — vérification par inspection, pas par test automatisé puisqu'il n'y a rien à câbler). Vérifier aussi que ce compte désactivé ne peut plus se connecter (`AppUserDetailsService`/`JwtAuthenticationFilter` déjà couverts par le comportement Spring Security existant — pas de nouveau test requis, comportement inchangé). |
| (Hors AC explicite, ajouté par cette spec) Un `ADMIN` ne peut pas se désactiver lui-même. | `UserServiceTest.rejectsSelfDisable` (unitaire) : `setEnabled(ownId, false, ownUsername)` lève `BusinessRuleException`. Manuel : dans l'écran Utilisateurs, vérifier que le bouton "Désactiver" de son propre compte est visuellement désactivé. |

## Écarts identifiés

- **`UserResponse` ne portait pas de champ `enabled`** alors que le design le "réutilise tel quel" — insuffisant pour que l'écran Utilisateurs affiche qui est actif/désactivé (nécessaire pour l'ergonomie de l'AC "Un `ADMIN` peut désactiver un compte vendeur"). Tranché : ajout du champ, additif et sans risque de régression sur les endpoints `/api/auth/*` qui réutilisent le même DTO.
- **Portée du rôle sélectionnable à la création** : le ticket ne parle que de créer des comptes `VENDEUR`, mais le formulaire propose aussi `ADMIN` (utile pour qu'un gérant délègue à un second gérant plus tard). Ce n'est pas une exigence du ticket mais ne contredit aucun critère d'acceptation ; à confirmer en revue si le produit préfère restreindre la création au rôle `SELLER` uniquement dans un premier temps.
- Les trois points explicitement laissés ouverts par le design (PUT, `SaleController.delete`, garde anti auto-désactivation) sont tranchés en tête de ce document plutôt que redondés ici.
