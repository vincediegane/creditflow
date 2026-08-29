# Design -- #35 Multi-tenant 2/10 -- Scoping ADMIN par organisation (CurrentShopContext)

## Approche

Le trou de securite tient en deux lignes : `CurrentShopContext.accessibleShopsOf` (branche
`ADMIN` sans boutique assignee, ligne 59-60) delegue a
`ShopRepository.findAllByActiveTrueOrderByNameAsc()`, qui ne filtre par aucune organisation.
La correction suit exactement le patron deja en place pour `role`/`shops` : ajouter une
methode `ShopRepository` filtree par `organization_id`, et faire passer `user.getOrganization()`
(deja disponible sur l'entite `User` chargee par `currentUser()`/`accessibleShopIds(User)`,
cf. #34) a cette methode au lieu de l'appel non filtre. Aucune migration necessaire : la colonne
`shops.organization_id` (NOT NULL, indexee `idx_shops_organization`) existe deja depuis #34.
Compromis assume : on ne touche qu'a la branche "ADMIN sans boutique" de `accessibleShopsOf` --
la branche `user.getShops()` non vide (SELLER, ADMIN de boutique) n'est pas re-filtree par
organisation ici, car `Shop.organization` n'est pas encore exploitee au niveau assignation
utilisateur-boutique ; c'est un choix deliberement hors perimetre (voir "Hors perimetre"), le
ticket ne demandant de fermer que le cas ADMIN global.

## Fichiers/modules impactes

Modifies :
- `backend/src/main/java/com/creditflow/shop/repository/ShopRepository.java` -- nouvelle
  methode de requete filtree par organisation, remplacant l'usage de
  `findAllByActiveTrueOrderByNameAsc()` dans `CurrentShopContext` (la methode existante peut
  rester si d'autres appelants en dependent -- a verifier par le spec-writer/codeur, aucun autre
  appelant trouve dans `backend/src/main/java` a ce jour).
- `backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java` -- ligne 59-60 :
  `shopRepository.findAllByActiveTrueOrderByNameAsc()` remplace par l'appel filtre par
  `user.getOrganization()` (ou `user.getOrganization().getId()`).
- `backend/src/test/java/com/creditflow/common/security/CurrentShopContextTest.java` -- le test
  existant `accessibleShopIdsForAdminWithoutAssignment` (lignes 72-81) stubbe aujourd'hui
  `shopRepository.findAllByActiveTrueOrderByNameAsc()` ; il doit etre adapte pour stubber la
  nouvelle methode et fournir un `Organization` sur le `User` construit (sinon
  `NullPointerException` sur `user.getOrganization()`). Nouveaux cas a ajouter : ADMIN sans
  boutique dans une base multi-organisations (ne voit que les boutiques de sa propre
  organisation), et ADMIN sans boutique dans une organisation qui n'a elle-meme aucune boutique
  active (liste vide, pas d'exception -- a confirmer par le spec-writer selon le comportement
  attendu de `accessibleShopIds()`/`resolveReadFilter()` en aval).

Non modifies (a rappeler explicitement au spec-writer) : `Shop.java`, `User.java`,
`Organization.java`, `OrganizationRepository.java` (deja complets depuis #34) ; tout autre
repository/`Specifications` metier (`CustomerRepository`, `ProductRepository`,
`CreditSaleRepository`, `PaymentRepository`, etc. -- tickets #37+) ; tout controller/endpoint ;
le frontend ; aucune nouvelle migration Flyway (la colonne et son index existent deja).

## Decisions cles

- **Le filtre s'exprime au niveau `ShopRepository`** (nouvelle methode derivee, ex.
  `findAllByActiveTrueAndOrganizationOrderByNameAsc(Organization organization)` ou equivalent
  par `organizationId`), pas par un filtrage en memoire dans `CurrentShopContext` apres avoir
  charge toutes les boutiques. Coherent avec le patron `findAllByActiveTrueOrderByNameAsc()`
  deja en place (delegation de la requete au repository) et avec la strategie retenue en #25
  ("chaque requete, chaque Specification... doit filtrer par tenant") : le prochain ticket qui
  devra filtrer par organisation ailleurs peut suivre le meme patron plutot que de recharger
  tout puis filtrer en Java, ce qui serait a la fois moins performant (charge toutes les
  boutiques de toutes les organisations) et un contre-exemple pour la chaine de tickets
  suivante.
- **La source de l'organisation est `user.getOrganization()`, l'utilisateur deja rechargee
  depuis la base a chaque requete** (`currentUser()` via `UserRepository.findByUsernameIgnoreCase`),
  pas un claim JWT. Conforme a la decision actee en #25 ("le JWT n'est pas la source de verite
  du tenant") et au texte du ticket. Aucun changement necessaire sur `JwtService`/
  `JwtAuthenticationFilter`/`AppUserDetailsService`.
- **`Organization` est chargee via l'association JPA `@ManyToOne` deja existante sur `User`**,
  pas via une requete separee a `OrganizationRepository` : `user.getOrganization()` suffit a
  obtenir l'entite (proxy lazy) a passer au filtre du repository -- pas besoin de charger
  explicitement l'`id` ni de resoudre l'organisation autrement. Simplicite : aucun nouveau
  point de couplage vers `OrganizationRepository` dans `CurrentShopContext`.
- **Le nom de la nouvelle methode `ShopRepository` et sa signature exacte (Organization vs
  organizationId) sont laisses au spec-writer/codeur**, dans le respect strict des conventions
  Spring Data deja en usage dans ce fichier (methodes derivees du nom, pas de `@Query` -- aucune
  des methodes existantes de `ShopRepository` n'utilise `@Query`).

## Risques / points d'attention

- **Regression du test existant `accessibleShopIdsForAdminWithoutAssignment`** (lignes 72-81 de
  `CurrentShopContextTest.java`) : il stubbe aujourd'hui l'ancienne methode et construit un
  `User.builder()...build()` sans `organization`. Sans organisation renseignee sur ce `User` de
  test, `user.getOrganization()` vaudra `null` et l'appel a la nouvelle methode du repository
  levera une `NullPointerException` (ou un comportement indefini si on passe l'id directement).
  Le spec-writer doit explicitement prevoir la mise a jour de ce test (ajout d'un `Organization`
  au `User` de test), pas seulement l'ajout de nouveaux cas.
- **Non-regression mono-tenant explicitement demandee par le ticket** : avec une seule ligne
  `organizations` en base (cas de toutes les instances existantes, cf. migration `V13`), le
  filtre par organisation ne doit rien restreindre de plus qu'aujourd'hui -- a couvrir par un
  test dedie (une organisation, deux boutiques actives, ADMIN sans assignation => les deux
  boutiques), distinct du test multi-organisations.
- **`Shop.organization` est `@ManyToOne(fetch = FetchType.LAZY, optional = false)`** : filtrer
  par `user.getOrganization()` directement (l'objet proxy, pas seulement son id) dans une methode
  Spring Data derivee fonctionne (Spring Data compare par la cle primaire du proxy), mais reste
  a verifier a l'implementation -- le spec-writer/codeur doit s'assurer qu'aucun
  `LazyInitializationException` n'est declenche hors transaction si un `organizationId` explicite
  est prefere a l'objet entier.
- **Un ADMIN sans boutique dans une organisation qui n'a elle-meme aucune boutique active**
  (deuxieme critere d'acceptation) : `accessibleShopsOf` retournera alors une liste vide plutot
  que de lever la `BusinessRuleException` actuelle (celle-ci n'est levee que si `user.getShops()`
  est vide ET `role != ADMIN`). Une liste vide remonte ensuite via `accessibleShopIds()` a
  `resolveReadFilter()` (vue consolidee vide, pas d'erreur) et a `shopIdForCreation()` (qui leve
  `BusinessRuleException` "boutiques multiples" si `accessible.size() != 1` -- avec 0 element ce
  message serait trompeur, "0 boutique accessible" n'est pas "plusieurs boutiques"). Le
  spec-writer doit decider explicitement si ce comportement en aval est accepte tel quel ou
  merite un ajustement -- le ticket ne demande qu'un test couvrant ce cas, pas necessairement un
  nouveau message.
- **Aucun autre appelant de `findAllByActiveTrueOrderByNameAsc()` trouve dans le code de
  production** (`backend/src/main/java`) au moment de la redaction de ce design -- seul
  `CurrentShopContext` l'utilise. A reverifier par le codeur avant de decider si l'ancienne
  methode doit etre supprimee ou seulement laissee inutilisee (choix mineur, sans impact sur le
  perimetre fonctionnel).

## Hors perimetre

- Filtrage par organisation de la branche `user.getShops()` non vide (SELLER, ADMIN de boutique
  explicitement assigne) : ces utilisateurs sont deja restreints a leurs boutiques assignees
  individuellement, et le ticket ne demande de corriger que le cas ADMIN global (branche
  `findAllByActiveTrueOrderByNameAsc()`). Une future incoherence (assignation croisee d'une
  boutique d'une autre organisation a un utilisateur) reste possible tant qu'aucun controle
  n'est ajoute a la creation/modification d'assignations `user_shops` -- hors perimetre de ce
  ticket.
- Propagation du scoping par organisation a tout autre repository/`Specifications` metier
  (`CustomerRepository`, `ProductRepository`, `CreditSaleRepository`, `PaymentRepository`,
  `StockReceptionSpecifications`, `AuditLogAccessGuard`, etc.) : tickets de suivi #36 a #43
  (design #25).
- Postgres Row-Level Security et variable de session `app.current_org_id` : ticket de suivi #7
  de la chaine (design #25).
- Claim `org_id` dans le JWT (defense en profondeur secondaire evoquee en #25) : non demande par
  ce ticket, le rechargement depuis la base suffit et reste le mecanisme d'application principal
  retenu.
- Toute UI/endpoint de gestion des organisations, tout changement au frontend : aucun impact
  identifie, `accessibleShopsOf` est un mecanisme interne au backend.
