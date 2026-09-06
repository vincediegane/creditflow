package com.creditflow.organization.service;

import com.creditflow.config.AppProperties;
import com.creditflow.organization.domain.OrganizationPlan;
import com.creditflow.organization.repository.OrganizationPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationPlanResolverTest {

    @Mock
    private OrganizationPlanRepository organizationPlanRepository;

    @Mock
    private AppProperties properties;

    private OrganizationPlanResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new OrganizationPlanResolver(organizationPlanRepository, properties);
    }

    @Test
    @DisplayName("retombe sur AppProperties.Plan quand aucune ligne n'existe pour l'organisation")
    void fallsBackToInstancePlanWhenNoRow() {
        when(organizationPlanRepository.findById(1L)).thenReturn(Optional.empty());
        AppProperties.Plan plan = new AppProperties.Plan();
        plan.setMultiShop(true);
        when(properties.getPlan()).thenReturn(plan);

        assertThat(resolver.multiShopEnabled(1L)).isTrue();

        when(organizationPlanRepository.findById(2L)).thenReturn(Optional.empty());
        AppProperties.Plan singleShopPlan = new AppProperties.Plan();
        singleShopPlan.setMultiShop(false);
        when(properties.getPlan()).thenReturn(singleShopPlan);

        assertThat(resolver.multiShopEnabled(2L)).isFalse();
    }

    @Test
    @DisplayName("utilise la valeur de organization_plan quand une ligne existe, sans consulter AppProperties.Plan")
    void overridesWhenRowExists() {
        when(organizationPlanRepository.findById(1L))
                .thenReturn(Optional.of(OrganizationPlan.builder().organizationId(1L).multiShop(false).build()));
        when(organizationPlanRepository.findById(2L))
                .thenReturn(Optional.of(OrganizationPlan.builder().organizationId(2L).multiShop(true).build()));

        assertThat(resolver.multiShopEnabled(1L)).isFalse();
        assertThat(resolver.multiShopEnabled(2L)).isTrue();
    }
}
