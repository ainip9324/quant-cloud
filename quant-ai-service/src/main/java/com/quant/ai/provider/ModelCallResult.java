package com.quant.ai.provider;

/** Auditable metadata returned by the provider boundary; it contains no credentials. */
public record ModelCallResult(
        String content,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String finishReason,
        long latencyMs,
        int toolCallCount) {
}
