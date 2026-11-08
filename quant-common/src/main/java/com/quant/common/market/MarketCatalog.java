package com.quant.common.market;

import java.util.List;

public final class MarketCatalog {
    private static final List<String> SPOT_SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT");
    private static final List<String> SPOT_INTERVALS = List.of("1min", "5min", "15min", "1h", "4h", "1day");
    private static final List<String> FUTURES_SYMBOLS = List.of("BTCUSDT", "ETHUSDT");
    private static final List<String> FUTURES_INTERVALS = List.of("15min", "1h", "4h", "1day");
    private MarketCatalog() { }
    public static MarketType requireMarketType(String raw) {
        if (raw == null || raw.isBlank()) throw invalid("marketType is required");
        try { return MarketType.valueOf(raw); } catch (IllegalArgumentException e) { throw invalid("marketType is invalid"); }
    }
    public static MarketSelection requireSelection(String marketType, String symbol, String interval) {
        MarketType type = requireMarketType(marketType);
        if (symbol == null || !supportedSymbols(type).contains(symbol)) throw invalid("symbol is invalid for marketType");
        if (interval == null || !supportedIntervals(type).contains(interval)) throw invalid("interval is invalid for marketType");
        return new MarketSelection(type, symbol, interval);
    }
    public static List<String> supportedSymbols(MarketType type) { return type == MarketType.SPOT ? SPOT_SYMBOLS : FUTURES_SYMBOLS; }
    public static List<String> supportedIntervals(MarketType type) { return type == MarketType.SPOT ? SPOT_INTERVALS : FUTURES_INTERVALS; }
    private static InvalidMarketQueryException invalid(String message) { return new InvalidMarketQueryException(message); }
}
