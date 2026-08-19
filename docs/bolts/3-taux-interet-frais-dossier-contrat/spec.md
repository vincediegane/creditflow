# Spec — Issue #3 : taux d'intérêt / frais de dossier configurables par contrat

## Résumé

Ajouter un intérêt/frais figé (`interestRate` et/ou `interestFee`, combinables) calculé une seule fois à la création d'un contrat, intégré au montant financé et donc à la simulation d'échéancier, sans modifier la signature de génération d'échéancier existante.

## Tâches

### Backend — schéma & domaine

- [ ] `backend/src/main/resources/db/migration/V3__credit_sale_interest.sql` (nouveau fichier — **V3 est le prochain numéro disponible**, seuls V1 et V2 existent actuellement sur cette branche) :
  ```sql
  ALTER TABLE credit_sales
      ADD COLUMN interest_rate   NUMERIC(5, 2),
      ADD COLUMN interest_amount NUMERIC(15, 2) NOT NULL DEFAULT 0;

  ALTER TABLE credit_sales
      ADD CONSTRAINT chk_credit_sales_interest_rate
          CHECK (interest_rate IS NULL OR (interest_rate >= 0 AND interest_rate <= 100)),
      ADD CONSTRAINT chk_credit_sales_interest_amount
          CHECK (interest_amount >= 0);
  ```
  `interest_rate` nullable (purement informatif) ; `interest_amount` `NOT NULL DEFAULT 0` pour que les contrats de démo et tout contrat existant restent valides sans backfill.

- [ ] `backend/src/main/java/com/creditflow/sale/domain/CreditSale.java` — ajouter, juste après le champ `totalPrice` :
  ```java
  @Column(name = "interest_rate", precision = 5, scale = 2)
  private BigDecimal interestRate;

  @Column(name = "interest_amount", nullable = false, precision = 15, scale = 2)
  private BigDecimal interestAmount;
  ```

### Backend — calcul

- [ ] `backend/src/main/java/com/creditflow/sale/service/InstallmentScheduleGenerator.java` — ajouter une méthode pure, sans toucher à `generate(...)` :
  ```java
  /** Interet fige, calcule une fois : taux applique sur le prix comptant + frais fixe, arrondis a l'unite. */
  public BigDecimal interestAmount(BigDecimal totalPrice, BigDecimal interestRate, BigDecimal interestFee) {
      BigDecimal total = Money.round(totalPrice);
      BigDecimal rate = Money.nullToZero(interestRate);
      BigDecimal fee = Money.round(Money.nullToZero(interestFee));
      BigDecimal fromRate = Money.round(
              total.multiply(rate).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
      return fromRate.add(fee);
  }
  ```
  Chaque composante (taux, frais) est arrondie à l'unité FCFA **avant** l'addition, conformément à `Money.round`. Le résultat est donc toujours un multiple de l'unité (échelle de stockage 2, partie décimale `.00`).

### Backend — service

- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` :
  - Remplacer la méthode privée `financedAmount(BigDecimal totalPrice, BigDecimal downPayment)` par une méthode de validation seule :
    ```java
    private void validateDownPayment(BigDecimal totalPrice, BigDecimal downPayment) {
        if (downPayment.compareTo(totalPrice) >= 0) {
            throw new BusinessRuleException("L'acompte doit etre inferieur au prix total");
        }
    }
    ```
    **Décision** : la validation reste basée sur `totalPrice` (prix comptant) seul, pas sur `totalPrice + interestAmount` — l'acompte se compare au prix, pas au coût total du crédit ; comportement inchangé pour les contrats à taux zéro.
  - `preview(SalePreviewRequest request)` :
    ```java
    BigDecimal totalPrice = Money.round(request.totalPrice());
    BigDecimal downPayment = Money.round(Money.nullToZero(request.downPayment()));
    validateDownPayment(totalPrice, downPayment);
    BigDecimal interestAmount = scheduleGenerator.interestAmount(totalPrice, request.interestRate(), request.interestFee());
    BigDecimal financed = totalPrice.add(interestAmount).subtract(downPayment);

    InstallmentScheduleGenerator.Schedule schedule =
            scheduleGenerator.generate(financed, request.installmentCount(), request.startDate());

    return new SalePreviewResponse(
            totalPrice,
            interestAmount,
            financed,
            schedule.monthlyAmount(),
            schedule.endDate(),
            schedule.lines().stream()
                    .map(l -> new SalePreviewResponse.PreviewLine(l.number(), l.dueDate(), l.amount()))
                    .toList());
    ```
  - `create(CreateSaleRequest request)` : appliquer le même calcul (`totalPrice`, `downPayment`, `validateDownPayment`, `interestAmount`, `financed`) avant `scheduleGenerator.generate(...)`, puis dans le `CreditSale.builder()` ajouter :
    ```java
    .interestRate(request.interestRate())
    .interestAmount(interestAmount)
    ```
    juste après `.totalPrice(totalPrice)`, avant `.downPayment(downPayment)`.

### Backend — DTOs

- [ ] `backend/src/main/java/com/creditflow/sale/dto/CreateSaleRequest.java` — nouvel ordre de champs (record) : `customerId, productId, totalPrice, downPayment, interestRate, interestFee, installmentCount, startDate, notes`. Ajouter :
  ```java
  @DecimalMin(value = "0.0", message = "Le taux ne peut pas etre negatif")
  @DecimalMax(value = "100.0", message = "Le taux ne peut pas depasser 100")
  BigDecimal interestRate,

  @DecimalMin(value = "0.0", message = "Les frais ne peuvent pas etre negatifs")
  BigDecimal interestFee,
  ```
  entre `downPayment` et `installmentCount`. Import `jakarta.validation.constraints.DecimalMax` à ajouter.

- [ ] `backend/src/main/java/com/creditflow/sale/dto/SalePreviewRequest.java` — même ajout (`interestRate`, `interestFee`, mêmes bornes `@DecimalMin`/`@DecimalMax`), même position (entre `downPayment` et `installmentCount`).

- [ ] `backend/src/main/java/com/creditflow/sale/dto/SalePreviewResponse.java` — nouvel ordre de champs : `totalPrice, interestAmount, financedAmount, monthlyAmount, endDate, lines`. `PreviewLine` inchangé.

- [ ] `backend/src/main/java/com/creditflow/sale/dto/SaleResponse.java` — insérer `interestRate` (nullable) et `interestAmount` juste après `totalPrice` et avant `downPayment` :
  `id, reference, customerId, customerName, customerPhone, productId, productName, totalPrice, interestRate, interestAmount, downPayment, financedAmount, installmentCount, monthlyAmount, amountPaid, remainingAmount, startDate, endDate, status, late, lateInstallments, daysLate, nextDueDate, nextDueAmount, paidInstallments, notes, createdAt`.

- [ ] `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java` — dans `toResponse(CreditSale sale, ...)`, ajouter `sale.getInterestRate()` et `sale.getInterestAmount()` dans le `new SaleResponse(...)` au même emplacement (après `sale.getTotalPrice()`, avant `sale.getDownPayment()`).

### Backend — corriger les appels positionnels existants (nécessaire, sinon échec de compilation)

- [ ] `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` (~ligne 152-154) — adapter l'appel `new CreateSaleRequest(...)` en insérant `null, null` (pas d'intérêt sur les données de démo) entre `downPayment` et `months` :
  ```java
  sales.add(creditSaleService.create(new CreateSaleRequest(
          customer.id(), product.id(), price, downPayment, null, null, months, startDate,
          "Contrat de demonstration")));
  ```
- [ ] `backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java` (~ligne 129-132) — même correction, `null, null` pour les contrats repris (pas de notion de taux dans le CSV legacy) :
  ```java
  SaleResponse sale = creditSaleService.create(new CreateSaleRequest(
          customer.getId(), product.getId(), row.totalPrice(), row.downPayment(),
          null, null, row.installmentCount(), row.startDate(),
          "Contrat repris depuis l'ancien suivi papier"));
  ```
- [ ] `backend/src/test/java/com/creditflow/sale/web/SaleControllerSecurityTest.java` — corriger les deux constructions positionnelles :
  - `createRequest()` : insérer `null, null` entre `BigDecimal.valueOf(20000)` et `6`.
  - `response()` : insérer `null, BigDecimal.ZERO` entre `BigDecimal.valueOf(100000)` (totalPrice) et `BigDecimal.valueOf(20000)` (downPayment).

### Backend — tests

- [ ] `backend/src/test/java/com/creditflow/sale/service/InstallmentScheduleGeneratorTest.java` — ajouter les cas suivants (couvrent le critère « test unitaire avec taux non nul ») :
  - `computesInterestFromRateOnly` : `interestAmount(new BigDecimal("200000"), new BigDecimal("10"), null)` → `200000` × 10 % = `20000`.
  - `computesInterestFromFeeOnly` : `interestAmount(new BigDecimal("200000"), null, new BigDecimal("5000"))` → `5000`.
  - `combinesRateAndFee` : `interestAmount(new BigDecimal("200000"), new BigDecimal("10"), new BigDecimal("5000"))` → `25000`.
  - `treatsNullRateAndFeeAsZero` : `interestAmount(new BigDecimal("200000"), null, null)` → `0` (garantit la rétrocompatibilité taux zéro).
  - `roundsInterestToNearestUnit` : `interestAmount(new BigDecimal("333333"), new BigDecimal("10"), null)` → attendu `33333` (333333 × 10 % = 33333.3, arrondi HALF_UP à l'unité).
  - `sumEqualsFinancedAmountWithInterest` (bout-en-bout, couvre directement le critère « somme des échéances == montant financé ») : `totalPrice = 200000`, `downPayment = 50000`, `rate = 10`, `fee = 3000` → `interestAmount = 23000` → `financed = 200000 + 23000 - 50000 = 173000` → `generator.generate(new BigDecimal("173000"), 4, ...)` → vérifier que la somme des `ScheduleLine.amount()` est exactement `173000`.

### Frontend — types & API

- [ ] `frontend/src/types.ts` :
  - `CreateSalePayload` : ajouter `interestRate?: number; interestFee?: number;` (après `downPayment`, avant `installmentCount`).
  - `SalePreview` : ajouter `totalPrice: number; interestAmount: number;` avant `financedAmount` (miroir de l'ordre backend).
  - `Sale` : ajouter `interestRate?: number; interestAmount: number;` juste après `totalPrice`, avant `downPayment`.

- [ ] `frontend/src/api/endpoints.ts` — élargir le type inline du payload de `salesApi.preview` :
  ```ts
  preview: (payload: {
    totalPrice: number;
    downPayment: number;
    interestRate?: number;
    interestFee?: number;
    installmentCount: number;
    startDate: string;
  }) => api.post<SalePreview>('/sales/preview', payload).then((r) => r.data),
  ```

### Frontend — pages

- [ ] `frontend/src/pages/NewSalePage.tsx` :
  - `FormValues` : ajouter `interestRate: number | ''; interestFee: number | '';`.
  - `defaultValues` : ajouter `interestRate: '', interestFee: ''`.
  - Dans la grille de saisie (entre le champ `Acompte` et le champ `Nombre de mensualités`, nouvelle paire `xs={12} sm={6}`) :
    - `TextField` « Taux d'intérêt (%) », `type="number"`, `inputProps={{ min: 0, max: 100, step: 0.1 }}`, `{...register('interestRate', { min: 0, max: 100 })}`.
    - `TextField` « Frais de dossier (FCFA) », `type="number"`, `inputProps={{ min: 0, step: 500 }}`, `{...register('interestFee', { min: 0 })}`.
  - `previewMutation.mutate({...})` (dans le `useEffect` de simulation automatique) : ajouter
    ```ts
    interestRate: values.interestRate === '' || values.interestRate == null ? undefined : Number(values.interestRate),
    interestFee: values.interestFee === '' || values.interestFee == null ? undefined : Number(values.interestFee),
    ```
  - Tableau de dépendances du `useEffect` : ajouter `values.interestRate, values.interestFee`.
  - `submit` (`createMutation.mutate({...})`) : ajouter la même conversion `interestRate`/`interestFee` que ci-dessus, à partir de `form.interestRate`/`form.interestFee`.
  - Panneau de simulation (`{preview && (...)}`) : ajouter deux `Summary` avant les items existants :
    ```tsx
    <Summary label="Prix comptant" value={formatMoney(preview.totalPrice)} />
    <Summary label="Intérêt / frais" value={formatMoney(preview.interestAmount)} />
    ```
    suivis des `Summary` déjà présents (« Montant à financer », « Mensualité », « Date de fin », « Nombre d'échéances »).

- [ ] `frontend/src/pages/SaleDetailPage.tsx` — insérer, entre la ligne `<Line label="Prix total" .../>` (ligne 149 actuelle) et `<Line label="Acompte" .../>` (ligne 150 actuelle) :
  ```tsx
  <Line
    label="Intérêt / frais"
    value={
      sale.interestRate != null
        ? `${formatMoney(sale.interestAmount)} (${sale.interestRate} %)`
        : formatMoney(sale.interestAmount)
    }
  />
  ```

## Contrat technique

### Migration `V3__credit_sale_interest.sql`
- `credit_sales.interest_rate NUMERIC(5,2)` nullable, borne `[0, 100]` si renseigné.
- `credit_sales.interest_amount NUMERIC(15,2) NOT NULL DEFAULT 0`, `>= 0`.

### `InstallmentScheduleGenerator`
- `interestAmount(BigDecimal totalPrice, BigDecimal interestRate, BigDecimal interestFee): BigDecimal` — pure, arrondit à l'unité FCFA, `null` traité comme `0`.
- `generate(BigDecimal financedAmount, int installmentCount, LocalDate startDate): Schedule` — **signature inchangée**.

### `POST /api/sales/preview` — payload `SalePreviewRequest`
```json
{
  "totalPrice": 200000,
  "downPayment": 50000,
  "interestRate": 10,
  "interestFee": 3000,
  "installmentCount": 4,
  "startDate": "2026-09-01"
}
```
`interestRate`/`interestFee` optionnels, `interestRate` ∈ [0, 100], `interestFee` ≥ 0.

### `POST /api/sales/preview` — réponse `SalePreviewResponse`
```json
{
  "totalPrice": 200000,
  "interestAmount": 23000,
  "financedAmount": 173000,
  "monthlyAmount": 43250,
  "endDate": "2026-12-01",
  "lines": [{ "number": 1, "dueDate": "2026-09-01", "amount": 43250 }]
}
```

### `POST /api/sales` — payload `CreateSaleRequest`
Mêmes champs `interestRate`/`interestFee` optionnels, en plus des champs existants (`customerId`, `productId`, `totalPrice`, `downPayment`, `installmentCount`, `startDate`, `notes`).

### `SaleResponse` (extrait des nouveaux champs)
- `interestRate: BigDecimal | null` — informatif seulement, jamais recalculé.
- `interestAmount: BigDecimal` — toujours présent (0 si aucun taux/frais saisi).

## Plan de tests

| Critère d'acceptation | Test | Type |
|---|---|---|
| Un vendeur peut saisir un taux ou un montant de frais fixe lors de la création d'un contrat | Saisir un taux et/ou des frais dans `NewSalePage.tsx`, vérifier l'envoi dans le payload `create` et la persistance dans `SaleDetailPage.tsx` (ligne « Intérêt / frais ») | Manuel (pas de suite de tests frontend dans le repo) |
| La simulation d'échéancier reflète l'intérêt avant validation | Renseigner un taux/frais dans `NewSalePage.tsx` et vérifier que le panneau de simulation affiche « Prix comptant », « Intérêt / frais » et un « Montant à financer » supérieur au prix comptant moins l'acompte | Manuel — complété par le test backend `CreditSaleService.preview()` implicite (aucun test unitaire dédié existant sur ce service ; à défaut, la couverture passe par `InstallmentScheduleGeneratorTest.sumEqualsFinancedAmountWithInterest` qui exerce le même calcul) |
| La somme des échéances générées reste exactement égale au montant financé (prix + intérêt − acompte), arrondi à l'unité FCFA | `InstallmentScheduleGeneratorTest.sumEqualsFinancedAmountWithInterest` | Unitaire |
| Test unitaire couvrant un cas avec taux non nul dans `InstallmentScheduleGeneratorTest` | `computesInterestFromRateOnly`, `combinesRateAndFee`, `roundsInterestToNearestUnit`, `sumEqualsFinancedAmountWithInterest` | Unitaire |
| Non-régression : contrats à taux zéro (comportement MVP inchangé) | `treatsNullRateAndFeeAsZero` + tests existants `generatesEqualInstallments`, `lastInstallmentAbsorbsRounding`, `sumEqualsFinancedAmount` (déjà présents, doivent continuer à passer sans modification) | Unitaire |
| Non-régression : sécurité des rôles sur `/api/sales` | `SaleControllerSecurityTest` (recompilation avec les nouveaux champs, comportement des tests inchangé) | Intégration (existant, à ne pas casser) |
| Non-régression : import legacy et données de démo | Vérifier que `DemoDataSeeder` et `LegacyImportService` compilent et créent toujours des contrats à `interestAmount = 0` | Manuel / compilation |

## Écarts identifiés

- **Le design ne liste pas tous les appels positionnels à `CreateSaleRequest`/`SaleResponse` impactés par l'ajout de champs aux records.** En plus de `SaleControllerSecurityTest.java` (mentionné), `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` et `backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java` construisent aussi `new CreateSaleRequest(...)` de façon positionnelle et ne compileront plus sans mise à jour. Résolu ci-dessus (tâches dédiées, `interestRate`/`interestFee` = `null`).
- **Le design ne tranche pas la base de validation de l'acompte une fois l'intérêt introduit** (comparer `downPayment` à `totalPrice` seul, ou à `totalPrice + interestAmount`). Tranché : la règle métier existante (`downPayment < totalPrice`) reste inchangée et ignore l'intérêt, pour ne pas modifier un comportement déjà validé côté MVP et rester cohérent avec l'assiette du taux (prix comptant).
- **Le design ne précise pas où afficher `interestRate` (champ informatif)** au-delà de la saisie dans `NewSalePage.tsx`. Tranché : affiché entre parenthèses à côté du montant d'intérêt dans `SaleDetailPage.tsx` (ligne « Intérêt / frais »), sans champ dédié supplémentaire — hors périmètre sinon.
