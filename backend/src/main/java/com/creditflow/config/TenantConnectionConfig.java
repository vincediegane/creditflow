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
