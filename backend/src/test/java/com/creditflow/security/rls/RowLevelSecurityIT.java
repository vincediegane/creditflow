package com.creditflow.security.rls;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie les criteres d'acceptation #40 contre un vrai Postgres (Testcontainers) :
 * AC1 (acces SQL direct isole par organisation), AC2 (absence de fuite entre deux
 * requetes consecutives sur la meme connexion physique) et la non-regression
 * mono-tenant (RLS ne doit rien changer d'observable sur une instance a une seule
 * organisation).
 *
 * <p>Ignore proprement (pas echoue) si Docker est indisponible -- voir
 * {@link #startContainerIfDockerAvailable()} : demarrage manuel du conteneur dans un
 * {@code @BeforeAll} unique plutot que {@code @Testcontainers}/{@code @Container},
 * pour garder le controle total sur l'ordre assumption-avant-demarrage (voir spec #40).</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RowLevelSecurityIT {

    private static final String APP_ROLE = "creditflow_app";
    private static final String APP_PASSWORD = "creditflow_app";

    private static PostgreSQLContainer<?> postgres;

    private static Long organizationAId;
    private static Long shopAId;

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
            statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE " + postgres.getDatabaseName() + " TO " + APP_ROLE);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .placeholders(Map.of("creditflowAppRole", APP_ROLE))
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // V13 a deja insere une organisation par defaut (id=1) et V10 une boutique par
        // defaut ("Boutique principale", rattachee a cette organisation) -- instance
        // mono-tenant a froid, exactement le scenario du test de non-regression. On les
        // reutilise avant d'introduire une deuxieme organisation dans ac1_....
        try (Connection admin = ownerConnection();
             Statement statement = admin.createStatement()) {
            organizationAId = firstOrganizationId(admin);
            shopAId = singleLong(statement, "SELECT id FROM shops ORDER BY id LIMIT 1");
            statement.execute("INSERT INTO customers (first_name, last_name, phone, shop_id) "
                    + "VALUES ('Amadou', 'Diallo', '770000001', " + shopAId + ")");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Order(1)
    void monoTenantNonRegression_seesAllExistingDataUnderTheSingleOrganization() throws SQLException {
        try (Connection app = appConnection()) {
            setCurrentOrgId(app, organizationAId);

            assertThat(countRows(app, "SELECT * FROM shops")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM customers")).isEqualTo(1);
        }
    }

    @Test
    @Order(2)
    void ac1_directSqlAccessIsIsolatedPerOrganization() throws SQLException {
        Long organizationBId;
        Long shopBId;
        try (Connection admin = ownerConnection();
             Statement statement = admin.createStatement()) {
            statement.execute("INSERT INTO organizations (name) VALUES ('Organisation B')");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT id FROM organizations WHERE name = 'Organisation B'")) {
                rs.next();
                organizationBId = rs.getLong("id");
            }
            statement.execute("INSERT INTO shops (name, active, organization_id) "
                    + "VALUES ('Boutique B', true, " + organizationBId + ")");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT id FROM shops WHERE name = 'Boutique B'")) {
                rs.next();
                shopBId = rs.getLong("id");
            }
            statement.execute("INSERT INTO customers (first_name, last_name, phone, shop_id) "
                    + "VALUES ('Fatou', 'Ndiaye', '770000002', " + shopBId + ")");

            statement.execute("INSERT INTO products (name, category, cash_price, credit_price, stock, status, shop_id) "
                    + "VALUES ('Produit A', 'Divers', 1000, 1200, 5, 'ACTIVE', " + shopAId + ")");
            statement.execute("INSERT INTO products (name, category, cash_price, credit_price, stock, status, shop_id) "
                    + "VALUES ('Produit B', 'Divers', 1000, 1200, 5, 'ACTIVE', " + shopBId + ")");

            long customerAId = singleLong(statement, "SELECT id FROM customers WHERE phone = '770000001'");
            long productAId = singleLong(statement, "SELECT id FROM products WHERE name = 'Produit A'");
            long saleAId = insertCreditSale(statement, shopAId, customerAId, productAId, "A");

            long customerBId = singleLong(statement, "SELECT id FROM customers WHERE phone = '770000002'");
            long productBId = singleLong(statement, "SELECT id FROM products WHERE name = 'Produit B'");
            insertCreditSale(statement, shopBId, customerBId, productBId, "B");

            statement.execute("INSERT INTO installments (sale_id, number, due_date, amount, status) "
                    + "VALUES (" + saleAId + ", 1, CURRENT_DATE, 1000, 'PENDING')");
        }

        try (Connection app = appConnection()) {
            setCurrentOrgId(app, organizationAId);
            assertThat(countRows(app, "SELECT * FROM shops")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM customers")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM products")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM credit_sales")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM installments")).isEqualTo(1);

            setCurrentOrgId(app, organizationBId);
            assertThat(countRows(app, "SELECT * FROM shops")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM customers")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM products")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM credit_sales")).isEqualTo(1);
            assertThat(countRows(app, "SELECT * FROM installments")).isEqualTo(0);
        }
    }

    @Test
    @Order(3)
    void ac2_noLeakageBetweenConsecutiveTenantsOnTheSamePhysicalConnection() throws SQLException {
        try (Connection app = appConnection()) {
            setCurrentOrgId(app, organizationAId);
            assertThat(countRows(app, "SELECT * FROM customers")).isEqualTo(1);

            // Meme java.sql.Connection, jamais fermee entre les deux SET : simule la
            // reutilisation d'une connexion physique par le pool applicatif (HikariCP).
            long organizationBId = singleLong(app.createStatement(),
                    "SELECT id FROM organizations WHERE name = 'Organisation B'");
            setCurrentOrgId(app, organizationBId);
            assertThat(countRows(app, "SELECT * FROM customers")).isEqualTo(1);

            try (Statement statement = app.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT first_name FROM customers LIMIT 1")) {
                rs.next();
                assertThat(rs.getString("first_name")).isEqualTo("Fatou");
            }

            setCurrentOrgId(app, null);
            assertThat(countRows(app, "SELECT * FROM customers")).isEqualTo(0);
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    private static long firstOrganizationId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id FROM organizations ORDER BY id LIMIT 1")) {
            rs.next();
            return rs.getLong("id");
        }
    }

    private static long singleLong(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long insertCreditSale(Statement statement, long shopId, long customerId, long productId,
                                          String label) throws SQLException {
        statement.execute("INSERT INTO credit_sales "
                + "(reference, customer_id, product_id, shop_id, total_price, down_payment, "
                + "financed_amount, installment_count, monthly_amount, remaining_amount, "
                + "start_date, end_date, status) "
                + "VALUES ('REF-" + label + "-IT', " + customerId + ", " + productId + ", " + shopId + ", "
                + "1200, 200, 1000, 10, 100, 1000, CURRENT_DATE, CURRENT_DATE + INTERVAL '10 months', 'ACTIVE')");
        return singleLong(statement, "SELECT id FROM credit_sales WHERE shop_id = " + shopId);
    }

    private static void setCurrentOrgId(Connection connection, Long organizationId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT set_config('app.current_org_id', ?, false)")) {
            statement.setString(1, organizationId == null ? "" : String.valueOf(organizationId));
            statement.execute();
        }
    }

    private static int countRows(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            return count;
        }
    }
}
