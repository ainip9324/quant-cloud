package com.quant.common.auth;

public record AuthenticatedPrincipal(long userId, UserRole role) {
    public AuthenticatedPrincipal {
        if (userId <= 0 || role == null) {
            throw new IllegalArgumentException("Invalid authenticated principal");
        }
    }
}
