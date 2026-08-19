# Design — Issue #3 : taux d'intérêt / frais de dossier configurables par contrat

## Approche

On ajoute un intérêt/frais figé à la création du contrat, calculé une fois puis stocké tel quel (aucun recalcul dynamique en cours de vie du contrat) : c'est ce qui permet à `interest_amount = 0` de reproduire exactement le comportement actuel sans branche de code séparée. Le vendeur peut saisir soit un taux (`interestRate`, en %, appliqué sur `totalPrice`), soit un frais de dossier fixe (`interestFee`), soit les deux — ils sont simplement additionnés en `interestAmount`, ce qui évite d'imposer une exclusivité artificielle côté validation. Le calcul de l'intérêt est isolé dans une méthode dédiée de `InstallmentScheduleGenerator` (classe déjà pure/testable), tandis que sa signature `generate(financedAmount, installmentCount, startDate)` reste inchangée : le montant financé continue d'être un simple nombre en entrée, que ce nombre inclue ou non de l'intérêt ne change rien à la génération des lignes. Le prix pour cette simplicité : le détail « part taux » vs « part frais fixe » n'est pas conservé séparément après création, seul le total figé `interest_amount` (et le taux `interest_rate` s'il a été utilisé, pour affichage) est persisté — jugé suffisant pour le MVP.

## Fichiers/modules impactés

Backend :
- `backend/src/main/resources/db/migration/V3__credit_sale_interest.sql` (nouveau) — ajoute `interest_rate` (nullable) et `interest_amount` (NOT NULL DEFAULT 0) à `credit_sales`. Voir risque de collision de version ci-dessous.
- `backend/src/main/java/com/creditflow/sale/domain/CreditSale.java` — ajoute les champs `interestRate` (`BigDecimal`, nullable) et `interestAmount` (`BigDecimal`, not null).
- `backend/src/main/java/com/creditflow/sale/service/InstallmentScheduleGenerator.java` — ajoute une méthode pure `interestAmount(BigDecimal totalPrice, BigDecimal interestRate, BigDecimal interestFee)` qui calcule et arrondit (`Money.round`) l'intérêt figé ; `generate(...)` n'est pas modifiée.
- `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` — `preview()` et `create()` appellent `scheduleGenerator.interestAmount(...)` puis calculent `financedAmount = totalPrice + interestAmount - downPayment` avant `generate(...)` ; `create()` renseigne `interestRate`/`interestAmount` sur l'entité.
- `backend/src/main/java/com/creditflow/sale/dto/CreateSaleRequest.java` — ajoute `interestRate` (nullable, borné 0-100) et `interestFee` (nullable, borné >= 0).
- `backend/src/main/java/com/creditflow/sale/dto/SalePreviewRequest.java` — mêmes deux champs optionnels.
- `backend/src/main/java/com/creditflow/sale/dto/SalePreviewResponse.java` — ajoute `totalPrice` et `interestAmount` (pour afficher le détail prix / intérêt / financé / mensualité demandé par le ticket).
- `backend/src/main/java/com/creditflow/sale/dto/SaleResponse.java` — ajoute `interestRate`, `interestAmount`.
- `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java` — reporte les deux nouveaux champs dans `SaleResponse`.
- `backend/src/test/java/com/creditflow/sale/service/InstallmentScheduleGeneratorTest.java` — nouveaux cas pour `interestAmount(...)` : taux seul, frais fixe seul, combinaison, et un cas bout-en-bout (financedAmount incluant l'intérêt) vérifiant que la somme des lignes générées reste exacte.

Frontend :
- `frontend/src/types.ts` — `CreateSalePayload` (ajoute `interestRate?`, `interestFee?`), `SalePreview` (ajoute `totalPrice`, `interestAmount`), `Sale` (ajoute `interestRate?`, `interestAmount`).
- `frontend/src/api/endpoints.ts` — `salesApi.preview(...)` : élargir le type du payload avec `interestRate?`/`interestFee?`.
- `frontend/src/pages/NewSalePage.tsx` — deux champs de saisie optionnels (Taux d'intérêt %, Frais de dossier), inclus dans `values`/`canPreview`/l'appel à `previewMutation`/`createMutation`, et une ligne « Intérêt / frais » + « Prix comptant » dans le panneau de simulation (`Summary`).
- `frontend/src/pages/SaleDetailPage.tsx` — ajoute une `Line label="Intérêt / frais"` entre « Prix total » et « Acompte » (lignes 149-151 actuelles).

## Décisions clés

- Champ figé unique côté persistance : on stocke `interest_amount` (total, arrondi FCFA, jamais recalculé après création) plutôt qu'un `interest_rate` obligatoire — conforme à la demande explicite du ticket (« calculé et figé à la création »). `interest_rate` est conservé en plus, mais seulement à titre informatif/nullable (permet d'afficher « 5% » sur le contrat quand ce mode a été utilisé ; reste NULL si seul un frais fixe a été saisi).
- Taux et frais combinables plutôt que mutuellement exclusifs : `interestAmount = round(totalPrice x rate / 100) + round(fee)`. Évite une règle de validation artificielle (« l'un ou l'autre mais pas les deux ») alors que rien dans le ticket ne l'interdit et que certains commerces cumulent un taux et des frais de dossier fixes.
- Assiette du taux = `totalPrice` (prix comptant), pas `financedAmount` post-acompte. C'est la pratique la plus lisible (l'intérêt porte sur le prix du bien, pas sur le solde après acompte) et cela évite une dépendance circulaire entre acompte et intérêt.
- `InstallmentScheduleGenerator.generate(...)` reste inchangé : l'intérêt est ajouté en amont dans `financedAmount` calculé par le service. Alternative rejetée : faire porter `totalPrice`/`downPayment`/`interestRate` directement par `generate(...)` — plus proche du texte du ticket mais casse la signature testée par les cas existants sans bénéfice fonctionnel, puisque le calcul de l'intérêt est déjà isolable en méthode pure séparée dans la même classe (satisfait « InstallmentScheduleGenerator intègre ce montant »).
- Numéro de migration V3 : à re-vérifier au moment de l'implémentation (voir Risques).

## Risques / points d'attention

- Collision de numéro de migration Flyway : la branche courante (`bolt/issue-3-taux-interet-frais-dossier-contrat`) part de `master`, qui ne contient à ce jour que `V1__create_schema.sql` et `V2__user_password_policy.sql` (vérifié par exploration du dossier `backend/src/main/resources/db/migration`). La branche `bolt/issue-2-audit-log-qui-a-fait-quoi-quand` (non encore mergée sur `master`) ajoute `created_by`/`updated_by` mais aucun fichier `V3__audit_columns.sql` n'a été trouvé sur cette branche lors de l'exploration ; le ticket y fait pourtant référence. Le codeur doit re-vérifier l'état réel des migrations sur `master` juste avant d'écrire le fichier SQL de ce ticket, et renommer en `V4__...` si un `V3` existe déjà à ce moment (notamment si #2 est mergé avant #3).
- Rétrocompatibilité des contrats existants : les lignes `credit_sales` déjà en base n'ont pas de colonne `interest_amount` ; la migration doit la créer `NOT NULL DEFAULT 0` pour que les contrats existants restent valides sans backfill supplémentaire (`financedAmount` inchangé pour eux).
- `SalePreviewResponse` change de forme (ajout `totalPrice`, `interestAmount`) — vérifier qu'aucun autre consommateur frontend que `NewSalePage.tsx` ne désérialise strictement cette réponse.
- Arrondi : `interestAmount` doit passer par `Money.round(...)` avant d'entrer dans le calcul de `financedAmount`, sinon le contrôle « somme des échéances == montant financé, arrondi à l'unité FCFA » (critère d'acceptation) peut échouer d'une unité sur certains taux.
- Validation : `interestRate` doit être borné [0, 100] et `interestFee` >= 0 ; un `interestRate`/`interestFee` absent ou null doit être traité comme 0 via `Money.nullToZero` (déjà utilisé pour `downPayment`), pour ne pas casser les appels existants qui n'envoient pas ces champs.
- Tests existants : seuls `InstallmentScheduleGeneratorTest` et `SaleControllerSecurityTest` ont été trouvés sous `backend/src/test/java/com/creditflow/sale/`. Vérifier qu'aucun test n'assume un schéma JSON strict pour `SaleResponse`/`SalePreviewResponse` qui casserait avec les champs additionnels.

## Hors périmètre

- Taux variable dans le temps, révision d'un contrat déjà créé, ou recalcul de l'intérêt après signature — explicitement exclu par le ticket (« pas de taux variable dans le temps pour le MVP »).
- Taux dégressif/actuariel (type TAEG) — le calcul retenu est un intérêt simple à plat sur le prix comptant, pas un amortissement à taux composé.
- Paramétrage d'un taux par défaut au niveau boutique/produit (configuration globale) — le ticket porte uniquement sur la saisie par contrat.
- Toute modification liée à `audit_log`/`created_by`/`updated_by` (ticket #2) — hors périmètre, à ne pas dupliquer ici.
