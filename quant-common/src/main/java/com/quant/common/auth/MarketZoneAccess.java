package com.quant.common.auth;

/** Shared exact route policy used independently by Gateway and core. */
public final class MarketZoneAccess {
    public static final String ROOT = "/api/core/market-zones";
    public static final String ANDROID_ALERT_STREAM = "/api/core/zone-alerts/android/stream";
    private static final String ITEM = ROOT + "/[1-9]\\d*";

    private MarketZoneAccess() { }

    public static boolean isAllowed(String method, String path, UserRole role) {
        if (role != UserRole.ADMIN && role != UserRole.VIEWER) return false;
        if (("GET".equals(method) || "PUT".equals(method)) && "/api/core/zone-alerts/settings".equals(path)) return true;
        if ("GET".equals(method) && "/api/core/zone-alerts".equals(path)
                || ("PUT".equals(method) || "DELETE".equals(method))
                && path.matches("/api/core/zone-alerts/subscriptions/[1-9]\\d*")) return true;
        if ("GET".equals(method) && (ROOT.equals(path) || path.matches(ITEM) || ANDROID_ALERT_STREAM.equals(path)
                || "/api/core/market-zone-observations".equals(path) || "/api/core/market-zone-events".equals(path)
                || "/api/core/market-workspace".equals(path) || "/api/core/market-workspace/stream".equals(path))) return true;
        if (role != UserRole.ADMIN) return false;
        return "POST".equals(method) && (ROOT.equals(path) || path.matches(ITEM + "/(enable|disable|archive)"))
                || "PUT".equals(method) && path.matches(ITEM)
                || "GET".equals(method) && path.matches(ITEM + "/revisions(/[1-9]\\d*)?");
    }
}
