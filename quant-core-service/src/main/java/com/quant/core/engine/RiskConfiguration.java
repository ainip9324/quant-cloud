package com.quant.core.engine;

/** 每次回测独立的风控参数快照，比例统一使用 0.01 表示 1%。 */
public record RiskConfiguration(Integer version, Preset preset, Double allocation,
                                Double stopLossRatio, Double rewardRiskRatio, Double maxNotionalRatio,
                                Double maxPositionRiskRatio, Integer atrPeriod, Double atrMultiplier,
                                Double minVolatilityRatio) {
    public RiskConfiguration(Integer version, Preset preset, Double allocation, Double stopLossRatio,
                             Double rewardRiskRatio, Double maxNotionalRatio, Double maxPositionRiskRatio,
                             Integer atrPeriod, Double atrMultiplier) {
        this(version, preset, allocation, stopLossRatio, rewardRiskRatio, maxNotionalRatio,
                maxPositionRiskRatio, atrPeriod, atrMultiplier, null);
    }

    public RiskConfiguration(Integer version, Preset preset, Double allocation, Double stopLossRatio,
                             Double rewardRiskRatio, Double maxNotionalRatio, Double maxPositionRiskRatio) {
        this(version, preset, allocation, stopLossRatio, rewardRiskRatio, maxNotionalRatio,
                maxPositionRiskRatio, null, null);
    }

    public RiskConfiguration(Integer version, Preset preset, Double allocation, Double stopLossRatio,
                             Double rewardRiskRatio, Double maxNotionalRatio) {
        this(version, preset, allocation, stopLossRatio, rewardRiskRatio, maxNotionalRatio, null);
    }
    public enum Preset { NOTIONAL_RATIO, STOP_RISK }

    /** 提交时默认按止损距离定仓；执行历史快照时仍要求已保存的定仓方式。 */
    RiskConfiguration forNewTask(boolean ema) {
        Preset selected = preset == null ? Preset.STOP_RISK : preset;
        if (ema) {
            if ((version != null && !usesAtr()) || stopLossRatio != null) {
                throw new IllegalArgumentException("新 EMA 回测请使用 ATR 止损参数");
            }
            // EMA 继续接受旧客户端的 v2 字段，名义上限仅由历史快照应用。
            return new RiskConfiguration(3, selected, allocation, null, rewardRiskRatio, null,
                    maxPositionRiskRatio, atrPeriod, atrMultiplier, minVolatilityRatio).normalized();
        }
        return new RiskConfiguration(version, selected, allocation, stopLossRatio, rewardRiskRatio, maxNotionalRatio,
                maxPositionRiskRatio, atrPeriod, atrMultiplier, minVolatilityRatio).normalized();
    }

    boolean usesAtr() {
        return Integer.valueOf(2).equals(version) || Integer.valueOf(3).equals(version);
    }

    /** 补齐默认值并校验，提交与执行共用同一规则。 */
    public RiskConfiguration normalized() {
        if (version != null && version != 1 && !usesAtr()) throw new IllegalArgumentException("不支持的风控版本");
        if (preset == null) throw new IllegalArgumentException("请选择风控方案");
        double amount = allocation == null ? (preset == Preset.NOTIONAL_RATIO ? 0.1 : 0.01) : allocation;
        double reward = rewardRiskRatio == null ? 2 : rewardRiskRatio;
        boolean emaVolatility = Integer.valueOf(3).equals(version);
        Double maximum = emaVolatility ? null : (maxNotionalRatio == null ? Double.valueOf(1) : maxNotionalRatio);
        double riskCap = maxPositionRiskRatio == null ? 0.05 : maxPositionRiskRatio;
        requireRange(amount, 0, preset == Preset.NOTIONAL_RATIO ? 100 : 0.1, "定仓比例");
        requireRange(reward, 0, 100, "盈亏比");
        if (maximum != null) requireRange(maximum, 0, 100, "最大名义仓位比例");
        requireRange(riskCap, 0, 1, "累计止损风险比例");
        if (minVolatilityRatio != null && !emaVolatility) throw new IllegalArgumentException("当前风控版本不支持波动过滤");
        if (usesAtr()) {
            if (stopLossRatio != null) throw new IllegalArgumentException("ATR 止损不能同时配置止损比例");
            int period = atrPeriod == null ? 20 : atrPeriod;
            double multiplier = atrMultiplier == null ? 2 : atrMultiplier;
            if (period < 2 || period > 500) throw new IllegalArgumentException("ATR 周期必须在 2..500 内");
            requireRange(multiplier, 0, 100, "ATR 倍数");
            Double minimum = minVolatilityRatio;
            if (emaVolatility) {
                if (maxNotionalRatio != null) throw new IllegalArgumentException("EMA 风控 v3 不设置名义仓位上限");
                if (minimum == null) minimum = 0.6;
                if (!Double.isFinite(minimum) || minimum < 0 || minimum > 1) {
                    throw new IllegalArgumentException("最低波动比须在 [0, 1] 内");
                }
            }
            return new RiskConfiguration(version, preset, amount, null, reward, maximum, riskCap, period, multiplier, minimum);
        }
        if (atrPeriod != null || atrMultiplier != null) throw new IllegalArgumentException("当前风控不支持 ATR 止损参数");
        double stop = stopLossRatio == null ? 0.02 : stopLossRatio;
        requireRange(stop, 0, 0.5, "止损比例");
        if (stop * reward >= 1) throw new IllegalArgumentException("止损比例乘盈亏比须小于 1，以保证多空保护价格有效");
        return new RiskConfiguration(1, preset, amount, stop, reward, maximum, riskCap);
    }

    private static void requireRange(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value <= minimum || value > maximum) {
            throw new IllegalArgumentException(name + "必须在 (" + minimum + ", " + maximum + "] 内");
        }
    }
}
