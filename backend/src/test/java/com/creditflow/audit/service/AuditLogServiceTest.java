package com.creditflow.audit.service;

import com.creditflow.audit.domain.AuditLog;
import com.creditflow.audit.dto.AuditLogResponse;
import com.creditflow.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    private MockedStatic<SecurityContextHolder> securityContextHolder;

    @AfterEach
    void tearDown() {
        if (securityContextHolder != null) {
            securityContextHolder.close();
        }
    }

    @Test
    @DisplayName("enregistre une entree avec l'auteur courant")
    void recordsEntryWithCurrentActor() {
        mockAuthenticatedUser("admin1");
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(i -> i.getArgument(0));

        auditLogService.record("CUSTOMER", 1L, "Amadou Diallo", "DELETE", null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEntityType()).isEqualTo("CUSTOMER");
        assertThat(saved.getEntityId()).isEqualTo(1L);
        assertThat(saved.getEntityLabel()).isEqualTo("Amadou Diallo");
        assertThat(saved.getAction()).isEqualTo("DELETE");
        assertThat(saved.getActor()).isEqualTo("admin1");
    }

    @Test
    @DisplayName("enregistre un acteur null sans authentification")
    void recordsNullActorWhenUnauthenticated() {
        SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.getAuthentication()).thenReturn(null);
        securityContextHolder = Mockito.mockStatic(SecurityContextHolder.class);
        securityContextHolder.when(SecurityContextHolder::getContext).thenReturn(context);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(i -> i.getArgument(0));

        auditLogService.record("PRODUCT", 2L, "iPhone 13", "PRICE_UPDATE", "Prix credit: 1 -> 2");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isNull();
    }

    @Test
    @DisplayName("liste les entrees triees et mappees en reponses")
    void listsMappedEntries() {
        AuditLog entry = AuditLog.builder()
                .id(1L).entityType("CREDIT_SALE").entityId(5L).entityLabel("VC-2026-00001")
                .action("CANCEL").details(null).actor("admin1").createdAt(LocalDateTime.now())
                .build();
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("CREDIT_SALE", 5L))
                .thenReturn(List.of(entry));

        List<AuditLogResponse> responses = auditLogService.list("CREDIT_SALE", 5L);

        assertThat(responses).hasSize(1);
        AuditLogResponse response = responses.get(0);
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.entityType()).isEqualTo("CREDIT_SALE");
        assertThat(response.entityId()).isEqualTo(5L);
        assertThat(response.action()).isEqualTo("CANCEL");
        assertThat(response.actor()).isEqualTo("admin1");
    }

    private void mockAuthenticatedUser(String username) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(username, null, List.of());
        SecurityContext context = Mockito.mock(SecurityContext.class);
        Mockito.when(context.getAuthentication()).thenReturn(authentication);
        securityContextHolder = Mockito.mockStatic(SecurityContextHolder.class);
        securityContextHolder.when(SecurityContextHolder::getContext).thenReturn(context);
    }
}
