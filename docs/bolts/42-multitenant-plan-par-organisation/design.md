# Design — #42 Multi-tenant 9/10 — Modele de plan par organisation en base

## Approche

Nouvelle table `organization_plan`, une ligne optionnelle par organisation, portant uniquement le
flag `multi_shop` : c'est le seul flag du ticket #24 qui peut varier par organisation sans toucher
a l'architecture d'envoi de notifications (voir Decisions cles pour `whatsappAuto`). Absence de
ligne = fallback integral sur `AppProperties.Plan` (comportement mono-tenant inchange). Un nouveau
service `OrganizationPlanResolver` centralise cette resolution (table si presente, sinon config
d'instance) et remplace les deux lectures directes de `properties.getPlan().isMultiShop()`
identifiees dans le code (`ShopService`, `AuthService`). Le prix paye : une table dediee plutot
qu'une colonne nullable sur `organizations`, legerement plus de code (entite, repository,
migration, grant, policy RLS), mais qui isole les donnees commerciales (facturation) du coeur
d'identite du tenant, et suit le pattern deja en place pour les autres tables "attributs par
organisation" (grant explicite V16, policy RLS V15). `whatsappAuto` reste un attribut d'instance,
assume et documente ci-dessous, pas leve dans ce ticket : aucun changement de
`NotificationChannel`/`WhatsAppCloudApiChannel`/`AppProperties.Whatsapp`.

## Fichiers/modules impactes

Backend, nouveaux fichiers :
- `backend/src/main/resources/db/migration/V17__organization_plan.sql` : table `organization_plan`
  (`organization_id BIGINT PRIMARY KEY REFERENCES organizations(id)`, `multi_shop BOOLEAN NOT
  NULL`, colonnes d'audit `created_at`/`updated_at`/`created_by`/`updated_by` comme
  `V13__organizations.sql`), policy RLS `organization_plan_tenant_isolation` reutilisant
  `app_current_org_id()` (deja cree par `V15__row_level_security.sql`), sur le meme modele que les
  policies existantes. Aucune ligne inseree par cette migration : absence de ligne = fallback,
  conforme au critere d'acceptation n2.
- `backend/src/main/resources/db/migration/V18__organization_plan_grants.sql` : GRANT SELECT,
  INSERT, UPDATE, DELETE ON organization_plan au role `${creditflowAppRole}` : necessaire en
  migration separee de V17 (la liste de tables de `V16__app_role_grants.sql` est deja appliquee, on
  ne modifie jamais une migration Flyway existante). Sans ce GRANT, le role applicatif restreint ne
  peut ni lire ni ecrire cette table, et toute lecture de plan echoue silencieusement en production
  (retombe correctement sur le fallback cote Java, mais avec une erreur SQL en log a chaque appel).
- `backend/src/main/java/com/creditflow/organization/domain/OrganizationPlan.java` : entite JPA,
  `@Id private Long organizationId` (pas de `@GeneratedValue`, valeur = id de l'organisation),
  `private boolean multiShop`, `extends Auditable` (meme pattern que `Organization`).
- `backend/src/main/java/com/creditflow/organization/repository/OrganizationPlanRepository.java` :
  `JpaRepository<OrganizationPlan, Long>`, `findById(organizationId)` suffit.
- `backend/src/main/java/com/creditflow/organization/service/OrganizationPlanResolver.java` :
  `@Service`, injecte `OrganizationPlanRepository` et `AppProperties` ; methode
  `boolean multiShopEnabled(Long organizationId)` retourne la valeur en base si une ligne existe
  pour cette organisation, sinon `properties.getPlan().isMultiShop()`. Pas de methode pour
  `whatsappAuto` : ce flag reste lu directement depuis `AppProperties.Plan` partout, jamais depuis
  cette table (voir Decisions cles).

Backend, fichiers modifies :
- `backend/src/main/java/com/creditflow/shop/service/ShopService.java` : injection
  d'`OrganizationPlanResolver` ; `assertPlanAllowsActive` prend un `organizationId` supplementaire
  et remplace `properties.getPlan().isMultiShop()` par
  `organizationPlanResolver.multiShopEnabled(organizationId)`. Dans `create()`, l'organisation est
  deja resolue par `resolveDefaultOrganization()` avant l'appel, ce meme id est reutilise (pas
  `TenantContext`/`CurrentShopContext`, voir Risques). Dans `update()`, l'id vient de
  `shop.getOrganization().getId()`.
- `backend/src/main/java/com/creditflow/auth/service/AuthService.java` : injection
  d'`OrganizationPlanResolver` ; `login()` construit `PlanSummary` avec
  `organizationPlanResolver.multiShopEnabled(reloaded.getOrganization().getId())` pour `multiShop`,
  et conserve `properties.getPlan().isWhatsappAuto()` pour `whatsappAuto` (inchange, instance-wide).
  L'appel est fait a l'interieur du bloc `try` existant (`TenantContext` deja positionne sur
  l'organisation de l'utilisateur), coherent avec le reste de la methode.
- Tests : `backend/src/test/java/com/creditflow/shop/service/ShopServiceTest.java` et
  `backend/src/test/java/com/creditflow/auth/service/AuthServiceTest.java` mockent aujourd'hui
  directement `properties.getPlan()`, a adapter pour mocker
  `organizationPlanResolver.multiShopEnabled(...)` (retour direct d'un booleen). Nouveaux tests
  dedies : `OrganizationPlanResolverTest` (fallback quand aucune ligne, override quand une ligne
  existe) et un test d'integration Flyway/RLS verifiant qu'une organisation ne peut pas lire la
  ligne de plan d'une autre (meme famille de test que celle attendue pour V15 dans le design #40).

Frontend : aucun fichier impacte. `AuthResponse.plan` garde exactement la meme forme
(`PlanSummary(multiShop, whatsappAuto)`), seule la source de la valeur `multiShop` change cote
backend ; `frontend/src/types.ts`, `AuthContext.tsx`, `ShopsPage.tsx` n'ont besoin d'aucun
changement.

## Decisions cles

- `whatsappAuto` reste un attribut d'instance, assume, pas leve. Confirme par lecture directe :
  `WhatsAppCloudApiChannel` (`@ConditionalOnProperty(name = "app.notification.channel",
  havingValue = "whatsapp")`) et `ManualCopyChannel` (`matchIfMissing = true`) sont les deux seules
  implementations de `NotificationChannel`, injectees en champ unique dans `ReminderService`
  (`private final NotificationChannel notificationChannel`) : un seul bean vit dans le contexte
  Spring pour tout le processus JVM. Lever la limite demanderait (a) un mecanisme de selection de
  canal par organisation a l'execution, et (b) de rendre les identifiants WhatsApp eux-memes
  multi-tenant (`AppProperties.Whatsapp.phoneNumberId`/`accessToken` sont aujourd'hui des valeurs
  d'instance uniques, deux organisations partageant une instance mutualisee auraient
  necessairement des comptes WhatsApp Business distincts). C'est un changement d'architecture du
  canal de notification, pas un ajout au modele de plan ; disproportionne pour ce ticket P2 dont le
  seul critere d'acceptation porte sur `multiShop`. Decision : documenter la limite (deja actee par
  la note d'architecture #25) plutot que la lever ici ; `organization_plan` ne porte donc pas de
  colonne `whatsapp_auto`, en ajouter une inutilisee aurait suggere a tort qu'elle est fonctionnelle.
- Table dediee `organization_plan`, pas une colonne sur `organizations`. `Organization` (#34) a ete
  deliberement gardee minimale (`id`, `name`) ; une donnee commerciale/facturation qui evoluera
  probablement (autres flags a l'avenir, historique de changement de formule) est mieux isolee dans
  sa propre table que melangee a l'identite du tenant. Cout : une entite/repository/migration/grant
  supplementaires plutot qu'un simple ajout de colonne.
- Absence de ligne = fallback, pas une colonne nullable. Un seul flag existe dans cette table
  (`multi_shop`), donc la granularite ligne presente/absente est suffisante et plus simple a
  raisonner que des colonnes nullables individuelles : une organisation a soit une formule
  explicite en base, soit herite integralement de `AppProperties.Plan`. Coherent avec le critere
  d'acceptation n2, formule comme "sans table de plan renseignee" (singulier, pas par flag).
- Pas d'interface d'administration pour peupler `organization_plan`. Meme decision que #24 pour les
  variables d'environnement : la formule est un evenement commercial rare, pilote par l'exploitant
  via SQL direct (role qui applique les migrations Flyway, distinct du role applicatif restreint
  `${creditflowAppRole}` du V16/V18) ; a documenter dans le runbook d'exploitation, hors perimetre
  code de ce ticket.
- `OrganizationPlanResolver` est un nouveau service dedie, pas une extension de
  `CurrentShopContext` : `CurrentShopContext` resout des boutiques/organisations a partir de
  l'utilisateur authentifie courant (via `SecurityContext`) ; `ShopService.create()` a besoin de
  resoudre le plan pour une organisation qui n'est pas necessairement celle de l'utilisateur
  courant au sens de `CurrentShopContext` (`resolveDefaultOrganization()`, voir Risques) mais un id
  d'organisation deja determine par ailleurs. Un service qui prend `organizationId` en parametre
  explicite est reutilisable dans les deux contextes (`AuthService` et `ShopService`) sans dependre
  du `SecurityContext`.
- `OrganizationPlanRepository.findById` est appele directement avec l'id, pas via une
  `Specification`/filtre `inOrganization(...)` comme `CustomerSpecifications.inOrganization` : il
  n'y a jamais de liste a filtrer ici, une seule ligne par cle primaire. La policy RLS
  `organization_plan_tenant_isolation` est une defense en profondeur, pas le mecanisme d'acces
  principal.

## Risques / points d'attention

- `ShopService.resolveDefaultOrganization()` (donc `create()`) et
  `UserService.resolveDefaultOrganization()` utilisent
  `organizationRepository.findFirstByOrderByIdAsc()`, qui renvoie toujours la premiere
  organisation creee en base, independamment de l'organisation de l'utilisateur authentifie
  courant, car la table `organizations` n'est pas sous RLS (seul `shops` et les tables metier le
  sont, cf. `V15__row_level_security.sql`, qui ne liste pas `organizations`). Gap preexistant, pas
  introduit par #42, hors perimetre des tickets #34/#35 merges : en environnement mutualise reel
  avec plusieurs organisations, toute nouvelle boutique ou tout nouvel utilisateur cree via l'API
  est aujourd'hui rattache a la premiere organisation, pas a celle de l'appelant. Ce ticket ne le
  corrige pas (onboarding self-service d'organisations explicitement exclu par #25), mais il faut
  le documenter pour ne pas laisser croire que le critere d'acceptation n1 est testable via
  `POST /api/shops` en conditions reelles multi-organisations sans seed manuel des deux
  organisations et de leurs boutiques respectives en base au prealable.
- Non-regression sur les deux lectures existantes de `AppProperties.Plan.isMultiShop()` : exhaustif
  par grep, uniquement `ShopService.assertPlanAllowsActive` et `AuthService.login()`. Les deux
  doivent passer par `OrganizationPlanResolver` apres ce ticket ; un oubli de l'un des deux
  laisserait un chemin encore gate par la config d'instance seule.
- `OrganizationPlanResolver.multiShopEnabled` ouvre une requete SQL supplementaire a chaque appel
  (`create()`, `update()` de boutique, et `login()`) : volume negligeable (une ligne par
  organisation, table quasi vide en pratique), pas de cache introduit dans ce ticket.
- Tests existants a adapter, pas seulement a etendre : `ShopServiceTest` et `AuthServiceTest`
  mockent aujourd'hui `properties.getPlan()` retournant un `AppProperties.Plan` construit
  manuellement. Si le codeur ajoute `OrganizationPlanResolver` sans retirer ces stubs devenus
  inutiles pour le chemin `multiShop`, les tests peuvent rester verts par accident (mock non
  appele) sans verifier le nouveau chemin de resolution : le reviewer doit verifier que les tests
  stubbent bien `organizationPlanResolver.multiShopEnabled(...)`, pas seulement
  `properties.getPlan()`.
- Flyway sur une base existante : `V17`/`V18` s'appliquent apres `V16` sur toute instance deja en
  production (mono-tenant ou non) sans erreur ni donnee a retro-remplir (table vide au depart),
  meme forme que `V15`/`V16` deja livrees dans ce meme cycle multi-tenant.

## Hors perimetre

- Lever la contrainte d'instance sur `whatsappAuto` (canal de notification resolu par organisation
  a l'execution, identifiants WhatsApp par organisation) : voir Decisions cles, assume et
  documente, pas traite ici.
- Toute interface d'administration (backend ou frontend) permettant de creer/modifier une ligne
  `organization_plan` depuis l'application : peuplement par SQL direct uniquement, comme #24 pour
  les variables d'environnement.
- Correction de `ShopService.resolveDefaultOrganization()`/`UserService.resolveDefaultOrganization()`
  (rattachement systematique a la premiere organisation) : gap preexistant documente en Risques,
  pas dans le perimetre de ce ticket.
- Extension de `organization_plan` a d'autres flags (`stockSuppliers`, `excelExport`, deja hors
  perimetre de #24) : seul `multi_shop` est couvert, coherent avec les criteres d'acceptation.
- Toute UI de facturation, cycle d'abonnement, expiration automatique de formule : non demande par
  ce ticket, meme decision que #24.
