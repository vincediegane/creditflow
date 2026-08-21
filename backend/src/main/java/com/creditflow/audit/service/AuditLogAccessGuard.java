package com.creditflow.audit.service;

import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.customer.service.CustomerService;
import com.creditflow.product.service.ProductService;
import com.creditflow.sale.service.CreditSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Garde d'acces en lecture au journal d'audit : les entrees exposent des donnees
 * nominatives (noms de clients relances, prix du catalogue), la lecture doit donc
 * suivre le cloisonnement par boutique de l'entite ciblee. La resolution delegue aux
 * getEntity(...) des modules, deja instrumentes avec CurrentShopContext.assertAccessible(...).
 *
 * <p>Cette garde ne peut pas vivre dans AuditLogService : les services metier dependent
 * deja de AuditLogService pour ecrire dans le journal, l'injection inverse creerait un
 * cycle de dependances Spring.
 */
@Component
@RequiredArgsConstructor
public class AuditLogAccessGuard {

    private final CustomerService customerService;
    private final ProductService productService;
    private final CreditSaleService creditSaleService;

    public void assertReadable(String entityType, Long entityId) {
        switch (entityType) {
            case "CUSTOMER" -> customerService.getEntity(entityId);
            case "PRODUCT" -> productService.getEntity(entityId);
            case "CREDIT_SALE" -> creditSaleService.getEntity(entityId);
            // Tout type non rattachable a une boutique (PENALTY_SETTINGS, entite globale,
            // ou type inconnu envoye par un client) est refuse : sans boutique de reference,
            // aucune garde ne peut etre appliquee. Meme message que assertAccessible pour ne
            // pas reveler l'existence de l'entite.
            default -> throw new ResourceNotFoundException("Ressource introuvable");
        }
    }
}
