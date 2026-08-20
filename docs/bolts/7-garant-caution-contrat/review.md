# Review -- #7 Garant/caution sur un contrat de credit

## Verdict

**APPROVE**

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Un vendeur peut renseigner un garant optionnel à la création d'un contrat | Couvert -- `CreateSaleRequest` (4 champs + `@AssertTrue isGuarantorConsistent`), `CreditSaleService.create()` (`blankToNull` + `builder()`), formulaire `NewSalePage.tsx` (Autocomplete de pré-remplissage + 4 `TextField` éditables + garde-fou miroir avant `mutate`). Testé côté backend par `CreditSaleServiceTest.createCapturesGuarantorFields` / `createWithoutGuarantorLeavesFieldsNull` (avec `ArgumentCaptor`, ces tests échoueraient si le mapping `builder()` était retiré) et `CreateSaleRequestValidationTest` (4 cas de la règle croisée). |
| 2 | La fiche contrat affiche les coordonnées du garant s'il existe | Couvert, avec une réserve mineure -- `SaleResponse`/`SaleMapper` reportent les 4 champs, `SaleDetailPage.tsx` affiche conditionnellement le bloc "Garant" (rendu absent si `guarantorFullName` vide, sous-champs conditionnels). Vérifié par lecture de code ; aucun test n'assert directement le contenu du mapping `SaleMapper.toResponse` (voir Findings mineurs). |
| 3 | La recherche globale retrouve un contrat via le nom/téléphone du garant | Couvert avec réserve documentée -- `SaleSpecifications.matches()` étend le `cb.or(...)` existant exactement sur le modèle de `CustomerSpecifications.matches` (`coalesce` + `likeIgnoreCase`/`like`). Le test `SaleSpecificationsTest` est un test par mocks Mockito (vérifie que les bons `root.get(...)` et `cb.coalesce(...)` sont invoqués), pas une exécution SQL réelle contre des données persistées comme le demandait la spec. Voir analyse détaillée ci-dessous : jugé acceptable pour ce ticket, non bloquant. |

## Analyse du point de vigilance -- SaleSpecificationsTest mocké vs @DataJpaTest

Vérifié : il n'existe aucune infrastructure de test avec base embarquée dans le projet (pas de dépendance H2/Testcontainers dans `backend/pom.xml`, aucun `@DataJpaTest` nulle part dans `backend/src/test`). Il n'existait d'ailleurs aucun test du tout pour `SaleSpecifications.matches()` avant ce bolt (recherche par nom client, téléphone, produit -- zéro couverture), donc le test mocké ajouté est un progrès net, pas une régression de couverture.

Éléments qui rendent le risque acceptable pour approuver sans exiger une infra H2/Testcontainers (changement d'ampleur disproportionnée pour ce ticket, comme indiqué dans le cadrage) :
- Le code de `SaleSpecifications.matches()` reproduit au caractère près le pattern déjà utilisé et non contesté de `CustomerSpecifications.matches()` pour les colonnes nullable (`cb.coalesce(root.get("champ"), "")` puis `likeIgnoreCase`/`like`) -- pattern éprouvé en production pour la recherche client (CNI, profession).
- La modification est strictement additive : les prédicats existants (référence, nom client, téléphone client, produit) ne sont pas touchés, seuls deux prédicats sont ajoutés en fin de `cb.or(...)`. Confirmé par lecture du diff : aucune ligne existante modifiée.
- Le test mocké vérifie concrètement que `root.get("guarantorFullName")` et `root.get("guarantorPhone")` sont atteints, et que `coalesce`/`like` reçoivent la valeur de recherche attendue -- il aurait échoué si le codeur avait oublié d'ajouter un prédicat au `cb.or(...)` ou s'était trompé de nom de champ.

Limite réelle non couverte : une régression de comportement JPA Criteria au runtime (interaction `coalesce`/`likeIgnoreCase` sur Postgres, sensibilité de casse réelle) ne serait pas détectée avant la mise en prod. Ce risque résiduel est réel mais symétrique à celui déjà accepté pour tout le reste de `SaleSpecifications`/`CustomerSpecifications` (jamais testés en intégration dans ce projet) -- il n'est pas spécifique à ce ticket.

Conclusion sur ce point : non bloquant. Suggestion : ouvrir un ticket de dette technique séparé pour ajouter une infra de test d'intégration (H2 ou Testcontainers), bénéfique à `SaleSpecifications`, `CustomerSpecifications` et au-delà -- mais ce n'est pas un motif de CHANGES_REQUESTED sur #7 spécifiquement.

## Vérifications de non-régression

- Migration Flyway : `V7__credit_sale_guarantor.sql` est bien la seule migration V7, aucune collision. Contenu du dossier `backend/src/main/resources/db/migration/` sur cette branche : V1, V2, V3, V4, V5, V7 -- V6 est bien absent (réservé par la PR #6 non mergée, comme documenté dans le spec). SQL bien formé : 4 colonnes nullable sans contrainte UNIQUE/CHECK, 2 index dont un index fonctionnel `LOWER(...)` qui suit exactement le pattern déjà en place dans `V1__create_schema.sql` (`idx_customers_last_name`, `idx_products_name`). Dialecte Postgres confirmé (`flyway-database-postgresql`, driver `org.postgresql`).
- `SaleSpecifications.matches()` : diff confirmé strictement additif (voir ci-dessus), aucune régression sur la recherche existante (référence, nom/téléphone client, produit).
- `DemoDataSeeder.java` / `LegacyImportService.java` : les deux appels positionnels à `new CreateSaleRequest(...)` ont été mis à jour avec 4 `null` en fin de liste. Compilent (confirmé par `mvn test` qui passe). Comportement inchangé : `blankToNull(null)` retourne `null`, donc aucun garant n'est créé pour les données de démo/import -- comportement correct et attendu, aucune régression fonctionnelle.
- Règle de validation croisée nom/téléphone : backend (`@AssertTrue isGuarantorConsistent`) et frontend (`Boolean(guarantorName) !== Boolean(guarantorPhone)`) sont bien symétriques et cohérents entre eux. Un contrat sans garant (les 4 champs vides/absents) passe la validation des deux côtés (hasName == hasPhone -> false == false -> true) -- confirmé par le test `acceptsNoGuarantorAtAll` et par `createWithoutGuarantorLeavesFieldsNull`. Pas de régression du flux de création standard.
- RBAC : aucun nouvel endpoint, aucune modification des règles de sécurité existantes. `SaleControllerSecurityTest` (mis à jour pour rester compilable) passe toujours.
- Cohérence avec spec.md : toutes les tâches de la checklist (migration, entité, DTOs, mapper, service, specifications, tests, frontend) sont réalisées conformément au contrat technique ; ordre des paramètres, position en fin de record/appel respectés au caractère près. Aucun écart non justifié constaté.

## Findings mineurs (non bloquants)

1. Aucun test n'asserte explicitement que `SaleMapper.toResponse()` reporte bien les 4 champs garant de l'entité vers `SaleResponse` (le plan de tests de la spec le demandait via un test `SaleMapper`/`CreditSaleServiceTest.findDetail`). Le mapping est trivial et à faible risque (4 lignes de passthrough), mais un test dédié aurait fermé complètement le critère d'acceptation n°2 avec une preuve automatisée plutôt que par lecture de code seule. Suggestion pour un futur bolt correctif, pas un blocage.
2. Aucun test (nouveau ou existant) ne couvre `GlobalSearchService`/`CreditSaleService.search()` de bout en bout pour confirmer que le chaînage vers `SaleSpecifications.matches()` remonte bien un contrat via son garant -- suggéré dans le plan de tests de la spec mais absent de la checklist des tâches obligatoires, et aucun test de ce service n'existe par ailleurs dans le repo (gap préexistant, non introduit par ce bolt).

## Build/tests

- Backend : `mvn -B test` (Maven, JDK 21) -- SUCCESS. Agrégation des rapports Surefire (`target/surefire-reports/*.txt`) : 164 tests, 0 échec, 0 erreur, 0 ignoré, conforme au rapport du codeur.
- Frontend : `npm run build` (= `tsc --noEmit && vite build`) -- SUCCESS, aucune erreur TypeScript, bundle généré (warning taille de chunk préexistant, sans rapport avec ce ticket).

## Conclusion

Code conforme à la spec point par point, migration bien formée et sans collision, non-régression vérifiée sur la recherche, les seeders/imports et le flux de création standard, RBAC inchangé. Le point de vigilance sur SaleSpecificationsTest (mocks au lieu d'un @DataJpaTest) est une limite honnêtement documentée par le codeur, justifiée par l'absence préexistante d'infra de test d'intégration dans le projet, et le risque associé est jugé acceptable au vu du caractère strictement additif et du pattern éprouvé repris de CustomerSpecifications. Build et tests passent réellement (vérifié indépendamment, pas seulement sur la base du rapport du codeur).

**APPROVE**
