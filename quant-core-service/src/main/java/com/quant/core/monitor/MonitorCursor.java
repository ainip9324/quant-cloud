package com.quant.core.monitor;

import com.quant.common.market.LiveMarketSnapshot.Status;
import com.quant.common.market.MarketType;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class MonitorCursor {
    private MarketType marketType;
    private String symbol;
    private String analysisInterval;
    private String feedId;
    private Long sourceSequence;
    private Long priceTime;
    private Long priceReceivedAt;
    private Long sourceEmittedAt;
    private BigDecimal lastPrice;
    private Status status;
    private boolean baselineRequired;
    private Long recordedAt;
}
