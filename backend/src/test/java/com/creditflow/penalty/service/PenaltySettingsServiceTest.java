package com.creditflow.penalty.service;

import com.creditflow.audit.service.AuditLogService;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.penalty.domain.PenaltyPeriod;
import com.creditflow.penalty.domain.PenaltyRateType;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.dto.PenaltySettingsRequest;
import com.creditflow.penalty.dto.PenaltySettingsResponse;
import com.creditflow.penalty.repository.PenaltySettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PenaltySettingsServiceTest {

    @Mock
    private PenaltySettingsRepository penaltySettingsRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PenaltySettingsService penaltySettingsService;

    private PenaltySettings settings;

    @BeforeEach
    void setUp() {
        settings = PenaltySettings.builder()
                .id(1L).enabled(false).rateType(PenaltyRateType.FIXED)
                .rate(BigDecimal.ZERO).period(PenaltyPeriod.DAY).capPercent(null)
                .build();
        when(penaltySettingsRepository.findById(1L)).thenReturn(Optional.of(settings));
        when(penaltySettingsRepository.save(any(PenaltySettings.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("retourne l'entite seedee")
    void currentReturnsSeededEntity() {
        assertThat(penaltySettingsService.current()).isSameAs(settings);
    }

    @Test
    @DisplayName("refuse d'activer la penalite avec un taux nul")
    void rejectsEnablingWithZeroRate() {
        PenaltySettingsRequest request = new PenaltySettingsRequest(
                true, PenaltyRateType.FIXED, BigDecimal.ZERO, PenaltyPeriod.DAY, null);

        assertThatThrownBy(() -> penaltySettingsService.update(request))
                .isInstanceOf(BusinessRuleException.class);
        verify(penaltySettingsRepository, never()).save(any(PenaltySettings.class));
    }

    @Test
    @DisplayName("met a jour et journalise les nouveaux reglages")
    void updatesAndRecordsAudit() {
        PenaltySettingsRequest request = new PenaltySettingsRequest(
                true, PenaltyRateType.FIXED, new BigDecimal("500"), PenaltyPeriod.DAY, null);

        PenaltySettingsResponse response = penaltySettingsService.update(request);

        assertThat(response.enabled()).isTrue();
        assertThat(response.rate()).isEqualByComparingTo("500");
        assertThat(settings.isEnabled()).isTrue();
        assertThat(settings.getRate()).isEqualByComparingTo("500");
        verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq("PENALTY_SETTINGS"),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("Parametres de penalite"),
                org.mockito.ArgumentMatchers.eq("UPDATE"),
                any(String.class));
    }
}
