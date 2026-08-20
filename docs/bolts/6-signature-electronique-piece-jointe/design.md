# Design — #6 Signature électronique / pièce jointe au contrat

## Approche

On introduit une entité `SaleAttachment` (relation 1-N vers `CreditSale`, table `sale_attachments`, migration `V6`) pour stocker plusieurs fichiers par contrat (pièce d'identité, signature), avec un type discriminant (`ID_DOCUMENT`, `SIGNATURE`, `OTHER`). Le stockage disque reste `FileStorageService`, étendu pour accepter un dossier `sales/{id}` et pour vérifier le contenu réel du fichier (magic bytes) en plus de l'extension — cette vérification est ajoutée directement dans `FileStorageService.store()` donc elle bénéficie aussi à l'upload photo client existant, sans dupliquer de code. Côté frontend, la signature est capturée avec un canvas HTML natif (pointer events, sans nouvelle dépendance), exportée en PNG via canvas.toBlob(), puis envoyée au même endpoint d'upload que la pièce d'identité. Le prix de cette approche : pas de bibliothèque de signature tierce (lissage/pression moins beau qu'une lib dédiée type signature_pad), mais elle évite d'ajouter une dépendance npm pour un besoin simple (tracé + export image), cohérent avec le peu de dépendances déjà présentes dans frontend/package.json.

Aucun export PDF de contrat n'existe aujourd'hui (seul PaymentReceiptGenerator génère un reçu de paiement, et PdfReportExporter des rapports agrégés) : le critère "inclus dans l'export PDF du contrat si un export existe" ne s'applique donc à rien de concret et est traité comme hors périmètre (voir plus bas).

## Fichiers/modules impactés

Backend :
- backend/src/main/java/com/creditflow/common/storage/FileStorageService.java — ajout d'une vérification de signature binaire (magic bytes) en plus du contrôle d'extension, réutilisée par tous les appelants (photo client + nouvelles pièces jointes contrat).
- backend/src/main/java/com/creditflow/sale/domain/CreditSale.java — ajout d'une relation OneToMany vers une nouvelle entité SaleAttachment (mappedBy sale, cascade ALL, orphanRemoval).
- Nouveau : backend/src/main/java/com/creditflow/sale/domain/SaleAttachment.java et backend/src/main/java/com/creditflow/sale/domain/SaleAttachmentType.java (enum ID_DOCUMENT, SIGNATURE, OTHER).
- Nouveau : backend/src/main/java/com/creditflow/sale/repository/SaleAttachmentRepository.java.
- Nouveau : backend/src/main/java/com/creditflow/sale/dto/SaleAttachmentResponse.java.
- backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java — mapping SaleAttachment vers SaleAttachmentResponse.
- backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java — nouvelles méthodes uploadAttachment(saleId, type, file) et deleteAttachment(saleId, attachmentId), suivant le pattern déjà utilisé par CustomerService.uploadPhoto (store puis save, avec auditLogService.record(...)).
- backend/src/main/java/com/creditflow/sale/web/SaleController.java — endpoints POST /api/sales/{id}/attachments (multipart, paramètre type) et DELETE /api/sales/{id}/attachments/{attachmentId}.
- backend/src/main/java/com/creditflow/sale/dto/SaleDetailResponse.java — ajout du champ List de SaleAttachmentResponse nommé attachments.
- backend/src/main/java/com/creditflow/sale/repository/CreditSaleRepository.java — findDetail doit aussi charger les attachments (requête séparée, voir Risques, pour éviter un produit cartésien avec la collection installments déjà chargée en JOIN FETCH).
- Nouveau : backend/src/main/resources/db/migration/V6__sale_attachments.sql (table sale_attachments, FK vers credit_sales, colonnes type, file_url, original_filename, content_type, colonnes d'audit created_at/created_by).

Frontend :
- frontend/src/types.ts — ajout de SaleAttachment et du champ attachments sur SaleDetail.
- frontend/src/api/endpoints.ts — salesApi.uploadAttachment(saleId, type, file) et salesApi.removeAttachment(saleId, attachmentId) (même pattern FormData que customersApi.uploadPhoto).
- Nouveau : frontend/src/components/SignaturePad.tsx — capture tactile via canvas, export toBlob().
- frontend/src/pages/SaleDetailPage.tsx — nouvelle carte "Pièces jointes" (liste des fichiers + aperçu image, bouton "Ajouter une pièce d'identité" avec un input file accept image/* et capture environment, bouton "Faire signer le client" ouvrant SignaturePad).

## Décisions clés

- Table dédiée plutôt que colonnes sur credit_sales : un contrat peut avoir plusieurs pièces (recto/verso CNI + signature), donc relation 1-N (sale_attachments) plutôt que des colonnes signature_url/id_document_url qui limiteraient à un fichier par type et ne permettraient pas d'historiser un remplacement.
- Vérification de contenu par magic bytes, pas de dépendance Tika : le projet n'a aucune dépendance de détection MIME (pom.xml vérifié) et les types autorisés sont fermés (jpg, jpeg, png, webp) ; une table statique de signatures binaires (FFD8FF pour JPEG, 89504E47 pour PNG, RIFF suivi de WEBP pour WEBP) suffit et évite d'alourdir le build avec tika-core.
- Rejet de fichier = BusinessRuleException, cohérent avec le comportement existant de FileStorageService.store (mauvaise extension entraine deja la meme exception), pas de nouveau type d'erreur ni de nouveau code HTTP.
- Signature capturée en canvas natif, sans librairie tierce de type signature_pad, pour rester cohérent avec le nombre volontairement réduit de dépendances frontend actuelles.
- Pas de nouvelle restriction de rôle : comme pour la photo client (CustomerController.uploadPhoto, sans PreAuthorize), l'upload et la suppression de pièce jointe contrat restent accessibles à ADMIN et SELLER (les deux seuls rôles existants) — pas de rôle vendeur distinct côté backend pour restreindre davantage.
- Attachement après création du contrat, pas pendant : CreateSaleRequest reste un simple POST JSON (pas de multipart), la pièce jointe est envoyée juste après (le flux front navigue déjà vers la fiche contrat à la création) — même schéma que CustomerService.uploadPhoto appelé après création du client. Il n'existe pas d'état brouillon de contrat pour justifier un flux en deux temps différent.
- Export PDF du contrat traité comme hors périmètre : aucun export PDF de contrat n'existe (seul le reçu de paiement PaymentReceiptGenerator et les rapports agrégés PdfReportExporter et ExcelReportExporter). Le critère d'acceptation correspondant (si un export existe) ne s'applique donc à rien ; créer un export de contrat serait une fonctionnalité entièrement nouvelle, non demandée explicitement par la user story.

## Risques / points d'attention

- CreditSaleRepository.findDetailById charge déjà installments via JOIN FETCH ; ajouter un second JOIN FETCH sur attachments dans la même requête JPQL provoquerait un produit cartésien (et un MultipleBagFetchException si les deux collections restent des List). Il faut charger les attachments avec une requête séparée dans le service (comme paymentRepository.findBySale est déjà fait séparément dans findDetail), plutôt que de toucher au mapping existant.
- application.yml limite déjà multipart.max-file-size à 5MB — suffisant pour une photo de CNI ou une image de signature (PNG issu d'un canvas de petite taille), mais aucun comportement particulier n'existe aujourd'hui en cas de dépassement (MaxUploadSizeExceededException non géré explicitement dans GlobalExceptionHandler, à vérifier) ; à couvrir a minima par un test manuel, pas de refonte de la gestion d'erreur globale dans ce ticket.
- La vérification de contenu par magic bytes doit lire les premiers octets du fichier sans empêcher la copie sur disque ensuite — utiliser file.getBytes() (déjà borné à 5MB par la config multipart) plutôt que de consommer l'InputStream une seule fois disponible.
- Suppression du contrat dans CreditSaleService.delete : avec cascade ALL et orphanRemoval sur attachments, les lignes sale_attachments seront supprimées en base automatiquement, mais les fichiers physiques ne le seront pas sans appel explicite à fileStorageService.deleteByPublicUrl pour chaque attachment — à ajouter dans CreditSaleService.delete, sur le même modèle que CustomerService.delete.
- Pas de contrainte empêchant plusieurs signatures pour un même contrat : à trancher par le spec-writer (remplacement de la signature existante vs accumulation de plusieurs versions comme trace).

## Hors périmètre

- Génération d'un export PDF de contrat incluant les pièces jointes (n'existe pas aujourd'hui, voir Décisions clés).
- Signature cryptographique ou horodatage qualifié de type eIDAS : il s'agit d'une capture d'image de signature manuscrite, pas d'une signature électronique avancée au sens juridique.
- Passage du stockage à S3 ou MinIO (mentionné comme possibilité future dans le commentaire de FileStorageService, mais hors périmètre de ce ticket).
- Ajout d'un rôle ou d'une permission granulaire spécifique aux pièces jointes (le système ne connaît que ADMIN et SELLER).
- OCR ou extraction automatique des données de la pièce d'identité scannée.
