#!/bin/sh
# =====================================================================
# Sauvegarde manuelle immediate.
#   ./scripts/backup.sh
# Le fichier est ecrit dans ./backups/
# =====================================================================
set -eu

cd "$(dirname "$0")/.."

. ./scripts/backup-verify.sh

DB_NAME="${DB_NAME:-creditflow}"
DB_USERNAME="${DB_USERNAME:-creditflow}"
STAMP=$(date +%Y%m%d-%H%M%S)
TARGET="backups/creditflow-manuel-$STAMP.sql.gz"
RAW="backups/creditflow-manuel-$STAMP.sql.raw"

mkdir -p backups

echo "Sauvegarde en cours..."
if docker compose exec -T db \
    pg_dump --clean --if-exists --no-owner --no-privileges \
            -U "$DB_USERNAME" -d "$DB_NAME" \
    > "$RAW"; then
    if gzip -9 "$RAW" && mv "$RAW.gz" "$TARGET"; then
        if backup_verify "$TARGET"; then
            echo "Sauvegarde terminee : $TARGET"
            ls -lh "$TARGET"
            echo
            echo "Pensez a copier ce fichier hors de cette machine."
        else
            echo "CRITIQUE : sauvegarde corrompue ou anormalement petite, mise en quarantaine ($TARGET)" >&2
            exit 1
        fi
    else
        rm -f "$RAW" "$RAW.gz"
        echo "[backup] CRITIQUE — echec de la compression/deplacement de la sauvegarde" >&2
        exit 1
    fi
else
    rm -f "$RAW"
    echo "[backup] CRITIQUE — echec de la sauvegarde (voir la sortie pg_dump ci-dessus)" >&2
    exit 1
fi
