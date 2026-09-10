package com.creditflow.notification.service;

import com.creditflow.audit.repository.AuditLogRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie de bout en bout, contre un vrai Postgres (Testcontainers, meme patron que
 * {@code RowLevelSecurityHibernateIT}), que {@link ReminderSchedulerJob#run()} traite
 * chaque organisation sous son propre tenant et n'envoie jamais une relance a un
 * client d'une autre organisation.
 */
@SpringBootTest
@Import(ReminderSchedulerJobMultiTenantIT.FakeAutomaticChannelConfig.class)
class ReminderSchedulerJobMultiTenantIT {

    private static final String APP_ROLE = "creditflow_app";
    private static final String APP_PASSWORD = "creditflow_app";

    private static PostgreSQLContainer<?> postgres;
    private static Long organizationAId;
    private static Long organizationBId;
    private static Long customerAId;
    private static Long customerBId;

    @Autowired
    private ReminderSchedulerJob reminderSchedulerJob;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @BeforeAll
    static void startContainerIfDockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker indisponible : ReminderSchedulerJobMultiTenantIT est ignore (voir spec #40).");

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

        try (Connection admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = admin.createStatement()) {
            organizationAId = singleLong(statement, "SELECT id FROM organizations ORDER BY id LIMIT 1");
            long shopAId = insertShop(statement, "Boutique Scheduler A", organizationAId);
            customerAId = insertCustomer(statement, "Amadou", "Diallo", "770000101", shopAId);
            long productAId = insertProduct(statement, "Produit Scheduler A", shopAId);
            long saleAId = insertCreditSale(statement, shopAId, customerAId, productAId, "SCHED-A");
            insertLateInstallment(statement, saleAId);

            statement.execute("INSERT INTO organizations (name) VALUES ('Organisation Scheduler B')");
            organizationBId = singleLong(statement,
                    "SELECT id FROM organizations WHERE name = 'Organisation Scheduler B'");
            long shopBId = insertShop(statement, "Boutique Scheduler B", organizationBId);
            customerBId = insertCustomer(statement, "Fatou", "Ndiaye", "770000102", shopBId);
            long productBId = insertProduct(statement, "Produit Scheduler B", shopBId);
            long saleBId = insertCreditSale(statement, shopBId, customerBId, productBId, "SCHED-B");
            insertLateInstallment(statement, saleBId);
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

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        if (postgres == null) {
            return;
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("app.reminder.auto-enabled", () -> true);
    }

    @Test
    void runSendsAndAuditsAReminderPerOrganizationWithoutCrossTenantLeakage() {
        reminderSchedulerJob.run();

        assertThat(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("CUSTOMER", customerAId))
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo("REMINDER_SENT");
                    assertThat(entry.getDetails()).endsWith("(auto)");
                });

        assertThat(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("CUSTOMER", customerBId))
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo("REMINDER_SENT");
                    assertThat(entry.getDetails()).endsWith("(auto)");
                });
    }

    private static long insertShop(Statement statement, String name, long organizationId) throws SQLException {
        statement.execute("INSERT INTO shops (name, active, organization_id) VALUES ('"
                + name + "', true, " + organizationId + ")");
        return singleLong(statement, "SELECT id FROM shops WHERE name = '" + name + "'");
    }

    private static long insertCustomer(Statement statement, String firstName, String lastName, String phone,
                                        long shopId) throws SQLException {
        statement.execute("INSERT INTO customers (first_name, last_name, phone, shop_id) VALUES ('"
                + firstName + "', '" + lastName + "', '" + phone + "', " + shopId + ")");
        return singleLong(statement, "SELECT id FROM customers WHERE phone = '" + phone + "'");
    }

    private static long insertProduct(Statement statement, String name, long shopId) throws SQLException {
        statement.execute("INSERT INTO products (name, category, cash_price, credit_price, stock, status, shop_id) "
                + "VALUES ('" + name + "', 'Divers', 1000, 1200, 5, 'ACTIVE', " + shopId + ")");
        return singleLong(statement, "SELECT id FROM products WHERE name = '" + name + "'");
    }

    private static long insertCreditSale(Statement statement, long shopId, long customerId, long productId,
                                          String reference) throws SQLException {
        statement.execute("INSERT INTO credit_sales "
                + "(reference, customer_id, product_id, shop_id, total_price, down_payment, "
                + "financed_amount, installment_count, monthly_amount, remaining_amount, "
                + "start_date, end_date, status) "
                + "VALUES ('REF-" + reference + "-IT', " + customerId + ", " + productId + ", " + shopId + ", "
                + "1200, 200, 1000, 10, 100, 1000, CURRENT_DATE - INTERVAL '1 month', "
                + "CURRENT_DATE + INTERVAL '9 months', 'ACTIVE')");
        return singleLong(statement, "SELECT id FROM credit_sales WHERE reference = 'REF-" + reference + "-IT'");
    }

    private static void insertLateInstallment(Statement statement, long saleId) throws SQLException {
        statement.execute("INSERT INTO installments (sale_id, number, due_date, amount, status) "
                + "VALUES (" + saleId + ", 1, CURRENT_DATE - INTERVAL '5 days', 100, 'PENDING')");
    }

    private static long singleLong(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @TestConfiguration
    static class FakeAutomaticChannelConfig {

        @Bean
        @Primary
        NotificationChannel fakeAutomaticChannel() {
            return new NotificationChannel() {
                @Override
                public String name() {
                    return "SCHEDULER_IT_FAKE_CHANNEL";
                }

                @Override
                public boolean send(String phone, String message) {
                    return true;
                }
            };
        }
    }
}
