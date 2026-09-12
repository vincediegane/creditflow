# Spec — #68 Valider empiriquement la Row-Level Security multi-tenant sur un Postgres reel

## Résumé

Exécuter `RowLevelSecurityIT` et `RowLevelSecurityHibernateIT` contre un vrai Postgres via Testcontainers, corriger au plus près de la cause tout défaut réel d'isolation révélé, et documenter le résultat de façon traçable dans un nouveau rapport de validation.

## Tâches

- [ ] **T1 — Vérifier les prérequis d'environnement avant toute exécution.**
  Confirmer `docker ps` (daemon actif) et la présence en cache de l'image `postgres:16-alpine`
  (`docker images | findstr postgres` ou équivalent). Si l'un des deux échoue, s'arrêter et
  documenter le blocage dans `docs/bolts/68-valider-rls-postgres-reel/validation-report.md`
  plutôt que de retenter en boucle — ne pas reproduire silencieusement le blocage documenté
  4 fois dans `docs/bolts/40-multitenant-postgres-rls/review.md`.

- [ ] **T2 — Exécuter `RowLevelSecurityIT` isolément.**
  Commande : `cd backend && mvn -o test -Dtest=RowLevelSecurityIT`.
  Capturer la sortie complète (4 tests dans l'ordre `@Order` : `monoTenantNonRegression...`,
  `ac1_directSqlAccessIsIsolatedPerOrganization`, `ac2_noLeakageBetweenConsecutiveTenants...`,
  `organizationPlanRowsAreIsolatedPerOrganization`) — fichier :
  `backend/src/test/java/com/creditflow/security/rls/RowLevelSecurityIT.java`.

- [ ] **T3 — Classer le résultat de T2** selon la grille du design (succès pur / défaut réel
  d'isolation / défaut du test) avant toute action. En cas d'échec, lire le premier test en échec
  dans l'ordre `@Order` (état partagé entre méthodes via champs statiques), pas le dernier.

- [ ] **T4 — Exécuter `RowLevelSecurityHibernateIT` isolément.**
  Commande : `mvn -o test -Dtest=RowLevelSecurityHibernateIT`. Capturer la sortie complète —
  fichier : `backend/src/test/java/com/creditflow/security/rls/RowLevelSecurityHibernateIT.java`.
  Attention : `@SpringBootTest` complet — distinguer un échec de démarrage de contexte Spring
  d'un échec d'assertion RLS avant de conclure.

- [ ] **T5 — Classer le résultat de T4** selon la même grille que T3.

- [ ] **T6 (conditionnelle, uniquement si T3/T5 révèlent une policy RLS manquante ou inversée) —**
  Ajouter une nouvelle migration Flyway `backend/src/main/resources/db/migration/V20__<description>.sql`
  corrigeant la policy concernée. **Ne jamais éditer `V15__row_level_security.sql`** (déjà
  appliquée/versionnée). Voir Écart identifié ci-dessous sur le numéro de version.

- [ ] **T7 (conditionnelle, uniquement si T3/T5 révèlent un GRANT manquant pour le rôle
  applicatif) —** Ajouter une nouvelle migration `backend/src/main/resources/db/migration/V20__<description>.sql`
  (ou `V21` si T6 a déjà consommé V20) avec le `GRANT` manquant. Ne jamais éditer
  `V16__app_role_grants.sql`.

- [ ] **T8 (conditionnelle, uniquement si T3/T5 révèlent que `getConnection`/`releaseConnection`
  ne repositionne/ne réinitialise pas correctement `app.current_org_id`) —** Corriger
  `backend/src/main/java/com/creditflow/config/TenantConnectionConfig.java`
  (`TenantAwareConnectionProvider.getConnection`, lignes 58-62 ; `releaseConnection`/
  `resetTenant`, lignes 65-68 et 94-99).

- [ ] **T9 (conditionnelle, uniquement si T3/T5 révèlent que le découpage transactionnel de
  l'authentification laisse fuiter un contexte tenant) —** Corriger
  `backend/src/main/java/com/creditflow/auth/service/AuthService.java`.

- [ ] **T10 (conditionnelle, uniquement si un défaut révèle que le seed échoue sous
  `FORCE ROW LEVEL SECURITY` faute de `TenantContext` positionné) —** Corriger
  `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` pour positionner le
  tenant avant le seed.

- [ ] **T11 (conditionnelle, uniquement si le défaut identifié est dans le test lui-même —
  hypothèse d'état seed, ordre `@Order`, timing conteneur) —** Corriger uniquement
  `RowLevelSecurityIT.java` ou `RowLevelSecurityHibernateIT.java`, avec un commentaire explicite
  dans le diff/commit expliquant pourquoi ce n'était pas un risque de sécurité réel. Ne jamais
  assouplir une assertion RLS sans avoir prouvé que l'assertion elle-même était fausse.

- [ ] **T12 — Re-exécuter `RowLevelSecurityIT` puis `RowLevelSecurityHibernateIT`** après tout
  correctif (T6-T11), jusqu'à obtenir un succès vert sans skip pour les deux.

- [ ] **T13 — Exécuter la suite complète sans exclusion.**
  Commande : `mvn -o test` (répertoire `backend`, sans `-Dtest` d'exclusion). Vérifier l'absence
  de régression sur les 387 tests existants (+ tout test ajouté par T6-T11).

- [ ] **T14 — Rédiger `docs/bolts/68-valider-rls-postgres-reel/validation-report.md`** (nouveau
  fichier) avec le contenu défini dans le Contrat technique ci-dessous.

- [ ] **T15 — Ajouter un pointeur en fin de `docs/bolts/40-multitenant-postgres-rls/review.md`**
  (ajout seul, ne pas réécrire le contenu existant du document, verdict APPROVE déjà clos)
  renvoyant vers `docs/bolts/68-valider-rls-postgres-reel/validation-report.md` pour lever
  explicitement le "Point résiduel non-bloquant" documenté aux lignes 119-146 de ce fichier.

## Contrat technique

**Commandes d'exécution (ordre imposé par le design, à ne pas paralléliser) :**
```
cd backend
mvn -o test -Dtest=RowLevelSecurityIT
mvn -o test -Dtest=RowLevelSecurityHibernateIT
mvn -o test
```

**Convention de migration Flyway pour tout correctif SQL :** nouveau fichier
`V<N>__<description_snake_case>.sql`, jamais d'édition d'un fichier `V*` existant.

**Structure attendue de `validation-report.md` :**
- En-tête : date, ticket (#68), auteur.
- Section "Environnement" : version Docker (`docker version`), image Postgres utilisée
  (`postgres:16-alpine`, ID/digest si disponible via `docker images`), OS/machine.
- Section "Exécution `RowLevelSecurityIT`" : commande exacte, sortie brute résumée (nombre de
  tests exécutés/verts/rouges/skippés), verdict par méthode de test
  (`monoTenantNonRegression...`, `ac1_...`, `ac2_...`, `organizationPlanRowsAreIsolatedPer...`).
- Section "Exécution `RowLevelSecurityHibernateIT`" : même structure.
- Section "Défauts trouvés et correctifs" : un défaut = un correctif tracé (fichier modifié,
  cause racine, migration Flyway le cas échéant) ; ou mention explicite "aucun défaut" si les
  deux IT passent du premier coup.
- Section "Suite complète (`mvn -o test`)" : résultat brut (nombre de tests, 0 échec attendu).
- Section "Verdict par critère d'acceptation" : tableau AC1/AC2/AC3 du ticket #68 (voir Plan de
  tests) avec statut final (Validé / Non validé + raison).
- Si l'exécution ne peut toujours pas aboutir (Docker indisponible malgré T1, ou blocage
  d'infrastructure similaire) : documenter le fait, le diagnostic, et explicitement **ne pas**
  cocher les critères d'acceptation du ticket — ne pas déclarer une validation qui n'a pas eu
  lieu.

## Plan de tests

| Critère d'acceptation (#68) | Couverture |
|---|---|
| `RowLevelSecurityIT` et `RowLevelSecurityHibernateIT` s'exécutent avec succès (pas de skip) sur un environnement Docker fonctionnel | T2, T4, T12 — preuve : sortie Maven/Surefire sans `Assumptions.assumeTrue` déclenché (pas de mention "ignored"/"skipped"), consignée dans `validation-report.md` (T14) |
| Tout défaut d'isolation révélé par cette exécution est corrigé et re-testé | T3/T5 (diagnostic), T6-T11 (correctif selon la nature du défaut), T12 (re-exécution des deux IT), T13 (non-régression suite complète) |
| Le résultat de cette validation est documenté de façon traçable | T14 (`validation-report.md`), T15 (pointeur depuis `docs/bolts/40-multitenant-postgres-rls/review.md`) |

**Correspondance interne aux tests eux-mêmes (héritée de #40, à titre de référence pour
l'interprétation des résultats de T2/T4) :**

| AC #40 | Test | Fichier |
|---|---|---|
| Non-régression mono-tenant | `monoTenantNonRegression_seesAllExistingDataUnderTheSingleOrganization` | `RowLevelSecurityIT.java` |
| AC1 — accès SQL direct isolé par organisation | `ac1_directSqlAccessIsIsolatedPerOrganization` | `RowLevelSecurityIT.java` |
| AC2 — pas de fuite entre tenants sur connexion physique réutilisée (JDBC brut) | `ac2_noLeakageBetweenConsecutiveTenantsOnTheSamePhysicalConnection` | `RowLevelSecurityIT.java` |
| Isolation `organization_plan` (V17/V18, #42) | `organizationPlanRowsAreIsolatedPerOrganization` | `RowLevelSecurityIT.java` |
| AC2 — pas de fuite via Hibernate/`MultiTenantConnectionProvider` (pool taille 1) | `tenantPropagatesThroughHibernateWithoutLeakingOnThePooledConnection` | `RowLevelSecurityHibernateIT.java` |

Aucun test manuel n'est requis : les critères d'acceptation du ticket #68 portent sur
l'exécution effective de tests automatisés déjà écrits, pas sur un comportement à vérifier
manuellement.

## Écarts identifiés

- **Numéro de migration Flyway obsolète dans le design.** Le design (section "Fichiers/modules
  impactés" et "Décisions clés") prescrit une éventuelle correction via une nouvelle migration
  `V17__...sql`. Or `backend/src/main/resources/db/migration/` contient déjà
  `V17__organization_plan.sql` et `V18__organization_plan_grants.sql`, et la dernière migration
  appliquée est `V19__users_email.sql`. Créer un fichier `V17__...sql` casserait l'ordre Flyway
  (checksum/conflit de version). **Correction appliquée dans cette spec (T6/T7) : le prochain
  numéro disponible est `V20`** (et `V21` si deux correctifs distincts sont nécessaires en
  parallèle). À vérifier une nouvelle fois au moment de coder, au cas où une migration
  intercurrente serait ajoutée par un autre ticket entretemps.

- **Constat positif, sans impact sur les tâches :** la table `organization_plan` (introduite
  par #42, hors périmètre de #40) a déjà sa policy RLS (`V17__organization_plan.sql`, lignes
  18-21) et son `GRANT` (`V18__organization_plan_grants.sql`). Le test
  `organizationPlanRowsAreIsolatedPerOrganization` de `RowLevelSecurityIT.java` (T2) doit donc
  passer sans correctif attendu sur ce point précis — aucune tâche conditionnelle dédiée n'est
  nécessaire au-delà de T6/T7 génériques si un défaut inattendu y était malgré tout révélé.

- **Absence de plan de repli explicite si l'exécution reste bloquée malgré la vérification T1.**
  Le design affirme, sur la base d'un `docker ps`/`docker images` constatés au moment de sa
  rédaction, que Docker est désormais fonctionnel dans cet environnement — mais ne précise pas
  la marche à suivre si `DockerClientFactory.instance().isDockerAvailable()` (utilisé en interne
  par `Assumptions.assumeTrue` dans les deux IT, `RowLevelSecurityIT.java:50` et
  `RowLevelSecurityHibernateIT.java:63`) échoue malgré tout au moment réel de l'exécution — ce
  check Testcontainers peut échouer pour des raisons plus fines qu'un `docker ps` réussi (socket
  non accessible au process Java, permissions). T1 couvre la vérification préalable, mais si
  l'exécution échoue quand même pour cette raison d'infrastructure (pas un défaut de code), la
  spec impose de le documenter comme tel dans `validation-report.md` (voir Contrat technique) et
  de **ne pas cocher les critères d'acceptation** plutôt que de forcer un contournement — à
  trancher explicitement par le codeur/reviewer si ce cas se présente, ce n'est pas un défaut du
  design mais un trou de couverture qu'il faut signaler avant de coder.
