package com.quant.ai.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.quant.common.market.MarketType;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplayResponseDto(Long taskId, String strategyId, MarketType marketType, String symbol, String interval,
                                BacktestReplayDto replay) { }
