package com.creditflow.common.storage;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Stockage S3 (ou compatible, ex. MinIO via {@code endpoint}) des fichiers uploades.
 * Actif uniquement si {@code app.storage.provider=s3} (variables requises verifiees au
 * demarrage par {@code StorageConfigValidator}).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
public class S3DocumentStorage implements DocumentStorage {

    private final DocumentValidation documentValidation;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;
    private final int signedUrlTtlSeconds;

    @Autowired
    public S3DocumentStorage(AppProperties properties, DocumentValidation documentValidation) {
        this.documentValidation = documentValidation;
        AppProperties.S3 s3Properties = properties.getStorage().getS3();
        this.bucket = s3Properties.getBucket();
        this.signedUrlTtlSeconds = s3Properties.getSignedUrlTtlSeconds();

        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                s3Properties.getAccessKey(), s3Properties.getSecretKey());
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);
        Region region = Region.of(s3Properties.getRegion());

        var s3ClientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(s3Properties.isPathStyleAccess());
        var s3PresignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider);
        if (StringUtils.hasText(s3Properties.getEndpoint())) {
            URI endpoint = URI.create(s3Properties.getEndpoint());
            s3ClientBuilder.endpointOverride(endpoint);
            s3PresignerBuilder.endpointOverride(endpoint);
        }

        this.s3Client = s3ClientBuilder.build();
        this.s3Presigner = s3PresignerBuilder.build();
    }

    S3DocumentStorage(DocumentValidation documentValidation, S3Client s3Client, S3Presigner s3Presigner,
                       String bucket, int signedUrlTtlSeconds) {
        this.documentValidation = documentValidation;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
        this.signedUrlTtlSeconds = signedUrlTtlSeconds;
    }

    @Override
    public String store(MultipartFile file, String folder) {
        byte[] content = documentValidation.validate(file);
        String extension = documentValidation.extensionOf(file.getOriginalFilename());
        String key = "%s/%s.%s".formatted(folder, UUID.randomUUID(), extension);

        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(file.getContentType()).build(),
                    RequestBody.fromBytes(content));
            return key;
        } catch (RuntimeException ex) {
            log.error("Echec d'enregistrement du fichier sur S3", ex);
            throw new BusinessRuleException("Impossible d'enregistrer le fichier");
        }
    }

    @Override
    public void delete(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public DocumentAccess resolve(String key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(signedUrlTtlSeconds))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return new DocumentAccess.Redirect(presigned.url().toString());
    }
}
