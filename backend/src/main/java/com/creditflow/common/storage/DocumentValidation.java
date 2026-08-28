package com.creditflow.common.storage;

import com.creditflow.common.exception.BusinessRuleException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Validation partagee des fichiers uploades (extensions autorisees, magic-bytes),
 * reutilisee par chaque implementation de {@link DocumentStorage}.
 */
@Component
public class DocumentValidation {

    private static final List<String> ALLOWED_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    public byte[] validate(MultipartFile file) {
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
            return content;
        } catch (IOException ex) {
            throw new BusinessRuleException("Impossible de lire le fichier");
        }
    }

    public String extensionOf(String filename) {
        String ext = StringUtils.getFilenameExtension(filename);
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
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
}
