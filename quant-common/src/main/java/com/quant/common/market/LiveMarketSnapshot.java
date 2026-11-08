package com.quant.common.market;

import java.math.BigDecimal;

/** Full replacement snapshot; sequence is comparable only within the same feedId. */
public record LiveMarketSnapshot(
        String feedId, long sequence, MarketType marketType, String symbol, String interval,
        Status status, BigDecimal lastPrice, Long priceTime, Long priceReceivedAt,
        LiveCandle candle, Long candleMarketTime, Long candleReceivedAt,
        Long lastClosedCandleTime, long emittedAt) {
    public enum Status { STOPPED, CONNECTING, RECONNECTING, RECOVERING, LIVE, DEGRADED }

    public LiveMarketSnapshot withStatus(Status next) {
        return new LiveMarketSnapshot(feedId, sequence, marketType, symbol, interval, next,
                lastPrice, priceTime, priceReceivedAt, candle, candleMarketTime, candleReceivedAt,
                lastClosedCandleTime, emittedAt);
    }
}
