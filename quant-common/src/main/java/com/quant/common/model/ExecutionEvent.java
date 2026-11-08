package com.quant.common.model;

import com.quant.common.enums.PositionSide;
import lombok.Data;

/**
 * 通用执行回报（引擎在交易真正执行成功后回调给策略）
 *
 * <p>用途：让策略依赖真实成交结果而非账户状态猜测。
 * 策略可以用来源标识关联对应的成交回报。
 */
@Data
public class ExecutionEvent {

    /** 事件类型 */
    public enum EventType {
        /** 开仓（BUY / SHORT_SELL） */
        OPEN,
        /** 固定止损平仓（STOP / SHORT_STOP） */
        STOP,
        /** 固定止盈平仓（TP / SHORT_TP） */
        TAKE_PROFIT,
        /** 信号平仓（SELL / COVER） */
        CLOSE,
        /** 强平（LIQUIDATION / SHORT_LIQUIDATION） */
        LIQUIDATION
    }

    /** 事件发生时间（毫秒时间戳） */
    private Long time;
    /** 事件类型 */
    private EventType eventType;
    /** 成交时持仓方向 */
    private PositionSide positionSide;
    /** 成交价格 */
    private double price;
    /** 成交数量 */
    private double amount;
    /** 已实现盈亏（开仓时为 0） */
    private double realizedPnl;
    /** 来源标识（开仓时保存的持仓来源） */
    private String sourceId;
}
