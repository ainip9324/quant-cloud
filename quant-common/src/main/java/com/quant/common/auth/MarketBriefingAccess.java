package com.quant.common.auth;

public final class MarketBriefingAccess {
    public static final String PATH = "/api/core/market-briefing";

    private MarketBriefingAccess() {
    }

    public static boolean isAllowed(String method, String path, UserRole role) {
        if (!PATH.equals(path)) {
            return false;
        }
        return ("GET".equals(method) && (role == UserRole.ADMIN || role == UserRole.VIEWER))
                || ("PUT".equals(method) && role == UserRole.ADMIN);
    }
}
