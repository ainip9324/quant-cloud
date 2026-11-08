package com.quant.core.monitor;

public final class MarketMonitorException extends RuntimeException {
    private final int status;
    private final String code;
    public MarketMonitorException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    static MarketMonitorException invalid(String message) {
        return new MarketMonitorException(400, "INVALID_MARKET_MONITOR_QUERY", message);
    }
}
