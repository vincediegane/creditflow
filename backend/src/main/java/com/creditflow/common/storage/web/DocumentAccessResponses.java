package com.creditflow.common.storage.web;

import com.creditflow.common.storage.DocumentAccess;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Convertit un {@link DocumentAccess} en reponse HTTP : contenu affiche inline (local) ou
 * redirection vers une URL signee (S3). Seul endroit qui « sait » qu'il y a deux formes de
 * reponse possibles, reutilise par chaque controleur exposant un document.
 */
public final class DocumentAccessResponses {

    private DocumentAccessResponses() {
    }

    public static ResponseEntity<byte[]> toResponseEntity(DocumentAccess access) {
        return switch (access) {
            case DocumentAccess.Inline inline -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(inline.contentType()))
                    .body(inline.content());
            case DocumentAccess.Redirect redirect -> ResponseEntity.status(302)
                    .header(HttpHeaders.LOCATION, redirect.url())
                    .build();
        };
    }
}
