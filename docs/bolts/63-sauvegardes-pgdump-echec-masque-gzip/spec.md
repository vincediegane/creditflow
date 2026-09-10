# Spec — #63 Sauvegardes automatiques : pg_dump en echec masque par gzip, backups vides sans alerte

## Résumé

Rendre visibles les échecs de `pg_dump` dans `scripts/backup.sh` et `scripts/backup-loop.sh` (au lieu d'être masqués par le succès de `gzip` sur un flux vide), et purger les 5 sauvegardes corrompues déjà présentes dans `backups/`.

## Tâches

- [ ] `scripts/backup-loop.sh` : ajouter `set -o pipefail` immédiatement après `set -eu` (ligne 14 actuelle), avant la définition de `DIR` (ligne 16). Aucun autre changement structurel requis : le `if pg_dump ... | gzip ...; then ... else ... fi` existant (lignes 33-40 actuelles) reflétera alors le vrai code de sortie de `pg_dump`.
- [ ] `scripts/backup-loop.sh` : renforcer le message de la branche d'échec (ligne 39 actuelle, `log "ECHEC de la sauvegarde : ..."`) pour qu'il porte explicitement un niveau critique, par exemple `log "CRITIQUE — echec de la sauvegarde : $(tr '\n' ' ' < /tmp/pg_dump.err)"`. Le préfixe `[backup]` déjà émis par `log()` (ligne 23) reste ; on ajoute le mot `CRITIQUE` dans le message lui-même pour qu'il soit grep-able/distinguable de la ligne `OK` dans les logs docker (`docker compose logs backup`).
- [ ] `scripts/backup.sh` : ajouter `set -o pipefail` immédiatement après `set -eu` (ligne 7 actuelle), avant `cd "$(dirname "$0")/.."` (ligne 9).
- [ ] `scripts/backup.sh` : remplacer le bloc inconditionnel lignes 19-29 actuelles (`docker compose exec ... | gzip -9 > "$TARGET.part"` suivi d'un `mv` et des messages de succès sans aucune vérification) par une structure `if ... ; then mv + messages de succès ; else rm -f "$TARGET.part" + message d'erreur explicite niveau critique sur stderr + exit 1 ; fi`, sur le modèle de `scripts/backup-loop.sh`. Le pipeline `pg_dump` lui-même (arguments `--clean --if-exists --no-owner --no-privileges -U "$DB_USERNAME" -d "$DB_NAME"`, lignes 20-21 actuelles) ne change pas.
- [ ] `backups/` : supprimer les 5 fichiers corrompus identifiés (~20 octets chacun, résidu de `gzip` sur un flux `pg_dump` vide) :
  - `backups/creditflow-20260822-180402.sql.gz`
  - `backups/creditflow-20260824-100002.sql.gz`
  - `backups/creditflow-20260825-151739.sql.gz`
  - `backups/creditflow-20260827-090429.sql.gz`
  - `backups/creditflow-20260829-231928.sql.gz`

  Aucun autre fichier du dossier n'est concerné (les fichiers légitimes restants pèsent plusieurs Ko). Le dossier n'étant pas suivi par git (`backups/.gitignore` = `*` + `!.gitkeep`), cette suppression ne produira aucun diff — le signaler explicitement dans le message de commit et/ou la description de PR.
- [ ] `README.md`, section "Sauvegardes" (lignes 52-65 actuelles) : ajouter une phrase précisant qu'un `pg_dump` en échec fait désormais échouer le script (code de sortie non nul, message explicite) plutôt que de produire un fichier vide silencieux — à placer près de la ligne 65 existante sur le test de restauration.

## Contrat technique

### `scripts/backup-loop.sh` — diff logique attendu

```sh
set -eu
set -o pipefail        # <-- ajout, ligne 14→15

...
    if pg_dump --clean --if-exists --no-owner --no-privileges 2>/tmp/pg_dump.err \
        | gzip -9 > "$TARGET.part"; then
        mv "$TARGET.part" "$TARGET"
        log "OK  $(basename "$TARGET") ($(du -h "$TARGET" | cut -f1))"
    else
        rm -f "$TARGET.part"
        log "CRITIQUE — echec de la sauvegarde : $(tr '\n' ' ' < /tmp/pg_dump.err)"   # <-- message renforcé
    fi
```

Comportement inchangé par ailleurs : rétention (lignes 42-45), boucle `while true` (ligne 28/48), intervalle `sleep` (ligne 47). Le script continue de tourner (`set -eu` ne fait pas sortir le `while` sur un échec de `pg_dump` puisque le test est dans un `if`) : c'est le comportement voulu, un échec ponctuel ne doit pas arrêter le conteneur de sauvegarde, juste être loggé de façon visible et ne pas produire de fichier partiel.

### `scripts/backup.sh` — diff logique attendu

```sh
set -eu
set -o pipefail        # <-- ajout, ligne 7→8

...
if docker compose exec -T db \
    pg_dump --clean --if-exists --no-owner --no-privileges \
            -U "$DB_USERNAME" -d "$DB_NAME" \
    | gzip -9 > "$TARGET.part"; then
    mv "$TARGET.part" "$TARGET"
    echo "Sauvegarde terminee : $TARGET"
    ls -lh "$TARGET"
    echo
    echo "Pensez a copier ce fichier hors de cette machine."
else
    rm -f "$TARGET.part"
    echo "[backup] CRITIQUE — echec de la sauvegarde (voir la sortie pg_dump ci-dessus)" >&2
    exit 1
fi
```

À la différence de `backup-loop.sh`, `backup.sh` est un script manuel, interactif, exécuté au premier plan : la sortie stderr de `docker compose exec`/`pg_dump` s'affiche déjà directement au terminal sans redirection, pas besoin d'un fichier `/tmp/pg_dump.err` intermédiaire. Le script doit en revanche sortir avec un code non nul (`exit 1`) pour que l'appelant (humain ou tout automatisme amont) détecte l'échec, ce qui n'existe pas actuellement.

### Codes de sortie / comportement observable après fix

| Scénario | `backup.sh` | `backup-loop.sh` |
|---|---|---|
| `pg_dump` réussit | exit 0, `$TARGET` créé, message "Sauvegarde terminee" | log "OK ...", `$TARGET` créé, boucle continue |
| `pg_dump` échoue | exit 1, aucun `$TARGET` ni `$TARGET.part` conservé, message d'erreur explicite sur stderr | log "CRITIQUE — echec ...", aucun `$TARGET` ni `$TARGET.part` conservé, boucle continue (retente au prochain intervalle) |

## Plan de tests

| Critère d'acceptation (ticket #63) | Test | Type |
|---|---|---|
| Un échec simulé de `pg_dump` fait échouer le script avec un code de sortie non nul et un message d'erreur explicite, sans fichier de sauvegarde vide | 1) `scripts/backup.sh` : lancer avec `DB_USERNAME` invalide (ex. `DB_USERNAME=inexistant ./scripts/backup.sh`) ou base arrêtée (`docker compose stop db` puis lancer le script) ; vérifier `echo $?` != 0, présence du message d'erreur, absence de `backups/creditflow-manuel-*.sql.gz` et de `*.sql.gz.part` créé par cette exécution. 2) `scripts/backup-loop.sh` : à exécuter réellement dans un conteneur `postgres:16-alpine` (`/bin/sh` = BusyBox ash) avec `PGPASSWORD` invalide ou `db` arrêté ; vérifier dans les logs docker (`docker compose logs backup`) la ligne "CRITIQUE", et l'absence de fichier `.sql.gz`/`.sql.gz.part` correspondant dans `./backups`. À exécuter réellement (pas seulement relu) car `pipefail` est une extension non-POSIX — le comportement doit être confirmé dans dash/dérivés (Git Bash côté hôte) ET BusyBox ash (conteneur `postgres:16-alpine`). | Intégration manuelle (les deux contextes d'exécution réels) |
| Un succès de `pg_dump` continue de produire une sauvegarde valide, sans régression | Lancer `scripts/backup.sh` et `scripts/backup-loop.sh` (ou un cycle du conteneur `backup`) en conditions normales (base up, identifiants corrects) ; vérifier `exit 0`, présence du fichier `.sql.gz` de taille cohérente (plusieurs Ko, pas ~20 octets), message "Sauvegarde terminee"/"OK" affiché, et que le fichier restauré via `scripts/restore.sh` (déjà existant, hors périmètre de modification) contient bien les données attendues. | Intégration manuelle |
| Les sauvegardes existantes anormalement petites (~20 octets) sont identifiées et traitées | Vérifier après purge que les 5 fichiers listés ci-dessus n'existent plus dans `backups/`, et que les fichiers légitimes restants sont inchangés. Documenter cette suppression dans le message de commit puisqu'elle n'apparaîtra dans aucun diff git (`backups/` est git-ignoré). | Vérification manuelle (`ls backups/`) |

## Écarts identifiés

Aucun écart entre le design et les critères d'acceptation du ticket. Deux précisions factuelles à noter pour le codeur (déjà tranchées par le design, pas des trous à combler) :

- Le ticket cite 4 dates (24, 25, 27, 29 août) ; l'inspection réelle du dossier montre 5 fichiers corrompus, avec une occurrence supplémentaire le 22 août (`creditflow-20260822-180402.sql.gz`). Confirmé par inspection directe (comptage de lignes binaires : ce fichier et les 4 autres cités ci-dessus ont exactement 1 "ligne" détectée contre 23 à 51 pour les fichiers légitimes du même dossier) — à traiter comme les 4 autres, purge incluse.
- Aucune alerte externe (email/SMS/webhook) n'est demandée par le ticket ni ajoutée ici ; le filet de détection permanent (vérification d'intégrité à chaque cycle, test de restauration automatique) est explicitement hors périmètre, renvoyé à l'issue #64.
