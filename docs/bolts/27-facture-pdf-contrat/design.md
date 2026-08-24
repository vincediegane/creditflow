# Design #27 - Facture PDF pour un contrat de vente a credit

## Approche

On ajoute un generateur PDF InvoiceGenerator par symetrie stricte avec
PaymentReceiptGenerator (meme bibliotheque OpenPDF/lowagie, meme format A5,
meme style de mise en page) : un @Component sans etat qui prend l'entite
CreditSale (+ ses Installment) et produit un byte[]. Il vit dans
com.creditflow.sale.export, package miroir de com.creditflow.payment.export,
car la facture est un document du module sale, pas payment. L'endpoint est
ajoute directement sur SaleController (GET /api/sales/{id}/invoice), qui
delegue a une methode invoice(Long saleId) de CreditSaleService, meme
decoupage que PaymentService.receipt(Long). Le prix de cette symetrie : on
duplique un peu de code de mise en page PDF (pas de classe abstraite commune
extraite) plutot que de refactorer PaymentReceiptGenerator pour la
factoriser -- un partage premature entre deux documents aux contenus assez
differents (l'un tourne vente, l'autre versement) couterait plus cher en
lisibilite qu'il ne ferait gagner en DRY, pour un P2 a faible surface.

Aucune migration Flyway n'est necessaire : la numerotation de facture est
derivee de CreditSale.id (identique au principe deja en place pour
CreditSale.reference et PaymentReceiptGenerator.receiptNumber), pas
persistee en tant que telle.

## Fichiers/modules impactes

Backend :
- backend/src/main/java/com/creditflow/sale/export/InvoiceGenerator.java (nouveau) -- generation PDF, calque sur PaymentReceiptGenerator.
- backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java -- nouvelle methode invoice(Long id) retournant un record Invoice(String fileName, byte[] content), reutilisant getEntity(id) (controle d'acces boutique deja en place) et sale.getInstallments() pour l'echeancier.
- backend/src/main/java/com/creditflow/sale/web/SaleController.java -- nouvel endpoint GET /api/sales/{id}/invoice retournant ResponseEntity<byte[]>, meme pattern que PaymentController.receipt (Content-Disposition: attachment, MediaType.APPLICATION_PDF).
- Test : backend/src/test/java/com/creditflow/sale/export/InvoiceGeneratorTest.java (nouveau, miroir de PaymentReceiptGeneratorTest.java).
- backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java -- etendre avec un cas sur /invoice (acces inter-boutique refuse), sur le modele des cas deja presents pour /detail.

Frontend :
- frontend/src/api/endpoints.ts -- ajouter salesApi.downloadInvoice(id), meme forme que paymentsApi.downloadReceipt (api.get('/sales/{id}/invoice', { responseType: 'blob' }) + downloadBlob + filenameFromHeaders).
- frontend/src/pages/SaleDetailPage.tsx -- bouton "Telecharger la facture" dans le bloc d'actions du PageHeader (a cote de "Generer la relance" / "Encaisser"), icone ReceiptLongIcon deja importee (deja utilisee pour le recu de paiement dans le tableau des versements) -- coherence visuelle avec l'existant.

Pas de nouveau fichier de migration Flyway.

## Decisions cles

- Numerotation : derivee, non persistee. Format retenu FAC-<annee demarrage
  contrat>-<id contrat sur 5 chiffres> (ex. FAC-2026-00042), meme schema que
  receiptNumber (REC-...) et buildReference (VC-...). Justification :
  CreditSale.reference porte deja une contrainte unique = true globale
  (toutes boutiques confondues, colonne credit_sales.reference dans
  V1__create_schema.sql / CreditSale.java ligne 48) -- donc sale.getId() est
  lui aussi unique tous shops confondus. Une sequence dediee par boutique en
  base ajouterait un etat a maintenir (compteur, migration, gestion de
  concurrence) pour un gain nul : pas besoin d'un numero demarrant a 1 par
  boutique, seulement d'un numero unique et tracable, ce que l'id contrat
  fournit deja sans ecriture supplementaire.
- Pas de generation a la creation du contrat : la facture est calculee a la
  demande (comme le recu), pas stockee en base ni sur disque. Le contenu
  reflete toujours l'etat courant du contrat au moment du telechargement.
- Identite boutique dans le PDF : PaymentReceiptGenerator utilise
  AppProperties.getShop() (nom + devise globaux de configuration), pas
  l'entite Shop liee a la vente (CreditSale.getShop(), qui a nom/adresse/
  telephone propres depuis V10__shops.sql). Pour rester coherent avec le
  recu existant (meme en-tete sur les deux documents remis au meme client)
  et ne pas complexifier ce ticket, InvoiceGenerator reproduit ce meme
  choix : properties.getShop().getName() / .getCurrency(). C'est un choix
  qui herite d'une limitation deja presente, pas une regression nouvelle
  (voir Risques).
- Contenu de l'echeancier : la facture doit "reprendre fidelement les
  donnees du contrat" (critere d'acceptation) -- contrairement au recu qui
  ne montre que la prochaine echeance, la facture liste l'integralite des
  Installment (numero, date, montant), comme le tableau deja affiche dans
  SaleDetailPage.tsx.
- Endpoint sans body / GET : conforme au pattern recu (GET .../receipt),
  pas de POST, pas de parametres -- la facture est entierement derivee du
  contrat.

## Risques / points d'attention

- Contrat annule ou solde : le ticket ne dit pas d'exclure ces cas. Decision
  retenue : l'endpoint reste accessible quel que soit SaleStatus (ACTIVE,
  COMPLETED, CANCELLED) -- un commercant doit pouvoir ressortir la facture
  d'un contrat solde ou annule (litige, comptabilite). Aucun filtre de
  statut a ajouter cote service ; a confirmer explicitement dans la spec
  pour eviter toute ambiguite au codeur.
- Permissions / multi-boutique : CreditSaleService.getEntity(id) applique
  deja currentShopContext.assertAccessible(sale.getShop().getId()) et
  renvoie ResourceNotFoundException (404) si la boutique n'est pas
  accessible -- reutiliser cette methode pour invoice() garantit
  automatiquement le meme comportement que les autres endpoints du contrat
  (/detail, /installments, /payments). Ne pas recreer un controle d'acces
  separe.
- Incoherence identite boutique heritee : le nom de boutique affiche vient
  de la config globale (app.shop.name), pas de l'entite Shop du contrat --
  potentiellement trompeur pour une installation multi-boutiques (le PDF
  affichera le meme nom de boutique quel que soit le shop reel du contrat).
  Defaut preexistant du recu de paiement, pas quelque chose que ce ticket
  doit corriger, mais le spec-writer doit trancher explicitement s'il faut
  reproduire ce choix tel quel (recommande, coherence entre les deux
  documents) ou utiliser sale.getShop() -- ce qui ferait diverger facture
  et recu visuellement.
- Duplication de mise en page PDF : InvoiceGenerator et
  PaymentReceiptGenerator partageront des utilitaires quasi identiques
  (money(), row(), couleurs HEADER/SOFT, DATE formatter). Accepte comme
  dette mineure pour ce ticket ; a surveiller si un 3e document PDF
  apparait.
- Nom de fichier telecharge : suivre le pattern de
  PaymentReceiptGenerator.fileName() (recu-<numero>-<date>.pdf) ->
  facture-<numero>-<datedujour>.pdf, coherent avec filenameFromHeaders deja
  utilise cote frontend pour tous les telechargements (recu, exports de
  rapports, modele CSV).

## Hors perimetre

- Pas de generation/envoi automatique de la facture (email, WhatsApp) au
  moment de la creation du contrat -- le ticket demande un telechargement a
  la demande depuis le detail du contrat, rien de plus.
- Pas de personnalisation du contenu de facture par boutique (logo,
  mentions legales specifiques) -- hors perimetre du ticket, non demande.
- Pas de correction de l'incoherence AppProperties.Shop vs entite Shop pour
  le recu de paiement existant -- seulement documentee comme risque herite,
  pas traitee ici.
- Pas de stockage persistant des factures generees (table invoices,
  historique, reemission identique) -- la facture est recalculee a chaque
  telechargement, a l'identique du recu de paiement.
- Pas de TVA/taxes -- le produit n'a pas cette notion (Product n'expose que
  cashPrice/creditPrice), et le ticket ne la mentionne pas.
