package com.creditflow.config;

import com.creditflow.common.security.TenantContext;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        var provider = new TenantConnectionConfig.TenantAwareConnectionProvider(dataSource);
        provider.releaseConnection("42", connection);

        verify(statement).setString(1, "");
        verify(statement).execute();
        verify(connection).close();
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
