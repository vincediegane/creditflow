package com.creditflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@RequiredArgsConstructor
public class UploadSizeGuardFilterConfig {

    private final MultipartProperties multipartProperties;
    private final ObjectMapper objectMapper;

    @Bean
    public FilterRegistrationBean<UploadSizeGuardFilter> uploadSizeGuardFilter() {
        FilterRegistrationBean<UploadSizeGuardFilter> registration = new FilterRegistrationBean<>(
                new UploadSizeGuardFilter(multipartProperties, objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
