package com.creditflow.common.storage;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Stockage local des fichiers (photos clients, pieces jointes de vente). Comportement
 * inchange par rapport a l'ancien {@code FileStorageService}, seul le point d'entree change
 * (interface {@link DocumentStorage}).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorage implements DocumentStorage {

    private final DocumentValidation documentValidation;
    private final Path root;
    private final String publicPath;

    public LocalDiskStorage(AppProperties properties, DocumentValidation documentValidation) {
        this.documentValidation = documentValidation;
        this.root = Paths.get(properties.getStorage().getUploadDir()).toAbsolutePath().normalize();
        this.publicPath = properties.getStorage().getPublicPath();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("Impossible de creer le repertoire de stockage: " + root, ex);
        }
    }

    @Override
    public String store(MultipartFile file, String folder) {
        byte[] content = documentValidation.validate(file);
        String extension = documentValidation.extensionOf(file.getOriginalFilename());

        try {
            Path directory = root.resolve(folder).normalize();
            if (!directory.startsWith(root)) {
                throw new BusinessRuleException("Chemin de stockage invalide");
            }
            Files.createDirectories(directory);

            String filename = UUID.randomUUID() + "." + extension;
            Files.write(directory.resolve(filename), content);
            return "%s/%s/%s".formatted(publicPath, folder, filename);
        } catch (IOException ex) {
            log.error("Echec d'enregistrement du fichier", ex);
            throw new BusinessRuleException("Impossible d'enregistrer le fichier");
        }
    }

    @Override
    public void delete(String key) {
        if (!StringUtils.hasText(key) || !key.startsWith(publicPath)) {
            return;
        }
        try {
            Path target = root.resolve(key.substring(publicPath.length() + 1)).normalize();
            if (target.startsWith(root)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ex) {
            log.warn("Suppression du fichier impossible: {}", key);
        }
    }

    @Override
    public DocumentAccess resolve(String key) {
        if (!StringUtils.hasText(key) || !key.startsWith(publicPath)) {
            throw new ResourceNotFoundException("Document introuvable");
        }
        Path target = root.resolve(key.substring(publicPath.length() + 1)).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new ResourceNotFoundException("Document introuvable");
        }
        try {
            byte[] content = Files.readAllBytes(target);
            return new DocumentAccess.Inline(content, contentTypeOf(documentValidation.extensionOf(key)));
        } catch (IOException ex) {
            throw new ResourceNotFoundException("Document introuvable");
        }
    }

    private String contentTypeOf(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
