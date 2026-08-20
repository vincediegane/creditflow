# Design — Achats fournisseurs et réception de stock (#8)

## Approche

On ajoute un module backend `supplier` (fournisseurs + réceptions de stock) qui reste indépendant du module `product` mais s'appuie sur lui : une ligne de réception incrémente `Product.stock` via une nouvelle méthode symétrique à `ProductService.decreaseStock`. Pour satisfaire le critère « historique distinguant entrées et sorties », on introduit une entité `StockMovement` (append-only) écrite automatiquement à chaque variation de stock, aussi bien côté vente (`decreaseStock`) que côté réception (`increaseStock`) — centraliser l'écriture dans `ProductService` évite qu'un futur appelant oublie de tracer le mouvement. On simplifie volontairement le périmètre « commande d'achat » du ticket en un flux à une seule étape (créer une réception = elle est immédiatement validée et le stock bouge tout de suite) : aucun critère d'acceptation ne demande un cycle brouillon/validation séparé, et l'ajouter maintenant serait de la sur-ingénierie non sollicitée. Le prix payé est la perte de traçabilité d'une commande « en attente » avant réception réelle — acceptable puisque le ticket exclut explicitement la gestion des paiements fournisseurs.

## Fichiers/modules impactés

Backend (nouveaux, module `com.creditflow.supplier`) :
- `backend/src/main/java/com/creditflow/supplier/domain/Supplier.java`
- `backend/src/main/java/com/creditflow/supplier/domain/StockReception.java`
- `backend/src/main/java/com/creditflow/supplier/domain/StockReceptionLine.java`
- `backend/src/main/java/com/creditflow/supplier/dto/SupplierRequest.java`, `SupplierResponse.java`
- `backend/src/main/java/com/creditflow/supplier/dto/StockReceptionRequest.java` (supplierId, receivedAt, notes, liste de lignes productId/quantity), `StockReceptionResponse.java`
- `backend/src/main/java/com/creditflow/supplier/mapper/SupplierMapper.java`, `StockReceptionMapper.java`
- `backend/src/main/java/com/creditflow/supplier/repository/SupplierRepository.java`, `SupplierSpecifications.java`, `StockReceptionRepository.java`
- `backend/src/main/java/com/creditflow/supplier/service/SupplierService.java` (CRUD, calqué sur CustomerService)
- `backend/src/main/java/com/creditflow/supplier/service/StockReceptionService.java` (crée la réception + ses lignes, appelle ProductService.increaseStock par ligne, dans une seule transaction)
- `backend/src/main/java/com/creditflow/supplier/web/SupplierController.java` (/api/suppliers), StockReceptionController.java (/api/stock-receptions)

Backend (module `product`, mouvements de stock) :
- `backend/src/main/java/com/creditflow/product/domain/StockMovement.java` (product, type IN/OUT, quantity, sourceType PURCHASE_RECEPTION/SALE, sourceId nullable, occurredAt, createdBy)
- `backend/src/main/java/com/creditflow/product/domain/StockMovementType.java` (enum IN, OUT)
- `backend/src/main/java/com/creditflow/product/repository/StockMovementRepository.java` (findByProductIdOrderByOccurredAtDesc)
- `backend/src/main/java/com/creditflow/product/dto/StockMovementResponse.java`
- `backend/src/main/java/com/creditflow/product/service/ProductService.java` — modifié : ajout de increaseStock(Product, int, sourceType, sourceId), decreaseStock étendu pour écrire un StockMovement OUT ; nouvelle dépendance StockMovementRepository
- `backend/src/main/java/com/creditflow/product/web/ProductController.java` — modifié : nouvel endpoint GET /api/products/{id}/stock-movements
- `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` — inchangé dans sa logique (l'appel existant productService.decreaseStock(product, 1) ligne ~179 suffit ; le mouvement OUT est désormais tracé automatiquement à l'intérieur de decreaseStock)

Migration Flyway :
- `backend/src/main/resources/db/migration/V8__suppliers_stock_receptions.sql` (tables suppliers, stock_receptions, stock_reception_lines, stock_movements)

Frontend :
- `frontend/src/types.ts` — ajout Supplier, SupplierPayload, StockReception, StockReceptionPayload, StockMovement, StockMovementType
- `frontend/src/api/endpoints.ts` — ajout suppliersApi (list/select/create/update/remove) et stockReceptionsApi (list/create), ajout de productsApi.stockMovements(id)
- `frontend/src/pages/SuppliersPage.tsx` — CRUD fournisseurs, calqué sur CustomersPage.tsx
- `frontend/src/pages/StockReceptionsPage.tsx` — formulaire de réception (choix fournisseur + lignes produit/quantité) et historique des réceptions
- `frontend/src/components/StockMovementsDialog.tsx` — dialog listant les mouvements d'un produit (entrées/sorties), ouvert depuis une nouvelle action sur les lignes de ProductsPage.tsx
- `frontend/src/pages/ProductsPage.tsx` — modifié : ajout d'un bouton/icône par ligne pour ouvrir StockMovementsDialog
- `frontend/src/App.tsx` — routes /fournisseurs et /achats (ou /receptions)
- `frontend/src/components/AppLayout.tsx` — ajout des entrées de navigation correspondantes dans NAV_ITEMS

## Décisions clés

- Numéro de migration V8, pas V6/V7 : les branches non mergées bolt/issue-6-signature-electronique-piece-jointe et bolt/issue-7-garant-caution-contrat réservent déjà respectivement V6__sale_attachments.sql et V7__credit_sale_guarantor.sql (vérifié via git ls-tree sur origin/...). Prendre V6 ou V7 ici recréerait la collision déjà corrigée par le commit 755a526. On utilise donc V8__suppliers_stock_receptions.sql.
- Pas d'entité « commande d'achat » séparée : une seule entité StockReception (+ lignes) couvre à la fois la commande et la réception, créée et validée en un seul appel POST /api/stock-receptions. Aucun état brouillon/validé n'est modélisé car aucun critère d'acceptation ne l'exige.
- StockMovement vit dans le module product, pas supplier : le stock appartient à Product, et le module sale en dépend déjà. Placer l'historique côté product évite une dépendance circulaire supplier/sale et centralise la traçabilité au même endroit que stock lui-même.
- Écriture du mouvement centralisée dans ProductService.increaseStock/decreaseStock plutôt que dans les appelants (StockReceptionService, CreditSaleService) : garantit qu'aucune variation de stock ne peut être créée sans laisser de trace, cohérent avec le critère d'acceptation sur l'historique.
- Historique exposé en List<StockMovementResponse> non paginé via GET /api/products/{id}/stock-movements, à l'image de AuditLogController.list — cohérent avec les conventions existantes plutôt qu'une nouvelle page PageResponse.
- Pas de coût unitaire ni de champ prix sur les lignes de réception : le ticket exclut explicitement la gestion des paiements fournisseurs ; ajouter un unitCost ouvrirait la porte à une logique de valorisation de stock non demandée.
- Autorisations : SupplierController et StockReceptionController suivent le même schéma que ProductController — lecture ouverte à tout utilisateur authentifié, création/modification/suppression restreintes à ADMIN via @PreAuthorize (seul rôle « gérant » disponible dans Role actuellement : ADMIN, SELLER).

## Risques / points d'attention

- ProductService.decreaseStock n'est actuellement appelé que si product.getStock() > 0 (CreditSaleService ligne ~178) : une vente sur un produit déjà à 0 ne décrémente pas et donc n'écrira pas de mouvement OUT — comportement préexistant à ne pas modifier, mais à documenter pour éviter toute confusion côté spec/tests.
- L'annulation d'une vente (CreditSaleService.cancel) ne restaure actuellement pas le stock ; ce ticket n'en parle pas et ne doit pas introduire cette restauration en aval (garder le scope serré), mais le spec-writer doit vérifier qu'aucun critère d'acceptation ne le sous-entend implicitement.
- Product n'a pas de verrou optimiste au-delà de Auditable : des réceptions concurrentes sur le même produit peuvent créer une race sur stock (lecture-modification-écriture). Pas de nouveau risque par rapport à l'existant (ProductService gère déjà decreaseStock ainsi), mais à surveiller si le volume de réceptions simultanées augmente.
- Les branches #6 et #7 ajoutent potentiellement des colonnes/tables qui seront mergées avant ce ticket ; si V8 est déjà pris au moment du merge de cette PR, il faudra renuméroter (même type de collision que le commit 755a526). Le codeur/reviewer doit vérifier l'état de master avant de merger.
- Aucune contrainte de format n'existe pour les futurs champs phone/email du fournisseur au-delà de la validation Bean Validation standard — rester cohérent avec CustomerRequest (@Size, pas de regex stricte sur téléphone).

## Hors périmètre

- Gestion des paiements fournisseurs (facture, échéance de paiement, solde fournisseur).
- Workflow de commande d'achat avec statuts (brouillon, envoyée, partiellement reçue, etc.).
- Restauration automatique du stock à l'annulation d'une vente.
- Coût d'achat / valorisation de stock (CUMP, FIFO, marge par produit).
- Nouveau rôle « gérant » distinct d'ADMIN/SELLER dans le système d'autorisation.
