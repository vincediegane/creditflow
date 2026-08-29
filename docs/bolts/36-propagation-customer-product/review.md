# Review — #36 Multi-tenant 3/10 — Propagation Customer/Product

CHANGES_REQUESTED

## Criteres d'acceptation

| Critere | Statut |
|---|---|
| Aucun endpoint client/produit n'expose de donnee d'une autre organisation | Partiel -- le filtrage inOrganization / AND ... shop.organization.id = :organizationId est correctement cable sur search/quickSearch/findAllForSelect/categories (Customer + Product), et la fuite reelle de LegacyImportService.resolveProduct est corrigee dans le code de production. Mais le test cense prouver la non-reutilisation cross-boutique (doesNotReuseProductWithSameNameFromAnotherShop) ne peut pas detecter une regression sur ce point precis (voir Finding 1) -- la couverture ne satisfait donc pas la barre "un test qui echouerait si le code etait retire" sur le point le plus sensible du ticket. |
| Instance mono-tenant : comportement strictement identique a aujourd'hui | Couvert -- inOrganization(null) retourne null (aucun predicat ajoute), et toute la suite CustomerServiceTest/ProductServiceTest/*SpecificationsTest/LegacyImportServiceTest existante reste verte. Raisonnement structurel valide (une seule Organization en mono-tenant, donc predicat toujours vrai), conforme a ce que le design/spec annoncait comme seule preuve disponible sans @DataJpaTest. |

## Findings

### 1. (Bloquant) Le nouveau test LegacyImportServiceTest.doesNotReuseProductWithSameNameFromAnotherShop ne teste pas ce qu'il pretend tester

backend/src/test/java/com/creditflow/dataimport/service/LegacyImportServiceTest.java:117-148

Le test cree existingInOtherShop (id=30, shop=2) mais ne le cable jamais a un stub Mockito : productRepository.findFirstByNameIgnoreCaseAndShop_Id n'est stubbe que pour ("Tecno Spark", 1L) -> Optional.empty() (ligne 129). Or Mockito retourne par defaut Optional.empty() pour tout appel non stubbe d'une methode de retour Optional<T> (comportement standard depuis Mockito 2, ReturnsEmptyValues). Consequence concrete :

- Si demain le code de production regressait -- par exemple en repassant targetShopId de resolveProduct a un mauvais id, ou en appelant findFirstByNameIgnoreCaseAndShop_Id(name, otherShop.getId()) par erreur -- la requete mockee resterait non stubbee pour ce cas, renverrait quand meme Optional.empty() par defaut, et le test continuerait de passer exactement comme aujourd'hui.
- Les seules assertions du test (getShop().getId() == 1L et getId() != existingInOtherShop.getId()) sont satisfaites uniquement parce que productRepository.save(...) est mocke pour fixer arbitrairement l'id a 21L (ligne 137) -- une comparaison a 30L qui est vraie par construction, independamment de l'argument shopId reellement transmis a findFirstByNameIgnoreCaseAndShop_Id.
- Aucune assertion/verify ne verifie l'argument shopId effectivement passe a findFirstByNameIgnoreCaseAndShop_Id (ni ici, ni dans importAssignsResolvedShopToNewCustomerAndProduct, ligne 83-115, qui teste en realite exactement le meme scenario "produit absent -> cree").

En l'etat, ce test est un quasi-doublon du test existant importAssignsResolvedShopToNewCustomerAndProduct et n'apporte aucune protection de regression sur la fuite inter-organisation qu'il est cense documenter -- alors que le code de production, lui, est correct (LegacyImportService.java:184 passe bien targetShopId a findFirstByNameIgnoreCaseAndShop_Id, symetrique a resolveCustomer).

Correction attendue : stubber findFirstByNameIgnoreCaseAndShop_Id("Tecno Spark", 2L) -> Optional.of(existingInOtherShop) (simulant la presence du produit homonyme dans l'autre boutique), puis verifier soit par verify(productRepository).findFirstByNameIgnoreCaseAndShop_Id(eq("Tecno Spark"), eq(1L)) que seul le shop cible est interroge, soit -- plus robuste -- que le produit utilise dans CreateSaleRequest/sauvegarde n'est jamais existingInOtherShop alors meme que le mock serait pret a le renvoyer si le code interrogeait le mauvais shop. Sans cette modification, un futur bug de scoping sur ce chemin ne serait detecte par aucun test.

### 2. (Mineur, non bloquant) CurrentShopContext.currentOrganizationId() n'a pas de test direct

backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java:43-45

La nouvelle methode (currentUser().getOrganization().getId()) n'est exercee que via des mocks (when(currentShopContext.currentOrganizationId()).thenReturn(...)) dans CustomerServiceTest/ProductServiceTest -- jamais testee dans son implementation reelle (CurrentShopContextTest n'a pas ete modifie). C'est conforme a la liste de taches de spec.md (qui ne mentionne pas CurrentShopContextTest), et le risque est faible vu la trivialite du code, mais cela merite d'etre signale : c'est le seul point du contrat technique qui reste non couvert par un test qui exercerait le vrai code plutot qu'un mock.

## Verifications effectuees (au-dela des findings)

- Contrat technique vs spec : signatures, ordre des parametres (organizationId en 3e position avant Pageable), corps de inOrganization, requetes @Query -- tout est identique mot pour mot au contrat de spec.md. Diff verifie fichier par fichier (CurrentShopContext, CustomerRepository, CustomerSpecifications, CustomerService, ProductRepository, ProductSpecifications, ProductService, LegacyImportService).
- LegacyImportService.resolveProduct/findProductByName : code reel relu (pas seulement le nom du test) -- resolveProduct (ligne 183-195) et l'appel dans importLegacySales (ligne 82) passent tous deux targetShopId a findFirstByNameIgnoreCaseAndShop_Id. La fuite decrite dans le design (reutilisation d'un produit homonyme d'une autre boutique/organisation, avec son prix/stock/shop d'origine) est bien fermee cote production, symetrique a resolveCustomer qui verifie deja customer.getShop().getId().equals(targetShopId).
- countByShop_IdIn : non touche (CustomerRepository.java:36, DashboardService.java:62-63), conforme a la decision tranchee de la spec.
- Fichiers hors perimetre : git diff --stat confirme que seuls les 8 fichiers de production + 5 fichiers de test + design.md/spec.md listes par le design sont modifies. Aucun UserService.java, ShopService.java, ShopRepository.java, aucune migration Flyway (backend/src/main/resources intact), aucun changement sur Customer.phone/cniNumber.
- Grep residuel : aucun appel restant a findFirstByNameIgnoreCase(String) (methode supprimee) ; aucun appel restant a quickSearch/findAllCategories avec l'ancienne arite cote repository. L'ajustement mecanique signale par le codeur (CustomerServiceTest never().quickSearch(any(), any(), any()) -> 4 arguments) est bien isole -- aucun autre site d'appel a l'ancienne signature n'a ete oublie, y compris dans GlobalSearchService/GlobalSearchServiceTest, qui appellent le niveau service (quickSearch(q, limit), arite inchangee) et non le repository.
- Qualite des autres tests neufs : *SpecificationsTest.inOrganization* suit fidelement le patron inShops deja en place (mocks Root/Path/CriteriaBuilder, verification des get(...) en chaine) -- assertions pertinentes. quickSearchPassesOrganizationIdToRepository/categoriesPassesOrganizationIdToRepository (Customer/Product) verifient bien la propagation exacte de l'id d'organisation avec verify(...).quickSearch(eq(...), eq(...), eq(organizationId), any()) -- assertions non triviales, corrects. Seul le test LegacyImportServiceTest (Finding 1) est en defaut.
- Coherence pour #37 (CreditSale) : le patron "jointure vers shop.organization.id + Specification/@Query etendue avec organizationId en parametre nomme" est applique de facon suffisamment mecanique et documentee (design + implementation identique entre Customer et Product) pour etre reproduit tel quel sur CreditSale, qui a une relation shop directe comme signale par le design. Aucune ambiguite relevee qui bloquerait #37.

## Build/tests

Commande lancee :

    cd backend
    mvn -DskipITs test

Resultat : Tests run: 378, Failures: 0, Errors: 0, Skipped: 0 -- BUILD SUCCESS. Confirme le rapport du codeur (378 tests, 0 echec). Aucun changement frontend dans ce bolt (backend uniquement, confirme par git diff --stat), pas de build frontend a lancer.

## Conclusion

Le code de production est correct et ferme reellement la fuite inter-organisation identifiee a l'audit (le point le plus important du ticket). Le blocage porte uniquement sur la rigueur du test de non-regression associe (Finding 1) : tel qu'ecrit, il ne validerait pas la correction s'il etait le seul filet de securite et ne detecterait pas une regression future sur ce chemin precis. A corriger avant merge -- changement localise, un seul test a renforcer.
