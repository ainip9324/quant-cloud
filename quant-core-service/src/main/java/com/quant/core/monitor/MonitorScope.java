package com.quant.core.monitor;

import com.quant.common.market.InvalidMarketQueryException;
import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketType;

public record MonitorScope(MarketType marketType, String symbol, String interval) {
    public MonitorScope {
        try {
            MarketCatalog.requireSelection(marketType == null ? null : marketType.name(), symbol, interval);
            if (marketType != MarketType.USDT_FUTURES) throw MarketMonitorException.invalid("Monitoring requires USDT_FUTURES");
        } catch (InvalidMarketQueryException exception) {
            throw MarketMonitorException.invalid("Invalid market, symbol or interval");
        }
    }
    public static MonitorScope of(String marketType, String symbol, String interval) {
        try { return new MonitorScope(MarketCatalog.requireMarketType(marketType), symbol, interval); }
        catch (InvalidMarketQueryException exception) { throw MarketMonitorException.invalid("Invalid market type"); }
    }
    public String key() { return marketType + ":" + symbol + ":" + interval; }
    public long periodMillis() {
        return switch (interval) {
            case "15min" -> 900_000L;
            case "1h" -> 3_600_000L;
            case "4h" -> 14_400_000L;
            case "1day" -> 86_400_000L;
            default -> throw new IllegalStateException("Unvalidated interval");
        };
    }
}
