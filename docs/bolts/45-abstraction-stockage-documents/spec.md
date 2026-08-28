# Spec — #45 Module de stockage de documents abstrait par fournisseur

## Résumé

Remplacement de `FileStorageService` par une interface `DocumentStorage` (implémentations `LocalDiskStorage` et `S3DocumentStorage` sélectionnées par `STORAGE_PROVIDER`) et fermeture de l'accès public aux fichiers uploadés, désormais servis par des endpoints authentifiés et scopés par boutique.

## Tâches

### Backend — cœur du module de stockage

- [ ] Extraire la validation de fichier partagée (extensions autorisées, magic-bytes) de `backend/src/main/java/com/creditflow/common/storage/FileStorageService.java` vers une nouvelle classe utilitaire `backend/src/main/java/com/creditflow/common/storage/DocumentValidation.java` (logique identique à l'existant : `ALLOWED_EXTENSIONS` = jpg/jpeg/png/webp, vérification des magic-bytes, extraction d'extension).
  - Test : `backend/src/test/java/com/creditflow/common/storage/DocumentValidationTest.java` — reprend les cas de `FileStorageServiceTest` (accepte PNG/JPEG/WEBP valides, rejette un fichier texte renommé `.png`, rejette un `.jpg` dont le contenu est un PNG, rejette une extension non supportée).

- [ ] Créer l'interface `backend/src/main/java/com/creditflow/common/storage/DocumentStorage.java` :
  ```java
  public interface DocumentStorage {
      String store(MultipartFile file, String folder);
      void delete(String key);
      DocumentAccess resolve(String key);
  }
  ```

- [ ] Créer le type `backend/src/main/java/com/creditflow/common/storage/DocumentAccess.java`, interface scellée :
  ```java
  public sealed interface DocumentAccess permits DocumentAccess.Inline, DocumentAccess.Redirect {
      record Inline(byte[] content, String contentType) implements DocumentAccess {}
      record Redirect(String url) implements DocumentAccess {}
  }
  ```

- [ ] Remplacer `backend/src/main/java/com/creditflow/common/storage/FileStorageService.java` par `backend/src/main/java/com/creditflow/common/storage/LocalDiskStorage.java` implémentant `DocumentStorage`, annotée `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)`. `store`/`delete` reprennent le comportement actuel (via `DocumentValidation`, garde anti-traversal `directory.startsWith(root)` inchangée). Nouvelle méthode `resolve(String key)` : lit le fichier sur disque à partir de la clé (même résolution de chemin que `deleteByPublicUrl` actuel) et retourne `DocumentAccess.Inline(bytes, contentType)` avec le content-type déduit de l'extension (`jpg`/`jpeg` → `image/jpeg`, `png` → `image/png`, `webp` → `image/webp`) ; lève `ResourceNotFoundException` si le fichier n'existe pas. Supprimer `FileStorageService.java`.
  - Test : `backend/src/test/java/com/creditflow/common/storage/LocalDiskStorageTest.java` — migre les cas de `FileStorageServiceTest` (renommé/supprimé) + nouveaux cas `resolve` : fichier existant → `Inline` avec le bon `contentType` et les bons octets ; fichier absent → `ResourceNotFoundException`.

- [ ] Étendre `backend/src/main/java/com/creditflow/config/AppProperties.java` : ajouter `provider` (String, défaut `"local"`) à `Storage`, et une classe imbriquée `Storage.S3` : `bucket`, `region`, `accessKey`, `secretKey` (String, nullable), `endpoint` (String, nullable — MinIO), `pathStyleAccess` (boolean, défaut `false`), `signedUrlTtlSeconds` (int, défaut `300`).

- [ ] Mettre à jour `backend/src/main/resources/application.yml`, bloc `app.storage` :
  ```yaml
  storage:
    upload-dir: ${UPLOAD_DIR:./data/uploads}
    public-path: /uploads
    provider: ${STORAGE_PROVIDER:local}
    s3:
      bucket: ${STORAGE_S3_BUCKET:}
      region: ${STORAGE_S3_REGION:}
      access-key: ${STORAGE_S3_ACCESS_KEY:}
      secret-key: ${STORAGE_S3_SECRET_KEY:}
      endpoint: ${STORAGE_S3_ENDPOINT:}
      path-style-access: ${STORAGE_S3_PATH_STYLE_ACCESS:false}
      signed-url-ttl-seconds: ${STORAGE_SIGNED_URL_TTL_SECONDS:300}
  ```
  (`public-path` reste utilisé uniquement en interne par `LocalDiskStorage` pour retrouver le fichier depuis la clé stockée — il n'est plus exposé via un `ResourceHandler`.)

- [ ] Ajouter au `backend/pom.xml` : import du BOM `software.amazon.awssdk:bom` en `dependencyManagement` (aucun BOM AWS n'est actuellement importé — figer une version, ex. `2.28.x`), puis les dépendances `software.amazon.awssdk:s3` et `software.amazon.awssdk:s3-presigner` (sans version explicite, héritée du BOM).

- [ ] Créer `backend/src/main/java/com/creditflow/common/storage/S3DocumentStorage.java` implémentant `DocumentStorage`, `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")` :
  - `store` : valide via `DocumentValidation` (même règles que local), construit une clé `folder + "/" + UUID + "." + extension` (**sans** préfixe `/uploads`, contrairement à la clé locale), upload via `PutObjectRequest`/`S3Client`, retourne la clé.
  - `delete(key)` : no-op si `key` vide/nulle (même garde que le comportement actuel), sinon `DeleteObjectRequest`.
  - `resolve(key)` : génère une URL signée `GetObject` via `S3Presigner`, durée = `signedUrlTtlSeconds`, retourne `DocumentAccess.Redirect(url)`.
  - Construction du client : `endpointOverride(URI.create(endpoint))` si `endpoint` renseigné (MinIO), `forcePathStyle(pathStyleAccess)`, credentials via `StaticCredentialsProvider`.
  - Test : `backend/src/test/java/com/creditflow/common/storage/S3DocumentStorageTest.java` — `S3Client`/`S3Presigner` mockés (Mockito) : `store` upload le contenu validé et retourne une clé sans `/uploads` ; `store` rejette un fichier invalide **avant** tout appel S3 (mêmes cas que `DocumentValidationTest`, vérifiés via `verifyNoInteractions(s3Client)`) ; `delete` appelle `deleteObject` avec la bonne clé et ne fait rien sur clé vide ; `resolve` retourne un `DocumentAccess.Redirect` dont l'URL provient du `S3Presigner` mocké.

- [ ] Créer `backend/src/main/java/com/creditflow/config/StorageConfigValidator.java` (même pattern que `PlanConfigValidator`/`SecurityDefaultsValidator`, `@PostConstruct`) : si `app.storage.provider=s3`, échoue au démarrage (`IllegalStateException`, message listant les variables manquantes) si `bucket`, `region`, `accessKey` ou `secretKey` est absent **ou** vide (`!StringUtils.hasText(...)`). Aucun contrôle si `provider=local`.
  - Test : `backend/src/test/java/com/creditflow/config/StorageConfigValidatorTest.java` — même style que `PlanConfigValidatorTest` (`ApplicationContextRunner`) : `provider=s3` avec toutes les variables absentes → échec ; `provider=s3` avec une variable présente mais vide (`""`) → échec ; `provider=s3` avec toutes les variables renseignées → pas d'échec ; configuration par défaut (`provider` non défini) → pas d'échec.

- [ ] Créer `backend/src/test/java/com/creditflow/common/storage/DocumentStorageWiringTest.java` (même pattern que `NotificationChannelWiringTest`, `ApplicationContextRunner` avec `LocalDiskStorage.class`, `S3DocumentStorage.class`, `DocumentValidation`) : configuration par défaut → un seul bean `LocalDiskStorage`, aucun bean `S3DocumentStorage` ; `app.storage.provider=s3` (+ propriétés S3 minimales) → un seul bean `S3DocumentStorage`, aucun bean `LocalDiskStorage`. Vérifie qu'un seul bean `DocumentStorage` est actif à la fois (critère d'acceptation « pas de branchement `if (provider == ...)` dispersé »).

### Backend — contrôle d'accès et endpoints de téléchargement

- [ ] Supprimer l'enregistrement du `ResourceHandler` sur `/uploads/**` dans `backend/src/main/java/com/creditflow/config/WebConfig.java`. Si le fichier ne conserve plus aucune logique utile, le supprimer entièrement ; retirer/reformuler toute référence Javadoc `{@link WebConfig}` ailleurs dans le code pour éviter un lien Javadoc mort.

- [ ] Retirer `"/uploads/**"` du tableau `PUBLIC_ENDPOINTS` dans `backend/src/main/java/com/creditflow/config/SecurityConfig.java`.

- [ ] Créer `backend/src/main/java/com/creditflow/common/storage/web/DocumentAccessResponses.java` : utilitaire partagé convertissant un `DocumentAccess` en `ResponseEntity<byte[]>` — `Inline` → `200 OK` avec `Content-Type` (pas de `Content-Disposition`, affichage inline) ; `Redirect` → `302` avec l'en-tête `Location`. Réutilisé par les deux contrôleurs ci-dessous pour éviter un `switch` dupliqué.
  - Test : `backend/src/test/java/com/creditflow/common/storage/web/DocumentAccessResponsesTest.java` — `Inline(bytes, "image/png")` → statut 200, corps = `bytes`, `Content-Type: image/png` ; `Redirect("https://...")` → statut 302, en-tête `Location` = l'URL.

- [ ] Ajouter l'endpoint `GET /{id}/photo` dans `backend/src/main/java/com/creditflow/customer/web/CustomerController.java`, déléguant à `CustomerService.resolvePhoto(id)` puis `DocumentAccessResponses`.

- [ ] Ajouter `CustomerService.resolvePhoto(Long id)` dans `backend/src/main/java/com/creditflow/customer/service/CustomerService.java` : réutilise `getEntity(id)` (scoping boutique existant), lève `ResourceNotFoundException.of("Photo", id)` si `photoUrl` est vide/nul, sinon retourne `documentStorage.resolve(customer.getPhotoUrl())`. Remplacer le champ `FileStorageService fileStorageService` par `DocumentStorage documentStorage` ; `uploadPhoto`/`delete` continuent d'appeler `store`/`delete` sans changement de logique métier (juste le type injecté).

- [ ] Modifier `backend/src/main/java/com/creditflow/customer/mapper/CustomerMapper.java` : `photoUrl` devient une expression calculée — `"/api/customers/" + customer.getId() + "/photo"` si `customer.getPhotoUrl()` non vide, `null` sinon — au lieu du mapping implicite du champ brut.
  - Test : `backend/src/test/java/com/creditflow/customer/service/CustomerServiceTest.java` (méthode ajoutée) — `resolvePhoto` avec photo présente délègue à `documentStorage.resolve(...)` ; `resolvePhoto` sans photo lève `ResourceNotFoundException` ; `resolvePhoto` sur un client d'une autre boutique lève `ResourceNotFoundException` (via `assertAccessible`, cf. test existant `getEntityRejectsCustomerFromAnotherShop`).
  - Test : `backend/src/test/java/com/creditflow/customer/web/CustomerControllerSecurityTest.java` (tests ajoutés) — requête `GET /api/customers/1/photo` **sans authentification** → 401 ; **avec authentification**, `customerService.resolvePhoto` mocké retournant `Inline` → 200 + `Content-Type` ; mocké retournant `Redirect` → 302 + `Location` ; `customerService.resolvePhoto` levant `ResourceNotFoundException` (client d'une autre boutique) → 404.

- [ ] Ajouter l'endpoint `GET /{id}/attachments/{attachmentId}/file` dans `backend/src/main/java/com/creditflow/sale/web/SaleController.java`, déléguant à `CreditSaleService.resolveAttachment(id, attachmentId)` puis `DocumentAccessResponses`.

- [ ] Ajouter `CreditSaleService.resolveAttachment(Long saleId, Long attachmentId)` dans `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` : réutilise `getEntity(saleId)` (scoping boutique) puis `saleAttachmentRepository.findByIdAndSaleId(attachmentId, saleId)` (déjà utilisé par `deleteAttachment`), lève `ResourceNotFoundException.of("Piece jointe", attachmentId)` si absent, sinon `documentStorage.resolve(attachment.getFileUrl())`. Remplacer le champ `FileStorageService fileStorageService` par `DocumentStorage documentStorage`.

- [ ] Modifier `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java` (`toResponse(SaleAttachment attachment)`) : `fileUrl` devient `"/api/sales/" + attachment.getSale().getId() + "/attachments/" + attachment.getId() + "/file"` au lieu de `attachment.getFileUrl()`.
  - Test : `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` (méthode ajoutée) — `resolveAttachment` avec pièce jointe existante délègue à `documentStorage.resolve(...)` ; `resolveAttachment` avec `attachmentId` inexistant pour ce contrat lève `ResourceNotFoundException` ; `resolveAttachment` sur un contrat d'une autre boutique lève `ResourceNotFoundException`.
  - Test : `backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java` (tests ajoutés) — mêmes quatre cas que pour `CustomerControllerSecurityTest` (401 sans auth, 200 `Inline`, 302 `Redirect`, 404 pièce jointe/contrat inaccessible), sur `GET /api/sales/1/attachments/2/file`.

### Frontend

- [ ] Créer `frontend/src/utils/apiFileUrl.ts` : fonction pure `toAuthenticatedFetchPath(apiUrl: string): string` retirant le préfixe `/api` d'une URL DTO (`/api/customers/1/photo` → `/customers/1/photo`) afin d'être passée à l'instance axios `api` (`frontend/src/api/client.ts`, `baseURL` = `/api` par défaut ou `VITE_API_URL` se terminant par `/api`) sans double-préfixage.
  - Test : `frontend/src/utils/__tests__/apiFileUrl.test.ts` (même style que `fileValidation.test.ts`) — `"/api/customers/1/photo"` → `"/customers/1/photo"` ; `"/api/sales/1/attachments/2/file"` → `"/sales/1/attachments/2/file"` ; entrée déjà sans préfixe `/api` renvoyée telle quelle (garde défensive).

- [ ] Créer `frontend/src/hooks/useAuthenticatedFile.ts` : hook `useAuthenticatedFile(apiUrl?: string | null)` qui, si `apiUrl` est défini, appelle `api.get(toAuthenticatedFetchPath(apiUrl), { responseType: 'blob' })`, construit une URL via `URL.createObjectURL`, la révoque (`URL.revokeObjectURL`) au démontage et à chaque changement d'`apiUrl` (pas de cache persistant, conformément à `design.md`), retourne `{ url: string | undefined, isLoading: boolean }`.

- [ ] Créer `frontend/src/components/CustomerAvatar.tsx` : composant `<CustomerAvatar customer={...} sx={...} />` encapsulant `useAuthenticatedFile(customer.photoUrl)` + `<Avatar src={url}>{initials(...)}</Avatar>` — nécessaire pour être utilisé dans une boucle `.map()` (un hook ne peut pas être appelé directement dans le callback d'un `.map()`, seulement dans le corps d'un composant).

- [ ] Créer `frontend/src/components/AttachmentThumbnail.tsx` : composant équivalent pour les pièces jointes de vente, encapsulant `useAuthenticatedFile(attachment.fileUrl)` + `<Box component="img" src={url}>`.

- [ ] Modifier `frontend/src/pages/CustomerDetailPage.tsx` (ligne ~98) : remplacer `<Avatar src={customer.photoUrl} ...>{initials(...)}</Avatar>` par `<CustomerAvatar customer={customer} />`.

- [ ] Modifier `frontend/src/pages/CustomersPage.tsx` (ligne ~195, dans `rows.map(...)`) : remplacer `<Avatar src={customer.photoUrl} ...>` par `<CustomerAvatar customer={customer} />`.

- [ ] Modifier `frontend/src/pages/SaleDetailPage.tsx` (ligne ~443, dans `attachments.map(...)`) : remplacer `<Box component="img" src={attachment.fileUrl} .../>` par `<AttachmentThumbnail attachment={attachment} />`.

### Configuration / déploiement

- [ ] `.env.example` : ajouter une section stockage avec `STORAGE_PROVIDER=local` et les variables `STORAGE_S3_BUCKET=`, `STORAGE_S3_REGION=`, `STORAGE_S3_ACCESS_KEY=`, `STORAGE_S3_SECRET_KEY=`, `STORAGE_S3_ENDPOINT=`, `STORAGE_S3_PATH_STYLE_ACCESS=false`, `STORAGE_SIGNED_URL_TTL_SECONDS=300`, avec un commentaire précisant qu'elles sont optionnelles tant que `STORAGE_PROVIDER=local`.

- [ ] `.env.production.example` : même section, avec un commentaire rappelant qu'il n'existe **aucune migration automatique** des fichiers déjà stockés localement en cas de bascule vers `s3` (cf. risques du design).

- [ ] `docker-compose.yml` : ajouter `STORAGE_PROVIDER: ${STORAGE_PROVIDER:-local}` et les `STORAGE_S3_*` (défauts vides / `false` / `300`) au bloc `environment:` du service `backend`.

## Contrat technique

**Interface** (`com.creditflow.common.storage`) :
```java
public interface DocumentStorage {
    String store(MultipartFile file, String folder);
    void delete(String key);
    DocumentAccess resolve(String key);
}

public sealed interface DocumentAccess permits DocumentAccess.Inline, DocumentAccess.Redirect {
    record Inline(byte[] content, String contentType) implements DocumentAccess {}
    record Redirect(String url) implements DocumentAccess {}
}
```

**Configuration** (`app.storage`, préfixe env `STORAGE_*`) :

| Propriété | Env var | Défaut | Obligatoire si |
|---|---|---|---|
| `app.storage.provider` | `STORAGE_PROVIDER` | `local` | — |
| `app.storage.s3.bucket` | `STORAGE_S3_BUCKET` | (vide) | `provider=s3` |
| `app.storage.s3.region` | `STORAGE_S3_REGION` | (vide) | `provider=s3` |
| `app.storage.s3.access-key` | `STORAGE_S3_ACCESS_KEY` | (vide) | `provider=s3` |
| `app.storage.s3.secret-key` | `STORAGE_S3_SECRET_KEY` | (vide) | `provider=s3` |
| `app.storage.s3.endpoint` | `STORAGE_S3_ENDPOINT` | (vide) | non (MinIO) |
| `app.storage.s3.path-style-access` | `STORAGE_S3_PATH_STYLE_ACCESS` | `false` | non |
| `app.storage.s3.signed-url-ttl-seconds` | `STORAGE_SIGNED_URL_TTL_SECONDS` | `300` | non |

**Nouveaux endpoints** (authentifiés, scopés boutique via `getEntity`/`assertAccessible`) :
- `GET /api/customers/{id}/photo` → `200` (octets + `Content-Type`, local) ou `302` (`Location`, S3) ou `404` si pas de photo / client inaccessible.
- `GET /api/sales/{id}/attachments/{attachmentId}/file` → même contrat, `404` si pièce jointe/contrat inaccessible.

**Changement de contrat DTO** (non rétrocompatible, cf. Risques du design) :
- `CustomerResponse.photoUrl` : `/api/customers/{id}/photo` si photo présente, `null` sinon (au lieu de la clé de stockage brute).
- `SaleAttachmentResponse.fileUrl` : `/api/sales/{saleId}/attachments/{id}/file` (au lieu de la clé de stockage brute).

**Frontend** : `toAuthenticatedFetchPath` retire le préfixe `/api` de ces URLs avant de les passer à l'instance axios `api` (dont le `baseURL` vaut déjà `/api` par défaut), pour éviter un double préfixage `/api/api/...`.

**Base de données** : aucune migration Flyway — `customers.photo_url` et `sale_attachments.file_url` restent `VARCHAR(255)`, suffisant pour une clé S3 (`folder/uuid.ext`) comme pour un chemin local.

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Changer `STORAGE_PROVIDER` (et variables associées) change le fournisseur sans modification de code | `DocumentStorageWiringTest` (bean actif selon `app.storage.provider`) + `StorageConfigValidatorTest` (démarrage refusé si `s3` mal configuré) + **vérification manuelle** : démarrer avec `STORAGE_PROVIDER=s3` + credentials réels (S3 ou compatible), uploader/consulter un document, sans recompiler ni modifier de code (pas d'infra MinIO dans `docker-compose.yml`, hors périmètre — vérification à faire contre un bucket S3 réel ou un service S3-compatible externe) |
| Une instance qui ne configure rien continue de fonctionner en stockage local, à l'identique de l'existant | `LocalDiskStorageTest` (comportement `store`/`delete`/`resolve` migré de `FileStorageServiceTest`) + `DocumentStorageWiringTest` (cas par défaut → `LocalDiskStorage` seul) + **vérification manuelle** : sur une instance sans `STORAGE_PROVIDER` défini, upload photo client / pièce jointe puis affichage, comme avant le ticket |
| Un fichier uploadé n'est plus accessible sans authentification, quel que soit le fournisseur | `CustomerControllerSecurityTest`/`SaleControllerSecurityTest` (401 sans token sur les nouveaux endpoints) + suppression de `/uploads/**` de `PUBLIC_ENDPOINTS` (`SecurityConfig.java`) et du `ResourceHandler` (`WebConfig.java`) + **vérification manuelle** : `curl` sans en-tête `Authorization` sur une ancienne URL `/uploads/...` (404, plus de résolution statique) et sur `/api/customers/{id}/photo` (401) |
| La logique métier ne connaît pas le fournisseur concret, un seul point d'entrée | `DocumentStorageWiringTest` (jamais deux beans `DocumentStorage` actifs simultanément) + relecture de `CustomerService`/`CreditSaleService` : aucune référence à `LocalDiskStorage`/`S3DocumentStorage`, uniquement à `DocumentStorage` |
| (implicite, régression) affichage des photos/pièces jointes après le changement de contrat DTO | `apiFileUrl.test.ts` (transformation d'URL correcte) + **vérification manuelle** : `CustomerDetailPage`, `CustomersPage`, `SaleDetailPage` affichent bien les images après connexion (pas de composant `@testing-library/react` dans le projet actuellement → pas de test automatisé de rendu pour `useAuthenticatedFile`/`CustomerAvatar`/`AttachmentThumbnail`, vérification manuelle uniquement) |
| Contenu servi conforme (200 inline / 302 redirection) | `DocumentAccessResponsesTest` (conversion `DocumentAccess` → `ResponseEntity`) + tests de contrôleur ajoutés (`Inline` → 200, `Redirect` → 302) |
| Suppression d'un document (photo/pièce jointe) reste fonctionnelle après renommage `FileStorageService` → `DocumentStorage` | tests existants `CustomerServiceTest#deletesCustomerAndRecordsAuditEntry` et équivalents `CreditSaleServiceTest` (mocks adaptés au type `DocumentStorage`) |

## Écarts identifiés

- Le design prévoit un test « bascule de fournisseur sans modification de code » comme critère d'acceptation explicite, mais aucun service S3-compatible (MinIO) n'est disponible dans `docker-compose.yml` (explicitement hors périmètre). La vérification manuelle de ce critère nécessite donc soit un bucket S3 réel, soit l'ajout ponctuel (hors ce ticket, ou en tâche de review manuelle) d'un service S3-compatible local — à trancher avant la revue finale : accepter une vérification manuelle contre un compte AWS de test, ou repousser cette vérification complète à un ticket ultérieur qui ajouterait MinIO à `docker-compose.yml`.
- Le design ne précise pas explicitement comment le frontend évite le double-préfixage `/api` entre le `baseURL` d'axios et l'URL absolue retournée par les DTO (`/api/customers/{id}/photo`) : la présente spec tranche ce point via `apiFileUrl.ts`/`toAuthenticatedFetchPath`, à valider par le codeur si `VITE_API_URL` est un jour configuré sans le suffixe `/api`.
- Le design demande de « factoriser » l'affichage authentifié dans un hook partagé mais ne mentionne pas la contrainte des règles des hooks React (un hook ne peut pas être appelé dans le callback d'un `.map()`) : la présente spec introduit deux petits composants (`CustomerAvatar`, `AttachmentThumbnail`) non explicitement listés dans le design pour respecter cette contrainte sur `CustomersPage.tsx` et `SaleDetailPage.tsx` — changement mineur d'implémentation, cohérent avec l'intention du design, pas un écart de fond.
