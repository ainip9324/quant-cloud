package com.quant.ai.report;
import java.time.LocalDateTime;
public record AiReportDetailVO(long id, long taskId, ReportStatus status, int attemptNo, String requestedModel, String actualModel,
                               String promptVersion, LocalDateTime createTime, LocalDateTime updateTime,
                               AiReviewReport report, String errorCode) { }
