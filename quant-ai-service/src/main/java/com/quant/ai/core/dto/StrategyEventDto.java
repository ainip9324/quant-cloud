package com.quant.ai.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StrategyEventDto(Long sequence, Long time, String category, String code, String sourceId,
                               String positionSide, Double price, Map<String, Object> details) {
}
