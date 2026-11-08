package com.quant.ai.report;

import java.time.LocalDateTime;

public record AiReportRecord(
        long id, long userId, long taskId, ReportStatus status, String inputHash,
        String promptVersion, String promptHash, String schemaVersion, String specificationVersion,
        String specificationHash, String modelProvider, String modelName, String actualModelName, int attemptNo,
        String reportJson, Integer promptTokens, Integer completionTokens, Integer totalTokens,
        String finishReason, Long latencyMs, Integer toolCallCount, String errorCode,
        String errorMessage, LocalDateTime createTime, LocalDateTime updateTime) { }
