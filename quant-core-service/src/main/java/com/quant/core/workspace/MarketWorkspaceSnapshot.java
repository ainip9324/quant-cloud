package com.quant.core.workspace;

import com.quant.common.market.LiveCandle;
import com.quant.common.market.LiveMarketSnapshot.Status;
import com.quant.common.market.MarketType;
import com.quant.common.model.Kline;
import com.quant.core.monitor.MarketMonitorView;
import com.quant.core.monitor.ZoneEvent;
import com.quant.core.zone.MarketZone;
import com.quant.core.zone.ZoneLifecycle;
import java.math.BigDecimal;
import java.util.List;

public record MarketWorkspaceSnapshot(String streamId, long version, long serverTime, MarketType marketType,
        String symbol, String interval, Status status, String lastPrice, Long marketTime, Long receivedAt,
        String historyStatus, List<Kline> candles, Candle currentCandle, List<Zone> zones, List<ZoneEvent> events) {
    public record Candle(long time, String open, String high, String low, String close, String volume) {
        static Candle from(LiveCandle c) { return new Candle(c.time(), decimal(c.open()), decimal(c.high()), decimal(c.low()), decimal(c.close()), decimal(c.volume())); }
    }
    public record Zone(long id, String lowerPrice, String upperPrice, long effectiveFrom, String note,
            ZoneLifecycle lifecycle, long revision, MarketMonitorView.Observation observation) {
        static Zone from(MarketZone z, MarketMonitorView.Observation observation) {
            return new Zone(z.id(), decimal(z.lowerPrice()), decimal(z.upperPrice()), z.effectiveFrom(), z.note(), z.lifecycle(), z.revision(), observation);
        }
    }
    static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
}
