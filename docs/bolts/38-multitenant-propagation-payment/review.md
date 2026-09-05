# Review — #38 Multi-tenant 5/10 — Propagation Payment

APPROVE

## Critères d'acceptation

| Critère | Statut | Preuve |
|---|---|---|
| Aucun endpoint paiement n'expose de donnée d'une autre organisation, y compris via `findByCustomer`/`findBySale` | Couvert | `PaymentSpecifications.inOrganization` (jointure `sale.shop.organization.id`) combinée dans `PaymentService.search` (`PaymentService.java:70`), testée par `PaymentSpecificationsTest.inOrganizationFiltersOnSaleShopOrganizationId` (mock Root/CB) et `PaymentServiceTest.search_combinesOrganizationFilter` (vérifie l'appel à `currentOrganizationId()`). `findByCustomer`/`findBySale`/`sumByCustomer`/`findBySaleIdOrderByPaymentDateAscIdAsc`/`findByClientRequestId` : audit des appelants refait indépendamment (voir Vérifications ci-dessous) — chaque appel est bien précédé d'un `assertAccessible`/`getEntity`/`customerService.findById` qui scope déjà l'organisation via `accessibleShopIds()`. Documenté par javadoc sur les 5 méthodes du repository + le service, conformément au contrat #36/#37. |
| Instance mono-tenant : comportement strictement identique à aujourd'hui | Couvert | `currentOrganizationId()` (`CurrentShopContext.java:44-46`) retourne toujours une valeur non nulle et constante par utilisateur ; `inOrganization(...)` ne peut donc jamais retirer de ligne en mono-organisation. `DashboardServiceTest`/`ReportServiceTest` adaptés à la nouvelle arité et toujours verts. |

## Vérifications effectuées (indépendamment du rapport du codeur)

1. **Alias JPQL** (`PaymentRepository.java`) : lu le fichier intégralement.
   - `findBetweenForShops` (l.54-66) : `JOIN FETCH p.sale s` déclare l'alias `s` → `AND s.shop.organization.id = :organizationId` est correct.
   - `sumBetweenForShops` (l.68-73) et `countBetweenForShops` (l.75-80) : pas d'alias `s` dans ces requêtes (`FROM Payment p` seul) → `AND p.sale.shop.organization.id = :organizationId` est correct (chemin complet depuis `p`).
   - Pas de faute de frappe croisée entre les deux formes.

2. **Exhaustivité des appelants** des 3 méthodes de repository modifiées — grep sur tout `backend/src` :
   - `findBetweenForShops`/`sumBetweenForShops`/`countBetweenForShops` : seuls appelants en prod = `DashboardService` (4 appels) et `ReportService` (1 appel, via `payments(...)`) — tous mis à jour avec `organizationId`. Aucun appelant orphelin resté à l'ancienne arité (aurait cassé la compilation, donc le `mvn test` vert le confirme aussi).

3. **Audit des méthodes volontairement inchangées** (`findByCustomer`, `findBySale`, `sumByCustomer`, `findBySaleIdOrderByPaymentDateAscIdAsc`, `findByClientRequestId`), refait par lecture directe (pas pris pour argent comptant depuis design.md) :
   - `PaymentService.findByCustomer`/`PaymentRepository.sumByCustomer` : unique appelant `CustomerProfileService.profile` (l.36-42), qui appelle `customerService.findById(customerId)` en premier → `CustomerService.getEntity` (l.84-89) fait `assertAccessible(customer.getShop().getId())` avant toute lecture des paiements. Confirmé.
   - `PaymentService.findBySale` : valide elle-même l'accès (`saleRepository.findDetailById` + `assertAccessible`) avant d'appeler le repository (`PaymentService.java:94-98`). Appelée par `SaleController.payments()`.
   - `PaymentRepository.findBySale` appelé directement par `CreditSaleService.findDetail` (l.106) et `CreditSaleService.delete` (l.262) : les deux passent par `getEntity(id)` (l.142-147) qui fait `assertAccessible` avant. Confirmé.
   - `findBySaleIdOrderByPaymentDateAscIdAsc` : appelée dans `PaymentService.delete` après `assertAccessible(sale.getShop().getId())` (l.199, avant l.211). Confirmé.
   - `findByClientRequestId` : `assertAccessible` exécuté juste après lecture, avant tout retour (`PaymentService.java:126-129`). Confirmé.
   - Conclusion : aucune de ces méthodes n'est atteignable sans passage préalable par un contrôle d'accès scoping déjà l'organisation via `accessibleShopIds()`. L'absence de filtre organisation direct est donc correcte, pas une régression de sécurité.

4. **Endpoints RBAC** (`PaymentController.java`) : `GET /{id}`, `GET /{id}/receipt`, `POST` (rejeu idempotent), `DELETE` (`@PreAuthorize("hasRole('ADMIN')")`) passent tous par des méthodes de service qui font `assertAccessible` avant toute exposition de données. `GET /api/payments` (search) et le dashboard/rapports bénéficient désormais en plus du filtre `inOrganization` explicite. Rien d'inchangé en RBAC (hors périmètre du ticket).

5. **Tests** : relecture ligne à ligne des diffs de `PaymentSpecificationsTest`, `PaymentServiceTest`, `DashboardServiceTest`, `ReportServiceTest` — correspondent exactement au plan de tests du spec.md (mêmes valeurs stubbées `100L`/`200L`/`10L`, mêmes `verify`). Pas de test tautologique détecté : le test `inOrganizationFiltersOnSaleShopOrganizationId` vérifie effectivement la chaîne de `.get(...)` et l'appel `cb.equal`, pas juste que la méthode ne lève pas d'exception. Le test `search_combinesOrganizationFilter` est minimal (vérifie seulement l'appel à `currentOrganizationId()`) mais cohérent avec le reste du fichier (aucun test préexistant sur `search` avant ce ticket) et avec la spec qui prescrit exactement cette assertion.

6. **Pas de migration Flyway, pas de dénormalisation** — cohérent avec design.md (jointure uniquement), pas de fichier `db/migration` touché dans le diff.

7. **Séquencement #37** : vérifié que `CreditSaleRepository`/`InstallmentRepository` n'ont aucune signature `organizationId` sur cette branche — aucune dépendance introduite vers #37, conforme à la note de séquencement du ticket.

## Build/tests

- `cd backend && mvn test` → **BUILD SUCCESS**, `Tests run: 382, Failures: 0, Errors: 0, Skipped: 0`.
- Sous-suite ciblée `mvn -Dtest="PaymentServiceTest,PaymentSpecificationsTest,DashboardServiceTest,ReportServiceTest" test` → 40/40 verts (isolé le test unitaire de la propagation).
- **Limitation d'environnement signalée** : tentative de démarrer un vrai contexte Spring (via `docker compose up db` + boot applicatif) pour détecter une éventuelle erreur de parsing JPQL non couverte par les tests mockés — le démon Docker de la sandbox n'a pas répondu (`docker info`/`docker ps` restent bloqués indéfiniment, Docker Desktop pourtant listé dans les process). Aucun `@SpringBootTest`/`@DataJpaTest` n'existe dans ce backend (confirmé par grep, cohérent avec design.md) : la suite `mvn test` ne charge donc jamais l'`EntityManagerFactory` complet et ne peut pas, par construction, détecter une erreur de parsing JPQL au démarrage. En compensation, les 3 `@Query` modifiées ont été relues et validées manuellement alias par alias (point 1 ci-dessus) — vérification déterministe et sans ambiguïté pour ce type d'erreur (alias déclaré vs. chemin complet). Risque résiduel jugé très faible : le patron est identique à celui déjà en production depuis #36 (`inShops` sur les mêmes requêtes, `s.shop.id` / `p.sale.shop.id` déjà présents et fonctionnels avant ce ticket).

## Conclusion

Diff conforme au contrat technique du spec.md/design.md, aucune régression détectée, alias JPQL corrects, tous les appelants mis à jour, audit des méthodes non filtrées directement re-vérifié et confirmé sûr par construction, tests significatifs et suite complète verte (382/382).
