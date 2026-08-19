# Review — Issue #3 : taux d'intérêt / frais de dossier configurables par contrat

## Verdict

APPROVE

## Contexte de la vérification

- Branche : `bolt/issue-3-taux-interet-frais-dossier-contrat`, base `master`.
- Commits inspectés : `8685c27` (design), `877b944` (spec), `7db9d7f` (backend), `0382233` (frontend) — hashes conformes au rapport du codeur.
- `git diff master...HEAD --stat` : 19 fichiers modifiés, coherent avec le perimetre de la spec (schema, domaine, calcul, service, DTO, mapper, 3 sites d'appel positionnels, tests backend, types/endpoints/pages frontend).

## Criteres d'acceptation

| Critere | Statut | Detail |
|---|---|---|
| Un vendeur peut saisir un taux ou un montant de frais fixe lors de la creation d'un contrat | Couvert | `CreateSaleRequest.interestRate/interestFee` (bornes DecimalMin/DecimalMax), champs TextField dedies dans NewSalePage.tsx, transmis dans createMutation.mutate avec conversion vide/null vers undefined |
| La simulation d'echeancier reflete l'interet avant validation | Couvert | `CreditSaleService.preview()` calcule interestAmount puis financed = totalPrice + interestAmount - downPayment avant scheduleGenerator.generate ; SalePreviewResponse expose totalPrice/interestAmount ; NewSalePage.tsx les affiche dans le panneau de simulation |
| La somme des echeances generees reste exactement egale au montant finance, arrondi a l'unite FCFA | Couvert | InstallmentScheduleGeneratorTest.sumEqualsFinancedAmountWithInterest verifie interestAmount=23000, financed=173000, et que la somme des lignes generees vaut exactement 173000 |
| Test unitaire couvrant un cas avec taux non nul dans InstallmentScheduleGeneratorTest | Couvert | interestFromRateOnly, interestFromFeeOnly, interestFromRateAndFee, interestRoundedToUnit, sumEqualsFinancedAmountWithInterest (noms differents de ceux suggeres dans la spec mais fonctionnellement equivalents et executes) |

## Points verifies en detail (conformes)

- Migration V3 : seuls V1__create_schema.sql, V2__user_password_policy.sql et V3__credit_sale_interest.sql existent sur backend/src/main/resources/db/migration/ de cette branche ; master n'a que V1/V2. Pas de collision. Contenu SQL identique a la spec.
- Signature de InstallmentScheduleGenerator.generate(...) : inchangee (diff confirme, seule une nouvelle methode interestAmount(...) a ete ajoutee).
- Calcul d'interet : fromRate = Money.round(total.multiply(rate).divide(100, 10, HALF_UP)) puis .add(fee), fee deja arrondi separement — conforme a "chaque composante arrondie a l'unite avant addition". Le test bout-en-bout sumEqualsFinancedAmountWithInterest reproduit l'exemple totalPrice=200000, downPayment=50000, rate=10%, fee=3000 -> interestAmount=23000 -> financed=173000 et verifie la somme des lignes.
- Validation de l'acompte : validateDownPayment(totalPrice, downPayment) compare uniquement a totalPrice, pas a totalPrice + interestAmount — conforme a la decision actee dans la spec.
- Retrocompatibilite taux zero : interestNullTreatedAsZero verifie interestAmount(200000, null, null) == 0. Les tests preexistants generatesEqualInstallments, lastInstallmentAbsorbsRounding, sumEqualsFinancedAmount continuent de passer sans modification.
- Grep exhaustif des constructeurs positionnels refait independamment :
  - new CreateSaleRequest( -> 3 sites : DemoDataSeeder.java:152, LegacyImportService.java:129, SaleControllerSecurityTest.java:47. Les trois inserent bien null, null a la position interestRate/interestFee.
  - new SaleResponse( -> 2 sites : SaleMapper.java:35 et SaleControllerSecurityTest.java:52, tous deux corrigement mis a jour.
  - Le grep du codeur etait donc bien complet, aucun site manquant.
- Risque de NPE sur interestAmount null en memoire (PaymentServiceTest.java:139, PaymentReceiptGeneratorTest.java) : verifie — getInterestAmount()/getInterestRate() ne sont references nulle part ailleurs dans le code de production. PaymentService et le generateur de recu n'y accedent jamais. De plus, saleRepository.save(...) est mocke dans PaymentServiceTest (thenAnswer retournant l'argument), donc aucune persistance reelle ni contrainte NOT NULL declenchee. L'affirmation du codeur est correcte, sans risque de NPE ni d'echec de test.
- DTOs/mapper : ordre des champs dans CreateSaleRequest, SalePreviewRequest, SalePreviewResponse, SaleResponse et SaleMapper.toResponse conforme exactement a la spec.
- Frontend NewSalePage.tsx : envoie bien undefined (jamais chaine vide ni NaN) quand interestRate/interestFee sont vides, dans le useEffect de simulation et dans submit. SaleDetailPage.tsx affiche "Interet / frais" avec le taux entre parentheses si renseigne, sinon juste le montant — conforme.
- RBAC : SaleController.java n'est pas modifie par ce diff ; aucune regression de securite introduite sur /api/sales ou /api/sales/preview.

## Ecarts mineurs (non bloquants)

1. backend/src/test/java/com/creditflow/sale/service/InstallmentScheduleGeneratorTest.java (methode interestRoundedToUnit, lignes ~110-116) — le test utilise interestAmount(100000, 3.33, null), mais 100000 x 3.33 / 100 = 3330 est un resultat entier exact qui ne sollicite aucun arrondi HALF_UP (une troncature donnerait le meme resultat). Le test ne verifie donc pas reellement le comportement d'arrondi annonce par son nom, contrairement au cas suggere par la spec (333333 x 10% = 33333.3 -> 33333, un vrai cas .5 d'arrondi). Non bloquant : le critere d'acceptation "test avec taux non nul" reste couvert par les autres tests de la classe.
2. frontend/src/pages/NewSalePage.tsx (ligne ~218) — inputProps step: 0.5 alors que la spec demandait step: 0.1 pour le champ Taux d'interet. Difference purement ergonomique (increment du selecteur natif du champ number), sans impact fonctionnel ni sur la validation metier (bornes 0/100 respectees).

## Build/tests executes

- Backend : mvn -o test (depuis backend/) -> BUILD SUCCESS, Tests run: 85, Failures: 0, Errors: 0, Skipped: 0, incluant InstallmentScheduleGeneratorTest (12 tests, 0 echec) et SaleControllerSecurityTest (5 tests, 0 echec).
- Frontend : npm run build (tsc --noEmit && vite build, depuis frontend/) -> succes, aucune erreur TypeScript, build Vite termine.

## Conclusion

Implementation conforme a la spec sur tous les points structurants (migration, entite, calcul, service, DTOs, mapper, sites d'appel positionnels, frontend). Les 4 criteres d'acceptation sont couverts par du code et des tests qui echoueraient si le code etait retire. Build et suite de tests backend/frontend passent integralement. Les deux ecarts releves sont mineurs (qualite d'un test, pas de couverture d'AC ; step UI) et n'appellent pas de blocage.
