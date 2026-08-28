package com.creditflow.auth.bootstrap;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.config.AppProperties;
import com.creditflow.organization.domain.Organization;
import com.creditflow.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminInitializerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AppProperties properties;

    private AdminInitializer adminInitializer;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        adminInitializer = new AdminInitializer(userRepository, passwordEncoder, properties, organizationRepository);
    }

    @Test
    @DisplayName("cree l'administrateur avec l'organisation par defaut sur une base fraiche")
    void createsAdminWithDefaultOrganization() throws Exception {
        when(userRepository.existsByUsernameIgnoreCase("admin")).thenReturn(false);
        Organization organization = Organization.builder().id(1L).name("Organisation par defaut").build();
        when(organizationRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(organization));

        ApplicationRunner runner = adminInitializer.createDefaultAdmin();
        runner.run(null);

        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganization()).isEqualTo(organization);
        assertThat(captor.getValue().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("echoue au demarrage si aucune organisation par defaut n'existe")
    void failsWhenNoDefaultOrganization() {
        when(userRepository.existsByUsernameIgnoreCase("admin")).thenReturn(false);
        when(organizationRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        ApplicationRunner runner = adminInitializer.createDefaultAdmin();

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("n'interroge pas l'organisation si l'administrateur existe deja")
    void skipsOrganizationLookupWhenAdminAlreadyExists() throws Exception {
        when(userRepository.existsByUsernameIgnoreCase("admin")).thenReturn(true);

        ApplicationRunner runner = adminInitializer.createDefaultAdmin();
        runner.run(null);

        verify(organizationRepository, never()).findFirstByOrderByIdAsc();
        verify(userRepository, never()).save(any());
    }
}
