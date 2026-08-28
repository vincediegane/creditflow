package com.creditflow.common.storage;

/**
 * Resultat de {@link DocumentStorage#resolve(String)} : soit le contenu du document
 * (fournisseur local), soit une redirection vers une URL signee (fournisseur S3).
 */
public sealed interface DocumentAccess permits DocumentAccess.Inline, DocumentAccess.Redirect {

    record Inline(byte[] content, String contentType) implements DocumentAccess {
    }

    record Redirect(String url) implements DocumentAccess {
    }
}
