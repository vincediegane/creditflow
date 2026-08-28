# Review — #44 Upload de fichier volumineux

## Verdict

CHANGES_REQUESTED

## Resume

Le code backend et frontend est propre, coherent avec la spec, suit les patrons existants du repo, et tous les builds/tests annonces par le codeur ont ete relances et confirmes (326 tests backend, 19 tests frontend, lint et build OK). Le point qui bloque l'approbation n'est pas un bug avere dans le code lu, mais l'absence totale, meme sous forme manuelle/legere, de toute verification du comportement reel qui est la raison d'etre de ce ticket P1 (la requete reste bloquee au lieu d'etre rejetee proprement). Le design.md lui-meme demandait explicitement une reproduction manuelle (curl) en alternative a un test SpringBootTest complet ; cette etape n'a pas ete faite, ni documentee. Voir Finding #1.

## Criteres d'acceptation

| # | Critere | Statut |
|---|---|---|
| 1 | Fichier depassant la limite renvoie une erreur claire en quelques secondes, sans blocage ni timeout | Partiel. Implemente et coherent sur le papier (filtre Content-Length + backstop MaxUploadSizeExceededException + max-swallow-size), mais non verifie empiriquement (ni test d'integration, ni reproduction manuelle documentee). Aucun test existant n'exerce le vrai mecanisme reseau/Tomcat ; tous les tests unitaires mockent HttpServletRequest/HttpServletResponse. |
| 2 | Frontend refuse localement un fichier trop lourd avec message explicite | Couvert. validateMaxFileSize + tests (sous/a/au-dessus de la limite), cable sur les 3 pages, message explicite affiche via setError. |
| 3 | Limite realiste (10 Mo) tranchee dans la spec | Couvert. application.yml (10MB/12MB/15MB) et fileValidation.ts (10*1024*1024) convergent exactement (Spring DataSize "10MB" = 10 485 760 octets, identique a la constante JS). Frontend <= backend, jamais l'inverse. |
| 4 | Non-regression fichier valide sous la limite | Couvert au niveau code (chemin de succes inchange, la validation n'intervient qu'en cas de depassement) et tests unitaires de la fonction de validation ; pas de test au niveau page mais coherent avec l'absence pre-existante de React Testing Library dans ce repo (pas une regression introduite par ce ticket). |

## Build/tests relances moi-meme

- Backend : mvn -o test depuis backend/ -> BUILD SUCCESS, 326 tests, 0 echec, 0 erreur (verifie via l'agregation de target/surefire-reports/*.txt). Confirme le chiffre annonce par le codeur.
- Frontend : npm test -- --run depuis frontend/ -> 3 fichiers, 19 tests, tous passants (fileValidation.test.ts: 3, offline/queue.test.ts: 7, offline/sync.test.ts: 9). Confirme le chiffre annonce.
- Frontend : npm run lint (tsc --noEmit) -> OK, aucune erreur.
- Frontend : npm run build (tsc --noEmit && vite build) -> OK, build produit (warning pre-existant sur la taille du chunk principal, sans rapport avec ce ticket).
- Tentative de smoke-test manuel en conditions reelles (demarrer le backend avec mvn spring-boot:run puis curl avec un fichier de 15 Mo, avec et sans Content-Length, comme demande explicitement dans design.md) : bloquee par le sandbox (le classifieur d'auto-mode refuse le lancement d'un process serveur en arriere-plan). Je n'ai donc pas pu verifier moi-meme le comportement de bout en bout ; voir Finding #1, cette verification reste a faire par un humain ou en CI avant merge.

## Points verifies (au-dela de la liste fournie)

- Le filtre UploadSizeGuardFilter ne lit jamais getInputStream()/getParts() : seule getContentLengthLong() est utilisee avant tout traitement multipart. Rejet effectue avant chain.doFilter, donc avant DispatcherServlet et avant la resolution multipart. Correct.
- Enregistrement via FilterRegistrationBean + Ordered.HIGHEST_PRECEDENCE (Integer.MIN_VALUE) : s'execute bien avant la chaine Spring Security, dont le filtre par defaut est enregistre a l'ordre -100 (SecurityProperties.DEFAULT_FILTER_ORDER). Verifie qu'aucun autre filtre du repo n'utilise HIGHEST_PRECEDENCE (pas de conflit d'ordre).
- Coherence des limites : application.yml (max-file-size: 10MB, max-request-size: 12MB) et fileValidation.ts (10 * 1024 * 1024) convergent bit a bit (l'unite MEGABYTES de Spring vaut 1024*1024, comme en JS ici). Le frontend ne depasse jamais le backend.
- GlobalExceptionHandler.handleMaxUploadSize suit exactement le patron build(HttpStatus, message, request) des handlers existants, pas de duplication, pas de regression sur les handlers voisins (tout le fichier relu).
- Les 3 points d'upload frontend (photo client, ID_DOCUMENT/OTHER/SIGNATURE de vente, import CSV) sont bien tous couverts par validateMaxFileSize, et la mutation reseau n'est jamais declenchee si la validation echoue (verifie ligne a ligne dans les 3 fichiers).
- Reset de l'input file sur echec de validation : correct dans CustomerDetailPage.tsx et SaleDetailPage.tsx (les deux inputs ID_DOCUMENT/OTHER reinitialisent event.target.value inconditionnellement). Manquant dans ImportPage.tsx, voir Finding #2 (mineur, non bloquant).
- vite.config.ts : l'elargissement de test.include de src/offline/**/*.test.ts a src/**/*.test.ts est sans risque aujourd'hui, il n'existe que 3 fichiers de test dans tout src/, tous compatibles avec environment: 'node' (aucun test de composant React necessitant jsdom). Changement necessaire (sinon fileValidation.test.ts n'aurait jamais tourne dans npm test), pas de regression cachee constatee.
- Justification du codeur sur l'absence de SpringBootTest/Testcontainers : verifiee et confirmee exacte. Une recherche sur tout src/test ne trouve aucune occurrence de SpringBootTest, seulement des WebMvcTest (tranches MockMvc avec services mockes, pas de vrai serveur ni de vraie base). pom.xml ne contient ni Testcontainers ni H2. Le meme constat vaut cote frontend (pas de React Testing Library / jsdom configure). Le risque de pollution de la base Postgres partagee invoque par le codeur est reel et l'absence d'infra est un fait avere du repo, pas une excuse de circonstance.
- Message d'erreur et serialisation JSON (ApiError avec LocalDateTime) : le filtre reutilise le bean ObjectMapper applicatif (meme pattern que SecurityConfig), qui beneficie de jackson-datatype-jsr310 auto-configure par Spring Boot ; pas de risque d'exception de serialisation en prod, meme si le test unitaire du filtre construit son propre ObjectMapper (donc ne prouve pas ce point pour le bean reel, nitpick mineur non liste separement).
- Aucun endpoint, route RBAC ou migration Flyway n'est touche par ce diff, pas de surface de regression securite/DB au-dela du filtre et des limites de taille.

## Findings

### 1. [BLOQUANT] AC1 - comportement central du ticket non verifie, seule sa logique "sur le papier" est testee

Ou : backend/src/main/java/com/creditflow/config/UploadSizeGuardFilter.java, backend/src/main/resources/application.yml (lignes 5-6 et 42-44), et l'absence totale de toute trace de verification manuelle documentee (pas de report.md, pas de mention dans les messages de commit).

Probleme : le design.md (section risques) demandait explicitement, en alternative a un test d'integration SpringBootTest complet : "A tester explicitement en reproduisant le scenario du ticket (curl, 6 Mo, sans/avec Content-Length) pour confirmer qu'aucun chemin ne mene plus au timeout" et "A verifier que le client (axios/navigateur) gere correctement la fermeture de connexion et presente bien le message 413 a l'utilisateur plutot qu'une erreur reseau opaque". Le codeur a justifie l'abandon de la tache 6 (test automatise) par l'absence d'infra de test isolee, justification verifiee et fondee, mais n'a documente aucune execution de la verification manuelle legere qui etait explicitement proposee comme alternative, alors qu'elle ne necessite aucune infra nouvelle (juste curl contre une instance locale deja utilisee par le dev au quotidien).

Pourquoi c'est concret et pas seulement theorique : frontend/src/api/client.ts lignes 81-83 contient deja un fallback explicite pour le cas ou error.response est undefined cote axios ("Serveur injoignable. Verifiez que le backend est demarre."). C'est precisement le scenario que Connection: close envoye par le filtre, pendant qu'un client est encore en train d'envoyer un gros corps de requete, peut declencher cote navigateur selon la maniere dont Tomcat cloture la connexion (fermeture propre apres avoir avale le corps via max-swallow-size, versus reset). Si ce cas se produit, l'utilisateur verrait "Serveur injoignable" au lieu du message attendu "Fichier trop volumineux", ce qui viole directement l'exigence "erreur claire" de l'AC1, pour exactement la classe de requetes que ce ticket P1 est cense corriger.

Scenario qui le declenche : un client (navigateur avec bundle JS pas encore rafraichi, script ou integration tierce, curl, ou tout appelant qui contourne la garde frontend) envoie un fichier multipart de 11 a 15 Mo directement a un des 3 endpoints d'upload. Le filtre ou le backstop applicatif rejette probablement bien la requete cote serveur, mais rien ne prouve aujourd'hui que le client recoit effectivement le message clair plutot qu'une erreur reseau opaque ou un vrai blocage, ni via un test automatise (tous mockent HttpServletRequest), ni via une trace de verification manuelle.

Action demandee avant merge, au minimum a executer et documenter dans le rapport du bolt ou en commentaire de PR :
1. curl -v -X POST http://localhost:8080/api/customers/1/photo avec un token valide et un fichier d'environ 15 Mo en multipart, verifier une reponse 413 en quelques secondes avec le bon corps JSON.
2. Meme test en supprimant le Content-Length explicite ou en forcant un Transfer-Encoding chunked, pour exercer le filet de securite applicatif plutot que le filtre.
3. Un test dans un vrai navigateur (onglet reseau des devtools) avec un fichier de plus de 10 Mo envoye en contournant volontairement la garde JS, pour confirmer que l'interface affiche bien le message attendu et non "Serveur injoignable".

Ceci ne remet pas en cause la qualite du code, qui est coherent et bien pense, mais un ticket P1 dont l'unique objet est un comportement reseau/timeout ne peut pas etre approuve sans une preuve, meme legere, que ce comportement fonctionne reellement de bout en bout.

### 2. [Mineur, non bloquant] Reset de l'input file manquant sur echec de validation dans l'import CSV

Ou : frontend/src/pages/ImportPage.tsx, fonction chooseFile (lignes 67-78).

Probleme : contrairement a CustomerDetailPage.tsx et aux deux inputs de SaleDetailPage.tsx (ID_DOCUMENT/OTHER) qui font event.target.value = '' de facon inconditionnelle, chooseFile ne reinitialise jamais la valeur de l'input file sous-jacent.

Scenario : l'utilisateur selectionne un fichier CSV/Excel de 12 Mo, voit le message d'erreur, puis re-selectionne accidentellement le meme fichier via le meme bouton avant de choisir le bon fichier : le navigateur ne declenche pas de nouvel evenement change pour une valeur d'input inchangee, donc rien ne se passe visuellement (pas de nouvelle tentative, pas de nouveau message). Aucun risque de securite ou de donnees, simple incoherence UX mineure par rapport aux deux autres pages du meme ticket.

Suggestion : ajouter la reinitialisation de la valeur de l'input dans le onChange, symetriquement aux deux autres pages.

### 3. [Info, non bloquant] Duplication du seuil 10 Mo sans source de verite unique, et message generique quand c'est le seuil de 12 Mo qui declenche le rejet

Ou : backend/src/main/java/com/creditflow/config/UploadSizeGuardFilter.java (message code en dur "10 Mo" alors que le seuil reellement teste par le filtre est max-request-size, 12 Mo), backend GlobalExceptionHandler.java, frontend fileValidation.ts.

Le message reste correct et actionnable pour l'utilisateur final (10 Mo est la vraie limite produit a respecter), mais la valeur "10 Mo" est dupliquee textuellement a 3 endroits sans constante partagee, et le filtre l'affiche meme quand c'est en realite le seuil de 12 Mo qui a declenche le rejet. Si la configuration change un jour, il faudra penser a mettre a jour les 3 endroits a la main. Pas bloquant, a garder en tete pour une future iteration.

## Commits examines

- ce7cbcd bolt(#44): backend
- 868e205 bolt(#44): frontend - utilitaire
- ecc604b bolt(#44): frontend - integration pages

Diff complet verifie via git diff master...HEAD --stat (14 fichiers, 700 insertions, 8 suppressions) et lecture ligne a ligne de chaque fichier de code modifie ou ajoute (hors design.md/spec.md).
