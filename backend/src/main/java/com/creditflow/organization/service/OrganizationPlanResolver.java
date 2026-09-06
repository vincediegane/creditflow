package com.creditflow.organization.service;

import com.creditflow.config.AppProperties;
import com.creditflow.organization.domain.OrganizationPlan;
import com.creditflow.organization.repository.OrganizationPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resout la formule effective d'une organisation : table organization_plan si
 * une ligne existe, sinon fallback integral sur la configuration d'instance
 * (AppProperties.Plan), pour compatibilite mono-tenant inchangee.
 *
 * Ne couvre que multi_shop. whatsappAuto reste un attribut d'instance, lu
 * directement depuis AppProperties.Plan partout ailleurs (voir #42, Decisions
 * cles) : ne pas ajouter de methode whatsappAuto ici sans lever d'abord la
 * contrainte d'architecture du canal de notification (NotificationChannel,
 * bean unique par JVM).
 */
@Service
@RequiredArgsConstructor
public class OrganizationPlanResolver {

    private final OrganizationPlanRepository organizationPlanRepository;
    private final AppProperties properties;

    @Transactional(readOnly = true)
    public boolean multiShopEnabled(Long organizationId) {
        return organizationPlanRepository.findById(organizationId)
                .map(OrganizationPlan::isMultiShop)
                .orElseGet(() -> properties.getPlan().isMultiShop());
    }
}
