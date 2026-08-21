package com.creditflow.search.service;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.customer.dto.CustomerResponse;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.product.dto.ProductResponse;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.search.dto.GlobalSearchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlobalSearchServiceTest {

    @Mock
    private CustomerService customerService;

    @Mock
    private ProductService productService;

    @Mock
    private CreditSaleService creditSaleService;

    @InjectMocks
    private GlobalSearchService globalSearchService;

    @Test
    @DisplayName("delegue a customerService.quickSearch, productService.quickSearch et creditSaleService.search "
            + "(deja filtres par boutique, aucune logique propre a ce service)")
    void delegatesToShopFilteredServices() {
        CustomerResponse customer = new CustomerResponse(1L, "Amadou", "Diallo", "Amadou Diallo", "770000001",
                null, null, null, null, null, true, null, null, null, false, null, null);
        when(customerService.quickSearch("Amadou", 10)).thenReturn(List.of(customer));
        when(productService.quickSearch(eq("Amadou"), anyInt())).thenReturn(List.of());
        when(creditSaleService.search(eq("Amadou"), any(), any(), any(Pageable.class)))
                .thenReturn(PageResponse.ofList(List.<SaleResponse>of()));

        GlobalSearchResponse response = globalSearchService.search("Amadou", 10);

        assertThat(response.customers()).containsExactly(customer);
        verify(customerService).quickSearch("Amadou", 10);
        verify(productService).quickSearch("Amadou", 10);
        verify(creditSaleService).search(eq("Amadou"), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("retourne un resultat vide et ne sollicite aucun service pour une requete vide")
    void returnsEmptyResultForBlankQuery() {
        GlobalSearchResponse response = globalSearchService.search("  ", 10);

        assertThat(response.totalResults()).isZero();
        verifyNoInteractions(customerService, productService, creditSaleService);
    }
}
