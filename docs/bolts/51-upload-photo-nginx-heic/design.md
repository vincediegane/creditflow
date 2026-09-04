# Design #51 - Upload de photo client : limite nginx et formats HEIC

## Approche

Deux correctifs independants sur la meme chaine dupload, plus un filet de securite sur les
messages derreur.

1. Aligner nginx sur la vraie limite backend. Le backend accepte deja jusqua 10 Mo par fichier
   (max-file-size: 10MB) et 12 Mo par requete multipart (max-request-size: 12MB), et rejette
   proprement au-dela via UploadSizeGuardFilter/GlobalExceptionHandler (413 JSON, deja en
   place depuis #44). Seul client_max_body_size 8m dans nginx est en dessous de cette limite
   reelle, cest la vraie cause du silence pour une photo de 8-10 Mo. Correction: relever
   client_max_body_size a 12m (meme valeur que max-request-size backend).
2. HEIC/HEIF: rejet avec message explicite et actionnable, pas de conversion serveur. Aucune
   librairie de decodage HEIC/HEIF nest presente dans le projet, ni cote backend (pom.xml ne
   contient ni plugin ImageIO ni binding libheif) ni cote frontend. Ajouter une conversion
   fiable HEIC vers JPEG (backend: binding natif libheif complexe a packager dans le
   Dockerfile; frontend: nouvelle dependance WASM type heic2any, taille de bundle et fiabilite
   cross-navigateur non negligeables) est un investissement disproportionne pour un correctif
   P1 cible upload casse. Decision: garder DocumentValidation strict (jpg, jpeg, png, webp
   uniquement) mais detecter specifiquement lextension heic/heif pour renvoyer un message
   dedie et actionnable, au lieu du message generique actuel.
3. Erreur nginx (413) exploitable cote frontend. Aujourdhui locations.conf na aucun error_page,
   donc un 413 emis par nginx renvoie la page derreur HTML par defaut (pas de JSON). Cote
   frontend, errorMessage ne plante pas dans ce cas (elle retourne le fallback passe par
   lappelant des que error.response existe, meme sans body.message exploitable), et
   CustomerDetailPage.tsx passe deja un fallback explicite (La photo na pas pu etre
   enregistree) a onError, donc il ny a pas dechec totalement silencieux aujourdhui pour un
   vrai 413 HTTP. Le vrai gain attendu est un message specifique (fichier trop volumineux)
   plutot que le fallback generique. Correction: faire renvoyer par nginx un corps JSON minimal
   compatible ApiError sur 413.

## Fichiers/modules impactes

Nginx:
- frontend/nginx/locations.conf: client_max_body_size 8m vers 12m (ligne 10), et ajout dun
  error_page 413 vers une location interne renvoyant un JSON au format ApiError (status,
  error, message, path optionnel) avec Content-Type: application/json, pour que errorMessage
  cote frontend recupere un body.message exploitable au lieu de tomber sur le fallback
  generique.

Backend:
- backend/src/main/java/com/creditflow/common/storage/DocumentValidation.java: ajouter une
  detection dediee de heic/heif (avant ou en complement du test sur ALLOWED_EXTENSIONS) levant
  un BusinessRuleException avec un message specifique et actionnable (ex: Format HEIC/HEIF non
  pris en charge. Convertissez la photo en JPEG, ou sur iPhone: Reglages > Appareil photo >
  Formats > Le plus compatible.), distinct du message generique conserve pour les autres
  extensions non supportees. Cette classe est partagee avec CreditSaleService.uploadAttachment
  (pieces jointes de vente, elles aussi limitees a image/* cote frontend
  SaleDetailPage.tsx), le nouveau message beneficie donc aussi a ce flux sans logique
  additionnelle.
- backend/src/main/resources/application.yml: aucune valeur numerique a changer (max-file-size:
  10MB / max-request-size: 12MB couvrent deja la fourchette 8-10 Mo du ticket). A verifier
  seulement en implementation que rien na change depuis #44.
- backend/src/main/java/com/creditflow/config/UploadSizeGuardFilter.java et
  backend/src/main/java/com/creditflow/common/exception/GlobalExceptionHandler.java: le
  message Fichier trop volumineux, taille maximale autorisee: 10 Mo est duplique en dur a ces
  deux endroits (issu de #44). A envisager: le deriver de multipartProperties.getMaxFileSize
  plutot que de garder une 3e source de verite en dur, pour eviter de reproduire exactement le
  type de derive qui a cause ce ticket (nginx/backend/frontend desynchronises). Amelioration
  peu couteuse, a confirmer en spec si elle rentre dans le perimetre ou si on se contente de
  laisser les 3 valeurs synchronisees manuellement comme aujourdhui.

Frontend:
- Aucun changement necessaire dans frontend/src/utils/fileValidation.ts
  (MAX_UPLOAD_FILE_SIZE_BYTES = 10 Mo deja coherent avec le backend) ni dans
  frontend/src/api/client.ts (errorMessage gere deja le cas reponse sans JSON exploitable via
  son fallback), ces deux fichiers sont corrects, seule la couche nginx etait desynchronisee.
- frontend/src/pages/CustomerDetailPage.tsx: aucun changement de code necessaire; le fallback
  passe a onError de photoMutation est deja explicite. A verifier uniquement en test manuel/E2E
  que le message specifique remonte bien une fois le JSON 413 nginx en place.

## Decisions cles

- Valeur client_max_body_size: 12m, alignee sur max-request-size backend (pas sur
  max-file-size 10 Mo) pour ne jamais rejeter au niveau proxy une requete que le backend
  accepterait, le backend reste la seule source de verite sur le rejet fichier trop gros et
  produit un message propre.
- HEIC/HEIF: rejet cible plutot que conversion ou acceptation telle quelle. Ecarte
  lacceptation telle quelle (les navigateurs non-Safari naffichent pas nativement du HEIC, ce
  qui casserait CustomerAvatar/useAuthenticatedFile a laffichage) et la conversion serveur
  (nouvelle dependance native/lourde, hors proportion avec un correctif P1). Le rejet cible
  avec message actionnable est le compromis retenu pour ce ticket.
- Portee du message HEIC: modifie DocumentValidation, donc impacte aussi les pieces jointes de
  vente (CreditSaleService.uploadAttachment), pas seulement la photo client. Choix assume: la
  meme classe de validation, le meme message, plutot que dupliquer une logique specifique
  photo client qui nexiste pas ailleurs dans le code.
- JSON derreur nginx statique (pas de proxy vers le backend pour formatter lerreur): le 413
  est intercepte avant que la requete natteigne le backend, donc le corps JSON est un texte
  fixe dans la config nginx, pas genere dynamiquement. Message volontairement identique au
  message backend (Fichier trop volumineux, taille maximale autorisee: 10 Mo) pour rester
  coherent aux yeux de lutilisateur, meme si numeriquement le seuil qui a declenche le rejet
  est celui de nginx (12 Mo, cas limite improbable pour un fichier image unique en dessous de
  10 Mo plus overhead multipart).

## Risques et points dattention

- A confirmer en conditions reelles avant de considerer le ticket clos: le ticket precise
  lui-meme que le bug na pas ete reproduit (contrairement a #44). A tester explicitement avec
  un fichier de 8-10 Mo avant/apres le changement client_max_body_size, et avec un vrai fichier
  .heic issu dun iPhone (reglage Automatique) avant/apres le message dedie.
- Comportement variable de la selection de fichier sur iOS. Selon la version de Safari et le
  reglage Formats de lappareil photo, un input type=file accept=image/* peut soit laisser
  passer un fichier .heic brut, soit le faire transcoder en JPEG par le systeme avant meme
  datteindre le JS, comportement non maitrise par le code de ce repo. Le rejet cote
  DocumentValidation doit donc etre teste sur un appareil reel, pas seulement suppose depuis la
  doc Apple.
- error_page 413 global vs zone /api/ uniquement: le bloc de reponse JSON doit surtout couvrir
  /api/ (upload passe par ce prefixe); verifier quil ne casse pas le comportement existant des
  autres locations (assets statiques, /uploads/) si applique globalement au fichier commun.
- Duplication de la limite a 3 endroits (frontend 10 Mo, backend 10/12 Mo, nginx 12 Mo) reste
  la meme strategie quen #44 (pas dendpoint de config expose), cest cette duplication meme
  qui a cause la derive corrigee ici; sans mecanisme de verification automatique, rien
  nempeche une regression future similaire. A signaler au reviewer/spec-writer comme dette
  assumee plutot que silencieuse.
- Non-regression #44: ne pas rouvrir UploadSizeGuardFilter/max-swallow-size
  (server.tomcat.max-swallow-size: 15MB, deja superieur a 12 Mo, aucun changement requis), le
  ticket demande explicitement de ne pas rouvrir ce perimetre.
- Photo deja stockee avec un nom HEIC avant ce correctif: aucune migration necessaire, le
  message ameliore ne sapplique quaux nouveaux uploads; aucun fichier .heic ne peut deja etre
  stocke puisque DocumentValidation les rejetait deja avant ce ticket (le changement est
  uniquement le texte du message, pas le comportement dacceptation).

## Hors perimetre

- Pas de conversion HEIC/HEIF vers JPEG, ni cote serveur ni cote client (voir Decisions cles).
- Pas de changement des valeurs max-file-size/max-request-size backend, ni de
  MAX_UPLOAD_FILE_SIZE_BYTES frontend, deja coherentes avec la fourchette 8-10 Mo du ticket.
- Pas de reouverture de UploadSizeGuardFilter / max-swallow-size (perimetre #44, deja merge).
- Pas dendpoint de configuration expose au frontend pour eliminer la duplication de la limite
  entre les 3 couches, amelioration future, hors P1.
- Pas de changement du modele de donnees (Customer.photoUrl, SaleAttachment) ni de
  LocalDiskStorage/S3DocumentStorage, uniquement DocumentValidation (message) et la config
  nginx.
