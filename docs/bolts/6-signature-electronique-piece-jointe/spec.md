# Spec — #6 Signature électronique / pièce jointe au contrat

## Résumé

Ajout d'une table `sale_attachments` (1-N sur `credit_sales`) avec deux nouveaux endpoints d'upload/suppression multipart, d'une vérification de contenu binaire (magic bytes) dans `FileStorageService`, et d'une carte "Pièces jointes" sur la fiche contrat permettant d'attacher une pièce d'identité scannée et une signature capturée sur canvas.

## Tâches

### Backend

- [ ] `backend/src/main/resources/db/migration/V6__sale_attachments.sql` : créer la table `sale_attachments` (voir Contrat technique), avec FK `sale_id` vers `credit_sales(id)` en `ON DELETE CASCADE` (filet de sécurité DB, en complément — pas en remplacement — de la purge applicative des fichiers physiques) et un index sur `sale_id`.
- [ ] `backend/src/main/java/com/creditflow/sale/domain/SaleAttachmentType.java` (nouveau) : enum `ID_DOCUMENT`, `SIGNATURE`, `OTHER`.
- [ ] `backend/src/main/java/com/creditflow/sale/domain/SaleAttachment.java` (nouveau) : entité `@Entity @Table(name = "sale_attachments")`, champs `id`, `sale` (`@ManyToOne(fetch = LAZY, optional = false)`), `type` (`@Enumerated(STRING)`), `fileUrl`, `originalFilename`, `contentType`, `createdAt`/`createdBy` avec un `@PrePersist` manuel (même schéma que `Payment.java` — pas de colonne `updated_*`, ces lignes ne sont jamais modifiées, seulement créées/supprimées).
- [ ] `backend/src/main/java/com/creditflow/sale/domain/CreditSale.java` : ajouter `@OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY) private List<SaleAttachment> attachments` (symétrique du champ `installments` existant). Pas de méthode `addAttachment()` : la persistance passe par `SaleAttachmentRepository` dans le service, pas par la collection en mémoire.
- [ ] `backend/src/main/java/com/creditflow/sale/repository/SaleAttachmentRepository.java` (nouveau) : `List<SaleAttachment> findBySaleIdOrderByCreatedAtAsc(Long saleId)`, `List<SaleAttachment> findBySaleIdAndType(Long saleId, SaleAttachmentType type)`, `Optional<SaleAttachment> findByIdAndSaleId(Long id, Long saleId)`.
- [ ] `backend/src/main/java/com/creditflow/sale/dto/SaleAttachmentResponse.java` (nouveau) : record `(Long id, Long saleId, SaleAttachmentType type, String fileUrl, String originalFilename, String contentType, LocalDateTime createdAt, String createdBy)`.
- [ ] `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java` : ajouter `public SaleAttachmentResponse toResponse(SaleAttachment attachment)`.
- [ ] `backend/src/main/java/com/creditflow/common/storage/FileStorageService.java` : ajouter la vérification de contenu par magic bytes dans `store()` (voir Contrat technique), en lisant `file.getBytes()` une seule fois (déjà borné à 5 Mo par `spring.servlet.multipart.max-file-size`) et en écrivant ce même tableau d'octets sur disque via `Files.write(...)` au lieu de recopier l'`InputStream`.
- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` :
  - injecter `SaleAttachmentRepository`.
  - `uploadAttachment(Long saleId, SaleAttachmentType type, MultipartFile file)` : si `type == SIGNATURE`, supprimer (fichier + ligne) toute signature existante avant d'enregistrer la nouvelle (règle tranchée, voir Contrat technique) ; stocke via `fileStorageService.store(file, "sales/" + saleId)` ; journalise via `auditLogService.record("CREDIT_SALE", saleId, sale.getReference(), "ATTACHMENT_ADD", type.name())`.
  - `deleteAttachment(Long saleId, Long attachmentId)` : charge via `findByIdAndSaleId` (sinon `ResourceNotFoundException.of("Piece jointe", attachmentId)`), supprime le fichier physique puis la ligne, journalise `"ATTACHMENT_REMOVE"`.
  - `findDetail(Long id)` : ajouter le chargement des pièces jointes via une requête séparée (`saleAttachmentRepository.findBySaleIdOrderByCreatedAtAsc(id)`), **sans** toucher à `findDetailById` ni ajouter de `JOIN FETCH` (cf. risque de produit cartésien avec `installments` déjà identifié par l'architecte).
  - `delete(Long id)` : avant `saleRepository.delete(sale)`, itérer `saleAttachmentRepository.findBySaleIdOrderByCreatedAtAsc(id)` et appeler `fileStorageService.deleteByPublicUrl(...)` pour chaque pièce jointe (le cascade JPA/DB supprime les lignes, pas les fichiers).
- [ ] `backend/src/main/java/com/creditflow/sale/dto/SaleDetailResponse.java` : ajouter le champ `List<SaleAttachmentResponse> attachments` (record à 4 composants : `sale`, `installments`, `payments`, `attachments`).
- [ ] `backend/src/main/java/com/creditflow/sale/web/SaleController.java` :
  - `POST /api/sales/{id}/attachments` (`consumes = MULTIPART_FORM_DATA_VALUE`, `@RequestParam SaleAttachmentType type`, `@RequestPart("file") MultipartFile file`) → `SaleAttachmentResponse`, aucun `@PreAuthorize` (ADMIN + SELLER, cohérent avec `CustomerController.uploadPhoto`).
  - `DELETE /api/sales/{id}/attachments/{attachmentId}` → `204 No Content`, aucun `@PreAuthorize`.
- [ ] `backend/src/test/java/com/creditflow/common/storage/FileStorageServiceTest.java` (nouveau) : instancier `FileStorageService` avec un `AppProperties` pointant vers un `@TempDir` ; couvrir : PNG valide accepté, JPEG valide accepté, WEBP valide accepté, fichier texte renommé `.png` rejeté (`BusinessRuleException`), fichier `.jpg` avec des octets PNG rejeté, extension non supportée toujours rejetée (non-régression du comportement existant).
- [ ] `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` : ajouter des cas pour `uploadAttachment` (stocke un `ID_DOCUMENT` et journalise, empile plusieurs `ID_DOCUMENT` sans suppression, remplace une `SIGNATURE` existante en supprimant l'ancien fichier), `deleteAttachment` (supprime fichier + ligne + journalise, lève `ResourceNotFoundException` si la pièce n'appartient pas à ce contrat), et `delete(Long id)` (purge les fichiers des pièces jointes avant suppression du contrat).
- [ ] `backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java` : ajouter `sellerCanUploadAttachment` et `sellerCanDeleteAttachment` (200/204, pas de `403`), pour documenter explicitement que ces deux endpoints restent ouverts à SELLER (contrairement à `cancel`/`delete` du contrat, réservés à ADMIN).

### Frontend

- [ ] `frontend/src/types.ts` : ajouter `export type SaleAttachmentType = 'ID_DOCUMENT' | 'SIGNATURE' | 'OTHER';`, `export interface SaleAttachment { id: number; saleId: number; type: SaleAttachmentType; fileUrl: string; originalFilename?: string; contentType?: string; createdAt: string; createdBy?: string; }`, et ajouter `attachments: SaleAttachment[];` à `SaleDetail`.
- [ ] `frontend/src/api/endpoints.ts` : dans `salesApi`, ajouter `uploadAttachment: (saleId: number, type: SaleAttachmentType, file: File) => { const form = new FormData(); form.append('file', file); return api.post<SaleAttachment>(\`/sales/${saleId}/attachments\`, form, { params: { type }, headers: { 'Content-Type': 'multipart/form-data' } }).then((r) => r.data); }` et `removeAttachment: (saleId: number, attachmentId: number) => api.delete(\`/sales/${saleId}/attachments/${attachmentId}\`).then(() => undefined)`.
- [ ] `frontend/src/components/SignaturePad.tsx` (nouveau) : composant contrôlé `{ open: boolean; onCapture: (file: File) => void; onCancel: () => void }` — un `<canvas>` avec écoute `pointerdown`/`pointermove`/`pointerup` pour tracer, bouton "Effacer" (efface le canvas), bouton "Valider" (appelle `canvas.toBlob(blob => onCapture(new File([blob], \`signature-${Date.now()}.png\`, { type: 'image/png' })), 'image/png')`), pas de nouvelle dépendance npm.
- [ ] `frontend/src/pages/SaleDetailPage.tsx` : nouvelle `Card` "Pièces jointes" (sous la carte Résumé ou en pied de colonne droite) affichant `data.attachments` (vignette `<img>` pour chaque fichier, libellé du type, bouton de suppression appelant `salesApi.removeAttachment` + `refresh()`) ; bouton "Ajouter une pièce d'identité" déclenchant un `<input type="file" accept="image/*" capture="environment" hidden>` dont le `onChange` appelle `salesApi.uploadAttachment(saleId, 'ID_DOCUMENT', file)` + `refresh()` ; bouton "Faire signer le client" ouvrant `SignaturePad`, dont `onCapture` appelle `salesApi.uploadAttachment(saleId, 'SIGNATURE', file)` + `refresh()`. Gérer les erreurs avec le même `errorMessage(err, ...)` + `setError(...)` déjà utilisé sur la page.

### Note opérationnelle (hors code)

- [ ] Avant de merger, vérifier qu'aucune autre branche `bolt/*` en cours n'a déjà consommé `V6__*.sql` (collision déjà rencontrée sur `V3`, cf. commit `755a526`).

## Contrat technique

### Table `sale_attachments`

```sql
CREATE TABLE sale_attachments (
    id                BIGSERIAL PRIMARY KEY,
    sale_id           BIGINT       NOT NULL,
    type              VARCHAR(20)  NOT NULL,
    file_url          VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255),
    content_type      VARCHAR(100),
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(80),
    CONSTRAINT fk_sale_attachments_sale FOREIGN KEY (sale_id) REFERENCES credit_sales (id) ON DELETE CASCADE
);

CREATE INDEX idx_sale_attachments_sale ON sale_attachments (sale_id);
```

`type` : `ID_DOCUMENT`, `SIGNATURE`, `OTHER` (pas de `CHECK` en base, comme pour `status` sur `credit_sales` — validé côté JPA/enum uniquement).

### Endpoints

- `POST /api/sales/{id}/attachments` — multipart, `?type=ID_DOCUMENT|SIGNATURE|OTHER` + partie `file`. Réponse `200 OK` avec `SaleAttachmentResponse`. Erreurs : `422` (`BusinessRuleException` — extension non supportée ou contenu ne correspondant pas à l'extension), `404` si `id` de contrat inconnu.
- `DELETE /api/sales/{id}/attachments/{attachmentId}` — `204 No Content`. `404` si la pièce jointe n'existe pas ou n'appartient pas à ce contrat.
- `GET /api/sales/{id}/detail` (existant, inchangé côté route) — la réponse `SaleDetailResponse` gagne un 4ᵉ champ `attachments: SaleAttachmentResponse[]`.

Aucun de ces deux nouveaux endpoints n'a de `@PreAuthorize` : accessibles à `ADMIN` et `SELLER`, comme `POST /api/customers/{id}/photo`.

### Règle métier — remplacement vs accumulation (point tranché)

- **`ID_DOCUMENT`** et **`OTHER`** : accumulation. Chaque upload ajoute une nouvelle ligne (permet recto/verso de CNI, plusieurs pages). Aucune suppression automatique ; le vendeur retire une pièce erronée via `DELETE`.
- **`SIGNATURE`** : remplacement. Avant d'enregistrer une nouvelle signature, `CreditSaleService.uploadAttachment` recherche les pièces jointes existantes de type `SIGNATURE` pour ce contrat (`findBySaleIdAndType`) et les supprime (fichier physique + ligne) avant de persister la nouvelle. Un contrat n'a donc jamais plus d'une signature active à la fois. Justification : contrairement à la pièce d'identité (qui peut légitimement avoir plusieurs faces/pages), une signature de contrat doit être univoque — accumuler plusieurs signatures introduirait une ambiguïté sur laquelle fait foi en cas de litige, ce qui est contraire à l'objectif même du ticket (« disposer d'une preuve »). Ce choix suit aussi le pattern déjà présent dans le code (`CustomerService.uploadPhoto` remplace la photo précédente).

### Vérification de contenu (magic bytes) dans `FileStorageService.store()`

Ajoutée après le contrôle d'extension existant, avant l'écriture sur disque :

- `jpg` / `jpeg` : les 3 premiers octets valent `FF D8 FF`.
- `png` : les 4 premiers octets valent `89 50 4E 47`.
- `webp` : octets `0-3` = `52 49 46 46` (`RIFF`) **et** octets `8-11` = `57 45 42 50` (`WEBP`).

Toute non-correspondance lève `BusinessRuleException("Le contenu du fichier ne correspond pas a son extension declaree")` — même type d'exception que le rejet d'extension existant, donc même code HTTP (`422`) et même comportement front (pas de nouveau cas à gérer côté UI). Cette vérification s'applique à **tous** les appelants de `store()`, y compris `CustomerService.uploadPhoto` (non-régression à couvrir par un test).

### Dossier de stockage

`fileStorageService.store(file, "sales/" + saleId)` → URL publique `"{publicPath}/sales/{saleId}/{uuid}.{ext}"`, cohérent avec le dossier `"customers"` déjà utilisé pour les photos clients.

## Plan de tests

| Critère d'acceptation (ticket #6) | Test |
|---|---|
| Un vendeur peut scanner/photographier une pièce d'identité et l'attacher au contrat avant validation. | Backend intégration : `SaleControllerSecurityTest.sellerCanUploadAttachment` (POST `/api/sales/{id}/attachments?type=ID_DOCUMENT`). Backend unitaire : `CreditSaleServiceTest` — `uploadAttachment` stocke un `ID_DOCUMENT` et permet l'accumulation de plusieurs pièces. Manuel : depuis la fiche contrat, bouton "Ajouter une pièce d'identité" (capture caméra sur mobile via `capture="environment"`), vérifier l'apparition de la vignette et sa persistance après rechargement de la page. Voir aussi Écarts identifiés (interprétation de "avant validation"). |
| Un client peut signer sur tablette au moment de la vente, l'image de signature étant conservée avec le contrat. | Backend unitaire : `CreditSaleServiceTest` — `uploadAttachment` de type `SIGNATURE` stocke l'image et la restitue dans `findDetail().attachments()`. Manuel (obligatoire, pas de tests automatisés frontend dans ce projet) : sur un appareil tactile (tablette ou émulation tactile navigateur), ouvrir "Faire signer le client", tracer une signature au doigt/stylet, valider, vérifier qu'elle apparaît dans "Pièces jointes" et reste visible après rechargement. |
| Un fichier dont le contenu ne correspond pas à son extension déclarée est rejeté (pas seulement filtré par extension). | Backend unitaire (nouveau) : `FileStorageServiceTest` — fichier texte renommé `.png` rejeté, fichier `.jpg` contenant des octets PNG rejeté, fichier réellement conforme à son extension (`jpg`/`png`/`webp`) accepté pour chaque type. Backend unitaire — non-régression : upload photo client (`CustomerServiceTest`) toujours fonctionnel avec une image valide. Manuel : tenter d'uploader un `.txt` renommé en `.jpg` depuis l'écran contrat, vérifier le message d'erreur `422` affiché côté UI. |
| (Risque signalé par l'architecte) Dépassement de `multipart.max-file-size` (5 Mo). | Manuel uniquement (pas de correctif de `GlobalExceptionHandler` dans ce ticket, cf. Écarts) : tenter d'uploader un fichier > 5 Mo, constater le comportement actuel (erreur générique) et confirmer qu'il n'empêche pas l'usage normal (photo de CNI ou signature PNG restent largement sous la limite). |
| Suppression d'un contrat avec pièces jointes ne laisse pas de fichiers orphelins. | Backend unitaire : `CreditSaleServiceTest.delete` — vérifie que `fileStorageService.deleteByPublicUrl` est appelé pour chaque pièce jointe avant `saleRepository.delete`. |

## Écarts identifiés

1. **Timing "avant validation" vs flux post-création choisi par le design.** Le critère d'acceptation dit qu'un vendeur doit pouvoir attacher une pièce d'identité "avant validation" du contrat. Le design choisit délibérément un flux en deux temps : `POST /api/sales` (JSON, sans fichier) puis upload de la pièce jointe une fois sur la fiche contrat créée — argumenté par l'absence d'état "brouillon" dans le modèle actuel et par le fait que le flux front navigue déjà vers la fiche contrat après création. Techniquement, la pièce d'identité est donc attachée **après** la création en base du contrat, pas avant. Cette interprétation est raisonnable (le contrat n'a pas encore encaissé de paiement à ce stade) mais diverge d'une lecture littérale du critère. À trancher avant de coder : soit on valide cette interprétation (attacher juste après création, avant tout encaissement), soit on demande un vrai flux pré-création (ce qui impliquerait de transformer `CreateSaleRequest` en payload multipart ou d'introduire un état `DRAFT`, hors périmètre actuel et non demandé explicitement par la user story).
2. **Gestion d'erreur du dépassement de taille (5 Mo).** `MaxUploadSizeExceededException` n'est pas interceptée explicitement par `GlobalExceptionHandler` (confirmé à la lecture du fichier) : elle tombe dans le handler générique `Exception.class` → réponse `500` avec un message générique ("Une erreur interne est survenue") au lieu d'un message métier clair. Ce n'est pas un blocage pour ce ticket (5 Mo est largement suffisant pour une photo de CNI ou une signature PNG), mais ce n'est pas non plus corrigé ici — traité comme test manuel uniquement, conformément à la note de risque de l'architecte. À signaler si le produit souhaite un message plus explicite dans un futur ticket.
