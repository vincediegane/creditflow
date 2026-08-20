package com.creditflow.sale.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateSaleRequestValidationTest {

    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("rejette un nom de garant seul, sans telephone")
    void rejectsGuarantorNameWithoutPhone() {
        CreateSaleRequest request = baseRequest("Moussa Kane", null);

        Set<ConstraintViolation<CreateSaleRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("guarantorConsistent"));
    }

    @Test
    @DisplayName("rejette un telephone de garant seul, sans nom")
    void rejectsGuarantorPhoneWithoutName() {
        CreateSaleRequest request = baseRequest(null, "770001122");

        Set<ConstraintViolation<CreateSaleRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("guarantorConsistent"));
    }

    @Test
    @DisplayName("accepte un nom et un telephone de garant renseignes ensemble")
    void acceptsGuarantorNameAndPhoneTogether() {
        CreateSaleRequest request = baseRequest("Moussa Kane", "770001122");

        Set<ConstraintViolation<CreateSaleRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("accepte l'absence totale de garant")
    void acceptsNoGuarantorAtAll() {
        CreateSaleRequest request = baseRequest(null, null);

        Set<ConstraintViolation<CreateSaleRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    private CreateSaleRequest baseRequest(String guarantorFullName, String guarantorPhone) {
        return new CreateSaleRequest(1L, 1L, BigDecimal.valueOf(100000), BigDecimal.valueOf(20000),
                null, null, 6, LocalDate.now(), null,
                guarantorFullName, guarantorPhone, null, null);
    }
}
