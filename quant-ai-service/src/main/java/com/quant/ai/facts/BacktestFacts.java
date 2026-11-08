package com.quant.ai.facts;

import java.util.List;
import java.util.Map;

/** Deterministic, model-free fact bundle. Numeric fields are absent rather than estimated. */
public record BacktestFacts(
        Map<String, Object> overview,
        Map<String, Object> performance,
        List<Map<String, Object>> zoneSummaries,
        List<Map<String, Object>> tradeSummaries,
        Map<String, Object> rejectionSummary,
        List<Map<String, Object>> notableSequences,
        List<String> dataLimitations) { }
