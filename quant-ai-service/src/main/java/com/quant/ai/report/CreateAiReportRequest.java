package com.quant.ai.report;

import jakarta.validation.constraints.Positive;

public record CreateAiReportRequest(@Positive long taskId) { }
