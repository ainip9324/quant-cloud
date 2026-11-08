package com.quant.ai.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.List;
import java.time.Duration;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/** 默认 provider：不发网络请求，供本地启动、CI 与后续业务测试使用。 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "model-provider", havingValue = "fake", matchIfMissing = true)
public class FakeModelClient implements ModelClient {

    /** 返回用于本地验证的固定文本。 */
    @Override
    public String complete(String prompt) {
        return "FAKE_MODEL_RESPONSE";
    }

    /** 返回用于本地验证的固定 JSON 对象。 */
    @Override
    public String completeJson(String prompt) {
        return "{\"provider\":\"fake\"}";
    }

    /** 以多个固定片段模拟模型流式输出。 */
    @Override
    public Flux<String> stream(String prompt) {
        return Flux.just("FAKE_", "MODEL_", "STREAM");
    }

    /** 返回固定复盘候选内容，供事实与证据校验测试使用。 */
    @Override
    public ModelCallResult completeReviewJson(String prompt, List<ToolCallback> tools, ToolContext toolContext) {
        String content = "{\"headline\":\"Deterministic backtest review\",\"summary\":\"Review uses verified facts only.\",\"evidence\":[{\"candidateId\":\"E1\",\"type\":\"FACT\",\"factCode\":\"FILL_COUNT\"}],\"findings\":[{\"title\":\"Execution count is available\",\"detail\":\"The deterministic fact layer provides the fill count.\",\"evidenceIds\":[\"E1\"]}],\"riskWarnings\":[],\"experimentHypotheses\":[]}";
        return new ModelCallResult(content, "fake", "fake", 0, 0, 0, "stop", 0, 0);
    }

    /** 检查调用时限后返回模拟复盘结果。 */
    @Override
    public ModelCallResult completeReviewJson(String system, String user, List<ToolCallback> tools, ToolContext toolContext, Duration allowedDuration) {
        if (allowedDuration == null || allowedDuration.isNegative() || allowedDuration.isZero()) {
            throw new com.quant.ai.error.AiModelException(com.quant.ai.error.AiErrorCode.AI_MODEL_TIMEOUT, "timeout");
        }
        return completeReviewJson(user, tools, toolContext);
    }
}
