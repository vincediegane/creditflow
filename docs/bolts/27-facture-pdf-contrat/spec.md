# Spec #27 - Facture PDF pour un contrat de vente a credit

## Résumé

Ajout d'un endpoint `GET /api/sales/{id}/invoice` qui génère à la demande une facture PDF (A5, OpenPDF) reprenant les données du contrat et l'échéancier complet, accessible depuis un bouton sur la page de détail du contrat, par symétrie stricte avec le reçu de paiement existant.

## Tâches

### Backend

- [ ] **1. `backend/src/main/java/com/creditflow/sale/export/InvoiceGenerator.java`** (nouveau)
  Créer la classe `@Component @RequiredArgsConstructor` `InvoiceGenerator`, package `com.creditflow.sale.export`, calquée sur `com.creditflow.payment.export.PaymentReceiptGenerator` (mêmes imports OpenPDF/lowagie, même `Document(PageSize.A5, 30, 30, 32, 30)`, mêmes couleurs `HEADER`/`SOFT`, même `DateTimeFormatter DATE = "dd/MM/yyyy"`).
  Dépendance injectée : `private final AppProperties properties;`
  Méthodes publiques :
  - `public byte[] generate(CreditSale sale, List<Installment> installments)` — construit le document : en-tête boutique (`properties.getShop().getName()`), titre `"FACTURE"`, ligne de référence `"N° %s   -   %s".formatted(invoiceNumber(sale), LocalDate.now().format(DATE))`, un bloc récapitulatif (voir Contrat technique), la table détails contrat, la table échéancier complète, un pied de page (mentions minimales, pas de signature obligatoire côté client comme sur le reçu — un simple texte de politesse suffit).
  - `public String invoiceNumber(CreditSale sale)` — `"FAC-%d-%05d".formatted(sale.getStartDate().getYear(), sale.getId())`.
  - `public String fileName(CreditSale sale)` — `"facture-%s-%s.pdf".formatted(invoiceNumber(sale).toLowerCase(Locale.ROOT), LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")))`.
  Méthodes privées à dupliquer/adapter depuis `PaymentReceiptGenerator` : `row(PdfPTable, String, String)`, `row(PdfPTable, String, String, boolean)`, `money(BigDecimal)` (utilise `properties.getShop().getCurrency()`). Ajouter une méthode privée `schedule(List<Installment> installments)` retournant un `PdfPTable` à 4 colonnes (N°, Échéance, Montant, Statut) listant **tous** les `Installment`, triés par `number` (l'ordre naturel de `sale.getInstallments()` est déjà `@OrderBy("number ASC")` sur l'entité — ne pas re-trier si la liste vient directement de `sale.getInstallments()`), avec un libellé de statut simple (`PENDING` → "En attente", `PARTIAL` → "Partiel", `PAID` → "Payée").
  Exceptions : catcher `DocumentException | IOException` et relancer `new BusinessRuleException("Impossible de generer la facture")`, comme dans `PaymentReceiptGenerator.generate`.

- [ ] **2. `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java`**
  Injecter `private final InvoiceGenerator invoiceGenerator;` (nouveau champ, `@RequiredArgsConstructor` génère le constructeur automatiquement — pas de constructeur manuel à toucher, mais vérifier que les tests unitaires qui instancient `CreditSaleService` avec `new CreditSaleService(...)` (voir `CreditSaleServiceTest`) sont mis à jour avec le paramètre supplémentaire).
  Ajouter :
  ```java
  public record Invoice(String fileName, byte[] content) {}

  @Transactional(readOnly = true)
  public Invoice invoice(Long id) {
      CreditSale sale = getEntity(id);
      List<Installment> installments = sale.getInstallments();
      return new Invoice(invoiceGenerator.fileName(sale), invoiceGenerator.generate(sale, installments));
  }
  ```
  Placer cette méthode à proximité de `findDetail` (même style que `PaymentService.receipt`). Pas de filtre sur `SaleStatus` : la méthode doit rester accessible pour `ACTIVE`, `COMPLETED` et `CANCELLED`.

- [ ] **3. `backend/src/main/java/com/creditflow/sale/web/SaleController.java`**
  Ajouter l'import `org.springframework.http.HttpHeaders` (ou qualifier en ligne, comme fait pour `receipt` dans `PaymentController` avec `org.springframework.http.HttpHeaders` en FQN).
  Ajouter l'endpoint, positionné après `detail` (ligne ~79) :
  ```java
  @GetMapping("/{id}/invoice")
  @Operation(summary = "Facture PDF du contrat, a remettre au client")
  public ResponseEntity<byte[]> invoice(@PathVariable Long id) {
      CreditSaleService.Invoice invoice = creditSaleService.invoice(id);
      return ResponseEntity.ok()
              .header(HttpHeaders.CONTENT_DISPOSITION,
                      "attachment; filename=\"" + invoice.fileName() + "\"")
              .contentType(MediaType.APPLICATION_PDF)
              .body(invoice.content());
  }
  ```
  Aucun `@PreAuthorize` supplémentaire : mêmes rôles que `detail`/`get` (accès par défaut, contrôle boutique via `getEntity`).

- [ ] **4. `backend/src/test/java/com/creditflow/sale/export/InvoiceGeneratorTest.java`** (nouveau)
  Miroir de `PaymentReceiptGeneratorTest.java` : construire une `CreditSale` (avec `customer`, `product`, `startDate`, etc.) et une `List<Installment>` couvrant au moins un statut `PAID` et un statut `PENDING`/`PARTIAL`. Voir Plan de tests pour le détail des cas.

- [ ] **5. `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java`**
  Mettre à jour l'instanciation de `CreditSaleService` (constructeur manuel ou `@InjectMocks`) pour inclure le nouveau mock `@Mock private InvoiceGenerator invoiceGenerator;`. Ajouter les cas de test décrits dans le Plan de tests (numérotation, statuts, accès inter-boutique).

- [ ] **6. `backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java`**
  Ajouter un test sur `/invoice` suivant le pattern déjà utilisé dans `AuditLogControllerSecurityTest` (`when(customerService.getEntity(2L)).thenThrow(new ResourceNotFoundException(...))` → `status().isNotFound()`), transposé à `creditSaleService.invoice(anyLong())` :
  ```java
  @Test
  @WithMockUser(roles = "SELLER")
  @DisplayName("refuse la facture d'un contrat d'une autre boutique")
  void sellerCannotDownloadInvoiceOfSaleFromAnotherShop() throws Exception {
      when(creditSaleService.invoice(2L)).thenThrow(new ResourceNotFoundException("Ressource introuvable"));

      mockMvc.perform(get("/api/sales/2/invoice")).andExpect(status().isNotFound());
  }
  ```
  Ajouter également un cas positif simple (`200 OK`, `Content-Type: application/pdf`) en mockant `creditSaleService.invoice(1L)` pour retourner un `CreditSaleService.Invoice("facture-fac-2026-00001-20260824.pdf", new byte[]{1,2,3})`.
  Ajouter les imports nécessaires (`ResourceNotFoundException`, `MockMvcRequestBuilders.get`, `anyLong` déjà importé).

### Frontend

- [ ] **7. `frontend/src/api/endpoints.ts`**
  Dans `salesApi` (après `removeAttachment`, avant la fermeture de l'objet ligne ~201), ajouter :
  ```ts
  /** Facture PDF du contrat, à remettre au client. */
  downloadInvoice: async (id: number) => {
    const response = await api.get(`/sales/${id}/invoice`, { responseType: 'blob' });
    downloadBlob(response.data, filenameFromHeaders(response.headers, `facture-${id}.pdf`));
  },
  ```
  Ne pas dupliquer `downloadBlob`/`filenameFromHeaders`, déjà déclarées en haut du fichier et réutilisées par `paymentsApi.downloadReceipt`.

- [ ] **8. `frontend/src/pages/SaleDetailPage.tsx`**
  Dans le `Stack` d'actions du `PageHeader` (lignes 140-154), ajouter un bouton entre `"Générer la relance"` (ligne 144) et le bloc conditionnel `sale.status === 'ACTIVE'` (ligne 145) :
  ```tsx
  <Button startIcon={<ReceiptIcon />} onClick={() => salesApi.downloadInvoice(sale.id)}>
    Télécharger la facture
  </Button>
  ```
  Réutiliser l'import existant `import ReceiptIcon from '@mui/icons-material/ReceiptLong';` (ligne 29) — pas de nouvel import d'icône. `salesApi` est déjà importé (ligne 32). Le bouton doit être visible quel que soit `sale.status` (ACTIVE, COMPLETED, CANCELLED) — ne pas le placer dans un bloc conditionnel de statut.

## Contrat technique

**Backend**

- `InvoiceGenerator.generate(CreditSale sale, List<Installment> installments) : byte[]` — PDF A5, `MediaType.APPLICATION_PDF`.
- `InvoiceGenerator.invoiceNumber(CreditSale sale) : String` — format `FAC-<année startDate>-<id sur 5 chiffres>`, ex. `FAC-2026-00042`.
- `InvoiceGenerator.fileName(CreditSale sale) : String` — `facture-<numéro en minuscules>-<yyyyMMdd du jour>.pdf`.
- `CreditSaleService.Invoice` — `record Invoice(String fileName, byte[] content)`.
- `CreditSaleService.invoice(Long id) : Invoice` — `@Transactional(readOnly = true)`, réutilise `getEntity(id)` (contrôle d'accès boutique + 404 si hors périmètre).
- Endpoint : `GET /api/sales/{id}/invoice` → `ResponseEntity<byte[]>`, `200 OK`, header `Content-Disposition: attachment; filename="facture-...pdf"`, `Content-Type: application/pdf`. `404` (`ResourceNotFoundException`) si le contrat n'existe pas ou n'appartient pas à une boutique accessible à l'utilisateur courant. Pas de body de requête, pas de query params.

**Frontend**

- `salesApi.downloadInvoice(id: number) : Promise<void>` — `GET /sales/{id}/invoice`, `responseType: 'blob'`, déclenche le téléchargement via `downloadBlob` avec le nom de fichier extrait du header `Content-Disposition` (fallback `facture-${id}.pdf`).
- Bouton "Télécharger la facture" dans `PageHeader.action` de `SaleDetailPage`, icône `ReceiptIcon` (alias de `ReceiptLong`), visible pour tout statut de contrat.

**Contenu de la facture (table détails)** — reprendre les mêmes lignes que `PaymentReceiptGenerator.details()` à l'exception de tout ce qui est spécifique à un paiement :
`Client` (`sale.getCustomer().getFullName()`), `Téléphone` (`sale.getCustomer().getPhone()`), `Contrat` (`sale.getReference()`), `Produit` (`sale.getProduct().getName()`), `Prix total` (`money(sale.getTotalPrice())`), `Acompte` (`money(sale.getDownPayment())`), `Montant financé` (`money(sale.getFinancedAmount())`), `Mensualité` (`money(sale.getMonthlyAmount())`), `Déjà réglé` (`money(sale.getDownPayment().add(sale.getAmountPaid()))`), `Reste à payer` (`money(sale.getRemainingAmount())`, mis en évidence comme dans le reçu).

**Table échéancier** — une ligne par `Installment` de `sale.getInstallments()` (pas seulement la prochaine échéance) : `N°` (`installment.getNumber()`), `Échéance` (`installment.getDueDate().format(DATE)`), `Montant` (`money(installment.getAmount())`), `Statut` (libellé FR dérivé de `installment.getStatus()`).

## Plan de tests

| Critère d'acceptation (ticket #27) | Test | Type |
|---|---|---|
| Depuis le détail d'un contrat, un bouton télécharge une facture PDF correspondant à ce contrat | `SaleControllerSecurityTest.sellerCanDownloadInvoice` (nouveau, `200 OK` + `Content-Type: application/pdf` + header `Content-Disposition`) ; vérification manuelle du bouton dans `SaleDetailPage` (clic déclenche le téléchargement, présent quel que soit le statut du contrat) | Intégration (contrôleur) + manuel (aucun test FE automatisé dans le repo à ce jour) |
| Le document reprend fidèlement les données du contrat (produit, prix, échéancier) | `InvoiceGeneratorTest.producesAValidPdf` (PDF non vide, en-tête `%PDF-`) ; `InvoiceGeneratorTest.listsAllInstallments` (nouveau — construire un contrat avec au moins 3 échéances de statuts différents, générer le PDF, vérifier a minima que le contenu produit ne lève pas d'exception et que `sale.getInstallments()` complet est bien passé à `generate` — assertion sur la taille du PDF croissante avec le nombre d'échéances, comme repère de non-régression, faute de pouvoir parser le texte du PDF facilement) | Unitaire |
| Chaque facture porte une référence unique et traçable | `InvoiceGeneratorTest.numbersTheInvoice` — `assertThat(generator.invoiceNumber(sale)).isEqualTo("FAC-2026-00042")` pour un contrat `id=42`, `startDate` en 2026 ; `assertThat(generator.fileName(sale)).startsWith("facture-fac-2026-00042-")` et `.endsWith(".pdf")` | Unitaire |
| Comportement selon le statut du contrat (ACTIVE / COMPLETED / CANCELLED) | `InvoiceGeneratorTest.handlesEverySaleStatus` (paramétré ou 3 cas explicites : générer le PDF pour un contrat `ACTIVE`, un `COMPLETED`, un `CANCELLED` — dans les trois cas `generate` ne lève pas d'exception et retourne un contenu non vide) ; `CreditSaleServiceTest.invoiceWorksRegardlessOfStatus` (mock `saleRepository.findDetailById` avec un contrat `CANCELLED`, vérifier que `invoice(id)` ne lève pas d'exception) | Unitaire |
| Contrôle d'accès inter-boutique (404 attendu) | `SaleControllerSecurityTest.sellerCannotDownloadInvoiceOfSaleFromAnotherShop` (mock `creditSaleService.invoice(2L)` lève `ResourceNotFoundException`, attend `status().isNotFound()`) ; `CreditSaleServiceTest.invoiceRejectsSaleFromAnotherShop` (mock `currentShopContext.assertAccessible(...)` pour lever `ResourceNotFoundException`, vérifier que `invoice(id)` propage l'exception sans appeler `invoiceGenerator.generate`) | Intégration (contrôleur) + unitaire (service) |
| Échéancier complet (pas seulement la prochaine échéance) | Couvert par `InvoiceGeneratorTest.listsAllInstallments` ci-dessus — vérifier que le générateur consomme bien la liste complète transmise par `CreditSaleService.invoice`, en s'assurant côté service que `installments = sale.getInstallments()` (et non un filtre `!isSettled()` comme dans `PaymentReceiptGenerator.details()`) | Unitaire |

## Écarts identifiés

Aucun écart bloquant entre `design.md` et le ticket #27. Un point de vigilance non bloquant, à titre informatif pour le codeur :

- Le design mentionne "étendre `SaleControllerSecurityTest` avec un cas sur `/invoice`, sur le modèle des cas déjà présents pour `/detail`" — aucun cas existant sur `/detail` n'a été trouvé dans ce fichier (il ne teste que les restrictions par rôle via `@PreAuthorize`). Le pattern correct et déjà utilisé ailleurs dans le repo pour ce type de contrôle (mock du service levant `ResourceNotFoundException` → assertion `status().isNotFound()`) se trouve dans `AuditLogControllerSecurityTest` (`rejectsAuditLogOfCustomerFromAnotherShop`, ligne 70-80) ; c'est ce pattern qui est repris dans la tâche 6 ci-dessus.
