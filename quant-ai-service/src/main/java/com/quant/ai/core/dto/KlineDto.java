package com.quant.ai.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KlineDto(Long time, Double open, Double high, Double low, Double close, Double volume) {
}
