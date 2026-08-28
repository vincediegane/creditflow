package com.creditflow.common.storage.web;

import com.creditflow.common.storage.DocumentAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAccessResponsesTest {

    @Test
    @DisplayName("Inline devient une reponse 200 avec le Content-Type et le corps attendus")
    void inlineBecomesOkResponse() {
        byte[] content = {1, 2, 3};

        ResponseEntity<byte[]> response = DocumentAccessResponses.toResponseEntity(
                new DocumentAccess.Inline(content, "image/png"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(content);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Redirect devient une reponse 302 avec l'en-tete Location")
    void redirectBecomesRedirectResponse() {
        ResponseEntity<byte[]> response = DocumentAccessResponses.toResponseEntity(
                new DocumentAccess.Redirect("https://s3.example.com/bucket/key?sig=xyz"));

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://s3.example.com/bucket/key?sig=xyz");
    }
}
