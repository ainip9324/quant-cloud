package com.quant.ai.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeDto(Long time, String type, String positionSide, Double price, Double quantity,
                       Double pnl, String sourceId) {
}
