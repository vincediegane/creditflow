# Design — #63 Sauvegardes automatiques : pg_dump en echec masque par gzip, backups vides sans alerte

## Approche

Le bug est confirme dans les deux scripts, pas seulement `backup-loop.sh` cite en premier par le
ticket : `backup.sh` (sauvegarde manuelle) contient exactement le meme pipeline `pg_dump | gzip -9 >
$TARGET.part` sous `set -eu` sans `pipefail`, donc le meme masquage silencieux si `pg_dump` echoue.
Les deux scripts doivent etre corriges ensemble. Fix retenu : ajouter `set -o pipefail` (une ligne,
diff minimal) dans les deux scripts plutot que reecrire le pipeline en deux etapes (pg_dump vers un
fichier intermediaire, puis gzip separement). Prix assume : `pipefail` est une extension non-POSIX,
absente du POSIX sh strict, mais supportee par les deux interpretes reellement en jeu ici (dash et
BusyBox ash modernes la supportent tous deux depuis plusieurs annees ; a confirmer par execution
reelle d'un echec simule, pas seulement par lecture, voir Risques). C'est le compromis explicitement
autorise par le ticket ("ou equivalent explicite") : prefere a la reecriture en deux etapes car il
laisse `backup-loop.sh` (deja structure en `if pg_dump ... | gzip ...; then`) fonctionner correctement
sans restructuration, l'`if` refletant enfin le vrai code de sortie du pipeline une fois `pipefail`
actif.

`backup-loop.sh` gere deja partiellement le cas d'echec (branche `else`, log, `rm -f
"$TARGET.part"`) : c'est uniquement la condition testee par le `if` qui est fausse aujourd'hui
(exit code de `gzip`, pas de `pg_dump`) faute de `pipefail`. `backup.sh`, lui, n'a aucune branche
d'echec : il faut y ajouter explicitement la detection et le nettoyage du fichier partiel, qui
n'existent pas actuellement (le `mv "$TARGET.part" "$TARGET"` s'execute sans condition).

Pour les sauvegardes deja corrompues presentes dans `backups/` : ce repertoire est entierement
git-ignore (`backups/.gitignore` contient `*` puis `!.gitkeep`, confirme par `git ls-files backups/`
qui ne retourne que `.gitkeep`). Les fichiers de ~20 octets ne sont donc pas versionnes, leur
suppression est un menage local/operationnel, pas un changement de code a committer. Decision : les
purger maintenant (suppression directe, une seule fois), sans construire de script permanent de
detection ; ce filet permanent est explicitement le sujet du ticket complementaire annonce en
dependance (verification d'integrite des sauvegardes + test de restauration), donc hors perimetre
ici.

## Fichiers/modules impactes

- `scripts/backup.sh` : ajout de `set -o pipefail` ; remplacement du `pg_dump | gzip > ... ; mv`
  inconditionnel (lignes 19-24 actuelles) par une structure `if ... ; then mv ... ; else rm -f
  ...part ; log erreur critique ; exit 1 ; fi`, sur le meme modele que `backup-loop.sh`.
- `scripts/backup-loop.sh` : ajout de `set -o pipefail` en tete (apres `set -eu`, ligne 14) ; la
  structure `if/else` existante (lignes 33-40) devient alors correcte sans autre changement
  structurel. Verifier que le message de log de la branche `else` (ligne 39, contenu de
  `/tmp/pg_dump.err`) reste explicite et de niveau visible ("ECHEC" deja present) ; le ticket
  demande un niveau "critique" : a minima garder ce prefixe distinct des lignes "OK", envisager de
  le faire ressortir davantage (exemple : prefixe "[backup] CRITIQUE") si le mecanisme de
  supervision du conteneur (logs docker) ne distingue pas deja les niveaux.
- `backups/` : suppression des 5 fichiers de ~20 octets identifies (voir Decisions cles) ; aucun
  autre fichier de ce repertoire n'est concerne (les fichiers legitimes font 6,9 a 11 Ko). Le
  repertoire n'etant pas suivi par git, cette suppression n'apparait dans aucun diff/PR ; a signaler
  explicitement dans la description de la PR pour que ce ne soit pas une action invisible.
- `README.md`, section "Sauvegardes" (lignes 52-65) : pas de changement de fond necessaire (le
  comportement documente reste vrai), mais une phrase peut etre ajoutee pour preciser qu'un
  `pg_dump` en echec fait desormais echouer le script au lieu de produire un fichier vide, en
  coherence avec la remarque deja presente ligne 65 sur le test de restauration.

Aucun changement necessaire dans `docker-compose.yml` : le service `backup` (lignes 88-108) ne
change ni d'image, ni de variables d'environnement, ni de point de montage.

## Decisions cles

- `set -o pipefail` plutot que restructuration en deux etapes. Voir justification en Approche.
  Alternative ecartee : ecrire `pg_dump` vers un fichier `.sql` temporaire, verifier son code de
  sortie, puis `gzip` seulement en cas de succes. Plus verbeux, plus de fichiers intermediaires a
  nettoyer sur le disque (`.sql` en clair en plus du `.gz`), pour un gain de portabilite qui n'est
  pas necessaire ici (les deux shells en jeu supportent `pipefail`).
- `backup.sh` recoit la meme structure `if/else` que `backup-loop.sh` (log + exit non-zero + pas de
  fichier partiel conserve), plutot qu'un traitement different pour le mode manuel. Coherence
  attendue par l'operateur qui lance les deux a des moments differents ; evite un deuxieme
  comportement a documenter.
- Purge immediate des 5 fichiers corrompus identifies, sans script de detection permanent. Les 5
  fichiers (et non 4, voir Risques) sont reconnaissables sans ambiguite : taille exacte de 20 octets,
  qui correspond precisement a la sortie de `gzip -9` sur une entree vide (verifie localement :
  entree vide compressee par gzip -9 fait exactement 20 octets), donc un pg_dump qui n'a produit
  aucun octet. Un filet de detection permanent (par exemple verifier l'integrite gzip et une taille
  minimale a chaque cycle) est le sujet du ticket complementaire deja annonce en dependance ; ne pas
  l'anticiper ici pour rester dans le perimetre "cause racine" du ticket.
- Aucune alerte externe (email/SMTP) ajoutee. Le canal email existe deja dans le backend
  (`EmailChannel`, #52) mais c'est un canal applicatif de notification client, sans mecanisme
  de branchement vers les scripts shell du service `backup` (conteneur distinct, pas de dependance
  reseau vers le backend). Le ticket demande explicitement "logger une erreur explicite" et "faire
  echouer visiblement le script", pas une alerte transverse. Rester sur les logs du conteneur
  `backup` (visibles via `docker compose logs backup` ou tout systeme de supervision des logs deja en
  place chez l'operateur) est suffisant pour ce ticket.

## Risques / points d'attention

- `pipefail` doit etre valide par execution, pas seulement par lecture. `backup-loop.sh` tourne
  dans le conteneur `postgres:16-alpine` (`/bin/sh` = BusyBox ash) ; `backup.sh` tourne sur la
  machine hote qui execute `docker compose`, dont le `/bin/sh` reel depend de l'environnement de
  l'operateur (dash sur Debian/Ubuntu, potentiellement un shell different sous Git Bash/MSYS sur
  Windows, cet environnement de developpement en particulier tourne sous Windows avec Git Bash). Le
  critere d'acceptation (un echec simule de pg_dump fait echouer le script) doit etre teste
  reellement dans les deux contextes d'execution (conteneur ET machine hote utilisee pour lancer
  `backup.sh`), pas suppose correct parce que la syntaxe est acceptee par le parseur.
- Ne pas casser le cas de succes. `backup-loop.sh` a deja une branche de succes fonctionnelle (mv
  + log taille) ; le risque de regression est concentre sur `backup.sh`, qui passe d'un flux lineaire
  sans branche a une structure conditionnelle ; verifier explicitement qu'un succes normal continue
  de produire `$TARGET` (pas seulement `$TARGET.part`) et le message "Sauvegarde terminee".
- La purge des 5 fichiers n'apparaitra dans aucun diff git (repertoire ignore) ; le codeur doit la
  documenter explicitement (message de commit ou description de PR) pour que ce ne soit pas une
  action silencieuse invisible en revue, ironie sachant que le ticket porte justement sur des echecs
  silencieux.
- Ecart avec l'enonce du ticket : le corps du ticket cite 4 dates (24, 25, 27, 29 aout) pour les
  fichiers de ~20 octets ; l'inspection reelle du dossier en montre 5, avec une occurrence
  supplementaire le 22 aout (`creditflow-20260822-180402.sql.gz`, 20 octets, a cote d'une sauvegarde
  legitime du meme jour a 05:08). A traiter comme les 4 autres (meme cause, meme purge) ; a signaler
  au spec-writer pour que le critere d'acceptation (les sauvegardes anormales sont identifiees)
  couvre bien les 5 fichiers reels et pas seulement les 4 cites.
- Detection par taille exacte (20 octets), pas par seuil arbitraire. Un seuil du type "moins de 1 Ko
  = suspect" serait plus fragile (une tres petite base reelle pourrait legitimement produire un
  fichier compresse de quelques centaines d'octets). La signature exacte de 20 octets (sortie
  deterministe de gzip -9 sur une entree vide) est sans ambiguite pour les fichiers actuellement
  presents ; a ne pas generaliser telle quelle a un futur script de detection permanent (hors
  perimetre) sans la reverifier.

## Hors perimetre

- Filet de detection permanent (verification d'integrite a chaque cycle, test de restauration
  automatique) : ticket complementaire distinct, deja annonce dans les dependances du ticket.
- Alerte externe (email, SMS, webhook) en cas d'echec de sauvegarde : le ticket demande un logging
  explicite et un code de sortie non nul, pas un canal de notification transverse.
- Modification de `docker-compose.yml`, des variables d'environnement du service `backup`, ou de
  `scripts/restore.sh`, `scripts/tenant-export.sh`, `scripts/tenant-delete.sh` : aucun de ces
  elements n'est concerne par le bug decrit.
- Reecriture du mecanisme de retention (`find ... -mtime +$RETENTION_DAYS -delete`,
  `backup-loop.sh` lignes 42-45) : non concerne par ce ticket, fonctionne independamment du bug
  corrige ici.
