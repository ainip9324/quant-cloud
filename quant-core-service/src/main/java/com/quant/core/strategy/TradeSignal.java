package com.quant.core.strategy;

/** 策略只表达交易动作和方向，数量及保护价格由风控生成。 */
public enum TradeSignal {
    HOLD, OPEN_LONG, OPEN_SHORT, ADD, CLOSE
}
