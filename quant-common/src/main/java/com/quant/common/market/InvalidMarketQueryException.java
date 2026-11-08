package com.quant.common.market;

public final class InvalidMarketQueryException extends IllegalArgumentException {
    public static final String CODE = "INVALID_MARKET_QUERY";
    public InvalidMarketQueryException(String message) { super(message); }
}
