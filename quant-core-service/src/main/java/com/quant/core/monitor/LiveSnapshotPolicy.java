package com.quant.core.monitor;

import com.quant.common.market.LiveMarketSnapshot;
import java.math.BigDecimal;
import java.time.Clock;

/** One policy for persisted monitoring input and the browser read model. */
public final class LiveSnapshotPolicy {
    private LiveSnapshotPolicy() { }
    public static boolean valid(MonitorScope scope, LiveMarketSnapshot snapshot, Clock clock, long staleMillis) {
        if (snapshot == null || snapshot.marketType() != scope.marketType() || !scope.symbol().equals(snapshot.symbol())
                || !scope.interval().equals(snapshot.interval()) || snapshot.feedId() == null
                || !snapshot.feedId().matches("[A-Za-z0-9-]{1,64}") || snapshot.sequence() <= 0
                || !price(snapshot.lastPrice()) || !fresh(snapshot.priceTime(), staleMillis, clock, staleMillis)
                || !fresh(snapshot.priceReceivedAt(), staleMillis, clock, staleMillis)
                || !fresh(snapshot.emittedAt(), staleMillis, clock, staleMillis) || snapshot.candle() == null
                || !fresh(snapshot.candleMarketTime(), scope.periodMillis() + staleMillis, clock, staleMillis)
                || !fresh(snapshot.candleReceivedAt(), scope.periodMillis() + staleMillis, clock, staleMillis)) return false;
        long start = snapshot.candle().time();
        return start <= clock.millis() + staleMillis && start <= snapshot.candleMarketTime()
                && start + scope.periodMillis() >= clock.millis() - staleMillis
                && snapshot.lastClosedCandleTime() != null && snapshot.lastClosedCandleTime() > 0
                && snapshot.lastClosedCandleTime() == start - scope.periodMillis();
    }
    private static boolean fresh(Long time, long age, Clock clock, long futureTolerance) {
        return time != null && time > 0 && time <= clock.millis() + futureTolerance && clock.millis() - time <= age;
    }
    private static boolean price(BigDecimal price) {
        if (price == null || price.signum() <= 0) return false;
        BigDecimal normalized = price.stripTrailingZeros();
        return normalized.scale() <= 18 && (long) normalized.precision() - normalized.scale() <= 20;
    }
}
