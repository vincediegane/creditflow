#!/bin/sh
# =====================================================================
# Test de restauration automatique, hebdomadaire par defaut.
#
# Tourne dans son propre conteneur, isole de `db` : une verification
# immediate au demarrage, puis toutes les RESTORE_TEST_INTERVAL_HOURS
# heures. Un echec ne doit jamais arreter la boucle : il doit rester
# visible dans les logs (log CRITIQUE) pour etre investigue.
# =====================================================================
set -eu

INTERVAL_HOURS="${RESTORE_TEST_INTERVAL_HOURS:-168}"

log() {
    echo "[restore-test-loop] $(date '+%Y-%m-%d %H:%M:%S') $1"
}

log "Demarrage — intervalle ${INTERVAL_HOURS}h"

while true; do
    if /bin/sh /usr/local/bin/restore-test.sh; then
        log "cycle termine (OK ou SKIP, voir logs ci-dessus)"
    else
        log "cycle termine avec un CRITIQUE, voir logs ci-dessus"
    fi

    sleep $((INTERVAL_HOURS * 3600))
done
