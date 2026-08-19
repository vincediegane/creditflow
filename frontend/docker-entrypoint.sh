#!/bin/sh
# =====================================================================
# Choisit la configuration nginx selon la presence de certificats.
#
# Deposer fullchain.pem et privkey.pem dans ./certs (monte sur
# /etc/nginx/certs) suffit a basculer l'application en HTTPS : aucune
# variable ni reconstruction d'image n'est necessaire.
# =====================================================================
set -eu

CERT_DIR=/etc/nginx/certs
TARGET=/etc/nginx/conf.d/default.conf

if [ -f "$CERT_DIR/fullchain.pem" ] && [ -f "$CERT_DIR/privkey.pem" ]; then
    echo "[nginx] Certificats detectes — demarrage en HTTPS (redirection depuis HTTP)."
    cp /etc/nginx/templates/https.conf "$TARGET"
else
    echo "[nginx] Aucun certificat dans $CERT_DIR — demarrage en HTTP."
    echo "[nginx] Pour activer HTTPS : ./scripts/generate-self-signed-cert.sh"
    cp /etc/nginx/templates/http.conf "$TARGET"
fi

nginx -t
exec "$@"
