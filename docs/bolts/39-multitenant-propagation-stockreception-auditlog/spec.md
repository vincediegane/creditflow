# Spec — #39 Multi-tenant 6/10 — Propagation StockReception/AuditLog

## Résumé

Ajout d'un filtrage explicite par organisation sur la recherche des réceptions de stock (`StockReceptionSpecifications`/`StockReceptionService`), accompagné de la documentation de l'audit — sans changement de code — de `AuditLogAccessGuard` et `ReminderService`, déjà sûrs par construction.

## Tâches

- [ ] `backend/src/main/java/com/creditflow/supplier/repository/StockReceptionSpecifications.java` — ajouter `Specification<StockReception> inOrganization(Long organizationId)` (sous-requête `EXISTS` sur `StockReceptionLine`, même structure que `inShops`, cf. Contrat technique).
- [ ] `backend/src/main/java/com/creditflow/supplier/service/StockReceptionService.java` — dans `search`, combiner `StockReceptionSpecifications.inOrganization(currentShopContext.currentOrganizationId())` avec `inShops(currentShopContext.accessibleShopIds())` déjà présent. Ne pas toucher `getEntity` ni `receive` (déjà sûrs, voir Décisions du design — aucun changement attendu).
- [ ] `backend/src/test/java/com/creditflow/supplier/repository/StockReceptionSpecificationsTest.java` — ajouter deux cas : `inOrganization` retourne `null` si `organizationId` est `null` ; `inOrganization` génère bien le prédicat `EXISTS` attendu sur la chaîne `line -> product -> shop -> organization -> id` (mêmes mocks Root/Subquery/CriteriaBuilder que `inShopsFiltersOnLineProductShop`).
- [ ] `backend/src/test/java/com/creditflow/supplier/service/StockReceptionServiceTest.java` — étendre `search_filtersOnAccessibleShops` (la renommer `search_filtersOnAccessibleShopsAndOrganization` est acceptable) pour vérifier que `currentShopContext.currentOrganizationId()` est bien appelé en plus de `accessibleShopIds()` lors de `search`. Stubber `currentOrganizationId()` dans `setUp()`.
- [ ] Aucune modification de `backend/src/main/java/com/creditflow/audit/service/AuditLogAccessGuard.java` — voir Écarts identifiés / point de vigilance ci-dessous. Ne pas ajouter de cas `STOCK_RECEPTION` : la conclusion de l'audit (voir design, section Décisions clés) est que le `default -> throw ResourceNotFoundException` couvre déjà ce type de manière sûre, et le cas `CREDIT_SALE` délègue déjà à `CreditSaleService.getEntity()` qui est sûr indépendamment de #37.
- [ ] Aucune modification de `backend/src/main/java/com/creditflow/notification/service/ReminderService.java` — les trois chemins (`prepareForSale`, `prepareForCustomer`, `sendAll`) sont déjà sûrs par construction (voir design, section Décisions clés). Aucun changement de code attendu, y compris commentaire.
- [ ] (optionnel, non bloquant) `backend/src/test/java/com/creditflow/audit/service/AuditLogAccessGuardTest.java` — si le codeur souhaite documenter explicitement la conclusion de l'audit dans le code de test, ajouter une assertion dédiée `auditLogAccessGuard.assertReadable("STOCK_RECEPTION", 1L)` lève `ResourceNotFoundException`, en plus de `rejectsGlobalOrUnknownEntityType` qui couvre déjà ce cas de façon générique. Cette tâche ne modifie aucun fichier de production.

## Contrat technique

`StockReceptionSpecifications.inOrganization` — nouvelle méthode, à ajouter juste après `inShops` :

```java
/**
 * Une reception appartient a l'organisation de la boutique de ses produits (via ses
 * lignes). Meme structure de sous-requete EXISTS que inShops, un get(...) de plus dans
 * la chaine du predicat.
 */
public static Specification<StockReception> inOrganization(Long organizationId) {
    if (organizationId == null) {
        return null;
    }
    return (root, query, cb) -> {
        Subquery<Long> lines = query.subquery(Long.class);
        Root<StockReceptionLine> line = lines.from(StockReceptionLine.class);
        lines.select(line.get("id"));
        lines.where(cb.equal(line.get("reception"), root),
                cb.equal(line.get("product").get("shop").get("organization").get("id"), organizationId));
        return cb.exists(lines);
    };
}
```

`StockReceptionService.search` — remplacer la construction de la spécification :

```java
Page<StockReception> page = stockReceptionRepository.findAll(
        Specs.combine(StockReceptionSpecifications.forSupplier(supplierId),
                StockReceptionSpecifications.inShops(currentShopContext.accessibleShopIds()),
                StockReceptionSpecifications.inOrganization(currentShopContext.currentOrganizationId())),
        pageable);
```

Aucune signature d'endpoint HTTP, de DTO ou de schéma de base ne change : c'est un ajout de filtrage interne uniquement.

## Plan de tests

| Critère d'acceptation du ticket | Test |
|---|---|
| Aucun endpoint réception de stock n'expose de donnée d'une autre organisation — recherche/liste | `StockReceptionSpecificationsTest.inOrganizationFiltersOnLineProductShopOrganization` (nouveau) + `StockReceptionServiceTest.search_filtersOnAccessibleShopsAndOrganization` (étendu) : vérifie que `search` combine bien `inOrganization` et `inShops`. |
| Aucun endpoint réception de stock n'expose de donnée d'une autre organisation — accès direct par id | Déjà couvert, inchangé : `StockReceptionServiceTest.getEntity_rejectsReceptionFromAnotherShop` (existant, ne pas modifier). |
| Aucun endpoint réception de stock n'expose de donnée d'une autre organisation — création | Déjà couvert, inchangé : `StockReceptionServiceTest.receive_rejectsProductFromAnotherShop` (existant, ne pas modifier). |
| Aucun endpoint journal d'audit n'expose de donnée d'une autre organisation | Déjà couvert, inchangé : `AuditLogAccessGuardTest.delegatesToOwningModule`, `propagatesRefusalForAnotherShop`, `rejectsGlobalOrUnknownEntityType` (existants, aucune modification de production nécessaire ; ce dernier test couvre déjà génériquement le cas `"STOCK_RECEPTION"` via le chemin `default`). Extension optionnelle listée ci-dessus pour traçabilité explicite. |
| Instance mono-tenant : comportement strictement identique à aujourd'hui — StockReception | `StockReceptionServiceTest.search_filtersOnAccessibleShopsAndOrganization` doit continuer à retourner une spécification non nulle sans changer le résultat métier ; pas d'infrastructure `@DataJpaTest` disponible sur ce projet (confirmé, comme en #36), donc pas de test d'intégration base réelle possible ici. Complément : test manuel — sur l'environnement mono-tenant existant, comparer la liste des réceptions de stock retournée par `GET` avant/après déploiement pour un même utilisateur (doit être identique, `inOrganization` ne restreint jamais quand une seule organisation existe). |
| Instance mono-tenant : comportement strictement identique à aujourd'hui — Audit/Reminder | Manuel : aucune modification de code sur `AuditLogAccessGuard`/`ReminderService`, donc comportement mono-tenant nécessairement inchangé par construction (pas de test dédié requis, les suites existantes `AuditLogAccessGuardTest`/`ReminderServiceTest` continuent de passer sans modification). |

## Écarts identifiés

Aucun écart matériel entre `design.md` et le ticket #39 : le design couvre les deux critères d'acceptation, et l'audit exhaustif (grep backend + `frontend/src/types.ts:512`) confirmant l'absence de tout cas `STOCK_RECEPTION` dans `AuditLogAccessGuard` est cohérent avec le fait qu'aucune UI n'affiche d'historique d'audit pour les réceptions de stock.

Deux points de vigilance à ne pas laisser dériver en scope creep pendant le codage :

1. Le corps du ticket dit « `AuditLogAccessGuard.assertReadable` : vérifier que la délégation à `getEntity()` des services métier reste sûre » et « `ReminderService` : audit du filtrage par organisation ». Cette formulation peut être lue à tort comme demandant un changement de code. Le design tranche explicitement que non (voir Hors périmètre du design : « Tout changement de code dans `ReminderService`/`AuditLogAccessGuard` au-delà de la documentation de l'audit »). Le codeur ne doit **pas** ajouter de cas `STOCK_RECEPTION`, de commentaire, ni de garde supplémentaire dans ces deux fichiers.
2. `StockReceptionService.search` exécutera désormais deux sous-requêtes `EXISTS` distinctes sur `StockReceptionLine` (une pour `inShops`, une pour `inOrganization`), fonctionnellement redondantes tant que `accessibleShopIds()` n'est pas corrompu. C'est un coût de performance mineur assumé (même patron de défense en profondeur que #36/#37/#38) — ne pas « optimiser » en supprimant l'un des deux filtres.
