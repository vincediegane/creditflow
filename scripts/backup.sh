#!/bin/sh
# =====================================================================
# Sauvegarde manuelle immediate.
#   ./scripts/backup.sh
# Le fichier est ecrit dans ./backups/
# =====================================================================
set -eu

cd "$(dirname "$0")/.."

DB_NAME="${DB_NAME:-creditflow}"
DB_USERNAME="${DB_USERNAME:-creditflow}"
STAMP=$(date +%Y%m%d-%H%M%S)
TARGET="backups/creditflow-manuel-$STAMP.sql.gz"

mkdir -p backups

echo "Sauvegarde en cours..."
docker compose exec -T db \
    pg_dump --clean --if-exists --no-owner --no-privileges \
            -U "$DB_USERNAME" -d "$DB_NAME" \
    | gzip -9 > "$TARGET.part"

mv "$TARGET.part" "$TARGET"

echo "Sauvegarde terminee : $TARGET"
ls -lh "$TARGET"
echo
echo "Pensez a copier ce fichier hors de cette machine."
