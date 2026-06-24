package com.dungeon.master.config;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves the application-facing username from a decoded JWT.
 *
 * <p>Under real Keycloak the {@code sub} claim is an opaque user UUID, while
 * {@code preferred_username} is the human-readable login name the frontend uses for display
 * and for host/ownership comparisons (e.g. {@code isCreator}). We key all identity on
 * {@code preferred_username} so stored values (session creator, player username, character
 * owner) match what the frontend sends. The dev mock JWT only carries a {@code sub} claim,
 * so we fall back to it and keep the dev profile working unchanged.
 */
public final class AuthUtils {

    private AuthUtils() {
    }

    public static String username(Jwt jwt) {
        String preferred = jwt.getClaimAsString("preferred_username");
        return (preferred != null && !preferred.isBlank()) ? preferred : jwt.getSubject();
    }
}
