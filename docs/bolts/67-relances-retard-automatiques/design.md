# Design - #67 Relances de retard reellement automatiques

## Approche

Ajouter un @Scheduled (Spring) qui rejoue quotidiennement la meme logique que
ReminderService.sendAll / LateCustomerService.lateCustomers, mais iteree
explicitement sur toutes les organisations actives (pas sur l'organisation
d'un utilisateur connecte, qui n'existe pas dans un job planifie). Le garde-fou
anti-doublon/anti-harcelement reutilise le journal d'audit existant
(AuditLog avec action = "REMINDER_SENT", deja ecrit par tout envoi manuel
aujourd'hui) plutot que de creer une nouvelle table : une fenetre de non-renvoi
de N jours par client, calculee sur ce journal, couvre a la fois "la tache est
relancee deux fois" et "manuel + planifie se chevauchent", au prix d'une seule
requete simple ajoutee au AuditLogRepository. Le point le plus delicat n'est
pas l'ordonnancement en lui-meme mais le multi-tenant par base partagee a
policies (RLS Postgres, cf. TenantConnectionConfig) : hors requete HTTP, rien
ne positionne TenantContext, donc le job doit le faire lui-meme, organisation
par organisation, exactement comme le fait deja DemoDataSeeder. Cout accepte :
le job doit boucler organisation par organisation avec un TenantContext.set/clear
explicite autour de chaque iteration, ce qui est un peu plus verbeux qu'un
simple findAll() global mais c'est le seul chemin deja valide dans ce code
pour traverser plusieurs tenants en toute securite.

## Fichiers/modules impactes

Backend (backend/src/main/java/com/creditflow/) :

- CreditFlowApplication.java - ajouter @EnableScheduling (aucune tache
  planifiee n'existe aujourd'hui dans le code, confirme par recherche
  exhaustive de @Scheduled).
- notification/service/ReminderSchedulerJob.java (nouveau) - le @Scheduled
  quotidien. Boucle sur OrganizationRepository.findAll(), pour chaque
  organisation avec au moins une boutique active
  (ShopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc) :
  TenantContext.set(orgId) dans un try/finally avec TenantContext.clear(),
  recupere les clients en retard, filtre ceux deja relances recemment, envoie
  via ReminderService, logge un recap (nb tentes / envoyes / ignores /
  echoues) par organisation.
- notification/service/ReminderService.java - ajouter une methode
  sendAutomatic(Long customerId) (variante de send/doSend sans dependance a
  une requete HTTP) ; factoriser doSend pour qu'elle reste le point unique
  d'ecriture de l'audit REMINDER_SENT/REMINDER_FAILED (deja le cas
  aujourd'hui, a conserver).
- notification/service/LateCustomerService.java - ajouter une surcharge
  lateCustomers(List<Long> shopIds, Long organizationId) prenant
  l'organisation explicitement, pour ne pas dependre de
  CurrentShopContext.currentOrganizationId() (qui exige un utilisateur
  authentifie via SecurityContextHolder, absent dans un thread de
  scheduler). La signature existante lateCustomers(List<Long> shopIds)
  devient un simple delegant vers la nouvelle surcharge avec
  currentShopContext.currentOrganizationId(), donc aucun appelant HTTP
  existant ne change de comportement.
- audit/repository/AuditLogRepository.java - ajouter
  boolean existsByEntityTypeAndEntityIdAndActionAndCreatedAtAfter(String entityType, Long entityId, String action, LocalDateTime after)
  (derived query Spring Data), utilisee comme garde de non-renvoi.
- config/AppProperties.java - etendre Reminder avec : autoEnabled (bool,
  defaut true, coupe-circuit operationnel sans changer de canal), autoCron
  (String, defaut quotidien, ex. "0 0 8 * * *"), autoCooldownDays (int,
  defaut 3, fenetre de non-renvoi par client).
- src/main/resources/application.yml - bloc app.reminder.auto-* correspondant,
  avec variables d'environnement REMINDER_AUTO_ENABLED, REMINDER_AUTO_CRON,
  REMINDER_AUTO_COOLDOWN_DAYS (meme convention que le reste du fichier).
- Tests (nouveaux, a la charge du codeur/spec-writer) :
  notification/service/ReminderSchedulerJobTest.java, ajustements dans
  notification/service/LateCustomerServiceTest.java et
  notification/service/ReminderServiceTest.java pour la nouvelle surcharge
  et sendAutomatic.

Rien a changer cote frontend (frontend/) : aucun ecran ni endpoint n'est
requis par le ticket, le declenchement manuel existant (/api/reminders/send,
/send-all) reste identique.

## Decisions cles

- Pas de nouvelle table de deduplication. Le journal d'audit existant
  (AuditLog, action REMINDER_SENT) sert de source de verite pour "ce client
  a-t-il ete relance recemment", quelle que soit la source (bouton manuel,
  "envoyer tout", ou job planifie) puisque ReminderService.doSend ecrit deja
  cette ligne pour tout envoi reussi, y compris aujourd'hui. Alternative
  ecartee : une table dediee reminder_dispatch_log - plus explicite mais
  migration + entite supplementaires pour un besoin deja couvert par une
  donnee existante.
- La fenetre de cooldown (autoCooldownDays, defaut 3 jours) ne s'applique
  qu'au declenchement automatique, pas aux endpoints manuels /send et
  /send-all : un gerant qui clique explicitement reste maitre de son geste
  (pas de regression sur le comportement actuel, exige par le critere
  d'acceptation). Le job planifie, lui, consulte le journal ecrit par
  n'importe quelle source (manuel ou auto) avant d'envoyer, donc un envoi
  manuel le matin empeche bien le job du lendemain de renvoyer un doublon
  dans la fenetre - c'est ce mecanisme qui satisfait a la fois "pas de
  doublon si la tache est relancee" et "pas de doublon manuel+planifie en
  parallele".
- Iteration explicite par organisation avec TenantContext.set/clear, au lieu
  d'une seule requete globale toutes-organisations. C'est le seul moyen de
  rester compatible avec l'isolation par Row-Level-Security Postgres pilotee
  par TenantConnectionConfig/TenantContext : sans tenant positionne,
  TenantIdentifierResolver retombe sur NO_TENANT et la session Postgres n'a
  pas de app.current_org_id, donc les requetes ne verraient (ou ne
  devraient voir) aucune ligne. Le patron est deja utilise par
  DemoDataSeeder pour la meme raison.
- Cloisonnement par boutique inchange : le job reutilise
  findLateForShops/LateCustomerService avec les shopIds de l'organisation
  courante (boutiques actives uniquement), donc aucune boutique inactive ni
  d'une autre organisation n'est jamais consideree dans la meme iteration.
- Gate d'activation = canal non-manuel, plus un flag autoEnabled explicite
  en secours. ManualCopyChannel ne peut pas etre pilote sans humain (c'est
  un texte a copier), donc le job ignore silencieusement (log info) les
  organisations dont app.notification.channel=manual plutot que de lever une
  exception a chaque execution planifiee - different du comportement de
  ReminderService.requireAutomaticChannel() qui, lui, doit continuer a
  lever une BusinessRuleException sur les endpoints HTTP manuels
  (comportement inchange).
- Pas d'authentification technique simulee (pas de SecurityContextHolder
  factice a la DemoDataSeeder.authenticateAsAdmin) : en shuntant
  CurrentShopContext via la nouvelle surcharge
  lateCustomers(shopIds, organizationId) et en n'appelant que des methodes
  de ReminderService/CustomerService qui ne dependent pas d'un utilisateur
  courant (prepareForCustomer ne l'utilise pas), le job n'a besoin que de
  TenantContext, pas d'une identite HTTP. AuditLogService reste appelable
  tel quel : CurrentUser.username() est deja null-safe et l'acteur sera
  simplement absent pour les envois automatiques - acceptable et
  distinguable (le detail "Canal ..." peut etre complete d'un suffixe
  "(auto)" pour la tracabilite, a preciser en spec).

## Risques / points d'attention

- RLS mal comprise = fuite ou silence cross-tenant. Si TenantContext n'est
  pas positionne correctement autour de chaque iteration (ou fuite d'une
  organisation a l'autre faute de finally), soit le job ne voit aucune
  ligne (echeances jamais relancees, silencieux), soit - pire - il
  voit/ecrit dans le mauvais tenant. A tester explicitement avec au moins
  deux organisations distinctes ayant chacune des echeances en retard.
- autoCooldownDays par defaut (propose : 3 jours) est un choix produit
  arbitraire non specifie par le ticket. Le critere d'acceptation ne fixe
  qu'un delai de declenchement ("sous 24h") pour la premiere relance, pas la
  cadence de repetition. A confirmer explicitement en spec pour eviter une
  sur-sollicitation (trop court) ou un recouvrement trop lent (trop long) ;
  garder configurable via application.yml/variable d'env pour ajustement
  sans redeploiement de code.
- Echec partiel par client ne doit pas interrompre le lot. Comme
  ReminderService.sendAll le fait deja (try/catch par client dans la
  boucle), le job doit isoler chaque envoi : un numero mal normalise ou une
  erreur WhatsApp pour un client ne doit pas empecher les suivants d'etre
  traites, ni faire echouer la transaction/l'organisation entiere.
- Volume et duree d'execution. Aucune pagination existante sur
  findLateForShops/lateCustomers : pour une organisation avec un grand
  nombre de clients en retard, le job envoie en sequence (meme contrainte
  que sendAll aujourd'hui). Acceptable au vu du volume actuel de
  CreditFlow, mais a garder en tete si le nombre d'organisations/clients
  grossit sensiblement.
- Heure d'execution et fuseau horaire. Le cron par defaut doit etre choisi a
  une heure raisonnable pour le fuseau des boutiques (pas 3h du matin
  serveur si le serveur est en UTC et les boutiques dans un autre fuseau) -
  a verifier avec la config de deploiement reelle plutot que de supposer un
  fuseau.
- Redemarrage/deploiement pendant la fenetre planifiee. Si l'instance
  redemarre juste avant l'heure du cron, l'execution du jour peut etre
  manquee (pas de rattrapage automatique type "run on startup if missed").
  Acceptable pour un P1 sans exigence de haute disponibilite explicite dans
  le ticket, mais a mentionner en spec comme limite connue.

## Hors perimetre

- Rouvrir ou modifier le canal WhatsApp (#5) ou le canal email SMTP (#52)
  eux-memes : ce ticket ne fait que declencher automatiquement les canaux
  deja existants, il ne change ni WhatsAppCloudApiChannel ni
  SmtpEmailChannel/EmailChannel.
- Ajouter un ecran de configuration frontend pour piloter la planification
  (cron, cooldown) : la configuration reste via application.yml/variables
  d'environnement, comme le reste des reglages app.reminder.* /
  app.notification.* existants.
- Notifications autres que la relance de retard (ex. rappel preventif avant
  echeance) : hors perimetre du ticket, qui porte specifiquement sur les
  echeances deja en retard.
- Historique/ecran dedie de suivi des envois automatiques (au-dela du
  journal d'audit deja consultable via /api/audit-logs) : non demande par
  les criteres d'acceptation.
- Fenetre de course (TOCTOU) intra-instance entre le job planifie et un
  declenchement manuel simultane sur le meme client (review #67, finding
  mineur). Le garde-fou anti-doublon (existsByEntityTypeAndEntityIdAndAction
  AndCreatedAtAfter, puis ecriture de l'AuditLog par doSend) n'est pas
  atomique : si un utilisateur declenche /api/reminders/send ou /send-all
  pour un client au meme instant (meme fenetre de quelques dizaines de ms)
  ou le job planifie evalue ce client, les deux threads peuvent lire "pas de
  REMINDER_SENT recent" avant que l'un des deux ait committe son audit log,
  et envoyer chacun une relance. C'est le meme type de risque que la
  concurrence multi-instance deja documentee ci-dessus (lecture-puis-
  ecriture non atomique du cooldown), mais ici entre deux threads de la
  meme instance (scheduler vs HTTP) plutot qu'entre deux instances.
  **Non corrige pour ce ticket** : fenetre tres etroite en pratique (cron
  quotidien a heure fixe, peu de chances qu'un humain declenche /send a la
  meme seconde), et une correction robuste (verrou/contrainte transactionnelle
  sur le cooldown) rejoindrait la meme solution que le cas multi-instance
  (ex. verrou consultatif Postgres) : a traiter ensemble si le besoin de
  fiabilite augmente, plutot que d'ajouter un correctif partiel ici.
