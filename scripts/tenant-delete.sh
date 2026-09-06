#!/bin/sh
# =====================================================================
# Suppression complete des donnees d'une organisation (IRREVERSIBLE).
#   ./scripts/tenant-delete.sh <org_id>
#
# Un export frais (scripts/tenant-export.sh) est execute automatiquement
# avant la suppression, sauf SKIP_EXPORT=1 (DECONSEILLE -- aucun outil de
# reimport n'existe, voir README).
# FORCE=1 desactive la confirmation interactive (automatisation).
# =====================================================================
set -eu

cd "$(dirname "$0")/.."

ORG_ID="${1:-}"
DB_NAME="${DB_NAME:-creditflow}"
DB_APP_USERNAME="${DB_APP_USERNAME:-creditflow_app}"
DB_MIGRATION_USERNAME="${DB_MIGRATION_USERNAME:-creditflow}"
UPLOAD_DIR_CONTAINER="${UPLOAD_DIR_CONTAINER:-/app/data/uploads}"
STAMP=$(date +%Y%m%d-%H%M%S)

if [ -z "$ORG_ID" ]; then
    echo "Usage : ./scripts/tenant-delete.sh <org_id>"
    exit 1
fi

case "$ORG_ID" in
    ''|*[!0-9]*)
        echo "org_id invalide (doit etre un entier positif) : $ORG_ID"
        exit 1
        ;;
esac

# Lecture du nom de l'organisation via le role de migration (bypass RLS,
# independant du mecanisme SET app.current_org_id -- verification de
# reference, voir design.md).
ORG_NAME=$(docker compose exec -T db psql -tA -v ON_ERROR_STOP=1 \
    -U "$DB_MIGRATION_USERNAME" -d "$DB_NAME" \
    -c "SELECT name FROM organizations WHERE id = $ORG_ID" | tr -d '\r')

if [ -z "$ORG_NAME" ]; then
    echo "Organisation introuvable : $ORG_ID"
    exit 1
fi

if [ "${SKIP_EXPORT:-}" = "1" ]; then
    echo "SKIP_EXPORT=1 : AUCUN export prealable (DANGEREUX -- donnees non recuperables en cas d'erreur)."
else
    echo "Export prealable obligatoire (definir SKIP_EXPORT=1 pour desactiver, deconseille)..."
    ./scripts/tenant-export.sh "$ORG_ID" "$STAMP"
    echo "Export effectue : backups/tenant-exports/${ORG_ID}-${STAMP}.tar.gz"
fi

echo "=============================================================="
echo " SUPPRESSION DEFINITIVE — organisation id=$ORG_ID nom='$ORG_NAME'"
echo " Boutiques, utilisateurs, clients, produits, ventes, paiements et"
echo " pieces jointes de cette organisation seront supprimes."
echo " suppliers et penalty_settings (partages entre organisations) ne"
echo " sont pas touches."
echo "=============================================================="
if [ "${FORCE:-}" != "1" ]; then
    printf "Taper OUI pour confirmer : "
    read -r CONFIRM
    if [ "$CONFIRM" != "OUI" ]; then
        echo "Suppression annulee."
        exit 1
    fi
fi

echo "Collecte des fichiers physiques a supprimer..."
FILES_TMP=$(mktemp)
trap 'rm -f "$FILES_TMP"' EXIT

docker compose exec -T db psql -tA -q -v ON_ERROR_STOP=1 \
    -U "$DB_APP_USERNAME" -d "$DB_NAME" \
    -v org_id="$ORG_ID" > "$FILES_TMP" <<'SQL'
SELECT set_config('app.current_org_id', :'org_id', false)
\g /dev/null
SELECT photo_url FROM customers WHERE photo_url IS NOT NULL
UNION
SELECT file_url FROM sale_attachments WHERE file_url IS NOT NULL;
SQL

echo "Suppression des donnees en base (transaction unique)..."
docker compose exec -T db psql -v ON_ERROR_STOP=1 -q -U "$DB_APP_USERNAME" -d "$DB_NAME" \
    -v org_id="$ORG_ID" \
    < scripts/sql/tenant-delete.sql

echo "Suppression des fichiers physiques (stockage local uniquement, service backend)..."
if [ -s "$FILES_TMP" ]; then
    while IFS= read -r URL; do
        [ -z "$URL" ] && continue
        case "$URL" in
            /uploads/*)
                REL="${URL#/uploads/}"
                docker compose exec -T backend rm -f "$UPLOAD_DIR_CONTAINER/$REL" \
                    || echo "  echec de suppression (ignore) : $URL"
                ;;
            *)
                echo "  ignore (prefixe inattendu, hors stockage local -- ex. S3) : $URL"
                ;;
        esac
    done < "$FILES_TMP"
else
    echo "  aucun fichier a supprimer."
fi

echo "Verification post-suppression (role de migration, bypass RLS)..."
REMAINING=$(docker compose exec -T db psql -tA -v ON_ERROR_STOP=1 \
    -U "$DB_MIGRATION_USERNAME" -d "$DB_NAME" \
    -c "SELECT count(*) FROM organizations WHERE id = $ORG_ID" | tr -d '\r')
if [ "$REMAINING" != "0" ]; then
    echo "ANOMALIE : l'organisation $ORG_ID est toujours presente apres suppression."
    exit 1
fi

echo "Suppression terminee : organisation $ORG_ID ('$ORG_NAME') n'existe plus."
