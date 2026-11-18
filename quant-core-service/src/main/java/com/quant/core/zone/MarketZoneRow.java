package com.quant.core.zone;

import com.quant.common.market.MarketType;
import java.math.BigDecimal;
import java.math.MathContext;
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
    private Long effectiveUntil;
    private BigDecimal endLowerPrice;
    private String note;
    private ZoneLifecycle lifecycle;
    private Long revision;
    private Long createdBy;
    private Long updatedBy;
    private Long createdAt;
    private Long updatedAt;

    public MarketZone snapshot() {
        return new MarketZone(id, marketType, symbol, analysisInterval, lowerPrice, upperPrice,
                effectiveFrom, effectiveUntil, endLowerPrice, note, lifecycle, revision, createdBy, updatedBy,
                createdAt, updatedAt);
    }

    /** 区域与通道在配置生效期间按市场时间观察。 */
    public boolean isObservableAt(long marketTime) {
        return lifecycle == ZoneLifecycle.ACTIVE && marketTime >= effectiveFrom
                && (effectiveUntil == null || marketTime < effectiveUntil);
    }

    /** 在有效期内计算下沿，平行通道上沿随其等宽平移。 */
    public BigDecimal lowerPriceAt(long marketTime) {
        if (endLowerPrice == null) return lowerPrice;
        BigDecimal offset = endLowerPrice.subtract(lowerPrice)
                .multiply(BigDecimal.valueOf(marketTime - effectiveFrom))
                .divide(BigDecimal.valueOf(effectiveUntil - effectiveFrom), MathContext.DECIMAL128);
        return lowerPrice.add(offset);
    }
}
