# Review - #67 Relances de retard reellement automatiques

## Verdict

APPROVE

## Resume

Deuxieme passage. Le Finding #1 (bloquant) du premier verdict est corrige :
`ReminderService.sendAutomatic()` ne traverse plus `CustomerService.getEntity()`
ni `CurrentShopContext` (donc plus `SecurityContextHolder`/`CurrentUser`). J'ai
relu le chemin d'appel complet apres le fix (`sendAutomatic` ->
`customerRepository.findById` -> `buildPreview` -> `saleRepository.findByCustomer`
+ `installmentRepository.findBySaleIdOrderByNumberAsc` -> `doSend` ->
`notificationChannel.send` + `auditLogService.record`) : aucun de ces appels ne
consulte `CurrentShopContext`. `AuditLogService.record()` appelle bien
`CurrentUser.username()`, mais cette methode est explicitement null-safe
(retourne `null` si `SecurityContextHolder` n'a pas d'`Authentication`, ne leve
jamais) - `actor` sera simplement `null` pour les relances automatiques, ce qui
est correct et sans effet de bord. Le cloisonnement multi-tenant reste assure
par la RLS Postgres pilotee par `TenantContext` (`TenantConnectionConfig`,
`app.current_org_id`), positionnee par `ReminderSchedulerJob.runForOrganization()`
independamment de toute authentification HTTP - verifie en lisant
`TenantConnectionConfig` et en confirmant qu'aucun composant du chemin
`sendAutomatic` ne depend de `TenantContext` indirectement via
`CurrentShopContext`.

J'ai reproduit empiriquement la regression que corrige ce round : en revertant
localement `sendAutomatic()` vers l'ancien appel a `prepareForCustomer()` (donc
`customerService.getEntity()`), le nouveau test
`sendAutomaticNeverConsultsCustomerServiceOrShopContext` echoue bien, avec les
deux autres tests `sendAutomaticRecords*WithAutoSuffix` - `NullPointerException:
Cannot invoke "Customer.getId()" because "customer" is null` a
`ReminderService.buildPreview` (`customerService` etant un mock Mockito
totalement non-stubbe, retournant `null`). Ce n'est pas un test qui se contente
de verifier des interactions sur des mocks deja "correctement" cables : il
casse reellement si la regression est reintroduite. Fichier restaure a
l'identique du commit `f6c2ad8` apres verification (`git diff` vide sur ce
fichier).

Les chemins manuels (`send()`, `generate()`, `sendAll()`) n'ont pas ete
touches par ce round (seuls `sendAutomatic()` et `buildPreview()`/
`prepareForCustomer()` ont change) et continuent d'utiliser
`customerService.getEntity()` avec verification d'acces - confirme par lecture
du diff et par les tests existants (`sendRecordsSuccess`, `sendRecordsFailure`,
`sendAllContinuesOnFailure`, `sendAllRejectsManualChannel`, `generateNeverCallsChannel`),
tous verts.

Le Finding #2 (TOCTOU intra-instance, mineur/informatif dans le premier
verdict) est desormais documente dans `design.md` section "Hors perimetre",
avec la meme justification et le meme type d'ecart accepte que le cas
multi-instance deja note - acceptable pour un ecart mineur explicitement
assume plutot que corrige.

`sendAutomatic()` n'est appele que depuis `ReminderSchedulerJob`, n'est exposee
par aucun controleur REST : pas de nouvelle surface RBAC a valider.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Une echeance en retard declenche une relance automatique sous 24h, sans action humaine | Couvert - `sendAutomatic()` s'execute desormais sans dependance a un `Authentication`/`CurrentShopContext` (Finding #1 corrige et verifie empiriquement), le cron quotidien (`app.reminder.auto-cron`, defaut `0 0 8 * * *`) et le flag `auto-enabled` sont testes (`ReminderSchedulerJobTest`). |
| 2 | Aucun double envoi (relance du job planifie, ou declenchement manuel en parallele) | Couvert pour le cas nominal (cooldown via `AuditLogRepository.existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter`, teste dans `skipsCustomerUnderCooldown`/`sendsCustomerNotUnderCooldown`), et desormais reellement exerce en pratique puisque le Finding #1 ne bloque plus les envois automatiques. La fenetre TOCTOU intra-instance (Finding #2) reste un ecart non corrige mais documente et juge mineur/etroit. |
| 3 | Le declenchement manuel (/send, /send-all) continue de fonctionner sans regression | Couvert - aucun changement de comportement sur `send()`/`generate()`/`sendAll()` dans ce round ; tests correspondants verts. |
| 4 | Cloisonnement multi-boutiques respecte par la tache planifiee | Couvert - RLS Postgres pilotee par `TenantContext.set/clear` (independante de `CurrentShopContext`), verifiee unitairement (`processesEachOrganizationWithItsOwnTenantContext`) ; l'IT Testcontainers dediee (`ReminderSchedulerJobMultiTenantIT`) existe et documente desormais explicitement pourquoi elle ne tourne pas via `mvn test` (pas de plugin Failsafe configure dans `backend/pom.xml`, convention deja en place depuis le bolt #40 pour `RowLevelSecurityIT`/`RowLevelSecurityHibernateIT`, hors perimetre de ce ticket). |

## Verification complementaire de ce round

- Relu `CurrentShopContext.java`, `TenantConnectionConfig.java`, `CurrentUser.java`,
  `AuditLogService.java` pour confirmer que rien dans le chemin
  `sendAutomatic -> buildPreview -> doSend` ne remonte, meme transitivement, a
  `SecurityContextHolder` de maniere bloquante.
- Confirme que `backend/pom.xml` ne configure ni `maven-failsafe-plugin` ni de
  binding personnalise de Surefire : la non-execution des `*IT.java` par
  `mvn test` est bien une convention pre-existante (bolt #40), pas une
  regression ni un contournement introduit par ce bolt.
- `sendAutomatic()` n'est reference que dans `ReminderSchedulerJob` et
  `ReminderService` - pas de controleur REST, donc pas de nouvelle question de
  RBAC.

## Build/tests

- `cd backend && mvn test` (suite complete) : **432 tests, 0 echec, BUILD SUCCESS** -
  confirme le chiffre rapporte par le codeur (431 -> 432, coherent avec l'ajout
  du seul nouveau test `sendAutomaticNeverConsultsCustomerServiceOrShopContext`).
- Reproduction empirique du Finding #1 : revert temporaire (non commite) de
  `sendAutomatic()` vers l'appel `prepareForCustomer(customerId, null)` (ancien
  code), puis `mvn -Dtest=ReminderServiceTest test` -> 3 echecs (`NullPointerException`
  a `ReminderService.buildPreview` via `prepareForCustomer`/`sendAutomatic`) sur
  `sendAutomaticRecordsFailureWithAutoSuffix`, `sendAutomaticNeverConsultsCustomerServiceOrShopContext`,
  `sendAutomaticRecordsSuccessWithAutoSuffix`. Fichier restaure ensuite via
  `git checkout -- ReminderService.java` (diff verifie vide apres restauration).
- Frontend : aucun fichier frontend touche par ce bolt (`git diff master --stat`),
  pas de build frontend necessaire.

## Suivi (non bloquant)

- Finding #2 (TOCTOU intra-instance) reste un ecart accepte et documente,
  coherent avec le traitement du cas multi-instance. A revisiter avec un verrou
  consultatif Postgres si le besoin de fiabilite augmente, comme note dans
  `design.md`.
- La convention "aucun `*IT.java` n'est execute par un goal Maven" (absence de
  Failsafe) est une dette pre-existante au module, desormais documentee dans
  `ReminderSchedulerJobMultiTenantIT`. Elle n'est pas propre a ce ticket et ne
  bloque pas ce round, mais vaudrait la peine d'un ticket dedie pour eviter
  que de futures IT restent silencieusement non executees en CI.
