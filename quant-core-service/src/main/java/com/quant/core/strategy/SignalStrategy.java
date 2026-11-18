package com.quant.core.strategy;

import com.quant.common.model.Kline;
import java.util.Map;

/** 可由不同风控方案复用的收盘信号接口。 */
public interface SignalStrategy {
    String signalVersion();

    /** 升级策略显式保留历史执行版本。 */
    default boolean supportsSignalVersion(String version) { return signalVersion().equals(version); }

    /** 只规范化本策略的信号参数，调用方提供任务独有的可写副本。 */
    default void normalizeSignalParameters(Map<String, Object> params) {}

    /** 只读取截至当前闭合 K 线的数据，返回候选动作。 */
    SignalDecision onSignal(StrategyContext context, Kline bar);
}
