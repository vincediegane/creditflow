#!/bin/sh
# =====================================================================
# Sauvegarde manuelle immediate.
#   ./scripts/backup.sh
# Le fichier est ecrit dans ./backups/
# =====================================================================
set -eu
set -o pipefail

cd "$(dirname "$0")/.."

DB_NAME="${DB_NAME:-creditflow}"
DB_USERNAME="${DB_USERNAME:-creditflow}"
STAMP=$(date +%Y%m%d-%H%M%S)
TARGET="backups/creditflow-manuel-$STAMP.sql.gz"

mkdir -p backups

echo "Sauvegarde en cours..."
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
