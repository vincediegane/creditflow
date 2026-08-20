# Spec -- #7 Garant/caution sur un contrat de credit

## Résumé

Ajout d'un garant optionnel (nom, téléphone, adresse, CNI en clair, snapshot sans nouvelle entité) sur un contrat de crédit, saisi à la création, affiché sur la fiche contrat, et retrouvable via la recherche globale/recherche de contrats.

## Tâches

- [ ] **Migration DB** -- `backend/src/main/resources/db/migration/V7__credit_sale_guarantor.sql` (nouveau) : ajoute les 4 colonnes nullable `guarantor_full_name`, `guarantor_phone`, `guarantor_address`, `guarantor_cni_number` sur `credit_sales`, + 2 index de recherche (voir Contrat technique).
- [ ] **Entité `CreditSale`** -- `backend/src/main/java/com/creditflow/sale/domain/CreditSale.java` : ajoute les 4 champs `@Column` nullable correspondants (`guarantorFullName`, `guarantorPhone`, `guarantorAddress`, `guarantorCniNumber`).
- [ ] **`CreateSaleRequest`** -- `backend/src/main/java/com/creditflow/sale/dto/CreateSaleRequest.java` : ajoute les 4 champs optionnels avec validations (`@Size`, `@Pattern`) + la règle de cohérence croisée nom/téléphone (voir Contrat technique et Écarts identifiés).
- [ ] **`SaleResponse`** -- `backend/src/main/java/com/creditflow/sale/dto/SaleResponse.java` : ajoute les 4 champs en fin de record (après `penaltyAmount`), tous nullable.
- [ ] **`SaleMapper`** -- `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java` : reporte les 4 champs de l'entité dans `new SaleResponse(...)` (méthode `toResponse(CreditSale, ...)`, fin de l'appel de constructeur).
- [ ] **`CreditSaleService.create()`** -- `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` : reprend les 4 champs de `CreateSaleRequest` dans `CreditSale.builder()`.
- [ ] **`SaleSpecifications.matches`** -- `backend/src/main/java/com/creditflow/sale/repository/SaleSpecifications.java` : étend le `cb.or(...)` existant avec un match sur `guarantorFullName` (like ignore case via `Specs.likeIgnoreCase` + `cb.coalesce(..., "")`) et `guarantorPhone` (`cb.like` brut, même convention que `customer.phone`, avec `coalesce`).
- [ ] **Test unitaire migration/entité** -- `backend/src/test/java/com/creditflow/sale/domain/CreditSaleTest.java` (nouveau si le fichier n'existe pas, sinon complété) : vérifie que `CreditSale.builder()` accepte les 4 champs garant et qu'ils sont `null` par défaut si non renseignés.
- [ ] **Test `CreateSaleRequest` validation** -- `backend/src/test/java/com/creditflow/sale/dto/CreateSaleRequestValidationTest.java` (nouveau) : couvre la règle de cohérence croisée nom/téléphone garant (voir Plan de tests).
- [ ] **Test `SaleSpecifications`** -- `backend/src/test/java/com/creditflow/sale/repository/SaleSpecificationsTest.java` (nouveau, sur le modèle de `backend/src/test/java/com/creditflow/common/repository/SpecsTest.java` ou en `@DataJpaTest`) : vérifie qu'une recherche par nom et par téléphone du garant retrouve le contrat.
- [ ] **Mise à jour test existant `SaleControllerSecurityTest`** -- `backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java` : les constructions positionnelles `new CreateSaleRequest(...)` et `new SaleResponse(...)` doivent être mises à jour pour inclure les 4 nouveaux paramètres (tous `null` acceptable, en fin de liste).
- [ ] **Vérification `CreditSaleServiceTest`** -- `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` : le `CreditSale.builder()` du `@BeforeEach` reste compilable tel quel (champs garant absents = `null` par défaut) ; ajouter un test `create()` qui vérifie la reprise des champs garant depuis `CreateSaleRequest` vers l'entité sauvegardée.
- [ ] **Types frontend** -- `frontend/src/types.ts` : ajoute à `Sale` les 4 champs optionnels garant (`guarantorFullName?`, `guarantorPhone?`, `guarantorAddress?`, `guarantorCniNumber?`) et à `CreateSalePayload` les mêmes 4 champs optionnels.
- [ ] **Formulaire `NewSalePage`** -- `frontend/src/pages/NewSalePage.tsx` : ajoute une section "Garant (optionnel)" dans `FormValues` (4 champs texte), un `Autocomplete` sur `customersQuery.data` pour pré-remplir depuis un client existant (sans persister de lien), champs éditables manuellement ensuite, et propage les 4 champs dans le payload envoyé à `createMutation` (voir Contrat technique pour le mapping des chaînes vides -> `undefined`).
- [ ] **Validation frontend garant** -- `frontend/src/pages/NewSalePage.tsx` : avant `createMutation.mutate(...)`, si `guarantorFullName` est renseigné sans `guarantorPhone` (ou l'inverse), afficher une erreur bloquante via `setError(...)` sans appeler l'API (miroir de la règle backend, voir Écarts identifiés).
- [ ] **Fiche contrat `SaleDetailPage`** -- `frontend/src/pages/SaleDetailPage.tsx` : ajoute un bloc "Garant" dans la carte "Résumé" (via le composant `Line` existant), affichant `guarantorFullName`, `guarantorPhone`, `guarantorAddress`, `guarantorCniNumber` (ceux non vides uniquement), rendu conditionnel sur `sale.guarantorFullName` non vide -- rien n'est affiché si absent.

## Contrat technique

### Migration SQL -- `V7__credit_sale_guarantor.sql`

```sql
ALTER TABLE credit_sales
    ADD COLUMN guarantor_full_name  VARCHAR(160),
    ADD COLUMN guarantor_phone      VARCHAR(30),
    ADD COLUMN guarantor_address    VARCHAR(255),
    ADD COLUMN guarantor_cni_number VARCHAR(50);

CREATE INDEX idx_credit_sales_guarantor_phone ON credit_sales (guarantor_phone);
CREATE INDEX idx_credit_sales_guarantor_name ON credit_sales (LOWER(guarantor_full_name));
```

Toutes les colonnes sont nullable, aucune contrainte `UNIQUE` ni `CHECK` (un même garant peut cautionner plusieurs contrats -- décision architecte confirmée).

`guarantor_full_name` est en `VARCHAR(160)` (et non 80 comme `customers.first_name`/`last_name`) car c'est un champ texte libre unique (pas de split prénom/nom), devant pouvoir contenir un nom complet.

### Entité `CreditSale.java`

```java
@Column(name = "guarantor_full_name", length = 160)
private String guarantorFullName;

@Column(name = "guarantor_phone", length = 30)
private String guarantorPhone;

@Column(name = "guarantor_address", length = 255)
private String guarantorAddress;

@Column(name = "guarantor_cni_number", length = 50)
private String guarantorCniNumber;
```

### `CreateSaleRequest.java`

Ajoute en fin de record (après `notes`) :

```java
@Size(max = 160, message = "Le nom du garant est trop long")
String guarantorFullName,

@Pattern(regexp = "^[0-9+\\-\\s()]{6,30}$", message = "Numero de telephone du garant invalide")
@Size(max = 30)
String guarantorPhone,

@Size(max = 255)
String guarantorAddress,

@Size(max = 50)
String guarantorCniNumber
```

Puis, dans le corps du record (compact ou méthode additionnelle), la règle de cohérence croisée tranchée ci-dessous (section Écarts identifiés) :

```java
@AssertTrue(message = "Le nom et le telephone du garant doivent etre renseignes ensemble")
public boolean isGuarantorConsistent() {
    boolean hasName = StringUtils.hasText(guarantorFullName);
    boolean hasPhone = StringUtils.hasText(guarantorPhone);
    return hasName == hasPhone;
}
```

Import requis : `org.springframework.util.StringUtils` et `jakarta.validation.constraints.AssertTrue`.

`guarantorAddress` et `guarantorCniNumber` restent libres même si nom/téléphone sont vides (aucune contrainte croisée sur eux).

### `SaleResponse.java`

Ajoute en fin de record (après `penaltyAmount`) :

```java
String guarantorFullName,
String guarantorPhone,
String guarantorAddress,
String guarantorCniNumber
```

### `SaleMapper.toResponse(CreditSale, ...)`

Ajoute en fin d'appel `new SaleResponse(...)` :

```java
sale.getGuarantorFullName(),
sale.getGuarantorPhone(),
sale.getGuarantorAddress(),
sale.getGuarantorCniNumber());
```

### `CreditSaleService.create()`

Ajoute au `CreditSale.builder()` :

```java
.guarantorFullName(blankToNull(request.guarantorFullName()))
.guarantorPhone(blankToNull(request.guarantorPhone()))
.guarantorAddress(blankToNull(request.guarantorAddress()))
.guarantorCniNumber(blankToNull(request.guarantorCniNumber()))
```

Ajouter une petite méthode privée utilitaire (ou réutiliser un helper existant si le codeur en trouve un équivalent) pour normaliser les chaînes vides envoyées par le frontend en `null` avant persistance :

```java
private String blankToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
}
```

### `SaleSpecifications.matches(String search)`

Étendre le `cb.or(...)` existant avec deux prédicats supplémentaires, sur le modèle exact de `CustomerSpecifications.matches` (colonnes nullable -> `cb.coalesce`) :

```java
Specs.likeIgnoreCase(cb, cb.coalesce(root.get("guarantorFullName"), ""), search),
cb.like(cb.coalesce(root.get("guarantorPhone"), ""), "%" + search.trim() + "%")
```

Le match téléphone garant suit la même convention que `customer.phone` dans ce fichier (comparaison brute, sans `lower`, car les téléphones ne contiennent pas de lettres).

### Frontend -- `types.ts`

```ts
export interface Sale {
  // ... champs existants inchangés
  guarantorFullName?: string;
  guarantorPhone?: string;
  guarantorAddress?: string;
  guarantorCniNumber?: string;
}

export interface CreateSalePayload {
  // ... champs existants inchangés
  guarantorFullName?: string;
  guarantorPhone?: string;
  guarantorAddress?: string;
  guarantorCniNumber?: string;
}
```

### Frontend -- `NewSalePage.tsx`

`FormValues` étendu avec `guarantorFullName: string`, `guarantorPhone: string`, `guarantorAddress: string`, `guarantorCniNumber: string` (valeurs par défaut `''`).

Section UI (nouvelle carte ou bloc dans la carte existante) :
- `Autocomplete` sur `customersQuery.data`, label "Choisir un client comme garant (optionnel)", `onChange` pré-remplit les 4 champs texte (`setValue('guarantorFullName', value.fullName)`, etc.) sans stocker d'`id`.
- 4 `TextField` en dessous, éditables manuellement à tout moment (y compris après pré-remplissage), avec labels "Nom du garant", "Téléphone du garant", "Adresse du garant", "N° CNI du garant".

Dans `submit`, avant `createMutation.mutate(...)` :

```ts
const guarantorName = form.guarantorFullName.trim();
const guarantorPhone = form.guarantorPhone.trim();
if (Boolean(guarantorName) !== Boolean(guarantorPhone)) {
  setError('Le nom et le téléphone du garant doivent être renseignés ensemble');
  return;
}
```

Puis dans le payload :

```ts
guarantorFullName: guarantorName || undefined,
guarantorPhone: guarantorPhone || undefined,
guarantorAddress: form.guarantorAddress.trim() || undefined,
guarantorCniNumber: form.guarantorCniNumber.trim() || undefined,
```

### Frontend -- `SaleDetailPage.tsx`

Dans la `Stack` de la carte "Résumé", après le bloc `Échéances`/`Retard` (ou dans un sous-bloc dédié précédé d'un `Divider`) :

```tsx
{sale.guarantorFullName && (
  <>
    <Divider sx={{ my: 1 }} />
    <Typography variant="subtitle2">Garant</Typography>
    <Line label="Nom" value={sale.guarantorFullName} />
    {sale.guarantorPhone && <Line label="Téléphone" value={sale.guarantorPhone} />}
    {sale.guarantorAddress && <Line label="Adresse" value={sale.guarantorAddress} />}
    {sale.guarantorCniNumber && <Line label="N° CNI" value={sale.guarantorCniNumber} />}
  </>
)}
```

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Un vendeur peut renseigner un garant optionnel à la création d'un contrat | **Backend** : test `CreditSaleServiceTest` -- `create()` avec un `CreateSaleRequest` incluant les 4 champs garant renseignés vérifie que l'entité sauvegardée porte ces valeurs ; un second cas avec les 4 champs `null`/absents vérifie que la création réussit sans garant (non-régression). **Backend** : `CreateSaleRequestValidationTest` -- un `CreateSaleRequest` avec `guarantorFullName` seul (sans `guarantorPhone`) échoue la validation Bean Validation (`isGuarantorConsistent` = false) ; un cas avec les deux renseignés passe ; un cas avec les deux absents passe. **Frontend** : test manuel -- créer un contrat en saisissant un garant (via Autocomplete puis via saisie libre), vérifier la création réussie et la redirection vers la fiche contrat. **Frontend** : test manuel -- saisir uniquement le nom du garant sans téléphone, vérifier que le message d'erreur bloque l'envoi. |
| La fiche contrat affiche les coordonnées du garant s'il existe | **Backend** : test `SaleMapper`/`CreditSaleServiceTest` (`findDetail`) vérifie que `SaleResponse` contient les 4 champs garant reportés depuis l'entité. **Frontend** : test manuel -- ouvrir la fiche d'un contrat créé avec garant, vérifier l'affichage du bloc "Garant" avec les 4 valeurs ; ouvrir la fiche d'un contrat sans garant, vérifier l'absence totale du bloc "Garant". |
| La recherche globale retrouve un contrat via le nom/téléphone du garant | **Backend** : test `SaleSpecificationsTest` (nouveau, `@DataJpaTest` ou test d'intégration sur repository) -- persiste un `CreditSale` avec `guarantorFullName="Moussa Kane"` et `guarantorPhone="770001122"`, puis vérifie que `saleRepository.findAll(SaleSpecifications.matches("Moussa"))` et `SaleSpecifications.matches("770001122")` retournent bien ce contrat, et qu'une recherche sans rapport ne le retourne pas. **Backend** : test d'intégration `GlobalSearchService`/`CreditSaleService.search()` (recherche par nom garant partiel, insensible à la casse, ex. "moussa") confirme la remontée du contrat -- aucune modification de `GlobalSearchService` n'étant nécessaire (il délègue déjà à `CreditSaleService.search`), ce test valide simplement la non-régression du chaînage. **Frontend** : test manuel -- utiliser la recherche globale avec le nom puis le téléphone du garant d'un contrat existant, vérifier que le contrat apparaît dans les résultats. |

## Écarts identifiés

- **Validation croisée nom/téléphone garant tranchée** : le design laissait ce point ouvert au spec-writer. Décision : **nom et téléphone du garant doivent être renseignés ensemble ou tous les deux absents** (règle symétrique, via `@AssertTrue` côté backend et garde-fou côté frontend). Justification : le ticket vise explicitement "sécuriser le recouvrement" (user story) et le 3e critère d'acceptation exige de pouvoir retrouver le contrat "via le nom/téléphone du garant" -- un garant identifié uniquement par un nom (sans téléphone joignable) ne sert ni le recouvrement ni une recherche fiable par téléphone, et un téléphone sans nom associé est inexploitable à l'affichage (fiche contrat). L'adresse et le CNI restent facultatifs même si nom+téléphone sont renseignés, car aucun critère d'acceptation n'en dépend.
- **Aucun autre écart** entre `design.md` et le ticket #7 : les 3 critères d'acceptation sont couverts par les tâches ci-dessus (saisie optionnelle à la création, affichage sur la fiche, recherche globale/contrats). Le numéro de migration `V7` est confirmé comme prochain numéro disponible dans `backend/src/main/resources/db/migration/` sur cette branche (dernier fichier existant : `V5__credit_sale_interest.sql` ; `V6` est réservé par la PR #6 non mergée, décision du design maintenue).
- **Point de vigilance signalé mais non bloquant** : deux constructions positionnelles existantes (`SaleControllerSecurityTest.createRequest()` et `.response()`) doivent être mises à jour pour rester compilables après l'ajout des 4 champs -- ajouté explicitement comme tâche ci-dessus pour éviter un oubli en phase de codage.
