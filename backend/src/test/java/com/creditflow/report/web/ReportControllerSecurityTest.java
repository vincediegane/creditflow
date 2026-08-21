package com.creditflow.report.web;

import com.creditflow.config.AbstractWebMvcSecurityTest;
import com.creditflow.report.dto.ReportData;
import com.creditflow.report.dto.ReportType;
import com.creditflow.report.export.ExcelReportExporter;
import com.creditflow.report.export.PdfReportExporter;
import com.creditflow.report.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
@Import({ ExcelReportExporter.class, PdfReportExporter.class })
class ReportControllerSecurityTest extends AbstractWebMvcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportService reportService;

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotReadSellerPerformance() throws Exception {
        mockMvc.perform(get("/api/reports/SELLER_PERFORMANCE"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCannotExportSellerPerformance() throws Exception {
        mockMvc.perform(get("/api/reports/SELLER_PERFORMANCE/export").param("format", "pdf"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReadSellerPerformance() throws Exception {
        when(reportService.build(any(), any(), any(), any(), any(), any())).thenReturn(sellerPerformanceReport());

        mockMvc.perform(get("/api/reports/SELLER_PERFORMANCE"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void sellerCanReadDefaultRate() throws Exception {
        when(reportService.build(any(), any(), any(), any(), any(), any())).thenReturn(defaultRateReport());

        mockMvc.perform(get("/api/reports/DEFAULT_RATE"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanExportSellerPerformanceInPdfAndExcel() throws Exception {
        when(reportService.build(any(), any(), any(), any(), any(), any())).thenReturn(sellerPerformanceReport());

        mockMvc.perform(get("/api/reports/SELLER_PERFORMANCE/export").param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType("application/pdf"));

        mockMvc.perform(get("/api/reports/SELLER_PERFORMANCE/export").param("format", "excel"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    private ReportData sellerPerformanceReport() {
        return new ReportData(
                ReportType.SELLER_PERFORMANCE,
                "Performance vendeur",
                "Au 01/01/2026",
                List.of(ReportData.Column.text("Vendeur")),
                List.of(),
                List.of(),
                LocalDateTime.now());
    }

    private ReportData defaultRateReport() {
        return new ReportData(
                ReportType.DEFAULT_RATE,
                "Taux de defaut par profession",
                "Au 01/01/2026",
                List.of(ReportData.Column.text("Profession")),
                List.of(),
                List.of(),
                LocalDateTime.now());
    }
}
