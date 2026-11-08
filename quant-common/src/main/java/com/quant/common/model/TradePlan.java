package com.quant.common.model;

import lombok.Data;

/**
 * 交易计划（策略输出，引擎据此执行）
 *
 * <p>设计目的：将<b>风控决策权</b>（仓位大小、止损止盈价、平仓原因）下放给策略，
 * 引擎只负责"按计划执行 + 记账"。这样不同策略可自定义风控，引擎保持通用，加新策略不改引擎。
 *
 * <p>使用方式：Strategy 实现 {@code onBarPlan} 返回 TradePlan，引擎走 executePlan 统一执行。
 *
 * <p>当前支持单向多空（OPEN_LONG / OPEN_SHORT），同一账户同一时刻只允许持有一边仓位。
 */
@Data
public class TradePlan {

    /** 计划动作 */
    public enum Action {
        /** 开多仓 */
        OPEN_LONG,
        /** 开空仓 */
        OPEN_SHORT,
        /** 加仓（累加持仓，多空通用：方向由当前持仓决定） */
        ADD,
        /** 平仓（多空通用：方向由当前持仓决定） */
        CLOSE,
        /** 无动作（引擎仍会检查现有持仓的止损止盈） */
        HOLD
    }

    /** 仓位基数：决定 amount 计算用余额还是权益 */
    public enum SizingBase {
        /** 按可用余额（现金）计算 */
        CASH,
        /** 按总权益计算 */
        EQUITY
    }

    /**
     * 仓位计算模式：决定 amount 的计算公式
     * <ul>
     *   <li>{@link #NOTIONAL_RATIO}：名义仓位比例，amount = base × positionRatio / price
     *       —— positionRatio 是"仓位价值占 base 的比例"，如 0.01 = 用 1% 的钱建仓</li>
     *   <li>{@link #RISK_PER_PRICE_UNIT}：风险单位，amount = base × riskRate / riskDistance
     *       —— riskRate 是"价格波动 riskDistance 时的亏损占 base 的比例"，如 0.01 + riskDistance=2×ATR
     *       表示止损触发时亏损约 1%。适合 ATR 控仓的海龟类策略</li>
     * </ul>
     */
    public enum SizingMode {
        /** 名义仓位比例：amount = base × positionRatio / price */
        NOTIONAL_RATIO,
        /** 风险单位：amount = base × riskRate / riskDistance */
        RISK_PER_PRICE_UNIT
    }

    /** 平仓原因（CLOSE 动作用），引擎据此映射交易类型 */
    public enum CloseType {
        /** 信号平仓 → 交易类型 "SELL" */
        SIGNAL,
        /** 止损平仓 → 交易类型 "STOP" */
        STOP
    }

    /** 动作 */
    private Action action;
    /** 仓位计算模式，默认名义仓位比例（NOTIONAL_RATIO） */
    private SizingMode sizingMode = SizingMode.NOTIONAL_RATIO;
    /** 仓位比例（NOTIONAL_RATIO 模式用），如 0.01 = 仓位价值占 base 的 1% */
    private double positionRatio;
    /** 风险比例（RISK_PER_PRICE_UNIT 模式用），如 0.01 = 价格波动 riskDistance 时亏 1% base */
    private double riskRate;
    /** 风险距离（RISK_PER_PRICE_UNIT 模式用，价格单位），如 2×ATR */
    private double riskDistance;
    /** 仓位基数（OPEN_LONG/ADD 用），默认 EQUITY */
    private SizingBase sizingBase = SizingBase.EQUITY;
    /** 固定止损价（OPEN_LONG 用，0 表示不设固定止损，由策略自行判断） */
    private double stopPrice;
    /** 固定止盈价（OPEN_LONG 用，0 表示不设止盈） */
    private double takeProfitPrice;
    /** 平仓原因（CLOSE 用），默认 SIGNAL */
    private CloseType closeType = CloseType.SIGNAL;
    /**
     * 计划成交价（null = 保持旧行为，按当前 K线收盘价成交）。
     * 非 null = 仅当该价格位于当前 K线 [low, high] 内时按该价成交（限价语义）。
     */
    private Double entryPrice;
    /** 来源标识（引擎保存为持仓元数据，平仓/强平回报时携带） */
    private String sourceId;

    /** 无动作（引擎仍会检查现有持仓的止损止盈） */
    public static TradePlan hold() {
        TradePlan p = new TradePlan();
        p.action = Action.HOLD;
        return p;
    }

    /** 开多仓（默认 EQUITY 基数） */
    public static TradePlan openLong(double positionRatio, double stopPrice, double takeProfitPrice) {
        return openLong(positionRatio, stopPrice, takeProfitPrice, SizingBase.EQUITY);
    }

    /** 开多仓（指定仓位基数） */
    public static TradePlan openLong(double positionRatio, double stopPrice, double takeProfitPrice, SizingBase sizingBase) {
        TradePlan p = new TradePlan();
        p.action = Action.OPEN_LONG;
        p.positionRatio = positionRatio;
        p.stopPrice = stopPrice;
        p.takeProfitPrice = takeProfitPrice;
        p.sizingBase = sizingBase;
        return p;
    }

    /** 开空仓（默认 EQUITY 基数）。注意：空头止损价在上方(stopPrice>入场价)，止盈价在下方(takeProfitPrice<入场价) */
    public static TradePlan openShort(double positionRatio, double stopPrice, double takeProfitPrice) {
        return openShort(positionRatio, stopPrice, takeProfitPrice, SizingBase.EQUITY);
    }

    /** 开空仓（指定仓位基数） */
    public static TradePlan openShort(double positionRatio, double stopPrice, double takeProfitPrice, SizingBase sizingBase) {
        TradePlan p = new TradePlan();
        p.action = Action.OPEN_SHORT;
        p.positionRatio = positionRatio;
        p.stopPrice = stopPrice;
        p.takeProfitPrice = takeProfitPrice;
        p.sizingBase = sizingBase;
        return p;
    }

    /** 加仓（默认 EQUITY 基数） */
    public static TradePlan add(double positionRatio) {
        return add(positionRatio, SizingBase.EQUITY);
    }

    /** 加仓（指定仓位基数） */
    public static TradePlan add(double positionRatio, SizingBase sizingBase) {
        TradePlan p = new TradePlan();
        p.action = Action.ADD;
        p.positionRatio = positionRatio;
        p.sizingBase = sizingBase;
        return p;
    }

    // ===== 风险单位模式（RISK_PER_PRICE_UNIT）：海龟类策略用 =====

    /**
     * 开多仓（风险单位模式）：amount = base × riskRate / riskDistance
     *
     * @param riskRate     风险比例，如 0.01 = 止损触发时亏损约 1% base
     * @param riskDistance 风险距离（价格单位），如 2×ATR（止损距离）
     * @param sizingBase   仓位基数（CASH/EQUITY）
     */
    public static TradePlan openLongRisk(double riskRate, double riskDistance, SizingBase sizingBase) {
        TradePlan p = new TradePlan();
        p.action = Action.OPEN_LONG;
        p.sizingMode = SizingMode.RISK_PER_PRICE_UNIT;
        p.riskRate = riskRate;
        p.riskDistance = riskDistance;
        p.sizingBase = sizingBase;
        return p;
    }

    /**
     * 开空仓（风险单位模式）：amount = base × riskRate / riskDistance
     * 空头止损距离同样为 riskDistance（止损价 = 入场价 + riskDistance）。
     */
    public static TradePlan openShortRisk(double riskRate, double riskDistance, SizingBase sizingBase) {
        TradePlan p = new TradePlan();
        p.action = Action.OPEN_SHORT;
        p.sizingMode = SizingMode.RISK_PER_PRICE_UNIT;
        p.riskRate = riskRate;
        p.riskDistance = riskDistance;
        p.sizingBase = sizingBase;
        return p;
    }

    // 风险单位模式下指定计划成交价及信号来源。

    /**
     * 开多仓（风险单位模式，支持计划成交价、固定止损止盈与来源标识）。
     *
     * <p>amount = base × riskRate / riskDistance。
     * entryPrice 非 null 时引擎仅当该价位于当前 K线 [low,high] 内才按该价成交。
     *
     * @param riskRate           风险比例，如 0.01
     * @param riskDistance       风险距离（价格单位）
     * @param entryPrice         计划成交价（limit 语义，非 null）
     * @param stopPrice          固定止损价（多头在下方）
     * @param takeProfitPrice    固定止盈价（多头在上方）
     * @param sourceId           信号来源标识
     * @param sizingBase         仓位基数（CASH/EQUITY）
     */
    public static TradePlan openLongRisk(double riskRate, double riskDistance,
                                         double entryPrice, double stopPrice, double takeProfitPrice,
                                         String sourceId, SizingBase sizingBase) {
        TradePlan p = openLongRisk(riskRate, riskDistance, sizingBase);
        p.entryPrice = entryPrice;
        p.stopPrice = stopPrice;
        p.takeProfitPrice = takeProfitPrice;
        p.sourceId = sourceId;
        return p;
    }

    /**
     * 开空仓（风险单位模式，支持计划成交价、固定止损止盈与来源标识）。
     * 空头止损价在上方、止盈价在下方，与多头对称。
     */
    public static TradePlan openShortRisk(double riskRate, double riskDistance,
                                          double entryPrice, double stopPrice, double takeProfitPrice,
                                          String sourceId, SizingBase sizingBase) {
        TradePlan p = openShortRisk(riskRate, riskDistance, sizingBase);
        p.entryPrice = entryPrice;
        p.stopPrice = stopPrice;
        p.takeProfitPrice = takeProfitPrice;
        p.sourceId = sourceId;
        return p;
    }

    /** 加仓（风险单位模式）：amount = base × riskRate / riskDistance */
    public static TradePlan addRisk(double riskRate, double riskDistance, SizingBase sizingBase) {
        TradePlan p = new TradePlan();
        p.action = Action.ADD;
        p.sizingMode = SizingMode.RISK_PER_PRICE_UNIT;
        p.riskRate = riskRate;
        p.riskDistance = riskDistance;
        p.sizingBase = sizingBase;
        return p;
    }

    /** 信号平仓（closeType=SIGNAL → "SELL"） */
    public static TradePlan close() {
        TradePlan p = new TradePlan();
        p.action = Action.CLOSE;
        p.closeType = CloseType.SIGNAL;
        return p;
    }

    /** 止损平仓（closeType=STOP → "STOP"） */
    public static TradePlan closeStop() {
        TradePlan p = new TradePlan();
        p.action = Action.CLOSE;
        p.closeType = CloseType.STOP;
        return p;
    }
}
