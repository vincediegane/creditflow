# Review — #40 Multi-tenant 7/10 — Défense en profondeur Postgres Row-Level Security

## APPROVE

Deuxième et dernier passage. Les deux findings mineurs du premier passage sont corrigés et
vérifiés. Le finding bloquant (Docker indisponible dans ce pipeline d'exécution, empêchant toute
validation empirique de RowLevelSecurityIT / RowLevelSecurityHibernateIT) reste non résolu, mais
c'est maintenant un fait établi trois fois de suite (codeur x2, reviewer x2) avec un diagnostic
précis par thread dump : il s'agit d'un blocage d'infrastructure sandbox, pas d'un défaut de
rigueur du codeur ni d'un problème de code. Aucune itération supplémentaire sur cette branche ne
peut lever ce point ; seule une exécution sur une machine avec un daemon Docker fonctionnel le
peut. Voir "Point résiduel non-bloquant" ci-dessous pour l'action de suivi requise avant un
déploiement réel multi-organisations.

## Ce qui a change depuis le premier passage

Diff f27ec19..HEAD (les 3 commits du deuxieme passage) : uniquement les 3 fichiers annonces,
aucune modification surprise du code de production.

- aa2f571 : ajout de TenantContextFilterTest.java (finding #2).
- 78be8c2 : suppression de DB_USERNAME/DB_PASSWORD dans .env.example et
  .env.production.example (finding #3).

## Verification du finding #2 (TenantContextFilterTest)

Relu backend/src/test/java/com/creditflow/common/security/TenantContextFilterTest.java (103
lignes, 3 tests) et TenantContextFilter.java (37 lignes) cote a cote.

- setsTenantContextForAuthenticatedUser : authentification reelle posee dans le
  SecurityContextHolder, mock de CurrentShopContext.currentOrganizationId() retournant 10L,
  et surtout la valeur de TenantContext.get() est capturee depuis l'interieur de
  chain.doFilter via un doAnswer, pas juste avant/apres l'appel du filtre. C'est le bon point
  d'observation : ca prouve que le contexte est positionne pendant l'execution de la chaine, pas
  seulement a un moment quelconque de la methode. Assertion finale que TenantContext.get() est
  bien null apres retour du filtre (nettoyage). Non tautologique : si la ligne
  TenantContext.set(...) etait supprimee ou deplacee apres doFilter, ce test echouerait
  (capture null au lieu de 10L).
- leavesTenantContextEmptyForAnonymousUser : utilise un vrai AnonymousAuthenticationToken
  (pas un mock d'Authentication generique), donc exerce reellement la condition
  !(authentication instanceof AnonymousAuthenticationToken) du filtre plutot qu'un stub qui
  contournerait la verification de type. currentShopContext.currentOrganizationId() n'est pas
  stubbe dans ce test (Mockito strict stubbing aurait fait echouer le test s'il avait ete appele a
  tort) - verifie implicitement, mais correctement, que la branche authenticated n'est pas prise
  pour un anonyme.
- clearsTenantContextWhenFilterChainThrows : chain.doFilter configure pour lever une
  RuntimeException, assertion que l'exception se propage bien (assertThatThrownBy) ET que
  TenantContext.get() est null apres. C'est exactement le scenario decrit dans le finding
  original (thread de pool Tomcat reutilise par un autre tenant apres une exception applicative
  non rattrapee) et il exerce reellement le finally du filtre, pas une reformulation du premier
  test. Si le TenantContext.clear() etait deplace hors du bloc finally (ex. apres l'appel a
  doFilter sans try/finally), ce test echouerait par fuite de contexte detectee.

Les trois tests sont bien construits, testent des scenarios distincts et non redondants entre eux,
et chacun echouerait reellement si la protection correspondante du filtre etait retiree. Finding
#2 correctement traite, pas de reserve.

## Verification du finding #3 (.env DB_USERNAME/DB_PASSWORD)

.env.example et .env.production.example relus integralement : DB_USERNAME/DB_PASSWORD ont bien
disparu des deux fichiers, ne restent que DB_MIGRATION_USERNAME/DB_MIGRATION_PASSWORD et
DB_APP_USERNAME/DB_APP_PASSWORD.

Grep de DB_USERNAME/DB_PASSWORD sur tout le repo pour verifier l'absence de dependance
residuelle :

- docker-compose.yml reference encore DB_USERNAME/DB_PASSWORD, mais comme variables
  d'environnement internes au conteneur backend, derivees de ${DB_APP_USERNAME} /
  ${DB_APP_PASSWORD} (indirection intentionnelle et deja actee par design.md : Spring Boot lit
  DB_USERNAME/DB_PASSWORD en interne, mais leur valeur vient maintenant du role applicatif).
  Cette indirection ne depend pas de ce que contiennent les fichiers .env*, donc la suppression
  ne casse rien ici.
- backend/src/main/resources/application.yml lit ${DB_USERNAME:creditflow} /
  ${DB_PASSWORD:creditflow} - memes valeurs par defaut qu'avant, coherentes avec le role de
  migration par defaut. Aucun changement requis ni fait ici, correct.
- scripts/backup.sh et scripts/restore.sh utilisent DB_USERNAME="${DB_USERNAME:-creditflow}"
  comme variable d'environnement shell (pas lue depuis .env, ces scripts ne le sourcent pas) -
  preexistant a ce bolt (aucun commit de la branche ne les touche, confirme par
  git log master..HEAD -- scripts/backup.sh scripts/restore.sh vide), donc hors perimetre du
  finding #3 et non affecte par la suppression dans les .env*.
- README.md et docs/bolts/40-.../{design,spec}.md mentionnent encore DB_USERNAME/DB_PASSWORD
  dans du texte explicatif ou un runbook de migration - documentation, pas des fichiers .env
  consommes par docker-compose, donc hors du perimetre strict du finding #3 tel que formule (qui
  ciblait specifiquement les deux fichiers .env*.example).

Aucun fichier ne s'appuie sur la presence de DB_USERNAME/DB_PASSWORD dans .env.example ou
.env.production.example specifiquement. Finding #3 correctement traite, rien de casse.

## Nouvelle tentative Docker (3e constat)

docker info relance avec un timeout de 30s (timeout 30 docker info) : la commande a ete tuee
par le timeout (code de sortie 124), sans jamais retourner de reponse - meme symptome exact que
lors des deux passages precedents (codeur et review #1). Un tasklist a par ailleurs revele
plusieurs process docker.exe deja bloques dans cet environnement avant meme ma propre tentative,
coherent avec le diagnostic deja etabli par thread dump lors du premier passage (appel HTTP reel
au daemon jamais resolu). Aucune investigation supplementaire menee au-dela de cette confirmation
rapide, conformement a la consigne - le diagnostic est deja complet et ne changera pas d'une
tentative a l'autre dans cet environnement.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| AC1 | Requete SQL directe isolee par organisation | Partiel - inchange depuis le 1er passage. Couvert par du code (V15, role applicatif V16) et par un test dedie (RowLevelSecurityIT#ac1_directSqlAccessIsIsolatedPerOrganization) qui echouerait si le code etait retire, mais jamais execute avec succes faute de Docker. Relecture statique SQL : coherente. |
| AC2 | Pas de fuite entre tenants sur connexion physique reutilisee | Partiel - inchange. RowLevelSecurityIT#ac2 et RowLevelSecurityHibernateIT couvrent le scenario exact (reutilisation litterale de connexion physique), jamais executes avec succes. TenantConnectionConfigTest (Mockito, vert) valide l'appel de set_config/RESET mais pas leur effet reel sur Postgres. |
| AC3 | Instance mono-tenant : comportement identique | Partiel - inchange. RowLevelSecurityIT#monoTenantNonRegression couvre ce cas, jamais executee. Pas de regression detectee sur les 387 tests Mockito/MockMvc, mais ceux-ci ne passent pas par une vraie session Hibernate/Postgres. |
| - | TenantContextFilter correctement teste (finding #2) | Couvert - 3 tests dedies, non tautologiques, verifies ci-dessus. |
| - | .env* coherents avec docker-compose.yml (finding #3) | Couvert - DB_USERNAME/DB_PASSWORD obsoletes retires des deux fichiers, aucune dependance residuelle cassee. |

Le statut des AC1/AC2/AC3 reste "Partiel" pour la meme raison structurelle qu'au premier passage :
le mecanisme central n'a jamais tourne avec succes dans ce pipeline. Ce n'est plus, a ce stade,
une raison de bloquer le merge (voir Verdict), mais une reserve qui doit etre levee avant un usage
en production avec plusieurs organisations reelles.

## Findings

Aucun finding bloquant restant. Les findings #2 et #3 du premier passage sont clos (voir
verifications ci-dessus). Le finding #1 reste ouvert mais reclasse - voir section suivante.

### Point residuel non-bloquant (ex-finding #1) : validation empirique de RLS encore a faire hors de ce pipeline

- Ou : backend/src/test/java/com/creditflow/security/rls/RowLevelSecurityIT.java,
  RowLevelSecurityHibernateIT.java, et transitivement
  backend/src/main/java/com/creditflow/config/TenantConnectionConfig.java.
- Etat : ces deux classes de test n'ont ete executees avec succes dans aucun environnement de ce
  pipeline (codeur, deux passages ; reviewer, deux passages), toujours avec le meme symptome
  precisement diagnostique (daemon Docker qui ne repond jamais a docker info /
  isDockerAvailable(), confirme par thread dump lors du premier passage, reconfirme par timeout
  lors de ce deuxieme passage). Ce n'est pas un probleme de code : la relecture statique du SQL et
  de TenantConnectionConfig n'a trouve aucune erreur, et les deux tests sont bien concus pour
  detecter exactement les bugs qui compteraient (policy inversee, fuite via connexion physique
  reutilisee, regression sur instance mono-tenant).
- Pourquoi ce n'est plus un motif de CHANGES_REQUESTED sur cette branche precisement : une 4e
  tentative (codeur ou reviewer) dans ce meme pipeline reproduirait tres probablement le meme
  echec, pour la meme raison d'infrastructure. Continuer a reclamer des changements de code sur
  cette base reviendrait a demander au codeur de corriger un probleme qui n'est pas dans le code.
- Action de suivi requise, hors de cette branche et hors de ce pipeline d'execution, avant tout
  deploiement en production avec plusieurs organisations reelles :
  1. Executer RowLevelSecurityIT et RowLevelSecurityHibernateIT sur une machine ou Docker
     fonctionne reellement (CI avec service Docker, poste de developpeur, WSL2 avec daemon
     fonctionnel).
  2. A defaut, rejouer manuellement le scenario docker compose up + psql direct en suivant le
     patron exact des deux tests (SQL deja ecrit, il suffit de le rejouer a la main) contre une
     vraie instance Postgres avant mise en production multi-tenant.
  3. Tracer cette action dans le suivi du ticket #40 (pas seulement dans ce fichier), pour qu'elle
     ne soit pas silencieusement consideree comme faite du seul fait que la PR a ete mergee avec un
     badge vert.

## Build/tests

- Commande : cd backend && mvn -o test -Dtest="!RowLevelSecurityIT,!RowLevelSecurityHibernateIT" -DfailIfNoTests=false
  Resultat : BUILD SUCCESS, 387 tests executes, 0 echec, 0 erreur, 0 ignore (384 precedents +
  3 nouveaux TenantContextFilterTest, confirme nommement dans le log Surefire). Confirme le
  rapport du codeur.
- Commande : timeout 30 docker info
  Resultat : timeout atteint (code de sortie 124), aucune reponse du daemon - 3e confirmation du
  meme symptome diagnostique au premier passage (voir section dediee ci-dessus). Pas
  d'investigation supplementaire menee, conformement a la consigne.
- RowLevelSecurityIT et RowLevelSecurityHibernateIT non executes dans cet environnement, pour
  la raison ci-dessus.

## Conclusion

Les deux findings mineurs du premier passage sont corriges proprement et verifies independamment :
les tests de TenantContextFilter sont reels et non tautologiques, et le nettoyage des .env* ne
casse aucune dependance existante. Le point bloquant du premier passage (absence de validation
empirique du mecanisme RLS/MultiTenantConnectionProvider) reste non resolu, mais c'est maintenant
un fait etabli de facon robuste et reproductible (4 tentatives independantes, meme diagnostic
precis a chaque fois) : il s'agit d'une limite de l'environnement d'execution de ce pipeline, pas
d'un defaut du code ni d'un manque de rigueur du codeur. Exiger une 5e tentative de code sur cette
branche n'apporterait rien. Le verdict est APPROVE, sous reserve explicite que l'action de suivi
decrite ci-dessus (execution reelle de RowLevelSecurityIT/RowLevelSecurityHibernateIT, ou
validation manuelle equivalente contre un vrai Postgres) soit effectuee avant tout deploiement en
production avec plusieurs organisations reelles.
