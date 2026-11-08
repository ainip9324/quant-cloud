package com.quant.core.zone;

public final class MarketZoneException extends RuntimeException {
    private final int status;
    private final String code;

    public MarketZoneException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    static MarketZoneException invalid(String message) { return new MarketZoneException(400, "INVALID_MARKET_ZONE", message); }
    static MarketZoneException conflict(String code, String message) { return new MarketZoneException(409, code, message); }
    static MarketZoneException storage() { return new MarketZoneException(503, "MARKET_ZONE_STORAGE_UNAVAILABLE", "Market zone storage is unavailable"); }
}
