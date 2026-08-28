package com.creditflow.shop.service;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.config.AppProperties;
import com.creditflow.organization.domain.Organization;
import com.creditflow.organization.repository.OrganizationRepository;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.dto.ShopRequest;
import com.creditflow.shop.mapper.ShopMapper;
import com.creditflow.shop.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopServiceTest {

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private AppProperties properties;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private ShopService shopService;

    @BeforeEach
    void setUp() {
        when(properties.getPlan()).thenReturn(new AppProperties.Plan());
        when(organizationRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(Organization.builder().id(1L).name("Organisation par defaut").build()));
    }

    private ShopRequest request() {
        return new ShopRequest("Boutique Centre-ville", "Dakar", "770000002", true);
    }

    @Test
    @DisplayName("refuse un nom de boutique deja utilise")
    void rejectsDuplicateName() {
        when(shopRepository.existsByNameIgnoreCase("Boutique Centre-ville")).thenReturn(true);

        assertThatThrownBy(() -> shopService.create(request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Boutique Centre-ville");

        verify(shopRepository, never()).save(any());
    }

    @Test
    @DisplayName("cree une boutique valide")
    void createsShop() {
        Shop entity = Shop.builder().name("Boutique Centre-ville").address("Dakar")
                .phone("770000002").active(true).build();
        when(shopRepository.existsByNameIgnoreCase("Boutique Centre-ville")).thenReturn(false);
        when(shopMapper.toEntity(any(ShopRequest.class))).thenReturn(entity);
        when(shopRepository.save(any(Shop.class))).thenAnswer(i -> i.getArgument(0));

        shopService.create(request());

        verify(shopRepository).save(entity);
    }

    @Test
    @DisplayName("rattache l'organisation par defaut a la boutique creee")
    void createAssignsDefaultOrganization() {
        Shop entity = Shop.builder().name("Boutique Centre-ville").address("Dakar")
                .phone("770000002").active(true).build();
        when(shopRepository.existsByNameIgnoreCase("Boutique Centre-ville")).thenReturn(false);
        when(shopMapper.toEntity(any(ShopRequest.class))).thenReturn(entity);
        when(shopRepository.save(any(Shop.class))).thenAnswer(i -> i.getArgument(0));

        shopService.create(request());

        var captor = ArgumentCaptor.forClass(Shop.class);
        verify(shopRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganization().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("signale une boutique introuvable")
    void failsWhenMissing() {
        when(shopRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shopService.getEntity(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Boutique");
    }

    @Test
    @DisplayName("autorise le nom courant lors d'une modification")
    void allowsSameNameOnUpdate() {
        Shop existing = Shop.builder().id(1L).name("Boutique Centre-ville").active(true).build();
        when(shopRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(shopRepository.existsByNameIgnoreCaseAndIdNot("Boutique Centre-ville", 1L)).thenReturn(false);
        when(shopRepository.save(any(Shop.class))).thenAnswer(i -> i.getArgument(0));

        shopService.update(1L, request());

        verify(shopRepository).save(existing);
        verify(shopMapper).updateEntity(request(), existing);
    }

    @Test
    @DisplayName("refuse la creation d'une seconde boutique active quand le plan est mono-boutique")
    void rejectsSecondActiveShopWhenPlanIsSingleShopOnCreate() {
        when(properties.getPlan()).thenReturn(singleShopPlan());
        when(shopRepository.countByActiveTrue()).thenReturn(1L);

        assertThatThrownBy(() -> shopService.create(request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("formule");

        verify(shopRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuse la reactivation d'une seconde boutique quand le plan est mono-boutique")
    void rejectsReactivationOfSecondShopWhenPlanIsSingleShop() {
        Shop existing = Shop.builder().id(1L).name("Boutique Centre-ville").active(false).build();
        when(properties.getPlan()).thenReturn(singleShopPlan());
        when(shopRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(shopRepository.existsByActiveTrueAndIdNot(1L)).thenReturn(true);

        assertThatThrownBy(() -> shopService.update(1L, request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("formule");

        verify(shopRepository, never()).save(any());
    }

    @Test
    @DisplayName("autorise une seconde boutique active quand le plan est multi-boutiques")
    void allowsSecondActiveShopWhenPlanIsMultiShop() {
        Shop entity = Shop.builder().name("Boutique Centre-ville").address("Dakar")
                .phone("770000002").active(true).build();
        when(shopRepository.countByActiveTrue()).thenReturn(1L);
        when(shopMapper.toEntity(any(ShopRequest.class))).thenReturn(entity);
        when(shopRepository.save(any(Shop.class))).thenAnswer(i -> i.getArgument(0));

        shopService.create(request());

        verify(shopRepository).save(entity);
    }

    @Test
    @DisplayName("n'empeche jamais la mise a jour de l'unique boutique deja active, meme en plan mono-boutique")
    void allowsUpdateOfSingleAlreadyActiveShopEvenWithSingleShopPlan() {
        Shop existing = Shop.builder().id(1L).name("Boutique Centre-ville").active(true).build();
        when(properties.getPlan()).thenReturn(singleShopPlan());
        when(shopRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(shopRepository.existsByActiveTrueAndIdNot(1L)).thenReturn(false);
        when(shopRepository.save(any(Shop.class))).thenAnswer(i -> i.getArgument(0));

        shopService.update(1L, request());

        verify(shopRepository).save(existing);
    }

    @Test
    @DisplayName("n'empeche jamais la simple mise a jour d'une boutique deja active sur une instance qui en a deja plusieurs")
    void allowsUpdateOfAlreadyActiveShopAmongMultipleEvenWithSingleShopPlan() {
        Shop existing = Shop.builder().id(1L).name("Boutique Centre-ville").active(true).build();
        when(properties.getPlan()).thenReturn(singleShopPlan());
        when(shopRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(shopRepository.existsByActiveTrueAndIdNot(1L)).thenReturn(true);
        when(shopRepository.save(any(Shop.class))).thenAnswer(i -> i.getArgument(0));

        shopService.update(1L, request());

        verify(shopRepository).save(existing);
    }

    private static AppProperties.Plan singleShopPlan() {
        AppProperties.Plan plan = new AppProperties.Plan();
        plan.setMultiShop(false);
        return plan;
    }
}
