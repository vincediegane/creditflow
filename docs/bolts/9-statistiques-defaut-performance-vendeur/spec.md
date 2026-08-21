# Spec — #9 Statistiques avancées (taux de défaut et performance vendeur)

## Résumé

Ajout de deux rapports (`DEFAULT_RATE`, `SELLER_PERFORMANCE`) au module `report` existant, agrégés en mémoire dans `ReportService` et exportables via les exporteurs PDF/Excel déjà en place, avec accès `SELLER_PERFORMANCE` restreint au rôle `ADMIN`.

## Tâches

### Backend

- [ ] `backend/src/main/java/com/creditflow/report/dto/ReportType.java` — ajouter les valeurs `DEFAULT_RATE` (« Taux de défaut par profession ») et `SELLER_PERFORMANCE` (« Performance vendeur »), avec javadoc courte à l'image des valeurs existantes.
- [ ] `backend/src/main/java/com/creditflow/report/service/ReportService.java` :
  - ajouter deux champs `private final InstallmentRepository installmentRepository;` et `private final UserRepository userRepository;` **à la fin** de la liste des champs existants (après `lateCustomerService`), pour que l'ordre du constructeur généré par `@RequiredArgsConstructor` reste `(paymentRepository, saleRepository, lateCustomerService, installmentRepository, userRepository)` ;
  - étendre la signature de `build(...)` avec 3 paramètres optionnels (`profession`, `minAmount`, `maxAmount`) — voir Contrat technique ;
  - ajouter les branches `DEFAULT_RATE -> defaultRate(profession, minAmount, maxAmount)` et `SELLER_PERFORMANCE -> sellerPerformance()` dans le `switch` ;
  - implémenter `defaultRate(String profession, BigDecimal minAmount, BigDecimal maxAmount)` et `sellerPerformance()` — voir Contrat technique pour la logique d'agrégation exacte.
- [ ] `backend/src/main/java/com/creditflow/report/web/ReportController.java` :
  - ajouter `@RequestParam(required = false) String profession`, `@RequestParam(required = false) BigDecimal minAmount`, `@RequestParam(required = false) BigDecimal maxAmount` sur `report(...)` et `export(...)`, propagés à `reportService.build(...)` ;
  - ajouter `@PreAuthorize("#type != T(com.creditflow.report.dto.ReportType).SELLER_PERFORMANCE or hasRole('ADMIN')")` sur `report(...)` et sur `export(...)`.
- [ ] `backend/src/test/java/com/creditflow/report/service/ReportServiceTest.java` (nouveau) — tests unitaires de `defaultRate(...)` et `sellerPerformance()` (filtrage, regroupement, cas de repli) + preuve d'exportabilité (AC3). Voir Plan de tests.
- [ ] `backend/src/test/java/com/creditflow/report/web/ReportControllerSecurityTest.java` (nouveau, sur le modèle de `PenaltySettingsControllerSecurityTest`/`AbstractWebMvcSecurityTest`) — tests 403/200 par rôle sur `/api/reports/{type}` et `/api/reports/{type}/export`. Voir Plan de tests.
- Pas de migration Flyway dans cette itération (`V10` non créée) — voir Contrat technique, section « Migration ».

### Frontend

- [ ] `frontend/src/types.ts` — étendre `export type ReportType` avec `'DEFAULT_RATE' | 'SELLER_PERFORMANCE'`.
- [ ] `frontend/src/api/endpoints.ts` — élargir le type des `params` de `reportsApi.get` et `reportsApi.download` à `{ from?: string; to?: string; profession?: string; minAmount?: number | string; maxAmount?: number | string }`.
- [ ] `frontend/src/pages/ReportsPage.tsx` :
  - étendre le tableau `REPORTS` avec `{ value: 'DEFAULT_RATE', label: 'Taux de défaut', needsDate: false, needsAmountFilters: true }` et `{ value: 'SELLER_PERFORMANCE', label: 'Performance vendeur', needsDate: false, adminOnly: true }` ;
  - importer `useAuth` (`../auth/AuthContext`, même import que `AppLayout.tsx`) et filtrer la liste affichée dans le `ToggleButtonGroup` avec `REPORTS.filter((r) => !r.adminOnly || user?.role === 'ADMIN')`, à l'image de `NAV_ITEMS.filter(...)` dans `AppLayout.tsx` ;
  - ajouter l'état local `profession`, `minAmount`, `maxAmount` (strings contrôlés) et un bloc de champs (`TextField`) affiché uniquement quand `type === 'DEFAULT_RATE'` ;
  - étendre le calcul de `params` : ajouter `profession`/`minAmount`/`maxAmount` au payload uniquement quand `type === 'DEFAULT_RATE'` et que le champ correspondant est renseigné (ne rien envoyer sinon, pour ne pas polluer la clé de cache `useQuery`).

## Contrat technique

### Endpoints

`GET /api/reports/{type}` et `GET /api/reports/{type}/export?format=pdf|excel` (inchangés dans leur forme), avec 3 nouveaux `@RequestParam` optionnels communs aux deux :

| Param | Type | Utilisé par |
|---|---|---|
| `profession` | `String` | `DEFAULT_RATE` uniquement — ignoré (silencieusement) pour les autres types |
| `minAmount` | `BigDecimal` | `DEFAULT_RATE` uniquement — idem |
| `maxAmount` | `BigDecimal` | `DEFAULT_RATE` uniquement — idem |

`from`/`to` restent acceptés mais sont **ignorés** par `DEFAULT_RATE` et `SELLER_PERFORMANCE` (ces deux rapports sont des photographies « au jour J », comme `LATE_CUSTOMERS`/`OUTSTANDING`).

Pas de validation serveur sur la cohérence `minAmount <= maxAmount` (si l'utilisateur inverse les bornes, le résultat est simplement vide) — cohérent avec l'absence de validation similaire sur `from`/`to` aujourd'hui.

### Sécurité

```java
@PreAuthorize("#type != T(com.creditflow.report.dto.ReportType).SELLER_PERFORMANCE or hasRole('ADMIN')")
```
sur `report(...)` et `export(...)`. Tout le reste (y compris `DEFAULT_RATE`) reste accessible à tout utilisateur authentifié, comme les rapports existants (aucune règle de rôle aujourd'hui dans `SecurityConfig` au-delà de `authenticated()` — la restriction est entièrement portée par ce `@PreAuthorize`, premier de ce pattern conditionnel-au-paramètre dans la base : à valider explicitement par test, voir Plan de tests).

Le filtrage de la liste des rapports visibles côté `ReportsPage.tsx` est un confort UX ; il ne remplace pas la vérification backend.

### `ReportService.build(...)`

```java
public ReportData build(ReportType type, LocalDate from, LocalDate to,
                         String profession, BigDecimal minAmount, BigDecimal maxAmount)
```

### `defaultRate(...)` — rapport `DEFAULT_RATE`

Population de départ : `saleRepository.findAllDetailed()` filtrée sur `status == SaleStatus.ACTIVE` (les contrats `CANCELLED` ne comptent pas dans un taux de défaut, les `COMPLETED` sont soldés donc hors sujet — même filtre que `outstanding()`).

Filtres appliqués **avant** regroupement, sur cette population :
- `minAmount`/`maxAmount` (si non nuls) sur `CreditSale.totalPrice`, bornes inclusives, indépendantes l'une de l'autre ;
- `profession` (si non nul/non vide) : ne garder que les contrats dont `Customer.profession` normalisée correspond (trim + `equalsIgnoreCase`) à la valeur passée (trim). Une valeur vide/blanche est traitée comme absente (pas de filtre).

Normalisation d'une profession : `raw == null || raw.isBlank() ? "Non renseignee" : raw.trim()` (pas de dédoublonnage de casse au-delà de ce trim — cf. risque déjà noté par l'architecte).

Retard : `Set<Long> lateSaleIds` et `Map<Long, BigDecimal> lateAmountBySale` calculés une fois via `installmentRepository.findLate(LocalDate.now())`, regroupés par `installment.getSale().getId()` (somme de `Installment.getRemaining()`), même définition du retard que `LATE_CUSTOMERS`.

Regroupement : par profession normalisée (`Collectors.groupingBy`, une entrée `LinkedHashMap`), tri des lignes résultantes par ordre alphabétique insensible à la casse de la profession (`Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)`).

**Colonnes** (labels sans accent, cohérent avec le style backend existant — `"Creances restantes"`, `"Deja paye"`, etc.) :

| # | Colonne (`Column`) | Type | Source |
|---|---|---|---|
| 1 | `text("Profession")` | TEXT | clé de regroupement |
| 2 | `number("Contrats actifs")` | NUMBER | taille du groupe |
| 3 | `number("Contrats en retard")` | NUMBER | contrats du groupe dont l'id ∈ `lateSaleIds` |
| 4 | `text("Taux de defaut")` | TEXT | `"%.1f %%"` (Locale.FRANCE) sur `lateCount * 100.0 / totalCount` — **TEXT**, pas NUMBER : `PdfReportExporter.amountFormat()` n'a aucune décimale (`"#,##0"`), un NUMBER tronquerait le pourcentage |
| 5 | `money("Montant en retard")` | MONEY | somme de `lateAmountBySale` pour les contrats du groupe |
| 6 | `money("Reste a payer")` | MONEY | somme de `CreditSale.remainingAmount` du groupe |

**Totaux** :
- `Total("Professions", (long) rows.size(), NUMBER)`
- `Total("Contrats actifs", (long) totalContracts, NUMBER)`
- `Total("Contrats en retard", (long) totalLate, NUMBER)`
- `Total("Taux de defaut global", "<pct texte>", TEXT)` (0,0 % si `totalContracts == 0`, éviter la division par zéro)
- `Total("Montant en retard", totalLateAmount, MONEY)`
- `Total("Reste a payer", totalRemaining, MONEY)`

`title = "Taux de defaut par profession"`, `period = "Au " + LocalDate.now().format(DATE)`.

### `sellerPerformance()` — rapport `SELLER_PERFORMANCE`

Population : `saleRepository.findAllDetailed()` filtrée sur `status != SaleStatus.CANCELLED` (un contrat annulé n'est pas une performance commerciale à créditer ni à charger au vendeur). Pas de filtres `profession`/`minAmount`/`maxAmount` (hors périmètre AC1, qui ne cible que « le rapport de défaut »).

Résolution du vendeur, **avant** regroupement (pour que les cas non résolus fusionnent dans un seul bucket) :

```java
Map<String, String> fullNameByUsername = userRepository.findAll().stream()
        .collect(Collectors.toMap(User::getUsername, User::getFullName, (a, b) -> a));

private String sellerLabel(String createdBy) {
    if (createdBy == null) return "Non attribue";
    return fullNameByUsername.getOrDefault(createdBy, "Non attribue");
}
```
Regroupement par `sellerLabel(sale.getCreatedBy())` — un `createdBy` null **ou** ne correspondant à aucun `User` (compte supprimé, désactivé) tombe dans le **même** bucket `"Non attribue"`, jamais exclu (décision explicite de l'architecte).

Retard : mêmes `lateSaleIds`/`lateAmountBySale` que `defaultRate()` (calculés localement dans cette méthode, pas de champ partagé nécessaire).

Tri des lignes : par « Montant encaisse » décroissant, égalité départagée par libellé vendeur croissant (déterministe pour les tests).

**Colonnes** :

| # | Colonne | Type | Source |
|---|---|---|---|
| 1 | `text("Vendeur")` | TEXT | `sellerLabel(...)` |
| 2 | `number("Contrats")` | NUMBER | taille du groupe |
| 3 | `money("Montant finance")` | MONEY | somme `CreditSale.financedAmount` |
| 4 | `money("Montant encaisse")` | MONEY | somme `CreditSale.amountPaid` (**pas** `Payment.createdBy`, cf. décision architecte : `amountPaid` trace la performance commerciale du vendeur, `Payment.createdBy` trace qui a encaissé physiquement, souvent un caissier différent) |
| 5 | `money("Reste a recouvrer")` | MONEY | somme `CreditSale.remainingAmount` |
| 6 | `number("Contrats en retard")` | NUMBER | contrats du groupe dont l'id ∈ `lateSaleIds` |
| 7 | `text("Taux de defaut")` | TEXT | même format que `defaultRate()` |

**Totaux** :
- `Total("Vendeurs", (long) rows.size(), NUMBER)`
- `Total("Contrats", (long) totalContracts, NUMBER)`
- `Total("Montant finance total", totalFinanced, MONEY)`
- `Total("Montant encaisse total", totalPaid, MONEY)`
- `Total("Reste a recouvrer total", totalRemaining, MONEY)`

`title = "Performance vendeur"`, `period = "Au " + LocalDate.now().format(DATE)`.

### Exports (AC3)

Aucun changement dans `ExcelReportExporter`/`PdfReportExporter` : les deux nouveaux types traversent le même `ReportController.export(...)` générique (aucun branchement par `type` dans ce contrôleur pour le choix de l'exporteur). La preuve d'exportabilité repose sur le fait que `ReportData` reste structurellement identique (colonnes/lignes/totaux typés) — voir Plan de tests pour la vérification.

### Migration — décision tranchée sur `V10`

**Pas de migration `V10__report_default_rate_indexes.sql` dans cette itération.** L'agrégation des deux nouveaux rapports se fait entièrement en mémoire via `saleRepository.findAllDetailed()` (aucun `WHERE`/`ORDER BY` SQL sur `created_by` n'est introduit — le regroupement par vendeur se fait côté Java après chargement complet, exactement comme `outstanding()`). Un index sur `credit_sales(created_by)` n'apporterait donc aucun bénéfice de plan d'exécution pour cette implémentation. Précédent cohérent : `V3__audit_columns.sql` a ajouté `created_by`/`updated_by` sur `customers`, `products`, `credit_sales`, `payments` sans jamais indexer ces colonnes, alors qu'elles sont déjà utilisées en filtrage/tri implicite ailleurs dans l'app. À reconsidérer uniquement si une future évolution ajoute une requête SQL filtrant/triant explicitement par `created_by` (ex. endpoint paginé « ventes par vendeur »), ou si le chargement complet de `findAllDetailed()` lui-même devient un problème de performance (risque déjà identifié séparément par l'architecte, orthogonal à l'indexation).

## Plan de tests

| Critère d'acceptation | Test | Type |
|---|---|---|
| AC1 — filtrer le rapport de défaut par tranche de montant ou profession | `ReportServiceTest#defaultRate_filtreParProfession` : avec `profession="Enseignant"`, seules les lignes correspondant (trim + ignoreCase) apparaissent | Unitaire |
| AC1 (suite) | `ReportServiceTest#defaultRate_filtreParTrancheDeMontant` : `minAmount`/`maxAmount` réduisent la population de contrats avant regroupement, testés indépendamment et combinés | Unitaire |
| AC1 (suite) | `ReportServiceTest#defaultRate_professionNonRenseigneeRegroupee` : profession `null`/blanche → bucket `"Non renseignee"` | Unitaire |
| AC1 (suite) | Depuis `/rapports` en tant que gérant, sélectionner « Taux de défaut », saisir profession + tranche de montant, vérifier la mise à jour du tableau | Manuel (pas d'infra de test frontend dans le repo) |
| AC2 — rapport performance vendeur visible ADMIN uniquement | `ReportControllerSecurityTest#sellerCannotReadSellerPerformance` : `GET /api/reports/SELLER_PERFORMANCE` en rôle `SELLER` → 403 | Intégration (`@WebMvcTest`) |
| AC2 (suite) | `ReportControllerSecurityTest#sellerCannotExportSellerPerformance` : `GET /api/reports/SELLER_PERFORMANCE/export?format=pdf` en `SELLER` → 403 | Intégration |
| AC2 (suite) | `ReportControllerSecurityTest#adminCanReadSellerPerformance` : même appel en `ADMIN` → 200 | Intégration |
| AC2 (suite) | `ReportControllerSecurityTest#sellerCanReadDefaultRate` : `GET /api/reports/DEFAULT_RATE` en `SELLER` → 200 (non-régression : le `@PreAuthorize` conditionnel ne doit pas sur-restreindre) | Intégration |
| AC2 (suite) | Connecté en `SELLER` sur `/rapports`, vérifier que le bouton « Performance vendeur » n'apparaît pas | Manuel |
| AC3 — les deux rapports exportables au même format que l'existant | `ReportServiceTest#defaultRateEstExportable` / `#sellerPerformanceEstExportable` : construire `ReportData` via `reportService.build(...)`, l'exporter avec de vraies instances `ExcelReportExporter`/`PdfReportExporter`, assert `bytes.length > 0` sans exception | Unitaire/intégration légère |
| AC3 (suite) | `ReportControllerSecurityTest#adminCanExportSellerPerformanceInPdfAndExcel` (via `@Import({ExcelReportExporter.class, PdfReportExporter.class})` dans le test, `AppProperties` déjà fourni par `AbstractWebMvcSecurityTest.TestSecurityBeans`) : 200 + `Content-Type`/`Content-Disposition` corrects pour `format=pdf` et `format=excel` | Intégration |
| AC3 (suite) | Depuis `/rapports`, exporter « Taux de défaut » et « Performance vendeur » en PDF et Excel, ouvrir les fichiers et vérifier une mise en page identique aux rapports existants | Manuel |

## Écarts identifiés

- **Interprétation de « tranche de montant » (AC1).** Le ticket évoque une « tranche de montant », ce qui peut suggérer des paliers prédéfinis (ex. « 0–500k », « 500k–1M », …) plutôt qu'une saisie libre. Le design écarte explicitement les « tranches configurables par le gérant » du périmètre et cette spec retient une saisie libre `minAmount`/`maxAmount`, qui satisfait littéralement l'exigence de filtrage sans imposer de table de paramétrage. Non bloquant pour cette itération, mais à confirmer avec le porteur produit si des paliers fixes étaient attendus.
- **Absence de dimension temporelle sur « Performance vendeur ».** Le récit utilisateur mentionne « suivre l'activité de mon équipe », ce qui a une connotation de période (ex. performance du mois en cours), alors que le design (et cette spec) livre un cumul « au jour J » sans filtre `from`/`to`, à l'image de `OUTSTANDING`. Aucun des 3 critères d'acceptation n'exige explicitement un tel filtre, donc ce n'est pas bloquant, mais c'est un écart potentiel entre l'intention du récit et le livrable qui mérite d'être tranché avant une itération ultérieure (filtre par mois/période sur ce rapport).
