package com.creditflow.common.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction du stockage de documents (photos clients, pieces jointes de vente).
 * Un seul fournisseur est actif a la fois, selectionne par {@code app.storage.provider}
 * ({@link LocalDiskStorage} par defaut, {@link S3DocumentStorage} si {@code s3}).
 */
public interface DocumentStorage {

    /** Enregistre le fichier valide et retourne la cle permettant de le retrouver. */
    String store(MultipartFile file, String folder);

    void delete(String key);

    DocumentAccess resolve(String key);
}
