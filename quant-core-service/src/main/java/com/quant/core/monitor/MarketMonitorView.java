package com.quant.core.monitor;

import com.quant.common.market.LiveMarketSnapshot.Status;
import com.quant.common.market.MarketType;
import java.math.BigDecimal;
import java.util.List;

public record MarketMonitorView(MarketType marketType, String symbol, String analysisInterval, Status status,
                                BigDecimal lastPrice, Long marketTime, Long receivedAt, String feedId,
                                Long sequence, List<Observation> zones) {
    public record Observation(long zoneId, long zoneRevision, Long stateRevision, ObservationState state, String role,
                              BigDecimal statePrice, Long stateMarketTime, Long recordedAt, boolean current) { }
}
