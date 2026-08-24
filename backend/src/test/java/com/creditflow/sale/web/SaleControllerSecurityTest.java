package com.creditflow.sale.web;

import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.payment.service.PaymentService;
import com.creditflow.sale.domain.SaleAttachmentType;
import com.creditflow.sale.domain.SaleStatus;
import com.creditflow.sale.dto.CreateSaleRequest;
import com.creditflow.sale.dto.SaleAttachmentResponse;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.service.CreditSaleService;
import com.creditflow.sale.service.InstallmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SaleController.class)
class SaleControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CreditSaleService creditSaleService;

    @MockBean
    private InstallmentService installmentService;

    @MockBean
    private PaymentService paymentService;

    private CreateSaleRequest createRequest() {
        return new CreateSaleRequest(1L, 1L, BigDecimal.valueOf(100000), BigDecimal.valueOf(20000),
                null, null, 6, LocalDate.now(), null, null, null, null, null);
    }

    private SaleResponse response() {
        return new SaleResponse(1L, "V-0001", 1L, "Amadou Diallo", "770000001", 1L, "Telephone",
                BigDecimal.valueOf(100000), null, BigDecimal.ZERO, BigDecimal.valueOf(20000), BigDecimal.valueOf(80000), 6,
                BigDecimal.valueOf(13334), BigDecimal.ZERO, BigDecimal.valueOf(80000), LocalDate.now(),
                LocalDate.now().plusMonths(6), SaleStatus.ACTIVE, false, 0, 0, null, null, 0, null, null,
                null, null, BigDecimal.ZERO, null, null, null, null);
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotCancelSale() throws Exception {
        mockMvc.perform(post("/api/sales/1/cancel")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotDeleteSale() throws Exception {
        mockMvc.perform(delete("/api/sales/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanCreateSale() throws Exception {
        when(creditSaleService.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/sales")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCancelSale() throws Exception {
        when(creditSaleService.cancel(anyLong())).thenReturn(response());

        mockMvc.perform(post("/api/sales/1/cancel")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteSale() throws Exception {
        mockMvc.perform(delete("/api/sales/1")).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanUploadAttachment() throws Exception {
        when(creditSaleService.uploadAttachment(anyLong(), any(), any())).thenReturn(
                new SaleAttachmentResponse(1L, 1L, SaleAttachmentType.ID_DOCUMENT, "/uploads/sales/1/a.png",
                        "cni.png", "image/png", LocalDateTime.now(), "vendeur"));
        MockMultipartFile file = new MockMultipartFile("file", "cni.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/sales/1/attachments")
                        .file(file)
                        .param("type", "ID_DOCUMENT"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanDeleteAttachment() throws Exception {
        mockMvc.perform(delete("/api/sales/1/attachments/2")).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("telecharge la facture d'un contrat accessible")
    void sellerCanDownloadInvoice() throws Exception {
        when(creditSaleService.invoice(1L)).thenReturn(
                new CreditSaleService.Invoice("facture-fac-2026-00001-20260824.pdf", new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/sales/1/invoice"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("refuse la facture d'un contrat d'une autre boutique")
    void sellerCannotDownloadInvoiceOfSaleFromAnotherShop() throws Exception {
        when(creditSaleService.invoice(2L)).thenThrow(new ResourceNotFoundException("Ressource introuvable"));

        mockMvc.perform(get("/api/sales/2/invoice")).andExpect(status().isNotFound());
    }
}
