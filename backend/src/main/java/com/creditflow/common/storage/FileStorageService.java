package com.creditflow.common.storage;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stockage local des fichiers (photos clients). L'implementation est isolee
 * derriere ce service pour pouvoir passer a S3 / MinIO plus tard.
 */
@Slf4j
@Service
public class FileStorageService {

    private static final List<String> ALLOWED_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    private final Path root;
    private final String publicPath;

    public FileStorageService(AppProperties properties) {
        this.root = Paths.get(properties.getStorage().getUploadDir()).toAbsolutePath().normalize();
        this.publicPath = properties.getStorage().getPublicPath();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("Impossible de creer le repertoire de stockage: " + root, ex);
        }
    }

    /** Enregistre le fichier et retourne l'URL publique relative. */
    public String store(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Le fichier est vide");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessRuleException("Format d'image non supporte (jpg, jpeg, png, webp)");
        }

        try {
            Path directory = root.resolve(folder).normalize();
            if (!directory.startsWith(root)) {
                throw new BusinessRuleException("Chemin de stockage invalide");
            }
            Files.createDirectories(directory);

            String filename = UUID.randomUUID() + "." + extension;
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, directory.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            }
            return "%s/%s/%s".formatted(publicPath, folder, filename);
        } catch (IOException ex) {
            log.error("Echec d'enregistrement du fichier", ex);
            throw new BusinessRuleException("Impossible d'enregistrer le fichier");
        }
    }

    public void deleteByPublicUrl(String publicUrl) {
        if (!StringUtils.hasText(publicUrl) || !publicUrl.startsWith(publicPath)) {
            return;
        }
        try {
            Path target = root.resolve(publicUrl.substring(publicPath.length() + 1)).normalize();
            if (target.startsWith(root)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ex) {
            log.warn("Suppression du fichier impossible: {}", publicUrl);
        }
    }

    private String extensionOf(String filename) {
        String ext = StringUtils.getFilenameExtension(filename);
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}
