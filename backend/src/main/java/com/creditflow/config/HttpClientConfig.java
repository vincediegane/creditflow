package com.creditflow.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Isole le {@link RestTemplate} dans une configuration a part : une configuration
 * {@code WebMvcConfigurer} serait automatiquement chargee par les tranches
 * {@code @WebMvcTest}, ce qui exigerait sinon un {@code RestTemplateBuilder} (non
 * autoconfigure dans ces tranches) pour chaque test de controleur du projet.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}
