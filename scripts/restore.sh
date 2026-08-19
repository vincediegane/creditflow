#!/bin/sh
# =====================================================================
# Restauration d'une sauvegarde.
#   ./scripts/restore.sh backups/creditflow-20260805-020000.sql.gz
#
# ATTENTION : ecrase integralement la base actuelle.
# =====================================================================
set -eu

cd "$(dirname "$0")/.."

FILE="${1:-}"
DB_NAME="${DB_NAME:-creditflow}"
DB_USERNAME="${DB_USERNAME:-creditflow}"

if [ -z "$FILE" ]; then
    echo "Usage : ./scripts/restore.sh <fichier .sql.gz>"
    echo
    echo "Sauvegardes disponibles :"
    ls -lht backups/*.sql.gz 2>/dev/null || echo "  (aucune)"
    exit 1
fi

if [ ! -f "$FILE" ]; then
    echo "Fichier introuvable : $FILE"
    exit 1
fi

echo "=============================================================="
echo " RESTAURATION — la base '$DB_NAME' va etre ECRASEE"
echo " Fichier : $FILE"
echo "=============================================================="
if [ "${FORCE:-}" != "1" ]; then
    printf "Taper OUI pour confirmer : "
    read -r CONFIRM
    if [ "$CONFIRM" != "OUI" ]; then
        echo "Restauration annulee."
        exit 1
    fi
fi

# Le backend est arrete pendant la restauration : il ne doit pas ecrire
# dans une base en cours de reconstruction.
echo "Arret du backend..."
docker compose stop backend >/dev/null 2>&1 || true

echo "Restauration en cours..."
gunzip -c "$FILE" | docker compose exec -T db psql -q -U "$DB_USERNAME" -d "$DB_NAME"

echo "Redemarrage du backend..."
docker compose start backend >/dev/null

echo "Restauration terminee."
