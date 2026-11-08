package com.quant.common.enums;

/**
 * 持仓方向（单向逐仓模式：同一账户同一时刻只允许持有一边仓位）
 *
 * <p>约束：position == 0 ⟺ NONE；position > 0 ⟺ LONG 或 SHORT。
 * 仓位数量始终存正数，方向由本枚举表达，不使用正负数量混合表达方向。
 */
public enum PositionSide {
    /** 无持仓 */
    NONE,
    /** 多头 */
    LONG,
    /** 空头 */
    SHORT
}
