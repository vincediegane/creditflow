# Review — #40 Multi-tenant 7/10 — Défense en profondeur Postgres Row-Level Security

## CHANGES_REQUESTED

Le verdict porte uniquement sur l'absence de toute exécution réussie du mécanisme central de
sécurité de ce ticket, pas sur un défaut identifié dans le code ou le SQL — la relecture statique
(détaillée ci-dessous) n'a trouvé aucune erreur de jointure, de nom de colonne, ni d'incohérence
de logique. C'est précisément parce que la relecture statique ne trouve rien à redire sur un
mécanisme jamais éprouvé qu'elle ne suffit pas à elle seule sur un ticket de sécurité : voir
Finding #1.

## Résumé de la vérification effectuée

- git diff master...HEAD --stat relu intégralement (31 fichiers, 2311 insertions / 43 suppressions).
- Les 3 migrations (V14/V15/V16) relues ligne par ligne et confrontées aux schémas réels V1, V3,
  V6, V9, V10, V13 : tous les noms de colonnes/tables référencés dans les policies RLS existent
  bel et bien avec la forme attendue (shop_id, sale_id, organization_id, reception_id/product_id).
  Aucune erreur de jointure trouvée.
- AuthService.java, CurrentShopContext.java, TenantContext.java, TenantContextFilter.java,
  TenantConnectionConfig.java, SecurityConfig.java, DemoDataSeeder.java, AdminInitializer.java,
  StockReception(Service).java relus intégralement.
- Écart audit_log dans les GRANT de V16 vérifié : recherche des appelants de
  AuditLogService.record( confirme exactement les 6 appelants cités par le codeur
  (PaymentService, CreditSaleService, ProductService, CustomerService, ReminderService,
  PenaltySettingsService). Le raisonnement est correct : audit_log reste hors RLS (aucune policy
  créée pour elle en V15) mais a bien besoin du GRANT pour continuer à recevoir des écritures une
  fois le rôle applicatif restreint en place. Écart justifié, documenté, pas à corriger.
- docker-compose.yml / .env.example / .env.production.example relus : séparation
  DB_APP_* / DB_MIGRATION_* cohérente. Le service backup a été correctement basculé sur le rôle
  de migration (pas le rôle applicatif restreint), sans quoi les sauvegardes seraient elles-mêmes
  filtrées par RLS - bon réflexe, non demandé explicitement par le ticket.
  docker compose config exécuté avec succès (résolution statique du YAML, sans démarrage réel).
- Les 4 fichiers de test adaptés non listés explicitement par la spec
  (AbstractWebMvcSecurityTest, ReminderControllerSecurityTest, DemoDataSeederTest,
  StockReceptionServiceTest) : diffs minimaux et mécaniques (nouveau paramètre de constructeur,
  nouveau mock), rien qui masquerait un problème.
- mvn -o clean test avec les tests Testcontainers exclus, relancé moi-même : 384/384 verts,
  BUILD SUCCESS, confirme le rapport du codeur.

## Priorité absolue : tentative d'exécution des tests Testcontainers

Docker ne fonctionne pas non plus dans mon environnement de review, avec exactement le même
symptôme que celui rapporté par le codeur : le binaire docker est présent et répond
(docker --version retourne Docker version 29.1.2), mais
DockerClientFactory.instance().isDockerAvailable() ne retourne jamais. J'ai confirmé cela
empiriquement, pas seulement en attendant un timeout arbitraire : j'ai lancé le test
RowLevelSecurityIT via Maven en arrière-plan, attendu, puis pris un thread dump (jstack) du
process Surefire pendant qu'il était bloqué. Le thread principal était bloqué sur un appel HTTP
réel vers le daemon Docker (chaîne d'appels docker-java jusqu'à
HttpRequestExecutor.execute -> CompletableFuture.get() en attente indéfinie d'une réponse), pas
sur une erreur de connexion rapide. docker compose config fonctionne (résolution YAML statique,
n'a pas besoin du daemon), mais toute commande qui a réellement besoin de parler au daemon
(docker info, isDockerAvailable()) reste bloquée sans jamais retourner d'erreur. J'ai dû tuer les
process Java (taskkill /F) pour les arrêter - ils ne se seraient pas terminés seuls.

Conséquence : je n'ai pas pu exécuter avec succès RowLevelSecurityIT ni
RowLevelSecurityHibernateIT, ni valider empiriquement les points 2/3/4 de la section "Zones
d'incertitude technique" de spec.md (routage réel de toutes les acquisitions de connexion
Hibernate via TenantAwareConnectionProvider, nécessité ou non d'une propriété
hibernate.multiTenancy supplémentaire, granularité réelle de getConnection(tenantId)).

Point aggravant découvert pendant cette review, absent du rapport du codeur : j'ai vérifié quels
tests, parmi les 384 verts, chargent réellement un contexte Spring complet avec Hibernate et une
base de données (condition nécessaire pour que TenantConnectionConfig /
HibernatePropertiesCustomizer soit ne serait-ce qu'instancié). Résultat : RowLevelSecurityHibernateIT
(jamais exécuté avec succès) est la seule classe de test de tout le module backend annotée
@SpringBootTest ; il n'existe aucun @DataJpaTest, aucune dépendance H2, et tous les autres tests
touchant la couche web sont des @WebMvcTest (tranches qui excluent l'auto-configuration
JPA/Hibernate par construction - confirmé sur PaymentControllerSecurityTest notamment).
Autrement dit : le bean HibernatePropertiesCustomizer et les deux composants
TenantAwareConnectionProvider / TenantIdentifierResolver n'ont jamais été chargés par Spring dans
un seul test exécuté avec succès sur cette branche, dans aucun environnement de ce pipeline (ni
chez le codeur, ni ici). Le seul signal positif disponible est indirect : la compilation réussit,
et le rapport du codeur affirme une vérification javap de la signature générique (non re-vérifiée
indépendamment par moi faute d'accès pratique à l'artefact hibernate-core résolu). Ce n'est pas
rien, mais ça ne prouve ni le bon fonctionnement du provider à l'exécution, ni l'absence de fuite
(AC2), ni que RLS bloque effectivement un accès direct (AC1).

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| AC1 | Requête SQL directe isolée par organisation | Partiel - couvert par du code (V15, rôle applicatif V16) et par un test dédié (RowLevelSecurityIT, méthode ac1_directSqlAccessIsIsolatedPerOrganization) qui échouerait bien si le code était retiré (assertions de comptage précises par organisation), mais ce test n'a jamais été exécuté avec succès dans ce pipeline. Relecture statique du SQL : cohérente, aucune erreur trouvée. |
| AC2 | Pas de fuite entre tenants sur connexion physique réutilisée | Partiel - même situation : RowLevelSecurityIT (méthode ac2, réutilise littéralement le même java.sql.Connection) et RowLevelSecurityHibernateIT (pool Hikari taille 1, emprunte la connexion physique directement après un release Hibernate pour vérifier le RESET) sont bien conçus pour exactement ce scénario, mais jamais exécutés avec succès. TenantConnectionConfigTest (Mockito, vert) valide seulement que set_config/RESET sont appelés, pas qu'ils produisent l'effet attendu sur une vraie connexion Postgres partagée. |
| AC3 | Instance mono-tenant : comportement identique | Partiel - RowLevelSecurityIT (méthode monoTenantNonRegression) couvre exactement ce cas (le plus dangereux selon le design : une erreur de policy casserait silencieusement l'accès aux données existantes), jamais exécutée. Aucune régression détectée par ailleurs sur les 384 tests Mockito/MockMvc existants, mais ceux-ci ne passent pas par une vraie session Hibernate/Postgres et ne peuvent donc pas, de toute façon, détecter une régression RLS. |

## Findings

### 1. [BLOQUANT] Le mécanisme central de sécurité du ticket n'a jamais tourné avec succès nulle part dans le pipeline

- Où : backend/src/test/java/com/creditflow/security/rls/RowLevelSecurityIT.java (242 lignes),
  RowLevelSecurityHibernateIT.java (169 lignes), et transitivement
  backend/src/main/java/com/creditflow/config/TenantConnectionConfig.java.
- Scénario concret qui n'est pas couvert : un bug de signe dans une jointure
  (organization_id = app_current_org_id() inversé par erreur, ou une policy équivalente à
  USING (true) par erreur de copier-coller), une régression Hibernate qui court-circuiterait
  TenantAwareConnectionProvider pour certains flux (batch insert, getReferenceById, etc. -
  exactement le point d'incertitude technique n3 de spec.md), ou un défaut du RESET qui laisserait
  app.current_org_id positionné sur une connexion rendue au pool - aucun de ces bugs ne serait
  détecté aujourd'hui par la suite verte, puisqu'aucun test exécuté avec succès ne charge le
  SessionFactory Hibernate réel avec un vrai Postgres.
- Pourquoi c'est bloquant pour ce ticket précisément : c'est un ticket de sécurité dont l'unique
  raison d'être est de fournir une garantie qui survit à un bug applicatif (défense en
  profondeur). Fusionner sur la seule foi d'une relecture statique, aussi rigoureuse soit-elle,
  reviendrait à accepter une garantie de sécurité non vérifiée par construction, dans un mécanisme
  que ce backend n'a jamais utilisé auparavant (MultiTenantConnectionProvider), et que spec.md
  qualifie elle-même de "zone d'incertitude technique - à valider par exécution, pas par lecture".
- Recommandation avant merge, indépendamment de qui l'exécute :
  1. Faire tourner RowLevelSecurityIT et RowLevelSecurityHibernateIT sur une machine où Docker
     fonctionne réellement (CI avec service Docker, poste d'un autre développeur, ou WSL2 avec un
     daemon Docker fonctionnel) - sans ce passage, aucune amélioration de code ne peut combler ce
     manque, le problème est environnemental, pas dans le code du bolt.
  2. À défaut de Docker, valider manuellement AC1/AC2 par une procédure docker compose up + psql
     direct suivant le patron exact des deux tests (le SQL y est déjà écrit, il suffit de le
     rejouer à la main une fois contre une vraie instance) avant tout déploiement en production
     avec plusieurs organisations réelles.
  3. Documenter ce point dans le suivi du ticket, pas seulement dans ce fichier, pour qu'il ne soit
     pas silencieusement considéré comme vérifié du seul fait que la PR a été mergée avec un
     badge vert.

### 2. [Mineur] TenantContextFilter n'a aucun test dédié

- Où : backend/src/main/java/com/creditflow/common/security/TenantContextFilter.java (37 lignes,
  aucun test qui l'exerce directement dans backend/src/test/java).
- Le filtre est importé dans AbstractWebMvcSecurityTest pour le câblage de la chaîne de filtres,
  mais aucun test n'affirme explicitement que TenantContext est bien positionné pour un
  utilisateur authentifié, resté vide pour un anonyme, ni que le bloc finally nettoie bien le
  ThreadLocal même si filterChain.doFilter lève une exception (cas réel : une exception
  applicative levée en aval, sur un thread de pool Tomcat qui sera réutilisé pour une requête
  suivante d'un autre tenant). Un test Mockito simple (mock FilterChain qui lève une exception,
  vérifier que TenantContext.get() retourne null après l'appel) coûterait peu et fermerait ce
  doute sans dépendre de Docker.

### 3. [Mineur] .env.example et .env.production.example conservent DB_USERNAME/DB_PASSWORD, désormais sans effet

- Où : .env.example lignes 5-6, .env.production.example lignes 15 et 17.
- Depuis ce bolt, docker-compose.yml ne référence plus DB_USERNAME/DB_PASSWORD (remplacés par
  DB_APP_USERNAME/DB_MIGRATION_USERNAME etc., confirmé par recherche dans docker-compose.yml).
  Un opérateur qui changerait DB_PASSWORD dans son .env en pensant durcir la base croirait avoir
  agi alors que la variable n'est plus lue nulle part. Ce n'est pas un bug fonctionnel (aucun code
  ne lit plus cette variable), mais une source de confusion en exploitation qui mériterait d'être
  supprimée ou clairement annotée comme obsolète dans un futur bolt.

## Build/tests

- Commande : cd backend puis mvn -o clean test avec les classes RowLevelSecurityIT et
  RowLevelSecurityHibernateIT exclues via -Dtest.
  Résultat : BUILD SUCCESS, 384 tests exécutés, 0 échec, 0 erreur, 0 ignoré (vérifié par
  agrégation des fichiers target/surefire-reports/*.txt).
- Commande : docker compose config.
  Résultat : résolution YAML réussie (services backend/db/backup avec la séparation
  DB_APP_* / DB_MIGRATION_* correctement propagée).
- Commande : docker info, puis mvn -Dtest=RowLevelSecurityIT test.
  Résultat : bloqué indéfiniment, confirmé par thread dump (jstack) montrant un appel HTTP réel
  au daemon Docker jamais résolu. Processus arrêtés manuellement (taskkill /F) après confirmation
  du diagnostic. RowLevelSecurityIT et RowLevelSecurityHibernateIT n'ont donc pas pu être
  exécutés avec succès dans cet environnement de review, ni dans celui du codeur.

## Conclusion

Le code et le SQL livrés sont, à la lecture, cohérents avec le schéma existant, avec design.md et
spec.md, et avec les décisions déjà actées par #34 et #36-#39. L'écart documenté sur audit_log est
justifié. Les adaptations de tests annexes sont minimales et honnêtes. Mais un ticket de sécurité
dont le mécanisme central (RLS combiné à un MultiTenantConnectionProvider jamais utilisé
auparavant dans ce backend) n'a été validé par aucune exécution réussie, dans aucun environnement
de ce pipeline, ne peut pas recevoir un APPROVE sans réserve : le risque n'est pas hypothétique,
c'est littéralement la zone que spec.md elle-même désigne comme nécessitant une preuve par
exécution plutôt que par lecture. Le verdict est CHANGES_REQUESTED, avec comme seule action
bloquante réelle : obtenir une exécution réussie de RowLevelSecurityIT et
RowLevelSecurityHibernateIT (ou une vérification manuelle équivalente contre un vrai Postgres)
avant tout merge vers une branche destinée à la production.
