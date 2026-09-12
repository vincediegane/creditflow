# Spec - #67 Relances de retard reellement automatiques

## Resume

Ajouter une tache planifiee quotidienne (`ReminderSchedulerJob`) qui parcourt
toutes les organisations actives, applique un garde-fou anti-doublon/anti-
harcelement base sur le journal d'audit existant, et declenche l'envoi des
relances de retard via `ReminderService`, sans toucher au declenchement
manuel existant (`/api/reminders/send`, `/send-all`).

## Taches

- [ ] `backend/src/main/java/com/creditflow/CreditFlowApplication.java` :
  ajouter `@EnableScheduling` (import
  `org.springframework.scheduling.annotation.EnableScheduling`).

- [ ] `backend/src/main/java/com/creditflow/config/AppProperties.java` :
  etendre la classe interne `Reminder` avec trois champs :
  `private boolean autoEnabled = true;`,
  `private String autoCron = "0 0 8 * * *";`,
  `private int autoCooldownDays = 3;`.

- [ ] `backend/src/main/resources/application.yml` : sous le bloc `app.reminder`
  existant, ajouter :
  ```yaml
  reminder:
    default-template: |
      ...
    window-start-day: 1
    window-end-day: 10
    auto-enabled: ${REMINDER_AUTO_ENABLED:true}
    auto-cron: "${REMINDER_AUTO_CRON:0 0 8 * * *}"
    auto-cooldown-days: ${REMINDER_AUTO_COOLDOWN_DAYS:3}
  ```
  (guillemets obligatoires autour de la valeur par defaut du cron : contient
  des espaces).

- [ ] `backend/src/main/java/com/creditflow/audit/repository/AuditLogRepository.java` :
  ajouter la derived query
  `boolean existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter(String entityType, Long entityId, String action, LocalDateTime after);`
  (import `java.time.LocalDateTime`).

- [ ] `backend/src/main/java/com/creditflow/notification/service/LateCustomerService.java` :
  - Ajouter la surcharge
    `@Transactional(readOnly = true) public List<LateCustomerResponse> lateCustomers(List<Long> shopIds, Long organizationId)`
    qui contient le corps actuel de `lateCustomers(List<Long> shopIds)`
    (remplacer l'appel a `currentShopContext.currentOrganizationId()` par le
    parametre `organizationId`).
  - Faire de `lateCustomers(List<Long> shopIds)` un simple delegant :
    `return lateCustomers(shopIds, currentShopContext.currentOrganizationId());`
    Aucun appelant HTTP existant (`ReminderController.lateCustomers`,
    `ReminderService.sendAll`) ne change de signature ni de comportement.

- [ ] `backend/src/test/java/com/creditflow/notification/service/LateCustomerServiceTest.java` :
  ajuster les deux tests existants qui appellent
  `installmentRepository.findLateForShops(any(), eq(shopIds), eq(100L))` pour
  couvrir explicitement les deux entrees (`lateCustomers(shopIds)` delegue
  bien vers `lateCustomers(shopIds, 100L)` via
  `currentShopContext.currentOrganizationId()`) ; ajouter un test dedie
  `lateCustomersWithExplicitOrganizationIdBypassesCurrentShopContext()` qui
  appelle directement la nouvelle surcharge avec un `organizationId` explicite
  (ex. `999L`) sans jamais stubber `currentShopContext.currentOrganizationId()`
  ni verifier d'interaction dessus (`verifyNoInteractions(currentShopContext)`)
  — preuve que le chemin planifie ne depend d'aucun utilisateur authentifie.

- [ ] `backend/src/main/java/com/creditflow/notification/service/ReminderService.java` :
  - Refactorer `doSend(Customer, BigDecimal, String)` en
    `doSend(Customer customer, BigDecimal amount, String message, boolean automatic)`,
    avec le detail d'audit `"Canal " + notificationChannel.name() + (automatic ? " (auto)" : "")`.
    `send()` et `sendAll()` continuent d'appeler ce point unique avec
    `automatic = false` (comportement et libelle d'audit inchanges pour ces
    deux chemins).
  - Ajouter `@Transactional public ReminderResponse sendAutomatic(Long customerId)` :
    `requireAutomaticChannel()`, puis `prepareForCustomer(customerId, null)`
    (gabarit par defaut, meme convention que `sendAll(null)`), puis
    `doSend(preview.customer(), preview.amount(), preview.message(), true)`.
    Ne depend d'aucun `CurrentShopContext`/`SecurityContextHolder`
    (`prepareForCustomer` ne consulte ni l'un ni l'autre).

- [ ] `backend/src/test/java/com/creditflow/notification/service/ReminderServiceTest.java` :
  - Ajouter des tests pour `sendAutomatic()` : refuse le canal
    `MANUAL_COPY` (meme assertion que `sendRejectsManualChannel`) ; historise
    un succes avec le detail d'audit se terminant par `"(auto)"` ; historise
    un echec avec le meme suffixe.
  - Ajouter une assertion de non-regression sur `send()`/`sendRecordsSuccess()` :
    le detail d'audit enregistre par un envoi manuel ne contient PAS le
    suffixe `"(auto)"` (verrouille la distinction manuel/auto dans le journal).

- [ ] `backend/src/main/java/com/creditflow/notification/service/ReminderSchedulerJob.java`
  (nouveau) :
  ```java
  @Slf4j
  @Component
  @RequiredArgsConstructor
  public class ReminderSchedulerJob {

      private final AppProperties properties;
      private final NotificationChannel notificationChannel;
      private final OrganizationRepository organizationRepository;
      private final ShopRepository shopRepository;
      private final LateCustomerService lateCustomerService;
      private final ReminderService reminderService;
      private final AuditLogRepository auditLogRepository;

      @Scheduled(cron = "${app.reminder.auto-cron}", zone = "${TZ:Africa/Dakar}")
      public void run() {
          if (!properties.getReminder().isAutoEnabled()) {
              log.info("Relances automatiques desactivees (app.reminder.auto-enabled=false).");
              return;
          }
          if (ManualCopyChannel.NAME.equals(notificationChannel.name())) {
              log.info("Aucun canal automatique configure : relances planifiees ignorees.");
              return;
          }
          for (Organization organization : organizationRepository.findAll()) {
              runForOrganization(organization);
          }
      }

      private void runForOrganization(Organization organization) {
          List<Shop> shops = shopRepository
                  .findAllByActiveTrueAndOrganizationIdOrderByNameAsc(organization.getId());
          if (shops.isEmpty()) {
              log.debug("Organisation {} sans boutique active : ignoree.", organization.getId());
              return;
          }
          TenantContext.set(organization.getId());
          try {
              processOrganization(organization, shops.stream().map(Shop::getId).toList());
          } catch (Exception e) {
              log.error("Echec de la tache de relance pour l'organisation {} : {}",
                      organization.getId(), e.getMessage(), e);
          } finally {
              TenantContext.clear();
          }
      }

      private void processOrganization(Organization organization, List<Long> shopIds) {
          int attempted = 0, sent = 0, skipped = 0, failed = 0;
          int cooldownDays = properties.getReminder().getAutoCooldownDays();
          LocalDateTime since = LocalDateTime.now().minusDays(cooldownDays);

          for (LateCustomerResponse lateCustomer :
                  lateCustomerService.lateCustomers(shopIds, organization.getId())) {
              attempted++;
              if (auditLogRepository.existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter(
                      "CUSTOMER", lateCustomer.customerId(), "REMINDER_SENT", since)) {
                  skipped++;
                  continue;
              }
              try {
                  ReminderResponse response = reminderService.sendAutomatic(lateCustomer.customerId());
                  if (response.sent()) sent++; else failed++;
              } catch (Exception e) {
                  log.warn("Echec de la relance automatique pour le client {} (organisation {}) : {}",
                          lateCustomer.customerId(), organization.getId(), e.getMessage());
                  failed++;
              }
          }
          log.info("Organisation {} : {} tentes, {} envoyes, {} ignores (cooldown), {} echoues.",
                  organization.getId(), attempted, sent, skipped, failed);
      }
  }
  ```
  Points imperatifs a respecter dans l'implementation :
  - `TenantContext.set`/`clear` toujours en `try/finally`, jamais de fuite
    entre deux organisations meme en cas d'exception.
  - Une exception levee pour une organisation (ex. erreur JDBC) ne doit
    jamais interrompre le traitement des organisations suivantes (try/catch
    autour de `processOrganization`, pas autour de la boucle globale).
  - Une exception levee pour un client (ex. `sendAutomatic` qui echoue) ne
    doit jamais interrompre le traitement des clients suivants de la meme
    organisation (meme patron que `ReminderService.sendAll`).
  - Le controle `autoEnabled`/canal manuel se fait une seule fois, avant la
    boucle sur les organisations (le canal est un bean applicatif unique,
    pas configurable par organisation).

- [ ] `backend/src/test/java/com/creditflow/notification/service/ReminderSchedulerJobTest.java`
  (nouveau, Mockito, meme style que `ReminderServiceTest`) :
  - `autoEnabled=false` : `run()` ne touche ni `organizationRepository` ni
    `lateCustomerService` (`verifyNoInteractions`).
  - Canal `MANUAL_COPY` : `run()` ne touche ni `organizationRepository` ni
    `lateCustomerService`.
  - Une organisation sans boutique active (`shopRepository...` renvoie une
    liste vide) : `lateCustomerService.lateCustomers(...)` jamais appele pour
    cette organisation.
  - Deux organisations, chacune avec des clients en retard : verifier que
    `lateCustomerService.lateCustomers(shopIds, orgId)` est appele avec le
    bon `organizationId` pour chacune, et que `TenantContext.get()` renvoie
    bien l'id de l'organisation courante au moment de l'appel (via
    `doAnswer` capturant `TenantContext.get()` pendant l'invocation du mock)
    puis que `TenantContext.get()` est `null` apres `run()` (pas de fuite en
    sortie de methode).
  - Cooldown : pour un client dont
    `auditLogRepository.existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter(...)`
    renvoie `true`, `reminderService.sendAutomatic` n'est jamais appele pour
    ce client ; pour un autre client du meme lot ou la methode renvoie
    `false`, `sendAutomatic` est appele.
  - Isolation des echecs : `sendAutomatic` leve une exception pour un client
    -> les clients suivants du meme lot sont quand meme traites (pas
    d'exception propagee hors de `processOrganization`).
  - Isolation entre organisations : une exception levee pendant le
    traitement d'une organisation (ex. `lateCustomerService.lateCustomers`
    qui leve) n'empeche pas le traitement de l'organisation suivante.
  - `TenantContext.clear()` est bien appele meme si une exception est levee
    pendant le traitement d'une organisation (verifier `TenantContext.get()`
    null apres, ou verifier via un spy/compteur).

- [ ] `backend/src/test/java/com/creditflow/notification/service/ReminderSchedulerJobMultiTenantIT.java`
  (nouveau, integration Testcontainers, meme patron que
  `RowLevelSecurityHibernateIT` : `@SpringBootTest`, `PostgreSQLContainer`,
  `Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), ...)`,
  migration Flyway avec le role applicatif RLS) :
  - Prepare deux organisations distinctes, chacune avec une boutique active
    et un client ayant une echeance en retard (donnees inserees en SQL brut
    comme dans `RowLevelSecurityHibernateIT`, canal notification force a une
    implementation de test factice non-manuelle via
    `@TestConfiguration`/`@Primary` `NotificationChannel` qui retourne
    toujours `true` et enregistre les appels).
  - Execute directement `reminderSchedulerJob.run()` (pas d'attente du
    cron).
  - Verifie que le client de l'organisation A a recu une relance (entree
    `AuditLog` action `REMINDER_SENT` avec le detail `(auto)`) et que le
    client de l'organisation B egalement, et que chacun n'a jamais "vu" les
    donnees de l'autre organisation pendant son propre traitement (assertion
    sur le contenu de `lateCustomerService.lateCustomers` obtenu pour
    chaque organisation, ou sur le nombre exact d'entrees `AuditLog` cote
    lignes RLS visibles apres coup avec un role admin).
  - Ce test couvre directement le critere d'acceptation "cloisonnement par
    boutique respecte par la tache planifiee" de bout en bout (au lieu de
    ne le verifier qu'au niveau de la requete SQL comme
    `RowLevelSecurityHibernateIT`).

## Contrat technique

### Configuration (`application.yml` / variables d'environnement)

| Propriete | Variable d'env | Defaut | Description |
|---|---|---|---|
| `app.reminder.auto-enabled` | `REMINDER_AUTO_ENABLED` | `true` | Coupe-circuit operationnel : si `false`, `ReminderSchedulerJob.run()` ne fait rien. |
| `app.reminder.auto-cron` | `REMINDER_AUTO_CRON` | `0 0 8 * * *` | Expression cron Spring (6 champs), executee dans le fuseau `TZ` (defaut `Africa/Dakar`, meme convention que le reste de l'app). |
| `app.reminder.auto-cooldown-days` | `REMINDER_AUTO_COOLDOWN_DAYS` | `3` | Fenetre de non-renvoi automatique par client, en jours, basee sur la derniere entree `AuditLog` action `REMINDER_SENT` pour ce client (source manuelle ou automatique confondues). Ne s'applique jamais aux endpoints manuels `/api/reminders/send` et `/send-all`. |

### `ReminderService` — nouvelle methode

```java
@Transactional
public ReminderResponse sendAutomatic(Long customerId)
```
- Precondition : leve `BusinessRuleException` si le canal configure est
  `ManualCopyChannel.NAME` (`requireAutomaticChannel()`, comportement
  identique a `send()`/`sendAll()`).
- Utilise le gabarit par defaut (`template = null`, meme convention que
  `sendAll(null)`).
- Ecrit une ligne `AuditLog` (action `REMINDER_SENT` ou `REMINDER_FAILED`,
  entite `CUSTOMER`) dont le champ `details` se termine par le suffixe
  `" (auto)"`, pour distinguer un envoi planifie d'un envoi manuel dans
  `/api/audit-logs`.
- Ne consulte ni `CurrentShopContext` ni `SecurityContextHolder` :
  utilisable depuis un thread sans utilisateur authentifie.

### `LateCustomerService` — nouvelle surcharge

```java
@Transactional(readOnly = true)
public List<LateCustomerResponse> lateCustomers(List<Long> shopIds, Long organizationId)
```
- Comportement identique a `lateCustomers(List<Long> shopIds)`, mais
  l'organisation est fournie explicitement plutot que resolue via
  `CurrentShopContext.currentOrganizationId()`.
- `lateCustomers(List<Long> shopIds)` devient un delegant strict vers cette
  surcharge : aucun appelant HTTP existant ne change de comportement.

### `AuditLogRepository` — nouvelle requete

```java
boolean existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter(
        String entityType, Long entityId, String action, LocalDateTime after);
```
Utilisee par `ReminderSchedulerJob` avec
`entityType = "CUSTOMER"`, `action = "REMINDER_SENT"`,
`after = LocalDateTime.now().minusDays(autoCooldownDays)`. Seuls les envois
**reussis** comptent dans la fenetre de cooldown : un `REMINDER_FAILED`
recent ne bloque pas une nouvelle tentative le jour suivant (un echec
technique ne doit pas priver durablement un client de relance).

### `ReminderSchedulerJob` (nouveau composant)

- `@Scheduled(cron = "${app.reminder.auto-cron}", zone = "${TZ:Africa/Dakar}")`
  sur `run()`, aucun parametre, aucune valeur de retour.
- Aucun endpoint HTTP, aucun DTO expose : composant interne uniquement.
- Aucune modification cote frontend, aucune modification des endpoints
  `/api/reminders/*` existants (hors comportement interne de `doSend`, dont
  la signature publique via `send()`/`sendAll()` ne change pas).

## Plan de tests

| Critere d'acceptation du ticket | Test(s) couvrant le critere |
|---|---|
| Une echeance en retard declenche une relance automatique sous 24h sans action humaine | Manuel/configuration : cron par defaut `0 0 8 * * *` (quotidien) documente et verifiable par lecture de `application.yml` ; `ReminderSchedulerJobTest` (deux organisations avec clients en retard -> `sendAutomatic` bien appele) ; `ReminderSchedulerJobMultiTenantIT` (execution reelle de bout en bout, `AuditLog` `REMINDER_SENT` cree). La garantie "sous 24h" en tant que telle depend de la frequence du cron (quotidien = pire cas 24h), verifiee par lecture de configuration, pas par un test automatise (pas de sens de faire tourner un test 24h). |
| Aucun double envoi si la tache planifiee est relancee ou si le declenchement manuel est utilise en parallele | `ReminderSchedulerJobTest` (cas cooldown : `existsBy...=true` -> `sendAutomatic` jamais appele) ; `ReminderServiceTest` (le detail d'audit ecrit par `send()`/`sendAll()` alimente la meme table que `sendAutomatic()`, donc un envoi manuel bloque bien le job du lendemain — verifie indirectement par le fait que `existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter` interroge `action = "REMINDER_SENT"` peu importe la source) ; `ReminderSchedulerJobMultiTenantIT` peut inclure un scenario "un `AuditLog REMINDER_SENT` existe deja avant `run()`" -> aucune nouvelle entree creee pour ce client. |
| Le declenchement manuel existant continue de fonctionner sans regression | `ReminderServiceTest` existant (inchange dans son comportement observable : `send()`, `sendAll()`, gestion d'erreur par client) + nouvelle assertion que le detail d'audit d'un envoi manuel ne contient pas `"(auto)"` ; `ReminderControllerSecurityTest` existant (inchange, aucune modification des endpoints) ; structurellement, `send()`/`sendAll()` n'appellent jamais `AuditLogRepository.existsBy...` (le cooldown n'existe que dans `ReminderSchedulerJob`), donc aucune regression possible sur le declenchement manuel par construction. |
| Le cloisonnement par boutique (multi-boutiques) est respecte par la tache planifiee | `LateCustomerServiceTest` (nouvelle surcharge testee independamment de `CurrentShopContext`) ; `ReminderSchedulerJobTest` (verification que `TenantContext` est positionne/efface correctement par organisation, aucune fuite) ; `ReminderSchedulerJobMultiTenantIT` (test d'integration reel avec RLS Postgres active, deux organisations, verifie qu'aucune relance n'est envoyee a un client d'une autre organisation). |

## Ecarts identifies

- **Concurrence multi-instance non traitee.** Ni le ticket ni le design
  n'abordent le cas de plusieurs instances backend actives simultanement
  (scale horizontal). `@Scheduled` sans verrou distribue (ex. verrou
  consultatif Postgres, `ShedLock`) declencherait `run()` en parallele sur
  chaque instance a la meme heure, et la lecture-puis-ecriture du cooldown
  (`existsBy...` puis `doSend`) n'est pas atomique : deux instances
  pourraient toutes deux lire "pas de relance recente" avant que l'une des
  deux n'ecrive son `AuditLog`, provoquant un double envoi que le mecanisme
  de cooldown ne peut pas empecher dans ce scenario precis. **Non bloquant
  pour ce ticket** : `docker-compose.yml` du depot ne deploie qu'une seule
  instance `backend` (pas de replicas), donc le risque ne se materialise pas
  dans le mode de deploiement actuel documente. A tracer explicitement (ex.
  commentaire dans `ReminderSchedulerJob` ou ticket de suivi) si une
  evolution vers plusieurs instances est envisagee.
- **`autoCooldownDays` par defaut (3 jours) reste un choix produit non
  specifie par le ticket**, comme deja signale par l'architecte. Cette spec
  fixe la valeur par defaut a 3 jours et la rend configurable
  (`REMINDER_AUTO_COOLDOWN_DAYS`) : a confirmer par le produit avant mise en
  production si ce rythme ne convient pas, sans necessiter de changement de
  code (juste la variable d'environnement).
- **Heure du cron par defaut (08:00) et fuseau.** Fixee dans cette spec via
  `zone = "${TZ:Africa/Dakar}"` sur `@Scheduled`, alignee sur la convention
  deja utilisee pour Hibernate (`spring.jpa.properties.hibernate.jdbc.time_zone`)
  et sur `docker-compose.yml` (`TZ: ${TZ:-Africa/Dakar}`). Aucun test
  automatise ne verifie le fuseau reel en production (depend de la variable
  d'environnement du conteneur deploye) : verification manuelle recommandee
  apres deploiement.
