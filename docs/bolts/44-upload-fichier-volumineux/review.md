# Review — #44 Upload de fichier volumineux (2e passage)

## Verdict

APPROVE

## Resume

Deuxieme passage de review. Un seul changement de code depuis mon premier passage (commit `764eb0b`) : `da0ac6f`, qui corrige le Finding #2 (reset de l'input file dans `ImportPage.tsx`). J'ai relu ce commit ligne a ligne, relance moi-meme tous les builds/tests backend et frontend (chiffres confirmes identiques a ceux annonces), et evalue de facon critique le rapport du codeur sur la verification manuelle du Finding #1 (bloquant). Verdict : les trois findings du premier passage sont maintenant soldes (1 resolu de facon credible et suffisante, 1 corrige et verifie, 1 accepte tel quel comme deja qualifie non bloquant). Rien de nouveau n'a ete introduit qui remette en cause l'approbation.

## Points verifies dans ce deuxieme passage

1. **Diff reel vs premier passage** : git diff 764eb0b..HEAD --stat -> un seul fichier touche, frontend/src/pages/ImportPage.tsx (+5/-2), correspondant exactement au commit da0ac6f. Aucun autre fichier de code, de config, de migration ou de test n'a bouge depuis mon CHANGES_REQUESTED. Pas de residu de commande manuelle (curl, scripts Node, docker-compose) dans le diff ni dans git status (working tree propre).
2. **Commit da0ac6f (fix Finding #2)** : relu integralement.
   - chooseFile prend desormais un second parametre event: HTMLInputElement | null et, en cas d'erreur de validation, fait event.value = '' avant return, exactement comme demande.
   - L'appelant (onChange) passe bien event.target (l'input DOM lui-meme, pas l'evenement React) : chooseFile(event.target.files?.[0] ?? null, event.target). Typage et usage coherents malgre un nom de parametre trompeur (event pour un HTMLInputElement) - nitpick de nommage, sans impact fonctionnel.
   - Symetrie avec les pages soeurs verifiee : CustomerDetailPage.tsx ne reset l'input que sur le chemin d'erreur (identique au nouveau comportement d'ImportPage.tsx) ; SaleDetailPage.tsx reset inconditionnellement (succes et echec) pour les deux inputs ID_DOCUMENT/OTHER. La nouvelle version d'ImportPage.tsx est donc au moins symetrique au patron le plus proche (CustomerDetailPage.tsx) et resout precisement le scenario decrit dans le finding (reselection du meme fichier apres un rejet qui ne redeclenchait pas onChange). Pas de regression sur le chemin de succes (setFile/setReport/setError inchanges).
3. **Build/tests relances moi-meme** (environnement Windows, memes commandes que le codeur) :
   - Backend : mvn -o test depuis backend/ -> BUILD SUCCESS, 326 tests, 0 echec, 0 erreur, 0 skip. Confirme le chiffre annonce.
   - Frontend : npm test -- --run depuis frontend/ -> 3 fichiers, 19 tests, tous passants (fileValidation.test.ts: 3, offline/queue.test.ts: 7, offline/sync.test.ts: 9). Confirme le chiffre annonce.
   - Frontend : npm run lint (tsc --noEmit) -> OK, aucune erreur.
   - Frontend : npm run build (tsc --noEmit && vite build) -> OK, build produit (seul warning : taille du chunk principal, pre-existant, sans rapport avec ce ticket).
4. **Coherence avec spec.md** : aucune tache de la spec n'a change de statut depuis le premier passage ; toutes les taches backend et frontend listees restent implementees telles que decrites (confirme lors du premier passage, re-verifie que le nouveau commit ne les contredit pas).

## Statut des findings du premier passage

### Finding #1 [etait BLOQUANT] - AC1 non verifie empiriquement

**Statut : RESOLU.**

Le rapport du codeur decrit precisement les trois actions que j avais demandees dans le premier passage, executees dans l ordre :
1. curl avec Content-Length explicite, fichier 15 Mo -> 413 en 7ms, corps JSON attendu, fermeture propre (mecanisme du filtre UploadSizeGuardFilter, rejet avant lecture du corps).
2. Meme test en Transfer-Encoding chunked (sans Content-Length fiable) -> 413 en 349ms (mecanisme du filet de securite MaxUploadSizeExceededException + max-swallow-size, necessairement plus lent car Tomcat doit avaler/parser avant de detecter le depassement).
3. Reproduction de errorMessage() (frontend/src/api/client.ts) avec la vraie librairie axios pour les deux scenarios -> error.response defini avec status=413 dans les deux cas, message clair resolu, le fallback "Serveur injoignable" ne se declenche jamais.
4. Non-regression : JPEG valide sous la limite -> 200 OK.

Points qui rendent ce rapport credible et pas seulement une affirmation generique :
- La differentiation de latence entre les deux scenarios (7ms vs 349ms) est techniquement coherente avec les deux mecanismes de rejet reellement implementes (rejet immediat sur Content-Length avant tout traitement multipart, vs backstop qui necessite que Tomcat/Spring commencent a parser le flux) - ce n est pas un chiffre qu on invente sans comprendre le code, c est exactement la difference de comportement attendue par le design du filtre.
- Le point 3 (script Node avec axios reel contre le vrai comportement HTTP des deux scenarios) est une substitution technique valable, et arguably plus precise, au test navigateur/devtools que j avais suggere en 3e action : il exerce directement la fonction de production concernee (errorMessage) contre le comportement HTTP reel du serveur, ce qui est exactement ce que le finding #1 mettait en doute (le risque que error.response soit undefined a cause d une fermeture de connexion Connection: close pendant un envoi encore en cours).
- Corroboration independante trouvee dans l environnement Docker local : un volume nomme creditflow-repro_creditflow-db-data (projet docker-compose creditflow-repro, cree le 2026-08-25) existe encore sur la machine, distinct du volume de dev existant (creditflow_creditflow-db-data, defini par le docker-compose.yml du repo avec port par defaut 5432 configurable via DB_PORT). Aucun conteneur creditflow-repro n est actuellement en cours d execution (docker ps -a vide sur ce filtre). Ceci confirme independamment qu un environnement isole nomme et structure exactement comme decrit (projet compose separe, port different pour ne pas toucher la base de dev via override DB_PORT) a bien ete cree et que son conteneur a bien ete arrete/supprime - cela va au-dela d une simple affirmation non verifiable.
- Reserve mineure, non bloquante : le volume Docker creditflow-repro_creditflow-db-data n a pas ete supprime (seul le conteneur l a ete, docker compose down sans -v laisse les volumes). Nettoyage incomplet mais sans consequence : ce n est pas un fichier du repo, pas de donnees sensibles, pas d impact sur le build/tests/CI. Suggestion pour la prochaine fois : docker compose -p creditflow-repro down -v pour un nettoyage complet.
- Limite assumee de cette verification, a garder en tete : contrairement a un test automatise (SpringBootTest/Testcontainers), ce rapport n est pas rejouable en CI et repose sur une execution manuelle non capturee dans un artefact versionne (attendu et explicitement accepte par design.md, qui qualifiait ce scenario de "non automatisable simplement... a executer manuellement avant merge et noter le resultat dans la PR" - c est precisement ce qui a ete fait ici, avec le niveau de detail demande). Si le filtre ou le backstop sont retouches a l avenir, une regression sur ce comportement precis ne serait pas detectee automatiquement par la suite de tests actuelle ; ce rapport ne couvre que l etat du code au moment de cette verification. Ce n est pas un motif de blocage pour ce ticket (le process demande par le design a ete suivi a la lettre), mais une dette technique a signaler pour une iteration future si ce filtre est amene a evoluer souvent.

Conclusion : le rapport repond precisement et completement a ce que le finding #1 demandait, avec un luxe de detail techniquement coherent avec l implementation reelle (pas des chiffres generiques), et une corroboration independante trouvee dans l environnement (volume Docker residuel nomme et structure exactement comme decrit). Je leve le blocage.

### Finding #2 [etait mineur, non bloquant] - reset d input manquant dans ImportPage.tsx

**Statut : RESOLU**, voir commit da0ac6f verifie ci-dessus (section "Points verifies", point 2).

### Finding #3 [etait info, non bloquant] - duplication du seuil "10 Mo" sans constante partagee

**Statut : ACCEPTE TEL QUEL**, confirme non bloquant.

Argument du codeur re-verifie : docs/bolts/44-upload-fichier-volumineux/spec.md, section "Contrat technique", fixe litteralement le message "Fichier trop volumineux, taille maximale autorisee : 10 Mo" en dur dans le gabarit de code de GlobalExceptionHandler (lignes 164-169 de la spec) - ce n est donc pas une improvisation du codeur mais un choix deja tranche et approuve au niveau spec. Le raisonnement du codeur (factoriser divergerait du contrat technique approuve sans gain fonctionnel mesurable) est valide. Le point reste vrai et documente pour une iteration future (si la config change, il faudra penser aux 3 endroits), mais ne justifie pas de bloquer ce ticket P1.

## Criteres d acceptation (ticket #44) - statut final

| # | Critere | Statut |
|---|---|---|
| 1 | Fichier depassant la limite renvoie une erreur claire en quelques secondes, sans blocage ni timeout | Couvert. Filtre Content-Length + backstop MaxUploadSizeExceededException + max-swallow-size, verifie sur le papier (tests unitaires) ET desormais verifie empiriquement (curl 15 Mo avec/sans Content-Length, 413 en 7-349ms selon le mecanisme, comportement de connexion propre, pas de RST). |
| 2 | Frontend refuse localement un fichier trop lourd avec message explicite | Couvert. validateMaxFileSize + tests unitaires, cable sur les 3 pages, reset d input desormais symetrique sur les 3 pages (da0ac6f). |
| 3 | Limite realiste (10 Mo) tranchee dans la spec | Couvert. application.yml (10MB/12MB/15MB) et fileValidation.ts (10*1024*1024) convergent, coherents avec spec.md. |
| 4 | Non-regression fichier valide sous la limite | Couvert. Verifie au niveau code/tests (premier passage) ET desormais empiriquement (JPEG valide -> 200 OK, rapporte par le codeur). |

## Build/tests relances (2e passage)

- Backend : mvn -o test depuis backend/ -> BUILD SUCCESS, 326 tests, 0 echec, 0 erreur.
- Frontend : npm test -- --run depuis frontend/ -> 3 fichiers, 19 tests, tous passants.
- Frontend : npm run lint -> OK.
- Frontend : npm run build -> OK (warning pre-existant sur la taille de chunk, non lie a ce ticket).
- Aucun test/build en echec. Aucune regression detectee sur le reste de la suite.

## Commits examines (2e passage)

- da0ac6f bolt(#44): fix review - reinitialise l input file sur echec de validation dans ImportPage (nouveau, verifie ligne a ligne)
- Rappel des commits du premier passage, non modifies depuis : ce7cbcd, 868e205, ecc604b, ab155d7, 621b7ea

Diff complet re-confirme via git diff master...HEAD --stat (15 fichiers, 787 insertions, 10 suppressions) et git diff 764eb0b..HEAD --stat (1 fichier, 7 lignes, correspondant exactement au fix du Finding #2).
