# Review — #36 Multi-tenant 3/10 — Propagation Customer/Product (passage 2)

APPROVE

## Contexte

Second passage de review. Le premier passage (commit 7c3c853) avait rendu CHANGES_REQUESTED avec un
Finding 1 bloquant : le test `LegacyImportServiceTest.doesNotReuseProductWithSameNameFromAnotherShop`
ne stubbait jamais `findFirstByNameIgnoreCaseAndShop_Id("Tecno Spark", 2L)`, donc une regression de
scoping (mauvais shopId interroge) serait passee inapercue -- Mockito renvoie `Optional.empty()` par
defaut pour tout appel non stubbe d'une methode retournant `Optional<T>`.

Le codeur a repondu avec le commit `bce592d`, qui corrige precisement ce point.

## Criteres d'acceptation

| Critere | Statut |
|---|---|
| Aucun endpoint client/produit n'expose de donnee d'une autre organisation | Couvert -- le filtrage `inOrganization` / jointure `shop.organization.id = :organizationId` reste correctement cable (inchange depuis le passage 1) sur search/quickSearch/findAllForSelect/categories (Customer + Product), et la fuite de `LegacyImportService.resolveProduct` est corrigee cote production. Le test `doesNotReuseProductWithSameNameFromAnotherShop`, desormais correctement instrumente, prouve reellement la non-reutilisation cross-boutique (verifie ci-dessous par cassure controlee du code de production). |
| Instance mono-tenant : comportement strictement identique a aujourd'hui | Couvert -- inchange depuis le passage 1. `inOrganization(null)` retourne `null` (aucun predicat ajoute), toute la suite existante reste verte. |

## Verification du Finding 1 (bloquant, passage 1)

Diff du commit `bce592d` relu ligne a ligne
(`backend/src/test/java/com/creditflow/dataimport/service/LegacyImportServiceTest.java`,
lignes 132-155) :

- Le test stubbe desormais explicitement
  `productRepository.findFirstByNameIgnoreCaseAndShop_Id("Tecno Spark", 2L) -> Optional.of(existingInOtherShop)`,
  en plus du stub existant pour `shopId=1L -> Optional.empty()`.
- Apres l'appel a `importLegacySales`, le test verifie
  `verify(productRepository, atLeastOnce()).findFirstByNameIgnoreCaseAndShop_Id(eq("Tecno Spark"), eq(1L))`
  et `verify(productRepository, never()).findFirstByNameIgnoreCaseAndShop_Id(eq("Tecno Spark"), eq(2L))`.
- L'assertion finale a aussi ete durcie : `assertThat(productCaptor.getValue()).isNotEqualTo(existingInOtherShop)`
  (comparaison d'objet, plus robuste que l'ancienne comparaison d'id qui etait vraie par construction
  a cause du mock `save`).

Je n'ai pas fait confiance au rapport du codeur affirmant avoir verifie la cassure du scenario -- je l'ai
reproduite moi-meme :

1. Modification temporaire de `LegacyImportService.resolveProduct` (ligne 184) pour interroger le
   mauvais shop : `findProductByName(row.productName(), targetShopId)` -> `findProductByName(row.productName(), 2L)`.
2. `mvn -DskipITs -Dtest=LegacyImportServiceTest#doesNotReuseProductWithSameNameFromAnotherShop test`
   -> **echec confirme** :
   `NeverWantedButInvoked: productRepository.findFirstByNameIgnoreCaseAndShop_Id("Tecno Spark", 2L)` --
   invoque a `LegacyImportService.findProductByName(LegacyImportService.java:198)` avec les arguments
   `[Tecno Spark, 2]`. Le test detecte donc reellement une regression de scoping shop.
3. `git checkout -- backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java`
   pour annuler la modification. `git status --short` confirme un working tree propre apres restauration
   (aucune modification residuelle de code de production).

Le Finding 1 est donc bien resolu : le test protege desormais reellement contre une regression sur le
chemin `resolveProduct`, symetriquement au raisonnement deja valide pour `resolveCustomer`.

## Perimetre du correctif (bce592d)

`git diff 7c3c853..HEAD` (= le seul commit `bce592d`) touche exactement deux fichiers, tous deux des
tests :

- `backend/src/test/java/com/creditflow/dataimport/service/LegacyImportServiceTest.java` (+10/-1)
- `backend/src/test/java/com/creditflow/common/security/CurrentShopContextTest.java` (+11)

Aucun fichier de production, aucune migration, aucun autre fichier de test n'est touche. Pas de
regression de perimetre introduite par la correction.

Le second changement (`CurrentShopContextTest.currentOrganizationIdReturnsAuthenticatedUserOrganization`)
traite le Finding 2 du passage 1, qui etait explicitement non bloquant. Le test ajoute exerce reellement
l'implementation (`currentUser().getOrganization().getId()`) via un utilisateur authentifie mocke au
niveau `SecurityContext`/`UserRepository`, plutot qu'un mock direct de `CurrentShopContext` -- couverture
correcte, meme si non requise pour l'approbation.

## Build/tests

Commandes lancees (backend uniquement -- aucun changement frontend dans ce bolt) :

    cd backend
    mvn -DskipITs test

Resultat : `Tests run: 379, Failures: 0, Errors: 0, Skipped: 0` -- BUILD SUCCESS (378 + 1 nouveau test
`CurrentShopContextTest`). Suite complete verte, y compris le test corrige.

Test cible relance isolement (avant et apres la cassure controlee du code de production, voir
section ci-dessus) : `mvn -DskipITs -Dtest=LegacyImportServiceTest#doesNotReuseProductWithSameNameFromAnotherShop test`
-- vert sur le code reel, rouge sur le code volontairement casse.

## Conclusion

Le Finding 1 (bloquant) du premier passage est corrige de maniere verifiee, pas seulement declarative :
j'ai reproduit moi-meme la cassure du scenario de scoping et confirme que le test corrige l'attrape. Le
Finding 2 (mineur) a ete traite au passage sans que cela soit une condition d'approbation. Aucun nouveau
probleme releve sur ce diff cible. Build et suite de tests complets verts.
