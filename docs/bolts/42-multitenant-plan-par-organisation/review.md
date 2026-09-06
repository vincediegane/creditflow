# Review — #42 Multi-tenant 9/10 — Modèle de plan par organisation en base

APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Deux organisations sur la même instance mutualisée peuvent avoir des formules différentes pour `multiShop` | Couvert — `OrganizationPlanResolver.multiShopEnabled(organizationId)` lit `organization_plan` par organisation, testé unitairement (`OrganizationPlanResolverTest.overridesWhenRowExists`, deux ids indépendants → deux valeurs différentes) et via RLS réel (`RowLevelSecurityIT.organizationPlanRowsAreIsolatedPerOrganization`, Testcontainers Postgres, org A `multi_shop=false` / org B `multi_shop=true`, chacune ne voit que sa propre ligne). Le gap préexistant sur `resolveDefaultOrganization()` (toujours la première organisation, table `organizations` hors RLS) est correctement documenté comme non testable bout-en-bout via l'API sans seed manuel — limite assumée et déjà hors périmètre de #34/#35/#25, pas introduite par ce ticket. |
| 2 | Instance mono-tenant sans table de plan renseignée : comportement identique au fallback `AppProperties.Plan` | Couvert — absence de ligne → `OrganizationPlanResolver` retombe sur `properties.getPlan().isMultiShop()` (`OrganizationPlanResolverTest.fallsBackToInstancePlanWhenNoRow`, deux valeurs de fallback testées). V17 n'insère aucune ligne. `ShopServiceTest`/`AuthServiceTest` couvrent la non-régression du chemin mono-tenant existant. |
| 3 | La limite `whatsappAuto` au niveau instance est documentée explicitement | Couvert — `spec.md` section "whatsappAuto — statut explicite" et `design.md` section "Décisions clés" expliquent précisément pourquoi (bean unique `NotificationChannel` par JVM, identifiants WhatsApp d'instance) et actent le statu quo sans ambiguïté. Vérifié qu'aucune colonne/méthode `whatsappAuto` n'a été ajoutée par erreur : `organization_plan` (V17), `OrganizationPlan`, `OrganizationPlanRepository`, `OrganizationPlanResolver` ne mentionnent `whatsappAuto` que dans des commentaires Javadoc/SQL, jamais en code fonctionnel. `AuthService.login()` continue de lire `properties.getPlan().isWhatsappAuto()` directement, inchangé. |

Aucun critère non couvert ni partiel.

## Cohérence avec spec.md / design.md

Le diff réel correspond exactement au contrat technique de `spec.md` (SQL, entité, repository, resolver, points d'appel `ShopService`/`AuthService`, adaptations de tests) — vérifié fichier par fichier via `git diff master..HEAD`, pas seulement sur la base du rapport du codeur. Aucune tâche de la checklist spec non faite, aucun écart non justifié.

Points spécifiques vérifiés :
- **V17/V18** : policy RLS `organization_plan_tenant_isolation` réutilise `app_current_org_id()` de V15, sur le même modèle direct que `shops_tenant_isolation` (jointure directe `organization_id`, pas de sous-requête inutile vu que la table porte directement la colonne). `FORCE ROW LEVEL SECURITY` présent. V18 est bien une migration séparée de V16 (confirmé par `git diff` : V16 non touchée) ; `git diff` confirme qu'aucun autre fichier de migration existant n'a été modifié. Numérotation Flyway séquentielle correcte (V16 → V17 → V18, pas de trou ni de collision).
- **`ShopService.create()`** : `resolveDefaultOrganization()` n'est appelé qu'une fois, l'instance résolue est réutilisée à la fois pour l'assertion de plan et pour `shop.setOrganization(organization)`. `resolveDefaultOrganization()` est un simple `findFirstByOrderByIdAsc()` (lecture pure, pas d'effet de bord type compteur/verrou), donc réduire de deux appels à un seul ne change aucun comportement observable au-delà de l'économie d'une requête — pas de régression.
- **`Organization` explicite ajoutée dans `ShopServiceTest`** (`allowsSameNameOnUpdate`, `rejectsReactivationOfSecondShopWhenPlanIsSingleShop`, `allowsUpdateOfSingleAlreadyActiveShopEvenWithSingleShopPlan`, `allowsUpdateOfAlreadyActiveShopAmongMultipleEvenWithSingleShopPlan`) : nécessaire et correcte, pas un contournement. `ShopService.update()` appelle désormais `shop.getOrganization().getId()` avant l'appel à `assertPlanAllowsActive` ; sans organisation explicite sur le `Shop` de test, NPE garanti. L'id `1L` choisi correspond à l'organisation stubée dans `setUp()` (`organizationRepository.findFirstByOrderByIdAsc()`), cohérent avec les stubs `organizationPlanResolver.multiShopEnabled(1L)` des mêmes tests — pas de valeur arbitraire qui masquerait un défaut de câblage.
- **Piège de mock** : vérifié ligne par ligne dans `ShopServiceTest`/`AuthServiceTest`, puis **reproduit manuellement** — stub `organizationPlanResolver.multiShopEnabled(1L)` inversé de `true` à `false` dans `allowsSecondActiveShopWhenPlanIsMultiShop`, suite complète relancée : le test échoue bien (`BusinessRuleException: Votre formule actuelle ne permet qu'une seule boutique active`), confirmant que le test exerce réellement le stub et qu'un défaut Mockito (`false` sur `boolean`) ne le ferait pas passer par accident. Fichier restauré ensuite (`git status` propre après restauration, confirmé). Même logique validée par lecture pour `AuthServiceTest.loginReflectsOrganizationOverrideOfMultiShop`, qui stubbe explicitement `properties.getPlan()` à `true` (défaut Mockito `new AppProperties.Plan()`) et `organizationPlanResolver.multiShopEnabled(1L)` à `false`, puis assert les deux valeurs opposées — preuve que c'est bien le resolver qui pilote `multiShop`, pas le fallback.
- Frontend : aucun fichier impacté par ce ticket, conforme à la spec (`PlanSummary` garde la même forme).

## Build/tests

- `mvn -Dtest='!*IT' test` (backend, hors IT nécessitant Docker) — **BUILD SUCCESS**, `Tests run: 410, Failures: 0, Errors: 0, Skipped: 0`.
- Vérification manuelle du piège de mock : stub inversé dans `ShopServiceTest.allowsSecondActiveShopWhenPlanIsMultiShop` → `mvn -Dtest='!*IT' test` échoue avec `Tests run: 410, Failures: 0, Errors: 1` sur exactement ce test ; fichier restauré, suite relancée → de nouveau **BUILD SUCCESS** 410/410.
- `RowLevelSecurityIT` (nécessite Docker/Testcontainers) non relancée dans cet environnement de revue (contrainte identique à celle du codeur) ; lue ligne par ligne — le nouveau test `organizationPlanRowsAreIsolatedPerOrganization` utilise la connexion applicative restreinte (`appConnection()`, rôle `creditflow_app`), donc exerce à la fois la policy RLS de V17 et le GRANT de V18, pas seulement la connexion admin.

## Conclusion

Diff conforme à la spec et au design, aucun bug ni régression identifié sur `ShopService`, `AuthService`, `OrganizationPlanResolver` ou les migrations SQL. Les trois critères d'acceptation sont couverts par du code et des tests qui échoueraient si le code était retiré ou si le mock était contourné (vérifié manuellement). Build et tests verts.
