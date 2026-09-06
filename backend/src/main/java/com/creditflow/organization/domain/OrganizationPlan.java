package com.creditflow.organization.domain;

import com.creditflow.common.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Formule commerciale d'une organisation, resolue en base plutot que par
 * configuration d'instance. Absence de ligne pour une organisation = fallback
 * integral sur {@link com.creditflow.config.AppProperties.Plan}, voir
 * {@link com.creditflow.organization.service.OrganizationPlanResolver}.
 *
 * Cle primaire = id de l'organisation (pas de generation autonome) : une
 * organisation a au plus une ligne de plan.
 */
@Entity
@Table(name = "organization_plan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationPlan extends Auditable {

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "multi_shop", nullable = false)
    private boolean multiShop;
}
