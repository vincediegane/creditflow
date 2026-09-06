package com.creditflow.organization.repository;

import com.creditflow.organization.domain.OrganizationPlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationPlanRepository extends JpaRepository<OrganizationPlan, Long> {
}
