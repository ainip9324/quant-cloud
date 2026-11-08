package com.quant.core.briefing;

public record MarketBriefing(String outlook, String events, String risks, long revision, Long updatedAt) {
}
