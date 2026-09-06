# Spec — #42 Multi-tenant 9/10 — Modèle de plan par organisation en base

## Résumé

Ajouter une table `organization_plan` (flag `multi_shop` par organisation, fallback sur
`AppProperties.Plan` si absente) et un service `OrganizationPlanResolver` qui remplace les deux
lectures directes de `properties.getPlan().isMultiShop()` dans `ShopService` et `AuthService`,
sans modifier le traitement instance-wide de `whatsappAuto`.

## Tâches

- [ ] **Migration table** — `backend/src/main/resources/db/migration/V17__organization_plan.sql` :
  créer `organization_plan` (PK = `organization_id`, `multi_shop BOOLEAN NOT NULL`, colonnes
  d'audit), policy RLS `organization_plan_tenant_isolation` réutilisant `app_current_org_id()`.
  Aucune ligne insérée.
- [ ] **Migration grants** — `backend/src/main/resources/db/migration/V18__organization_plan_grants.sql` :
  `GRANT SELECT, INSERT, UPDATE, DELETE ON organization_plan TO ${creditflowAppRole}` (V16 ne doit
  jamais être modifiée).
- [ ] **Entité JPA** — `backend/src/main/java/com/creditflow/organization/domain/OrganizationPlan.java`.
- [ ] **Repository** — `backend/src/main/java/com/creditflow/organization/repository/OrganizationPlanRepository.java`.
- [ ] **Service resolver** — `backend/src/main/java/com/creditflow/organization/service/OrganizationPlanResolver.java`
  (nouveau package `organization.service`, n'existe pas encore).
- [ ] **Test unitaire du resolver** — `backend/src/test/java/com/creditflow/organization/service/OrganizationPlanResolverTest.java`
  (fallback sans ligne, override avec ligne).
- [ ] **Point d'appel `ShopService`** — modifier `assertPlanAllowsActive` (signature + logique) et
  les deux appelants (`create()`, `update()`).
- [ ] **Point d'appel `AuthService`** — modifier `login()` pour construire `PlanSummary.multiShop`
  via le resolver.
- [ ] **Adapter `ShopServiceTest`** — retirer les stubs de `properties.getPlan()` devenus inutiles
  pour le chemin `multiShop`, mocker `organizationPlanResolver.multiShopEnabled(...)`.
- [ ] **Adapter `AuthServiceTest`** — idem, injecter le nouveau mock dans le constructeur d'`AuthService`.
- [ ] **Test d'intégration RLS** — vérifier qu'une organisation ne peut pas lire la ligne
  `organization_plan` d'une autre (même famille que le test RLS attendu pour V15/#40).
- [ ] **Documentation `whatsappAuto`** — ajouter une note dans ce spec (section Contrat technique
  ci-dessous) confirmant explicitement le statu quo, conformément au critère d'acceptation n°3.

## Contrat technique

### V17 — `backend/src/main/resources/db/migration/V17__organization_plan.sql`

```sql
-- =====================================================================
-- V17 - Plan par organisation (#42)
-- Absence de ligne = fallback integral sur AppProperties.Plan (mono-tenant
-- inchange). Un seul flag ici : multi_shop. whatsappAuto reste un attribut
-- d'instance, assume, non couvert par cette table (voir design.md #42,
-- section Decisions cles).
-- =====================================================================

CREATE TABLE organization_plan (
    organization_id BIGINT PRIMARY KEY REFERENCES organizations (id),
    multi_shop      BOOLEAN   NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(80),
    updated_by      VARCHAR(80)
);

ALTER TABLE organization_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_plan_tenant_isolation ON organization_plan
    USING (organization_id = app_current_org_id());
```

### V18 — `backend/src/main/resources/db/migration/V18__organization_plan_grants.sql`

```sql
-- =====================================================================
-- V18 - Octroi des droits au role applicatif restreint sur organization_plan (#42)
-- Migration separee de V16 : V16 est deja appliquee en production, on ne
-- modifie jamais une migration Flyway existante (checksum).
-- =====================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON organization_plan TO ${creditflowAppRole};
```

### Entité — `backend/src/main/java/com/creditflow/organization/domain/OrganizationPlan.java`

```java
package com.creditflow.organization.domain;

import com.creditflow.common.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Formule commerciale d'une organisation, resolue en base plutot que par
 * configuration d'instance. Absence de ligne pour une organisation = fallback
 * integral sur {@link com.creditflow.config.AppProperties.Plan}, voir
 * {@link com.creditflow.organization.service.OrganizationPlanResolver}.
 *
 * Cle primaire = id de l'organisation (pas de generation autonome) : une
 * organisation a au plus une ligne de plan.
 */
@Entity
@Table(name = "organization_plan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationPlan extends Auditable {

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "multi_shop", nullable = false)
    private boolean multiShop;
}
```

### Repository — `backend/src/main/java/com/creditflow/organization/repository/OrganizationPlanRepository.java`

```java
package com.creditflow.organization.repository;

import com.creditflow.organization.domain.OrganizationPlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationPlanRepository extends JpaRepository<OrganizationPlan, Long> {
}
```

`findById(organizationId)` (hérité de `JpaRepository`) suffit : une seule ligne par clé primaire,
pas de liste à filtrer.

### Service — `backend/src/main/java/com/creditflow/organization/service/OrganizationPlanResolver.java`

```java
package com.creditflow.organization.service;

import com.creditflow.config.AppProperties;
import com.creditflow.organization.domain.OrganizationPlan;
import com.creditflow.organization.repository.OrganizationPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resout la formule effective d'une organisation : table organization_plan si
 * une ligne existe, sinon fallback integral sur la configuration d'instance
 * (AppProperties.Plan), pour compatibilite mono-tenant inchangee.
 *
 * Ne couvre que multi_shop. whatsappAuto reste un attribut d'instance, lu
 * directement depuis AppProperties.Plan partout ailleurs (voir #42, Decisions
 * cles) : ne pas ajouter de methode whatsappAuto ici sans lever d'abord la
 * contrainte d'architecture du canal de notification (NotificationChannel,
 * bean unique par JVM).
 */
@Service
@RequiredArgsConstructor
public class OrganizationPlanResolver {

    private final OrganizationPlanRepository organizationPlanRepository;
    private final AppProperties properties;

    @Transactional(readOnly = true)
    public boolean multiShopEnabled(Long organizationId) {
        return organizationPlanRepository.findById(organizationId)
                .map(OrganizationPlan::isMultiShop)
                .orElseGet(() -> properties.getPlan().isMultiShop());
    }
}
```

### `ShopService.java` — avant/après

Avant :
```java
private final AppProperties properties;
...
private void assertPlanAllowsActive(boolean requestedActive, boolean wasActive, Long excludingShopId) {
    if (!requestedActive || wasActive || properties.getPlan().isMultiShop()) {
        return;
    }
    ...
}
```
Appels : `assertPlanAllowsActive(effectiveActive(request, null), false, null)` (dans `create()`),
`assertPlanAllowsActive(effectiveActive(request, shop), shop.isActive(), id)` (dans `update()`).

Après :
```java
private final AppProperties properties;
private final OrganizationPlanResolver organizationPlanResolver;
...
@Transactional
public ShopResponse create(ShopRequest request) {
    Organization organization = resolveDefaultOrganization();
    assertPlanAllowsActive(effectiveActive(request, null), false, null, organization.getId());
    assertNameAvailable(request.name(), null);

    Shop shop = shopMapper.toEntity(request);
    shop.setOrganization(organization);
    Shop saved = shopRepository.save(shop);
    log.info("Boutique creee: {} ({})", saved.getName(), saved.getId());
    return shopMapper.toResponse(saved);
}

@Transactional
public ShopResponse update(Long id, ShopRequest request) {
    Shop shop = getEntity(id);
    assertPlanAllowsActive(effectiveActive(request, shop), shop.isActive(), id,
            shop.getOrganization().getId());
    assertNameAvailable(request.name(), id);

    shopMapper.updateEntity(request, shop);
    return shopMapper.toResponse(shopRepository.save(shop));
}
...
private void assertPlanAllowsActive(boolean requestedActive, boolean wasActive, Long excludingShopId,
        Long organizationId) {
    if (!requestedActive || wasActive || organizationPlanResolver.multiShopEnabled(organizationId)) {
        return;
    }
    ...
}
```
Note : `create()` doit appeler `resolveDefaultOrganization()` une seule fois (avant l'assertion) et
réutiliser l'instance résolue pour `shop.setOrganization(organization)`, au lieu de rappeler
`resolveDefaultOrganization()` une seconde fois plus bas comme dans le code actuel — évite une
requête dupliquée et garantit que le plan vérifié correspond bien à l'organisation assignée.

### `AuthService.java` — avant/après

Avant :
```java
PlanSummary plan = new PlanSummary(
        properties.getPlan().isMultiShop(), properties.getPlan().isWhatsappAuto());
```

Après :
```java
private final OrganizationPlanResolver organizationPlanResolver;
...
PlanSummary plan = new PlanSummary(
        organizationPlanResolver.multiShopEnabled(reloaded.getOrganization().getId()),
        properties.getPlan().isWhatsappAuto());
```
Appel inchangé de position : à l'intérieur du bloc `try` existant, après le rechargement de
`reloaded` (organisation déjà connue, `TenantContext` déjà positionné). `whatsappAuto` continue de
lire `properties.getPlan()` directement — ne pas router ce flag par `OrganizationPlanResolver`.

### `whatsappAuto` — statut explicite (critère d'acceptation n°3)

`whatsappAuto` **n'est pas levé** par ce ticket et reste un attribut d'instance unique, lu
directement depuis `AppProperties.Plan.isWhatsappAuto()` dans `AuthService.login()` et vérifié au
démarrage par `PlanConfigValidator`. Raison : `NotificationChannel` n'a qu'un seul bean vivant par
JVM (`WhatsAppCloudApiChannel` conditionné par `app.notification.channel=whatsapp`, ou
`ManualCopyChannel` par défaut), injecté en champ unique dans `ReminderService`. Rendre ce flag
multi-tenant demanderait un mécanisme de sélection de canal par organisation à l'exécution et des
identifiants WhatsApp Business par organisation (`AppProperties.Whatsapp.phoneNumberId`/
`accessToken` sont aujourd'hui des valeurs d'instance uniques) — hors périmètre P2 de ce ticket,
qui ne couvre que `multiShop`. `organization_plan` ne porte donc aucune colonne `whatsapp_auto`.

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| CA1 — Deux organisations sur la même instance peuvent avoir des formules `multiShop` différentes | `OrganizationPlanResolverTest.overridesWhenRowExists()` : une ligne `organization_plan(multi_shop=false)` pour l'organisation A retourne `false`, tandis qu'aucune ligne pour l'organisation B (ou une ligne `multi_shop=true`) retourne une valeur différente — unitaire, deux appels indépendants à `multiShopEnabled(idA)`/`multiShopEnabled(idB)` avec des mocks de repository distincts par id. Complété par le test d'intégration RLS (isolation) et documentation du gap `resolveDefaultOrganization()` ci-dessous (limite manuelle pour un test bout-en-bout via l'API). |
| CA2 — Instance mono-tenant sans table de plan renseignée : comportement identique au fallback `AppProperties.Plan` | `OrganizationPlanResolverTest.fallsBackToInstancePlanWhenNoRow()` : `organizationPlanRepository.findById(...)` retourne `Optional.empty()`, `properties.getPlan().isMultiShop()` retourne une valeur connue (`true` puis `false` dans deux cas), assertion que `multiShopEnabled` renvoie exactement cette valeur. Complété par la non-régression des tests `ShopServiceTest`/`AuthServiceTest` existants (mono-boutique/multi-boutique), adaptés pour mocker le resolver (voir piège ci-dessous) au lieu de `properties.getPlan()` directement. |
| CA3 — `whatsappAuto` documenté explicitement (levé ou assumé) | Manuel / revue de spec : section "whatsappAuto — statut explicite" ci-dessus, à valider par le reviewer comme couvrant le critère (aucun test automatisé pertinent pour une décision de non-changement ; `PlanConfigValidatorTest` existant, s'il existe, ne doit nécessiter aucune modification puisque `AuthService`/`PlanConfigValidator` continuent de lire `properties.getPlan().isWhatsappAuto()` sans changement de comportement). |

### Piège de mock à corriger explicitement dans les tests existants

**`ShopServiceTest.java`** :
- Retirer le stub `when(properties.getPlan()).thenReturn(new AppProperties.Plan());` du `setUp()`
  (devenu un mock mort pour le chemin `multiShop` : `assertPlanAllowsActive` n'appellera plus
  `properties.getPlan()`).
- Retirer `private static AppProperties.Plan singleShopPlan()` et tous ses appels
  (`when(properties.getPlan()).thenReturn(singleShopPlan());` dans
  `rejectsSecondActiveShopWhenPlanIsSingleShopOnCreate`,
  `rejectsReactivationOfSecondShopWhenPlanIsSingleShop`,
  `allowsUpdateOfSingleAlreadyActiveShopEvenWithSingleShopPlan`,
  `allowsUpdateOfAlreadyActiveShopAmongMultipleEvenWithSingleShopPlan`).
- Ajouter un `@Mock private OrganizationPlanResolver organizationPlanResolver;`.
- Dans chacun de ces quatre tests, remplacer le stub retiré par
  `when(organizationPlanResolver.multiShopEnabled(1L)).thenReturn(false);` (id `1L` = organisation
  par défaut résolue par `organizationRepository.findFirstByOrderByIdAsc()` dans `setUp()`), et dans
  `allowsSecondActiveShopWhenPlanIsMultiShop` (ainsi que les tests de création/màj sans restriction
  de plan) s'assurer soit qu'aucun stub n'est nécessaire (défaut Mockito `false` sur `boolean`
  suffirait à casser silencieusement `allowsSecondActiveShopWhenPlanIsMultiShop` : ce test exige
  explicitement `when(organizationPlanResolver.multiShopEnabled(1L)).thenReturn(true);`), pour
  éviter qu'un défaut de mock (`false`) ne fasse passer le test pour une mauvaise raison.
- Vérifier après modification qu'un test échoue si on inverse volontairement la valeur stubbée
  (sanity check manuel du codeur/reviewer) — garantit que le mock est bien exercé et non contourné.

**`AuthServiceTest.java`** :
- Retirer `when(properties.getPlan()).thenReturn(new AppProperties.Plan());` du `setUp()` (mock mort
  pour `multiShop`; `properties.getPlan()` reste appelé uniquement pour `whatsappAuto`, donc
  **ne pas retirer** `properties` du mock ni du constructeur — seulement le stub `multiShop` devient
  inutile si aucun test n'exerce plus `isMultiShop()` via `properties`).
- Ajouter un `@Mock private OrganizationPlanResolver organizationPlanResolver;`, l'injecter dans
  chaque construction d'`AuthService` (`setUp()` et la construction locale dans
  `loginResolvesAccessibleShopsWhileStillAnonymous`) — signature à ajuster partout où
  `new AuthService(...)` est appelé.
- Dans `setUp()`, stubber `when(organizationPlanResolver.multiShopEnabled(1L)).thenReturn(true);`
  (id `1L` = organisation de l'utilisateur `admin` construit dans `setUp()`), pour que
  `loginIncludesPlan` continue de vérifier `response.plan().multiShop()` égal à `true` via le
  nouveau chemin, et non plus via `properties.getPlan()`.
- Ajouter un test dédié `loginReflectsOrganizationOverrideOfMultiShop()` :
  `when(organizationPlanResolver.multiShopEnabled(1L)).thenReturn(false);`, assertion
  `response.plan().multiShop()` est `false` alors que `properties.getPlan().isMultiShop()` (mock par
  défaut `new AppProperties.Plan()`, `true`) ne l'est pas — preuve explicite que le resolver, pas le
  fallback, pilote la valeur dans ce test.
- `response.plan().whatsappAuto()` reste vérifié via `properties.getPlan().isWhatsappAuto()`, stub
  inchangé.

## Écarts identifiés

- Aucun écart entre `design.md` et le ticket #42 constaté après lecture complète du design et
  vérification directe du code réel (`AppProperties.java`, `Organization.java`, `Auditable.java`,
  `V13`/`V15`/`V16`, `ShopService.java`, `AuthService.java`, `PlanConfigValidator.java`,
  `ShopServiceTest.java`, `AuthServiceTest.java`) : toutes les affirmations du design sont exactes
  telles quelles, aucune dérive du code depuis sa rédaction.
- Point d'attention repris du design, à ne pas perdre de vue en revue : le critère d'acceptation
  n°1 (deux organisations, deux formules) n'est réellement testable de bout en bout via l'API
  (`POST /api/shops`) qu'avec un seed manuel de deux organisations et de leurs boutiques respectives
  en base, car `ShopService.resolveDefaultOrganization()` et `UserService.resolveDefaultOrganization()`
  rattachent systématiquement à la première organisation créée
  (`organizationRepository.findFirstByOrderByIdAsc()`), `organizations` n'étant pas sous RLS. Ce
  ticket ne corrige pas ce comportement (hors périmètre, déjà exclu par #25) ; le test unitaire du
  resolver (`OrganizationPlanResolverTest`) couvre la logique de résolution isolément, mais ne
  constitue pas une preuve d'intégration bout-en-bout du CA1 via l'API réelle — à documenter dans le
  runbook d'exploitation, pas à corriger dans ce ticket.
