package com.creditflow.common.security;

import com.creditflow.auth.domain.Role;
import com.creditflow.auth.domain.User;
import com.creditflow.auth.repository.UserRepository;
import com.creditflow.common.exception.BusinessRuleException;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.dto.ShopSummary;
import com.creditflow.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

/**
 * Resout les boutiques accessibles a l'utilisateur authentifie courant et
 * interprete l'en-tete optionnel {@link #SHOP_HEADER} pour les ecrans
 * consolidables (dashboard, rapports) et les creations de ressources.
 */
@Component
@RequiredArgsConstructor
public class CurrentShopContext {

    public static final String SHOP_HEADER = "X-Shop-Id";

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;

    /** Boutiques accessibles a l'utilisateur authentifie courant (jamais vide pour un compte valide). */
    public List<Long> accessibleShopIds() {
        User user = currentUser();
        if (!user.getShops().isEmpty()) {
            return user.getShops().stream().map(Shop::getId).toList();
        }
        if (user.getRole() == Role.ADMIN) {
            return shopRepository.findAllByActiveTrueOrderByNameAsc().stream().map(Shop::getId).toList();
        }
        throw new BusinessRuleException("Aucune boutique n'est assignee a votre compte. Contactez votre administrateur.");
    }

    /** Resumes (id + nom) des boutiques accessibles — utilise par AuthResponse. */
    public List<ShopSummary> accessibleShops() {
        User user = currentUser();
        List<Shop> shops;
        if (!user.getShops().isEmpty()) {
            shops = user.getShops().stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();
        } else if (user.getRole() == Role.ADMIN) {
            shops = shopRepository.findAllByActiveTrueOrderByNameAsc();
        } else {
            throw new BusinessRuleException("Aucune boutique n'est assignee a votre compte. Contactez votre administrateur.");
        }
        return shops.stream().map(s -> new ShopSummary(s.getId(), s.getName())).toList();
    }

    /**
     * Filtre de lecture pour les ecrans consolidables (dashboard, rapports) :
     * - si l'en-tete X-Shop-Id est present et pointe vers une boutique accessible : liste a un element ;
     * - si l'en-tete est present mais non accessible : BusinessRuleException (422) ;
     * - si l'en-tete est absent : accessibleShopIds() (vue consolidee si plusieurs boutiques).
     */
    public List<Long> resolveReadFilter() {
        List<Long> accessible = accessibleShopIds();
        Optional<Long> requested = requestedShopId();
        if (requested.isEmpty()) {
            return accessible;
        }
        Long shopId = requested.get();
        if (!accessible.contains(shopId)) {
            throw new BusinessRuleException(
                    "La boutique demandee (X-Shop-Id=%d) n'est pas accessible pour votre compte.".formatted(shopId));
        }
        return List.of(shopId);
    }

    /**
     * Boutique cible pour une creation (client, produit, vente, import) :
     * - en-tete X-Shop-Id si present (doit etre accessible) ;
     * - sinon, s'il n'existe qu'une seule boutique accessible, celle-ci ;
     * - sinon BusinessRuleException (422).
     */
    public Long shopIdForCreation() {
        List<Long> accessible = accessibleShopIds();
        Optional<Long> requested = requestedShopId();
        if (requested.isPresent()) {
            Long shopId = requested.get();
            if (!accessible.contains(shopId)) {
                throw new BusinessRuleException(
                        "La boutique demandee (X-Shop-Id=%d) n'est pas accessible pour votre compte."
                                .formatted(shopId));
            }
            return shopId;
        }
        if (accessible.size() == 1) {
            return accessible.get(0);
        }
        throw new BusinessRuleException(
                "Vous etes rattache a plusieurs boutiques : precisez la boutique cible via l'en-tete X-Shop-Id "
                        + "avant de creer cette ressource.");
    }

    /**
     * Garde d'acces direct par identifiant : leve ResourceNotFoundException si shopId
     * n'appartient pas a accessibleShopIds() — ne revele jamais si la ressource existe
     * dans une autre boutique.
     */
    public void assertAccessible(Long shopId) {
        if (!accessibleShopIds().contains(shopId)) {
            throw new ResourceNotFoundException("Ressource introuvable");
        }
    }

    private User currentUser() {
        String username = CurrentUser.username();
        if (username == null) {
            throw new IllegalStateException("Aucun utilisateur authentifie");
        }
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));
    }

    private Optional<Long> requestedShopId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        String header = attributes.getRequest().getHeader(SHOP_HEADER);
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(header.trim()));
        } catch (NumberFormatException e) {
            throw new BusinessRuleException("En-tete X-Shop-Id invalide");
        }
    }
}
