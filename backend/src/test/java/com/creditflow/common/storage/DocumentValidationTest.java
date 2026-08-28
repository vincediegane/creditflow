package com.creditflow.common.storage;

import com.creditflow.common.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentValidationTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] WEBP_BYTES = {
            0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50
    };

    private final DocumentValidation documentValidation = new DocumentValidation();

    @Test
    @DisplayName("accepte un PNG valide")
    void acceptsValidPng() {
        byte[] content = documentValidation.validate(
                new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES));

        assertThat(content).isEqualTo(PNG_BYTES);
    }

    @Test
    @DisplayName("accepte un JPEG valide")
    void acceptsValidJpeg() {
        byte[] content = documentValidation.validate(
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES));

        assertThat(content).isEqualTo(JPEG_BYTES);
    }

    @Test
    @DisplayName("accepte un WEBP valide")
    void acceptsValidWebp() {
        byte[] content = documentValidation.validate(
                new MockMultipartFile("file", "photo.webp", "image/webp", WEBP_BYTES));

        assertThat(content).isEqualTo(WEBP_BYTES);
    }

    @Test
    @DisplayName("rejette un fichier texte renomme en .png")
    void rejectsTextFileRenamedAsPng() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", "ceci n'est pas une image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentValidation.validate(file))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ne correspond pas");
    }

    @Test
    @DisplayName("rejette un .jpg dont le contenu est en realite un PNG")
    void rejectsMismatchedJpegContent() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg", PNG_BYTES);

        assertThatThrownBy(() -> documentValidation.validate(file))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ne correspond pas");
    }

    @Test
    @DisplayName("rejette une extension non supportee")
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentValidation.validate(file))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Format d'image non supporte");
    }
}
