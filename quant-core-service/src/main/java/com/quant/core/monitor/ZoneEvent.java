package com.quant.core.monitor;

import com.quant.common.market.MarketType;
import java.math.BigDecimal;
import lombok.Data;

/** Event and frozen definition joined through the immutable revision, never the current zone. */
@Data
public class ZoneEvent {
    private Long id;
    private Long zoneId;
    private Long zoneRevision;
    private MarketType marketType;
    private String symbol;
    private String analysisInterval;
    private ObservationState fromState;
    private ObservationState toState;
    private String eventCode;
    private BigDecimal observedPrice;
    private Long marketTime;
    private Long recordedAt;
    private String feedId;
    private Long sourceSequence;
    private BigDecimal lowerPrice;
    private BigDecimal upperPrice;
    private Long effectiveFrom;
    private String note;
}
