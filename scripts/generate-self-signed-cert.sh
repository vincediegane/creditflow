#!/bin/sh
# =====================================================================
# Genere un certificat auto-signe pour chiffrer les echanges sur le
# reseau local de la boutique (comptoir, tablette, second poste).
#
#   ./scripts/generate-self-signed-cert.sh [nom-ou-ip]
#
# Le navigateur affichera un avertissement la premiere fois : c'est normal
# pour un certificat auto-signe. Pour un acces depuis Internet, preferez un
# vrai certificat (Let's Encrypt) depose sous le meme nom dans ./certs.
# =====================================================================
set -eu

cd "$(dirname "$0")/.."

HOSTNAME_TARGET="${1:-creditflow.local}"
DAYS=825

mkdir -p certs

echo "Generation d'un certificat auto-signe pour : $HOSTNAME_TARGET"

docker run --rm -v "$(pwd)/certs:/certs" alpine/openssl req -x509 -nodes \
    -newkey rsa:2048 \
    -keyout /certs/privkey.pem \
    -out /certs/fullchain.pem \
    -days "$DAYS" \
    -subj "/CN=$HOSTNAME_TARGET/O=CreditFlow" \
    -addext "subjectAltName=DNS:$HOSTNAME_TARGET,DNS:localhost,IP:127.0.0.1"

echo
echo "Certificats crees dans ./certs :"
ls -l certs
echo
echo "Redemarrez le frontend pour activer HTTPS :"
echo "  docker compose up -d --force-recreate frontend"
