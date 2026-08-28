package com.creditflow.common.storage;

import com.creditflow.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3DocumentStorageTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private S3DocumentStorage s3DocumentStorage;

    @BeforeEach
    void setUp() {
        s3DocumentStorage = new S3DocumentStorage(
                new DocumentValidation(), s3Client, s3Presigner, "creditflow-bucket", 300);
    }

    @Test
    @DisplayName("store televerse le contenu valide et retourne une cle sans /uploads")
    void storeUploadsValidatedContentAndReturnsKeyWithoutUploadsPrefix() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        String key = s3DocumentStorage.store(file, "customers");

        assertThat(key).startsWith("customers/").endsWith(".png").doesNotContain("/uploads");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("creditflow-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo(key);
    }

    @Test
    @DisplayName("store rejette un fichier invalide avant tout appel S3")
    void storeRejectsInvalidFileBeforeAnyS3Call() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", "ceci n'est pas une image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> s3DocumentStorage.store(file, "customers"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ne correspond pas");

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("store rejette une extension non supportee avant tout appel S3")
    void storeRejectsUnsupportedExtensionBeforeAnyS3Call() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> s3DocumentStorage.store(file, "customers"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Format d'image non supporte");

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("delete appelle deleteObject avec la bonne cle")
    void deleteCallsDeleteObjectWithKey() {
        s3DocumentStorage.delete("customers/abc.png");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("creditflow-bucket");
        assertThat(captor.getValue().key()).isEqualTo("customers/abc.png");
    }

    @Test
    @DisplayName("delete ne fait rien sur une cle vide")
    void deleteDoesNothingOnBlankKey() {
        s3DocumentStorage.delete("");
        s3DocumentStorage.delete(null);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("resolve retourne une redirection vers l'URL signee")
    void resolveReturnsRedirectFromPresigner() {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(toUri("https://s3.example.com/creditflow-bucket/customers/abc.png?sig=xyz"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        DocumentAccess access = s3DocumentStorage.resolve("customers/abc.png");

        assertThat(access).isInstanceOf(DocumentAccess.Redirect.class);
        assertThat(((DocumentAccess.Redirect) access).url())
                .isEqualTo("https://s3.example.com/creditflow-bucket/customers/abc.png?sig=xyz");
    }

    private static java.net.URL toUri(String value) {
        try {
            return URI.create(value).toURL();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
