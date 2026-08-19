# Review — Ticket #4 : Pénalité de retard configurable

## Verdict

APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Le gérant peut activer/désactiver la pénalité et en définir le taux depuis les paramètres | Couvert — `PenaltySettingsController` (ADMIN-only GET/PUT), `PenaltySettingsService.update()` avec validation métier (`enabled=true` + `rate<=0` -> 400), `PenaltySettingsPage.tsx`. Testé : `PenaltySettingsServiceTest`, `PenaltySettingsControllerSecurityTest`. |
| 2 | Une échéance en retard affiche le principal dû + la pénalité calculée à la date du jour | Couvert — `penaltyAmount` exposé sur `InstallmentResponse`, `SaleResponse`, `LateCustomerResponse` via `SaleMapper`/`LateCustomerService`, affiché sur `SaleDetailPage`, `InstallmentsPage`, `LateCustomersPage`. Testé : `PenaltyCalculatorTest`. |
| 3 | Un paiement peut imputer la pénalité avant ou après le principal, selon une règle explicite et testée | Couvert — `PaymentAllocator.applyPenalty()` imputé avant `allocate()` dans `PaymentService.register()` et dans le rejeu de `delete()`. Testé : `PaymentAllocatorTest` (FIFO, débordement inter-échéances, désactivé, enchaînement applyPenalty->allocate), `PaymentServiceTest` (pénalité avant principal, plafond incluant la pénalité, rejeu après suppression). |
| 4 | Test unitaire sur le calcul de pénalité pour plusieurs durées de retard | Couvert — `PenaltyCalculatorTest` : `daysLate` 0/1/6 (DAY), 8 (WEEK, période entamée), FIXED/PERCENT, plafond, `penaltyPaid` partiel/dépassé/null, `totalOutstanding`. |

Tous les critères sont couverts par du code et par des tests qui échoueraient si le code était retiré (vérifié en lisant chaque test, pas seulement leur présence).

## Vérifications ciblées (points signalés par l'orchestrateur)

1. **Fix `SaleControllerSecurityTest`** — correct et complet. Une recherche de `new SaleResponse(`, `new InstallmentResponse(`, `new LateCustomerResponse(` dans tout `backend/` ne retourne que 3 fichiers : `SaleMapper.java`, `LateCustomerService.java` (constructions légitimes, dans le mapper/service lui-même) et `SaleControllerSecurityTest.java` (seul appel positionnel restant dans les tests). Le `BigDecimal.ZERO` ajouté en 26e argument correspond exactement au nouveau champ `penaltyAmount` en dernière position du record `SaleResponse`. Aucun autre site de construction positionnelle oublié.
2. **Un seul appel DB par méthode** — vérifié ligne par ligne dans `InstallmentService` (4 sites : `search`, `upcoming`, `late`, `bySale`) et `CreditSaleService` (7 sites, dont les 2 imbriqués de `findDetail`) : chaque méthode appelle `penaltySettingsService.current()` une seule fois en tête (ou juste avant le retour pour `create`/`cancel`) et réutilise la même instance `settings` pour tous les appels à `saleMapper.toResponse(...)` de la méthode.
3. **Formule `PenaltyCalculator`** — conforme au contrat technique de la spec point par point : `!enabled` -> 0 ; `daysLate==0` -> 0 ; `periodsElapsed = ceilDiv(daysLate, periodDays)` en arithmétique entière (pas de prorata) ; `FIXED` = `rate * periodsElapsed` ; `PERCENT` = assiette `installment.getAmount()` (pas le solde du contrat), division `scale=10, HALF_UP` puis `* periodsElapsed` ; plafond `cap = amount * capPercent / 100` (même arrondi) puis `Money.min(gross, cap)` ; résultat final `Money.round(Money.max(gross - nullToZero(penaltyPaid), 0))` — jamais négatif, `penaltyPaid` null traité comme zéro. Les 11 tests de `PenaltyCalculatorTest` couvrent chaque branche, y compris la formule `PERCENT` (50000x2%x3=3000) et `WEEK` avec période entamée (8 jours -> 2 semaines, pas 1).
4. **Ordre d'imputation pénalité-avant-principal** — `PaymentService.register()` (L.131-132) appelle `applyPenalty(...)` puis passe son reliquat à `allocate(...)`. `PaymentService.delete()` (L.176-177) applique la même séquence dans le rejeu chronologique (`findBySaleIdOrderByPaymentDateAscIdAsc`), avec le même tri FIFO par `Installment::getNumber` dans les deux méthodes de `PaymentAllocator`. Testé de bout en bout par `PaymentServiceTest#penaltyIsAppliedBeforePrincipal` (pénalité réglée, `amountPaid` reste à 0) et `#deleteRecalculatesPenaltyPaid`.
5. **Nouveau plafond de paiement** — implémenté exactement comme spécifié : `payable = sale.getRemainingAmount().add(penaltyCalculator.totalOutstanding(...))`, rejet si `amount > payable`. Testé par `PaymentServiceTest#acceptsOverpaymentCoveredByPenalty` (un montant qui dépasserait `remainingAmount` seul est accepté car il couvre `remainingAmount + pénalité`) et par le test existant `rejectsOverpayment` (toujours vert avec settings désactivées par défaut dans `@BeforeEach`, donc pas de régression silencieuse).
6. **Sécurité `PenaltySettingsController`** — `@PreAuthorize("hasRole('ADMIN')")` au niveau classe, donc GET et PUT tous deux protégés. `PenaltySettingsControllerSecurityTest` vérifie explicitement les 4 combinaisons : SELLER -> 403 sur GET et PUT, ADMIN -> 200 sur GET et PUT.
7. **`reset()` remet `penaltyPaid` à zéro** — confirmé dans `PaymentAllocator.reset()` (L.101, en plus de `amountPaid`/`status`/`paidAt`), et testé explicitement par `PaymentAllocatorTest#resetClearsAllocations` (assertion ajoutée sur `penaltyPaid` après un `applyPenalty()` suivi d'un `reset()`), plus indirectement par `PaymentServiceTest#deleteRecalculatesPenaltyPaid` (une échéance avec `penaltyPaid=9999` avant suppression se retrouve à `2000` après rejeu, prouvant que le reset a bien eu lieu avant le recalcul).

Aucune incohérence trouvée avec `spec.md`, y compris sur les deux écarts explicitement documentés par le spec-writer (injection de `PenaltyCalculator` dans `SaleMapper` plutôt que dans les services, et ajout de la colonne Pénalité sur `InstallmentsPage.tsx`) : les deux sont fidèlement implémentés tels que corrigés dans la spec. `PaymentDialog.tsx` est resté intact, conformément à l'écart #4 (non bloquant, assumé par le spec-writer).

## Findings

Aucun finding bloquant. Deux remarques mineures, non bloquantes :

- `backend/src/main/java/com/creditflow/penalty/service/PenaltyCalculator.java:31` — le calcul inline `settings.getPeriod() == PenaltyPeriod.WEEK ? 7 : 1` duplique l'information déjà portée par `PenaltyPeriod.days()` (`backend/src/main/java/com/creditflow/penalty/domain/PenaltyPeriod.java`), qui reste donc une méthode morte. Conforme à la formule littérale de la spec, donc non bloquant, mais `settings.getPeriod().days()` aurait évité la duplication.
- `frontend/src/pages/PenaltySettingsPage.tsx:74` — la conversion de `capPercent` vide en `undefined` avant l'envoi au backend repose sur une comparaison de chaîne plutôt que sur `Number.isNaN`. Fonctionne pour le cas d'usage du formulaire MUI actuel mais reste fragile si le composant venait à changer.

## Build/tests

- Backend : `mvn -o test` (dans `backend/`) -> `BUILD SUCCESS`, **122 tests, 0 échec, 0 erreur, 0 ignoré** (exécution complète confirmée, pas seulement le rapport du codeur).
- Frontend : `npm run build` (dans `frontend/`, exécute `tsc --noEmit && vite build`) -> succès, aucune erreur TypeScript, bundle généré (avertissement non bloquant sur la taille du chunk principal, préexistant au ticket).

## Fichiers clés revus

- `backend/src/main/java/com/creditflow/penalty/service/PenaltyCalculator.java`
- `backend/src/main/java/com/creditflow/payment/service/PaymentAllocator.java`
- `backend/src/main/java/com/creditflow/payment/service/PaymentService.java`
- `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java`
- `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java`
- `backend/src/main/java/com/creditflow/sale/service/InstallmentService.java`
- `backend/src/main/java/com/creditflow/notification/service/LateCustomerService.java`
- `backend/src/main/java/com/creditflow/penalty/web/PenaltySettingsController.java`
- `backend/src/main/java/com/creditflow/penalty/service/PenaltySettingsService.java`
- `backend/src/main/resources/db/migration/V4__penalty_settings.sql`
- `backend/src/test/java/com/creditflow/penalty/service/PenaltyCalculatorTest.java`
- `backend/src/test/java/com/creditflow/payment/service/PaymentAllocatorTest.java`
- `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java`
- `backend/src/test/java/com/creditflow/penalty/web/PenaltySettingsControllerSecurityTest.java`
- `frontend/src/pages/PenaltySettingsPage.tsx`
- `frontend/src/pages/SaleDetailPage.tsx`, `InstallmentsPage.tsx`, `LateCustomersPage.tsx`
