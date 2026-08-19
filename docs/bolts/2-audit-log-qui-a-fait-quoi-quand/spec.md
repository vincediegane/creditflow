# Spec — Journal d'audit : qui a fait quoi, quand (#2)

## Résumé

Ajouter `created_by`/`updated_by` sur les entités auditables et sur `Payment`, plus une table `audit_log` append-only alimentée explicitement aux points de suppression/annulation/modification de prix (client, contrat, produit, **et paiement**), avec exposition API et affichage dans les fiches contrat/client.

## Tâches

### Backend — colonnes créateur/modificateur

- [ ] `backend/src/main/java/com/creditflow/common/domain/Auditable.java` — ajouter `createdBy` (`@Column(name = "created_by", length = 80)`) et `updatedBy` (`@Column(name = "updated_by", length = 80)`), renseignés dans `onCreate()`/`onUpdate()` via un helper privé null-safe `currentUsername()` qui lit `SecurityContextHolder.getContext().getAuthentication()` et retourne `null` si l'authentification est absente ou non authentifiée (jamais d'exception).
- [ ] `backend/src/main/java/com/creditflow/payment/domain/Payment.java` — ajouter `createdBy` (`@Column(name = "created_by", length = 80)`), renseigné dans le `onCreate()` existant via le même helper (dupliqué ou factorisé dans une petite classe utilitaire partagée, ex. `com.creditflow.common.security.CurrentUser` — au choix du codeur, mais pas de duplication de logique SecurityContextHolder à plus de 2 endroits).
- [ ] `backend/src/main/resources/db/migration/V3__audit_columns.sql` (nouveau) :
  ```sql
  ALTER TABLE customers ADD COLUMN created_by VARCHAR(80);
  ALTER TABLE customers ADD COLUMN updated_by VARCHAR(80);
  ALTER TABLE products ADD COLUMN created_by VARCHAR(80);
  ALTER TABLE products ADD COLUMN updated_by VARCHAR(80);
  ALTER TABLE credit_sales ADD COLUMN created_by VARCHAR(80);
  ALTER TABLE credit_sales ADD COLUMN updated_by VARCHAR(80);
  ALTER TABLE payments ADD COLUMN created_by VARCHAR(80);

  CREATE TABLE audit_log (
      id          BIGSERIAL PRIMARY KEY,
      entity_type VARCHAR(30)  NOT NULL,
      entity_id   BIGINT       NOT NULL,
      entity_label VARCHAR(255) NOT NULL,
      action      VARCHAR(30)  NOT NULL,
      details     TEXT,
      actor       VARCHAR(80),
      created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
  );

  CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
  ```
  Toutes les colonnes sont nullable, aucun backfill (cohérent avec la décision d'architecture).

### Backend — table `audit_log`

- [ ] `backend/src/main/java/com/creditflow/audit/domain/AuditLog.java` (nouveau) — `@Entity @Table(name = "audit_log")` : `id`, `entityType`, `entityId`, `entityLabel`, `action`, `details`, `actor`, `createdAt` (`@PrePersist` local, même style que `Payment.onCreate()` — pas d'extension d'`Auditable`, pas d'`updatedAt` sur une table append-only).
- [ ] `backend/src/main/java/com/creditflow/audit/repository/AuditLogRepository.java` (nouveau) — `List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId)`.
- [ ] `backend/src/main/java/com/creditflow/audit/dto/AuditLogResponse.java` (nouveau) — record `(Long id, String entityType, Long entityId, String entityLabel, String action, String details, String actor, LocalDateTime createdAt)`.
- [ ] `backend/src/main/java/com/creditflow/audit/service/AuditLogService.java` (nouveau) :
  - `void record(String entityType, Long entityId, String entityLabel, String action, String details)` — construit et sauvegarde une `AuditLog`, `actor` lu via `SecurityContextHolder` (null-safe, même helper que `Auditable`). Transactionnel par défaut (rejoint la transaction appelante, pas de `readOnly`).
  - `List<AuditLogResponse> list(String entityType, Long entityId)` — `@Transactional(readOnly = true)`, mapping manuel `AuditLog -> AuditLogResponse` (pas de nouveau mapper MapStruct pour une conversion aussi simple, cohérent avec le style `SaleMapper` en `@Component` manuel).
- [ ] `backend/src/main/java/com/creditflow/audit/web/AuditLogController.java` (nouveau) — `GET /api/audit-log?entityType=...&entityId=...` (deux `@RequestParam` obligatoires, 400 automatique si absents), pas de `@PreAuthorize` (couvert par `anyRequest().authenticated()` dans `SecurityConfig` — tout rôle authentifié).

### Backend — points d'écriture de l'audit log

- [ ] `backend/src/main/java/com/creditflow/customer/service/CustomerService.java#delete` — injecter `AuditLogService`, appeler `auditLogService.record("CUSTOMER", id, customer.getFullName(), "DELETE", null)` **avant** `customerRepository.delete(customer)`, dans la même transaction (déjà `@Transactional`).
- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java#cancel` — injecter `AuditLogService`, appeler `auditLogService.record("CREDIT_SALE", id, sale.getReference(), "CANCEL", null)` après le passage à `SaleStatus.CANCELLED`, avant/au moment du `save`.
- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java#delete` — appeler `auditLogService.record("CREDIT_SALE", id, sale.getReference(), "DELETE", null)` avant `saleRepository.delete(sale)`.
- [ ] `backend/src/main/java/com/creditflow/product/service/ProductService.java#update` — capturer `oldCashPrice`/`oldCreditPrice` **avant** `productMapper.updateEntity(...)` et les `setCashPrice`/`setCreditPrice`. Après application des nouvelles valeurs, comparer avec `compareTo` (pas `equals`, à cause de l'échelle `BigDecimal`) ; si l'une des deux a changé, appeler `auditLogService.record("PRODUCT", id, product.getName(), "PRICE_UPDATE", details)` avec `details` ne listant que les prix effectivement modifiés (ex. `"Prix credit: 120000 -> 130000"`). Aucun appel si aucun prix ne change.
- [ ] `backend/src/main/java/com/creditflow/payment/service/PaymentService.java#delete` — **(décision spec-writer, voir Écarts identifiés)** injecter `AuditLogService`, appeler `auditLogService.record("CREDIT_SALE", sale.getId(), sale.getReference(), "PAYMENT_DELETE", details)` **avant** `paymentRepository.delete(payment)`, avec `details` récapitulant le versement supprimé (montant, date, méthode) puisque la ligne `payments` elle-même va disparaître. Utiliser `entityType = "CREDIT_SALE"` (pas `"PAYMENT"`) pour que l'entrée apparaisse dans la section « Historique » déjà prévue sur `SaleDetailPage` sans écran dédié supplémentaire.

### Backend — exposition des colonnes dans les réponses API

- [ ] `backend/src/main/java/com/creditflow/payment/dto/PaymentResponse.java` — ajouter le champ `createdBy` (String). Le mapping MapStruct (`PaymentMapper.java`) auto-résout par correspondance de nom (`Payment.createdBy` → `PaymentResponse.createdBy`), aucune annotation `@Mapping` supplémentaire attendue — vérifier après génération.
- [ ] `backend/src/main/java/com/creditflow/customer/dto/CustomerResponse.java` — ajouter `createdBy`/`updatedBy` (String). Idem, mapping MapStruct automatique via `CustomerMapper.java`.
- [ ] `backend/src/main/java/com/creditflow/product/dto/ProductResponse.java` — ajouter `createdBy`/`updatedBy` (String). Idem via `ProductMapper.java`.
- [ ] `backend/src/main/java/com/creditflow/sale/dto/SaleResponse.java` — ajouter `createdBy`/`updatedBy` (String).
- [ ] `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java#toResponse(CreditSale, List<Installment>, LocalDate)` — `SaleMapper` est un `@Component` manuel (pas MapStruct) : ajouter explicitement `sale.getCreatedBy()` et `sale.getUpdatedBy()` aux arguments positionnels du `new SaleResponse(...)`.

### Backend — tests

- [ ] `backend/src/test/java/com/creditflow/common/domain/AuditableTest.java` (nouveau) — instancie une sous-classe concrète existante (ex. `Customer`), mocke `SecurityContextHolder` (via `SecurityContextHolder.setContext(...)` avec un `Authentication` stub, puis `clearContext()` en `@AfterEach`), appelle directement `onCreate()`/`onUpdate()` (package-private, accessible depuis le même package en test), vérifie que `createdBy`/`updatedBy` sont renseignés avec le nom de l'utilisateur courant, et qu'ils restent `null` sans authentification (pas d'exception levée).
- [ ] `backend/src/test/java/com/creditflow/payment/domain/PaymentTest.java` (nouveau) — même principe que ci-dessus, appliqué à `Payment.onCreate()` : `createdBy` renseigné depuis `SecurityContextHolder`, `null` si absent.
- [ ] `backend/src/test/java/com/creditflow/audit/service/AuditLogServiceTest.java` (nouveau) — `record()` sauvegarde une `AuditLog` avec les bons champs et un `actor` issu du contexte de sécurité mocké (et `null` si absent, sans exception) ; `list()` retourne les entrées triées et mappées correctement (mock `AuditLogRepository`).
- [ ] `backend/src/test/java/com/creditflow/audit/web/AuditLogControllerSecurityTest.java` (nouveau, `@WebMvcTest` + `AbstractWebMvcSecurityTest`, suivant le modèle de `PaymentControllerSecurityTest`/`SaleControllerSecurityTest`) — `GET /api/audit-log?entityType=CUSTOMER&entityId=1` retourne 200 pour `@WithMockUser(roles = "SELLER")` **et** pour `roles = "ADMIN"` (tout rôle authentifié) ; retourne 400 si `entityType` ou `entityId` manquant.
- [ ] `backend/src/test/java/com/creditflow/customer/service/CustomerServiceTest.java` — ajouter un test `deletesCustomerAndRecordsAuditEntry` : mock `AuditLogService`, vérifie que `delete(id)` appelle `auditLogService.record("CUSTOMER", id, customer.getFullName(), "DELETE", null)` puis `customerRepository.delete(customer)` (ordre non critique à tester strictement, mais les deux appels doivent avoir lieu).
- [ ] `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` (nouveau, n'existe pas encore — créer un fichier minimal suivant le style Mockito de `CustomerServiceTest`/`PaymentServiceTest`, sans chercher à couvrir l'intégralité du service) — deux tests : `cancel()` appelle `auditLogService.record("CREDIT_SALE", id, reference, "CANCEL", null)` ; `delete()` (sans paiement associé) appelle `auditLogService.record("CREDIT_SALE", id, reference, "DELETE", null)`.
- [ ] `backend/src/test/java/com/creditflow/product/service/ProductServiceTest.java` (nouveau, n'existe pas encore — même remarque de périmètre minimal) — deux tests : `update()` avec `cashPrice`/`creditPrice` modifiés appelle `auditLogService.record("PRODUCT", id, name, "PRICE_UPDATE", <details non vide>)` ; `update()` sans changement de prix (mêmes valeurs) n'appelle **jamais** `auditLogService.record(...)`.
- [ ] `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java` — ajouter un test `deletingPaymentRecordsAuditEntryOnSale` : mock `AuditLogService`, vérifie que `delete(id)` appelle `auditLogService.record("CREDIT_SALE", sale.getId(), sale.getReference(), "PAYMENT_DELETE", <details non null>)` avant `paymentRepository.delete(payment)`.

### Frontend

- [ ] `frontend/src/types.ts` — ajouter `createdBy?: string` sur `Payment` ; `createdBy?: string` et `updatedBy?: string` sur `Customer`, `Product`, `Sale` ; nouveau type :
  ```ts
  export interface AuditLogEntry {
    id: number;
    entityType: string;
    entityId: number;
    entityLabel: string;
    action: string;
    details?: string;
    actor?: string;
    createdAt: string;
  }
  ```
- [ ] `frontend/src/api/endpoints.ts` — ajouter :
  ```ts
  export const auditLogApi = {
    list: (entityType: string, entityId: number) =>
      api.get<AuditLogEntry[]>('/audit-log', { params: { entityType, entityId } }).then((r) => r.data),
  };
  ```
- [ ] `frontend/src/pages/SaleDetailPage.tsx` :
  - Ajouter une requête `useQuery({ queryKey: ['audit-log', 'CREDIT_SALE', saleId], queryFn: () => auditLogApi.list('CREDIT_SALE', saleId), enabled: Number.isFinite(saleId) })`.
  - Ajouter une `Card` « Historique » (sous la carte « Versements ») listant les entrées (`Date` = `createdAt`, `Action` traduite en français — `CANCEL` → « Annulation du contrat », `DELETE` → « Suppression du contrat », `PAYMENT_DELETE` → « Annulation d'un versement », `Auteur` = `actor ?? '—'`, `Détails` = `details ?? '—'`).
  - Ajouter une colonne « Enregistré par » dans le tableau « Versements » (`payment.createdBy ?? '—'`).
- [ ] `frontend/src/pages/CustomerDetailPage.tsx` — même principe : requête `auditLogApi.list('CUSTOMER', customerId)`, carte « Historique ». **Note** : voir Écarts identifiés — cette section n'affichera en pratique jamais rien tant que le client existe encore (voir ci-dessous), à ne pas considérer comme un bug d'implémentation.
- [ ] `frontend/src/pages/PaymentsPage.tsx` — ajouter une colonne « Enregistré par » dans le tableau (`payment.createdBy ?? '—'`), positionnée par exemple entre « Référence » et « Montant ».

## Contrat technique

**Colonnes ajoutées** (toutes nullable, `VARCHAR(80)`, pas de FK vers `users.id`) :
- `customers.created_by`, `customers.updated_by`
- `products.created_by`, `products.updated_by`
- `credit_sales.created_by`, `credit_sales.updated_by`
- `payments.created_by`

**Table `audit_log`** :
| colonne | type | nullable |
|---|---|---|
| id | BIGSERIAL PK | non |
| entity_type | VARCHAR(30) | non — valeurs utilisées : `CUSTOMER`, `CREDIT_SALE`, `PRODUCT` |
| entity_id | BIGINT | non |
| entity_label | VARCHAR(255) | non |
| action | VARCHAR(30) | non — valeurs utilisées : `DELETE`, `CANCEL`, `PRICE_UPDATE`, `PAYMENT_DELETE` |
| details | TEXT | oui |
| actor | VARCHAR(80) | oui (null si contexte non authentifié) |
| created_at | TIMESTAMP | non, `DEFAULT NOW()` |

Index : `(entity_type, entity_id)`.

**Endpoint** : `GET /api/audit-log?entityType={CUSTOMER|CREDIT_SALE|PRODUCT}&entityId={id}` → `AuditLogEntry[]`, tri `createdAt DESC`, accessible à tout utilisateur authentifié (ADMIN ou SELLER), 400 si un des deux paramètres est absent.

**`AuditLogService.record(String entityType, Long entityId, String entityLabel, String action, String details)`** — méthode interne (pas de contrôleur d'écriture direct), appelée uniquement depuis les services métier listés ci-dessus, toujours dans la transaction appelante.

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Chaque paiement enregistré porte l'identifiant du vendeur qui l'a saisi, visible sur le reçu interne et la fiche contrat. | Unitaire : `PaymentTest#onCreate` (createdBy renseigné depuis `SecurityContextHolder`). Manuel : se connecter en tant que vendeur, enregistrer un versement, vérifier la colonne « Enregistré par » sur `SaleDetailPage` (fiche contrat) et sur `PaymentsPage`. **Interprétation retenue** : « reçu interne » = les vues internes (fiche contrat, liste des paiements), par opposition au PDF `PaymentReceiptGenerator` remis au client (qui contient déjà une ligne de signature manuscrite du vendeur et n'est volontairement pas modifié — voir Écarts identifiés). |
| La suppression d'un client, l'annulation d'un contrat et la modification d'un prix produit sont historisées avec auteur + date. | Unitaire : `CustomerServiceTest#deletesCustomerAndRecordsAuditEntry`, `CreditSaleServiceTest#cancel...`/`#delete...`, `ProductServiceTest#update...` (avec et sans changement de prix), `AuditLogServiceTest#record...`. Intégration légère : `AuditLogControllerSecurityTest` (accès à `GET /api/audit-log`). Manuel : annuler un contrat, modifier le prix d'un produit, consulter `GET /api/audit-log?entityType=...&entityId=...` (et la carte « Historique » de `SaleDetailPage` pour l'annulation de contrat) et vérifier auteur + date. |
| La désactivation d'un compte utilisateur ne fait disparaître aucune entrée d'historique existante. | Garantie structurelle : `created_by`/`updated_by`/`audit_log.actor` sont des `VARCHAR` sans FK vers `users.id` (migration `V3`), donc aucune suppression en cascade n'est possible. Test existant `UserServiceTest#disablesAnotherUsersAccount` couvre déjà `verify(userRepository, never()).deleteById(any())`. Manuel (non automatisable sans infra de test d'intégration DB, absente du projet) : enregistrer un paiement avec l'utilisateur X, désactiver le compte X, vérifier que `payment.createdBy` et les entrées `audit_log.actor = "X"` restent inchangés dans les réponses API. |

## Écarts identifiés

1. **`PaymentService.delete()` — décision de journalisation (point ouvert signalé par l'architecte)** : je décide d'**inclure** cette action dans `audit_log`, malgré le fait que le ticket ne la cite pas explicitement parmi les 4 actions sensibles listées dans les critères d'acceptation. Justification : `PaymentService.delete()` effectue un DELETE physique exactement du même type que les trois autres actions couvertes (`CustomerService.delete`, `CreditSaleService.cancel/delete`, `ProductService.update`) ; sans journalisation, la suppression d'un versement — un scénario de litige client tout aussi probable que les autres ("j'ai payé, vous dites que non") — ne laisserait strictement aucune trace, ce qui contredit directement l'intention de la user story ("pouvoir enquêter en cas d'erreur ou de litige"). L'action est déjà `ADMIN`-only au niveau contrôleur (`PaymentController#delete`), cohérente avec les autres actions sensibles. Pour éviter d'ajouter un nouvel écran d'historique dédié aux paiements, l'entrée est journalisée sous `entityType = "CREDIT_SALE"` (et non `"PAYMENT"`) avec `entityId = sale.getId()` : elle apparaît ainsi automatiquement dans la carte « Historique » de `SaleDetailPage` déjà prévue par le design, sans travail frontend supplémentaire au-delà de la traduction du libellé d'action `PAYMENT_DELETE`.

2. **Visibilité incomplète des entrées `DELETE`** : pour `CUSTOMER` et `CREDIT_SALE`, l'action `DELETE` supprime physiquement l'entité — la fiche (`CustomerDetailPage`/`SaleDetailPage`) qui porterait la carte « Historique » devient donc inaccessible (404) juste après l'action qu'on cherche à tracer. La donnée reste bien interrogeable via `GET /api/audit-log?entityType=...&entityId=<id_connu>`, ce qui satisfait la lettre du critère d'acceptation (« historisées avec auteur + date »), mais pas totalement l'esprit de la user story (« voir » facilement). C'est cohérent avec le choix assumé par l'architecte de ne pas construire d'écran de consultation dédié (`Hors périmètre`) ; je le documente explicitement plutôt que de le laisser implicite. Ne bloque pas l'implémentation de ce ticket — à traiter dans un ticket ultérieur si le besoin d'investigation post-suppression se confirme (ex. écran « clients/contrats supprimés »).

3. **`ProductService` — pas de fiche produit détaillée** : la modification de prix est bien journalisée en base et interrogeable via l'API, mais il n'existe aujourd'hui aucune `ProductDetailPage` pour l'afficher (confirmé : seul `ProductController`/liste existent, pas de route détail). Assumé comme dans le design (`Hors périmètre`), documenté ici pour éviter toute ambiguïté lors de la revue.

4. **« Reçu interne » vs reçu PDF client** : le seul document PDF existant (`PaymentReceiptGenerator`) est explicitement un reçu remis au client, avec une ligne de signature manuscrite du vendeur, et n'est pas modifié par cette spec (non mentionné dans le design). Le critère d'acceptation sur le « reçu interne » est donc interprété comme couvert par les vues internes de l'application (fiche contrat, liste des paiements) plutôt que par ce PDF — à confirmer avec le demandeur si un besoin explicite de PDF interne distinct existe, mais rien dans le ticket ne le suggère.
