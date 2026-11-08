package com.quant.core.zone;

import com.quant.common.market.MarketType;
import java.math.BigDecimal;
import lombok.Data;

/** Persistence-only row. HTTP commands never bind directly to this type. */
@Data
public class MarketZoneRow {
    private Long id;
    private MarketType marketType;
    private String symbol;
    private String analysisInterval;
    private BigDecimal lowerPrice;
    private BigDecimal upperPrice;
    private Long effectiveFrom;
    private String note;
    private ZoneLifecycle lifecycle;
    private Long revision;
    private Long createdBy;
    private Long updatedBy;
    private Long createdAt;
    private Long updatedAt;

    public MarketZone snapshot() {
        return new MarketZone(id, marketType, symbol, analysisInterval, lowerPrice, upperPrice,
                effectiveFrom, note, lifecycle, revision, createdBy, updatedBy, createdAt, updatedAt);
    }
}
