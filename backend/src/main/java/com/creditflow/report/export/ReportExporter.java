package com.creditflow.report.export;

import com.creditflow.report.dto.ReportData;

/**
 * Un exportateur par format. Ajouter un format revient a ajouter une
 * implementation : aucun autre code n'est modifie (principe ouvert/ferme).
 */
public interface ReportExporter {

    /** Identifiant utilise dans l'URL : "pdf", "excel". */
    String format();

    String contentType();

    String fileExtension();

    byte[] export(ReportData data);
}
