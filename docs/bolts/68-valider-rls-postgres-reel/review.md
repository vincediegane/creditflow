# Review — #68 Valider empiriquement la Row-Level Security multi-tenant sur un Postgres reel

## APPROVE

J ai re-execute moi-meme les deux IT et la suite complete (pas seulement relu le rapport du
codeur) : les resultats rapportes sont exacts, la modification de code est minimale et
correctement causale, et le rapport de validation est honnete et tracable (episode de blocage
initial inclus, pas seulement le succes final).

## Verification independante effectuee

- docker version / docker ps : Docker Desktop 4.54.0, daemon actif, conteneurs infra deja en
  cours (infra-postgres-1 sur le port hote 5433, pas de conflit) — confirme les prealables du
  rapport.
- mvn test -Dtest=RowLevelSecurityIT : Tests run: 4, Failures: 0, Errors: 0, Skipped: 0,
  BUILD SUCCESS, log Testcontainers confirmant Testcontainers version: 1.21.4 et une connexion
  Docker reelle reussie (Connected to docker: ... API Version: 1.52). Correspond exactement au
  rapport (4/4 verts, pas de skip).
- mvn test -Dtest=RowLevelSecurityHibernateIT : Tests run: 1, Failures: 0, Errors: 0,
  Skipped: 0, BUILD SUCCESS, contexte Spring demarre normalement. Correspond au rapport
  (1/1 vert, pas de skip).
- mvn test (suite complete) : Tests run: 418, Failures: 0, Errors: 0, Skipped: 0,
  BUILD SUCCESS. Correspond exactement au chiffre du rapport (418, 0 echec, aucune regression).
- git diff master -- backend/pom.xml : diff strictement limite a l ajout de la ligne
  <testcontainers.version>1.21.4</testcontainers.version>, aucun autre changement cache dans ce
  fichier.
- Causalite du correctif verifiee independamment (pas seulement prise pour argent comptant) :
  - spring-boot-dependencies-3.5.6.pom (BOM herite) gere testcontainers.version=1.21.3 par
    defaut — confirme que sans l override, la version resolue aurait ete 1.21.3, celle qui
    bloquait selon le diagnostic du codeur.
  - mvn dependency:tree -Dincludes=org.testcontainers:testcontainers sur l etat actuel du repo
    resout bien 1.21.4, coherent avec l override.
  - Le jar testcontainers-1.21.4.jar dans le cache Maven local porte un timestamp du jour meme
    de ce bolt (telecharge pendant la session), ce qui confirme que l acces reseau a Maven
    Central etait bien necessaire pour resoudre le correctif — justifiant a posteriori l
    abandon du flag -o prescrit par la spec (deviation documentee et assumee dans le rapport,
    pas silencieuse).
  - Tentative de reverter temporairement la ligne du pom.xml pour reproduire le blocage et
    prouver la causalite de maniere destructive : bloquee par la sandbox (modification de
    fichier source non autorisee pour ce role) — comportement attendu et respecte sans
    contournement. La preuve de causalite ci-dessus (BOM par defaut = 1.21.3, override = 1.21.4,
    jar frais telecharge ce jour) est jugee suffisante sans cette manipulation.
- Lecture ligne a ligne de RowLevelSecurityIT.java et RowLevelSecurityHibernateIT.java : ces
  fichiers ne font pas partie du diff (git diff master --name-only ne les liste pas) — aucune
  assertion RLS n a ete touchee ni assouplie, conformement a l interdiction explicite du design.
  Les deux Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), ...)
  existent toujours (mecanisme de skip volontaire si Docker est indisponible, herite de #40)
  mais les executions reelles ci-dessus prouvent qu ils ne se sont pas declenches
  (Skipped: 0 dans les deux cas, logs Testcontainers montrant une connexion Docker reussie avant
  le demarrage du conteneur).
- docs/bolts/40-multitenant-postgres-rls/review.md : le pointeur ajoute (lignes 175-187) est
  bien un ajout en fin de fichier ; le contenu original (verdict APPROVE, analyse, findings,
  section Point residuel non-bloquant) est intact et non modifie.
- validation-report.md relu integralement : contient explicitement la section Historique de
  resolution retracant le premier passage bloque (commit 630e40e), la correction d hypothese
  sur mvn -o, et le second passage reussi — tracabilite honnete, pas seulement le resultat final
  positif.

## Criteres d acceptation (#68)

| # | Critere | Statut | Preuve |
|---|---|---|---|
| 1 | RowLevelSecurityIT et RowLevelSecurityHibernateIT s executent avec succes (pas de skip) sur un environnement Docker fonctionnel | Couvert | Re-execute moi-meme : 4/4 et 1/1, Skipped: 0 dans les deux cas, BUILD SUCCESS. |
| 2 | Tout defaut d isolation revele par cette execution est corrige et re-teste | Couvert | Aucun defaut d isolation RLS reel n a ete revele (les deux IT passent des le premier essai apres levee du blocage d outillage) — cas explicitement prevu par le contrat technique de la spec (mention explicite aucun defaut si les deux IT passent du premier coup). Le seul blocage rencontre (outillage Testcontainers/Docker Desktop, pas RLS) a ete corrige (testcontainers.version=1.21.4) et re-teste (T12 respecte). |
| 3 | Le resultat de cette validation est documente de facon tracable | Couvert | validation-report.md complet avec environnement, commandes exactes, sorties brutes, historique de resolution incluant l episode de blocage initial, verdict par critere ; pointeur ajoute dans docs/bolts/40-multitenant-postgres-rls/review.md sans alteration du contenu clos. |

## Coherence avec spec.md

- T1-T5, T12-T15 executees et respectees. T6-T11 (correctifs conditionnels RLS) legitimement non
  declenchees, aucun defaut reel n ayant ete trouve — coherent avec le Prix assume du design
  (ne pas elargir le perimetre si aucun defaut n est revele).
- Seule deviation reperee : les commandes de T2/T4/T13 prescrivaient mvn -o test ... (mode
  offline), le codeur a utilise mvn test ... sans -o. Deviation explicitement documentee et
  justifiee (acces reseau a Maven Central necessaire pour resoudre testcontainers:1.21.4,
  confirme par le timestamp du jar telecharge ce jour dans le cache local ci-dessus) — pas une
  divergence silencieuse. Le contrat technique de la spec anticipait deja ce type d ecart
  (section Ecarts identifies : hypothese initiale de la spec peut etre fausse). Non bloquant.
- Presentation mineure, non bloquante : le tableau Verdict par critere d acceptation du rapport
  de validation etiquette les lignes AC1/AC2/non-regression (heritage #40) plutot que de
  reprendre verbatim les 3 criteres d acceptation du ticket #68 tels que formules. Le fond
  couvre neanmoins bien les 3 criteres de #68 (voir tableau ci-dessus construit independamment a
  partir des sections Execution et Historique de resolution du meme rapport).

## Build/tests (executes par le reviewer, pas seulement relus)

- cd backend && mvn test -Dtest=RowLevelSecurityIT -> BUILD SUCCESS, Tests run: 4, Failures: 0, Errors: 0, Skipped: 0.
- cd backend && mvn test -Dtest=RowLevelSecurityHibernateIT -> BUILD SUCCESS, Tests run: 1, Failures: 0, Errors: 0, Skipped: 0.
- cd backend && mvn test (suite complete) -> BUILD SUCCESS, Tests run: 418, Failures: 0, Errors: 0, Skipped: 0.
- mvn dependency:tree -Dincludes=org.testcontainers:testcontainers -> resout 1.21.4 (verification de causalite).
- Aucune modification de fichier source effectuee par ce role (conforme au perimetre du reviewer).

## Conclusion

Les trois criteres d acceptation du ticket #68 sont couverts par du code/des tests reellement
executes (pas de skip), aucun defaut d isolation RLS n a ete trouve, le correctif d outillage
(bump testcontainers.version) est minimal, cause verifiee independamment, sans regression sur
les 418 tests de la suite. La documentation est tracable et honnete, y compris l echec initial.

Verdict : APPROVE.
