package com.creditflow.common.storage;

import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            byte[] content = file.getBytes();
            if (!matchesExtension(content, extension)) {
                throw new BusinessRuleException(
                        "Le contenu du fichier ne correspond pas a son extension declaree");
            }

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

    private boolean matchesExtension(byte[] content, String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> matches(content, 0, 0xFF, 0xD8, 0xFF);
            case "png" -> matches(content, 0, 0x89, 0x50, 0x4E, 0x47);
            case "webp" -> matches(content, 0, 0x52, 0x49, 0x46, 0x46)
                    && matches(content, 8, 0x57, 0x45, 0x42, 0x50);
            default -> false;
        };
    }

    private boolean matches(byte[] content, int offset, int... expected) {
        if (content.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((content[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
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
