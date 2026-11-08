package com.quant.ai.report;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.report")
public record AiReportProperties(
        Duration totalTimeout,
        Duration staleGrace,
        int toolCallBudget,
        int responseByteLimit,
        int persistedReportByteLimit) {
}
