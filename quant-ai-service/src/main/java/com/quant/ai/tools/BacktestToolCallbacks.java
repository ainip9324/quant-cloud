package com.quant.ai.tools;

import com.quant.ai.context.AiReportToolContext;
import com.quant.ai.core.CoreGatewayException;
import com.quant.ai.error.AiErrorCode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The actual Spring AI callbacks intended for Stage C. The model sees only
 * record inputs below; authenticated identity travels in ToolContext instead.
 */
@Component
public class BacktestToolCallbacks {

    public static final String REPORT_CONTEXT_KEY = "quant.ai.report-tool-context";

    private final List<ToolCallback> callbacks;

    /** 登记复盘可用的只读工具，并为每个回调接入服务端授权检查。 */
    public BacktestToolCallbacks(BacktestReadTools tools) {
        this.callbacks = List.of(
                FunctionToolCallback.builder("getBacktestOverview",
                                (OverviewInput input, ToolContext context) ->
                                        tools.getBacktestOverview(reportContext(context).requestContext(), checkedTask(context, input.taskId())))
                        .description("Read a safe deterministic summary for one accessible backtest task.")
                        .inputType(OverviewInput.class)
                        .build(),
                FunctionToolCallback.builder("getReplayEventSummary",
                                (TaskInput input, ToolContext context) ->
                                        tools.getReplayEventSummary(reportContext(context).requestContext(), checkedTask(context, input.taskId())))
                        .description("Read deterministic event and rejection summaries for one accessible replay.")
                        .inputType(TaskInput.class)
                        .build(),
                FunctionToolCallback.builder("getReplayEventWindow",
                                (EventWindowInput input, ToolContext context) -> tools.getReplayEventWindow(
                                        reportContext(context).requestContext(), checkedTask(context, input.taskId()), input.fromSequence(), input.toSequence()))
                        .description("Read a bounded event sequence window and nearby K lines for one accessible replay.")
                        .inputType(EventWindowInput.class)
                        .build(),
                FunctionToolCallback.builder("getStrategySpecification",
                                (StrategySpecificationInput input, ToolContext context) -> {
                                    StrategySpecificationInput checked = checkedSpecification(context, input.strategyId(), input.version());
                                    return tools.getStrategySpecification(reportContext(context).requestContext(), checked.strategyId(), checked.version());
                                })
                        .description("Read a controlled, versioned strategy specification.")
                        .inputType(StrategySpecificationInput.class)
                        .build()
        );
    }

    /** 交给模型的四个受控只读工具；输入record里不包含userId或Q-Token。 */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /** 身份、允许访问的任务/策略版本和共享预算通过服务端上下文传递，不进入工具参数Schema。 */
    public ToolContext toolContext(AiReportToolContext reportContext) {
        return new ToolContext(Map.of(REPORT_CONTEXT_KEY, reportContext));
    }

    /** 从隐藏的 ToolContext 取得认证身份、授权范围和预算。 */
    private static AiReportToolContext reportContext(ToolContext context) {
        Object value = context.getContext().get(REPORT_CONTEXT_KEY);
        if (value instanceof AiReportToolContext reportContext) {
            return reportContext;
        }
        throw new CoreGatewayException(AiErrorCode.AI_CORE_UNAUTHORIZED, "authenticated tool context is missing");
    }

    /** 扣减工具预算并校验请求的任务属于本次报告。 */
    private static long checkedTask(ToolContext context, long taskId) {
        AiReportToolContext reportContext = reportContext(context);
        // 先计数再检查范围：模型发出的无效/越权工具请求同样消耗本次报告预算。
        reportContext.toolCallBudget().consume();
        if (taskId <= 0 || taskId != reportContext.allowedTaskId()) {
            throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "task is outside this report");
        }
        return taskId;
    }

    /** 扣减工具预算并校验请求的策略与版本属于本次报告。 */
    private static StrategySpecificationInput checkedSpecification(ToolContext context, String strategyId, String version) {
        AiReportToolContext reportContext = reportContext(context);
        reportContext.toolCallBudget().consume();
        // 模型不能通过工具切换到另一策略或规范版本，初次生成和修复共用此约束。
        if (!reportContext.allowedStrategyId().equals(strategyId)
                || !reportContext.allowedSpecificationVersion().equals(version)) {
            throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "specification is outside this report");
        }
        return new StrategySpecificationInput(strategyId, version);
    }

    public record OverviewInput(long taskId) {
    }

    public record TaskInput(long taskId) {
    }

    public record EventWindowInput(long taskId, long fromSequence, long toSequence) {
    }

    public record StrategySpecificationInput(String strategyId, String version) {
    }
}
