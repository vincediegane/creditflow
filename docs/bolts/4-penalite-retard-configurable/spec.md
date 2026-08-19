# Spec — Pénalité de retard configurable (#4)

## Résumé

Ajouter un réglage global de pénalité de retard (`penalty_settings`, ADMIN, activable/désactivable, taux fixe ou pourcentage, plafond optionnel), calculée à la lecture sur chaque échéance en retard et imputée en priorité sur les versements, avant le principal.

## Tâches

### Backend — module `penalty` (nouveau)

- [ ] `backend/src/main/resources/db/migration/V4__penalty_settings.sql` (nouveau) :
  ```sql
  CREATE TABLE penalty_settings (
      id          BIGINT PRIMARY KEY,
      enabled     BOOLEAN        NOT NULL DEFAULT FALSE,
      rate_type   VARCHAR(20)    NOT NULL DEFAULT 'FIXED',
      rate        NUMERIC(15, 2) NOT NULL DEFAULT 0,
      period      VARCHAR(10)    NOT NULL DEFAULT 'DAY',
      cap_percent NUMERIC(5, 2),
      created_at  TIMESTAMP      NOT NULL DEFAULT NOW(),
      updated_at  TIMESTAMP,
      created_by  VARCHAR(80),
      updated_by  VARCHAR(80)
  );

  INSERT INTO penalty_settings (id, enabled, rate_type, rate, period, cap_percent)
  VALUES (1, FALSE, 'FIXED', 0, 'DAY', NULL);

  ALTER TABLE installments ADD COLUMN penalty_paid NUMERIC(15, 2) NOT NULL DEFAULT 0;
  ```
  Ligne unique `id = 1` (pas de `BIGSERIAL`) : c'est la clé technique du singleton, jamais exposée côté frontend.

- [ ] `backend/src/main/java/com/creditflow/penalty/domain/PenaltyRateType.java` (nouveau) — `enum { FIXED, PERCENT }`.
- [ ] `backend/src/main/java/com/creditflow/penalty/domain/PenaltyPeriod.java` (nouveau) :
  ```java
  public enum PenaltyPeriod {
      DAY(1), WEEK(7);
      private final int days;
      PenaltyPeriod(int days) { this.days = days; }
      public int days() { return days; }
  }
  ```
- [ ] `backend/src/main/java/com/creditflow/penalty/domain/PenaltySettings.java` (nouveau) — `@Entity @Table(name = "penalty_settings")`, `extends Auditable`, `@Id private Long id` (**pas** de `@GeneratedValue` — id fixe `1L` posé explicitement lors des écritures), champs `boolean enabled`, `PenaltyRateType rateType` (`@Enumerated(STRING)`, `@Builder.Default = PenaltyRateType.FIXED`), `BigDecimal rate` (`precision=15, scale=2`), `PenaltyPeriod period` (`@Enumerated(STRING)`, `@Builder.Default = PenaltyPeriod.DAY`), `BigDecimal capPercent` (`precision=5, scale=2`, nullable).
- [ ] `backend/src/main/java/com/creditflow/penalty/repository/PenaltySettingsRepository.java` (nouveau) — `interface ... extends JpaRepository<PenaltySettings, Long>`.
- [ ] `backend/src/main/java/com/creditflow/penalty/dto/PenaltySettingsRequest.java` (nouveau) :
  ```java
  public record PenaltySettingsRequest(
          boolean enabled,
          @NotNull(message = "Le type de taux est obligatoire") PenaltyRateType rateType,
          @NotNull(message = "Le taux est obligatoire")
          @DecimalMin(value = "0.0", message = "Le taux ne peut pas etre negatif") BigDecimal rate,
          @NotNull(message = "La periode est obligatoire") PenaltyPeriod period,
          @DecimalMin(value = "0.0", inclusive = false, message = "Le plafond doit etre positif")
          @DecimalMax(value = "100.0", message = "Le plafond ne peut pas depasser 100%") BigDecimal capPercent
  ) {}
  ```
  `capPercent` reste `null` autorisé (validation Bean Validation ignore les champs `null`) — `null` = pas de plafond.
- [ ] `backend/src/main/java/com/creditflow/penalty/dto/PenaltySettingsResponse.java` (nouveau) — record `(boolean enabled, PenaltyRateType rateType, BigDecimal rate, PenaltyPeriod period, BigDecimal capPercent, LocalDateTime updatedAt, String updatedBy)`.
- [ ] `backend/src/main/java/com/creditflow/penalty/service/PenaltySettingsService.java` (nouveau) :
  - `@Transactional(readOnly = true) PenaltySettings current()` — `repository.findById(1L).orElseThrow(() -> new IllegalStateException("penalty_settings non initialise (migration V4 manquante)"))`. Ne retourne jamais `null` grâce au seed de migration.
  - `@Transactional PenaltySettingsResponse update(PenaltySettingsRequest request)` — récupère l'entité `id=1`, validation métier **`if (request.enabled() && !Money.isPositive(request.rate())) throw new BusinessRuleException("Le taux de penalite doit etre positif si la penalite est activee")`**, applique les champs, `auditLogService.record("PENALTY_SETTINGS", 1L, "Parametres de penalite", "UPDATE", details)` avec `details` récapitulant l'état avant/après (ex. `"enabled: false -> true, rate: 0 -> 500 (FIXED/DAY)"`), sauvegarde, retourne le DTO.
- [ ] `backend/src/main/java/com/creditflow/penalty/web/PenaltySettingsController.java` (nouveau) — `@RestController @RequestMapping("/api/penalty-settings") @PreAuthorize("hasRole('ADMIN')")` sur la classe : `GET` (mappe `current()` vers `PenaltySettingsResponse`) et `PUT` (`@Valid @RequestBody PenaltySettingsRequest`).
- [ ] `backend/src/main/java/com/creditflow/penalty/service/PenaltyCalculator.java` (nouveau, `@Component`, composant pur — voir **Contrat technique** pour la formule exacte) :
  - `BigDecimal outstanding(Installment installment, PenaltySettings settings, LocalDate reference)`
  - `BigDecimal totalOutstanding(List<Installment> installments, PenaltySettings settings, LocalDate reference)`

### Backend — tests du module `penalty`

- [ ] `backend/src/test/java/com/creditflow/penalty/service/PenaltyCalculatorTest.java` (nouveau) — au minimum :
  - `enabled=false` → `outstanding(...) == 0` quel que soit le retard.
  - `daysLate=0` (échéance non en retard) → `outstanding(...) == 0`.
  - `FIXED`, `period=DAY`, `rate=500`, `daysLate=1` → `500` ; `daysLate=6` → `3000`.
  - `PERCENT`, `period=DAY`, `rate=2` (2%), `installment.amount=50000`, `daysLate=3` → `3000` (`50000 * 0.02 * 3`).
  - `WEEK` avec période entamée : `period=WEEK`, `daysLate=8` → `2` périodes (`ceil(8/7)`), pas `1`.
  - `capPercent=10`, `installment.amount=50000` → plafond `5000` : avec un retard qui donnerait normalement `8000`, `outstanding(...) == 5000`.
  - `penaltyPaid` déjà réglé partiellement (ex. gross `3000`, `penaltyPaid=1000`) → `outstanding(...) == 2000` ; `penaltyPaid` supérieur au gross → `outstanding(...) == 0` (jamais négatif).
  - `installment.penaltyPaid == null` → traité comme zéro (pas de `NullPointerException`).
  - `totalOutstanding(...)` sur une liste de 3 échéances (une en retard, une à jour, une déjà réglée) → somme correcte.
- [ ] `backend/src/test/java/com/creditflow/penalty/service/PenaltySettingsServiceTest.java` (nouveau) — `update()` avec `enabled=true` et `rate=0` lève `BusinessRuleException` ; `update()` valide persiste et journalise via `AuditLogService` (mock) ; `current()` retourne l'entité seedée.
- [ ] `backend/src/test/java/com/creditflow/penalty/web/PenaltySettingsControllerSecurityTest.java` (nouveau, `@WebMvcTest(PenaltySettingsController.class)` + `AbstractWebMvcSecurityTest`, même modèle que `AuditLogControllerSecurityTest`) — `GET`/`PUT` retournent `403` pour `@WithMockUser(roles = "SELLER")` et `200` pour `roles = "ADMIN"`.

### Backend — `Installment` et `PaymentAllocator`

- [ ] `backend/src/main/java/com/creditflow/sale/domain/Installment.java` — ajouter `@Column(name = "penalty_paid", nullable = false, precision = 15, scale = 2) @Builder.Default private BigDecimal penaltyPaid = Money.ZERO;`. Aucune méthode existante (`isLate`, `daysLate`, `getRemaining`, `isSettled`) ne change.
- [ ] `backend/src/main/java/com/creditflow/payment/service/PaymentAllocator.java` :
  - Ajouter `@RequiredArgsConstructor` (actuellement pas de constructeur explicite) et un champ `private final PenaltyCalculator penaltyCalculator`.
  - Nouvelle méthode `public BigDecimal applyPenalty(List<Installment> installments, PenaltySettings settings, BigDecimal amount, LocalDate paymentDate)` : trie par `Installment::getNumber` (même ordre que `allocate`), pour chaque échéance calcule `outstanding = penaltyCalculator.outstanding(installment, settings, paymentDate)` ; si `> 0` impute `applied = Money.min(outstanding, remaining)` sur `penaltyPaid` (`installment.setPenaltyPaid(installment.getPenaltyPaid().add(applied))`), décrémente `remaining`, s'arrête dès que `remaining <= 0`. **Ne touche jamais** `amountPaid`/`status`/`paidAt`. Retourne le reliquat non imputé (destiné à `allocate(...)`).
  - `allocate(...)` : signature **inchangée**.
  - `reset(...)` : ajouter `installment.setPenaltyPaid(Money.ZERO);` dans la boucle existante, en plus de `amountPaid`/`status`/`paidAt`.
- [ ] `backend/src/test/java/com/creditflow/payment/service/PaymentAllocatorTest.java` :
  - Mettre à jour l'instanciation `private final PaymentAllocator allocator = new PaymentAllocator(new PenaltyCalculator());` (signature de constructeur changée).
  - `schedule(...)` : ajouter `.penaltyPaid(Money.ZERO)` reste optionnel grâce à `@Builder.Default`, mais **vérifier** que `PaymentAllocatorTest#resetClearsAllocations` couvre aussi `penaltyPaid` après un `applyPenalty(...)` (ajouter cette assertion ou un nouveau test).
  - Ajouter des tests dédiés à `applyPenalty(...)` : imputation FIFO sur l'échéance la plus ancienne d'abord ; un versement qui couvre la pénalité de deux échéances déborde correctement ; `settings.enabled=false` → `applyPenalty(...)` retourne `amount` inchangé (aucune imputation) ; le reliquat retourné par `applyPenalty(...)` alimente correctement `allocate(...)` (test bout-en-bout `applyPenalty` puis `allocate` sur le même versement).

### Backend — `PaymentService` (imputation pénalité-avant-principal)

- [ ] `backend/src/main/java/com/creditflow/payment/service/PaymentService.java` — injecter `PenaltySettingsService penaltySettingsService` et `PenaltyCalculator penaltyCalculator` (via `@RequiredArgsConstructor`, déjà présent sur la classe).
  - `register(PaymentRequest request)` : après le calcul de `paymentDate` et **avant** le contrôle de plafond, `PenaltySettings settings = penaltySettingsService.current();`. Remplacer le contrôle `amount.compareTo(sale.getRemainingAmount()) > 0` par :
    ```java
    BigDecimal penaltyDue = penaltyCalculator.totalOutstanding(sale.getInstallments(), settings, paymentDate);
    BigDecimal payable = sale.getRemainingAmount().add(penaltyDue);
    if (amount.compareTo(payable) > 0) {
        throw new BusinessRuleException(
                "Le montant depasse le reste a payer, penalites incluses (%s)".formatted(payable.toPlainString()));
    }
    ```
  - Remplacer `paymentAllocator.allocate(sale.getInstallments(), amount, paymentDate);` par :
    ```java
    BigDecimal afterPenalty = paymentAllocator.applyPenalty(sale.getInstallments(), settings, amount, paymentDate);
    paymentAllocator.allocate(sale.getInstallments(), afterPenalty, paymentDate);
    ```
  - `Payment.amount` reste `amount` (le versement complet, principal + pénalité imputée) — **aucun** nouveau champ sur `Payment`.
  - `delete(Long id)` : après `paymentAllocator.reset(installments);`, injecter `PenaltySettings settings = penaltySettingsService.current();` puis remplacer le rejeu :
    ```java
    paymentRepository.findBySaleIdOrderByPaymentDateAscIdAsc(sale.getId())
            .forEach(p -> {
                BigDecimal after = paymentAllocator.applyPenalty(installments, settings, p.getAmount(), p.getPaymentDate());
                paymentAllocator.allocate(installments, after, p.getPaymentDate());
            });
    ```
- [ ] `backend/src/test/java/com/creditflow/payment/service/PaymentServiceTest.java` :
  - Ajouter `@Mock private PenaltySettingsService penaltySettingsService;` et remplacer `@Spy private PaymentAllocator paymentAllocator = new PaymentAllocator();` par `new PaymentAllocator(new PenaltyCalculator())`.
  - Dans `@BeforeEach`, stubber `when(penaltySettingsService.current()).thenReturn(disabledSettings())` par défaut (une `PenaltySettings` avec `enabled=false`), pour que les tests existants (qui ne testent pas la pénalité) continuent de passer sans changement de comportement.
  - Nouveaux tests : (1) avec des settings `enabled=true, rateType=FIXED, rate=500, period=DAY`, une échéance en retard de plusieurs jours et un versement dont le montant est inférieur à la pénalité + principal de la première échéance, vérifier que `installment.getPenaltyPaid() > 0` **et** `installment.getAmountPaid() == 0` (pénalité imputée avant le principal) ; (2) `rejectsOverpayment` étendu ou dupliqué : un montant supérieur à `remainingAmount` mais couvrant `remainingAmount + pénalité en cours` est **accepté** (ne lève pas d'exception) ; (3) `delete()` avec pénalité active : après suppression et rejeu, `penaltyPaid` est correctement recalculé (pas resté à son ancienne valeur).

### Backend — exposition `penaltyAmount` sur les réponses (assemblage des DTO)

**Correction par rapport au design** : `PenaltyCalculator` est injecté dans `SaleMapper` (pas dans `CreditSaleService`/`InstallmentService`), qui reste le seul point d'assemblage des DTO — voir **Écarts identifiés #1**. `CreditSaleService`/`InstallmentService` n'ont besoin que de `PenaltySettingsService`.

- [ ] `backend/src/main/java/com/creditflow/sale/mapper/SaleMapper.java` — passer de `@Component` sans dépendance à `@RequiredArgsConstructor` avec `private final PenaltyCalculator penaltyCalculator`.
  - `toResponse(Installment installment, LocalDate today)` → `toResponse(Installment installment, LocalDate today, PenaltySettings settings)`, calcule `BigDecimal penaltyAmount = penaltyCalculator.outstanding(installment, settings, today);`, l'ajoute en dernier argument positionnel du `new InstallmentResponse(...)`.
  - `toResponse(CreditSale sale, List<Installment> installments, LocalDate today)` → `toResponse(CreditSale sale, List<Installment> installments, LocalDate today, PenaltySettings settings)`, calcule `BigDecimal penaltyAmount = penaltyCalculator.totalOutstanding(lines, settings, today);` (sur `lines`, la liste déjà null-safe existante), l'ajoute en dernier argument positionnel du `new SaleResponse(...)`.
- [ ] `backend/src/main/java/com/creditflow/sale/dto/InstallmentResponse.java` — ajouter `BigDecimal penaltyAmount` en dernier champ du record.
- [ ] `backend/src/main/java/com/creditflow/sale/dto/SaleResponse.java` — ajouter `BigDecimal penaltyAmount` en dernier champ du record.
- [ ] `backend/src/main/java/com/creditflow/sale/service/InstallmentService.java` — injecter `PenaltySettingsService penaltySettingsService`. **4 sites d'appel confirmés par lecture du code actuel**, tous de la forme `saleMapper.toResponse(installment, today)` :
  - `search(...)` L.40 — récupérer `PenaltySettings settings = penaltySettingsService.current();` une fois en tête de méthode, appeler `saleMapper.toResponse(installment, today, settings)`.
  - `upcoming(int days)` L.47 — idem.
  - `late()` L.55 — idem.
  - `bySale(Long saleId)` L.63 — idem.
- [ ] `backend/src/main/java/com/creditflow/sale/service/CreditSaleService.java` — injecter `PenaltySettingsService penaltySettingsService`. **7 sites d'appel confirmés par lecture du code actuel** (6 sur l'overload `CreditSale`, 1 sur l'overload `Installment`) :
  - `search(...)` L.64 — `saleMapper.toResponse(sale, sale.getInstallments(), today, settings)`.
  - `findById(Long id)` L.70 — idem (`today` = `LocalDate.now()` local à la méthode, inchangé).
  - `findDetail(Long id)` L.79 — appel sur `sale` (overload `CreditSale`) — ajouter `settings`.
  - `findDetail(Long id)` L.80 — **second appel dans la même méthode**, à l'intérieur du `.stream().map(i -> saleMapper.toResponse(i, today))` (overload `Installment`) — ajouter `settings` : `.map(i -> saleMapper.toResponse(i, today, settings))`.
  - `findByCustomer(Long customerId)` L.88 — idem overload `CreditSale`.
  - `create(CreateSaleRequest request)` L.162 — idem overload `CreditSale`. Récupérer `settings` en tête de méthode ou juste avant le `return`.
  - `cancel(Long id)` L.173 — idem overload `CreditSale`.
  - Chaque méthode ne fait **qu'un seul** appel à `penaltySettingsService.current()` (pas un par site d'appel dans `findDetail`).
  - Dans `create(...)`, ajouter `.penaltyPaid(Money.ZERO)` sur le `Installment.builder()` (L.143-149) — cohérent avec `.amountPaid(Money.ZERO)` déjà présent (redondant avec `@Builder.Default` mais explicite, comme le fait déjà `amountPaid`).
- [ ] `backend/src/test/java/com/creditflow/sale/service/CreditSaleServiceTest.java` — ajouter `@Mock private PenaltySettingsService penaltySettingsService;` et, dans `@BeforeEach`, `when(penaltySettingsService.current()).thenReturn(<PenaltySettings désactivées>);` (`lenient()` déjà couvert par `@MockitoSettings(strictness = Strictness.LENIENT)` sur la classe). Aucune assertion sur `saleMapper` n'existe actuellement dans ce test (`saleMapper` est un `@Mock` jamais stubé ni vérifié) — pas de modification supplémentaire nécessaire.
- [ ] Vérifier que `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` et `backend/src/main/java/com/creditflow/dataimport/service/LegacyImportService.java` compilent sans modification : les deux passent exclusivement par `CreditSaleService.create(...)`/`CreditSaleService.findById(...)` (confirmé par lecture — aucun `Installment.builder()` direct dans ces deux classes), donc aucun changement de code n'y est requis grâce à `@Builder.Default` sur `Installment.penaltyPaid`.

### Backend — clients en retard (`LateCustomerService`)

- [ ] `backend/src/main/java/com/creditflow/notification/service/LateCustomerService.java` — injecter `PenaltySettingsService penaltySettingsService` et `PenaltyCalculator penaltyCalculator`. Dans `lateCustomers()`, récupérer `PenaltySettings settings = penaltySettingsService.current();` une fois, la transmettre à `toResponse(installments, today, settings)`. Dans `toResponse(...)`, calculer `BigDecimal penaltyAmount = penaltyCalculator.totalOutstanding(installments, settings, today);` (sur la liste des échéances en retard de ce client, déjà filtrée par `findLate(today)`), l'ajouter en dernier argument du `new LateCustomerResponse(...)`.
- [ ] `backend/src/main/java/com/creditflow/notification/dto/LateCustomerResponse.java` — ajouter `BigDecimal penaltyAmount` en dernier champ du record.

### Frontend

- [ ] `frontend/src/types.ts` :
  - `Installment` — ajouter `penaltyAmount: number`.
  - `Sale` — ajouter `penaltyAmount: number`.
  - `LateCustomer` — ajouter `penaltyAmount: number`.
  - Nouveaux types :
    ```ts
    export type PenaltyRateType = 'FIXED' | 'PERCENT';
    export type PenaltyPeriod = 'DAY' | 'WEEK';

    export interface PenaltySettings {
      enabled: boolean;
      rateType: PenaltyRateType;
      rate: number;
      period: PenaltyPeriod;
      capPercent?: number;
      updatedAt?: string;
      updatedBy?: string;
    }

    export interface PenaltySettingsPayload {
      enabled: boolean;
      rateType: PenaltyRateType;
      rate: number;
      period: PenaltyPeriod;
      capPercent?: number;
    }
    ```
- [ ] `frontend/src/api/endpoints.ts` — ajouter :
  ```ts
  export const penaltySettingsApi = {
    get: () => api.get<PenaltySettings>('/penalty-settings').then((r) => r.data),
    update: (payload: PenaltySettingsPayload) =>
      api.put<PenaltySettings>('/penalty-settings', payload).then((r) => r.data),
  };
  ```
  et importer `PenaltySettings`, `PenaltySettingsPayload` dans la liste d'imports en tête de fichier.
- [ ] `frontend/src/pages/SaleDetailPage.tsx` :
  - Dans la `Stack` « Résumé », ajouter `<Line label="Pénalités en cours" value={formatMoney(sale.penaltyAmount)} />` juste après la ligne « Reste à payer ».
  - Dans le tableau « Échéancier », ajouter une colonne `<TableCell align="right">Pénalité</TableCell>` (après « Reste », avant « Statut ») et la cellule correspondante `<TableCell align="right">{formatMoney(installment.penaltyAmount)}</TableCell>`.
- [ ] `frontend/src/pages/InstallmentsPage.tsx` — ajouter une colonne « Pénalité » dans le tableau (après « Reste », avant « Statut »), affichant `formatMoney(installment.penaltyAmount)` — **voir Écarts identifiés #2** : cette page n'est pas listée dans le design mais est l'écran « Échéances » générique visé par le critère d'acceptation 2.
- [ ] `frontend/src/pages/LateCustomersPage.tsx` — ajouter une colonne « Pénalité » dans le tableau des retards (après « Montant en retard », avant « Reste à payer »), affichant `formatMoney(row.penaltyAmount)`.
- [ ] `frontend/src/pages/PenaltySettingsPage.tsx` (nouveau) — même famille de composants que `UsersPage.tsx`, mais formulaire d'édition d'un enregistrement unique (pas de liste/dialog de création) :
  - `useQuery({ queryKey: ['penalty-settings'], queryFn: penaltySettingsApi.get })`.
  - `useForm<PenaltySettingsPayload>` avec `reset(data)` dans un `useEffect` quand la query résout.
  - Champs : `Switch`/`FormControlLabel` pour `enabled` ; `TextField select` pour `rateType` (`Fixe (montant)` / `Pourcentage de l'échéance`) ; `TextField type="number"` pour `rate` (label dynamique selon `rateType` : `Montant par période (FCFA)` ou `Taux par période (%)`) ; `TextField select` pour `period` (`Par jour` / `Par semaine`) ; `TextField type="number"` pour `capPercent` (optionnel, label « Plafond (% de l'échéance, laisser vide = pas de plafond) »).
  - `useMutation({ mutationFn: penaltySettingsApi.update, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['penalty-settings'] }) })`.
  - Un seul bouton « Enregistrer », pas de suppression/désactivation de compte à gérer (contrairement à `UsersPage`).
- [ ] `frontend/src/App.tsx` — importer `PenaltySettingsPage`, ajouter la route `<Route path="parametres/penalites" element={<PenaltySettingsPage />} />` à l'intérieur du bloc existant `<Route element={<RequireRole role="ADMIN" />}>` (qui contient déjà `utilisateurs`).
- [ ] `frontend/src/components/AppLayout.tsx` — ajouter dans `NAV_ITEMS` (après l'entrée `Utilisateurs`) : `{ to: '/parametres/penalites', label: 'Pénalités', icon: <PercentIcon />, adminOnly: true }`, avec l'import `import PercentIcon from '@mui/icons-material/Percent';`.

## Contrat technique

### Formule de calcul (`PenaltyCalculator`)

Pour une échéance `installment`, des réglages `settings` et une date de référence `reference` :

1. Si `!settings.isEnabled()` → `outstanding = 0`.
2. `daysLate = installment.daysLate(reference)` (méthode existante, retourne `0` si l'échéance n'est pas en retard — inclut le cas où elle est `PAID`).
3. Si `daysLate == 0` → `outstanding = 0`.
4. `periodDays = settings.getPeriod() == WEEK ? 7 : 1`.
5. `periodsElapsed = ceilDiv(daysLate, periodDays)` — **périodes entamées comptent entier** : `(daysLate + periodDays - 1) / periodDays` en arithmétique entière (`long`), jamais de prorata.
6. Montant brut :
   - `FIXED` → `gross = settings.getRate().multiply(BigDecimal.valueOf(periodsElapsed))`.
   - `PERCENT` → `gross = installment.getAmount().multiply(settings.getRate()).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(periodsElapsed))`. **Assiette = `installment.getAmount()`** (montant de l'échéance concernée), jamais le solde du contrat.
7. Plafond : si `settings.getCapPercent() != null`, `cap = installment.getAmount().multiply(settings.getCapPercent()).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)` ; `gross = Money.min(gross, cap)`.
8. `outstanding = Money.round(Money.max(gross.subtract(Money.nullToZero(installment.getPenaltyPaid())), BigDecimal.ZERO))` — jamais négatif, jamais `null`.

`totalOutstanding(installments, settings, reference)` = somme de `outstanding(...)` sur chaque échéance de la liste (`BigDecimal.ZERO` si la liste est vide).

### `applyPenalty` (imputation)

`applyPenalty(installments, settings, amount, paymentDate)` impute `amount` sur `penaltyPaid`, échéance la plus ancienne d'abord (tri par `Installment::getNumber`, identique à `allocate`), à hauteur de `outstanding(installment, settings, paymentDate)` par échéance, et retourne le reliquat non consommé. Ce reliquat est ensuite passé à `allocate(...)` pour le principal. Conséquence assumée (héritée du design) : une échéance ne peut passer `PAID` (statut porté par `amountPaid`/`status`, principal uniquement) que si sa pénalité due **au moment de ce paiement précis** a été intégralement couverte par ce même versement — un versement insuffisant pour couvrir pénalité + principal d'une échéance laissera cette échéance `PARTIAL` même si son `amountPaid` égale déjà `amount`.

### Endpoint `PenaltySettingsController`

| Méthode | Chemin | Rôle | Body | Réponse |
|---|---|---|---|---|
| GET | `/api/penalty-settings` | ADMIN | — | `PenaltySettingsResponse` |
| PUT | `/api/penalty-settings` | ADMIN | `PenaltySettingsRequest` | `PenaltySettingsResponse` |

```json
{
  "enabled": true,
  "rateType": "FIXED",
  "rate": 500,
  "period": "DAY",
  "capPercent": 10,
  "updatedAt": "2026-08-19T10:00:00",
  "updatedBy": "admin"
}
```

Règle métier supplémentaire (décision spec-writer, non explicite dans le design) : `PUT` avec `enabled=true` et `rate<=0` → `400 BusinessRuleException` (« Le taux de pénalité doit être positif si la pénalité est activée »). Évite un état incohérent « activé mais sans effet » qui tromperait le gérant sur l'écran `PenaltySettingsPage`.

### `PaymentService.register` — nouveau plafond

`amount` accepté si `amount <= sale.getRemainingAmount() + penaltyCalculator.totalOutstanding(sale.getInstallments(), settings, paymentDate)` (référence = `paymentDate`, cohérent avec `applyPenalty` qui utilise la même date).

## Plan de tests

| Critère d'acceptation | Test |
|---|---|
| Le gérant peut activer/désactiver la pénalité et en définir le taux depuis les paramètres. | Unitaire : `PenaltySettingsServiceTest` (validation, persistance, audit). Intégration légère : `PenaltySettingsControllerSecurityTest` (`GET`/`PUT` réservés ADMIN, `403` pour SELLER). Manuel : se connecter en ADMIN, ouvrir `Pénalités` dans le menu, activer, saisir un taux, enregistrer, recharger la page et vérifier la persistance ; se connecter en SELLER et vérifier que l'entrée de menu et la route `/parametres/penalites` sont inaccessibles (`RequireRole`). |
| Une échéance en retard affiche le principal dû + la pénalité calculée à la date du jour. | Unitaire : `PenaltyCalculatorTest` (formule). Manuel : activer la pénalité, ouvrir une fiche contrat (`SaleDetailPage`) avec une échéance en retard, vérifier la colonne « Pénalité » de l'échéancier et la ligne « Pénalités en cours » du résumé ; vérifier la colonne « Pénalité » sur `InstallmentsPage` (filtre « En retard ») et sur `LateCustomersPage`. |
| Un paiement peut imputer la pénalité avant ou après le principal, selon une règle explicite et testée. | Unitaire : `PaymentAllocatorTest` (nouveaux tests `applyPenalty`, FIFO, désactivé, reset). Unitaire : `PaymentServiceTest` (nouveau scénario pénalité-avant-principal, plafond incluant la pénalité, rejeu via `delete()`). Manuel : activer la pénalité, encaisser un versement inférieur au principal + pénalité d'une échéance en retard, vérifier via `GET /api/sales/{id}/detail` (ou l'écran) que `penaltyPaid` a été réglé avant `amountPaid`. |
| Test unitaire sur le calcul de pénalité pour plusieurs durées de retard. | `PenaltyCalculatorTest` — scénarios `daysLate` = 0, 1, 6 (DAY) et 8 (WEEK, période entamée), couvrant `FIXED`/`PERCENT`/plafond/désactivé/`penaltyPaid` partiel (liste détaillée dans les Tâches). |

## Écarts identifiés

1. **Dépendances de `CreditSaleService`/`InstallmentService` (correction du design)** : le design indique que `CreditSaleService` doit « injecter `PenaltySettingsService`/`PenaltyCalculator` ». Après lecture du code, seul `PenaltySettingsService` est nécessaire dans `CreditSaleService` et `InstallmentService` (pour récupérer les réglages une fois par méthode et les transmettre à `saleMapper.toResponse(...)`) : le calcul lui-même (`PenaltyCalculator`) est injecté directement dans `SaleMapper`, seul point d'assemblage des DTO, comme c'est déjà le cas pour toute la logique de retard existante (`isLate`/`daysLate`). Cette spec retient cette version corrigée — moins de dépendances superflues, même résultat.
2. **Nombre exact de sites d'appel à `saleMapper.toResponse(...)`** : confirmé par lecture — `InstallmentService` en compte **4** (comme annoncé par le design). `CreditSaleService` en compte **7**, pas « six » : 6 appels sur l'overload `(CreditSale, List<Installment>, LocalDate)` (`search`, `findById`, `findDetail`, `findByCustomer`, `create`, `cancel`) **plus 1** appel supplémentaire sur l'overload `(Installment, LocalDate)`, imbriqué dans le `.stream()` de `findDetail(...)` (ligne 80), que le design n'avait pas comptabilisé séparément. Les deux appels de `findDetail(...)` doivent réutiliser la **même** instance de `PenaltySettings` (un seul `penaltySettingsService.current()` par méthode).
3. **`InstallmentsPage.tsx` absent du design** : le design ne liste que `SaleDetailPage.tsx` et `LateCustomersPage.tsx` côté frontend pour l'affichage de la pénalité, mais omet `InstallmentsPage.tsx` — l'écran générique « Échéances » (avec filtre « En retard »), qui consomme déjà `InstallmentResponse` (donc `penaltyAmount` après cette spec) et correspond littéralement au critère d'acceptation 2 (« une échéance en retard affiche… »). Cette spec ajoute la colonne « Pénalité » sur cet écran pour couvrir le critère à la lettre ; à signaler en revue si ce n'était pas l'intention de l'architecte.
4. **`PaymentDialog.tsx` non modifié (hors périmètre assumé)** : le pré-remplissage du montant (`Math.min(monthlyAmount, remainingAmount)`) ne tient pas compte de la pénalité en cours. Le gérant peut toujours saisir manuellement un montant supérieur (le nouveau plafond backend l'accepte), mais l'UI ne le suggère pas. Le design ne mentionne pas ce fichier et le critère d'acceptation 3 porte sur la règle d'imputation (couverte côté backend), pas sur l'ergonomie de saisie : non bloquant, laissé tel quel.
