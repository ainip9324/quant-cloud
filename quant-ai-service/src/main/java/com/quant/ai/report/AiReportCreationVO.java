package com.quant.ai.report;
public record AiReportCreationVO(long reportId, long taskId, ReportStatus status, int attemptNo, boolean reused) { }
