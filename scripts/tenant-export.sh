#!/bin/sh
# =====================================================================
# Export CSV des donnees d'une seule organisation (isolation stricte :
# aucune autre organisation n'apparait dans le resultat, garantie par
# RLS + filtres explicites -- voir scripts/sql/tenant-export.sql).
#   ./scripts/tenant-export.sh <org_id> [horodatage]
#
# [horodatage] optionnel (format YYYYMMDD-HHMMSS) : fourni par
# tenant-delete.sh pour que l'export prealable et son message partagent
# le meme nom de fichier. Genere automatiquement si omis.
#
# Ecrit ./backups/tenant-exports/<org_id>-<horodatage>.tar.gz (un .csv
# par table une fois decompresse).
# =====================================================================
set -eu

cd "$(dirname "$0")/.."

ORG_ID="${1:-}"
STAMP="${2:-$(date +%Y%m%d-%H%M%S)}"
DB_NAME="${DB_NAME:-creditflow}"
DB_APP_USERNAME="${DB_APP_USERNAME:-creditflow_app}"

if [ -z "$ORG_ID" ]; then
    echo "Usage : ./scripts/tenant-export.sh <org_id> [horodatage]"
    exit 1
fi

case "$ORG_ID" in
    ''|*[!0-9]*)
        echo "org_id invalide (doit etre un entier positif) : $ORG_ID"
        exit 1
        ;;
esac

EXPORT_NAME="${ORG_ID}-${STAMP}"
CONTAINER_DIR="/tmp/tenant-export-$EXPORT_NAME"
ARCHIVE="backups/tenant-exports/$EXPORT_NAME.tar.gz"

mkdir -p backups/tenant-exports

# Nettoyage garanti du repertoire temporaire cote conteneur, meme en cas
# d'echec d'une etape intermediaire (db est un service de longue duree,
# pas un conteneur jetable).
trap 'docker compose exec -T db rm -rf "$CONTAINER_DIR" >/dev/null 2>&1 || true' EXIT

echo "Export de l'organisation $ORG_ID en cours..."
docker compose exec -T db mkdir -p "$CONTAINER_DIR"

docker compose exec -T db psql -v ON_ERROR_STOP=1 -q \
    -U "$DB_APP_USERNAME" -d "$DB_NAME" \
    -v org_id="$ORG_ID" \
    -v out_organizations="$CONTAINER_DIR/organizations.csv" \
    -v out_users="$CONTAINER_DIR/users.csv" \
    -v out_shops="$CONTAINER_DIR/shops.csv" \
    -v out_user_shops="$CONTAINER_DIR/user_shops.csv" \
    -v out_customers="$CONTAINER_DIR/customers.csv" \
    -v out_products="$CONTAINER_DIR/products.csv" \
    -v out_credit_sales="$CONTAINER_DIR/credit_sales.csv" \
    -v out_installments="$CONTAINER_DIR/installments.csv" \
    -v out_payments="$CONTAINER_DIR/payments.csv" \
    -v out_sale_attachments="$CONTAINER_DIR/sale_attachments.csv" \
    -v out_stock_receptions="$CONTAINER_DIR/stock_receptions.csv" \
    -v out_stock_reception_lines="$CONTAINER_DIR/stock_reception_lines.csv" \
    -v out_stock_movements="$CONTAINER_DIR/stock_movements.csv" \
    -v out_audit_log="$CONTAINER_DIR/audit_log.csv" \
    < scripts/sql/tenant-export.sql

echo "Empaquetage..."
docker compose exec -T db tar -czf - -C /tmp "tenant-export-$EXPORT_NAME" > "$ARCHIVE.part"
mv "$ARCHIVE.part" "$ARCHIVE"

echo "Export termine : $ARCHIVE"
ls -lh "$ARCHIVE"
