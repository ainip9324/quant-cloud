package com.quant.common.model;

import com.quant.common.enums.PositionSide;
import lombok.Data;

/**
 * 交易记录模型（USDT 本位逐仓永续合约）
 *
 * <p>每笔成交记录成交价、数量，以及成交后的账户快照（可用保证金/占用保证金/权益），
 * 并记录本次成交的保证金变动（margin，开仓+加仓为占用正值，平仓为释放负值）和手续费。
 *
 * <p>方向语义：type 描述动作（BUY/SELL/STOP 为多头，SHORT_SELL/COVER/SHORT_STOP 为空头），
 * positionSide 记录该笔交易发生时/后的持仓方向。旧任务无 positionSide 时前端按 LONG 兼容。
 */
@Data
public class Trade {
    /** 交易时间（毫秒时间戳） */
    private long time;
    /**
     * 交易类型：
     * <ul>
     *   <li>多头：BUY / ADD / SELL / STOP / TP / LIQUIDATION</li>
     *   <li>空头：SHORT_SELL / SHORT_ADD / COVER / SHORT_STOP / SHORT_TP / SHORT_LIQUIDATION</li>
     * </ul>
     */
    private String type;
    /** 持仓方向（LONG/SHORT，交易时点的方向；旧任务可能为 null） */
    private PositionSide positionSide;
    /** 成交价格 */
    private double price;
    /** 成交数量 */
    private double amount;
    /** 交易后可用保证金 */
    private double availableBalance;
    /** 本次保证金占用(+)或释放(-) */
    private double margin;
    /** 本次手续费 */
    private double fee;
    /** 交易后占用保证金 */
    private double positionMargin;
    /** 交易后权益（成交时点即时计算） */
    private double equity;
    /** 交易后加权开仓均价（平仓后为0） */
    private double avgEntryPrice;
    /** 来源标识（用于解释信号来源，无来源为 null/空） */
    private String sourceId;
}
