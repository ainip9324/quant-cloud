package com.quant.core.zone;

import com.quant.common.market.MarketType;
import java.math.BigDecimal;

/** Shared definition at one immutable revision, not a calculated monitoring state. */
public record MarketZone(long id, MarketType marketType, String symbol, String analysisInterval,
                         BigDecimal lowerPrice, BigDecimal upperPrice, long effectiveFrom, String note,
                         ZoneLifecycle lifecycle, long revision, long createdBy, long updatedBy,
                         long createdAt, long updatedAt) { }
