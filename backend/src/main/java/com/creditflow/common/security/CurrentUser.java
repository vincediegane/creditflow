package com.creditflow.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Lecture null-safe de l'utilisateur authentifie, utilisee pour tracer
 * l'auteur d'une action (colonnes created_by/updated_by, journal d'audit).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }
}
