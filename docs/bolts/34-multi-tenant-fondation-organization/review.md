# Review — #34 Multi-tenant 1/10 — Fondation de données (entité Organization)

## Verdict

APPROVE

## Critères d'acceptation

| Critère | Statut | Commentaire |
|---|---|---|
| La migration Flyway s'applique proprement sur une base existante sans perte de données | Couvert (par vérification manuelle rapportée par le codeur, non re-exécutée par moi) | Le contenu de `V13__organizations.sql` a été relu ligne à ligne : ordre `CREATE TABLE` → `INSERT` organisation par défaut → `ALTER TABLE ... ADD COLUMN` (nullable) → `UPDATE` (backfill) → `ALTER COLUMN ... SET NOT NULL` → `ADD CONSTRAINT FK` → `CREATE INDEX`, à l'identique pour `shops` puis `users`. Aucun `NOT NULL` n'est posé avant le backfill — pas de risque de casser une base existante. Le fichier reproduit exactement le patron déjà accepté en production dans `V10__shops.sql` (même structure, mêmes noms de colonnes d'audit). Je n'ai pas de Postgres disponible dans cet environnement de review pour rejouer moi-même la vérification manuelle ; je me fie au rapport détaillé du codeur (conteneur Docker isolé, V1-V12 pré-appliquées, données de démo intactes, `organizations` = 1 ligne, 0 NULL). Rien dans le SQL ne contredit ce rapport. |
| Une instance mono-tenant se retrouve avec exactement une ligne `organizations`, toutes ses boutiques et tous ses utilisateurs y étant rattachés | Couvert | `INSERT INTO organizations` insère une seule ligne, `UPDATE shops/users SET organization_id = (SELECT id FROM organizations ORDER BY id LIMIT 1)` rattache tout l'existant à cette ligne unique avant que `NOT NULL` soit posé. Confirmé côté applicatif par `ShopServiceTest.createAssignsDefaultOrganization`, `UserServiceTest.createAssignsDefaultOrganization`, `AdminInitializerTest.createsAdminWithDefaultOrganization`. |
| Aucun comportement observable ne change | Couvert | Diff `master..HEAD` limité aux fichiers attendus par la spec (migration, `Organization`, `OrganizationRepository`, `Shop`, `User`, `ShopService`, `UserService`, `AdminInitializer`, tests, design/spec). Vérifié par `git diff --name-only` : aucune trace de `CurrentShopContext.java`, `ShopRepository.java`, d'un controller, d'un DTO exposé au client, ou du frontend. `ShopMapper` n'est pas touché (cohérent avec la spec, qui corrige d'ailleurs explicitement une imprécision du design à ce sujet dans sa section « Écarts identifiés »). |
| Suite de tests existante intégralement verte | Couvert | Voir section Build/tests ci-dessous — ré-exécuté moi-même, 367/367 verts. |

## Points vérifiés en détail (au-delà du tableau ci-dessus)

- **Exhaustivité des 3 points d'insertion** : `rg "new User\(|new Shop\(|User\.builder|Shop\.builder"` sur `backend/src/main` ne retourne que `AdminInitializer.java:42` et `UserService.java:52` pour les builders, plus `AppProperties.java:18` qui est un `new Shop()` sans rapport (classe de config imbriquée `AppProperties.Shop`, homonyme sans lien avec l'entité JPA). `Shop` est construit via `ShopMapper.toEntity` (MapStruct) puis `shop.setOrganization(...)` est appelé juste après dans `ShopService.create` — confirmé conforme au contrat de la spec. `DemoDataSeeder` a été lu intégralement : il ne crée ni `Shop` ni `User`, il consomme une boutique et un admin déjà existants (créés par `AdminInitializer`/migration) via `CurrentShopContext` — pas un 4e point d'insertion caché.
- **Cohérence JPA / SQL** : `Shop.organization` et `User.organization` sont `@ManyToOne(fetch = FetchType.LAZY, optional = false)` avec `@JoinColumn(name = "organization_id", nullable = false)`, exactement en phase avec `ALTER COLUMN organization_id SET NOT NULL` en base.
- **Qualité des tests** :
  - `AdminInitializerTest` couvre bien les 3 cas demandés par la spec : création avec organisation résolue (capture de l'argument `save`), échec par `IllegalStateException` si `findFirstByOrderByIdAsc()` est vide, et non-interrogation de `organizationRepository` (`verify(..., never())`) quand l'admin existe déjà.
  - Les stubs `organizationRepository.findFirstByOrderByIdAsc()` dans `ShopServiceTest`/`UserServiceTest` sont posés dans `@BeforeEach`, sous `@MockitoSettings(strictness = Strictness.LENIENT)` déjà présent sur les deux classes — pas de risque de `UnnecessaryStubbingException` pour les tests qui n'exercent pas `create()`.
- **Pas de collision de migration** : `V13__organizations.sql` est bien le premier numéro libre après `V12__payment_idempotency.sql` (le trou `V8` est pré-existant, non touché). Noms de contraintes/index (`fk_shops_organization`, `fk_users_organization`, `idx_shops_organization`, `idx_users_organization`) ne sont utilisés nulle part ailleurs dans `db/migration/`.
- **Solidité du socle pour #35-#43** : `com.creditflow.organization.domain.Organization` / `.repository.OrganizationRepository`, colonne `organization_id`, méthode `findFirstByOrderByIdAsc()` — nommage cohérent avec le module `shop` existant, documenté explicitement (design.md section "Décisions clés", spec.md section "Contrat technique") avec justification pour chaque choix (NOT NULL sans étape nullable intermédiaire, pas de table de jonction, package minimal sans `service/`/`web/`). Rien d'ambigu qui obligerait un futur ticket à deviner une convention.

## Build/tests

- `cd backend && mvn -q -DskipTests=false test` → exit code 0.
- Vérification indépendante du compte de tests via les rapports Surefire : `grep -h "Tests run" target/surefire-reports/*.txt` agrégé sur 66 fichiers de rapport → **367 tests, 0 failure, 0 error, 0 skipped**, cohérent avec le rapport du codeur.

## Remarques mineures (non bloquantes)

- Je n'ai pas pu rejouer moi-même la vérification manuelle Docker/Postgres (V1-V12 existantes + application de V13) dans cet environnement de review ; le verdict s'appuie sur la relecture statique du SQL (qui ne présente aucune anomalie d'ordre d'opérations) et sur le rapport détaillé et vérifiable du codeur. Si un doute subsiste avant merge définitif dans la chaîne, une ré-exécution de cette vérification manuelle par un humain reste la seule preuve directe pour le critère « migration sur base existante sans perte de données », comme signalé dans le design lui-même.
