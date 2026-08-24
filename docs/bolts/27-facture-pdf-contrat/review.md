# Review #27 - Facture PDF pour un contrat de vente a credit

## Verdict

APPROVE

## Critères d'acceptation

| # | Critère | Statut | Preuve |
|---|---|---|---|
| 1 | Depuis le détail d'un contrat, un bouton télécharge une facture PDF correspondant à ce contrat | Couvert | Backend : `GET /api/sales/{id}/invoice` (`SaleController.java:82-90`) délègue à `CreditSaleService.invoice(id)`. Frontend : bouton "Télécharger la facture" ajouté sans condition de statut dans `SaleDetailPage.tsx:145-147`, appelant `salesApi.downloadInvoice(sale.id)` (`endpoints.ts:203-206`). Test intégration `SaleControllerSecurityTest.sellerCanDownloadInvoice` (200 OK + `Content-Type: application/pdf`), qui échouerait sans l'endpoint. |
| 2 | Le document reprend fidèlement les données du contrat (produit, prix, échéancier) | Couvert | `InvoiceGenerator.details()` reprend toutes les lignes prescrites par la spec (client, téléphone, contrat, produit, prix total, acompte, montant financé, mensualité, déjà réglé, reste à payer). `schedule()` liste **tous** les `Installment` transmis, sans filtre `!isSettled()` (contrairement à `PaymentReceiptGenerator.details()` qui ne montre que la prochaine échéance) — vérifié en confrontant le diff aux deux classes côte à côte. Test `InvoiceGeneratorTest.listsAllInstallments` compare la taille du PDF avec 1 vs 3 échéances (repère de non-régression accepté par la spec faute de parsing de texte). |
| 3 | Chaque facture porte une référence unique et traçable | Couvert | `invoiceNumber()` = `FAC-<année startDate>-<id sur 5 chiffres>`, dérivé de `CreditSale.id` (PK globalement unique, même principe que `CreditSale.reference` et `PaymentReceiptGenerator.receiptNumber`). Test `InvoiceGeneratorTest.numbersTheInvoice` vérifie le format exact (`FAC-2026-00042`) et le nom de fichier dérivé. Pas de persistance ajoutée, cohérent avec la décision assumée dans `design.md`. |

Les 3 critères sont couverts par du code **et** par un test qui échouerait si le code était retiré (vérifié en relisant les assertions, pas seulement leur présence).

## Vérifications spécifiques demandées

- **Contrôle d'accès inter-boutique** : `CreditSaleService.invoice(id)` réutilise `getEntity(id)`, qui appelle `currentShopContext.assertAccessible(sale.getShop().getId())` et lève `ResourceNotFoundException.of("Contrat", id)` si la boutique n'est pas accessible — aucun contrôle d'accès dupliqué, aucune fuite d'info (message générique, 404 propagé tel quel par le contrôleur). `CreditSaleServiceTest.invoiceRejectsSaleFromAnotherShop` vérifie en plus que `invoiceGenerator.generate` n'est jamais appelé dans ce cas (`verify(invoiceGenerator, never()).generate(any(), any())`), et `SaleControllerSecurityTest.sellerCannotDownloadInvoiceOfSaleFromAnotherShop` vérifie le 404 côté HTTP. Confirmé.
- **Échéancier complet** : `schedule(List<Installment> installments)` itère sur `installments` sans aucun filtre — pas de `!isSettled()` copié depuis `PaymentReceiptGenerator`. `CreditSaleService.invoice()` transmet `sale.getInstallments()` brut (liste déjà triée par `@OrderBy("number ASC")` sur l'entité `CreditSale`, confirmé en lisant `CreditSale.java:117-119`), pas de re-tri ni de filtrage. Confirmé.
- **Numéro de facture unique/traçable** : format et dérivation conformes au contrat technique (`FAC-<année>-<id 5 chiffres>`), `sale.getId()` étant une PK globale unique (colonne `credit_sales.id`). Test unitaire dédié en place.
- **Pas de migration Flyway** : `git diff master...HEAD --name-only` ne montre aucun fichier sous `db/migration/`. Cohérent avec le choix assumé (numérotation dérivée, non persistée).
- **Bouton frontend visible quel que soit le statut** : le bouton "Télécharger la facture" est placé au niveau du `Stack` d'actions, **avant** le bloc `{sale.status === 'ACTIVE' && (...)}` qui conditionne le bouton "Encaisser" — il n'est lui-même dans aucune condition de statut (`SaleDetailPage.tsx:145-147`). Confirmé par lecture directe du JSX.
- **Absence de régression sur l'existant** : `PaymentReceiptGenerator.java` n'est pas touché par le diff (aucune ligne modifiée) ; `PaymentReceiptGeneratorTest` repasse (3/3). Le reste de `SaleController`/`CreditSaleService` n'est modifié qu'en ajout (nouvel import, nouveau champ injecté, nouvelle méthode/endpoint) — aucune méthode existante n'a été touchée en dehors de l'ajout du paramètre `invoiceGenerator` au constructeur généré par Lombok, correctement répercuté dans `CreditSaleServiceTest` (instanciation manuelle mise à jour).

## Cohérence avec spec.md / design.md

Le code suit fidèlement les tâches 1 à 8 de la spec : package, signatures, formats de chaîne, injection `AppProperties`, absence de `@PreAuthorize` supplémentaire, réutilisation de `downloadBlob`/`filenameFromHeaders`/`ReceiptIcon` existants sans duplication ni nouvel import inutile. Aucun écart non justifié constaté. Le choix `AppProperties.getShop()` plutôt que `sale.getShop()` est bien repris tel quel, conformément à la décision explicite du design (dette héritée du reçu de paiement, hors périmètre de ce ticket).

## Findings

Aucun finding bloquant. Deux remarques mineures, non bloquantes, pour information :

1. `InvoiceGenerator.java:106` — libellé `"Payée"` (avec accent) alors que le reste des libellés du générateur (`"Echeance"`, `"Contrat"`, etc.) est en ASCII pur sans accents, comme le reste de la classe et de `PaymentReceiptGenerator`. Incohérence typographique mineure, sans impact fonctionnel (l'encodage PDF/Helvetica gère l'accent correctement, testé via `producesAValidPdf`).
2. `InvoiceGeneratorTest.listsAllInstallments` (et la ligne du plan de tests correspondante) vérifie la présence de toutes les échéances par la taille du PDF croissante plutôt que par extraction de texte — c'est la méthode explicitement acceptée par la spec ("faute de pouvoir parser le texte du PDF facilement"), donc pas un finding, juste une limite de couverture assumée en amont.

## Build/tests

Backend (Maven 3.9.16 / JDK 21) :
- `mvn -Dtest=InvoiceGeneratorTest,CreditSaleServiceTest,SaleControllerSecurityTest test` → `InvoiceGeneratorTest` 4/4, `CreditSaleServiceTest` 16/16, `SaleControllerSecurityTest` 9/9 — 0 échec, 0 erreur.
- `mvn -Dtest="com.creditflow.sale.**,com.creditflow.payment.**" test` (régression complète des packages `sale` et `payment`) → 92 tests, 0 échec, 0 erreur, `BUILD SUCCESS`.
- `mvn -Dtest=com.creditflow.payment.export.PaymentReceiptGeneratorTest test` (non-régression explicite du reçu de paiement, code voisin non censé être touché) → 3/3, 0 échec.

Frontend (Node/npm) :
- `npm run lint` (`tsc --noEmit`) → OK, aucune erreur.
- `npm run build` (`tsc --noEmit && vite build`) → `BUILD SUCCESS`, bundle généré (avertissement pré-existant sur la taille du chunk principal, sans lien avec ce ticket).

Tous les chiffres rapportés par le codeur (4/16/9 tests, 29 au total, régression `sale`/`payment` sans échec, lint/build OK) sont confirmés par relance indépendante.
