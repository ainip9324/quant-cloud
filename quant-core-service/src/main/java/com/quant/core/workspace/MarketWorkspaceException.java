package com.quant.core.workspace;

public final class MarketWorkspaceException extends RuntimeException {
    private final int status;
    private final String code;
    public MarketWorkspaceException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    static MarketWorkspaceException invalid() { return new MarketWorkspaceException(400, "INVALID_MARKET_WORKSPACE_QUERY", "Invalid market workspace query"); }
}
