package com.creditflow.organization.repository;

import com.creditflow.organization.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findFirstByOrderByIdAsc();
}
