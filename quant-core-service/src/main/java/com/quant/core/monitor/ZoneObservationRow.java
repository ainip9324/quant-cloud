package com.quant.core.monitor;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class ZoneObservationRow {
    private Long zoneId;
    private Long zoneRevision;
    private ObservationState state;
    private BigDecimal statePrice;
    private Long stateMarketTime;
    private String feedId;
    private Long sourceSequence;
    private Long recordedAt;
}
