package com.quant.core.strategy;

/** 信号仅携带动作与可选来源修订，不携带数量或保护价格。 */
public record SignalDecision(TradeSignal action, String sourceId, Long sourceRevision) {
    public SignalDecision {
        if (action == null || (sourceId == null) != (sourceRevision == null)
                || (sourceId != null && (sourceId.isBlank() || sourceRevision <= 0))) {
            throw new IllegalArgumentException("信号动作或来源修订无效");
        }
    }

    public static SignalDecision of(TradeSignal action) { return new SignalDecision(action, null, null); }
}
