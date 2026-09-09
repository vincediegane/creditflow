# =====================================================================
# Verification d'integrite d'une sauvegarde.
#   . scripts/backup-verify.sh ; backup_verify "$TARGET"
#
# Retour 0 = OK. Retour 1 = echoue, fichier deplace vers quarantine/
# (l'appelant est responsable du log CRITIQUE, pour rester coherent avec
# le style de log deja existant dans backup.sh et backup-loop.sh).
# =====================================================================

backup_verify() {
    TARGET="$1"
    MIN_SIZE="${BACKUP_MIN_SIZE_BYTES:-1024}"
    SIZE=$(wc -c < "$TARGET")
    if [ "$SIZE" -lt "$MIN_SIZE" ] || ! gunzip -t "$TARGET" 2>/dev/null; then
        QDIR="$(dirname "$TARGET")/quarantine"
        mkdir -p "$QDIR"
        mv "$TARGET" "$QDIR/"
        return 1
    fi
    return 0
}
