package com.creditflow.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("renvoie 413 avec un message clair quand le fichier depasse la taille autorisee")
    void handlesMaxUploadSizeExceeded() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/customers/1/photo");

        ResponseEntity<ApiError> response = handler.handleMaxUploadSize(
                new MaxUploadSizeExceededException(10 * 1024 * 1024), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().message()).isEqualTo("Fichier trop volumineux, taille maximale autorisee : 10 Mo");
        assertThat(response.getBody().path()).isEqualTo("/api/customers/1/photo");
    }
}
