# Design — #45 Module de stockage de documents abstrait par fournisseur

## Approche

`FileStorageService` devient une interface `DocumentStorage` (paquet
`com.creditflow.common.storage`) avec une implementation `LocalDiskStorage` (comportement actuel,
inchange, activee par defaut) et une implementation `S3DocumentStorage` (AWS S3, compatible MinIO
via endpoint override). Le fournisseur est selectionne par `@ConditionalOnProperty` sur
`app.storage.provider`, exactement le pattern deja utilise par `NotificationChannel`
(`ManualCopyChannel` / `WhatsAppCloudApiChannel`) : aucun `if (provider == ...)` disperse, un seul
bean `DocumentStorage` actif au demarrage. Second volet, indissociable du premier pour respecter le
critere d'acceptation « plus accessible sans authentification » : les DTO n'exposent plus jamais
l'URL/cle de stockage brute, mais une URL d'API backend stable (`/api/customers/{id}/photo`,
`/api/sales/{saleId}/attachments/{attachmentId}/file`) ; le controleur applique le controle d'acces
existant (scoping boutique via `getEntity()`/`assertAccessible`) puis delegue a `DocumentStorage`
soit un flux d'octets (local), soit une redirection vers une URL signee a duree limitee (S3). Le
prix de cette approche : les endroits qui affichent une image (`CustomerDetailPage.tsx`,
`SaleDetailPage.tsx`, `CustomersPage.tsx`) doivent etre adaptes cote frontend, car un `<img
src=...>` brut n'envoie pas l'en-tete `Authorization` : c'est un changement obligatoire, pas un
a-cote optionnel, sinon les photos/pieces jointes cessent de s'afficher apres ce ticket (regression
visible immediate). Azure Blob Storage n'est pas implemente dans ce ticket (l'interface le permet
sans changement d'appelant) : le ticket demande S3 en priorite et Azure « dans un second temps si
besoin », il n'y a aujourd'hui aucun deploiement qui l'exige.

## Fichiers/modules impactes

Backend, coeur du module de stockage :
- `backend/src/main/java/com/creditflow/common/storage/FileStorageService.java` renomme/scinde en
  `DocumentStorage.java` (interface : `store`, `delete`, `resolve`) et `LocalDiskStorage.java`
  (implementation actuelle : validation d'extension, magic bytes, garde anti-traversal,
  `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing =
  true)`).
- `backend/src/main/java/com/creditflow/common/storage/S3DocumentStorage.java` (nouveau) :
  `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")`, reutilise la meme
  validation d'extension/magic-bytes (factorisee dans une classe utilitaire partagee plutot que
  dupliquee), upload/suppression via `S3Client`, URL signee via `S3Presigner`.
- `backend/src/main/java/com/creditflow/common/storage/DocumentAccess.java` (nouveau) : type de
  retour de `resolve()`, soit un contenu inline (octets + content-type, cas local), soit une
  redirection vers une URL signee (cas S3). Le controleur choisit `200 body` ou `302 Location` selon
  le type recu, c'est le seul endroit qui « sait » qu'il y a deux formes de reponse possibles, la
  logique metier ne le sait jamais.
- `backend/src/main/java/com/creditflow/config/AppProperties.java` : extension de `Storage` :
  `provider` (defaut `local`), sous-classe `S3` (`bucket`, `region`, `accessKey`, `secretKey`,
  `endpoint` optionnel pour MinIO, `pathStyleAccess`, `signedUrlTtlSeconds`).
- `backend/src/main/resources/application.yml` : `app.storage.provider: ${STORAGE_PROVIDER:local}`
  et bloc `app.storage.s3.*` lie aux variables d'environnement `STORAGE_S3_*`.
- `backend/src/main/java/com/creditflow/config/StorageConfigValidator.java` (nouveau, meme pattern
  que `PlanConfigValidator`/`SecurityDefaultsValidator`) : echoue au demarrage si
  `STORAGE_PROVIDER=s3` mais qu'une variable requise (bucket, region, credentials) manque, plutot que
  d'echouer au premier upload en production.
- `backend/pom.xml` : ajout des dependances `software.amazon.awssdk:s3` et
  `software.amazon.awssdk:s3-presigner`.

Backend, controle d'acces et endpoints de telechargement :
- `backend/src/main/java/com/creditflow/config/WebConfig.java` : suppression de l'enregistrement du
  `ResourceHandler` sur `/uploads/**` (le fichier n'est plus jamais servi de facon statique, quel
  que soit le fournisseur).
- `backend/src/main/java/com/creditflow/config/SecurityConfig.java` : retrait de `/uploads/**` de
  `PUBLIC_ENDPOINTS`.
- `backend/src/main/java/com/creditflow/customer/web/CustomerController.java` : nouvel endpoint
  `GET /{id}/photo` (authentifie, meme garde de scoping que les autres endpoints du controleur).
- `backend/src/main/java/com/creditflow/customer/service/CustomerService.java` : nouvelle methode
  qui reutilise `getEntity(id)` (scoping boutique deja en place) puis appelle
  `documentStorage.resolve(...)` ; `uploadPhoto`/`delete` continuent d'utiliser `store`/`delete` sans
  changement de logique metier.
- `backend/src/main/java/com/creditflow/customer/dto/CustomerResponse.java` et
  `backend/src/main/java/com/creditflow/customer/mapper/CustomerMapper.java` : `photoUrl` cesse de
  refleter la cle de stockage brute et devient l'URL d'API `/api/customers/{id}/photo` (calculee a
  partir de l'id, pas de la valeur stockee) quand une photo existe, `null` sinon.
- `backend/src/main/java/com/creditflow/sale/web/SaleController.java` : nouvel endpoint
  `GET /{id}/attachments/{attachmentId}/file`, sur le modele exact de `invoice`/`delivery-note`
  deja presents (`ResponseEntity<byte[]>` + `Content-Disposition`), avec branche 302 pour S3.
- `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` : nouvelle methode qui
  reutilise `getEntity(saleId)` (scoping deja en place, ligne 144 `assertAccessible`) et
  `saleAttachmentRepository.findByIdAndSaleId` (deja utilise par `deleteAttachment`) avant d'appeler
  `documentStorage.resolve(...)`.
- `backend/src/main/java/com/creditflow/sale/dto/SaleAttachmentResponse.java` : `fileUrl` devient de
  meme l'URL d'API `/api/sales/{saleId}/attachments/{id}/file`.

Frontend, obligatoire pour eviter une regression d'affichage :
- `frontend/src/pages/CustomerDetailPage.tsx` (ligne ~98, `<img src={customer.photoUrl}>`),
  `frontend/src/pages/CustomersPage.tsx` (ligne ~195, meme pattern liste), et
  `frontend/src/pages/SaleDetailPage.tsx` (ligne ~443, `<img src={attachment.fileUrl}>`) :
  remplacer le `src` direct par une recuperation authentifiee (`api.get(url, { responseType: 'blob'
  })` via `frontend/src/api/client.ts`, qui pose deja l'en-tete `Authorization`) puis
  `URL.createObjectURL(...)`, factorisee dans un helper/hook partage (nouveau, ex.
  `frontend/src/hooks/useAuthenticatedFile.ts`) pour eviter de dupliquer la logique dans les trois
  pages.

Configuration / deploiement :
- `.env.production.example` et `.env.example` : nouvelle section `STORAGE_PROVIDER=local` et
  variables `STORAGE_S3_BUCKET`, `STORAGE_S3_REGION`, `STORAGE_S3_ACCESS_KEY`,
  `STORAGE_S3_SECRET_KEY`, `STORAGE_S3_ENDPOINT` (optionnel, MinIO), `STORAGE_S3_PATH_STYLE_ACCESS`,
  `STORAGE_SIGNED_URL_TTL_SECONDS`, documentees comme optionnelles tant que `STORAGE_PROVIDER=local`.
- `docker-compose.yml` : ajout de `STORAGE_PROVIDER: ${STORAGE_PROVIDER:-local}` et des
  `STORAGE_S3_*` (defauts vides) au bloc `environment:` du service `backend`, sans changer le
  comportement par defaut (`local`, volume `creditflow-uploads` inchange).

## Decisions cles

- **Renommage `FileStorageService` vers une interface `DocumentStorage`**, pas juste une extraction
  d'interface au-dessus de l'existant : le nom `FileStorageService` etait deja celui de
  l'implementation concrete ; garder ce nom pour l'interface aurait entretenu la confusion entre les
  deux couches. Cout : renommer les deux points d'appel (`CreditSaleService`, `CustomerService`).
- **Format de la cle stockee en base inchange pour le fournisseur local**
  (`/uploads/customers/<uuid>.jpg`, comme aujourd'hui) plutot que migre vers une cle relative
  propre (`customers/<uuid>.jpg`). Ce format n'est plus jamais expose tel quel a l'API/au
  frontend (voir plus bas), donc son historique n'a plus d'importance fonctionnelle, mais le garder
  identique evite toute migration de donnees Flyway sur `customers.photo_url` et
  `sale_attachments.file_url` pour les instances existantes, ce qui satisfait directement le critere
  d'acceptation « une instance qui ne configure rien continue de fonctionner a l'identique ».
  `LocalDiskStorage` garde la meme logique de decoupage du prefixe `publicPath` qu'aujourd'hui
  (`FileStorageService.deleteByPublicUrl`) pour retrouver le fichier sur disque.
- **Les DTO n'exposent plus la cle de stockage, mais une URL d'API calculee a partir de
  l'identifiant metier** (`/api/customers/{id}/photo`, pas `/api/documents/{cle}`). Alternative
  ecartee : exposer un endpoint generique `/api/documents/{key}`, rejetee car elle reintroduirait
  une cle opaque devinable/enumerable cote client et forcerait a revalider l'appartenance de la cle
  a un client/contrat a chaque requete au lieu de reutiliser directement le scoping deja present sur
  `getEntity(id)`.
- **Reponse a deux formes pour `resolve()`** (contenu inline en local, redirection 302 vers URL
  signee en S3) plutot qu'un flux uniforme (proxy integral des octets S3 a travers le backend dans
  tous les cas). Un flux uniforme aurait ete plus simple pour le controleur, mais fait transiter
  chaque telechargement par la memoire/le CPU du backend meme en S3, ce que la generation d'URL
  signee (explicitement suggeree par le ticket pour le cloud) evite.
- **Selection du fournisseur par `@ConditionalOnProperty`**, pas par une fabrique ou un aiguillage au
  runtime : un seul bean `DocumentStorage` existe dans le contexte Spring a la fois, coherent avec
  le pattern `NotificationChannel` deja en place et avec le critere « la logique metier ne connait
  pas le fournisseur concret ».
- **Azure Blob Storage non implemente** dans ce ticket (voir Hors perimetre) : l'interface
  `DocumentStorage` est concue pour qu'une future implementation Azure n'exige aucun changement
  d'appelant, seulement une nouvelle classe et une valeur `STORAGE_PROVIDER=azure`.
- **Validation de fichier (extensions autorisees, verification magic-bytes) factorisee** dans une
  classe utilitaire partagee entre `LocalDiskStorage` et `S3DocumentStorage`, plutot que dupliquee
  ou deplacee dans les services appelants : elle reste un detail d'implementation du stockage, pas
  de la logique metier client/contrat.

## Risques / points d'attention

- **Changement de contrat DTO non retrocompatible pour le frontend** : `photoUrl`/`fileUrl` passent
  d'une URL statique directement affichable a une URL d'API qui exige un en-tete `Authorization`.
  Sans l'adaptation frontend listee ci-dessus (fetch authentifie + `ObjectURL`), toute image cesse de
  s'afficher des le deploiement du backend : ce n'est pas une amelioration optionnelle, c'est une
  regression bloquante si le frontend n'est pas livre dans le meme changement.
- **Pas de migration de donnees entre fournisseurs** : changer `STORAGE_PROVIDER` sur une instance
  qui a deja des fichiers stockes en local ne les rapatrie pas vers S3 (et inversement) : les
  fichiers deja references en base redeviennent introuvables si on bascule le fournisseur sans
  avoir migre les objets au prealable. A documenter clairement dans `.env.production.example`
  (bascule = pour une nouvelle instance ou apres migration manuelle des objets, pas un simple
  changement de variable sur une instance en production avec des fichiers existants).
- **Duree de vie des URL signees** : une URL signee S3 avec un TTL court (quelques minutes) expiree
  avant que le navigateur ne l'utilise (page restee ouverte longtemps, cache navigateur) provoquera
  une erreur de chargement d'image sans message clair pour l'utilisateur : le frontend doit
  re-recuperer l'URL signee a chaque affichage (pas la mettre en cache long terme), ce que le
  helper `useAuthenticatedFile` doit garantir par construction (pas de cache persistant de l'URL
  signee).
- **`StorageConfigValidator` doit couvrir le cas `STORAGE_PROVIDER=s3` avec des valeurs vides**
  (variable presente dans l'environnement mais vide, cas frequent avec Docker Compose et des
  defauts vides par variable), pas seulement le cas ou la variable est absente, sur le modele de
  `SecurityDefaultsValidator` qui gere deja ce genre de garde pour `JWT_SECRET`.
- **MinIO n'est teste qu'indirectement** via la compatibilite API S3 (endpoint override et acces en
  mode path-style) : aucune instance MinIO n'est presente dans ce depot (`docker-compose.yml`
  n'a pas de service MinIO) ; il n'y a donc pas d'environnement de test d'integration local pour
  cette voie sans ajouter un service MinIO au compose de dev, ce qui n'est pas demande par le
  ticket.
- **`CustomersPage.tsx` affiche potentiellement plusieurs photos dans une liste** (ligne ~195) : si
  chaque miniature declenche un fetch authentifie separe, verifier qu'il n'y a pas de regression de
  performance perceptible sur une liste longue (N requetes au lieu de N chargements d'image
  paralleles par le navigateur) ; acceptable pour ce ticket mais a garder en tete si le nombre de
  clients affiches par page grandit.

## Hors perimetre

- Implementation Azure Blob Storage (interface prevue pour l'accueillir, mais pas livree ici : le
  ticket la place explicitement « dans un second temps si besoin »).
- Migration des fichiers deja stockes localement vers S3/Azure (script d'export/import d'objets) :
  changer `STORAGE_PROVIDER` sur une instance existante avec des fichiers deja en local n'est pas
  couvert.
- Isolation multi-tenant du stockage (ticket de suivi #41) : ce ticket securise l'acces
  (authentification) mais ne scope pas les fichiers par organisation, car le multi-tenant
  (`Organization`) n'existe pas encore dans le code (voir #25).
- Ajout d'un service MinIO a `docker-compose.yml` pour le developpement local.
- Extension de la liste des extensions/types de fichiers autorises (jpg, jpeg, png, webp) : ce
  ticket ne touche pas aux regles de validation de contenu, seulement au fournisseur de stockage et
  a l'acces.
- Generation des PDF (facture, bon de livraison) : ces fichiers sont generes a la volee par
  `CreditSaleService.invoice()`/`deliveryNote()` et ne transitent pas par `DocumentStorage`
  aujourd'hui ; ils restent hors perimetre de ce ticket.
