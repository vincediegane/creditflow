# Review — #63 Sauvegardes automatiques : pg_dump en echec masque par gzip, backups vides sans alerte

## Verdict

CHANGES_REQUESTED

## Résumé

Le contrat technique de la spec est respecté a la lettre pour la structure if/else, le placement de set -o pipefail, le message CRITIQUE et la purge de backups/. Testé indépendamment (conteneurs Docker réels), le comportement fonctionne correctement dans le contexte postgres:16-alpine (busybox ash) utilisé par docker compose. Mais un test indépendant supplémentaire (non prévu par le plan de tests de la spec) révèle une régression réelle et bloquante : sur un hôte dont /bin/sh est dash (par ex. Debian/Ubuntu par défaut, une cible de déploiement plausible pour ce projet auto-hébergé), scripts/backup.sh ne démarre même plus, en échec comme en succès, à cause de set -o pipefail qui n'est pas supporté par dash. C'est un bug introduit par ce ticket précisément sur le point que la spec elle-même avait identifié comme risqué (pipefail est une extension non-POSIX) mais dont le plan de test ne couvrait pas le bon interpréteur (il confond Git Bash cote hote avec dash, alors que Git Bash sh est en réalité bash).

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Échec simulé de pg_dump : code de sortie non nul, message explicite, aucun fichier vide silencieux | Partiel : vrai et vérifié indépendamment dans le contexte réel du conteneur backup (postgres:16-alpine, busybox ash) et sur cet hôte de dev (Git Bash = bash). Faux sur un hôte dash : le script échoue avant même d'appeler pg_dump, avec un message de syntaxe shell (Illegal option -o pipefail) qui n'a rien d'explicite sur l'origine réelle du problème, voir Finding 1. |
| 2 | Succès de pg_dump : sauvegarde valide, sans régression | Non couvert sur hôte dash (voir Finding 1) : régression totale, scripts/backup.sh ne produit plus aucune sauvegarde, y compris en cas de succès de la base. Couvert dans le contexte busybox ash / Git Bash (vérifié indépendamment, fichier .sql.gz d'environ 11 Ko produit, exit 0). |
| 3 | Sauvegardes d'environ 20 octets dans backups/ identifiées et traitées | Couvert : vérifié, backups/ ne contient plus que les 4 fichiers légitimes (10-11 Ko), aucun des 5 fichiers corrompus listés dans la spec. Purge documentée dans le commit a7f6e6a (vide, comme requis car le dossier est git-ignoré), avec la précision honnête que 3 des 5 ont été supprimés par la rétention 14 jours pendant le test réel plutôt que manuellement, cohérent et vérifiable. |

## Findings

### 1. BLOQUANT : set -o pipefail casse scripts/backup.sh (et fragilise scripts/backup-loop.sh) sur tout hôte où /bin/sh est dash

Fichiers concernés : scripts/backup.sh ligne 8, scripts/backup-loop.sh ligne 15.

Les deux scripts déclarent #!/bin/sh, inchangé par ce ticket : c'était déjà le cas sur master et ce choix visait explicitement le sh POSIX (aucun bashisme avant ce diff). set -o pipefail est une extension bash/ksh/busybox-ash, absente de dash. Or dash est le /bin/sh par défaut sur Debian et Ubuntu, la famille de distribution la plus courante pour de l'auto-hébergement Docker Compose, donc une cible de déploiement crédible pour CreditFlow. Le README documente explicitement l'exécution directe de scripts/backup.sh (pas via bash scripts/backup.sh), ce qui invoque le shebang #!/bin/sh, donc dash sur un tel hôte.

Reproduit indépendamment (conteneur debian:bookworm-slim, dash réel, hors de toute affirmation du codeur), en exécutant scripts/backup.sh via son shebang sous dash : le script s'arrête ligne 8 avec l'erreur "Illegal option -o pipefail" et un code de sortie 2, avant même d'atteindre pg_dump, que la base soit joignable ou non et que les identifiants soient corrects ou non.

Confirmé également de façon indépendante via shellcheck (dialecte sh) sur les deux fichiers : alerte SC3040 "In POSIX sh, set option pipefail is undefined" sur scripts/backup.sh ligne 8 et scripts/backup-loop.sh ligne 15.

Scénario qui déclenche le bug : un exploitant déploie CreditFlow sur un VPS Debian ou Ubuntu standard (/bin/sh = dash par défaut, pas une configuration exotique) et lance ./scripts/backup.sh comme documenté dans le README (section Sauvegardes) pour une sauvegarde manuelle avant une mise à jour. Le script échoue instantanément avec un message de syntaxe shell obscur, sans jamais toucher à pg_dump ni à la base, que la base soit up ou down. C'est une régression totale du critère d'acceptation numéro 2 (le cas de succès ne fonctionne plus du tout sur cet hôte), et une violation de l'esprit du critère numéro 1 : le message produit n'est pas un message explicite sur l'échec de la sauvegarde, c'est une erreur de syntaxe shell qui ne mentionne ni pg_dump ni la base de données, un pire diagnostic qu'avant le ticket.

scripts/backup-loop.sh est moins exposé en pratique car docker-compose.yml (non modifié par ce ticket) ne l'exécute que dans l'image postgres:16-alpine via un entrypoint /bin/sh, et busybox ash de cette image supporte bien pipefail (vérifié indépendamment : dans un conteneur postgres:16-alpine, "set -o pipefail" suivi de "false | true" emprunte bien la branche else). Mais le script reste marqué #!/bin/sh et casserait de la même façon si jamais invoqué manuellement sur un hôte dash (débogage, script de secours copié ailleurs) ; le shellcheck SC3040 le signale aussi.

La spec elle-même avait identifié ce risque ("pipefail est une extension non-POSIX, le comportement doit être confirmé dans dash et dérivés (Git Bash côté hôte) et BusyBox ash") mais le plan de test contenait une confusion : Git Bash côté hôte n'est pas dash, c'est bash exécuté sous le nom sh (confirmé sur cette machine : /bin/sh --version affiche GNU bash, version 5.2.26). Le codeur a donc testé un shell qui supporte nativement pipefail, en croyant couvrir le cas dash. Aucun test contre un vrai dash n'a été fait, d'où la régression passée inaperçue.

Suggestion (à trancher côté spec ou design, non appliquée ici) : soit passer scripts/backup.sh en #!/bin/bash et documenter la dépendance à bash dans le README et les prérequis, soit renoncer à pipefail sur backup.sh et capturer le statut de pg_dump autrement, par exemple écrire pg_dump dans un fichier intermédiaire non compressé puis tester son statut avant de gzip, sans dépendre de pipefail.

### 2. Non bloquant : aucun test automatisé ne casserait si le fix était retiré

Le plan de tests de la spec ne prévoit que des vérifications manuelles en conteneur (aucun test scripté versionné, par exemple un test shell exécutable en CI). C'est cohérent avec l'absence de CI ou de tests shell existants dans le repo (pas de dossier .github) et le fait que le projet ne semble pas outillé pour tester ses scripts bash, mais cela signifie qu'une régression future sur ce point, y compris la régression du Finding 1, ne serait pas détectée automatiquement. À considérer pour une itération d'outillage : un job shellcheck plus un test sous dash minimal en CI aurait détecté le Finding 1 immédiatement.

## Build/tests

- Diff vs master (git diff master...HEAD --stat et --name-only) : uniquement README.md, scripts/backup.sh, scripts/backup-loop.sh, docs/bolts/63-.../design.md, docs/bolts/63-.../spec.md. Aucun fichier hors périmètre (docker-compose.yml, scripts/restore.sh, autres scripts) : conforme.
- Contrat technique : diff logique de scripts/backup.sh et scripts/backup-loop.sh comparé ligne à ligne au contrat de spec.md : conforme à l'identique (placement de set -o pipefail, structure if/else, message CRITIQUE, rm -f du fichier .part dans la branche d'échec).
- Etat de backups/ : 4 fichiers légitimes (10 à 11 Ko), aucun des 5 fichiers d'environ 20 octets listés dans la spec. .gitignore et .gitkeep intacts.
- Syntaxe : bash -n sur les deux scripts, résultat OK.
- shellcheck (dialecte sh, image koalaman/shellcheck:stable) sur les deux fichiers : alerte SC3040 sur les deux occurrences de set -o pipefail (voir Finding 1), aucune autre alerte.
- Test réel indépendant, scripts/backup.sh, échec (conteneur db réel postgres:16-alpine via docker compose up -d db, DB_USERNAME invalide) : exit 1, message CRITIQUE explicite sur stderr, message d'erreur pg_dump affiché (rôle inexistant), aucun fichier .part ni .sql.gz créé. Conforme.
- Test réel indépendant, scripts/backup.sh, succès (mêmes conditions, identifiants valides) : exit 0, fichier backups/creditflow-manuel-*.sql.gz d'environ 11 Ko créé, message de succès affiché. Conforme (fichier de test supprimé après vérification).
- Test réel indépendant, scripts/backup-loop.sh, échec (conteneur postgres:16-alpine, entrypoint /bin/sh backup-loop.sh, identifiants PGUSER/PGPASSWORD invalides, volume backups isolé) : log CRITIQUE avec détail de l'erreur pg_dump, boucle non interrompue (conteneur reste up), aucun fichier créé dans le volume de backups.
- Test réel indépendant, scripts/backup-loop.sh, succès (mêmes conditions, identifiants valides) : log OK avec nom et taille du fichier, fichier réellement présent dans le volume de backups (environ 12 Ko).
- Test réel indépendant, régression dash (conteneur debian:bookworm-slim, /bin/sh = dash réel) : exécution de scripts/backup.sh via son shebang échoue ligne 8 avec "Illegal option -o pipefail", exit 2, avant tout appel à pg_dump. Confirme le Finding 1 de façon indépendante.
- Nettoyage : conteneurs et volumes de test Docker supprimés après vérification, backups/ remis dans son état d'origine (4 fichiers légitimes).
- Pas de code applicatif backend ou frontend touché par ce ticket, donc pas de build Maven ou npm nécessaire pour ce périmètre.

## Conclusion

La logique métier du fix (structure if/else, purge, message renforcé) est correcte et fidèle à la spec, et fonctionne bien dans le contexte réel de déploiement docker compose (image postgres:16-alpine). Mais le mécanisme choisi (set -o pipefail sous un shebang #!/bin/sh inchangé, documenté pour un usage direct sur l'hôte) introduit une régression bloquante et reproductible sur tout hôte dash (Debian/Ubuntu par défaut), qui casse scripts/backup.sh en succès comme en échec, l'inverse de l'objectif du ticket. À corriger avant merge (changer le shebang en #!/bin/bash avec mise à jour de la documentation des prérequis, ou implémenter la détection d'échec de pg_dump sans dépendre de pipefail), puis retester sur dash réel avant nouvelle review.
