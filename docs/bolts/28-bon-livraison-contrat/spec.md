# Spec #28 - Bon de livraison pour un contrat de vente a credit

## Résumé

La spec livre `DeliveryNoteGenerator` (backend), l'endpoint `GET /api/sales/{id}/delivery-note`, le téléchargement frontend associé, et un bouton d'upload générique "Joindre un document" (type `OTHER`) sur `SaleDetailPage.tsx` permettant de rattacher le bon signé au contrat.

## Tâches

- [ ] `backend/src/main/java/com/creditflow/sale/export/DeliveryNoteGenerator.java` (nouveau) — `@Component @RequiredArgsConstructor`, dépend de `AppProperties`, méthode `byte[] generate(CreditSale sale)` (un seul paramètre, pas de `List<Installment>`), méthode `String deliveryNoteNumber(CreditSale sale)` retournant `"BL-%d-%05d".formatted(sale.getStartDate().getYear(), sale.getId())`, méthode `String fileName(CreditSale sale)` sur le modèle exact de `InvoiceGenerator.fileName` (préfixe `bon-livraison-` au lieu de `facture-`). Document A5, mêmes marges `new Document(PageSize.A5, 30, 30, 32, 30)`, mêmes couleurs `HEADER`/`SOFT`, même police shop/titre/small. Titre affiché : `"BON DE LIVRAISON"` (jamais "bon de réception", cf. risque architecte). Contenu du tableau `details()` (PdfPTable 2 colonnes 40/60, via la méthode privée `row()` recopiée à l'identique de `InvoiceGenerator`) : Boutique (nom, `properties.getShop().getName()`), Adresse (uniquement si `sale.getShop().getAddress()` non vide via `StringUtils.hasText`), Contrat (`sale.getReference()`), Client (`sale.getCustomer().getFullName()`), Téléphone (`sale.getCustomer().getPhone()`), Produit (`sale.getProduct().getName()`), Quantité livrée (chaîne en dur `"1"`), Date de livraison (`sale.getStartDate()` formatée `dd/MM/yyyy`). Aucune ligne financière (`totalPrice`, `downPayment`, `financedAmount`, `monthlyAmount`, `remainingAmount`, `amountPaid`) ni méthode `money()`/`schedule()` copiée. Bloc signatures : `PdfPTable` 2 colonnes, une cellule "Signature du client" et une cellule "Signature du livreur / vendeur", chacune avec une ligne de séparation `Rectangle.BOTTOM` (technique identique à `addScheduleCell`/`row`) suivie d'un espace vide suffisant (padding vertical, ex. `cell.setFixedHeight(50f)` puis libellé en dessous ou au-dessus — au choix du codeur, contrainte : la ligne de soulignement doit être visuellement séparée du texte, pas immédiatement sous le libellé). Footer optionnel type "Document à conserver." (facultatif, cohérence avec les 2 autres générateurs).

- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` — ajouter `import com.creditflow.sale.export.DeliveryNoteGenerator;`, injecter `private final DeliveryNoteGenerator deliveryNoteGenerator;` (constructeur généré par Lombok, pas de changement manuel ailleurs), ajouter le record `public record DeliveryNote(String fileName, byte[] content) {}` juste après `Invoice`, puis la méthode :
  ```java
  @Transactional(readOnly = true)
  public DeliveryNote deliveryNote(Long id) {
      CreditSale sale = getEntity(id);
      return new DeliveryNote(deliveryNoteGenerator.fileName(sale), deliveryNoteGenerator.generate(sale));
  }
  ```
  placée juste après la méthode `invoice(Long id)`.

- [ ] `backend/src/main/java/com/creditflow/sale/web/SaleController.java` — ajouter, juste après la méthode `invoice`, l'endpoint :
  ```java
  @GetMapping("/{id}/delivery-note")
  @Operation(summary = "Bon de livraison PDF du contrat, a remettre au client")
  public ResponseEntity<byte[]> deliveryNote(@PathVariable Long id) {
      CreditSaleService.DeliveryNote note = creditSaleService.deliveryNote(id);
      return ResponseEntity.ok()
              .header(HttpHeaders.CONTENT_DISPOSITION,
                      "attachment; filename=\"" + note.fileName() + "\"")
              .contentType(MediaType.APPLICATION_PDF)
              .body(note.content());
  }
  ```

- [ ] `backend/src/test/java/com/creditflow/sale/export/DeliveryNoteGeneratorTest.java` (nouveau, miroir de `InvoiceGeneratorTest`) — `setUp()` identique (même `AppProperties`, `Customer`, `Product`, `CreditSale` avec `id(42L)`, `startDate(LocalDate.of(2026, 6, 5))`). Cas de test détaillés dans le Plan de tests ci-dessous.

- [ ] `backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java` — ajouter deux méthodes de test miroir de `sellerCanDownloadInvoice`/`sellerCannotDownloadInvoiceOfSaleFromAnotherShop`, en mockant `creditSaleService.deliveryNote(1L)` → `new CreditSaleService.DeliveryNote("bon-livraison-bl-2026-00001-20260824.pdf", new byte[]{1,2,3})` sur `GET /api/sales/1/delivery-note` (200 + `content().contentType(MediaType.APPLICATION_PDF)`), et `creditSaleService.deliveryNote(2L)` → `ResourceNotFoundException` sur `GET /api/sales/2/delivery-note` (404).

- [ ] `frontend/src/api/endpoints.ts` — dans `salesApi`, ajouter juste après `downloadInvoice` :
  ```ts
  /** Bon de livraison PDF du contrat, à remettre au client lors de la remise du produit. */
  downloadDeliveryNote: async (id: number) => {
    const response = await api.get(`/sales/${id}/delivery-note`, { responseType: 'blob' });
    downloadBlob(response.data, filenameFromHeaders(response.headers, `bon-livraison-${id}.pdf`));
  },
  ```

- [ ] `frontend/src/pages/SaleDetailPage.tsx` — imports : ajouter `LocalShippingOutlinedIcon` depuis `@mui/icons-material/LocalShippingOutlined` et `NoteAddOutlinedIcon` depuis `@mui/icons-material/NoteAddOutlined` (ou équivalent MUI existant dans le projet — vérifier disponibilité du package, sinon réutiliser une icône déjà importée ailleurs dans le repo pour rester cohérent avec la palette d'icônes du projet). Dans le bloc `action` du `PageHeader` (après le bouton `Télécharger la facture`, avant le bouton conditionnel `Encaisser`), ajouter :
  ```tsx
  <Button startIcon={<LocalShippingOutlinedIcon />} onClick={() => salesApi.downloadDeliveryNote(sale.id)}>
    Télécharger le bon de livraison
  </Button>
  ```
  Dans le bloc "Pièces jointes", ajouter un `useRef<HTMLInputElement>(null)` nommé `otherDocumentInput` (à côté de `idDocumentInput`), un `<input type="file" hidden ref={otherDocumentInput} accept="image/*" onChange={...}>` déclenchant `uploadAttachmentMutation.mutate({ type: 'OTHER', file })` (même pattern que le handler `ID_DOCUMENT`, y compris le reset `event.target.value = ''`), puis un troisième `Button` `size="small"` avec icône `NoteAddOutlinedIcon`, `onClick={() => otherDocumentInput.current?.click()}`, `disabled={uploadAttachmentMutation.isPending}`, libellé **"Joindre un document"**, placé dans le même `Stack direction="row"` après le bouton "Faire signer le client". L'`accept` est restreint à `image/*` (voir Écarts identifiés — pas de PDF dans ce ticket, cohérent avec le bouton pièce d'identité existant).

- [ ] Vérification manuelle non automatisable : contrôle visuel du rendu PDF (positionnement, lisibilité des zones de signature, absence de coupure de page sur A5) — à consigner dans la description de la PR, pas de test automatisé pour le rendu visuel.

## Contrat technique

- Endpoint : `GET /api/sales/{id}/delivery-note`
  - Réponse `200` : `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="bon-livraison-bl-<annee>-<id5>-<yyyyMMdd>.pdf"`, corps = octets PDF.
  - Réponse `404` : contrat introuvable ou hors périmètre de la boutique courante (via `CreditSaleService.getEntity` → `currentShopContext.assertAccessible`), comportement identique à `/invoice`.
  - Aucun filtre sur `SaleStatus` : accessible pour `ACTIVE`, `COMPLETED`, `CANCELLED`.
- `CreditSaleService.DeliveryNote(String fileName, byte[] content)` — record, symétrique à `Invoice`.
- `CreditSaleService.deliveryNote(Long id): DeliveryNote` — `@Transactional(readOnly = true)`.
- `DeliveryNoteGenerator.generate(CreditSale sale): byte[]` — signature à un seul argument (différence volontaire avec `InvoiceGenerator.generate(sale, installments)`).
- `DeliveryNoteGenerator.deliveryNoteNumber(CreditSale sale): String` — format `BL-<année de sale.getStartDate()>-<id sur 5 chiffres>`.
- `DeliveryNoteGenerator.fileName(CreditSale sale): String` — format `bon-livraison-<deliveryNoteNumber en minuscules>-<yyyyMMdd du jour>.pdf`.
- Frontend : `salesApi.downloadDeliveryNote(id: number): Promise<void>` — déclenche un téléchargement navigateur, aucune valeur de retour exploitable.
- Upload pièce jointe : réutilisation stricte du contrat existant `POST /api/sales/{id}/attachments` (`multipart/form-data`, `type=OTHER`, part `file`), aucun changement de payload/DTO.
- Libellé UI de la pièce jointe de type `OTHER` : **conservé tel quel ("Autre")**, `ATTACHMENT_TYPE_LABELS` n'est pas modifié — un bon de livraison signé scanné apparaîtra donc sous "Autre" au même titre que tout futur document divers, décision tranchée ci-dessous.

## Plan de tests

| Critère d'acceptation du ticket | Test |
|---|---|
| Un bon de livraison PDF peut être généré et téléchargé/imprimé depuis le détail du contrat | `DeliveryNoteGeneratorTest.producesAValidPdf()` — `content` non vide, préfixe `%PDF-` (5 premiers octets ISO-8859-1), `content.length > 700` (calquer sur `InvoiceGeneratorTest.producesAValidPdf`). Complété par `SaleControllerSecurityTest.sellerCanDownloadDeliveryNote()` — `GET /api/sales/1/delivery-note` → 200, `Content-Type: application/pdf`. Manuel : clic sur "Télécharger le bon de livraison" dans `SaleDetailPage` déclenche bien un téléchargement de fichier PDF nommé correctement. |
| Le document identifie clairement le(s) produit(s) livré(s) et le client, avec une zone de signature | `DeliveryNoteGeneratorTest` : test de non-régression de taille — comparer `generate(sale)` avec une variante où `product.getName()` / `customer.getFullName()` sont vides ou courts (`assertThat(withLongName.length).isGreaterThan(withShortName.length)`, même technique que `listsAllInstallments` de `InvoiceGeneratorTest` pour prouver que le contenu varie selon les données injectées, faute de pouvoir parser le texte du PDF facilement avec les dépendances actuelles du projet — vérifier si une lib d'extraction de texte PDF est déjà présente dans le build backend : si oui, préférer un test d'extraction de texte assertant la présence littérale de `sale.getProduct().getName()`, `sale.getCustomer().getFullName()`, `"Signature du client"` et `"Signature du livreur"` dans le texte extrait — plus robuste que le test de taille). Manuel : ouverture du PDF téléchargé, vérification visuelle des deux zones de signature côte à côte. |
| Le document peut être rattaché au contrat une fois signé, via la pièce jointe existante | Test manuel/E2E (aucune modification backend, contrat d'API `POST /api/sales/{id}/attachments` déjà couvert par les tests existants) : cliquer sur "Joindre un document" dans `SaleDetailPage`, sélectionner un fichier, vérifier l'apparition de la pièce jointe sous le libellé "Autre" dans la grille, et vérifier `uploadAttachmentMutation` appelée avec `{ type: 'OTHER', file }`. Si un test de composant frontend existe déjà pour `SaleDetailPage` (vérifier présence d'un fichier de test associé) : étendre avec un cas simulant le clic sur le nouveau bouton et l'appel à `salesApi.uploadAttachment(saleId, 'OTHER', file)`. |
| Le document est clairement distinct de la facture (#27), pas de doublon de contenu/objectif | `DeliveryNoteGeneratorTest.noFinancialData()` (nouveau, sans équivalent direct dans `InvoiceGeneratorTest`, à écrire spécifiquement) — si extraction de texte PDF disponible : assertion négative que le texte extrait ne contient ni `money(sale.getTotalPrice())`, ni `money(sale.getFinancedAmount())`, ni `money(sale.getMonthlyAmount())`, ni le mot "Échéancier"/"Echeancier", ni "Reste à payer"/"Acompte" ; à défaut d'extraction de texte, test de revue de code obligatoire en review (checklist explicite dans la PR) confirmant qu'aucune méthode `money()`/`schedule()` n'est appelée dans `DeliveryNoteGenerator` — c'est le critère le plus à risque de régression silencieuse si un futur codeur copie-colle `details()` d'`InvoiceGenerator` sans retirer les lignes financières. Ajouter aussi une assertion de titre : le texte extrait (ou, à défaut, une relecture manuelle documentée dans la PR) doit contenir "BON DE LIVRAISON" et ne pas contenir "FACTURE" ni "REÇU"/"REBUT". |
| Numérotation lisible et stable | `DeliveryNoteGeneratorTest.numbersTheDeliveryNote()` — `assertThat(generator.deliveryNoteNumber(sale)).isEqualTo("BL-2026-00042")`, `assertThat(generator.fileName(sale)).startsWith("bon-livraison-bl-2026-00042-")`, `.endsWith(".pdf")` (calque exact de `InvoiceGeneratorTest.numbersTheInvoice`). |
| Génération possible quel que soit le statut du contrat | `DeliveryNoteGeneratorTest.handlesEverySaleStatus()` — boucle sur `SaleStatus.ACTIVE/COMPLETED/CANCELLED`, `assertThat(generator.generate(sale)).isNotEmpty()` (calque exact de `InvoiceGeneratorTest.handlesEverySaleStatus`). |
| Contrôle d'accès inter-boutique | `SaleControllerSecurityTest.sellerCannotDownloadDeliveryNoteOfSaleFromAnotherShop()` — mock `creditSaleService.deliveryNote(2L)` lève `ResourceNotFoundException`, `GET /api/sales/2/delivery-note` → 404 (calque exact de `sellerCannotDownloadInvoiceOfSaleFromAnotherShop`). |
| Adresse boutique absente gérée proprement | `DeliveryNoteGeneratorTest.omitsAddressWhenBlank()` (nouveau) — construire un `Shop` sans `address` (null), vérifier que la génération ne lève pas d'exception (`assertThat(generator.generate(sale)).isNotEmpty()`) ; si extraction de texte disponible, vérifier l'absence littérale de la chaîne `"null"` dans le PDF généré. |

## Écarts identifiés

- **Libellé "Autre" vs libellé spécifique pour la pièce jointe `OTHER`** (point laissé ouvert par le design) : tranché en faveur de **conserver le libellé générique "Autre"** sans ajout ni de sous-catégorie ni de logique de détection côté UI (ex. deviner via `originalFilename` ou date d'upload). Motif : introduire une distinction visuelle fiable nécessiterait soit une nouvelle valeur d'enum (explicitement écartée par le design et hors périmètre du ticket), soit une heuristique fragile côté frontend (nommage de fichier) qui n'apporte pas de garantie et complexifie le composant pour un gain marginal — le ticket n'exige pas de distinguer visuellement le bon de livraison signé des autres pièces jointes "Autre", seulement de pouvoir le rattacher au contrat, ce qui est satisfait tel quel.
- **Prévisualisation de la pièce jointe `OTHER`** : la grille "Pièces jointes" de `SaleDetailPage.tsx` affiche systématiquement `<Box component="img" src={attachment.fileUrl} />`. Si le bon de livraison signé est uploadé au format PDF, la vignette affichera une image cassée au lieu d'un aperçu exploitable — comportement déjà présent aujourd'hui pour tout upload non-image via un futur type `OTHER`, non spécifique à ce ticket. Décision : restreindre l'`accept` du nouveau bouton à `image/*` uniquement pour éviter la vignette cassée, cohérent avec le bouton "Ajouter une pièce d'identité" existant qui a la même contrainte — le PDF scanné reste hors périmètre de ce ticket (l'utilisateur photographie le document signé, comme pour la pièce d'identité).
- **Absence de bibliothèque d'extraction de texte PDF côté tests** : à confirmer par le codeur en inspectant la configuration de build backend — si aucune dépendance de type extraction de texte PDF n'est présente pour les tests, les assertions de contenu textuel du plan de tests ci-dessus (produit, client, absence de données financières) devront être remplacées par des tests de taille comparative (pattern `listsAllInstallments`) et une checklist de revue de code manuelle documentée dans la description de la PR, plutôt que bloquer sur l'ajout d'une nouvelle dépendance de test non demandée par le ticket.
