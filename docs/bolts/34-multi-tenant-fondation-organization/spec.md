# Spec — #34 Multi-tenant 1/10 — Fondation de données (entité Organization)

## Résumé

Ajouter une table `organizations` (une ligne "organisation par défaut" insérée par la migration), rattacher `shops` et `users` à cette organisation via `organization_id NOT NULL`, et mettre à jour les trois points de création (`ShopService.create`, `UserService.create`, `AdminInitializer.createDefaultAdmin`) pour continuer à fonctionner après la migration — sans introduire de filtrage ni de comportement observable nouveau.

## Tâches

- [ ] `backend/src/main/resources/db/migration/V13__organizations.sql` — nouvelle migration (V13 est le prochain numéro libre après V12 ; rappel : V8 n'existe pas dans la séquence, ne pas essayer de le recréer). Contenu exact en section Contrat technique.
- [ ] `backend/src/main/java/com/creditflow/organization/domain/Organization.java` — nouvelle entité JPA, `@Entity @Table(name = "organizations")`, étend `Auditable`, pattern Lombok identique à `Shop.java` (`@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`). Champs : `id` (`@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`), `name` (`@Column(nullable = false, length = 120)`). Pas de champ `active`, pas de contrainte d'unicité sur `name`.
- [ ] `backend/src/main/java/com/creditflow/organization/repository/OrganizationRepository.java` — `extends JpaRepository<Organization, Long>`, une seule méthode : `Optional<Organization> findFirstByOrderByIdAsc();`.
- [ ] `backend/src/main/java/com/creditflow/shop/domain/Shop.java` — ajouter le champ `organization`, pattern identique à `Customer.shop` (cf. #10) :
  ```java
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "organization_id", nullable = false)
  private Organization organization;
  ```
  Import `com.creditflow.organization.domain.Organization`.
- [ ] `backend/src/main/java/com/creditflow/auth/domain/User.java` — ajouter le même champ `organization` (même annotation exacte que ci-dessus, `@JoinColumn(name = "organization_id", nullable = false)`), à côté du champ `shops` existant (ne pas toucher à `shops`/`user_shops`).
- [ ] `backend/src/main/java/com/creditflow/shop/service/ShopService.java` — injecter `OrganizationRepository organizationRepository` (constructeur `@RequiredArgsConstructor`, nouveau champ `private final OrganizationRepository organizationRepository;`). Dans `create(ShopRequest request)`, après `Shop shop = shopMapper.toEntity(request);` et avant `shopRepository.save(shop)`, ajouter :
  ```java
  shop.setOrganization(resolveDefaultOrganization());
  ```
  Ajouter la méthode privée :
  ```java
  private Organization resolveDefaultOrganization() {
      return organizationRepository.findFirstByOrderByIdAsc()
              .orElseThrow(() -> new IllegalStateException(
                      "Aucune organisation par defaut trouvee : la migration V13 doit etre appliquee."));
  }
  ```
  Ne pas toucher `update()` (l'organisation d'une boutique existante n'est jamais modifiée dans ce ticket — hors périmètre, cf. `Écarts identifiés` si un cas contraire est trouvé).
- [ ] `backend/src/main/java/com/creditflow/auth/service/UserService.java` — injecter `OrganizationRepository organizationRepository` (même mécanisme). Dans `create(UserRequest request)`, ajouter `.organization(resolveDefaultOrganization())` au `User.builder()` avant `.build()`. Ajouter la même méthode privée `resolveDefaultOrganization()` que dans `ShopService` (dupliquée à l'identique — pas de service partagé, cohérent avec le hors-périmètre "pas de logique de scoping" du design). Ne pas toucher `setEnabled()`/`updateShops()`.
- [ ] `backend/src/main/java/com/creditflow/auth/bootstrap/AdminInitializer.java` — injecter `OrganizationRepository organizationRepository` (nouveau champ `private final OrganizationRepository organizationRepository;`, `@RequiredArgsConstructor` existant se charge de l'injection). Dans le lambda `createDefaultAdmin()`, résoudre l'organisation **uniquement après** le `if (userRepository.existsByUsernameIgnoreCase(...)) { return; }` (ne pas interroger `organizations` inutilement à chaque redémarrage une fois l'admin déjà créé) :
  ```java
  Organization organization = organizationRepository.findFirstByOrderByIdAsc()
          .orElseThrow(() -> new IllegalStateException(
                  "Aucune organisation par defaut trouvee : la migration V13 doit etre appliquee."));
  User user = User.builder()
          .username(admin.getUsername())
          .password(passwordEncoder.encode(admin.getPassword()))
          .fullName(admin.getFullName())
          .role(Role.ADMIN)
          .enabled(true)
          .mustChangePassword(admin.isForcePasswordChange())
          .organization(organization)
          .build();
  ```
- [ ] `backend/src/test/java/com/creditflow/shop/service/ShopServiceTest.java` — ajouter `@Mock private OrganizationRepository organizationRepository;`, dans `@BeforeEach` ajouter `when(organizationRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(Organization.builder().id(1L).name("Organisation par defaut").build()));` (strictness déjà `LENIENT`, aucun risque pour les tests qui n'en ont pas besoin). Ajouter un nouveau test `createAssignsDefaultOrganization()` : capture le `Shop` passé à `save`, assert `captor.getValue().getOrganization().getId()` égale `1L`.
- [ ] `backend/src/test/java/com/creditflow/auth/service/UserServiceTest.java` — le constructeur `userService = new UserService(userRepository, shopRepository, passwordEncoder)` (ligne 53) doit être mis à jour pour le nouveau paramètre : ajouter `@Mock private OrganizationRepository organizationRepository;`, stubber `findFirstByOrderByIdAsc()` dans `@BeforeEach` (même pattern que ci-dessus), passer `organizationRepository` au constructeur. Ajouter un nouveau test `createAssignsDefaultOrganization()` symétrique à celui de `ShopServiceTest`.
- [ ] `backend/src/test/java/com/creditflow/auth/bootstrap/AdminInitializerTest.java` — **nouveau fichier** (aucun test n'existe actuellement pour `AdminInitializer`). Pattern Mockito identique à `ShopServiceTest`/`UserServiceTest` (`@ExtendWith(MockitoExtension.class)`, mocks `UserRepository`, `PasswordEncoder`, `AppProperties` construit à la main comme dans `ShopServiceTest`, `OrganizationRepository`). Récupérer le `ApplicationRunner` via `adminInitializer.createDefaultAdmin()` et l'exécuter avec `.run(null)`. Couvrir :
  - l'admin créé sur une base fraîche a `organization` renseignée avec l'organisation par défaut résolue (capturer l'argument de `userRepository.save(...)`) ;
  - si `organizationRepository.findFirstByOrderByIdAsc()` retourne vide, `run(null)` lève `IllegalStateException` (simule une base sans migration V13 appliquée — protège explicitement contre le risque de blocage au démarrage identifié dans le design) ;
  - si l'admin existe déjà (`existsByUsernameIgnoreCase` retourne `true`), `organizationRepository` n'est jamais interrogé (`verify(organizationRepository, never()).findFirstByOrderByIdAsc();`) et `userRepository.save` n'est jamais appelé.

## Contrat technique

### Migration `V13__organizations.sql` (contenu exact attendu, sur le modèle de `V10__shops.sql`)

```sql
-- =====================================================================
-- V13 - Fondation multi-tenant : entite Organization (#34)
-- =====================================================================

CREATE TABLE organizations (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    created_by  VARCHAR(80),
    updated_by  VARCHAR(80)
);

-- Organisation par defaut : recoit toutes les boutiques et tous les utilisateurs
-- existants lors du retro-remplissage (instance mono-tenant).
INSERT INTO organizations (name, created_at) VALUES ('Organisation par defaut', NOW());

-- shops.organization_id
ALTER TABLE shops ADD COLUMN organization_id BIGINT;
UPDATE shops SET organization_id = (SELECT id FROM organizations ORDER BY id LIMIT 1);
ALTER TABLE shops ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE shops ADD CONSTRAINT fk_shops_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
CREATE INDEX idx_shops_organization ON shops (organization_id);

-- users.organization_id
ALTER TABLE users ADD COLUMN organization_id BIGINT;
UPDATE users SET organization_id = (SELECT id FROM organizations ORDER BY id LIMIT 1);
ALTER TABLE users ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT fk_users_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);
CREATE INDEX idx_users_organization ON users (organization_id);
```

### Entité / Repository

| Élément | Détail |
|---|---|
| `Organization` | `id BIGSERIAL`, `name VARCHAR(120) NOT NULL`, colonnes `Auditable` (`created_at`, `updated_at`, `created_by`, `updated_by`) |
| `OrganizationRepository.findFirstByOrderByIdAsc()` | seule méthode ; utilisée exclusivement par les 3 points d'insertion (`ShopService.create`, `UserService.create`, `AdminInitializer.createDefaultAdmin`) |
| `Shop.organization` / `User.organization` | `@ManyToOne(fetch = FetchType.LAZY, optional = false)`, `@JoinColumn(name = "organization_id", nullable = false)` |
| Erreur de résolution | `IllegalStateException` si `findFirstByOrderByIdAsc()` est vide (ne devrait jamais arriver derrière une migration V13 appliquée ; message explicite plutôt qu'un `NoSuchElementException` opaque) |

Aucun DTO, aucun endpoint, aucune modification de `ShopMapper` (la mapping MapStruct `ShopRequest → Shop` reste inchangée : `organization` n'existe pas dans `ShopRequest` et n'est jamais exposé au client — il est renseigné en service, pas en mapper, contrairement à ce que suggère la formulation "ShopMapper.java / ShopService.java" du design : après lecture, `ShopMapper.toEntity` n'a aucune information sur l'organisation, seul `ShopService.create` peut la résoudre).

## Plan de tests

| Critère d'acceptation | Couverture |
|---|---|
| La migration Flyway s'applique proprement sur une base existante sans perte de données | **Non testable par `mvn test`** (aucun `@SpringBootTest`/`@DataJpaTest` dans la suite actuelle, entièrement Mockito). **Vérification manuelle obligatoire** : `docker-compose up -d db`, s'assurer que V1 à V12 sont déjà appliquées (base existante, pas fraîche), démarrer le backend (`docker-compose up -d backend` ou `mvn spring-boot:run` en pointant sur cette base) pour déclencher Flyway sur V13, puis vérifier via `psql`/un client SQL que toutes les tables et lignes pré-existantes (`customers`, `products`, `credit_sales`, `shops`, `users`, etc.) sont intactes après la migration. |
| Une instance mono-tenant se retrouve avec exactement une ligne `organizations`, toutes ses boutiques et tous ses utilisateurs y étant rattachés | **Vérification manuelle** (même session que ci-dessus) : `SELECT COUNT(*) FROM organizations;` doit renvoyer `1` ; `SELECT COUNT(*) FROM shops WHERE organization_id IS NULL;` et `SELECT COUNT(*) FROM users WHERE organization_id IS NULL;` doivent renvoyer `0` ; `SELECT DISTINCT organization_id FROM shops;` et `SELECT DISTINCT organization_id FROM users;` doivent chacun renvoyer une seule valeur, identique à l'`id` de l'unique ligne `organizations`. Complété côté applicatif par `AdminInitializerTest` (l'admin créé au démarrage a bien `organization` renseignée) et `ShopServiceTest`/`UserServiceTest` (`createAssignsDefaultOrganization`), qui couvrent le mécanisme de résolution mais pas la valeur réelle en base — la vérification manuelle reste indispensable pour ce critère. |
| Aucun comportement observable ne change (aucune requête, aucun endpoint ne filtre encore par organisation) | Suite `mvn test` complète au vert (voir critère suivant) : aucun test `ShopControllerSecurityTest`, `UserControllerTest`, `AuthServiceTest`, etc. n'est modifié dans ses assertions HTTP/DTO — seule l'implémentation interne de `ShopService.create`/`UserService.create`/`AdminInitializer` change. Vérification manuelle complémentaire : démarrer l'application après migration (base fraîche **et** base existante rétro-remplie), créer une boutique via l'écran `Boutiques`, créer un compte `SELLER` via l'écran `Utilisateurs`, confirmer qu'aucune erreur `500`/violation de contrainte n'apparaît (couvre directement le risque `organization_id NOT NULL` identifié dans le design) et qu'aucune donnée d'une autre organisation n'est visible ou masquée (il n'y en a qu'une, donc rien ne doit changer visuellement). |
| Suite de tests existante intégralement verte | `mvn test` sur le module `backend` : tous les tests existants passent sans modification de leurs assertions, plus les nouveaux tests (`ShopServiceTest.createAssignsDefaultOrganization`, `UserServiceTest.createAssignsDefaultOrganization`, `AdminInitializerTest` — 3 cas). |

## Écarts identifiés

- **Le design mentionne `ShopMapper.java` comme fichier potentiellement modifié pour renseigner `organization`** ("`ShopMapper.java` / `ShopService.java` — `create()` doit renseigner `organization`..."). Après lecture de `ShopMapper` (mapping MapStruct pur depuis `ShopRequest`, qui n'a aucune notion d'organisation), seul `ShopService.create` peut porter cette responsabilité : `ShopMapper` n'est **pas** modifié par cette spec. Écart cosmétique sans impact sur le comportement attendu, signalé par prudence.
- **Le design ne précise pas explicitement le comportement si `findFirstByOrderByIdAsc()` retourne vide** (base sans migration V13, ou table `organizations` vidée manuellement). Cette spec retient une `IllegalStateException` explicite aux 3 points d'insertion plutôt qu'un `NoSuchElementException` par défaut de `Optional.get()`, pour un message d'erreur actionnable au démarrage — cohérent avec le risque "AdminInitializer empêche le démarrage" déjà documenté dans le design, mais le choix du type d'exception n'est pas prescrit : à confirmer par le codeur/reviewer si une convention différente existe ailleurs dans le code pour ce type d'échec de démarrage.
- **Aucun écart de fond détecté entre le design et les critères d'acceptation du ticket** : les 3 points d'insertion identifiés par le design couvrent exhaustivement les créations de `Shop`/`User` du code actuel (confirmé par grep sur `User.builder()` et `Shop.builder()` dans `src/main`, en dehors des tests) ; le hors-périmètre (scoping, RLS, endpoints) est cohérent avec "aucun comportement observable ne change".
