# Review — #6 Signature électronique / pièce jointe au contrat

## Verdict

**APPROVE**

L'implémentation suit fidèlement `spec.md`, la migration `V6` ne collisionne avec aucune autre migration (vérifié en direct contre PostgreSQL, voir section Build/tests), la protection IDOR sur `deleteAttachment` est correcte, la vérification magic bytes est bien appliquée à tous les appelants de `FileStorageService.store()` (donc aussi à l'upload photo client existant, sans y toucher), et la purge des fichiers physiques à la suppression d'un contrat est bien en place. Build et tests (backend + frontend) passent réellement. Deux observations mineures et non bloquantes sont listées ci-dessous à titre de suivi.

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Un vendeur peut scanner/photographier une piece d'identite et l'attacher au contrat avant validation | Couvert |
| 2 | Un client peut signer sur tablette au moment de la vente, l'image de signature etant conservee avec le contrat | Couvert |
| 3 | Un fichier dont le contenu ne correspond pas a son extension declaree est rejete (pas seulement filtre par extension) | Couvert |

Détail :

1. **ID_DOCUMENT** : couvert par du code ET par des tests qui échoueraient si le code était retiré — `POST /api/sales/{id}/attachments?type=ID_DOCUMENT` (`SaleController.uploadAttachment`), accumulation testée (`CreditSaleServiceTest.accumulatesIdDocumentAttachments`), accès SELLER testé (`SaleControllerSecurityTest.sellerCanUploadAttachment`). L'interprétation "après création du contrat / avant tout encaissement" du critère "avant validation" est un écart déjà tranché en amont par le design, non re-signalé ici.
2. **SIGNATURE** : couvert — `SignaturePad.tsx` (canvas natif, pointer events, aucune nouvelle dépendance npm), règle de remplacement testée (`CreditSaleServiceTest.replacesExistingSignature`), restitution via `findDetail().attachments()` (code relu manuellement, correct — voir observation mineure n°2 sur l'absence de test dédié pour cette restitution précise).
3. **Magic bytes** : couvert — vérification JPEG/PNG/WEBP dans `FileStorageService.store()`, testée positivement et négativement dans `FileStorageServiceTest`, y compris le cas `.jpg` contenant en réalité des octets PNG. S'applique structurellement aux deux seuls appelants de `store()` (`CustomerService.uploadPhoto` et `CreditSaleService.uploadAttachment`) : une régression sur ce point casserait déjà `FileStorageServiceTest`.

## Observations mineures (non bloquantes)

1. **Test de non-régression explicite manquant** — `backend/src/test/java/com/creditflow/customer/service/CustomerServiceTest.java` : le plan de tests de `spec.md` (section "Plan de tests", ligne 3) demandait explicitement un test de non-régression confirmant qu'un upload de photo client valide fonctionne toujours après l'ajout de la vérification magic bytes. Ce test n'a pas été ajouté (`CustomerServiceTest` mocke toujours `FileStorageService`, donc `uploadPhoto` n'exerce jamais le vrai contenu binaire). Risque réel faible : `FileStorageService.store()` est la seule implémentation partagée par `CustomerService.uploadPhoto` et `CreditSaleService.uploadAttachment`, et elle est déjà couverte de façon exhaustive par `FileStorageServiceTest`. À ajouter en petite dette de test si un futur ticket retouche ce chemin.
2. **Pas de test dédié `findDetail().attachments()`** — `CreditSaleServiceTest` ne contient pas de cas vérifiant explicitement que `findDetail(id)` restitue les pièces jointes après upload (mentionné dans le plan de tests de la spec pour le critère "signature"). Le code de mapping (`saleAttachmentRepository.findBySaleIdOrderByCreatedAtAsc(id)` -> `saleMapper::toResponse`) a été relu manuellement et est correct, même schéma que `installments`/`payments` dans la même méthode `findDetail`. Risque faible, code de plomberie uniquement.
3. **Divergences cosmétiques mineures vs spec sur `frontend/src/components/SignaturePad.tsx`** : props nommées `{ open, onClose, onValidate }` au lieu de `{ open, onCapture, onCancel }` mentionné dans la spec, et nom de fichier fixe `signature.png` au lieu de `signature-${Date.now()}.png`. Sans impact fonctionnel : le fichier final sur disque est de toute façon nommé par un UUID généré côté `FileStorageService`.

Aucun de ces trois points ne remet en cause la correction fonctionnelle ou la sécurité du livrable ; ils sont listés pour information/suivi, pas comme blocage.

## Points d'attention spécifiques (demandés par l'orchestrateur)

- **Collision de migration Flyway `V6`** : aucune autre migration `V6__*.sql` n'existe dans le dépôt (`ls backend/src/main/resources/db/migration` -> V1 a V6, un seul fichier V6). Le merge-base de la branche avec `master` (commit `f20c2e8`) inclut déjà le fix `755a526` (collision `V3` résolue sur une autre branche, `V5__credit_sale_interest.sql` correctement après `V4__penalty_settings.sql`). Vérifié en plus par exécution réelle contre un PostgreSQL 16 propre (voir Build/tests) : Flyway valide et applique proprement les 6 migrations, `flyway_schema_history` confirme `version 6 / sale attachments / success = t`, et `\d sale_attachments` confirme le schéma exact du contrat technique (FK `ON DELETE CASCADE`, index sur `sale_id`).
- **IDOR sur `deleteAttachment`** : `CreditSaleService.deleteAttachment(Long saleId, Long attachmentId)` charge via `saleAttachmentRepository.findByIdAndSaleId(attachmentId, saleId)`, une requête dérivée qui scope les deux colonnes — pas un simple `findById`. Une pièce jointe appartenant à un autre contrat renvoie `404 ResourceNotFoundException`, jamais de suppression croisée possible. Testé explicitement (`CreditSaleServiceTest.rejectsDeletingAttachmentFromAnotherSale` : mock `findByIdAndSaleId(3L, 1L)` renvoie `Optional.empty()` -> `ResourceNotFoundException` levée, `delete()` jamais appelé).
- **Non-régression upload photo client** : confirmé structurellement — `FileStorageService.store()` est le seul point d'entrée pour les deux appelants (`CustomerService.uploadPhoto` en dossier `"customers"`, `CreditSaleService.uploadAttachment` en dossier `"sales/{saleId}"`), donc la vérification magic bytes s'applique automatiquement aux deux sans code dupliqué. Voir Observation mineure n°1 pour la lacune de test explicite au niveau `CustomerServiceTest`.
- **Fichiers orphelins à la suppression d'un contrat** : `CreditSaleService.delete(Long id)` itère `saleAttachmentRepository.findBySaleIdOrderByCreatedAtAsc(id)` et appelle `fileStorageService.deleteByPublicUrl(...)` pour chaque pièce jointe avant `saleRepository.delete(sale)`. Les lignes DB sont purgées soit par le cascade JPA (`CreditSale.attachments` en `CascadeType.ALL` + `orphanRemoval = true`), soit par la contrainte `ON DELETE CASCADE` en base — double filet conforme à l'intention de la spec. Testé (`CreditSaleServiceTest.deletesSaleAndRecordsAuditEntry` vérifie l'appel à `deleteByPublicUrl`).

## Findings

Aucun finding bloquant. Voir "Observations mineures" ci-dessus pour le suivi non bloquant.

## Build/tests

- **Backend — `mvn test`** : `BUILD SUCCESS`, exit code 0. 165/165 tests passent, 0 échec, 0 erreur (rapports Surefire : `Total tests: 165 Failures: 0 Errors: 0 Skipped: 0`).
- **Frontend — `npx tsc --noEmit`** : exit code 0, aucune erreur de type.
- **Frontend — `npm run build`** (`tsc --noEmit && vite build`) : succès, "built in 44.37s" (avertissement standard de taille de chunk, préexistant, sans lien avec ce ticket).
- **Validation supplémentaire de la migration Flyway (au-delà du rapport du codeur)** : conteneur PostgreSQL 16 temporaire lancé via Docker, backend démarré en pointant dessus (`DB_URL` réel, hors H2/mock). Résultat :
  - `Successfully validated 6 migrations` puis `Current version of schema "public": 6`.
  - `flyway_schema_history` : les 6 migrations sont présentes et `success = t`, y compris `6 | sale attachments | t`.
  - `\d sale_attachments` confirme la table, les types de colonnes, l'index `idx_sale_attachments_sale` et la FK `fk_sale_attachments_sale ... ON DELETE CASCADE`, conformes au contrat technique de `spec.md`.
  - L'application démarre sans erreur liée à l'entité `SaleAttachment` ni à son mapping JPA.
  - Ressources de test nettoyées après vérification (conteneur Docker supprimé, processus `mvn spring-boot:run` arrêtés). Aucun fichier du dépôt n'a été modifié pendant cette validation (`git status` reste clean après coup).
