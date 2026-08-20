package com.creditflow.common.storage;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] WEBP_BYTES = {
            0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50
    };

    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        AppProperties properties = new AppProperties();
        properties.getStorage().setUploadDir(tempDir.toString());
        properties.getStorage().setPublicPath("/uploads");
        fileStorageService = new FileStorageService(properties);
    }

    @Test
    @DisplayName("accepte un PNG valide")
    void acceptsValidPng() {
        String url = fileStorageService.store(
                new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES), "sales/1");

        assertThat(url).startsWith("/uploads/sales/1/").endsWith(".png");
    }

    @Test
    @DisplayName("accepte un JPEG valide")
    void acceptsValidJpeg() {
        String url = fileStorageService.store(
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES), "sales/1");

        assertThat(url).endsWith(".jpg");
    }

    @Test
    @DisplayName("accepte un WEBP valide")
    void acceptsValidWebp() {
        String url = fileStorageService.store(
                new MockMultipartFile("file", "photo.webp", "image/webp", WEBP_BYTES), "sales/1");

        assertThat(url).endsWith(".webp");
    }

    @Test
    @DisplayName("rejette un fichier texte renomme en .png")
    void rejectsTextFileRenamedAsPng() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", "ceci n'est pas une image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fileStorageService.store(file, "sales/1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ne correspond pas");
    }

    @Test
    @DisplayName("rejette un .jpg dont le contenu est en realite un PNG")
    void rejectsMismatchedJpegContent() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg", PNG_BYTES);

        assertThatThrownBy(() -> fileStorageService.store(file, "sales/1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ne correspond pas");
    }

    @Test
    @DisplayName("rejette une extension non supportee")
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fileStorageService.store(file, "sales/1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Format d'image non supporte");
    }
}
