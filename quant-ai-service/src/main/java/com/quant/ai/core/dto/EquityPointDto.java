package com.quant.ai.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EquityPointDto(Long time, Double equity) {
}
