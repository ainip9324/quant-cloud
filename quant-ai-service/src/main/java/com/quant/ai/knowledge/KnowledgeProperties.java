package com.quant.ai.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ai.knowledge")
public record KnowledgeProperties(
        boolean enabled,
        @NotBlank String redisHost,
        @Min(1) @Max(65535) int redisPort,
        boolean secureCookie,
        @Min(1) int visitorDailyLimit,
        @Min(1) int visitorPoolDailyLimit,
        @Min(1) int accountDailyLimit,
        @Min(1) int totalDailyLimit) {
}
