package com.quant.data.market;

import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketQueryLimits;
import com.quant.common.market.MarketSelection;

public final class MarketDataQuery {
    private final MarketSelection selection; private final int limit;
    private MarketDataQuery(MarketSelection selection, int limit) { this.selection = selection; this.limit = limit; }
    public static MarketDataQuery of(String marketType, String symbol, String interval, Integer limit) {
        int validatedLimit = MarketQueryLimits.requireLimit(limit);
        return new MarketDataQuery(MarketCatalog.requireSelection(marketType, symbol, interval), validatedLimit);
    }
    public MarketSelection selection() { return selection; } public int limit() { return limit; }
}
