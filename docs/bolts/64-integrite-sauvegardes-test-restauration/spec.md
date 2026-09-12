# Spec — Issue #64 : intégrité des sauvegardes et test périodique de restauration

## Résumé

Ajouter une vérification automatique post-sauvegarde (taille plancher + `gunzip -t`) avec mise en quarantaine et log critique en cas d'échec, ainsi qu'un service Docker `restore-test` exécutant hebdomadairement une restauration de test dans une instance PostgreSQL éphémère isolée, invocable aussi manuellement.

## Tâches

### Vérification post-sauvegarde

- [ ] `scripts/backup-verify.sh` (nouveau) : script sourcable définissant une fonction `backup_verify "$TARGET"` qui (1) vérifie que la taille du fichier est `>= ${BACKUP_MIN_SIZE_BYTES:-1024}` octets, (2) exécute `gunzip -t "$TARGET"`. En cas d'échec sur l'un des deux contrôles : déplace `$TARGET` vers `"$(dirname "$TARGET")/quarantine/"` (créé avec `mkdir -p` si absent) et retourne un code non-zéro. En cas de succès, retourne 0 sans effet de bord. Ne pas imprimer le message CRITIQUE lui-même dans ce script (laisser l'appelant logger, pour rester cohérent avec le style de log déjà existant dans `backup.sh` et `backup-loop.sh`).
- [ ] `scripts/backup.sh` : insérer l'appel à `backup_verify` entre le `mv "$TARGET.part" "$TARGET"` et l'`echo "Sauvegarde terminee..."`. En cas d'échec : `echo "CRITIQUE : sauvegarde corrompue ou anormalement petite, mise en quarantaine ($TARGET)"` sur stderr, puis `exit 1`.
- [ ] `scripts/backup-loop.sh` : insérer l'appel à `backup_verify` entre le `mv "$TARGET.part" "$TARGET"` de la boucle et le `log "OK ..."`, sous forme `if backup_verify "$TARGET"; then log "OK ..."; else log "CRITIQUE : ..."; fi` — **ne pas** laisser un échec interrompre la boucle `while true` (cohérent avec le traitement existant de l'échec `pg_dump` juste en dessous, qui continue au cycle suivant).
- [ ] `scripts/backup-loop.sh` : ajuster la commande de purge par rétention (`find "$DIR" -name 'creditflow-*.sql.gz' -mtime "+$RETENTION_DAYS" -print -delete`) pour exclure `$DIR/quarantine/` du balayage (ex. `-not -path "*/quarantine/*"`). Sans ce correctif, les fichiers mis en quarantaine seraient supprimés automatiquement au bout de `BACKUP_RETENTION_DAYS` jours par le mécanisme de rétention existant, ce qui efface la preuve d'un échec avant qu'un humain ait pu l'investiguer — voir **Écarts identifiés**.

### Restauration de test

- [ ] `scripts/restore-test.sh` (nouveau) :
  - Sélectionne la dernière archive valide : `ls -t "$DIR"/*.sql.gz 2>/dev/null | head -1` sur le répertoire de premier niveau uniquement (n'utilise pas `find ... -printf`, non disponible de façon fiable sous busybox/Alpine — `postgres:16-alpine` embarque des utilitaires limités ; `ls -t` évite le problème et suffit).
  - Si aucune archive trouvée (première installation, tout en quarantaine) : log `[restore-test] SKIP — aucune sauvegarde valide disponible`, `exit 0` (pas d'échec CRITIQUE — éviter le faux positif au premier déploiement).
  - Initialise une instance PostgreSQL éphémère locale dans un répertoire temporaire non persistant (`TMPDATA=$(mktemp -d)`, `initdb -D "$TMPDATA/data" ...`, `pg_ctl -D "$TMPDATA/data" -o "-k $TMPDATA -h ''" -l "$TMPDATA/log" start` — socket Unix uniquement, pas d'écoute TCP, pour éviter tout conflit de port).
  - **Point non couvert par le design, à traiter explicitement** : `initdb` refuse de s'exécuter en tant que root. Le service Docker `restore-test` doit soit définir `user: postgres` dans `docker-compose.yml`, soit le script doit basculer via `su-exec postgres` (présent dans l'image `postgres:16-alpine`, utilisé en interne par `docker-entrypoint.sh`). Sans ce point, le mécanisme échoue systématiquement en CRITIQUE dès le premier cycle. Voir **Écarts identifiés**.
  - Crée une base (`createdb -h "$TMPDATA" restore_test`), restaure via `gunzip -c "$ARCHIVE" | psql -h "$TMPDATA" -d restore_test -v ON_ERROR_STOP=1 -q`.
  - Vérifie via `psql -h "$TMPDATA" -d restore_test -tAc "..."` : `to_regclass('public.organizations')`, `('public.users')`, `('public.credit_sales')`, `('public.installments')`, `('public.payments')`, `('public.flyway_schema_history')` tous non nuls, et `SELECT count(*) FROM flyway_schema_history` `>= 1`.
  - Arrête proprement (`pg_ctl -D "$TMPDATA/data" stop -m fast`) et supprime `$TMPDATA` (`trap 'pg_ctl ... stop -m fast 2>/dev/null; rm -rf "$TMPDATA"' EXIT` pour garantir le nettoyage même en cas d'échec).
  - Code de sortie non-zéro + `echo "[restore-test] CRITIQUE : ..."` sur tout échec (restauration ou vérification de contenu).
  - Ne monte/n'écrit jamais dans `backups/` (lecture seule) ni dans `backups/quarantine/`.
- [ ] `scripts/restore-test-loop.sh` (nouveau) : wrapper boucle hebdomadaire, calqué sur `scripts/backup-loop.sh` : exécution immédiate au démarrage puis toutes les `${RESTORE_TEST_INTERVAL_HOURS:-168}` heures, appelle `scripts/restore-test.sh`, logue le résultat (`OK` / `SKIP` / `CRITIQUE`) sans jamais laisser un échec arrêter la boucle.
- [ ] `docker-compose.yml` : nouveau service `restore-test` :
  - `image: postgres:16-alpine`, `container_name: creditflow-restore-test`, `restart: unless-stopped`.
  - `user: postgres` (nécessaire pour `initdb`, voir ci-dessus).
  - **Pas de** `depends_on: db` (conforme au design — instance totalement isolée).
  - `environment`: `RESTORE_TEST_INTERVAL_HOURS: ${RESTORE_TEST_INTERVAL_HOURS:-168}`, `TZ: ${TZ:-Africa/Dakar}` (cohérence avec le service `backup` existant qui définit déjà `TZ`).
  - `volumes`: `./backups:/backups:ro`, `./scripts/restore-test-loop.sh:/usr/local/bin/restore-test-loop.sh:ro`, `./scripts/restore-test.sh:/usr/local/bin/restore-test.sh:ro`.
  - `entrypoint: ['/bin/sh', '/usr/local/bin/restore-test-loop.sh']`.
  - Placer ce nouveau service après le service `backup` existant, avant `volumes:`.

### Configuration

- [ ] `.env.production.example` : sous la section existante `# --- Sauvegardes ---` (à côté de `BACKUP_INTERVAL_HOURS`/`BACKUP_RETENTION_DAYS`), ajouter `BACKUP_MIN_SIZE_BYTES=1024` et `RESTORE_TEST_INTERVAL_HOURS=168` avec un commentaire bref sur leur rôle.
- [ ] `.env.example` : ce fichier n'a actuellement aucune section Sauvegardes (les valeurs par défaut de `BACKUP_INTERVAL_HOURS`/`BACKUP_RETENTION_DAYS` ne viennent que des defaults inline de `docker-compose.yml`). Ajouter une nouvelle section `# Sauvegardes` avec uniquement les deux nouvelles variables (`BACKUP_MIN_SIZE_BYTES=1024`, `RESTORE_TEST_INTERVAL_HOURS=168`) — ne pas ajouter rétroactivement `BACKUP_INTERVAL_HOURS`/`BACKUP_RETENTION_DAYS`, hors périmètre de ce ticket.

### Documentation

- [ ] `README.md`, section `### Sauvegardes` : documenter (1) la vérification automatique post-sauvegarde (taille plancher, `gunzip -t`) et la mise en quarantaine dans `backups/quarantine/` avec log critique ; (2) le service `restore-test` et sa cadence hebdomadaire par défaut ; (3) la commande manuelle `docker compose exec restore-test sh /usr/local/bin/restore-test.sh` (voir remarque sur le bit exécutable ci-dessous) ; (4) compléter la ligne existante (« Testez une restauration avant la mise en service, puis une fois par trimestre ») en précisant qu'elle porte sur `restore.sh` en conditions réelles et que le test automatique hebdomadaire de `restore-test.sh` (instance jetable) la complète sans la remplacer.

### Validation opérationnelle (AC3)

- [ ] Exécuter réellement `scripts/restore-test.sh` une fois de bout en bout (via `docker compose exec restore-test sh /usr/local/bin/restore-test.sh` ou équivalent local), constater un `OK` et non un `CRITIQUE`/`SKIP`, et consigner la preuve (sortie de commande) dans la review de cette bolt avant la mise en production chez le premier client. Cette tâche n'est pas un changement de code mais une condition de passage en revue explicitement exigée par AC3.

## Contrat technique

**`scripts/backup-verify.sh`** (sourcé, pas exécuté directement) :
```sh
# Usage: . scripts/backup-verify.sh ; backup_verify "$TARGET"
# Retour 0 = OK, retour 1 = échoué et déplacé vers quarantine/
backup_verify() {
    TARGET="$1"
    MIN_SIZE="${BACKUP_MIN_SIZE_BYTES:-1024}"
    SIZE=$(wc -c < "$TARGET")
    if [ "$SIZE" -lt "$MIN_SIZE" ] || ! gunzip -t "$TARGET" 2>/dev/null; then
        QDIR="$(dirname "$TARGET")/quarantine"
        mkdir -p "$QDIR"
        mv "$TARGET" "$QDIR/"
        return 1
    fi
    return 0
}
```

**`scripts/restore-test.sh`** — codes de sortie et logs :
- Aucune archive disponible → `exit 0`, log `[restore-test] SKIP — ...`.
- Succès → `exit 0`, log `[restore-test] OK — restauration et vérification réussies (<fichier>)`.
- Échec (restauration ou vérification de contenu) → `exit 1`, log `[restore-test] CRITIQUE — ...`.

**`docker-compose.yml`** — service `restore-test` (extrait) :
```yaml
  restore-test:
    image: postgres:16-alpine
    container_name: creditflow-restore-test
    restart: unless-stopped
    user: postgres
    environment:
      RESTORE_TEST_INTERVAL_HOURS: ${RESTORE_TEST_INTERVAL_HOURS:-168}
      TZ: ${TZ:-Africa/Dakar}
    volumes:
      - ./backups:/backups:ro
      - ./scripts/restore-test-loop.sh:/usr/local/bin/restore-test-loop.sh:ro
      - ./scripts/restore-test.sh:/usr/local/bin/restore-test.sh:ro
    entrypoint: ['/bin/sh', '/usr/local/bin/restore-test-loop.sh']
```

**Variables d'environnement nouvelles** :
| Variable | Défaut | Fichiers |
|---|---|---|
| `BACKUP_MIN_SIZE_BYTES` | `1024` | `.env.example`, `.env.production.example`, `docker-compose.yml` (implicite via `backup-verify.sh`) |
| `RESTORE_TEST_INTERVAL_HOURS` | `168` | `.env.example`, `.env.production.example`, `docker-compose.yml` |

## Plan de tests

| Critère d'acceptation (ticket #64) | Test | Type |
|---|---|---|
| AC1 — sauvegarde corrompue ou anormalement petite détectée automatiquement, alerte visible (log critique min.), pas de "OK" silencieux | 1) Test unitaire/manuel de `backup-verify.sh` : forger un fichier `.gz` < 1024 octets et un `.gz` valide mais tronqué (corrompu après le header gzip) ; vérifier que `backup_verify` retourne 1 et déplace le fichier vers `quarantine/`. 2) Test d'intégration : simuler la signature du bug #63 (pipe qui compresse un flux vide) en exécutant `echo -n | gzip -9 > backups/creditflow-test.sql.gz.part && mv ...` puis lancer `backup.sh`/`backup-loop.sh` modifiés sur ce cas et vérifier le log `CRITIQUE` en sortie et l'absence de fichier `OK` silencieux. 3) Vérifier qu'un fichier de sauvegarde valide (~10-11 Ko réel) passe sans déclencher de quarantaine (non-régression). | Intégration / manuel |
| AC2 — procédure de restauration de test existe, déclenchable facilement (script/commande documentée) | 1) Exécuter `docker compose exec restore-test sh /usr/local/bin/restore-test.sh` manuellement et vérifier code de sortie 0 sur une archive valide. 2) Vérifier que la commande est bien documentée dans `README.md` section Sauvegardes. 3) Cas sans archive valide (dossier `backups/` vide ou tout en quarantaine) : vérifier `exit 0` + log `SKIP`, pas de `CRITIQUE`. 4) Cas archive corrompue seule disponible (en quarantaine) : vérifier qu'elle est bien ignorée par la sélection (`ls -t "$DIR"/*.sql.gz` sur le niveau racine uniquement). | Manuel / intégration |
| AC3 — procédure de restauration exécutée au moins une fois manuellement de bout en bout avant mise en production chez le premier client, succès validé | Exécution réelle documentée (voir tâche « Validation opérationnelle » ci-dessus) : lancer `restore-test.sh` contre une vraie archive produite par `backup.sh`/`backup-loop.sh` sur cette branche, observer `OK`, consigner la sortie complète (horodatage, nom de fichier restauré, résultat des vérifications de tables) dans la review de la bolt. | Manuel (obligatoire, non automatisable par nature du critère) |
| Non-régression — boucle `backup-loop.sh` continue de tourner après un échec de vérification | Simuler un échec de `backup_verify` dans `backup-loop.sh` (via fichier forgé trop petit) et vérifier que la boucle `while true` enchaîne bien sur le cycle suivant (pas d'arrêt du conteneur `backup`). | Intégration |
| Non-régression — service `restore-test` ne dépend pas de `db` et ne peut pas écrire dans `backups/` | Vérifier `docker compose config` (pas de `depends_on: db` pour `restore-test`) et tenter une écriture dans `/backups` depuis le conteneur `restore-test` (doit échouer, montage `:ro`). | Manuel / intégration |
| Non-régression — `initdb` ne casse pas en root | Démarrer le service `restore-test` et vérifier dans les logs qu'il n'y a pas d'erreur `initdb: error: cannot be run as root` (confirme que `user: postgres` — ou l'alternative `su-exec` — est effectivement en place). | Intégration |

## Écarts identifiés

- **`initdb` et l'exécution en root** : le design ne mentionne pas que `initdb` refuse de s'exécuter en tant que root, alors que le service `restore-test` (comme le service `backup` existant) tournera par défaut en root s'il override l'`entrypoint` par défaut de l'image `postgres:16-alpine` sans préciser `user:`. Sans correctif, `restore-test.sh` échouerait systématiquement dès le premier cycle (toujours CRITIQUE), ce qui contredit directement AC1/AC2. Résolution proposée dans cette spec : `user: postgres` sur le service Docker. À valider avant codage — alternative possible : wrapper `su-exec postgres` dans le script si `user: postgres` pose un problème de permissions sur les fichiers montés en lecture seule.
- **Rétention et quarantaine** : le design ne précise pas si les fichiers mis en quarantaine doivent survivre à la purge par rétention (`BACKUP_RETENTION_DAYS`, dans `backup-loop.sh`). Tel quel, le `find` de rétention existant balaierait aussi `backups/quarantine/` et supprimerait les preuves d'échec après `BACKUP_RETENTION_DAYS` jours (14 par défaut), ce qui peut être en tension avec l'esprit d'AC1 (alerte visible, pas d'effacement silencieux de la preuve). Cette spec propose d'exclure `quarantine/` de la purge automatique — à confirmer avant codage, car cela signifie que `backups/quarantine/` grossira indéfiniment sans purge propre à ce ticket (pas de politique de rétention pour la quarantaine elle-même, jugé hors périmètre ici mais à surveiller).
- **Bit exécutable sur environnement Windows** : la commande manuelle documentée par le design (`docker compose exec restore-test restore-test.sh`) suppose que `restore-test.sh` est exécutable et sur le `PATH` du conteneur. Les montages bind depuis un hôte Windows (cf. environnement de dev de ce dépôt) ne préservent pas toujours fidèlement le bit exécutable Unix. Cette spec recommande d'invoquer explicitement `sh /usr/local/bin/restore-test.sh` (documenté dans le README et utilisé dans le plan de tests) pour rester robuste indépendamment du bit exécutable, plutôt que de dépendre de la résolution de `PATH` + permissions.
