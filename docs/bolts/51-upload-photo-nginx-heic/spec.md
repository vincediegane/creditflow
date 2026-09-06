# Spec #51 - Upload de photo client : limite nginx et formats HEIC

## Résumé

Relever `client_max_body_size` à 12m et ajouter un `error_page 413` JSON dans `frontend/nginx/locations.conf`, et faire détecter par `DocumentValidation` les extensions HEIC/HEIF pour renvoyer un message dédié et actionnable au lieu du message générique.

## Tâches

- [ ] `frontend/nginx/locations.conf` (ligne 10) : remplacer `client_max_body_size 8m;` par `client_max_body_size 12m;`.
- [ ] `frontend/nginx/locations.conf` (bloc `location /api/`, lignes 68-76) : ajouter la redirection d'erreur 413 vers une location interne dédiée, et créer cette location. Exemple exact à adapter :
  ```nginx
  location /api/ {
      proxy_pass http://backend:8080;
      proxy_http_version 1.1;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      proxy_read_timeout 120s;
      error_page 413 = @api_413;
  }

  location @api_413 {
      internal;
      default_type application/json;
      return 413 '{"status":413,"error":"Payload Too Large","message":"Fichier trop volumineux, taille maximale autorisee : 10 Mo"}';
  }
  ```
  Placer `location @api_413 { ... }` juste après le bloc `location /api/` existant, avant `location /uploads/`. Ne pas toucher aux autres locations (`/uploads/`, `/swagger-ui/`, `/v3/api-docs`, assets statiques) : le scope du 413 JSON est uniquement `/api/`.
- [ ] `backend/src/main/java/com/creditflow/common/storage/DocumentValidation.java` : ajouter une liste `HEIC_EXTENSIONS = List.of("heic", "heif")` et, dans `validate(...)`, tester cette liste **avant** le test sur `ALLOWED_EXTENSIONS` pour lever un `BusinessRuleException` avec un message dédié si l'extension est heic/heif :
  ```java
  private static final List<String> HEIC_EXTENSIONS = List.of("heic", "heif");

  // dans validate(), juste après le calcul de `extension` :
  if (HEIC_EXTENSIONS.contains(extension)) {
      throw new BusinessRuleException(
          "Format HEIC/HEIF non pris en charge. Convertissez la photo en JPEG, ou sur iPhone : "
              + "Reglages > Appareil photo > Formats > Le plus compatible.");
  }
  if (!ALLOWED_EXTENSIONS.contains(extension)) {
      throw new BusinessRuleException("Format d'image non supporte (jpg, jpeg, png, webp)");
  }
  ```
  Ne pas ajouter `heic`/`heif` à `ALLOWED_EXTENSIONS` ni à `matchesExtension` (pas de magic-bytes HEIC à gérer, le rejet se fait avant toute lecture du contenu).
- [ ] `backend/src/test/java/com/creditflow/common/storage/DocumentValidationTest.java` : ajouter deux tests couvrant le rejet HEIC/HEIF avec message dédié (voir Plan de tests).
- [ ] `backend/src/main/resources/application.yml` : aucune modification — vérifier seulement que `max-file-size: 10MB` et `max-request-size: 12MB` (lignes 43-44) sont toujours en place, inchangées.
- [ ] Ne pas modifier `frontend/src/utils/fileValidation.ts`, `frontend/src/api/client.ts`, `frontend/src/pages/CustomerDetailPage.tsx`, `backend/src/main/java/com/creditflow/config/UploadSizeGuardFilter.java`, `backend/src/main/java/com/creditflow/common/exception/GlobalExceptionHandler.java` — hors scope (voir Écarts identifiés pour la justification du point sur la duplication du message).
- [ ] Vérification manuelle nginx (non automatisable dans ce repo, pas d'infra e2e/docker de test existante) : voir Plan de tests, item AC1 et AC3.

## Contrat technique

- **nginx `client_max_body_size`** : `8m` → `12m`, aligné sur `spring.servlet.multipart.max-request-size` (backend), pas sur `max-file-size` (10 Mo) — le backend reste seul juge du rejet fichier-trop-gros.
- **nginx `error_page 413`** : scope `location /api/` uniquement. Corps JSON minimal, `Content-Type: application/json`, champs `status` (int), `error` (string), `message` (string) — suffisant pour que `errorMessage()` (`frontend/src/api/client.ts:78-80`) lise `body.message` et l'affiche, sans dépendre de `timestamp`/`path`/`violations` (non lus par le frontend). Message identique au texte backend : `"Fichier trop volumineux, taille maximale autorisee : 10 Mo"`.
- **`DocumentValidation.HEIC_EXTENSIONS`** : `List.of("heic", "heif")`, comparaison insensible à la casse (réutilise `extensionOf()` qui lowercase déjà via `Locale.ROOT`).
- **Message HEIC** (`BusinessRuleException`, mappé en HTTP 422 par `GlobalExceptionHandler.handleBusiness`) : `"Format HEIC/HEIF non pris en charge. Convertissez la photo en JPEG, ou sur iPhone : Reglages > Appareil photo > Formats > Le plus compatible."` — s'applique identiquement à l'upload photo client (`Customer.photoUrl`) et aux pièces jointes de vente (`CreditSaleService.uploadAttachment`), les deux passant par `DocumentValidation.validate()`.
- **Formats acceptés inchangés** : `jpg, jpeg, png, webp` (`ALLOWED_EXTENSIONS`), aucune extension ajoutée.

## Plan de tests

| Critère d'acceptation (ticket #51) | Test | Type |
|---|---|---|
| Une photo de smartphone récent entre 8 et 10 Mo s'uploade avec succès | 1) Vérifier `client_max_body_size 12m;` dans `frontend/nginx/locations.conf` après modif. 2) Manuel : `docker compose up`, uploader une photo réelle de 8-10 Mo via l'UI (fiche client) ou via `curl -F "file=@photo_9mo.jpg" -X PUT http://localhost/api/customers/{id}/photo` avec un token valide, vérifier code 200/204 et absence de coupure. 3) Backend : couvert par les tests existants de `UploadSizeGuardFilterTest` (limite 12 Mo côté filtre, déjà en place depuis #44, aucune régression attendue car non modifié) | Manuel (nginx, pas d'infra de test automatisée pour la config nginx dans ce repo) + non-régression via tests backend existants |
| Une photo HEIC/HEIC est soit acceptée (conversion), soit refusée avec message explicite et actionnable | Tranché en design : refusée, pas de conversion. Test unitaire `DocumentValidationTest` : `rejectsHeicExtension()` — `MockMultipartFile("file", "photo.heic", "image/heic", <bytes quelconques>)`, assert `BusinessRuleException` avec message contenant `"HEIC/HEIF"` et `"Convertissez"`. `rejectsHeifExtension()` — même chose avec `photo.heif`. Complément manuel recommandé (signalé par l'architecte comme non fiable à 100% en théorie) : tester avec un vrai fichier `.heic` exporté d'un iPhone réglé sur "Le plus compatible" vs "Automatique", pour confirmer le comportement réel de sélection de fichier sur iOS/Safari | Unitaire (JUnit) + manuel complémentaire |
| Tout rejet (proxy ou backend) affiche un message visible — plus jamais d'échec silencieux | 1) Backend HEIC : déjà couvert par le test unitaire ci-dessus (`BusinessRuleException` → 422 JSON via `GlobalExceptionHandler.handleBusiness`, message lu par `errorMessage()` côté frontend, aucun changement requis côté frontend). 2) nginx 413 : manuel — envoyer un fichier > 12 Mo via `curl -i -F "file=@gros_fichier_13mo.jpg" http://localhost/api/customers/{id}/photo`, vérifier que la réponse est `413` avec `Content-Type: application/json` et un corps contenant `"message":"Fichier trop volumineux..."` (pas de page HTML nginx par défaut). 3) Backend 413 (fichier entre 10 et 12 Mo, donc accepté par nginx mais rejeté par le backend) : déjà couvert par `UploadSizeGuardFilterTest.rejectsOversizedMultipart` (non modifié, non-régression). 4) UI : vérifier manuellement dans `CustomerDetailPage.tsx` qu'un toast/message d'erreur s'affiche bien pour ces deux cas (413 nginx et 422 backend HEIC) | Unitaire (backend, cas HEIC) + manuel (nginx 413, affichage UI) |
| Non-régression : upload jpg/png/webp < 8 Mo continue de fonctionner | Tests existants `DocumentValidationTest.acceptsValidPng/acceptsValidJpeg/acceptsValidWebp` restent verts sans modification (le nouveau bloc HEIC est un `if` supplémentaire placé avant, qui ne modifie pas le comportement pour ces extensions). Complément manuel : re-tester l'upload d'une photo jpg classique < 8 Mo depuis l'UI après déploiement du nouveau `locations.conf`, pour confirmer que la remontée à 12m ne casse rien | Unitaire (existant, à garder vert) + manuel |

## Écarts identifiés

- **Duplication du message "Fichier trop volumineux..." — hors scope de ce ticket P1.** Le message est actuellement dupliqué en dur dans `UploadSizeGuardFilter.java:53` et `GlobalExceptionHandler.java:63`, et sera repris tel quel (dupliqué une 3e fois) dans le nouveau `error_page 413` de `locations.conf`. Une dérivation depuis `multipartProperties.getMaxFileSize()` serait plus robuste, mais :
  - aucun des 4 critères d'acceptation du ticket ne porte sur cette duplication ni sur le texte du message backend existant (déjà fonctionnel depuis #44) ;
  - le refactor toucherait deux fichiers backend déjà stables et testés (`UploadSizeGuardFilterTest`, tests de `GlobalExceptionHandler`) sans lien direct avec le bug d'upload silencieux décrit dans le ticket ;
  - il introduirait un risque de régression sur un périmètre que le ticket demande explicitement de ne pas rouvrir (« Chevauche le périmètre de #44 (déjà mergé) sans le rouvrir »).
  Décision : **laisser tel quel dans ce ticket**, dupliquer le message une 3e fois dans nginx de façon assumée (cohérence du message perçu par l'utilisateur prioritaire sur la déduplication technique). À traiter dans un ticket dédié ultérieur si jugé utile.
- **AC2 du ticket laissait le choix "accepté avec conversion" ou "refusé avec message"** — tranché par l'architecte (conversion jugée disproportionnée pour un P1, aucune lib HEIC dans le repo) : refus ciblé avec message actionnable. Aucun écart restant avec le ticket, ce choix est explicitement prévu comme "à trancher en spec" par l'AC — c'est fait ci-dessus.
- Aucun autre écart entre `design.md` et les critères d'acceptation du ticket : les 4 AC sont couvertes par les tâches ci-dessus (nginx 12m + error_page 413 pour AC1/AC3, message HEIC dédié pour AC2, non-modification des chemins déjà corrects pour AC4).
