# Review — Issue #64 : integrite des sauvegardes et test periodique de restauration

## Verdict

CHANGES_REQUESTED

## Resume

L'implementation est globalement conforme au contrat technique de spec.md et j'ai pu reproduire
independamment, en Docker, tous les scenarios cles (quarantaine + CRITIQUE, exclusion de la purge,
demarrage restore-test sans erreur root, cycle restore-test.sh reussi sur une vraie archive,
SKIP propre, archive en quarantaine ignoree par la selection, montage :ro). Un defaut concret
bloque neanmoins l'approbation : BACKUP_MIN_SIZE_BYTES n'est jamais transmis au conteneur
backup dans docker-compose.yml, ce qui rend ce reglage inoperant en pratique malgre sa
documentation dans les deux fichiers .env*.example (voir Finding 1).

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| AC1 | Sauvegarde corrompue/anormalement petite detectee automatiquement, alerte visible (log critique min.), pas de "OK" silencieux | Partiel — le mecanisme fonctionne avec la valeur par defaut (1024 octets), verifie en conteneur Alpine reel (quarantaine + code retour 1 sur fichier trop petit et sur fichier corrompu apres troncature). Mais le seuil BACKUP_MIN_SIZE_BYTES, documente comme configurable par l'operateur, n'a aucun effet reel car non cable dans docker-compose.yml (Finding 1). |
| AC2 | Procedure de restauration de test existe, declenchable facilement (script/commande documentee) | Couvert — scripts/restore-test.sh + `docker compose exec restore-test sh /usr/local/bin/restore-test.sh` documentes dans README.md, testes en Docker (SKIP sans archive, quarantaine ignoree, cycle complet reussi, montage /backups:ro refuse l'ecriture, pas de depends_on: db). |
| AC3 | Restauration de test executee au moins une fois manuellement de bout en bout, succes valide avant mise en production | Couvert — reproduit independamment (voir section Build/tests), OK obtenu sur une archive reelle produite par backup.sh sur cette branche, en plus de la preuve deja fournie par le codeur. |

## Findings

### 1. (Bloquant) BACKUP_MIN_SIZE_BYTES n'est jamais propage au conteneur backup — docker-compose.yml:99-108

Le bloc environment: du service backup definit BACKUP_INTERVAL_HOURS et BACKUP_RETENTION_DAYS
(lignes 102-103) mais pas BACKUP_MIN_SIZE_BYTES :

```
    environment:
      PGHOST: db
      ...
      BACKUP_INTERVAL_HOURS: ${BACKUP_INTERVAL_HOURS:-24}
      BACKUP_RETENTION_DAYS: ${BACKUP_RETENTION_DAYS:-14}
      TZ: ${TZ:-Africa/Dakar}
```

Une variable definie dans .env n'est visible dans le process du conteneur que si elle est
explicitement listee sous environment: (ou env_file:) du service — docker-compose.yml
l'utilise seulement pour l'interpolation ${...} au moment du parsing du fichier YAML, pas pour
peupler l'environnement runtime du conteneur.

Reproduction : positionnement de BACKUP_MIN_SIZE_BYTES=99999 au moment de
`docker compose up -d backup`, puis verification dans le conteneur :

```
$ docker exec creditflow-backup env | grep -i BACKUP
BACKUP_INTERVAL_HOURS=24
BACKUP_RETENTION_DAYS=14
$ docker exec creditflow-backup sh -c "echo [$BACKUP_MIN_SIZE_BYTES]"
[]
```

La variable n'atteint jamais le conteneur : backup_verify() retombe systematiquement sur son
defaut interne ${BACKUP_MIN_SIZE_BYTES:-1024}, quel que soit ce que l'operateur configure dans
.env / .env.production.example. Or ces deux fichiers documentent explicitement la variable comme
un reglage operationnel ("Taille plancher (octets) en dessous de laquelle une sauvegarde est jugee
anormale..."). Un operateur qui, apres un incident, augmente ce seuil pour durcir la detection
(ex. dump attendu ~10 Mo, seuil releve a 1 Mo) n'obtiendra silencieusement aucun changement de
comportement — regression de configurabilite qui contredit l'esprit d'AC1 (pas de faux "OK"
silencieux : ici c'est le reglage lui-meme qui est silencieusement ignore).

Le comportement par defaut (1024 octets) reste correct et a ete verifie en conditions reelles
(conteneur Alpine, fichier de 20 octets vers quarantaine + code 1), donc ce n'est pas une
regression totale d'AC1, mais c'est un ecart concret par rapport au contrat de configuration
attendu par la spec (tableau "Variables d'environnement nouvelles", qui liste docker-compose.yml
comme fichier concerne pour BACKUP_MIN_SIZE_BYTES, a la difference de RESTORE_TEST_INTERVAL_HOURS
qui, lui, est correctement cable pour le service restore-test).

Correctif attendu : ajouter `BACKUP_MIN_SIZE_BYTES: ${BACKUP_MIN_SIZE_BYTES:-1024}` au bloc
environment: du service backup, au meme niveau que BACKUP_RETENTION_DAYS.

### 2. (Non bloquant, observation) Sortie bruyante de la restauration dans les logs — scripts/restore-test.sh:47

```
if ! gunzip -c "$ARCHIVE" | psql -h "$TMPDATA" -d "$DBNAME" -v ON_ERROR_STOP=1 -q; then
```

-q supprime la banniere/les invites psql mais pas les resultats des requetes executees durant
le restore (setval(...) de pg_dump --clean notamment) : le cycle observe en conditions reelles
affiche une quinzaine de blocs setval / valeur dans les logs du conteneur restore-test avant
la ligne OK. Ce n'est pas un bug fonctionnel (comportement conforme au contrat technique de la
spec, qui specifie exactement -q sans redirection supplementaire) mais ca pollue des logs censes
rester lisibles pour reperer un CRITIQUE au milieu d'executions hebdomadaires cumulees sur la
duree. A envisager pour une iteration future : rediriger vers /dev/null. Ne bloque pas cette review.

## Verifications independantes effectuees (au-dela du rapport du codeur)

- Diff reel (`git diff master...HEAD`, hors spec.md/design.md) : conforme au contrat
  technique — backup-verify.sh reproduit le snippet de la spec au caractere pres,
  backup.sh/backup-loop.sh inserent l'appel a backup_verify exactement a l'endroit prescrit,
  backup-loop.sh exclut bien quarantine/ de la purge (`-not -path "*/quarantine/*"`) sans
  interrompre la boucle en cas d'echec, restore-test.sh/restore-test-loop.sh et le service
  docker-compose.yml correspondent au contrat (image, user: postgres, pas de depends_on: db,
  volumes :ro, entrypoint). Aucun fichier hors perimetre touche (git diff --stat limite a
  .env.example, .env.production.example, README.md, docker-compose.yml et les 5 scripts attendus).
- Tests unitaires de backup_verify() (source directement, hors conteneur) : fichier trop petit
  vers quarantaine + retour 1 ; fichier de taille suffisante mais tronque apres le header gzip
  (gunzip -t echoue) vers quarantaine + retour 1 ; fichier valide de 20 Ko conserve, retour 0.
- Conteneur backup reel (Alpine/busybox, scripts en LF) : cycle nominal sur une vraie base
  (db demarre, schema Flyway present) donne un log OK, taille 16.0K > seuil. Simulation de la
  signature du bug #63 (flux vide compresse, 20 octets) executee directement dans le conteneur via
  backup_verify donne quarantaine + code 1, confirme en environnement busybox reel (pas seulement
  bash hote).
- Purge de retention vs quarantaine : fichier en quarantaine date artificiellement a 2020 (donc
  +14 jours) — `find ... -not -path "*/quarantine/*" -mtime +14` ne le remonte pas, alors que la
  meme commande sans le filtre le remonte. Confirme que le correctif de retention est effectif et
  pas juste present dans le code sans effet.
- Service restore-test : demarre via `docker compose up -d restore-test` ; `docker exec ... id`
  donne uid=70(postgres) gid=70(postgres), aucune erreur "initdb: error: cannot be run as root"
  dans les logs. Cycle immediat au demarrage donne :
  `[restore-test] OK — restauration et verification reussies (creditflow-20260909-182432.sql.gz)`
  sur une archive reellement produite par le service backup pendant cette review (6 tables +
  flyway_schema_history verifiees par le script, execution en environ 7s, nettoyage de l'instance
  ephemere effectif).
- Cas SKIP (repertoire BACKUP_DIR vide) donne exit 0 + log SKIP, pas de CRITIQUE.
- Cas archive uniquement en quarantaine correctement ignoree par `ls -t "$DIR"/*.sql.gz`
  (niveau racine uniquement) donne SKIP, pas de faux succes sur une archive corrompue.
- Isolation /backups:ro : `docker exec creditflow-restore-test touch /backups/x` donne
  "Read-only file system". `docker inspect`/`docker compose config` confirment l'absence de
  depends_on vers db pour restore-test.
- Syntaxe shell : `sh -n` sur les 6 scripts concernes (y compris nouveaux), a la fois via le
  shell de l'hote et via postgres:16-alpine (busybox ash) — aucune erreur de syntaxe.
- Verification independante de l'affirmation CRLF du codeur (hors perimetre du ticket, mais
  verifiee car soulevee dans le rapport) :
  - Les blobs git de scripts/backup.sh, scripts/backup-loop.sh, scripts/restore.sh (deja sur
    master avant ce ticket) sont en LF pur (`git cat-file -p ... | grep -c $'\r'` donne 0 pour
    chacun). Le depot ne contient aucun .gitattributes — donc aucune regle de normalisation
    de fin de ligne n'existe pour quelque script que ce soit, ancien ou nouveau.
  - Sur ce poste, core.autocrlf=true : le checkout de n'importe quel script shell (ancien ou
    nouveau, sans distinction) produit du CRLF dans l'arbre de travail (verifie : 36/54/53
    retours chariot pour backup.sh/backup-loop.sh/restore.sh deja presents sur master, et
    21/71/28 retours chariot pour les 3 nouveaux fichiers de ce ticket) — confirmant que le
    probleme preexiste bel et bien a #64, s'applique uniformement (pas de degradation introduite
    par ce diff), et qu'aucune configuration .gitattributes n'aurait pu proteger selectivement
    les nouveaux fichiers sans proteger egalement les anciens de la meme facon. L'affirmation du
    codeur est donc confirmee independamment. Ce point reste hors perimetre de #64 (pas de
    regression introduite), mais merite un ticket dedie (`*.sh text eol=lf` dans un futur
    .gitattributes) pour fiabiliser les executions futures bind-montees sur postes Windows.
  - Note pratique : `sh scripts/backup.sh` execute directement sur l'hote (Git Bash) fonctionne
    malgre le CRLF (verifie, genere une sauvegarde valide) ; le probleme ne se manifeste que pour
    l'execution bind-montee dans un conteneur Alpine/busybox, coherent avec ce que rapporte le
    codeur.
- Proprete du depot apres tests : `git status --short` vide avant et apres l'ensemble des
  manipulations Docker ; toutes les archives .sql.gz generees pendant les tests (ignorees par
  backups/.gitignore) ont ete supprimees manuellement en fin de review ; les copies temporaires
  LF utilisees pour contourner le CRLF cote conteneurs ont ete creees et executees uniquement
  depuis le repertoire scratch de session (hors depot), jamais commitees ni ecrites dans le repo.

## Build/tests

- `docker version` / `docker compose version` : Docker Desktop 4.54.0, Compose v2.40.3 — OK.
- `docker compose config` (sur docker-compose.yml de la branche) : parse sans erreur, service
  restore-test present avec user: postgres, pas de depends_on, volumes :ro corrects — OK.
- `sh -n` sur scripts/{backup,backup-loop,backup-verify,restore-test,restore-test-loop,restore}.sh
  (hote + postgres:16-alpine) — OK, aucune erreur de syntaxe.
- Scenarios Docker reels detailles ci-dessus (db + backup + restore-test, cycles complets, SKIP,
  quarantaine, purge, isolation :ro, absence de depends_on, absence d'erreur root) — tous passent
  tels que specifies dans le plan de tests de spec.md, a l'exception du defaut de cablage
  BACKUP_MIN_SIZE_BYTES (Finding 1) qui n'est pas couvert par le plan de tests de la spec
  elle-meme (angle mort du plan de tests, pas seulement du code).
- Aucun changement backend/frontend dans ce diff — pas de build Maven/npm necessaire pour ce
  ticket (perimetre strictement infra/scripts, confirme par git diff --stat).

## Conclusion

Le travail est solide et la validation operationnelle AC3 est reelle (reproduite independamment,
pas seulement declarative). Le blocage porte sur un point precis et actionnable : cabler
BACKUP_MIN_SIZE_BYTES dans l'environment: du service backup de docker-compose.yml. Une fois
ce correctif applique (et idealement re-teste avec une valeur non-defaut pour confirmer la prise
en compte), le reste de l'implementation est pret a etre approuve sans autre changement necessaire.
