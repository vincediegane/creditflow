#!/bin/sh
# =====================================================================
# Restauration de test dans une instance PostgreSQL ephemere et isolee.
#
# Restaure la derniere sauvegarde valide (hors quarantine/) dans une base
# jetable (socket Unix locale uniquement, aucune ecoute TCP) et verifie la
# presence des tables cle. Ne touche jamais ./backups (lecture seule) ni le
# service `db` de production.
#
#   docker compose exec restore-test sh /usr/local/bin/restore-test.sh
#
# Codes de sortie : 0 = OK ou SKIP (aucune sauvegarde disponible),
#                    1 = CRITIQUE (restauration ou verification echouee).
# =====================================================================
set -eu

DIR="${BACKUP_DIR:-/backups}"

log() {
    echo "[restore-test] $(date '+%Y-%m-%d %H:%M:%S') $1"
}

fail() {
    log "CRITIQUE — $1"
    exit 1
}

ARCHIVE=$(ls -t "$DIR"/*.sql.gz 2>/dev/null | head -1)
if [ -z "$ARCHIVE" ]; then
    log "SKIP — aucune sauvegarde valide disponible"
    exit 0
fi

TMPDATA=$(mktemp -d)
DBNAME="restore_test"

cleanup() {
    pg_ctl -D "$TMPDATA/data" stop -m fast >/dev/null 2>&1
    rm -rf "$TMPDATA"
}
trap cleanup EXIT

initdb -D "$TMPDATA/data" -U postgres --auth=trust >"$TMPDATA/initdb.log" 2>&1 \
    || fail "initdb a echoue ($(tail -1 "$TMPDATA/initdb.log"))"

pg_ctl -D "$TMPDATA/data" -o "-k $TMPDATA -h ''" -l "$TMPDATA/log" start >/dev/null 2>&1 \
    || fail "demarrage de l'instance ephemere impossible ($(tail -1 "$TMPDATA/log"))"

createdb -h "$TMPDATA" "$DBNAME" || fail "creation de la base de test impossible"

if ! gunzip -c "$ARCHIVE" | psql -h "$TMPDATA" -d "$DBNAME" -v ON_ERROR_STOP=1 -q; then
    fail "restauration echouee ($ARCHIVE)"
fi

for TABLE in organizations users credit_sales installments payments flyway_schema_history; do
    if ! EXISTS=$(psql -h "$TMPDATA" -d "$DBNAME" -tAc "SELECT to_regclass('public.$TABLE')"); then
        fail "verification de la table $TABLE impossible"
    fi
    if [ -z "$EXISTS" ]; then
        fail "table $TABLE absente apres restauration"
    fi
done

if ! COUNT=$(psql -h "$TMPDATA" -d "$DBNAME" -tAc "SELECT count(*) FROM flyway_schema_history"); then
    fail "lecture de flyway_schema_history impossible"
fi
if [ "$COUNT" -lt 1 ]; then
    fail "flyway_schema_history vide apres restauration"
fi

log "OK — restauration et verification reussies ($(basename "$ARCHIVE"))"
