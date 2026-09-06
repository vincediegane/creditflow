# Spec — #41 Multi-tenant 8/10 — Isolation du stockage fichiers

## Résumé

Préfixer par organisation (`org-{organizationId}/...`) la convention de dossier utilisée par
`CustomerService.uploadPhoto()` et `CreditSaleService.uploadAttachment()` lors de l'appel à
`DocumentStorage.store()`, sans toucher au mécanisme d'autorisation existant (déjà couvert par #45 + #40).

## Tâches

- [ ] `backend/src/main/java/com/creditflow/customer/service/CustomerService.java` (ligne 136, méthode
  `uploadPhoto`) : remplacer
  `customer.setPhotoUrl(documentStorage.store(file, "customers"));`
  par
  `customer.setPhotoUrl(documentStorage.store(file, "org-" + currentShopContext.currentOrganizationId() + "/customers"));`
  Aucun import à ajouter (`currentShopContext` déjà injecté ligne 41, `currentOrganizationId()` déjà
  utilisé lignes 49/60/70).

- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` (ligne 285, méthode
  `uploadAttachment`) : remplacer
  `String fileUrl = documentStorage.store(file, "sales/" + saleId);`
  par
  `String fileUrl = documentStorage.store(file, "org-" + currentShopContext.currentOrganizationId() + "/sales/" + saleId);`
  Aucun import à ajouter (`currentShopContext` déjà injecté ligne 71).

- [ ] `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` — adapter les deux
  tests existants qui stubbent `documentStorage.store(file, "sales/1")` pour refléter le nouveau
  paramètre `folder` (la classe stub déjà `currentShopContext.currentOrganizationId()` → `100L` dans
  `searchCombinesCurrentOrganizationFilter`, ligne 493 ; réutiliser la même valeur `100L` pour la
  cohérence) :
  - `accumulatesIdDocumentAttachments` (ligne 348-364) : ajouter
    `when(currentShopContext.currentOrganizationId()).thenReturn(100L);` et remplacer
    `when(documentStorage.store(file, "sales/1"))` par
    `when(documentStorage.store(file, "org-100/sales/1"))`.
  - `replacesExistingSignature` (ligne 367-383) : même ajustement (ajouter le stub
    `currentOrganizationId()` → `100L`, remplacer `"sales/1"` par `"org-100/sales/1"` dans le stub de
    `documentStorage.store`).
  Ne pas dupliquer/modifier les tests `resolveAttachmentRejectsSaleFromAnotherShop` (ligne 430-439) ni
  aucun autre test d'accès : ils ne sont pas concernés par ce ticket (voir Plan de tests).

- [ ] `backend/src/test/java/com/creditflow/customer/service/CustomerServiceTest.java` — ajouter un
  nouveau test pour `uploadPhoto` (aucun test existant ne couvre cette méthode aujourd'hui, donc
  création et non adaptation). Le `@BeforeEach setUp()` (ligne 65-72) stubbe déjà
  `currentShopContext.currentOrganizationId()` → `100L`, pas besoin de re-stubber. Exemple de
  structure attendue (à placer par exemple juste après `resolvePhotoRejectsCustomerFromAnotherShop`,
  ligne ~205) :
  ```java
  @Test
  @DisplayName("uploadPhoto stocke le fichier dans un dossier prefixe par l'organisation")
  void uploadPhotoStoresFileUnderOrganizationScopedFolder() {
      Shop shop = Shop.builder().id(1L).name("Boutique principale").active(true).build();
      Customer customer = Customer.builder()
              .id(1L).firstName("Amadou").lastName("Diallo").phone("770000001").active(true)
              .shop(shop).build();
      when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
      MockMultipartFile file = new MockMultipartFile(
              "file", "photo.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
      when(documentStorage.store(file, "org-100/customers")).thenReturn("/uploads/org-100/customers/a.png");
      when(customerRepository.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));

      customerService.uploadPhoto(1L, file);

      verify(documentStorage).store(file, "org-100/customers");
  }
  ```
  (Ajouter les imports `org.springframework.mock.web.MockMultipartFile` et
  `static org.mockito.ArgumentMatchers.any` s'ils ne sont pas déjà présents dans le fichier — vérifier
  avant d'ajouter un doublon d'import.)

- [ ] Aucune tâche de migration de données, aucun changement dans `DocumentStorage`,
  `LocalDiskStorage`, `S3DocumentStorage`, `CustomerController`, `SaleController`, `SecurityConfig`,
  ni côté frontend (confirmé par lecture du code : `store()`/`resolve()`/`delete()` acceptent déjà une
  chaîne `folder`/`key` libre, sans logique dépendante de sa forme).

## Contrat technique

- `DocumentStorage.store(MultipartFile file, String folder)` — signature inchangée
  (`backend/src/main/java/com/creditflow/common/storage/DocumentStorage.java` ligne 13). Seule la
  valeur de `folder` passée par les deux appelants change.
- Nouvelle convention de valeur pour `folder` :
  - Photo client : `"org-" + organizationId + "/customers"` (était `"customers"`).
  - Pièce jointe de vente : `"org-" + organizationId + "/sales/" + saleId` (était `"sales/" + saleId`).
- Clé/chemin résultant (`LocalDiskStorage`, ligne 57) : `"%s/%s/%s".formatted(publicPath, folder, filename)`
  → ex. `/uploads/org-100/customers/<uuid>.png`. Pour `S3DocumentStorage` (ligne 85) : clé objet
  `"%s/%s.%s".formatted(folder, UUID.randomUUID(), extension)` → ex. `org-100/customers/<uuid>.png`.
- `organizationId` résolu via `CurrentShopContext.currentOrganizationId()` (ligne 45 de
  `CurrentShopContext.java`), déjà injecté dans les deux services concernés — pas de nouvelle
  dépendance.
- Aucune nouvelle route API, aucun nouveau DTO, aucune nouvelle colonne/migration Flyway. Les colonnes
  `customers.photo_url` et `sale_attachments.file_url` conservent leur type/nullabilité actuels et
  stockent toujours littéralement la clé retournée par `store()`, qu'elle porte ou non le préfixe
  `org-{id}/`.

## Plan de tests

| Critère d'acceptation du ticket | Statut | Test |
|---|---|---|
| AC1 — Un fichier uploadé n'est plus accessible sans authentification | **Déjà couvert par #45**, non retouché ici | Non-régression uniquement : aucun test nouveau requis pour ce ticket. Couverture existante à ne pas dupliquer : les endpoints `GET /api/customers/{id}/photo` et `GET /api/sales/{id}/attachments/{attachmentId}/file` passent par la chaîne de sécurité Spring standard (authentification requise), testée par la suite existante de #45. Ce ticket ne modifie aucun contrôleur ni `SecurityConfig`. |
| AC2 — Pas d'accès inter-organisation même en connaissant l'UUID | **Déjà couvert par #40 (RLS) + logique `assertAccessible` existante**, non retouché ici | Non-régression : tests unitaires déjà présents et à ne pas modifier — `getEntityRejectsCustomerFromAnotherShop` (`CustomerServiceTest.java` ligne 153-164), `resolvePhotoRejectsCustomerFromAnotherShop` (ligne 193-205), `resolveAttachmentRejectsSaleFromAnotherShop` (`CreditSaleServiceTest.java` ligne 430-439) prouvent que `getEntity(id)` + `assertAccessible` bloquent l'accès avant tout appel à `documentStorage.resolve()`. L'isolation stricte inter-organisation (Postgres RLS via `app.current_org_id`) est couverte par la suite de tests de #40, hors périmètre de ce ticket — ne pas dupliquer ici de test d'intégration RLS. |
| AC3 — Instance mono-tenant : fichiers existants restent accessibles après migration | Nouveau comportement à vérifier : absence de migration nécessaire | Vérification manuelle/documentaire : confirmer par lecture de code (déjà fait, voir design.md "Décisions clés") que `resolve(key)`/`delete(key)` de `LocalDiskStorage`/`S3DocumentStorage` utilisent littéralement la clé stockée en base sans dérivation à partir de l'organisation courante — aucun test automatisé supplémentaire n'a de sens ici puisqu'aucun code de `resolve`/`delete` ne change. Un test manuel de non-régression recommandé avant merge : sur une base de données existante (dossier `org-100/` absent), uploader une nouvelle photo/pièce jointe et vérifier qu'elle atterrit sous `org-{id}/...` tandis qu'un fichier antérieur (`customers/<uuid>.jpg` sans préfixe) reste résolvable via l'endpoint authentifié existant. |
| Nouveau (hors ticket texte, mais requis par le design) — le chemin/la clé générée à l'upload contient bien le segment `org-{organizationId}` | Nouveau, à tester | Tests unitaires modifiés/ajoutés : `CreditSaleServiceTest.accumulatesIdDocumentAttachments` et `.replacesExistingSignature` (vérifient `documentStorage.store(file, "org-100/sales/1")` via `verify`/stub d'argument) ; nouveau test `CustomerServiceTest.uploadPhotoStoresFileUnderOrganizationScopedFolder` (vérifie `documentStorage.store(file, "org-100/customers")`). |

## Écarts identifiés

Aucun écart de fond entre le design et les critères d'acceptation du ticket. Point de vigilance
documentaire pour le reviewer, à ne pas traiter comme un trou fonctionnel : AC1 et AC2, tels que
formulés dans le ticket, sont satisfaits par du code déjà mergé sur `master` avant ce ticket (#45 pour
l'authentification des endpoints de fichiers, #40 pour l'isolation Postgres RLS par organisation,
logique `assertAccessible` déjà en place pour le scoping boutique). Ce ticket n'ajoute aucun mécanisme
d'autorisation supplémentaire — le seul changement de code est la convention de nommage des dossiers de
stockage physique (défense en profondeur, pas un correctif de faille active). Le plan de tests ci-dessus
référence explicitement les tests unitaires existants qui couvrent déjà ce comportement, pour que le
reviewer n'aille pas chercher une garde d'autorisation absente de ce diff.
