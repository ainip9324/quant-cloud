package com.quant.ai.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BacktestReplayDto(Integer schemaVersion, List<KlineDto> bars, List<StrategyEventDto> events) {
}
