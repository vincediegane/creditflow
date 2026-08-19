package com.creditflow.penalty.repository;

import com.creditflow.penalty.domain.PenaltySettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PenaltySettingsRepository extends JpaRepository<PenaltySettings, Long> {
}
