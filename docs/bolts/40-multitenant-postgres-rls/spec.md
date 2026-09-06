# Spec — #40 Multi-tenant 7/10 — Défense en profondeur Postgres Row-Level Security

## Résumé

Ajoute une garde d'isolation multi-tenant au niveau Postgres (Row-Level Security, rôle applicatif
restreint, propagation Hibernate du tenant par connexion) indépendante de la couche applicative,
validée par des tests Testcontainers contre un vrai Postgres.

## Ordre d'exécution impératif

Les tâches sont numérotées dans l'ordre où elles doivent être livrées/exécutées. Ne pas paralléliser
les groupes 1→5 : chaque groupe suppose le précédent en place (migration RLS avant code Hibernate,
rôle applicatif avant GRANT, etc.).

1. Migrations SQL (V14, V15) — schéma prêt pour RLS, RLS pas encore appliqué au rôle applicatif.
2. Infra : rôle Postgres + docker-compose + `.env*` + `application.yml` (flyway) — le rôle
   `creditflow_app` doit exister **avant** que V16 s'exécute.
3. Migration V16 (GRANT) — dépend du rôle créé en 2.
4. Code Hibernate (`TenantContext`, `TenantConnectionConfig`, `TenantContextFilter`,
   `SecurityConfig`).
5. `AuthService`, `CurrentShopContext`, `DemoDataSeeder`, `StockReception`/`StockReceptionService`.
6. `pom.xml` (Testcontainers) puis tests (`TenantConnectionConfigTest`, `RowLevelSecurityIT`,
   adaptation `AuthServiceTest`).
7. Documentation (`README.md`) pour les instances déjà déployées.

---

## Tâches

### 1. Migrations SQL

- [ ] `backend/src/main/resources/db/migration/V14__stock_receptions_shop_id.sql` — ajoute
  `stock_receptions.shop_id`, backfill via les lignes existantes, garde-fou explicite si une
  réception n'a aucune ligne exploitable (voir Contrat technique), contrainte NOT NULL, FK, index.
- [ ] `backend/src/main/resources/db/migration/V15__row_level_security.sql` — fonction
  `app_current_org_id()` + `ENABLE`/`FORCE ROW LEVEL SECURITY` + `CREATE POLICY` sur les 11 tables
  listées par le ticket.
- [ ] `db/init/01-create-app-role.sh` (nouveau) — crée le rôle `creditflow_app` (nom réellement pris
  depuis `DB_APP_USERNAME`, valeur par défaut `creditflow_app`) via `docker-entrypoint-initdb.d`,
  avec `CONNECT`/`USAGE ON SCHEMA public`.
- [ ] `backend/src/main/resources/db/migration/V16__app_role_grants.sql` — `GRANT` explicite
  table par table + séquences, au rôle applicatif (nom paramétré via un placeholder Flyway, voir
  Contrat technique). **Ne peut être appliquée avec succès que si la tâche `db/init` a déjà tourné**
  (rôle existant) — sur une instance Docker Compose déjà initialisée, voir tâche 7 (runbook).

### 2. Infrastructure / configuration

- [ ] `docker-compose.yml` — service `db` : `POSTGRES_USER`/`POSTGRES_PASSWORD` basculent sur
  `DB_MIGRATION_USERNAME`/`DB_MIGRATION_PASSWORD` (nouveau, défaut identique à l'actuel
  `DB_USERNAME`/`DB_PASSWORD` pour compatibilité), montage de `db/init/01-create-app-role.sh`,
  nouvelles variables `DB_APP_USERNAME`/`DB_APP_PASSWORD` injectées dans l'environnement du service
  `db` (nécessaires au script) ; service `backend` : `DB_USERNAME`/`DB_PASSWORD` basculent sur
  `DB_APP_USERNAME`/`DB_APP_PASSWORD`, ajout de `DB_MIGRATION_USERNAME`/`DB_MIGRATION_PASSWORD` pour
  Flyway ; service `backup` : reste sur `DB_MIGRATION_USERNAME`/`DB_MIGRATION_PASSWORD` (un backup
  `pg_dump` doit voir toutes les données, pas seulement celles filtrées par RLS — voir Contrat
  technique).
- [ ] `backend/src/main/resources/application.yml` — `spring.flyway.url`/`user`/`password` explicites
  (rôle migration), `spring.flyway.placeholders.creditflowAppRole` (rôle applicatif, réutilisé par
  V16).
- [ ] `.env.example` — ajoute `DB_APP_USERNAME`, `DB_APP_PASSWORD`, `DB_MIGRATION_USERNAME`,
  `DB_MIGRATION_PASSWORD` avec les mêmes valeurs de démonstration que l'actuel `DB_USERNAME`/
  `DB_PASSWORD` pour `DB_MIGRATION_*`, et `creditflow_app`/`creditflow_app` pour `DB_APP_*`.
- [ ] `.env.production.example` — mêmes quatre variables, valeurs vides marquées « A CHANGER »
  comme le reste du fichier.
- [ ] `backend/src/main/java/com/creditflow/config/SecurityDefaultsValidator.java` — **non listé par
  le design, ajouté ici (voir Écarts identifiés)** : le contrôle de démarrage en mode `prod` doit
  vérifier *les deux* mots de passe (applicatif ET migration), pas uniquement l'ancien
  `spring.datasource.password` qui ne représente plus que le rôle applicatif après ce ticket.

### 3. Code Hibernate / servlet

- [ ] `backend/src/main/java/com/creditflow/common/security/TenantContext.java` (nouveau).
- [ ] `backend/src/main/java/com/creditflow/config/TenantConnectionConfig.java` (nouveau) —
  `MultiTenantConnectionProvider`, `CurrentTenantIdentifierResolver`, `HibernatePropertiesCustomizer`.
- [ ] `backend/src/main/java/com/creditflow/common/security/TenantContextFilter.java` (nouveau).
- [ ] `backend/src/main/java/com/creditflow/config/SecurityConfig.java` — enregistrement du filtre.

### 4. Code métier

- [ ] `backend/src/main/java/com/creditflow/auth/service/AuthService.java` — `login()` réécrit en
  deux emprunts de session distincts (voir Contrat technique — section critique).
- [ ] `backend/src/main/java/com/creditflow/common/security/CurrentShopContext.java` — nouvelle
  méthode `reloadWithShopsInitialized(String username)`.
- [ ] `backend/src/main/java/com/creditflow/bootstrap/DemoDataSeeder.java` — positionne
  `TenantContext` avant le seeding.
- [ ] `backend/src/main/java/com/creditflow/supplier/domain/StockReception.java` — relation `shop`
  directe.
- [ ] `backend/src/main/java/com/creditflow/supplier/service/StockReceptionService.java` — renseigne
  `.shop(...)` à la construction.

### 5. Tests

- [ ] `backend/pom.xml` — `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`
  (scope `test`, pas de `<version>` : gérées par le BOM `spring-boot-dependencies` hérité du parent).
- [ ] `backend/src/test/java/com/creditflow/config/TenantConnectionConfigTest.java` (nouveau).
- [ ] `backend/src/test/java/com/creditflow/security/rls/RowLevelSecurityIT.java` (nouveau).
- [ ] `backend/src/test/java/com/creditflow/auth/service/AuthServiceTest.java` — adapter les mocks à
  la nouvelle forme de `login()`.

### 6. Documentation

- [ ] `README.md` — section « Sécurité » : mentionner `DB_APP_PASSWORD`/`DB_MIGRATION_PASSWORD` en
  plus de `DB_PASSWORD` ; nouvelle sous-section « Mise à niveau vers le rôle applicatif restreint
  (#40) » pour les instances Docker Compose déjà déployées (procédure manuelle, voir Contrat
  technique).

---

## Contrat technique

### V14__stock_receptions_shop_id.sql (SQL complet)

```sql
-- =====================================================================
-- V14 - stock_receptions.shop_id (#40)
-- Prerequis a la policy RLS de V15 : la colonne doit exister et etre
-- fiable avant que la policy WITH CHECK ne s'applique a l'INSERT de
-- l'en-tete de reception (voir design.md, decision "stock_receptions
-- recoit un shop_id propre").
-- =====================================================================

ALTER TABLE stock_receptions ADD COLUMN shop_id BIGINT;

UPDATE stock_receptions sr
SET shop_id = (
    SELECT p.shop_id
    FROM stock_reception_lines srl
    JOIN products p ON p.id = srl.product_id
    WHERE srl.reception_id = sr.id
    ORDER BY srl.id
    LIMIT 1
);

-- Garde-fou explicite (risque signale par design.md) : une reception sans
-- ligne exploitable ne peut pas etre rattachee automatiquement a une
-- boutique. On echoue bruyamment plutot que de laisser passer un NULL
-- silencieusement jusqu'a la contrainte NOT NULL ci-dessous.
DO $$
DECLARE
    orphan_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO orphan_count FROM stock_receptions WHERE shop_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'V14: % reception(s) sans ligne exploitable pour deduire shop_id -- '
            'resoudre manuellement (rattacher une ligne ou supprimer la reception) '
            'avant de rejouer cette migration.', orphan_count;
    END IF;
END $$;

ALTER TABLE stock_receptions ALTER COLUMN shop_id SET NOT NULL;
ALTER TABLE stock_receptions ADD CONSTRAINT fk_stock_receptions_shop
    FOREIGN KEY (shop_id) REFERENCES shops (id);
CREATE INDEX idx_stock_receptions_shop ON stock_receptions (shop_id);
```

### V15__row_level_security.sql (SQL complet)

```sql
-- =====================================================================
-- V15 - Row-Level Security (#40)
-- =====================================================================

-- app.current_org_id est positionne par le pool applicatif (voir
-- TenantConnectionConfig) via set_config(..., false) -- jamais par le
-- client SQL lui-meme. STABLE : la valeur ne change pas au sein d'une
-- meme requete, autorise Postgres a optimiser les policies.
CREATE FUNCTION app_current_org_id() RETURNS BIGINT AS $$
    SELECT NULLIF(current_setting('app.current_org_id', true), '')::BIGINT;
$$ LANGUAGE sql STABLE;

-- shops : jointure directe sur organization_id (V13).
ALTER TABLE shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE shops FORCE ROW LEVEL SECURITY;
CREATE POLICY shops_tenant_isolation ON shops
    USING (organization_id = app_current_org_id());

-- customers, products, credit_sales, stock_receptions : shop_id direct (V10, V14).
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;
CREATE POLICY customers_tenant_isolation ON customers
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));

ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;
CREATE POLICY products_tenant_isolation ON products
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));

ALTER TABLE credit_sales ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_sales FORCE ROW LEVEL SECURITY;
CREATE POLICY credit_sales_tenant_isolation ON credit_sales
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));

ALTER TABLE stock_receptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_receptions FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_receptions_tenant_isolation ON stock_receptions
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));

-- installments, payments, sale_attachments : deux sauts via credit_sales.shop_id.
ALTER TABLE installments ENABLE ROW LEVEL SECURITY;
ALTER TABLE installments FORCE ROW LEVEL SECURITY;
CREATE POLICY installments_tenant_isolation ON installments
    USING (sale_id IN (
        SELECT cs.id FROM credit_sales cs
        JOIN shops s ON s.id = cs.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;
CREATE POLICY payments_tenant_isolation ON payments
    USING (sale_id IN (
        SELECT cs.id FROM credit_sales cs
        JOIN shops s ON s.id = cs.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

ALTER TABLE sale_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE sale_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY sale_attachments_tenant_isolation ON sale_attachments
    USING (sale_id IN (
        SELECT cs.id FROM credit_sales cs
        JOIN shops s ON s.id = cs.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

-- stock_reception_lines : via reception_id -> stock_receptions.shop_id (V14),
-- plus direct que via product_id maintenant que l'en-tete porte shop_id.
-- (Precision par rapport a design.md, qui groupait ce cas avec stock_movements
-- sans trancher explicitement le chemin de jointure -- voir Ecarts identifies.)
ALTER TABLE stock_reception_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_reception_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_reception_lines_tenant_isolation ON stock_reception_lines
    USING (reception_id IN (
        SELECT sr.id FROM stock_receptions sr
        JOIN shops s ON s.id = sr.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

-- stock_movements : pas de lien direct vers stock_receptions (source
-- polymorphe source_type/source_id) -- seul chemin fiable : product_id.
ALTER TABLE stock_movements ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_movements FORCE ROW LEVEL SECURITY;
CREATE POLICY stock_movements_tenant_isolation ON stock_movements
    USING (product_id IN (
        SELECT p.id FROM products p
        JOIN shops s ON s.id = p.shop_id
        WHERE s.organization_id = app_current_org_id()
    ));

-- user_shops : meme forme que customers (shop_id direct).
ALTER TABLE user_shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_shops FORCE ROW LEVEL SECURITY;
CREATE POLICY user_shops_tenant_isolation ON user_shops
    USING (shop_id IN (SELECT id FROM shops WHERE organization_id = app_current_org_id()));
```

**Comportement fail-closed intentionnel** : tant que `app.current_org_id` n'est pas positionné
(valeur NULL renvoyée par `app_current_org_id()`), `organization_id = NULL` et `shop_id IN (...)`
sont tous deux faux pour toute ligne — aucune donnée protégée n'est visible. C'est le comportement
recherché pendant la phase 1 du login (voir plus bas), pas un bug.

### V16__app_role_grants.sql (SQL complet)

```sql
-- =====================================================================
-- V16 - Octroi des droits au role applicatif restreint (#40)
-- Suppose que le role ${creditflowAppRole} existe deja (cree par
-- db/init/01-create-app-role.sh, hors Flyway -- voir Risques).
-- Enumeration explicite des tables : ALL TABLES IN SCHEMA public
-- inclurait flyway_schema_history, que le pool applicatif n'a aucune
-- raison de lire ou modifier.
-- =====================================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON
    organizations,
    shops,
    users,
    user_shops,
    customers,
    products,
    credit_sales,
    installments,
    payments,
    sale_attachments,
    suppliers,
    stock_receptions,
    stock_reception_lines,
    stock_movements,
    penalty_settings
TO ${creditflowAppRole};

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${creditflowAppRole};
```

Note : `${creditflowAppRole}` est un placeholder Flyway (voir `application.yml` ci-dessous), résolu
à `spring.flyway.placeholders.creditflowAppRole` — jamais une chaîne interpolée côté Java avant
envoi à Flyway. `organizations`, `users`, `suppliers`, `penalty_settings` restent hors RLS (décision
du design) mais ont toujours besoin des privilèges SQL de base pour être lus/écrits par le pool
applicatif — RLS et GRANT sont deux mécanismes indépendants ; l'absence de policy RLS sur une table
ne dispense pas de lui accorder les privilèges de table.

### db/init/01-create-app-role.sh (script complet)

```bash
#!/bin/sh
# Cree le role applicatif restreint, hors Flyway (aucun mot de passe versionne).
# Monte sur /docker-entrypoint-initdb.d/ : ne s'execute qu'a la toute premiere
# initialisation du volume Postgres (voir Risques pour les instances existantes).
set -e

: "${DB_APP_USERNAME:?DB_APP_USERNAME doit etre defini}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD doit etre defini}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${DB_APP_USERNAME}') THEN
            CREATE ROLE "${DB_APP_USERNAME}" LOGIN PASSWORD '${DB_APP_PASSWORD}';
        END IF;
    END
    \$\$;

    GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO "${DB_APP_USERNAME}";
    GRANT USAGE ON SCHEMA public TO "${DB_APP_USERNAME}";
EOSQL
```

`$POSTGRES_USER`/`$POSTGRES_DB` sont exportés par l'image officielle `postgres` pendant l'exécution
des scripts `docker-entrypoint-initdb.d` (mécanisme standard de l'image, pas une zone d'incertitude).

### docker-compose.yml (extraits à modifier)

```yaml
services:
  db:
    environment:
      POSTGRES_DB: ${DB_NAME:-creditflow}
      POSTGRES_USER: ${DB_MIGRATION_USERNAME:-creditflow}
      POSTGRES_PASSWORD: ${DB_MIGRATION_PASSWORD:-creditflow}
      DB_APP_USERNAME: ${DB_APP_USERNAME:-creditflow_app}
      DB_APP_PASSWORD: ${DB_APP_PASSWORD:-creditflow_app}
      TZ: ${TZ:-Africa/Dakar}
    volumes:
      - creditflow-db-data:/var/lib/postgresql/data
      - ./db/init/01-create-app-role.sh:/docker-entrypoint-initdb.d/01-create-app-role.sh:ro
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U ${DB_MIGRATION_USERNAME:-creditflow} -d ${DB_NAME:-creditflow}']
      # (interval/timeout/retries inchanges)

  backend:
    environment:
      DB_URL: jdbc:postgresql://db:5432/${DB_NAME:-creditflow}
      DB_USERNAME: ${DB_APP_USERNAME:-creditflow_app}
      DB_PASSWORD: ${DB_APP_PASSWORD:-creditflow_app}
      DB_MIGRATION_USERNAME: ${DB_MIGRATION_USERNAME:-creditflow}
      DB_MIGRATION_PASSWORD: ${DB_MIGRATION_PASSWORD:-creditflow}
      # (toutes les autres variables inchangees)

  backup:
    environment:
      PGHOST: db
      PGDATABASE: ${DB_NAME:-creditflow}
      # backup/restore doivent voir TOUTES les donnees (pas filtrees par RLS) :
      # utiliser le role de migration/proprietaire, pas le role applicatif.
      PGUSER: ${DB_MIGRATION_USERNAME:-creditflow}
      PGPASSWORD: ${DB_MIGRATION_PASSWORD:-creditflow}
      # (BACKUP_INTERVAL_HOURS/BACKUP_RETENTION_DAYS/TZ inchanges)
```

### application.yml (ajouts)

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/creditflow}
    username: ${DB_USERNAME:creditflow}
    password: ${DB_PASSWORD:creditflow}
    hikari:
      maximum-pool-size: 10
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    url: ${DB_URL:jdbc:postgresql://localhost:5432/creditflow}
    user: ${DB_MIGRATION_USERNAME:creditflow}
    password: ${DB_MIGRATION_PASSWORD:creditflow}
    placeholders:
      creditflowAppRole: ${DB_USERNAME:creditflow_app}
```

`creditflowAppRole` réutilise `DB_USERNAME` (résolu par Spring Boot avant que Flyway ne lise ses
propres placeholders) : c'est exactement la valeur que `spring.datasource.username` utilisera pour
se connecter, donc le rôle que V16 doit habiliter — une seule source de vérité.

### SecurityDefaultsValidator.java (modification)

```java
@Value("${spring.datasource.password:}")
private String appDatabasePassword;

@Value("${spring.flyway.password:}")
private String migrationDatabasePassword;

// dans collectProblems() :
static final String DEFAULT_APP_DB_PASSWORD = "creditflow_app";
// DEFAULT_DB_PASSWORD ("creditflow") devient le defaut du role de MIGRATION.

if (DEFAULT_APP_DB_PASSWORD.equals(appDatabasePassword)) {
    problems.add("DB_APP_PASSWORD utilise encore la valeur de livraison");
}
if (DEFAULT_DB_PASSWORD.equals(migrationDatabasePassword)) {
    problems.add("DB_MIGRATION_PASSWORD utilise encore la valeur de livraison");
}
```

### TenantContext.java (structure complète)

```java
package com.creditflow.common.security;

public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_ORGANIZATION_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long organizationId) {
        CURRENT_ORGANIZATION_ID.set(organizationId);
    }

    public static Long get() {
        return CURRENT_ORGANIZATION_ID.get();
    }

    public static void clear() {
        CURRENT_ORGANIZATION_ID.remove();
    }
}
```

### TenantConnectionConfig.java (structure et signatures)

```java
package com.creditflow.config;

import com.creditflow.common.security.TenantContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Configuration
public class TenantConnectionConfig {

    public static final String NO_TENANT = "__no_tenant__";

    @Bean
    public HibernatePropertiesCustomizer hibernateTenancyCustomizer(
            TenantAwareConnectionProvider connectionProvider,
            TenantIdentifierResolver tenantIdentifierResolver) {
        return hibernateProperties -> {
            hibernateProperties.put(
                    org.hibernate.cfg.AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER,
                    connectionProvider);
            hibernateProperties.put(
                    org.hibernate.cfg.AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                    tenantIdentifierResolver);
        };
    }

    @Component
    public static class TenantAwareConnectionProvider implements MultiTenantConnectionProvider<String> {

        private static final String SET_ORG_ID = "SELECT set_config('app.current_org_id', ?, false)";

        private final DataSource dataSource;

        public TenantAwareConnectionProvider(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Connection getAnyConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void releaseAnyConnection(Connection connection) throws SQLException {
            resetTenant(connection);
            connection.close();
        }

        @Override
        public Connection getConnection(String tenantIdentifier) throws SQLException {
            Connection connection = dataSource.getConnection();
            applyTenant(connection, tenantIdentifier);
            return connection;
        }

        @Override
        public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
            resetTenant(connection);
            connection.close();
        }

        @Override
        public boolean supportsAggressiveRelease() {
            return false;
        }

        @Override
        public boolean isUnwrappableAs(Class<?> unwrapType) {
            return false;
        }

        @Override
        public <T> T unwrap(Class<T> unwrapType) {
            return null;
        }

        private void applyTenant(Connection connection, String tenantIdentifier) throws SQLException {
            String value = (tenantIdentifier == null || NO_TENANT.equals(tenantIdentifier))
                    ? "" : tenantIdentifier;
            try (PreparedStatement statement = connection.prepareStatement(SET_ORG_ID)) {
                statement.setString(1, value);
                statement.execute();
            }
        }

        private void resetTenant(Connection connection) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(SET_ORG_ID)) {
                statement.setString(1, "");
                statement.execute();
            }
        }
    }

    @Component
    public static class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

        @Override
        public String resolveCurrentTenantIdentifier() {
            Long organizationId = TenantContext.get();
            return organizationId == null ? NO_TENANT : String.valueOf(organizationId);
        }

        @Override
        public boolean validateExistingCurrentSessions() {
            return true;
        }
    }
}
```

**Zones d'incertitude technique — à valider par exécution, pas par lecture** (le spike recommandé
par le design) :

1. Signature exacte de `MultiTenantConnectionProvider<T>`/`CurrentTenantIdentifierResolver<T>` dans
   la version d'Hibernate ORM réellement résolue par `spring-boot-starter-parent:3.5.6` (vérifier via
   `mvn dependency:tree | grep hibernate-core`). Le générique `<String>` ci-dessus est ma meilleure
   estimation ; un écart de signature est une simple erreur de compilation à corriger, pas une
   régression silencieuse.
2. Hibernate 6.x nécessite-t-il encore une propriété `hibernate.multiTenancy=DATABASE`
   (`MultiTenancyStrategy`, héritage Hibernate 5) en plus de `MULTI_TENANT_CONNECTION_PROVIDER`/
   `MULTI_TENANT_IDENTIFIER_RESOLVER`, ou la simple présence du provider suffit-elle à activer le
   mode multi-tenant ? Si le démarrage réussit mais qu'aucun `set_config` n'est jamais exécuté
   (observable en loggant `TenantAwareConnectionProvider.getConnection`), c'est le symptôme de cette
   incertitude.
3. Le `DataSource` Spring Boot auto-configuré (Hikari) reste par ailleurs bindé à
   `hibernate.connection.datasource` par l'auto-configuration JPA de Spring Boot. Vérifier
   empiriquement qu'Hibernate route bien **toutes** les acquisitions de connexion via
   `TenantAwareConnectionProvider` et ne bascule jamais sur le `DataSource` brut (ce qui court-
   circuiterait `set_config` silencieusement).
4. Granularité réelle de `getConnection(tenantId)` : est-il invoqué une fois par `Session` Hibernate
   (= une fois par transaction Spring Data JPA implicite, ce dont dépend la correction d'`AuthService`
   ci-dessous), ou dans d'autres circonstances non anticipées par cette lecture statique ? C'est
   exactement ce que `RowLevelSecurityIT` (AC2) et `TenantConnectionConfigTest` doivent confirmer
   avant de considérer le ticket terminé.

### TenantContextFilter.java (structure complète)

```java
package com.creditflow.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

    private final CurrentShopContext currentShopContext;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        try {
            if (authenticated) {
                TenantContext.set(currentShopContext.currentOrganizationId());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

### SecurityConfig.java (ajout)

```java
private final TenantContextFilter tenantContextFilter;
// ...
http
    // ... inchange ...
    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
    .addFilterAfter(tenantContextFilter, JwtAuthenticationFilter.class);
```

### AuthService.java — section critique, à suivre à la lettre

Le design dit « scinder `login()` en deux transactions » sans traiter un piège Spring classique : si
`login()` reste elle-même `@Transactional` et appelle des méthodes `@Transactional` de sa **propre**
classe, l'auto-invocation ne passe pas par le proxy Spring et les deux méthodes s'exécutent dans la
**même** session/connexion déjà ouverte — la scission n'a alors aucun effet et le bug (liste de
boutiques vide) réapparaît silencieusement. La solution ci-dessous l'évite en ne mettant
**aucune** annotation `@Transactional` sur `login()` elle-même et en délégant chaque étape à un bean
**différent** (`UserRepository`, `CurrentShopContext`), chacun traversant son propre proxy Spring.

```java
public AuthResponse login(LoginRequest request) {
    authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.username(), request.password()));

    // Etape 1 : userRepository est un bean distinct d'AuthService -- cet appel ouvre sa
    // propre transaction/session (comportement par defaut de SimpleJpaRepository), meme
    // si login() n'est plus @Transactional. Ne touche que `users` (hors RLS).
    User user = userRepository.findByUsernameIgnoreCase(request.username())
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));

    // user.getOrganization() est un proxy lazy : .getId() est lisible sans requete
    // supplementaire (l'id de la FK est deja connu), meme sur une entite detachee.
    TenantContext.set(user.getOrganization().getId());
    try {
        // Etape 2 : currentShopContext est aussi un bean distinct -- nouvel appel a travers
        // le proxy Spring, donc nouvelle transaction/session, cette fois avec le tenant
        // resolu. Recharge l'utilisateur (necessaire : l'instance de l'etape 1 est detachee,
        // sa collection `shops` lazy ne peut pas etre initialisee dans une autre session).
        User reloaded = currentShopContext.reloadWithShopsInitialized(request.username());

        String token = jwtService.generateToken(reloaded.getUsername(), reloaded.getRole().name());
        log.info("Connexion reussie pour {}", reloaded.getUsername());
        PlanSummary plan = new PlanSummary(
                properties.getPlan().isMultiShop(), properties.getPlan().isWhatsappAuto());

        return new AuthResponse(token, "Bearer", jwtService.expiryOf(token), toResponse(reloaded),
                currentShopContext.accessibleShops(reloaded), plan);
    } finally {
        TenantContext.clear();
    }
}
```

Nouvelle méthode sur `CurrentShopContext` (bean déjà responsable de la résolution des boutiques) :

```java
/**
 * Recharge l'utilisateur par nom et force l'initialisation de sa collection de
 * boutiques dans la session courante, avant que la transaction ne se ferme.
 * Utilise exclusivement par AuthService.login(), seul appelant qui a besoin
 * d'une session ouverte APRES resolution du tenant (voir design #40).
 */
@Transactional(readOnly = true)
public User reloadWithShopsInitialized(String username) {
    User user = userRepository.findByUsernameIgnoreCase(username)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));
    org.hibernate.Hibernate.initialize(user.getShops());
    return user;
}
```

`Hibernate.initialize(...)` force le chargement de la collection `user_shops`/`shops` (protégées par
RLS) **pendant que la session de l'étape 2 est encore ouverte** — après cet appel, la collection est
un `Set` matérialisé, lisible sans session active, donc `toResponse(reloaded)` et
`currentShopContext.accessibleShops(reloaded)` (appelés après le retour de
`reloadWithShopsInitialized`, donc hors de toute transaction active côté `AuthService`) peuvent lire
`user.getShops()` sans `LazyInitializationException`. `accessibleShops(user)` n'a pas besoin d'être
elle-même `@Transactional` : si la collection est vide (cas ADMIN sans assignation directe), elle
retombe sur `shopRepository.findAllByActiveTrueAndOrganizationIdOrderByNameAsc(...)`, un appel
Spring Data JPA qui ouvre sa propre transaction/session implicite (tenant déjà résolu à ce stade).

### DemoDataSeeder.java (modification)

```java
private final OrganizationRepository organizationRepository; // nouvelle dependance

// dans seedDemoData(), avant seedCustomers()/seedProducts()/... :
authenticateAsAdmin();
Organization organization = organizationRepository.findFirstByOrderByIdAsc()
        .orElseThrow(() -> new IllegalStateException(
                "Aucune organisation par defaut trouvee : la migration V13 doit etre appliquee."));
TenantContext.set(organization.getId());
try {
    // ... seedCustomers()/seedProducts()/seedSales()/seedPayments() inchanges ...
} finally {
    TenantContext.clear();
    SecurityContextHolder.clearContext();
}
```

Même pattern que `AdminInitializer` (`findFirstByOrderByIdAsc()`, table `organizations` hors RLS,
donc toujours lisible quel que soit l'état de `TenantContext` à cet instant).

### StockReception.java / StockReceptionService.java

```java
// StockReception.java — ajout
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "shop_id", nullable = false)
private Shop shop;
```

```java
// StockReceptionService.java — receive(), ajout d'une dependance ShopRepository
private final ShopRepository shopRepository;
// ...
StockReception reception = StockReception.builder()
        .supplier(supplier)
        .shop(shopRepository.getReferenceById(targetShopId))
        .receivedAt(request.receivedAt())
        .notes(request.notes())
        .build();
```

Même pattern que `ShopService.create()`/`CreditSaleService` (`shopRepository.getReferenceById(...)`,
pas de `findById` : évite une requête, la FK est validée par Postgres à l'INSERT).

### Runbook — instances Docker Compose déjà déployées

À ajouter dans `README.md`, section « Sécurité » :

```
Sur une instance déjà lancée en production, le script docker-entrypoint-initdb.d ne se
rejoue pas automatiquement. Avant de mettre à jour vers cette version :

1. Se connecter en superuser : `docker compose exec db psql -U <DB_USERNAME actuel> -d <DB_NAME>`
2. Exécuter :
   CREATE ROLE creditflow_app LOGIN PASSWORD '<mot de passe choisi>';
   GRANT CONNECT ON DATABASE <DB_NAME> TO creditflow_app;
   GRANT USAGE ON SCHEMA public TO creditflow_app;
3. Dans .env : renommer DB_USERNAME/DB_PASSWORD actuels en DB_MIGRATION_USERNAME/
   DB_MIGRATION_PASSWORD, ajouter DB_APP_USERNAME=creditflow_app et DB_APP_PASSWORD=<le
   mot de passe choisi a l'etape 2>.
4. `docker compose up -d --build` (Flyway applique V14-V16 avec les identifiants de
   migration ; le backend démarre ensuite avec les identifiants applicatifs restreints).

Sans l'étape 2, le démarrage échoue au moment de V16 (`GRANT ... TO creditflow_app` sur un
rôle inexistant).
```

---

## Plan de tests

| Critère d'acceptation | Test | Mécanisme |
|---|---|---|
| AC1 — une requête SQL directe sous un autre tenant ne voit pas les données d'une autre organisation | `RowLevelSecurityIT` (méthode dédiée AC1) | Testcontainers, connexion JDBC brute sous `creditflow_app`, `SET app.current_org_id` variable, requêtes directes sur `customers`/`shops`/`credit_sales` |
| AC2 — absence de fuite entre deux requêtes consécutives de tenants différents sur la même connexion physique | `RowLevelSecurityIT` (méthode dédiée AC2) + `TenantConnectionConfigTest` | Voir détail ci-dessous : deux angles complémentaires (fonctionnel via Hibernate, et défense-en-profondeur via JDBC brut) |
| AC3 — instance mono-tenant : comportement strictement identique | `RowLevelSecurityIT` (test de non-régression exécuté **en premier**, avant tout scénario multi-org) + suite Mockito existante inchangée | Testcontainers, une seule organisation (comme V13 à froid), vérifie que toutes les données existantes restent visibles |
| `AuthService.login()` retourne les boutiques accessibles après scission en deux étapes | `AuthServiceTest` (adapté) | Mockito, mock de `currentShopContext.reloadWithShopsInitialized(...)` |
| `StockReceptionService.receive()` associe la bonne boutique | test existant de `StockReceptionServiceTest` (si présent) à étendre — sinon, ajouter une assertion sur `reception.getShop()` | Mockito |
| `TenantAwareConnectionProvider`/`TenantIdentifierResolver` appellent bien `set_config`/RESET | `TenantConnectionConfigTest` | Mockito (DataSource/Connection/PreparedStatement mockés) |
| Ignoré proprement si Docker absent | `RowLevelSecurityIT` | `Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), ...)` — voir mécanisme exact ci-dessous |

### Mécanisme exact de détection Docker (ignoré, pas échoué)

**Ne pas** utiliser `@Testcontainers`/`@Container` en lifecycle automatique pour le conteneur
principal : l'extension JUnit 5 de Testcontainers démarre le conteneur dans **son propre**
`beforeAll`, sans garantie d'ordre par rapport à un `@BeforeAll` défini sur la classe de test — si
Docker est absent, `.start()` échoue avec une exception dure avant même que l'assumption ait pu
s'exécuter, ce qui **échoue** le test au lieu de l'ignorer. Pattern retenu, qui élimine ce risque
d'ordonnancement en gardant un contrôle total dans une seule méthode :

```java
class RowLevelSecurityIT {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startContainerIfDockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker indisponible : RowLevelSecurityIT est ignore (voir spec #40).");

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("creditflow_it")
                .withUsername("creditflow_it_owner")
                .withPassword("creditflow_it_owner");
        postgres.start();

        try (Connection admin = postgres.createConnection("");
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE creditflow_app LOGIN PASSWORD 'creditflow_app'");
            statement.execute("GRANT CONNECT ON DATABASE " + postgres.getDatabaseName()
                    + " TO creditflow_app");
            statement.execute("GRANT USAGE ON SCHEMA public TO creditflow_app");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .placeholders(Map.of("creditflowAppRole", "creditflow_app"))
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    // ... @Test AC1, @Test AC2, @Test non-regression mono-tenant ...
}
```

`DockerClientFactory.instance().isDockerAvailable()` et `PostgreSQLContainer.createConnection(String)`
sont des API Testcontainers documentées, pas une supposition.

### AC1 — détail du test

1. Dans `@BeforeAll` (après migration) : via une connexion superuser, insérer deux organisations
   (A, B), une boutique par organisation, un client par boutique.
2. Nouvelle connexion JDBC via `creditflow_app`/`creditflow_app` : `SET app.current_org_id = '<idA>'`
   puis `SELECT * FROM customers` → un seul client, celui de l'organisation A.
3. Même connexion ou une nouvelle : `SET app.current_org_id = '<idB>'` → un seul client, celui de B.
4. Vérifier aussi `shops`, `products`, `credit_sales` (au moins une table à deux sauts, par exemple
   `installments`, pour couvrir les policies à jointure double).

### AC2 — détail du test (deux angles, complémentaires)

**Angle fonctionnel (via Hibernate/Spring, `@SpringBootTest` avec `@DynamicPropertySource` pointant
vers le conteneur, `spring.datasource.hikari.maximum-pool-size=1` pour forcer la réutilisation
physique)** : positionner `TenantContext` sur l'organisation A, effectuer une lecture via un
repository (transaction #1), positionner `TenantContext` sur l'organisation B, effectuer une lecture
(transaction #2) — avec un pool de taille 1, HikariCP réutilise nécessairement la même connexion
physique. Vérifier que la deuxième lecture ne renvoie que les données de B.

**Angle défense-en-profondeur (JDBC brut, contourne délibérément Hibernate)** : avec ce même pool de
taille 1, juste après la transaction #1 (tenant A) libérée par Hibernate, emprunter directement une
connexion depuis le même bean `DataSource` (sans passer par Hibernate) et exécuter
`SELECT current_setting('app.current_org_id', true)` **sans faire de SET au préalable** — la valeur
doit être vide/NULL, prouvant que `releaseConnection` a bien exécuté le RESET. Ce test échouerait si
quelqu'un supprimait le RESET dans `TenantAwareConnectionProvider.releaseConnection` en pensant que
le prochain `set_config` à l'emprunt suivant suffit (vrai pour les emprunts qui passent par
Hibernate, faux pour tout futur consommateur du même pool qui ne passerait pas par le provider).

### Test de non-régression mono-tenant (à exécuter en premier, avant tout scénario multi-org)

Une seule organisation (comme un déploiement V13 à froid) : toutes les policies doivent laisser
passer toutes les lignes existantes sans aucun filtrage observable. Une erreur de policy (mauvaise
colonne, jointure inversée) casserait silencieusement l'accès aux données même en mono-tenant dès
l'activation de `FORCE ROW LEVEL SECURITY` — ce cas doit être détecté avant les tests multi-org, pas
après.

### TenantConnectionConfigTest.java (structure)

```java
class TenantConnectionConfigTest {

    @Test
    void getConnectionAppliesSetConfigWithTenantValue() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        var provider = new TenantConnectionConfig.TenantAwareConnectionProvider(dataSource);
        Connection result = provider.getConnection("42");

        assertThat(result).isSameAs(connection);
        verify(statement).setString(1, "42");
        verify(statement).execute();
    }

    @Test
    void releaseConnectionResetsTenantAndCloses() throws SQLException {
        // ... meme montage, verifie statement.setString(1, "") puis connection.close() ...
    }

    @Test
    void resolverReturnsSentinelWhenThreadLocalEmpty() {
        TenantContext.clear();
        var resolver = new TenantConnectionConfig.TenantIdentifierResolver();
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(TenantConnectionConfig.NO_TENANT);
    }

    @Test
    void resolverReturnsOrganizationIdWhenSet() {
        TenantContext.set(7L);
        try {
            var resolver = new TenantConnectionConfig.TenantIdentifierResolver();
            assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo("7");
        } finally {
            TenantContext.clear();
        }
    }
}
```

### AuthServiceTest.java — adaptations nécessaires

- Ajouter `reloadWithShopsInitialized(String)` aux stubs `currentShopContext` dans `setUp()` et dans
  chaque test qui appelle `login()` (`loginIncludesAccessibleShops`, `loginIncludesPlan`) :
  `when(currentShopContext.reloadWithShopsInitialized("admin")).thenReturn(user);`
- `loginResolvesAccessibleShopsWhileStillAnonymous` construit un vrai `CurrentShopContext` avec un
  `UserRepository` mocké : ajouter
  `when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(user));` pour que
  `reloadWithShopsInitialized` (implémentation réelle, pas mockée dans ce test) trouve l'utilisateur.
  `Hibernate.initialize(user.getShops())` est un no-op sûr sur le `HashSet` de test (pas un proxy
  Hibernate réel) — aucune adaptation nécessaire de ce côté.
- Les autres tests (`changePassword*`, `currentUserExposesTheObligation`) sont inchangés, `login()`
  n'étant plus `@Transactional` mais ces tests ne l'appellent pas.

---

## Écarts identifiés

- **`SecurityDefaultsValidator` n'est mentionné nulle part dans `design.md`** alors que ce ticket
  change la signification de `spring.datasource.password` (rôle applicatif restreint au lieu du
  rôle historique unique) : sans mise à jour, le garde-fou de mise en production (`app.security.strict`)
  ne détecterait plus un `DB_MIGRATION_PASSWORD` resté à la valeur de livraison, régression de
  sécurité silencieuse. Traité comme tâche 2 de la présente spec.
- **Nom du rôle applicatif ambigu dans `design.md`** : le texte cite à la fois un rôle littéral
  `creditflow_app` et une variable d'environnement `DB_APP_USERNAME` qui suggère un nom
  configurable, sans trancher lequel des deux fait foi pour `V16__app_role_grants.sql` (un script SQL
  versionné ne peut pas interpoler une variable d'environnement sans mécanisme dédié). Résolu ici par
  un placeholder Flyway (`spring.flyway.placeholders.creditflowAppRole`), mécanisme standard et
  documenté de Flyway — à confirmer en revue plutôt qu'à découvrir en CI.
- **Chemin de jointure de `stock_reception_lines` non tranché par `design.md`** (cité dans la même
  parenthèse que `stock_movements` sans préciser si la jointure passe par `product_id` ou par
  `reception_id`). Cette spec retient `reception_id → stock_receptions.shop_id`, plus direct
  maintenant que l'en-tête porte `shop_id` (V14), et n'introduit pas de dépendance à la cohérence
  boutique-produit/boutique-réception vérifiée uniquement côté applicatif (`StockReceptionService`).
- **Conséquence non anticipée par le design sur `ShopService.create()`** : cette méthode assigne
  systématiquement `resolveDefaultOrganization()` (= la première organisation, bug préexistant déjà
  documenté par #35-#39, hors périmètre de ce ticket). Avec `FORCE ROW LEVEL SECURITY` actif, un
  administrateur d'une organisation autre que la première verrait cette création échouer avec une
  violation de policy RLS (`new row violates row-level security policy`) au lieu de réussir en
  attribuant silencieusement la boutique à la mauvaise organisation. C'est un changement de mode de
  défaillance (échec bruyant au lieu de corruption silencieuse) plutôt qu'une régression au sens
  strict, sans impact sur les critères d'acceptation de ce ticket (mono-tenant : une seule
  organisation, donc `resolveDefaultOrganization()` retourne toujours la bonne) — mais à signaler
  explicitement en revue, et à traiter par un ticket dédié si une deuxième organisation est un jour
  activée en production.
