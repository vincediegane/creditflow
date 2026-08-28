# Spec #44 - Upload de fichier volumineux : rejet propre au lieu d'un blocage

## Résumé

Rejeter en quelques secondes (413, `Content-Length` trop grand détecté avant
lecture du corps + filet de sécurité applicatif) tout upload dépassant 10 Mo,
et empêcher l'envoi côté frontend avec un message clair sur les trois points
d'upload (photo client, pièce jointe de contrat, import CSV/Excel).

## Décisions tranchées

- **Limite finale** : `max-file-size = 10MB`, `max-request-size = 12MB`
  (marge overhead multipart/boundary). 10 Mo est dans la fourchette
  10-15 Mo demandée par le ticket et couvre largement une photo JPEG issue
  d'un téléphone récent (typiquement 2-8 Mo, y compris capteurs 48-108 MP
  compressés) ; conservé au bas de la fourchette pour garder un rejet
  backend rapide et un message frontend cohérent. `server.tomcat.max-swallow-size`
  fixé à **15MB** (marge au-dessus de `max-request-size`) pour que Tomcat
  puisse "avaler" le corps résiduel proprement quand le filet de sécurité
  se déclenche (au lieu de couper brutalement la connexion).
- **Enregistrement du filtre** : `FilterRegistrationBean` (pas
  `HttpSecurity.addFilterBefore`). Raison : `addFilterBefore` ordonne les
  filtres *à l'intérieur* de la chaîne Spring Security
  (`FilterChainProxy`), mais ne change pas la position de
  `FilterChainProxy` lui-même dans la chaîne de filtres du conteneur
  servlet. Le filtre de garde n'a rien à voir avec l'authentification et
  doit s'exécuter avant elle (avant `JwtAuthenticationFilter`, avant tout
  parsing JWT) pour éviter tout travail inutile sur une requête déjà trop
  grosse. `FilterRegistrationBean` avec un ordre explicite
  (`Ordered.HIGHEST_PRECEDENCE`) donne ce contrôle au niveau conteneur,
  indépendamment de Spring Security — cohérent avec la remarque du design
  ("le garde indépendant de la logique d'authentification").

## Tâches

### Backend

- [ ] `backend/src/main/resources/application.yml` — remplacer
      `spring.servlet.multipart.max-file-size: 5MB` par `10MB` et
      `max-request-size: 5MB` par `12MB` (lignes 41-42) ; ajouter sous la
      section `server:` (ligne 1-4) une clé `tomcat.max-swallow-size: 15MB`.
- [ ] Nouveau `backend/src/main/java/com/creditflow/config/UploadSizeGuardFilter.java`
      — filtre (`OncePerRequestFilter` ou `jakarta.servlet.Filter`) qui :
      ne traite que les requêtes dont `Content-Type` commence par
      `multipart/` (insensible à la casse) ; lit `request.getContentLengthLong()`
      (ne lit jamais le corps) ; compare à
      `multipartProperties.getMaxRequestSize().toBytes()` (bean
      `org.springframework.boot.autoconfigure.web.servlet.MultipartProperties`,
      auto-configuré par Spring Boot, injecté par constructeur — pas de
      valeur dupliquée en dur) ; si `contentLength > 0` et dépasse la
      limite, écrit directement une réponse `413` (voir Contrat technique)
      avec `Connection: close`, sans appeler `chain.doFilter(...)` ; sinon
      appelle `chain.doFilter(...)` normalement (y compris si
      `Content-Length` est absent/`-1`, cas chunked — laissé au filet de
      sécurité).
- [ ] Nouveau `backend/src/main/java/com/creditflow/config/UploadSizeGuardFilterConfig.java`
      — bean `FilterRegistrationBean<UploadSizeGuardFilter>`, `addUrlPatterns("/api/*")`,
      `setOrder(Ordered.HIGHEST_PRECEDENCE)`.
- [ ] `backend/src/main/java/com/creditflow/common/exception/GlobalExceptionHandler.java`
      — ajouter `@ExceptionHandler(MaxUploadSizeExceededException.class)`
      retournant `413 PAYLOAD_TOO_LARGE` via `build(...)` (même patron que
      les handlers existants, ex. ligne 22-25), message :
      `"Fichier trop volumineux, taille maximale autorisee : 10 Mo"`.
- [ ] Nouveau test `backend/src/test/java/com/creditflow/config/UploadSizeGuardFilterTest.java`
      — unitaire avec Mockito (`HttpServletRequest`/`HttpServletResponse`/`FilterChain`
      mockés, pas de contexte Spring) : (1) `Content-Type: multipart/form-data`
      + `Content-Length` > limite → statut 413 écrit, `chain.doFilter`
      jamais appelé (`verify(chain, never())...`) ; (2) même Content-Type
      + `Content-Length` absent (`-1`) → `chain.doFilter` appelé ; (3)
      `Content-Type: application/json` (ex. `/api/customers` en POST) →
      `chain.doFilter` appelé sans inspection de la taille.
- [ ] Nouveau test `backend/src/test/java/com/creditflow/common/exception/GlobalExceptionHandlerTest.java`
      — unitaire, instancie `GlobalExceptionHandler` directement, appelle
      `handleMaxUploadSize(...)` avec un `HttpServletRequest` mocké et une
      `MaxUploadSizeExceededException`, vérifie statut 413 et message.
- [ ] Nouveau test d'intégration `backend/src/test/java/com/creditflow/customer/web/CustomerPhotoUploadSizeIntegrationTest.java`
      — `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)`,
      upload réel via `TestRestTemplate`/`RestClient` (Tomcat réel, pas
      MockMvc, pour que `Content-Length` soit réellement posé par le
      client HTTP) d'un fichier de ~15 Mo vers `/api/customers/{id}/photo`
      avec un utilisateur authentifié valide : vérifier réponse `413`
      reçue en moins de quelques secondes (assertion sur la durée
      mesurée, ex. `< 3000ms`) et corps JSON contenant le message attendu.
      Réutiliser ce test comme gabarit si un deuxième cas (upload
      d'attachment de vente) est jugé nécessaire, mais un seul endpoint
      suffit pour valider le comportement du filtre (générique, non lié à
      un endpoint précis).

### Frontend

- [ ] Nouveau `frontend/src/utils/fileValidation.ts` — exporte
      `MAX_UPLOAD_FILE_SIZE_BYTES = 10 * 1024 * 1024` (commentaire :
      aligné sur `spring.servlet.multipart.max-file-size` backend) et
      `validateMaxFileSize(file: File, maxBytes = MAX_UPLOAD_FILE_SIZE_BYTES): string | null`
      retournant un message du type `"Fichier trop volumineux (max 10 Mo)."`
      si dépassement, `null` sinon.
- [ ] Nouveau test `frontend/src/utils/__tests__/fileValidation.test.ts`
      (patron : `frontend/src/offline/__tests__/queue.test.ts`) — cas
      fichier sous la limite → `null` ; fichier au-dessus → message
      non-null contenant "10 Mo" ; fichier exactement à la limite →
      accepté (`null`, comparaison stricte `>`).
- [ ] `frontend/src/pages/CustomerDetailPage.tsx` — dans le `onChange` de
      l'input photo (lignes 111-116), appeler `validateMaxFileSize(file)`
      avant `photoMutation.mutate(file)` ; si message renvoyé,
      `setError(message)`, réinitialiser `event.target.value = ''` et ne
      pas déclencher la mutation.
- [ ] `frontend/src/pages/SaleDetailPage.tsx` — même validation aux trois
      points d'entrée : input pièce d'identité (`onChange`, lignes
      370-376), input "autre document" (`onChange`, lignes 399-405), et
      callback `onValidate` de `SignaturePad` (ligne 501, `setError` si
      message renvoyé — cas rare vu la taille générée par le canvas, mais
      gardé pour cohérence du patron de validation).
- [ ] `frontend/src/pages/ImportPage.tsx` — dans `chooseFile` (lignes
      66-70), appeler `validateMaxFileSize(selected)` si `selected` non
      nul ; si message renvoyé, `setError(message)` et ne pas appeler
      `setFile(selected)` (garder l'état précédent / `null`).

## Contrat technique

### Backend — configuration (`application.yml`)

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 12MB
server:
  tomcat:
    max-swallow-size: 15MB
```

### Backend — `UploadSizeGuardFilter`

- S'applique uniquement si `request.getContentType()` commence par
  `multipart/` (sinon passthrough immédiat).
- Ne lit jamais `request.getInputStream()`.
- Si `contentLength > 0 && contentLength > multipartProperties.getMaxRequestSize().toBytes()` :
  - `response.setStatus(413)`
  - `response.setHeader("Connection", "close")`
  - `response.setContentType("application/json;charset=UTF-8")`
  - Corps JSON au format `ApiError` existant (réutiliser `ObjectMapper`,
    même patron que `SecurityConfig.writeError(...)`, lignes 69-76) :
    `ApiError.of(413, "Payload Too Large", "Fichier trop volumineux, taille maximale autorisee : 10 Mo", request.getRequestURI())`
  - Ne pas appeler `chain.doFilter(...)`.
- Sinon : `chain.doFilter(request, response)`.

### Backend — enregistrement du filtre

```java
@Bean
public FilterRegistrationBean<UploadSizeGuardFilter> uploadSizeGuardFilterRegistration(
        UploadSizeGuardFilter filter) {
    FilterRegistrationBean<UploadSizeGuardFilter> registration = new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
}
```

### Backend — `GlobalExceptionHandler`

```java
@ExceptionHandler(MaxUploadSizeExceededException.class)
public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                      HttpServletRequest request) {
    return build(HttpStatus.PAYLOAD_TOO_LARGE,
            "Fichier trop volumineux, taille maximale autorisee : 10 Mo", request);
}
```

### Frontend — `fileValidation.ts`

```ts
export const MAX_UPLOAD_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 Mo — aligné sur le backend

export function validateMaxFileSize(
  file: File,
  maxBytes: number = MAX_UPLOAD_FILE_SIZE_BYTES,
): string | null {
  if (file.size > maxBytes) {
    const maxMb = Math.round(maxBytes / (1024 * 1024));
    return `Fichier trop volumineux (max ${maxMb} Mo).`;
  }
  return null;
}
```

Usage type (identique dans les trois pages) :

```ts
const validationError = validateMaxFileSize(file);
if (validationError) {
  setError(validationError);
  event.target.value = '';
  return;
}
photoMutation.mutate(file); // ou uploadAttachmentMutation.mutate(...), setFile(...)
```

## Plan de tests

| Critère d'acceptation (ticket #44) | Test |
|---|---|
| Upload dépassant la limite → erreur claire, en quelques secondes, sans blocage ni timeout | `UploadSizeGuardFilterTest` (unitaire, cas Content-Length > limite → 413 immédiat sans `chain.doFilter`) ; `CustomerPhotoUploadSizeIntegrationTest` (intégration, Tomcat réel, mesure de durée < quelques secondes) ; `GlobalExceptionHandlerTest` (filet de sécurité si Content-Length absent/mensonger) ; test manuel avec `curl` et transfert chunked (sans `Content-Length` fiable) pour valider le filet de sécurité `max-swallow-size` + handler — non automatisable simplement, à exécuter manuellement avant merge et noter le résultat dans la PR |
| Le frontend refuse localement un fichier trop volumineux, message explicite | `fileValidation.test.ts` (unitaire, message contient "10 Mo") ; vérification manuelle sur les 3 pages (`CustomerDetailPage`, `SaleDetailPage` x3 points, `ImportPage`) : sélectionner un fichier > 10 Mo affiche l'`Alert` d'erreur et aucune requête réseau n'est déclenchée (pas d'infra de test de composants React dans le repo actuellement — vérification manuelle uniquement pour ces 3 pages, la logique de décision est couverte par le test unitaire de l'utilitaire) |
| Limite réaliste pour une photo de téléphone (10-15 Mo) | Tranché en amont (10 Mo backend / 12 Mo requête, voir Décisions tranchées) ; vérification manuelle : uploader une photo réelle récente (12-48 MP, JPEG) sur les 3 points d'upload doit passer sans erreur |
| Non-régression : fichier valide sous la limite s'uploade normalement | `CustomerPhotoUploadSizeIntegrationTest` étendu d'un cas fichier ~1-2 Mo → 200 OK (même test que le cas 413, gabarit unique) ; tests service existants déjà présents (`CreditSaleServiceTest`, `LegacyImportServiceTest`, `FileStorageServiceTest`) restent verts sans modification — à faire tourner en CI pour confirmer l'absence de régression sur `uploadPhoto`, `uploadAttachment` (3 types), `legacySales` ; vérification manuelle des 3 flux frontend avec fichiers valides |

## Écarts identifiés

Aucun écart bloquant entre le design et le ticket. Deux points mineurs à
noter pour la suite (déjà couverts par les décisions ci-dessus, pas de
blocage) :

- Le design laissait la valeur finale de limite et le choix
  `FilterRegistrationBean` vs `SecurityConfig` ouverts explicitement à la
  charge du spec-writer — tranchés ci-dessus (10 Mo / 12 Mo / 15 Mo,
  `FilterRegistrationBean`).
- Le scénario "Content-Length absent ou mensonger" (upload chunked) ne
  peut pas être couvert par un test automatisé simple avec les outils
  déjà présents dans le repo (pas de `TestRestTemplate`/`RANDOM_PORT`
  existant, `curl` chunked difficile à scripter en test JVM) : listé en
  test manuel dans le plan de tests plutôt que comme lacune de couverture
  à combler avant merge.
