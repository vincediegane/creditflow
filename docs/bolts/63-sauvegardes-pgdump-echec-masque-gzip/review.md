# Review — #63 Sauvegardes automatiques : pg_dump en echec masque par gzip, backups vides sans alerte (2e passage)

## Verdict

APPROVE

## Résumé

Le finding bloquant du premier passage (set -o pipefail, extension non supportee par dash) est corrige : les deux scripts ne dependent plus d'aucune extension shell non-POSIX. Le nouveau mecanisme - pg_dump ecrit dans un fichier intermediaire *.sql.raw, le code de sortie de cette commande (et non d'un pipe) determine directement succes/echec, gzip n'est applique qu'apres coup dans un sous-if - a ete verifie independamment, pas seulement relu, dans les trois contextes pertinents : dash reel (debian:bookworm-slim), busybox ash reel (postgres:16-alpine, l'image exacte utilisee par docker-compose.yml pour le service backup) avec une vraie base Postgres sur un reseau Docker isole, et bash cote hote (syntaxe). Dans tous les cas : code de sortie correct, message CRITIQUE explicite, aucun fichier intermediaire (.sql.raw, .sql.gz partiel) laisse sur le disque en cas d'echec, fichier .sql.gz valide et de taille coherente en cas de succes. shellcheck -s sh ne remonte plus aucune alerte (SC3040 disparue). Le reste du diff deja valide au premier passage (structure if/else, message CRITIQUE, purge de backups/, ajout README) n'a pas regresse. Aucun fichier hors perimetre touche.

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Echec simule de pg_dump : code de sortie non nul, message explicite, aucun fichier vide silencieux | Couvert. Verifie independamment sous dash reel (backup.sh et backup-loop.sh, debian:bookworm-slim) ET sous busybox ash reel (backup-loop.sh dans un conteneur postgres:16-alpine avec une vraie base et des identifiants invalides) : exit non nul (backup.sh), message [backup] CRITIQUE explicite mentionnant l'erreur pg_dump reelle, aucun fichier .sql.raw/.sql.gz cree dans le repertoire de sortie. |
| 2 | Succes de pg_dump : sauvegarde valide, sans regression | Couvert, y compris sur dash (le point qui avait regresse au premier passage). Verifie independamment : backup.sh sous dash reel produit un .sql.gz valide (contenu decompresse verifie) et sort en 0 ; backup-loop.sh sous busybox ash reel contre une vraie base postgres:16-alpine produit un .sql.gz de 371 octets contenant un dump pg_dump valide et exploitable (en-tete PostgreSQL database dump, restrict, etc.), log OK. |
| 3 | Sauvegardes d'environ 20 octets dans backups/ identifiees et traitees | Couvert, inchange depuis le premier passage : backups/ ne contient que les 4 fichiers legitimes (10-11 Ko), aucun des 5 fichiers corrompus. Confirme a nouveau par inspection directe de ls -la backups. |

## Findings

Aucun finding bloquant. Le probleme du premier passage est resolu et verifie de facon independante dans les environnements pertinents.

Remarques non bloquantes, pour information seulement (pas d'action requise) :

- Comme au premier passage, aucun test automatise scripte/versionne (CI) ne couvrirait une regression future de ce type ; seules des verifications manuelles en conteneur existent. Coherent avec l'absence d'outillage CI shell existant dans le repo, mais a garder en tete pour une iteration d'outillage ulterieure (shellcheck + execution dash minimale en CI).
- Le comportement de gzip en cas d'echec de compression partielle (disque plein en cours d'ecriture, par exemple) n'a pas ete teste explicitement dans ce passage ni le precedent ; la branche else du sous-if (rm -f RAW RAW.gz) couvre ce cas de facon defensive mais n'a pas ete declenchee par un test reel. Risque juge mineur (scenario rare, deja present sous une forme equivalente avant ce ticket avec TARGET.part), ne bloque pas l'approbation.

## Build/tests

- git diff master...HEAD --name-only : README.md, scripts/backup-loop.sh, scripts/backup.sh, docs/bolts/63-.../design.md, docs/bolts/63-.../spec.md, docs/bolts/63-.../review.md. Aucun fichier hors perimetre (pas de docker-compose.yml, pas de scripts/restore.sh, pas de scripts/tenant-export.sh).
- grep pipefail dans scripts/ : aucune occurrence. Confirme que le mecanisme incrimine a bien ete retire des deux scripts, pas seulement contourne.
- grep .raw dans scripts/ : uniquement les deux definitions de RAW dans backup.sh/backup-loop.sh, coherent avec le nouveau mecanisme.
- dash -n scripts/backup.sh et dash -n scripts/backup-loop.sh (conteneur debian:bookworm-slim, dash reel via /bin/sh -> dash) : syntaxe OK pour les deux.
- shellcheck -s sh (image koalaman/shellcheck:stable) sur les deux fichiers : aucune alerte, exit 0 (disparition de SC3040 constatee au premier passage).
- bash -n sur les deux scripts : OK.
- Test reel independant, scripts/backup-loop.sh, echec sous dash reel (debian:bookworm-slim, /bin/sh -> dash, pg_dump absent du PATH) : log CRITIQUE - echec de la sauvegarde : pg_dump not found, exit du process 0 (boucle continue comme prevu), aucun fichier cree dans backups/.
- Test reel independant, scripts/backup-loop.sh, succes sous dash reel (meme conteneur, pg_dump simule par un faux binaire dans le PATH ecrivant sur stdout et sortant en 0) : log OK (4.0K), fichier .sql.gz cree, contenu decompresse conforme, aucun fichier .raw residuel.
- Test reel independant, scripts/backup.sh, succes sous dash reel (debian:bookworm-slim, docker simule pour renvoyer un flux pg_dump factice en sortie 0, execution via le shebang du script) : exit 0, message Sauvegarde terminee, fichier .sql.gz cree et lisible, aucun .raw residuel.
- Test reel independant, scripts/backup.sh, echec sous dash reel (meme conteneur, docker simule pour renvoyer une erreur pg_dump apres une sortie partielle, exit 1) : exit 1, message CRITIQUE sur stderr, backups/ reste vide (aucun .raw ni .gz).
- Test reel independant en environnement Docker isole (reseau bolt63-test-net, conteneur postgres:16-alpine reel pour la base) avec le vrai backup-loop.sh monte dans un conteneur postgres:16-alpine (image et entrypoint identiques a docker-compose.yml, busybox ash) :
  - Echec (PGUSER/PGPASSWORD invalides) : log CRITIQUE - echec de la sauvegarde, erreur pg_dump reelle (authentification refusee), aucun fichier cree dans le volume backups.
  - Succes (identifiants reels creditflow/creditflow) : log OK avec nom et taille, fichier de 371 octets present, contenu decompresse verifie valide (en-tete PostgreSQL database dump, pg_dump version 16.15).
- Nettoyage : conteneurs et reseau Docker de test supprimes apres verification, fichiers temporaires de test hors du repo, backups/ du repo inchange (4 fichiers legitimes).
- Etat de backups/ (repo) : inchange depuis le premier passage, 4 fichiers legitimes de 10-11 Ko, .gitignore/.gitkeep intacts.
- Pas de code applicatif backend/frontend touche par ce ticket : pas de build Maven ni npm necessaire pour ce perimetre.

## Conclusion

La regression bloquante identifiee au premier passage (set -o pipefail incompatible avec dash) est corrigee par un mecanisme qui ne depend plus d'aucune extension shell non-POSIX, verifie de facon independante et reproductible dans dash reel, busybox ash reel (contexte exact de deploiement docker-compose.yml) et bash. Aucun fichier intermediaire n'est laisse sur le disque en cas d'echec ou de succes. Le reste du diff (structure if/else, message CRITIQUE, purge, README) reste conforme a la spec et n'a pas regresse. Aucun fichier hors perimetre. Deuxieme et dernier passage : APPROVE.
