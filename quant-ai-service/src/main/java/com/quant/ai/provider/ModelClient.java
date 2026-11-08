package com.quant.ai.provider;

import reactor.core.publisher.Flux;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import java.time.Duration;

/**
 * 模型边界接口。阶段 A 不暴露业务 HTTP API；后续 B/C/D 只能通过此接口调用模型。
 */
public interface ModelClient {

    /** 同步生成纯文本结果。 */
    String complete(String prompt);

    /** 生成可解析的 JSON 文本。 */
    String completeJson(String prompt);

    /** 按到达顺序输出模型生成的文本片段。 */
    Flux<String> stream(String prompt);

    /** One structured report attempt. Service-level validation owns the single repair attempt. */
    default ModelCallResult completeReviewJson(String prompt, List<ToolCallback> tools, ToolContext toolContext) {
        long started = System.nanoTime();
        return new ModelCallResult(completeJson(prompt), "unknown", "unknown", null, null, null,
                "stop", (System.nanoTime() - started) / 1_000_000, 0);
    }

    /**
     * create链路的一次结构化调用：接收system/user提示、受控工具与隐藏上下文。
     * allowedDuration是报告剩余预算；返回原始内容和用量元数据，报告校验及是否修复由Service决定。
     */
    ModelCallResult completeReviewJson(String system, String user, List<ToolCallback> tools,
                                       ToolContext toolContext, Duration allowedDuration);
}
