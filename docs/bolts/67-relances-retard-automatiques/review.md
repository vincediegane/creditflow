# Review - #67 Relances de retard reellement automatiques

## Verdict

CHANGES_REQUESTED

## Resume

L'architecture generale (scheduler + cooldown base sur l'audit + surcharge
lateCustomers(shopIds, organizationId) + TenantContext par organisation) est
propre et bien testee au niveau unitaire. Mais le chemin d'execution reel de
ReminderService.sendAutomatic() traverse CustomerService.getEntity(), qui
appelle CurrentShopContext.assertAccessible() -> currentUser(), lequel exige
un Authentication dans le SecurityContextHolder. Le job planifie ne peuple
jamais ce contexte. Resultat : en production, chaque relance automatique
leve IllegalStateException("Aucun utilisateur authentifie"), est rattrapee
par le catch (Exception e) par-client de ReminderSchedulerJob, et compte
comme "echouee" - silencieusement, tous les jours, pour tous les clients. Le
critere d'acceptation numero 1 du ticket (declenchement automatique reel, sans
action humaine) n'est donc pas rempli malgre "431 tests, 0 echec".

Ce bug n'est detecte par aucun test execute par mvn test :
- Les tests unitaires (ReminderServiceTest) mockent entierement
  CustomerService, donc n'exercent jamais le vrai getEntity().
- Le seul test qui l'aurait detecte, ReminderSchedulerJobMultiTenantIT
  (Testcontainers, contexte Spring reel, aucune authentification), est un
  fichier *IT.java : ce pattern n'est pas dans les includes par defaut de
  Surefire (*Test.java, Test*.java, *Tests.java, *TestCase.java), donc
  mvn test ne le compile/execute meme pas comme test.

J'ai confirme empiriquement (hors du repo, script Java jetable compile contre
target/classes, sans toucher au code source) que CurrentUser.username()
retourne null et que currentUser() dans CurrentShopContext leve bien
IllegalStateException en l'absence de tout SecurityContextHolder peuple -
exactement la situation d'un thread @Scheduled.

Le meme piege est deja documente ailleurs dans la base : DemoDataSeeder
authentifie explicitement un utilisateur technique avant d'appeler des
services passant par CurrentShopContext ("les services de creation
resolvent la boutique cible via CurrentShopContext, qui exige un utilisateur
authentifie : le seeding s'execute sous l'identite technique de
l'administrateur"). ReminderSchedulerJob n'applique pas ce meme
contournement.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Une echeance en retard declenche une relance automatique sous 24h, sans action humaine | Non couvert - sendAutomatic() echoue systematiquement en production (voir Findings #1). Le job "tourne" (log "X tentes, 0 envoyes, ... X echoues") mais n'envoie jamais rien. |
| 2 | Aucun double envoi (relance du job planifie, ou declenchement manuel en parallele) | Partiel - la logique de cooldown via AuditLogRepository.existsBy... est correcte et testee au niveau unitaire (couvre bien le cas "le job est relance" et "un envoi manuel precedent bloque l'auto"), mais elle est invalidee en pratique tant que le Finding #1 n'est pas corrige (aucun envoi automatique reussi -> pas de garde-fou a verifier en conditions reelles). Voir aussi Finding #2 (race TOCTOU mineure). |
| 3 | Le declenchement manuel (/send, /send-all) continue de fonctionner sans regression | Couvert - doSend(..., automatic) ne change que le suffixe d'audit et le flag de cooldown n'est jamais consulte sur ces chemins ; send()/sendAll() inchanges fonctionnellement, testes (ReminderServiceTest, suffixe "ne se termine pas par (auto)" verifie explicitement). |
| 4 | Cloisonnement multi-boutiques respecte par la tache planifiee | Couvert au niveau architecture (RLS Postgres pilotee par TenantContext.set/clear, independante de CurrentShopContext/SecurityContextHolder - voir TenantConnectionConfig), et teste unitairement (processesEachOrganizationWithItsOwnTenantContext, isolation par organisation). L'IT Testcontainers dediee existe et est bien concue, mais n'a pas pu etre executee ici (Docker indisponible) ni par mvn test (pattern *IT.java hors des includes Surefire par defaut). |

## Findings

### 1. [BLOQUANT] sendAutomatic() echoue systematiquement en production : depend transitivement de CurrentShopContext/SecurityContextHolder malgre le contrat contraire

- Fichiers/lignes :
  - backend/src/main/java/com/creditflow/notification/service/ReminderService.java:67-71 (sendAutomatic) appelle prepareForCustomer (ligne 159) qui appelle customerService.getEntity(customerId).
  - backend/src/main/java/com/creditflow/customer/service/CustomerService.java:84-89 : getEntity() appelle inconditionnellement currentShopContext.assertAccessible(customer.getShop().getId()).
  - backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java:136-149 : assertAccessible -> accessibleShopIds() -> currentUser(), qui leve IllegalStateException("Aucun utilisateur authentifie") si CurrentUser.username() (base sur SecurityContextHolder) est null.
  - backend/src/main/java/com/creditflow/notification/service/ReminderSchedulerJob.java:39-96 : run()/processOrganization() ne peuplent jamais SecurityContextHolder (seul TenantContext.set/clear est gere), contrairement a ce que fait par exemple DemoDataSeeder (authenticateAsAdmin()) pour un besoin similaire.
- Scenario qui le declenche : le cron s'execute (app.reminder.auto-cron), trouve un client en retard sans reminder recent, appelle reminderService.sendAutomatic(customerId). Dans un thread @Scheduled, aucun Authentication n'est present dans SecurityContextHolder (confirme empiriquement). getEntity() leve alors IllegalStateException, remontee jusqu'au catch (Exception e) de ReminderSchedulerJob.processOrganization (ligne ~88), comptabilisee en "echoue", journalisee en WARN, et silencieusement ignoree. Aucune relance n'est jamais envoyee automatiquement.
- Pourquoi les tests ne l'ont pas vu : ReminderServiceTest mocke CustomerService en totalite (@Mock private CustomerService customerService;), donc n'exerce jamais le vrai getEntity(). Le seul test qui exerce un contexte Spring reel sans authentification, ReminderSchedulerJobMultiTenantIT, est un fichier *IT.java que Surefire n'inclut pas par defaut (mvn test ne le mentionne meme pas dans son log, confirme par grep sur un run complet). Il n'y a pas de .github/workflows dans le repo, donc rien n'indique que cette IT tourne ailleurs non plus.
- Correctif suggere : soit authentifier un principal technique autour de ReminderSchedulerJob.run() (meme pattern que DemoDataSeeder.authenticateAsAdmin(), avec nettoyage en finally), soit - plus propre puisque le contrat annonce explicitement "ne consulte pas CurrentShopContext" - faire en sorte que sendAutomatic() n'appelle pas customerService.getEntity() mais recupere le client via un chemin qui ne fait pas de verification d'acces utilisateur (par ex. customerRepository.findById directement, la frontiere multi-tenant etant deja assuree par la RLS pilotee par TenantContext).

### 2. [MINEUR/INFORMATIF] Fenetre de course (TOCTOU) entre sendAutomatic planifie et send/sendAll manuel simultanes sur le meme client

- Fichier/ligne : backend/src/main/java/com/creditflow/notification/service/ReminderSchedulerJob.java:80-92 (existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter puis, hors de toute transaction partagee, reminderService.sendAutomatic(...)).
- Scenario : si un utilisateur declenche manuellement /api/reminders/send pour un client au meme instant (meme fenetre de quelques dizaines de ms) ou le job planifie evalue ce meme client, les deux threads peuvent lire "pas de REMINDER_SENT recent" avant que l'un des deux ait committe son audit log, et envoyer chacun une relance - un vrai double envoi, sur une seule instance, sans qu'il soit necessaire d'avoir plusieurs instances backend. Le design.md ne documente que le cas "multi-instance sans verrou distribue" comme ecart accepte ; cette course intra-instance (thread scheduler vs thread HTTP) n'est pas couverte par cette justification et n'est pas testee.
- Impact : fenetre tres etroite (cron quotidien a 8h, peu de chances qu'un humain declenche /send a la meme seconde), donc plutot a documenter que bloquant en soi - mais ce n'est actuellement ni mentionne ni teste, alors que le critere d'acceptation #2 du ticket parle explicitement de ce cas ("declenchement manuel utilise en parallele").

## Build/tests

- cd backend && mvn test (suite complete) : 431 tests, 0 echec, BUILD SUCCESS - confirme le chiffre rapporte par le codeur. Log complet grepe pour verifier qu'aucune classe *IT.java (ReminderSchedulerJobMultiTenantIT, RowLevelSecurityIT, RowLevelSecurityHibernateIT) n'y apparait : confirme, ces classes ne sont pas executees par mvn test (pattern Surefire par defaut, convention deja existante depuis le bolt #40, pas une regression de ce bolt).
- cd backend && mvn test -Dtest=ReminderServiceTest,ReminderSchedulerJobTest,LateCustomerServiceTest,ReminderSchedulerJobMultiTenantIT : 21 tests unitaires OK (LateCustomerServiceTest 3, ReminderSchedulerJobTest 9, ReminderServiceTest 9), ReminderSchedulerJobMultiTenantIT = 0 test execute (assumption Docker non disponible dans cet environnement : "Could not find a valid Docker environment") - ignore proprement comme prevu par le design, mais du coup jamais verifie ici.
- Verification hors-repo (script Java jetable, compile contre backend/target/classes + classpath Maven, aucun fichier source du projet modifie) : confirme que CurrentUser.username() retourne null et que la levee d'IllegalStateException par CurrentShopContext.currentUser() se produit bien en l'absence de tout SecurityContextHolder peuple, exactement la situation d'un thread @Scheduled - preuve empirique du Finding #1.
- Frontend : aucun fichier frontend touche par ce bolt (confirme par git diff master --stat), pas de build frontend necessaire.

## Recommandation

CHANGES_REQUESTED tant que le Finding #1 n'est pas corrige : c'est un bug qui
annule completement l'effet du ticket en production (le critere d'acceptation
principal - "relance automatique reelle, sans action humaine" - n'est pas
rempli), sans qu'aucun test execute par mvn test ne le revele. Le Finding #2
devrait au minimum etre documente comme ecart accepte (comme le cas
multi-instance l'est deja) si non corrige, plutot que silencieusement absent
du design.
