# Review -- #35 Multi-tenant 2/10 -- Scoping ADMIN par organisation (CurrentShopContext)

## Verdict

APPROVE

## Critères d'acceptation

| # | Critère | Statut |
|---|---|---|
| 1 | Un ADMIN d'une organisation ne voit plus, via `accessibleShopsOf`, que les boutiques de sa propre organisation. | Couvert -- `CurrentShopContext.java:60` remplace l'appel non filtré par `shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(user.getOrganization().getId())`, exactement le contrat technique de la spec. Test `accessibleShopIdsForAdminWithoutAssignmentIsIsolatedByOrganization` (`CurrentShopContextTest.java:91-104`) vérifie le résultat filtré et `verify(shopRepository, never())...(organizationB.getId())`. |
| 2 | Instance mono-tenant : comportement strictement identique à aujourd'hui, non-régression testée. | Couvert -- `accessibleShopIdsForAdminWithoutAssignment` (`CurrentShopContextTest.java:78-88`) adapté avec une seule `Organization`, deux boutiques, assertion `containsExactly(1L, 2L)` identique au comportement pré-changement. |
| 3 | Tests couvrant le cas ADMIN sans boutique assignée dans une base multi-organisations. | Couvert -- test d'isolation (ci-dessus) + `accessibleShopIdsForAdminWithoutAssignmentInOrganizationWithoutActiveShops` (`CurrentShopContextTest.java:107-118`, liste vide sans exception sur `accessibleShopIds()`/`resolveReadFilter()`) + `shopIdForCreationFailsForAdminWithoutAccessibleShops` (`CurrentShopContextTest.java:121-132`, `BusinessRuleException` sans assertion sur le message, conforme à la décision tranchée de la spec). |

## Vérifications complémentaires

- **Contrat technique respecté à la lettre** : signature `List<Shop> findAllByActiveTrueAndOrganizationIdOrderByNameAsc(Long organizationId)` (`ShopRepository.java:18`), méthode dérivée sans `@Query`, cohérente avec les conventions existantes du fichier. Le champ `Shop.organization` (`Shop.java:47-48`) confirme que la traversée dérivée `OrganizationId` -> `organization.id` est valide ; le démarrage réussi de tous les tests `@SpringBootTest` de la suite (ex. `SupplierControllerSecurityTest`) confirme indirectement que Spring Data a bien pu parser cette méthode dérivée au boot (sinon échec immédiat au démarrage du contexte).
- **`findAllByActiveTrueOrderByNameAsc()` intacte** (`ShopRepository.java:16`) : grep confirme son seul appelant de production restant est `DemoDataSeeder.java:80`, non touché, ainsi que `DemoDataSeederTest.java` (absent de la liste des fichiers modifiés). Conforme à l'écart identifié dans `spec.md`.
- **Décision "organisation sans boutique active"** respectée telle quelle : aucune modification du message `"Vous etes rattache a plusieurs boutiques : ..."` en ligne 106-108 de `CurrentShopContext.java`, uniquement verrouillée par un test qui ne fait pas d'assertion sur le libellé (`shopIdForCreationFailsForAdminWithoutAccessibleShops`). Pas de sur-ingénierie.
- **Écart `AuthServiceTest.java` vérifié et légitime** : le test `loginResolvesAccessibleShopsWhileStillAnonymous` (ligne 159-175) instancie un `CurrentShopContext` réel (pas un mock) pour exercer le vrai code de résolution au login ; le `user` de fixture (`@BeforeEach`, ligne 69-79) n'avait pas d'`Organization` avant ce ticket, ce qui aurait provoqué une `NullPointerException` réelle sur `user.getOrganization().getId()` avec le nouveau code de production -- pas un contournement artificiel. La correction est strictement minimale : ajout d'un `Organization.builder().id(1L)...build()` à la fixture partagée et adaptation du stub `ShopRepository` vers la nouvelle méthode (lignes 4, 81, 166). Aucun élargissement de périmètre : les autres tests de la classe utilisent `CurrentShopContext` mocké et ne sont pas affectés par l'ajout du champ `organization` sur la fixture.
- **Aucun fichier hors périmètre touché** : `git diff bolt/issue-34-multi-tenant-fondation-organization..HEAD --name-only` liste exactement `CurrentShopContext.java`, `ShopRepository.java`, `AuthServiceTest.java`, `CurrentShopContextTest.java`, plus `design.md`/`spec.md`. Pas de `Shop.java`, `User.java`, `Organization.java`, `OrganizationRepository.java`, controller, endpoint, frontend, ni migration Flyway.
- **Qualité des tests** : les 4 nouveaux/adaptés tests correspondent exactement au plan de tests de la spec (stubs par `organizationId`, `verify(never())` pour l'isolation, listes vides sans exception, `BusinessRuleException` sans assertion de message). Remarque mineure non bloquante : le `verify(never())` de l'isolation est garanti par construction dès lors que le code de production n'appelle jamais qu'avec `user.getOrganization().getId()` -- il s'agit d'un test de régression utile (verrouille le comportement si le code venait à changer) plutôt que d'une preuve d'isolation au niveau base de données réelle ; cohérent avec le choix assumé dans la spec de ne pas ajouter de `@DataJpaTest` (convention déjà en place, aucun test dédié existant pour `ShopRepository`/`OrganizationRepository`).
- **Reproductibilité pour la chaîne #36-#43** : le patron (méthode dérivée Spring Data filtrée par `organizationId`, filtrage au niveau requête et non en mémoire, source de l'organisation = `user.getOrganization()` rechargé depuis la base, pas de claim JWT) est clair et bien documenté dans `design.md`/`spec.md`. Note pour la suite (non bloquante ici) : `Customer`/`Product` n'ont pas encore de colonne `organization_id` directe (vérifié par grep) -- les tickets suivants devront probablement filtrer via une jointure `shop.organization` plutôt que reproduire une méthode dérivée à un seul niveau comme ici ; à anticiper dans le design des tickets #36+.

## Build/tests

- `cd backend && mvn -q -Dtest=CurrentShopContextTest,AuthServiceTest,DemoDataSeederTest test` -> succès (exit code 0).
- `cd backend && mvn test` -> `BUILD SUCCESS`, `Tests run: 370, Failures: 0, Errors: 0, Skipped: 0`. Suite complète lancée moi-même, pas seulement le rapport du codeur.

## Conclusion

Le code correspond exactement au contrat technique de la spec, les trois critères d'acceptation sont couverts par du code ET des tests qui échoueraient sans le fix (vérifié par lecture, pas seulement par les noms de tests), aucun fichier hors périmètre n'a été touché, l'écart `AuthServiceTest` est légitime et minimal, et la suite complète est verte. Aucun finding bloquant.
