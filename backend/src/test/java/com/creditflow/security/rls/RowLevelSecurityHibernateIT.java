package com.creditflow.security.rls;

import com.creditflow.common.security.TenantContext;
import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.repository.CustomerRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complement fonctionnel a {@link RowLevelSecurityIT} (JDBC brut) : verifie AC2 en passant
 * reellement par Hibernate/Spring Data JPA, avec un pool HikariCP de taille 1 pour forcer
 * la reutilisation de la meme connexion physique entre deux transactions de tenants
 * differents -- exactement la zone d'incertitude technique de la spec #40 (granularite reelle
 * de {@code MultiTenantConnectionProvider.getConnection(tenantId)}).
 *
 * <p>Classe distincte de {@link RowLevelSecurityIT} : combiner un {@code @SpringBootTest}
 * (contexte complet) avec des verifications JDBC brutes independantes dans une seule classe
 * de test aurait exige soit des classes {@code @Nested} avec un partage de contexte Spring
 * fragile a valider sans Docker fonctionnel dans cet environnement, soit un double
 * demarrage de conteneur dans la meme classe -- deux conteneurs Testcontainers distincts,
 * un par classe de test, restent le choix le plus simple a auditer.</p>
 */
@SpringBootTest
class RowLevelSecurityHibernateIT {

    private static final String APP_ROLE = "creditflow_app";
    private static final String APP_PASSWORD = "creditflow_app";

    private static PostgreSQLContainer<?> postgres;
    private static Long organizationAId;
    private static Long organizationBId;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void startContainerIfDockerAvailable() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker indisponible : RowLevelSecurityHibernateIT est ignore (voir spec #40).");

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

            statement.execute("INSERT INTO shops (name, active, organization_id) "
                    + "VALUES ('Boutique Hibernate A', true, " + organizationAId + ")");
            long shopAId = singleLong(statement, "SELECT id FROM shops WHERE name = 'Boutique Hibernate A'");
            statement.execute("INSERT INTO customers (first_name, last_name, phone, shop_id) "
                    + "VALUES ('Amadou', 'Diallo', '660000001', " + shopAId + ")");

            statement.execute("INSERT INTO organizations (name) VALUES ('Organisation Hibernate B')");
            organizationBId = singleLong(statement,
                    "SELECT id FROM organizations WHERE name = 'Organisation Hibernate B'");
            statement.execute("INSERT INTO shops (name, active, organization_id) "
                    + "VALUES ('Boutique Hibernate B', true, " + organizationBId + ")");
            long shopBId = singleLong(statement, "SELECT id FROM shops WHERE name = 'Boutique Hibernate B'");
            statement.execute("INSERT INTO customers (first_name, last_name, phone, shop_id) "
                    + "VALUES ('Fatou', 'Ndiaye', '660000002', " + shopBId + ")");
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        // Le schema est deja migre par Flyway.configure() dans startContainerIfDockerAvailable()
        // (avec le role proprietaire) : desactive pour eviter que Spring Boot ne retente une
        // migration avec le role applicatif restreint, qui n'a pas les privileges Flyway.
        registry.add("spring.flyway.enabled", () -> false);
    }

    @Test
    void tenantPropagatesThroughHibernateWithoutLeakingOnThePooledConnection() throws SQLException {
        TenantContext.set(organizationAId);
        try {
            List<Customer> customersA = customerRepository.findAll();
            assertThat(customersA).extracting(Customer::getFirstName).containsExactly("Amadou");
        } finally {
            TenantContext.clear();
        }

        // Angle defense-en-profondeur : la connexion physique (pool de taille 1) vient d'etre
        // rendue par Hibernate a la fin de la transaction precedente -- l'emprunter directement
        // en contournant Hibernate doit montrer que releaseConnection() a bien execute le RESET.
        try (Connection raw = dataSource.getConnection();
             Statement statement = raw.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT current_setting('app.current_org_id', true) AS org")) {
            rs.next();
            assertThat(rs.getString("org")).isNullOrEmpty();
        }

        TenantContext.set(organizationBId);
        try {
            List<Customer> customersB = customerRepository.findAll();
            assertThat(customersB).extracting(Customer::getFirstName).containsExactly("Fatou");
        } finally {
            TenantContext.clear();
        }
    }

    private static long singleLong(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
