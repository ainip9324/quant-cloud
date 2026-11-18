package com.quant.core.engine;

import com.alibaba.fastjson2.JSON;
import com.quant.core.strategy.EmaStrategy;
import com.quant.core.strategy.SignalStrategy;
import com.quant.core.strategy.Strategy;
import com.quant.core.util.IntervalUtils;
import java.util.HashMap;
import java.util.Map;

/** 固化信号、风控和数据截止时间，旧任务缺少 execution 时走原引擎流程。 */
public record SignalRunConfiguration(int version, String strategyVersion, String interval,
                                     long closedBefore, RiskConfiguration risk) {

    /** 在 WAITING 入库前冻结新任务配置，禁止客户端通过 params 注入内部执行快照。 */
    public static Map<String, Object> prepare(Map<String, Object> params, RiskConfiguration requested,
                                              Strategy strategy, String interval) {
        if (params != null && (params.containsKey("execution"))) {
            throw new IllegalArgumentException("execution 由服务端生成");
        }
        if (requested == null) return params;
        if (!(strategy instanceof SignalStrategy signals)) {
            throw new IllegalArgumentException("当前策略尚未支持独立风控方案");
        }
        if (IntervalUtils.toMillis(interval) <= 0) throw new IllegalArgumentException("信号周期无效");
        Map<String, Object> normalized = params == null ? new HashMap<>() : new HashMap<>(params);
        for (String oldRisk : new String[] {"positionRatio", "riskRate", "stopPoints", "takeProfitPoints"}) {
            if (normalized.containsKey(oldRisk)) throw new IllegalArgumentException("请通过风控方案设置 " + oldRisk);
        }
        signals.normalizeSignalParameters(normalized);
        boolean ema = strategy instanceof EmaStrategy;
        if (!ema && requested.usesAtr()) throw new IllegalArgumentException("当前策略不支持 ATR 风控版本");
        normalized.put("execution", new SignalRunConfiguration(1, signals.signalVersion(), interval,
                System.currentTimeMillis(), requested.forNewTask(ema)));
        return normalized;
    }

    /** 执行历史任务时读取已保存版本，未知或损坏快照立即拒绝。 */
    public static SignalRunConfiguration from(Map<String, Object> params, Strategy strategy) {
        if (params == null || !params.containsKey("execution")) return null;
        SignalRunConfiguration config;
        try {
            config = JSON.parseObject(JSON.toJSONString(params.get("execution")), SignalRunConfiguration.class);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("信号执行快照无效", invalid);
        }
        if (config == null || config.version != 1 || config.risk == null || config.closedBefore <= 0
                || config.risk.version() == null || (config.risk.version() != 1
                    && !(config.risk.usesAtr() && strategy instanceof EmaStrategy
                         && config.risk.atrPeriod() != null && config.risk.atrMultiplier() != null))
                || (config.risk.version() == 3 && config.risk.minVolatilityRatio() == null)
                || (EmaStrategy.RETEST_SIGNAL_VERSION.equals(config.strategyVersion) && config.risk.version() != 3)
                || IntervalUtils.toMillis(config.interval) <= 0
                || !(strategy instanceof SignalStrategy signals)
                || !signals.supportsSignalVersion(config.strategyVersion)) {
            throw new IllegalArgumentException("信号执行快照版本无效");
        }
        return new SignalRunConfiguration(1, config.strategyVersion, config.interval,
                config.closedBefore, config.risk.normalized());
    }
}
