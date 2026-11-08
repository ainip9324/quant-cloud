package com.quant.common.model;

import com.quant.common.enums.PositionSide;
import lombok.Data;

/**
 * 账户模型（USDT 本位逐仓永续合约，支持多空双向，单向持仓）
 *
 * <p>逐仓语义：每个仓位的 {@link #positionMargin} 独立隔离，亏损上限为该仓位保证金，
 * 不波及 {@link #availableBalance}。强平时保证金全损，绝不穿仓。
 *
 * <p>权益公式：equity = availableBalance + positionMargin + unrealizedPnl
 *   其中 unrealizedPnl = position × (markPrice - avgEntryPrice) × directionSign(positionSide)
 *   （多头：数量×(当前价-均价)；空头：数量×(均价-当前价)）
 *
 * <p>单向持仓约束：position == 0 ⟺ positionSide == NONE；position > 0 ⟺ LONG 或 SHORT。
 * 数量始终为正数，方向由 {@link #positionSide} 表达。
 *
 * <p>引擎在执行 OPEN_LONG/OPEN_SHORT/ADD/CLOSE/LIQUIDATION 时自动维护这些字段，
 * 策略通过 StrategyContext 只读访问。
 */
@Data
public class Account {
    /** 可用保证金（开仓/加仓时扣减，平仓时返还） */
    private double availableBalance = 100000;
    /** 当前逐仓占用保证金（隔离，亏损上限，强平时全损） */
    private double positionMargin = 0;
    /** 持仓方向（NONE/LONG/SHORT，单向：同一时刻只允许一边） */
    private PositionSide positionSide = PositionSide.NONE;
    /** 持仓数量（始终为正数，方向由 positionSide 表达） */
    private double position = 0;
    /** 加权开仓均价（加仓按成交量加权更新） */
    private double avgEntryPrice = 0;
    /** 总权益 = availableBalance + positionMargin + unrealizedPnl */
    private double equity = 100000;

    /** 入场次数（开仓算1次，每次加仓+1，通用成交元数据） */
    private int entryCount = 0;
    /** 上次入场价（开仓/加仓的成交价，通用成交元数据） */
    private double lastEntryPrice = 0;
    /** 固定止损价（引擎检查触发，0 表示不设；空头止损在价格上方） */
    private double stopPrice = 0;
    /** 固定止盈价（引擎检查触发，0 表示不设；空头止盈在价格下方） */
    private double takeProfitPrice = 0;
    /** 当前持仓来源标识（引擎保存并在平仓/强平回报时携带，空串=无来源） */
    private String sourceId = "";

    /** 杠杆倍数（1~100，默认10） */
    private int leverage = 10;
    /** 维持保证金率（默认 0.5%，positionEquity ≤ position×markPrice×rate 时强平） */
    private double maintenanceMarginRate = 0.005;
}
