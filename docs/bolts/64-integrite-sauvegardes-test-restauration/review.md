# Review — Issue #64 : integrite des sauvegardes et test periodique de restauration

## Verdict

APPROVE

## Resume

Deuxieme et dernier passage. Le correctif du codeur (commit `2c6a723`) resout exactement le
seul point bloquant du premier passage : `BACKUP_MIN_SIZE_BYTES` est desormais propage au
conteneur `backup` dans `docker-compose.yml`, sur le meme modele que `RESTORE_TEST_INTERVAL_HOURS`
pour `restore-test`. J'ai reproduit independamment en Docker (pas seulement relu le diff) : la
valeur non-defaut atteint bien le conteneur, est bien appliquee par `backup_verify()` (mise en
quarantaine d'une archive de 12629 octets — valide en gzip, largement au-dessus du defaut 1024 —
uniquement a cause du seuil artificiellement releve a 99999), et le comportement par defaut sans
reglage explicite reste 1024 (backup reel de 16.0K accepte, `OK`). Le reste du diff du ticket
(`backup-verify.sh`, `backup.sh`, `backup-loop.sh`, `restore-test.sh`, `restore-test-loop.sh`,
service `restore-test`, exclusion de `quarantine/` de la purge) n'a pas regresse : re-teste en
conditions reelles ce passage-ci (cycle `restore-test` complet, `OK` sur une archive produite
pendant cette session ; utilisateur non-root confirme ; pas de `depends_on: db`). Le commit
correctif ne touche que `docker-compose.yml`, comme annonce, aucun fichier hors perimetre.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| AC1 | Sauvegarde corrompue/anormalement petite detectee automatiquement, alerte visible (log critique min.), pas de "OK" silencieux | Couvert — mecanisme verifie avec le defaut (1024, non-regression) ET avec un seuil operateur non-defaut (99999), les deux effectivement appliques en conteneur reel. Le finding bloquant du premier passage (seuil non cable) est resolu et reverifie independamment. |
| AC2 | Procedure de restauration de test existe, declenchable facilement (script/commande documentee) | Couvert — deja valide au premier passage, reconfirme sans regression ce passage-ci (service `restore-test` demarre, cycle complet `OK`, montage `/backups:ro`, pas de `depends_on: db`). |
| AC3 | Restauration de test executee au moins une fois manuellement de bout en bout, succes valide avant mise en production | Couvert — reproduit une nouvelle fois independamment ce passage-ci : `[restore-test] OK — restauration et verification reussies (creditflow-20260909-184107.sql.gz)` sur une archive reellement produite par le service `backup` de cette branche pendant la session de review. |

## Findings

Aucun finding bloquant restant. Les deux observations non bloquantes du premier passage (sortie
bruyante de `psql -q` dans les logs de `restore-test.sh`, et le probleme CRLF preexistant a #64
sur les scripts bind-montes sous Windows) restent valables mais hors perimetre de ce ticket — ni
l'un ni l'autre n'a de rapport avec le correctif de ce commit et aucun des deux ne bloque
l'approbation. Ce sont des candidats a un ticket dedie, pas des regressions introduites par #64.

## Verifications independantes effectuees (deuxieme passage)

- Diff du commit correctif (`git show 2c6a723`) : un seul fichier modifie,
  `docker-compose.yml`, une seule ligne ajoutee
  (`BACKUP_MIN_SIZE_BYTES: ${BACKUP_MIN_SIZE_BYTES:-1024}`), au bon endroit (bloc `environment:`
  du service `backup`, au meme niveau que `BACKUP_RETENTION_DAYS`). `git diff-tree --name-only`
  confirme qu'aucun autre fichier n'est touche par ce commit.
- Diff complet du ticket sur `docker-compose.yml` (`git diff master...HEAD -- docker-compose.yml`) :
  conforme au contrat technique de `spec.md` de bout en bout, y compris apres le correctif —
  service `backup` avec les trois variables de seuil/intervalle/retention cablees, service
  `restore-test` avec `user: postgres`, pas de `depends_on`, volumes `:ro`, entrypoint attendu.
- Reproduction Docker reelle du scenario du finding bloquant precedent (contournement du CRLF
  preexistant via des copies LF montees depuis le repertoire scratch de session, hors depot —
  `docker-compose.yml` lui-meme non modifie pour ce test, seul un fichier `override.yml` de
  session a servi a remplacer les scripts montes) :
  - `BACKUP_MIN_SIZE_BYTES=99999 docker compose up -d db backup` puis
    `docker exec creditflow-backup env | grep -i BACKUP` donne
    `BACKUP_MIN_SIZE_BYTES=99999` — propagation confirmee (echouait avec `[]` au premier passage).
  - Log du conteneur : `CRITIQUE : sauvegarde corrompue ou anormalement petite, mise en
    quarantaine (/backups/creditflow-20260909-184016.sql.gz)`. Verification que ce n'est pas une
    coincidence : le fichier deplace fait 12629 octets et `gunzip -t` le confirme structurellement
    valide — la quarantaine est due exclusivement au seuil eleve, pas a une corruption reelle.
  - Recreation du conteneur `backup` sans `BACKUP_MIN_SIZE_BYTES` positionne dans le shell hote :
    `docker exec creditflow-backup env | grep -i BACKUP` donne `BACKUP_MIN_SIZE_BYTES=1024`
    (defaut interpole par docker-compose), et le cycle de sauvegarde reel produit
    `OK  creditflow-20260909-184107.sql.gz (16.0K)` — non-regression du comportement par defaut
    confirmee.
  - Service `restore-test` redemarre (memes copies LF de session) : cycle immediat donne
    `[restore-test] OK — restauration et verification reussies
    (creditflow-20260909-184107.sql.gz)` sur l'archive reelle produite par `backup` durant cette
    session — confirme que le reste du pipeline (backup -> restore-test) fonctionne toujours de
    bout en bout apres le correctif.
  - `docker exec creditflow-restore-test id` donne `uid=70(postgres) gid=70(postgres)` — pas de
    regression sur l'execution non-root.
  - `docker compose config` (sans override) : service `backup` resout bien
    `BACKUP_MIN_SIZE_BYTES: "1024"` par defaut ; aucun `depends_on` pour `restore-test`.
- Nettoyage post-review : conteneurs et reseau supprimes (`docker compose down`), fichiers de
  sauvegarde/quarantaine generes pendant cette session de tests supprimes manuellement
  (`backups/*.sql.gz` et `backups/quarantine/*` sont de toute facon ignores par
  `backups/.gitignore`), fichiers `override*.yml` et copies de scripts LF crees uniquement dans le
  repertoire scratch de session (hors depot). `git status --short` vide avant et apres.

## Build/tests

- `docker version` / `docker compose version` : Docker Desktop 4.54.0 (moteur 29.1.2),
  Compose v2.40.3 — OK.
- `docker compose config` sur `docker-compose.yml` de la branche (sans override) : parse sans
  erreur, `BACKUP_MIN_SIZE_BYTES: "1024"` resolu par defaut pour le service `backup` — OK.
- Scenarios Docker reels detailles ci-dessus (propagation de la variable, seuil non-defaut
  applique, defaut non-regresse, cycle `restore-test` complet, utilisateur non-root) — tous
  passent.
- Aucun changement backend/frontend dans ce diff (perimetre strictement infra/scripts, confirme
  par `git diff --stat` du commit correctif comme du diff complet du ticket) — pas de build
  Maven/npm necessaire.

## Conclusion

Le finding bloquant du premier passage est corrige et reverifie independamment en conditions
reelles (pas seulement relu dans le diff), sans regression detectee sur le reste de
l'implementation deja validee. Approbation.
