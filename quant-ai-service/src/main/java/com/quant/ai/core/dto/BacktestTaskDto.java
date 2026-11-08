package com.quant.ai.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.quant.common.market.MarketType;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BacktestTaskDto(Long id, String strategyId, MarketType marketType, String symbol, String interval, String status,
                              Long startTs, Long endTs, Map<String, Object> params, BacktestResultDto result) { }
