package com.creditflow.auth.service;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.dto.UserRequest;
import com.creditflow.auth.dto.UserResponse;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.organization.domain.Organization;
import com.creditflow.organization.repository.OrganizationRepository;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.dto.ShopSummary;
import com.creditflow.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAllByOrderByFullNameAsc()
                .stream()
                .map(UserService::toResponse)
                .toList();
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        if (userRepository.existsByUsernameIgnoreCase(request.username())) {
            throw new BusinessRuleException("Ce nom d'utilisateur est deja utilise");
        }

        Set<Shop> shops = resolveShops(request.role(), request.shopIds());

        User user = User.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(request.role())
                .enabled(true)
                .mustChangePassword(true)
                .shops(shops)
                .organization(resolveDefaultOrganization())
                .build();

        User saved = userRepository.save(user);
        log.info("Compte utilisateur cree: {} ({})", saved.getUsername(), saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public UserResponse setEnabled(Long id, boolean enabled, String currentUsername) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Utilisateur", id));

        if (!enabled && user.getUsername().equalsIgnoreCase(currentUsername)) {
            throw new BusinessRuleException("Vous ne pouvez pas desactiver votre propre compte");
        }

        user.setEnabled(enabled);
        User saved = userRepository.save(user);
        log.info("Compte utilisateur {} {}", saved.getUsername(), enabled ? "active" : "desactive");
        return toResponse(saved);
    }

    @Transactional
    public UserResponse updateShops(Long id, List<Long> shopIds, String currentUsername) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Utilisateur", id));

        user.setShops(resolveShops(user.getRole(), shopIds));
        User saved = userRepository.save(user);
        log.info("Boutiques mises a jour pour {} par {}", saved.getUsername(), currentUsername);
        return toResponse(saved);
    }

    private Organization resolveDefaultOrganization() {
        return organizationRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException(
                        "Aucune organisation par defaut trouvee : la migration V13 doit etre appliquee."));
    }

    private Set<Shop> resolveShops(Role role, List<Long> shopIds) {
        if (role == Role.SELLER && CollectionUtils.isEmpty(shopIds)) {
            throw new BusinessRuleException("Un vendeur doit etre rattache a au moins une boutique");
        }
        if (CollectionUtils.isEmpty(shopIds)) {
            return new HashSet<>();
        }
        Set<Shop> shops = new HashSet<>();
        for (Long shopId : shopIds) {
            shops.add(shopRepository.findById(shopId)
                    .orElseThrow(() -> ResourceNotFoundException.of("Boutique", shopId)));
        }
        return shops;
    }

    private static UserResponse toResponse(User user) {
        List<ShopSummary> shops = user.getShops().stream()
                .map(s -> new ShopSummary(s.getId(), s.getName()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isMustChangePassword(), user.isEnabled(), shops);
    }
}
