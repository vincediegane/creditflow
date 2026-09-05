package com.creditflow.config;

import com.creditflow.auth.security.AppUserDetailsService;
import com.creditflow.auth.security.JwtAuthenticationFilter;
import com.creditflow.auth.security.JwtService;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.common.security.TenantContextFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, TenantContextFilter.class,
        AbstractWebMvcSecurityTest.TestSecurityBeans.class })
public abstract class AbstractWebMvcSecurityTest {

    @MockBean
    protected JwtService jwtService;

    @MockBean
    protected AppUserDetailsService appUserDetailsService;

    @MockBean
    protected CurrentShopContext currentShopContext;

    @TestConfiguration
    static class TestSecurityBeans {

        @Bean
        AppProperties appProperties() {
            AppProperties properties = new AppProperties();
            properties.getCors().setAllowedOrigins(List.of("http://localhost:5173"));
            return properties;
        }
    }
}
