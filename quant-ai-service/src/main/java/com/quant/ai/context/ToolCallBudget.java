package com.quant.ai.context;

import com.quant.ai.error.AiErrorCode;
import com.quant.ai.error.AiModelException;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared across an initial generation and its single repair attempt. */
public final class ToolCallBudget {
    private final int maximum;
    private final AtomicInteger used = new AtomicInteger();

    public ToolCallBudget(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
        this.maximum = maximum;
    }

    /** 占用一次工具调用额度，超出上限时撤销本次计数并拒绝。 */
    public void consume() {
        int next = used.incrementAndGet();
        if (next > maximum) {
            used.decrementAndGet();
            throw new AiModelException(AiErrorCode.AI_REPORT_TOOL_BUDGET_EXCEEDED, "tool call budget exhausted");
        }
    }

    /** 返回已计入本次报告的工具调用数，供记录生成用量。 */
    public int used() { return used.get(); }
    public int maximum() { return maximum; }
}
