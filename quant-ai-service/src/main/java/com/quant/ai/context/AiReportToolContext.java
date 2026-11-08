package com.quant.ai.context;

/** Hidden execution context. It is deliberately not a tool input record. */
public record AiReportToolContext(
        AiRequestContext requestContext,
        long allowedTaskId,
        String allowedStrategyId,
        String allowedSpecificationVersion,
        ToolCallBudget toolCallBudget) {
}
