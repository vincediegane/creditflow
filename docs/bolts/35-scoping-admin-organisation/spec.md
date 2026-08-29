# Spec -- #35 Multi-tenant 2/10 -- Scoping ADMIN par organisation (CurrentShopContext)

## Résumé

Filtrer par organisation la branche "ADMIN sans boutique assignée" de `CurrentShopContext.accessibleShopsOf` via une nouvelle méthode `ShopRepository`, avec non-régression mono-tenant et couverture de tests multi-organisations explicitement démontrées.

## Tâches

- [ ] `backend/src/main/java/com/creditflow/shop/repository/ShopRepository.java` -- ajouter la méthode dérivée `List<Shop> findAllByActiveTrueAndOrganizationIdOrderByNameAsc(Long organizationId);`. Ne pas toucher à `findAllByActiveTrueOrderByNameAsc()` : elle reste utilisée par `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java:80` (voir Écarts identifiés) et par ses tests -- aucune suppression, aucune dépréciation dans ce ticket.
- [ ] `backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java` (lignes 59-60) -- remplacer `shopRepository.findAllByActiveTrueOrderByNameAsc()` par `shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(user.getOrganization().getId())`. Ne rien changer d'autre dans `accessibleShopsOf`, `accessibleShopIds`, `resolveReadFilter`, `shopIdForCreation` (cf. décision ci-dessous : comportement en aval accepté tel quel).
- [ ] `backend/src/test/java/com/creditflow/common/security/CurrentShopContextTest.java` -- adapter `accessibleShopIdsForAdminWithoutAssignment` : donner une `Organization` au `User` construit et stubber la nouvelle méthode au lieu de `findAllByActiveTrueOrderByNameAsc()`. Ce test, avec une seule organisation et deux boutiques, devient le test de non-régression mono-tenant (AC2).
- [ ] `backend/src/test/java/com/creditflow/common/security/CurrentShopContextTest.java` -- ajouter un test d'isolation stricte multi-organisations : deux organisations distinctes ayant chacune des boutiques actives en base, un ADMIN sans assignation de l'organisation A ne doit récupérer que les boutiques de l'organisation A et la méthode repository ne doit jamais être invoquée avec l'`organizationId` de l'organisation B (AC1, AC3).
- [ ] `backend/src/test/java/com/creditflow/common/security/CurrentShopContextTest.java` -- ajouter un test pour le cas ADMIN sans assignation dans une organisation qui n'a elle-même aucune boutique active : `accessibleShopIds()` et `resolveReadFilter()` retournent une liste vide, sans exception (AC3).
- [ ] `backend/src/test/java/com/creditflow/common/security/CurrentShopContextTest.java` -- ajouter un test verrouillant le comportement de `shopIdForCreation()` pour ce même cas (0 boutique accessible) : lève toujours `BusinessRuleException`, message inchangé (documente explicitement la décision ci-dessous, évite toute régression silencieuse si quelqu'un modifie ce message plus tard sans s'en rendre compte).

## Contrat technique

### Nouvelle méthode `ShopRepository`

```java
List<Shop> findAllByActiveTrueAndOrganizationIdOrderByNameAsc(Long organizationId);
```

- Méthode dérivée Spring Data, pas de `@Query`, conforme aux conventions existantes du fichier (`findAllByActiveTrueOrderByNameAsc`, `existsByActiveTrueAndIdNot`).
- Paramètre `Long organizationId` (et non l'entité `Organization`) : appelée avec `user.getOrganization().getId()`. Cet appel ne déclenche pas de `LazyInitializationException` même avec `spring.jpa.open-in-view: false` (confirmé dans `application.yml`), car `getId()` sur un proxy Hibernate non initialisé n'a pas besoin d'atteindre la base -- l'identifiant est déjà porté par le proxy issu de la colonne `organization_id` de `users`.
- `Shop.organization` (FK `organization_id`, `NOT NULL`, indexée `idx_shops_organization` depuis #34) est la colonne filtrée -- aucune migration nécessaire.

### `CurrentShopContext.accessibleShopsOf` (ligne 59-60)

```java
if (user.getRole() == Role.ADMIN) {
    return shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(user.getOrganization().getId());
}
```

Aucun autre changement de signature ni de comportement sur `accessibleShopIds`, `accessibleShops`, `resolveReadFilter`, `shopIdForCreation`, `assertAccessible`.

### Décision tranchée -- ADMIN sans boutique dans une organisation sans boutique active

Comportement accepté **tel quel, sans modification de code au-delà du filtre par organisation** :

- `accessibleShopsOf` / `accessibleShopIds()` retournent une liste vide (pas de `BusinessRuleException`, contrairement à la branche "aucune boutique ni rôle ADMIN" ligne 62 qui reste, elle, inchangée et non atteinte dans ce cas puisque le rôle est ADMIN).
- `resolveReadFilter()` retourne une liste vide (vue consolidée vide), sans erreur -- comportement cohérent avec un ADMIN qui n'a effectivement accès à rien.
- `shopIdForCreation()` : avec `accessible.size() == 0`, tombe dans la branche `else` existante (ligne 106-108) et lève `BusinessRuleException("Vous etes rattache a plusieurs boutiques : ...")`. **Ce message reste inchangé dans ce ticket.** Il est techniquement imprécis pour le cas "zéro boutique" (il parle de "plusieurs boutiques" alors qu'il y en a zéro), mais :
  - le ticket ne demande qu'un test couvrant ce cas, pas un nouveau comportement ;
  - le résultat fonctionnel est correct (la création est bien bloquée, aucune fuite de données inter-organisation) ;
  - corriger le message est un changement cosmétique orthogonal au scoping de sécurité visé par ce ticket, et sort du périmètre "Hors périmètre" du design (propagation du scoping / ajustements fonctionnels en aval sont pour les tickets #36-#43).
  - Un message dédié pour le cas 0 boutique pourra être traité dans un ticket de suivi si jugé utile ; ce n'est pas un blocage pour #35.

## Plan de tests

| Critère d'acceptation (ticket #35) | Test | Assertions clés |
|---|---|---|
| Un ADMIN d'une organisation ne voit plus, via `accessibleShopsOf`, que les boutiques de sa propre organisation. | `CurrentShopContextTest` -- nouveau test d'isolation multi-organisations | Stub `findAllByActiveTrueAndOrganizationIdOrderByNameAsc(orgA.getId())` -> `[shop1]` et `findAllByActiveTrueAndOrganizationIdOrderByNameAsc(orgB.getId())` -> `[shop2]` ; ADMIN de l'organisation A authentifié ; `accessibleShopIds()` retourne `containsExactly(1L)` uniquement ; `verify(shopRepository, never()).findAllByActiveTrueAndOrganizationIdOrderByNameAsc(orgB.getId())`. |
| Instance mono-tenant (une seule organisation en base) : comportement strictement identique à aujourd'hui -- non-régression explicitement testée. | `CurrentShopContextTest.accessibleShopIdsForAdminWithoutAssignment` (adapté) | Une seule `Organization` construite, ADMIN sans `shops` rattaché à cette organisation, stub de la nouvelle méthode pour cet unique `organizationId` -> `[shop1, shop2]` ; `accessibleShopIds()` retourne `containsExactly(1L, 2L)` -- identique au comportement actuel du test avant modification. |
| Tests couvrant le cas ADMIN sans boutique assignée dans une base multi-organisations. | Le test d'isolation multi-organisations (ci-dessus) **et** le nouveau test "organisation sans boutique active" | Isolation : couvre le cas où l'ADMIN est dans une base multi-org avec des boutiques dans sa propre organisation. Organisation vide : `Organization` sans boutique active associée, ADMIN sans `shops` rattaché à cette organisation, stub de la méthode pour cet `organizationId` -> `List.of()` ; `accessibleShopIds()` et `resolveReadFilter()` retournent des listes vides, aucune exception levée. |
| (Complémentaire, verrouille la décision de spec ci-dessus) | Nouveau test `shopIdForCreation` avec 0 boutique accessible | Même setup que le test "organisation sans boutique active" ; `assertThatThrownBy(() -> currentShopContext.shopIdForCreation()).isInstanceOf(BusinessRuleException.class)` (pas d'assertion sur le libellé exact du message, celui-ci restant volontairement inchangé -- cf. décision). |

Aucun test d'intégration `@DataJpaTest` requis : convention déjà en place dans le repo (`ShopRepository` et `OrganizationRepository` n'ont aucun test dédié existant, les méthodes dérivées Spring Data sont validées indirectement via `CurrentShopContextTest` avec des mocks -- cohérent avec la couverture actuelle).

## Écarts identifiés

- **Le design affirme (section "Fichiers/modules impactés" et risque final) qu'aucun autre appelant de `findAllByActiveTrueOrderByNameAsc()` n'existe en code de production hormis `CurrentShopContext`.** C'est inexact : `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java:80` l'utilise également, pour détecter une installation mono-boutique avant de semer des données de démonstration (`if (shops.size() != 1) { ... seeding ignoré ... }`). Conséquence tranchée dans cette spec : `findAllByActiveTrueOrderByNameAsc()` **n'est pas supprimée ni dépréciée** dans ce ticket -- elle reste utilisée telle quelle par `DemoDataSeeder`, qui n'est pas dans le périmètre de #35 (le seeding de démo n'est pas un chemin de sécurité multi-tenant à corriger ici). Le codeur ne doit pas toucher à `DemoDataSeeder.java` ni à ses tests (`DemoDataSeederTest.java`).
- Aucun autre écart entre le design et les critères d'acceptation du ticket n'a été identifié : le design couvre bien les trois critères d'acceptation, et la seule décision explicitement laissée ouverte (comportement en aval pour organisation sans boutique active) est tranchée ci-dessus dans le Contrat technique.
