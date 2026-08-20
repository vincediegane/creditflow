# Design -- #7 Garant/caution sur un contrat de credit

## Approche

On ajoute au contrat (`credit_sales`) un jeu de colonnes optionnelles decrivant le garant
en clair (nom, telephone, adresse, CNI), sans nouvelle table ni FK obligatoire vers
`customers`. C'est un instantane ("snapshot"), pas une relation : le garant n'a pas besoin
d'exister comme `Customer` pour etre renseigne, et si le vendeur choisit un client existant
dans l'UI, on se contente de pre-remplir les champs a partir de sa fiche (le lien n'est pas
persiste). Ce choix evite une nouvelle entite/repository/mapper pour un besoin a un seul
usage (un garant = un contrat, pas de reutilisation ni de fiche garant dediee demandee par
le ticket), au prix de ne pas pouvoir dedupliquer un meme garant entre plusieurs contrats ni
retrouver "tous les contrats garantis par X" autrement que par un match texte nom/telephone.
La recherche (`SaleSpecifications.matches`) et l'affichage (`SaleResponse`/fiche contrat)
sont etendus en suivant exactement les conventions deja en place pour `customer`/`product`.

## Fichiers/modules impactes

Backend :
- `backend/src/main/resources/db/migration/V7__credit_sale_guarantor.sql` (nouveau) -- `ALTER TABLE credit_sales ADD COLUMN guarantor_full_name, guarantor_phone, guarantor_address, guarantor_cni_number` (tous nullable), + index sur nom/telephone pour la recherche. Voir "Decisions cles" pour le choix du numero V7.
- `backend/src/main/java/com/creditflow/sale/domain/CreditSale.java` -- 4 nouveaux champs `@Column` nullable (`guarantorFullName`, `guarantorPhone`, `guarantorAddress`, `guarantorCniNumber`), pas de nouvelle relation JPA.
- `backend/src/main/java/com/creditflow/sale/dto/CreateSaleRequest.java` -- 4 champs optionnels correspondants, avec `@Size` alignes sur `CustomerRequest` (phone `@Pattern` identique, cni `@Size(max=50)`, address `@Size(max=255)`).
- `backend/src/main/java/com/creditflow/sale/dto/SaleResponse.java` -- 4 champs supplementaires en fin de record (nullable), pour l'affichage sur la fiche contrat.
- `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java` -- reporter les 4 champs de l'entite vers `SaleResponse` dans `toResponse(CreditSale, ...)`.
- `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` -- methode `create()` : reprendre les 4 champs du `CreateSaleRequest` dans le `CreditSale.builder()`. Validation metier legere (voir Decisions cles).
- `backend/src/main/java/com/creditflow/sale/repository/SaleSpecifications.java` -- etendre `matches(String search)` avec un `OR` sur `guarantorFullName` (like ignore case) et `guarantorPhone` (like), avec `cb.coalesce(..., "")` pour gerer les colonnes nulles comme fait deja pour `cniNumber`/`profession` dans `CustomerSpecifications`.
- Aucun changement requis dans `GlobalSearchService` : il delegue deja a `CreditSaleService.search(...)` qui utilisera la spec mise a jour -- la recherche globale par nom/telephone du garant fonctionne automatiquement.

Frontend :
- `frontend/src/types.ts` -- `Sale` (4 champs optionnels garant) et `CreateSalePayload` (idem).
- `frontend/src/pages/NewSalePage.tsx` -- section "Garant (optionnel)" dans le formulaire : soit un `Autocomplete` sur `customersQuery.data` (comme pour le client) qui pre-remplit les 4 champs texte, soit saisie manuelle libre. Champs ajoutes a `FormValues` et au payload envoye a `createMutation`.
- `frontend/src/pages/SaleDetailPage.tsx` -- bloc "Garant" dans la carte "Resume" (via le composant `Line` existant), affiche uniquement si `sale.guarantorFullName` est renseigne.
- Pas de changement dans `SalesPage.tsx` ni dans la recherche globale : elles consomment deja `salesApi.search`/`SaleResponse` sans liste de colonnes figee a modifier pour le filtrage cote serveur.

## Decisions cles

- **Pas de table `guarantors` ni de FK vers `customers`** : le ticket ne demande ni fiche garant reutilisable, ni suivi "quels contrats X garantit-il". Un snapshot texte sur `credit_sales` suffit aux 3 criteres d'acceptation (saisie optionnelle, affichage, recherche) et reste coherent avec l'existant "pas de garant = comportement actuel inchange" -- 4 colonnes nullable n'affectent aucune ligne existante.
- **Numero de migration : V7, pas V6.** Etat reel du dossier sur cette branche : V1 a V5 seulement (le dernier merge en master est `V5__credit_sale_interest.sql`, issu du fix de collision `755a526`). La branche `bolt/issue-6-signature-electronique-piece-jointe` (PR #6, non mergee) contient deja `V6__sale_attachments.sql` sur son propre historique. Comme le precedent de collision (V3) montre que deux bolts paralleles peuvent reclamer le meme numero sans le savoir, et qu'ici on sait que V6 est deja pris par une branche non mergee mais existante, on prend directement V7 pour eviter une collision certaine a la fusion -- au prix d'un trou de version si #6 est abandonnee (sans consequence, Flyway n'exige pas de continuite stricte).
- **Aucune validation croisee forte entre les 4 champs garant** : si un seul champ est renseigne (ex. juste le nom), on l'accepte tel quel plutot que d'imposer nom+telephone obligatoires ensemble. Simplicite cote API ; le spec-writer peut resserrer cette regle si le produit l'exige (ex. "si un garant est renseigne, nom et telephone sont obligatoires").
- **Pas de contrainte d'unicite ni de validation stricte sur `guarantor_phone`** (contrairement a `customers.phone` qui est unique) : un meme garant peut cautionner plusieurs contrats, et le garant n'est pas un client de la boutique.
- **Index de recherche** : `CREATE INDEX idx_credit_sales_guarantor_phone ON credit_sales (guarantor_phone)` et un index sur `LOWER(guarantor_full_name)`, sur le modele de `idx_customers_last_name`/`idx_customers_phone` dans `V1__create_schema.sql`, pour garder les performances de recherche coherentes avec le reste du schema.

## Risques / points d'attention

- **Collision de migration avec la PR #6** : si #6 est mergee avant #7, `V6__sale_attachments.sql` arrivera sur master avant que cette branche ne merge son propre `V7`. Comme choisi ci-dessus (V7 direct), il n'y a pas de collision dans ce sens ; le risque inverse (cette branche mergee en premier, #6 devant ensuite retomber sur V6 ou etre renumerotee si elle prend V7 elle aussi) reste a surveiller par l'orchestrateur au moment du merge -- pas d'action possible ici puisque #6 n'est pas dans le perimetre de cette branche.
- **`CreditSale` est construit avec `@Builder`/`@AllArgsConstructor`** : ajouter 4 champs a l'entite change la signature du constructeur "tout argument" genere par Lombok. Aucun `new CreditSale(` positionnel n'existe actuellement dans le code (seul `CreditSale.builder()` est utilise), donc impact limite -- a reverifier apres codage si des tests instancient l'entite directement.
- **`SaleResponse` est un `record` positionnel** : ajouter des champs en fin de liste evite de casser les appels existants dans `SaleMapper`. Le frontend consomme du JSON nomme, donc pas d'impact cote client.
- **Aucun lien avec la fonctionnalite "document contractuel exporte" citee dans le ticket** : a ce jour, sur cette branche (issue de master, sans #6), il n'existe aucun module de generation de contrat PDF/document (seuls `PaymentReceiptGenerator` pour les recus et `PdfReportExporter` pour les rapports existent). Le ticket #7 lie explicitement cet aspect a "#6 si les deux sont livrees ensemble" -- non applicable ici tant que #6 n'est pas mergee.
- **Coherence des tests existants** : `CreditSaleService` et `SaleMapper` ont probablement des tests qui construisent des `CreditSale`/`CreateSaleRequest` par builder -- les nouveaux champs etant optionnels avec valeur par defaut `null`, aucun test existant ne devrait casser, mais le codeur doit verifier les tests dans `backend/src/test/java/com/creditflow/sale/`.

## Hors perimetre

- Pas de nouvelle entite/table `Guarantor` reutilisable entre contrats.
- Pas de FK/lien persistant vers `customers` pour le garant (juste un pre-remplissage UI ponctuel si le vendeur choisit un client existant).
- Pas de generation de document contractuel PDF ni d'integration avec la PR #6 (signature electronique / pieces jointes) -- non mergee, hors perimetre de cette branche.
- Pas de validation metier avancee (ex. garant obligatoire au-dela d'un certain montant, garant distinct du client) : le ticket demande un champ optionnel, sans regle de seuil.
- Pas de page ou d'ecran dedie a la gestion des garants (liste, edition independante) : uniquement saisie/affichage dans le cycle de vie du contrat.
