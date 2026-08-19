#!/bin/sh
# =====================================================================
# Sauvegarde automatique de la base CreditFlow.
#
# Tourne dans son propre conteneur : une sauvegarde immediate au demarrage,
# puis toutes les BACKUP_INTERVAL_HOURS heures. Les fichiers sont ecrits dans
# ./backups sur la machine hote, de facon a survivre a la suppression des
# conteneurs et des volumes.
#
# IMPORTANT : ce repertoire doit etre recopie regulierement hors de la machine
# (disque externe, cloud). Une sauvegarde qui reste sur le disque qui tombe en
# panne ne protege de rien.
# =====================================================================
set -eu

DIR="${BACKUP_DIR:-/backups}"
INTERVAL_HOURS="${BACKUP_INTERVAL_HOURS:-24}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"

mkdir -p "$DIR"

log() {
    echo "[backup] $(date '+%Y-%m-%d %H:%M:%S') $1"
}

log "Demarrage — intervalle ${INTERVAL_HOURS}h, retention ${RETENTION_DAYS} jours, cible $DIR"

while true; do
    STAMP=$(date +%Y%m%d-%H%M%S)
    TARGET="$DIR/creditflow-$STAMP.sql.gz"

    # --clean/--if-exists : le fichier peut etre rejoue sur une base existante.
    if pg_dump --clean --if-exists --no-owner --no-privileges 2>/tmp/pg_dump.err \
        | gzip -9 > "$TARGET.part"; then
        mv "$TARGET.part" "$TARGET"
        log "OK  $(basename "$TARGET") ($(du -h "$TARGET" | cut -f1))"
    else
        rm -f "$TARGET.part"
        log "ECHEC de la sauvegarde : $(tr '\n' ' ' < /tmp/pg_dump.err)"
    fi

    DELETED=$(find "$DIR" -name 'creditflow-*.sql.gz' -mtime "+$RETENTION_DAYS" -print -delete | wc -l)
    if [ "$DELETED" -gt 0 ]; then
        log "$DELETED sauvegarde(s) expiree(s) supprimee(s)"
    fi

    sleep $((INTERVAL_HOURS * 3600))
done
