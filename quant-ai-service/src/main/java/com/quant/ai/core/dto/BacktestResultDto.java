package com.quant.ai.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BacktestResultDto(Double returnRate, Double maxDrawdown, Double initialCapital,
                                List<EquityPointDto> equityCurve, List<TradeDto> trades) {
}
