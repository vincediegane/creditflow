# Design #44 - Upload de fichier volumineux : rejet propre au lieu d'un blocage

## Approche

Le blocage n'est pas un probleme de message d'erreur manquant mais un
probleme de timing : Tomcat leve `SizeLimitExceededException` alors qu'il
est encore en train de lire le corps de la requete (`Request.parseParts`),
et la reponse d'erreur n'est ecrite qu'apres que Spring ait tente de
resoudre le multipart via `DispatcherServlet` -- trop tard, le client
(navigateur ou curl) est deja en train d'envoyer des Mo de donnees que
personne ne lit plus, d'ou l'attente jusqu'au timeout cote client. La
correction a deux niveaux :

1. **Backend - rejet avant lecture du corps** : un filtre servlet
   (`UploadSizeGuardFilter`) place avant la resolution multipart de
   Spring, qui lit uniquement l'en-tete `Content-Length` (jamais le corps)
   et renvoie immediatement `413 Payload Too Large` + `Connection: close`
   si la taille annoncee depasse la limite configuree. C'est le seul
   moyen fiable d'obtenir un rejet "en quelques secondes" : des qu'un
   client lit le corps de la requete (meme partiellement, meme pour le
   swallow), le cout de transfert reseau reste paye. Un
   `@ExceptionHandler(MaxUploadSizeExceededException.class)` est ajoute en
   filet de securite pour les cas ou `Content-Length` est absent ou
   inferieur a la realite (upload chunked) et ou Tomcat/Spring detectent
   quand meme le depassement en cours de parsing.
2. **Frontend - validation avant envoi** : verification de `file.size` sur
   les trois points d'upload avant tout appel HTTP, avec un message
   immediat. C'est la ligne de defense qui evite meme d'atteindre le
   backend dans l'immense majorite des cas (photo trop lourde prise au
   telephone).

Prix du choix : le filtre Content-Length ne protege pas contre un client
qui mentirait sur l'en-tete `Content-Length` (rare, non couvert par le
ticket) ; c'est pourquoi le filet de securite backend (ExceptionHandler)
et une limite Tomcat coherente (`max-swallow-size`) restent necessaires en
complement, pas en remplacement.

## Fichiers/modules impactes

Backend :
- `backend/src/main/resources/application.yml` -- relever
  `spring.servlet.multipart.max-file-size` et `max-request-size`
  (actuellement `5MB` / `5MB` -- valeur cible a trancher, voir Decisions
  cles), et ajouter/aligner `server.tomcat.max-swallow-size` pour eviter
  l'etat incoherent ou la limite de swallow (defaut Tomcat = 2 MB) est
  inferieure a la nouvelle limite d'upload.
- Nouveau : `backend/src/main/java/com/creditflow/config/UploadSizeGuardFilter.java`
  (ou `common/web/`) -- filtre lisant `Content-Length`, comparant a
  `MultipartProperties.getMaxRequestSize()` (bean deja fourni par
  l'auto-configuration multipart de Spring Boot, evite de dupliquer la
  valeur en dur), rejette en `413` + `Connection: close` sans toucher au
  flux d'entree si depassement, sinon `chain.doFilter()` normal. Ne
  s'applique qu'aux requetes dont `Content-Type` commence par
  `multipart/`.
- Enregistrement du filtre : soit via `FilterRegistrationBean` (bean dans
  `WebConfig.java` ou une nouvelle petite classe de config), soit via
  `HttpSecurity.addFilterBefore(...)` dans `SecurityConfig.java`. A
  trancher (voir Decisions cles) : le filtre doit s'executer avant toute
  lecture du corps, donc avant `DispatcherServlet` -- les deux options
  fonctionnent, mais `FilterRegistrationBean` le garde independant de la
  logique d'authentification.
- `backend/src/main/java/com/creditflow/common/exception/GlobalExceptionHandler.java`
  -- ajouter `@ExceptionHandler(MaxUploadSizeExceededException.class)`
  retournant `413 PAYLOAD_TOO_LARGE` avec un message explicite (ex.
  "Fichier trop volumineux, taille maximale autorisee : X Mo"), sur le
  meme patron que les handlers existants (`build(HttpStatus, message,
  request)`).
- Tests : nouveaux tests pour `UploadSizeGuardFilter` (rejet immediat sans
  lecture du corps) et pour le nouveau handler dans un test d'integration
  d'un des trois endpoints avec un fichier au-dela de la limite (verifier
  `413` rapide, pas de timeout).

Frontend :
- `frontend/src/api/endpoints.ts` -- aucune modification de signature
  necessaire ; la validation de taille doit se faire avant l'appel a
  `customersApi.uploadPhoto`, `salesApi.uploadAttachment`,
  `importApi.legacySales`, donc au niveau des pages/composants
  appelants plutot que dans le client API lui-meme (ces fonctions
  prennent deja un `File` en parametre et ne font aucune validation
  aujourd'hui).
- `frontend/src/pages/CustomerDetailPage.tsx` -- valider `file.size` dans
  le `onChange` de l'input photo (autour de la ligne 111-116) avant
  `photoMutation.mutate(file)`, afficher `setError(...)` avec un message
  explicite si trop lourd, ne pas declencher la mutation.
- `frontend/src/pages/SaleDetailPage.tsx` -- meme validation aux trois
  points d'entree de piece jointe : input piece d'identite (autour de la
  ligne 370-376), input "autre document" (autour de la ligne 399-405), et
  `SignaturePad`'s `onValidate` (autour de la ligne 501, fichier genere
  par le canvas de signature -- normalement tres petit, mais coherent de
  le passer par la meme verification).
- `frontend/src/pages/ImportPage.tsx` -- valider `file.size` dans
  `chooseFile` (autour de la ligne 66-70) pour l'import CSV/Excel legacy.
- Nouveau : petit utilitaire partage (ex.
  `frontend/src/utils/fileValidation.ts`) exportant une fonction du type
  `validateMaxFileSize(file, maxBytes)` retournant un message d'erreur ou
  `null`, et une constante de limite en octets, pour eviter de dupliquer
  le seuil dans les trois pages et pour que la valeur reste alignee avec
  le backend.

## Decisions cles

- **Valeur finale de la limite** : proposition **10 Mo** pour
  `max-file-size` (photo/piece jointe unique), avec `max-request-size` a
  **12 Mo** (marge pour l'overhead multipart/boundary, requetes toujours
  mono-fichier sur les trois endpoints concernes -- `uploadPhoto`,
  `uploadAttachment`, `legacySales` n'envoient jamais qu'un seul champ
  `file`). 10 Mo est dans la fourchette 10-15 Mo suggeree par le ticket et
  couvre une photo de telephone recent tout en restant raisonnable pour
  un import CSV/Excel de reprise de donnees. A confirmer par le
  spec-writer si un seuil different est prefere (ex. 15 Mo pour plus de
  marge photo).
- **Mecanisme de rejet propre** : filtre Content-Length pre-multipart
  (ci-dessus) choisi plutot que de se reposer uniquement sur
  `server.tomcat.max-swallow-size`. Augmenter seulement le swallow-size
  (ex. `-1` illimite) ferait lire tout le corps oversized avant de
  repondre -- ca evite le blocage indefini mais pas le gaspillage de bande
  passante/temps pour un fichier de plusieurs dizaines de Mo. Le filtre
  Content-Length rejette avant meme le premier octet du corps. Le
  `max-swallow-size` est neanmoins releve/aligne en filet de securite pour
  les requetes qui echapperaient au filtre (voir Risques).
- **Coherence frontend/backend** : la limite frontend doit etre egale ou
  legerement inferieure a `max-file-size` backend (jamais superieure),
  pour qu'un fichier accepte cote client soit garanti accepte cote
  serveur. Decision : utiliser exactement la meme valeur en octets
  (10 x 1024 x 1024) des deux cotes, dupliquee en dur cote frontend (pas
  d'endpoint public exposant la config serveur actuellement) -- a
  documenter clairement en commentaire pour que toute evolution future de
  `application.yml` pense a repercuter la valeur cote frontend.
- **Limite de l'import CSV/Excel (`ImportPage.tsx`)** : le ticket ne fixe
  pas de seuil dedie pour l'import legacy, seulement pour les photos. Ce
  fichier partage la meme limite serveur globale (`max-file-size`
  s'applique a toute requete multipart, pas seulement aux photos).
  Decision : reutiliser la meme constante frontend (10 Mo) plutot que
  d'introduire un seuil distinct non demande par le ticket.

## Risques / points d'attention

- **Ordre d'execution du filtre** : le filtre doit s'executer avant que
  quoi que ce soit ne lise le corps de la requete, y compris avant
  `JwtAuthenticationFilter`/Spring Security si possible (rejeter un upload
  surdimensionne ne necessite pas d'etre authentifie au prealable, et
  eviter cette dependance simplifie le raisonnement). A verifier en
  implementation que Spring Security (`DelegatingFilterProxy`) ne lit pas
  le corps avant notre filtre pour les endpoints multipart.
- **`Content-Length` absent ou mensonger** : un client qui envoie en
  `Transfer-Encoding: chunked` (rare pour un upload de fichier depuis un
  navigateur avec `FormData`, mais possible via certains clients HTTP)
  n'a pas de `Content-Length` fiable -- le filtre ne peut pas rejeter en
  amont dans ce cas, et c'est le filet de securite
  (`MaxUploadSizeExceededException` + `max-swallow-size` correctement
  configure) qui doit eviter le blocage. A tester explicitement en
  reproduisant le scenario du ticket (curl, 6 Mo, sans/avec Content-Length)
  pour confirmer qu'aucun chemin ne mene plus au timeout.
- **`Connection: close` sur rejet** : necessaire pour eviter de laisser la
  connexion keep-alive dans un etat incoherent (corps partiellement
  envoye/non drane). A verifier que le client (axios/navigateur) gere
  correctement la fermeture de connexion et presente bien le message 413
  a l'utilisateur plutot qu'une erreur reseau opaque.
- **Non-regression sur les trois flux existants** : `uploadPhoto`
  (`CustomerDetailPage.tsx`), `uploadAttachment` (`SaleDetailPage.tsx`,
  trois types ID_DOCUMENT/SIGNATURE/OTHER) et `legacySales`
  (`ImportPage.tsx`) doivent continuer a fonctionner sans changement pour
  un fichier sous la limite -- la seule chose qui change en dessous du
  seuil est l'ajout d'une verification cote client (pas de nouvel appel
  reseau, pas de changement du flux de succes). A tester chaque flux avec
  un fichier valide.
- **`FileStorageService` inchange** : ce service ne fait aucune
  verification de taille aujourd'hui (seulement extension + magic bytes) ;
  il continue de recevoir des `MultipartFile` deja valides par Spring/le
  filtre en amont -- pas de nouvelle logique de taille a y ajouter, la
  limite se joue avant d'atteindre le controller.
- **`SignaturePad`** : le fichier genere par le canvas de signature est
  normalement tres petit (quelques dizaines de Ko), la validation de
  taille y est une formalite de coherence, pas un cas reel de blocage --
  a ne pas complexifier inutilement.

## Hors perimetre

- Pas de mecanisme d'upload progressif / chunked / resumable -- le ticket
  demande un rejet propre et rapide d'un fichier trop gros, pas un
  support des tres gros fichiers.
- Pas de compression/redimensionnement cote client des photos avant
  envoi -- solution produit differente (reduirait le besoin d'une limite
  haute mais change le comportement fonctionnel), non demandee par le
  ticket.
- Pas d'endpoint public exposant la configuration de limite au frontend
  (ex. `/api/config`) -- la valeur reste dupliquee en dur des deux cotes
  pour ce ticket (voir Decisions cles), une factorisation via un endpoint
  de config est une amelioration ulterieure hors perimetre P1.
- Pas de changement du modele de donnees (`SaleAttachment`,
  `SaleAttachmentType`) ni de `FileStorageService` (extensions
  autorisees, verification magic bytes) -- uniquement la couche de rejet
  taille et les messages associes.
- Pas de retry automatique cote frontend en cas de rejet -- l'utilisateur
  doit choisir un autre fichier, comportement deja implicite dans les
  boutons "Changer la photo" / "Joindre un document" existants.
