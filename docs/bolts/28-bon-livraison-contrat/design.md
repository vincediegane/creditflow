# Design #28 - Bon de livraison pour un contrat de vente a credit

## Approche

On ajoute un troisieme generateur PDF, DeliveryNoteGenerator, par symetrie
stricte avec InvoiceGenerator (#27) et PaymentReceiptGenerator : meme
package com.creditflow.sale.export, meme bibliotheque OpenPDF/lowagie,
meme format A5, meme style de mise en page (@Component sans etat, injecte
AppProperties, produit un byte[]). Contrairement a InvoiceGenerator, il
ne prend que CreditSale en entree (pas la liste des Installment :
aucun echeancier sur ce document). L'endpoint est ajoute sur SaleController
(GET /api/sales/{id}/delivery-note), qui delegue a une methode
deliveryNote(Long id) de CreditSaleService, meme decoupage que
invoice(Long id). Le prix de cette symetrie : un troisieme fichier qui
duplique encore les utilitaires de mise en page PDF (money, row, couleurs
HEADER/SOFT, DATE) sans extraction commune -- accepte pour un P2 a faible
surface, comme deja assume dans le design #27.

## Fichiers/modules impactes

Backend :
- backend/src/main/java/com/creditflow/sale/export/DeliveryNoteGenerator.java (nouveau) -- generation PDF, calque sur InvoiceGenerator/PaymentReceiptGenerator.
- backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java -- nouvelle methode deliveryNote(Long id) retournant un record DeliveryNote(String fileName, byte[] content), reutilisant getEntity(id) (controle d'acces boutique deja en place), sur le modele exact de invoice(Long id).
- backend/src/main/java/com/creditflow/sale/web/SaleController.java -- nouvel endpoint GET /api/sales/{id}/delivery-note retournant ResponseEntity<byte[]>, meme pattern que /invoice (Content-Disposition: attachment, MediaType.APPLICATION_PDF).
- Test : backend/src/test/java/com/creditflow/sale/export/DeliveryNoteGeneratorTest.java (nouveau, miroir de InvoiceGeneratorTest.java).
- backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java -- etendre avec un cas sur /delivery-note (acces inter-boutique refuse), sur le modele des cas deja presents pour /invoice.

Frontend :
- frontend/src/api/endpoints.ts -- ajouter salesApi.downloadDeliveryNote(id), meme forme que downloadInvoice (api.get('/sales/{id}/delivery-note', { responseType: 'blob' }) + downloadBlob + filenameFromHeaders).
- frontend/src/pages/SaleDetailPage.tsx -- bouton "Telecharger le bon de livraison" dans le bloc d'actions du PageHeader, a cote du bouton facture existant (icone type LocalShippingOutlined, a importer). Egalement : ajout d'un point d'entree minimal dans le bloc "Pieces jointes" pour permettre l'upload du bon signe (voir Decisions cles) -- reutilise le pattern deja en place pour "Ajouter une piece d'identite" (input file cache + bouton), avec type OTHER.

Pas de nouveau fichier de migration Flyway. Pas de modification de
SaleAttachment.java / SaleAttachmentType.java.

## Decisions cles

- Numerotation : derivee, non persistee, format BL-<annee demarrage
  contrat>-<id contrat sur 5 chiffres> (ex. BL-2026-00042), meme schema
  que invoiceNumber (FAC-...) et receiptNumber (REC-...). Meme
  justification que #27 : sale.getId() est deja unique tous shops
  confondus, pas besoin d'un compteur dedie.
- Distinction de contenu avec la facture (critere d'acceptation #4) :
  le bon de livraison n'affiche aucune donnee financiere du detail de
  facture -- pas de totalPrice, downPayment, financedAmount,
  monthlyAmount, remainingAmount, ni d'echeancier (Installment). Il se
  limite a : boutique, reference contrat, client (nom, telephone), produit,
  date de livraison, et deux zones de signature. La facture (#27) reste le
  seul document a montrer le detail financier et l'echeancier ; le recu de
  paiement (PaymentReceiptGenerator) reste le seul a montrer un montant
  encaisse. Les trois documents ne se recouvrent donc pas.
- Quantite : ni CreditSale ni Product n'exposent de notion de quantite
  (Product n'a que name/category/stock/description ; CreditSale relie un
  contrat a un seul Product). Decision : le contrat couvre implicitement 1
  unite du produit ; le PDF affiche "Quantite livree : 1" en dur plutot que
  d'introduire un champ quantite (changement de modele de donnees hors
  perimetre du ticket, qui ne le demande pas).
- Date de livraison : proxy = sale.getStartDate() (date de demarrage du
  contrat), comme suggere dans le ticket -- il n'existe aucune etape
  "livraison effective" distincte de la creation du contrat dans le parcours
  actuel (CreditSaleService.create). Ne pas introduire de nouveau champ ni
  de nouvelle transition de statut pour dissocier "contrat cree" de
  "produit livre" : hors perimetre (voir plus bas).
- Identite boutique dans le PDF : par coherence avec InvoiceGenerator et
  PaymentReceiptGenerator (qui utilisent tous deux
  AppProperties.getShop().getName(), config globale, pas l'entite Shop
  liee a la vente), le bon de livraison reprend le meme en-tete. Ajout
  cependant d'une ligne "Adresse" issue de sale.getShop().getAddress()
  (entite Shop, colonne nullable) dans le corps du document : pertinente
  pour un document de remise physique, absente des deux autres documents,
  ne cree donc pas de doublon. A omettre si null/vide.
- Zones de signature : deux zones (client + livreur/vendeur) cote a cote,
  contre une seule ("Signature du vendeur") sur le recu de paiement.
  Faisabilite : identique au recu -- pas de veritable capture de signature
  dans le PDF, seulement un PdfPTable a 2 colonnes avec libelle + espace
  blanc/ligne de soulignement (Rectangle.BOTTOM), meme technique que les
  lignes row() deja utilisees. Aucune dependance nouvelle (pas de signature
  electronique embarquee dans le PDF -- ca, c'est le role du
  SignaturePad/SaleAttachmentType.SIGNATURE existant, #6, deja separe).
- Rattachement du document signe (critere d'acceptation #3) :
  SaleAttachmentType (ID_DOCUMENT, SIGNATURE, OTHER) et l'endpoint
  POST /api/sales/{id}/attachments sont deja generiques (type + fichier en
  multipart/form-data) -- aucune modification backend necessaire, la
  colonne sale_attachments.type est un VARCHAR(20) sans contrainte CHECK
  (V6__sale_attachments.sql). Cote frontend en revanche, SaleDetailPage.tsx
  n'expose aujourd'hui que deux boutons dedies (ID_DOCUMENT et SIGNATURE,
  via SignaturePad) -- aucun point d'entree UI n'existe pour uploader un
  fichier de type OTHER. Decision : reutiliser OTHER (deja dans l'enum et
  deja affiche "Autre" dans ATTACHMENT_TYPE_LABELS) plutot que d'ajouter
  une valeur d'enum dediee (ex. DELIVERY_NOTE), et ajouter un troisieme
  bouton minimal "Joindre un document" dans le bloc Pieces jointes, sur le
  meme pattern que le bouton "Ajouter une piece d'identite" (input file
  cache + bouton), pour que le critere d'acceptation #3 soit reellement
  executable depuis l'UI et pas seulement possible via l'API. Alternative
  rejetee : ajouter un type d'enum dedie -- ne coute pas de migration
  (colonne non contrainte) mais ajoute un libelle/mapping supplementaire
  pour un gain marginal face a "Autre", qui suffit a distinguer le document
  apres coup au vu de son nom de fichier original conserve
  (originalFilename).

## Risques / points d'attention

- Ambiguite du bouton OTHER generique : en reutilisant OTHER, un bon de
  livraison signe scanne apparaitra sous le libelle generique "Autre" dans
  la grille de pieces jointes, au meme titre que n'importe quel autre
  fichier deja ou futur attache sous ce type -- pas de distinction visuelle
  forte. A trancher explicitement par le spec-writer (accepter "Autre" tel
  quel vs ajouter un libelle plus specifique cote UI uniquement, sans
  toucher a l'enum backend).
- Statut du contrat : comme pour la facture (#27), le ticket ne demande pas
  d'exclure les contrats CANCELLED/COMPLETED. Decision a repliquer :
  l'endpoint reste accessible quel que soit SaleStatus, aucun filtre a
  ajouter cote service.
- Permissions / multi-boutique : reutiliser CreditSaleService.getEntity(id)
  pour deliveryNote() garantit le meme controle d'acces
  (currentShopContext.assertAccessible, 404 sinon) que les autres endpoints
  du contrat -- ne pas recreer de verification separee.
- Confusion avec le bon de reception fournisseur : StockReceptionController
  (backend/src/main/java/com/creditflow/supplier/web/StockReceptionController.java,
  #8) couvre un flux inverse (entree de stock depuis un fournisseur). Aucun
  chevauchement de code attendu (modules supplier vs sale totalement
  separes), mais le nommage doit rester sans ambiguite dans le PDF genere
  (titre "BON DE LIVRAISON" cote client, jamais "bon de reception") et dans
  les libelles UI/commit pour eviter toute confusion au codage.
- Duplication de mise en page PDF : troisieme fichier a partager des
  utilitaires quasi identiques avec InvoiceGenerator et
  PaymentReceiptGenerator. Dette deja actee en #27 comme "a surveiller si
  un 3e document PDF apparait" -- c'est desormais le cas ; le spec-writer
  peut juger si une factorisation minimale (ex. classe utilitaire pour
  money/row/couleurs) devient pertinente ici, ou si on reste sur la meme
  duplication assumee que les deux precedents (recommande pour rester dans
  le perimetre P2 du ticket).
- Adresse boutique potentiellement vide : Shop.address est nullable
  (aucune contrainte NOT NULL identifiee dans les migrations shop) -- gerer
  l'absence proprement (ligne omise) plutot que d'afficher "null" ou une
  chaine vide disgracieuse.

## Hors perimetre

- Pas de nouvelle etape "livraison effective" distincte de la creation du
  contrat (pas de nouveau champ deliveryDate, pas de nouvelle transition de
  statut) -- le ticket suggere cette dissociation au conditionnel ("si ces
  deux etapes sont dissociees dans le parcours"), or elles ne le sont pas
  actuellement ; sale.getStartDate() suffit comme proxy.
- Pas de notion de quantite multi-unites ou de contrat multi-produits --
  hors perimetre, le modele de donnees (CreditSale -> un seul Product)
  n'est pas modifie.
- Pas de nouvelle valeur d'enum SaleAttachmentType dediee au bon de
  livraison -- OTHER est reutilise (voir Decisions cles).
- Pas de generation/envoi automatique du bon de livraison (email, WhatsApp)
  a la creation du contrat -- telechargement a la demande uniquement, comme
  la facture et le recu.
- Pas de stockage persistant du PDF genere (pas de table dediee, pas
  d'historique de reemission) -- recalcule a chaque telechargement, a
  l'identique des deux autres documents.
- Pas de modification de StockReceptionController ni du flux de reception
  fournisseur (#8) -- flux distinct, deja livre, non concerne par ce
  ticket.
- Pas de correction de l'incoherence deja identifiee en #27 entre
  AppProperties.Shop (config globale) et l'entite Shop liee a la vente pour
  le nom de boutique -- reproduite telle quelle par coherence, non traitee
  ici.
