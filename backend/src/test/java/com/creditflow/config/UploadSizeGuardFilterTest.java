package com.creditflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadSizeGuardFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    private UploadSizeGuardFilter filter;

    @BeforeEach
    void setUp() {
        MultipartProperties multipartProperties = new MultipartProperties();
        multipartProperties.setMaxRequestSize(DataSize.ofMegabytes(12));
        filter = new UploadSizeGuardFilter(multipartProperties, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    @DisplayName("rejette en 413 un multipart dont la taille depasse la limite")
    void rejectsOversizedMultipart() throws Exception {
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=----abc");
        when(request.getContentLengthLong()).thenReturn(15L * 1024 * 1024);
        when(request.getRequestURI()).thenReturn("/api/customers/1/photo");
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        verify(chain, never()).doFilter(request, response);
        assertThat(writer.toString()).contains("Fichier trop volumineux");
    }

    @Test
    @DisplayName("laisse passer un multipart sans Content-Length connu")
    void passesThroughWhenContentLengthUnknown() throws Exception {
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=----abc");
        when(request.getContentLengthLong()).thenReturn(-1L);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("ignore les requetes non multipart sans inspecter la taille")
    void passesThroughNonMultipartRequests() throws Exception {
        when(request.getContentType()).thenReturn("application/json");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(response);
    }
}
