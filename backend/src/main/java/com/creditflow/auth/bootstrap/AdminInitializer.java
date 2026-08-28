package com.creditflow.auth.bootstrap;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.config.AppProperties;
import com.creditflow.organization.domain.Organization;
import com.creditflow.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cree l'administrateur unique de la boutique au demarrage s'il n'existe pas.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdminInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final OrganizationRepository organizationRepository;

    @Bean
    @Order(1)
    public ApplicationRunner createDefaultAdmin() {
        return args -> {
            AppProperties.Admin admin = properties.getAdmin();
            if (userRepository.existsByUsernameIgnoreCase(admin.getUsername())) {
                log.info("Administrateur '{}' deja present.", admin.getUsername());
                return;
            }
            Organization organization = organizationRepository.findFirstByOrderByIdAsc()
                    .orElseThrow(() -> new IllegalStateException(
                            "Aucune organisation par defaut trouvee : la migration V13 doit etre appliquee."));
            User user = User.builder()
                    .username(admin.getUsername())
                    .password(passwordEncoder.encode(admin.getPassword()))
                    .fullName(admin.getFullName())
                    .role(Role.ADMIN)
                    .enabled(true)
                    .mustChangePassword(admin.isForcePasswordChange())
                    .organization(organization)
                    .build();
            userRepository.save(user);

            if (admin.isForcePasswordChange()) {
                log.info("Administrateur '{}' cree : un changement de mot de passe sera "
                        + "demande a la premiere connexion.", admin.getUsername());
            } else {
                log.info("Administrateur '{}' cree automatiquement.", admin.getUsername());
            }
        };
    }
}
