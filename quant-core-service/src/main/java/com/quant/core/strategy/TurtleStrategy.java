package com.quant.core.strategy;

import com.quant.common.enums.PositionSide;
import com.quant.common.model.Kline;
import com.quant.common.model.TradePlan;
import com.quant.core.indicator.IndicatorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 海龟交易策略（经典版，多空镜像）— onBarPlan 自管理风控
 *
 * <p>核心规则：
 * <ul>
 *   <li>入场：收盘价突破过去 ENTRY_PERIOD 根最高价 → 开多；跌破最低价 → 开空</li>
 *   <li>加仓：开仓后每朝有利方向走 0.5 ATR 加仓一次（最多 MAX_ADD_COUNT 次）</li>
 *   <li>止损：多头收盘跌破 entryPrice - 2 ATR / 空头收盘涨破 entryPrice + 2 ATR → 止损平仓（动态，每根重算）</li>
 *   <li>出场：多头跌破 EXIT_PERIOD 最低价 / 空头突破 EXIT_PERIOD 最高价 → 信号平仓</li>
 * </ul>
 *
 * <p>决策优先级：无仓位先判开多再判开空；有仓位先判退出再判加仓。
 * 同根 K线同时触及上下突破（极端行情）→ HOLD + debug 日志，避免行为随 if 顺序变化。
 *
 * <p>风控参数（海龟专属，本策略私有）：
 * ATR_PERIOD=20, RISK_PER_TRADE=0.01, MAX_ADD_COUNT=4, ADD_SPACING_ATR=0.5, STOP_LOSS_ATR=2.0
 *
 * <p>仓位（B 方案）：RISK_PER_PRICE_UNIT 模式 + CASH 基数，amount = 余额 × 1% / (2×ATR)，
 * 止损触发时单笔亏损约 1%（止损距离 2×ATR 放入分母，与 RISK_PER_TRADE 命名语义一致）。
 * 注意：止损线基于首次 entryPrice，加仓不移动止损，故多次加仓后总亏损会大于单笔风险。
 *
 * <p>并发安全：无实例可变字段，所有计算基于 StrategyContext（每次回测独立）。
 */
@Slf4j
@Component
public class TurtleStrategy implements Strategy {

    /** 入场周期：突破过去 20 根K线的最高价 */
    private static final int ENTRY_PERIOD = 20;

    /** 出场周期：跌破过去 10 根K线的最低价 */
    private static final int EXIT_PERIOD = 10;

    /** ATR 计算周期 */
    private static final int ATR_PERIOD = 20;

    /** 单笔风险比例（每次交易只冒 1% 的风险） */
    private static final double RISK_PER_TRADE = 0.01;

    /** 最大入场次数（开仓 + 加仓最多 4 次） */
    private static final int MAX_ADD_COUNT = 4;

    /** 加仓间距（每上涨 0.5 ATR 加仓） */
    private static final double ADD_SPACING_ATR = 0.5;

    /** 止损 ATR 倍数（跌破 2 ATR 止损） */
    private static final double STOP_LOSS_ATR = 2.0;

    @Override
    public String getId() {
        return "turtle";
    }

    @Override
    public int getMinPeriod() {
        return ENTRY_PERIOD + 1;
    }

    /**
     * 海龟策略交易计划（自管理 ATR 控仓/加仓/动态止损，多空镜像）
     *
     * <p>策略读取 ctx 的持仓状态（positionSide/entryPrice/entryCount/lastEntryPrice）做风控判断，
     * 返回 TradePlan 指导引擎执行。止损由策略自行判断（不设固定 stopPrice），
     * 因为海龟止损是动态的（每根 ATR 变化）。
     */
    @Override
    public TradePlan onBarPlan(StrategyContext ctx, Kline bar) {
        if (ctx.size() < ENTRY_PERIOD + 1) {
            return TradePlan.hold();
        }

        double close = bar.getClose();
        double atr = IndicatorUtils.calcATR(ctx.history(), ATR_PERIOD);
        if (atr <= 0) {
            return TradePlan.hold();
        }

        PositionSide side = ctx.getPositionSide();

        // ===== 无仓位：入场（先判开多突破，再判开空突破）=====
        if (side == PositionSide.NONE) {
            double highest = highestHigh(ctx.lookBack(ENTRY_PERIOD));
            double lowest = lowestLow(ctx.lookBack(ENTRY_PERIOD));
            boolean buyBreakout = close > highest;   // 突破上轨 → 开多
            boolean sellBreakout = close < lowest;   // 跌破下轨 → 开空

            // 极端行情同根同时触及上下突破：保守 HOLD，避免行为随 if 顺序变化 //比如跳空低开再收高
            if (buyBreakout && sellBreakout) {
                log.debug("多空突破同时触发，HOLD: close={}, highest={}, lowest={}", close, highest, lowest);
                return TradePlan.hold();
            }
            if (buyBreakout) {
                double riskDistance = STOP_LOSS_ATR * atr;
                return TradePlan.openLongRisk(RISK_PER_TRADE, riskDistance, TradePlan.SizingBase.CASH);
            }
            if (sellBreakout) {
                double riskDistance = STOP_LOSS_ATR * atr;
                return TradePlan.openShortRisk(RISK_PER_TRADE, riskDistance, TradePlan.SizingBase.CASH);
            }
            return TradePlan.hold();
        }

        // ===== 多头持仓：先判退出（止损→通道），再判加仓 =====
        if (side == PositionSide.LONG) {
            double lowest = lowestLow(ctx.lookBack(EXIT_PERIOD));
            double stopPrice = ctx.getEntryPrice() - STOP_LOSS_ATR * atr;

            // ① ATR 动态止损（每根重算止损价，风控最高优先级）
            if (close <= stopPrice) {
                return TradePlan.closeStop();
            }
            // ② 信号平仓（跌破 EXIT_PERIOD 最低价；先退出后加仓，同根K线退出与加仓同时满足时退出优先）
            if (close < lowest) {
                return TradePlan.close();
            }
            // ③ 加仓（涨 0.5 ATR 且未封顶）
            if (ctx.getEntryCount() < MAX_ADD_COUNT
                    && close >= ctx.getLastEntryPrice() + ADD_SPACING_ATR * atr) {
                double riskDistance = STOP_LOSS_ATR * atr;
                return TradePlan.addRisk(RISK_PER_TRADE, riskDistance, TradePlan.SizingBase.CASH);
            }
            return TradePlan.hold();
        }

        // ===== 空头持仓：镜像（先判退出（止损→通道），再判加仓）=====
        double highest = highestHigh(ctx.lookBack(EXIT_PERIOD));
        double stopPrice = ctx.getEntryPrice() + STOP_LOSS_ATR * atr;

        // ① ATR 动态止损（空头：涨破 entryPrice + 2ATR 止损，风控最高优先级）
        if (close >= stopPrice) {
            return TradePlan.closeStop();
        }
        // ② 信号平仓（突破 EXIT_PERIOD 最高价；先退出后加仓，同根K线退出与加仓同时满足时退出优先）
        if (close > highest) {
            return TradePlan.close();
        }
        // ③ 加仓（跌 0.5 ATR 且未封顶）
        if (ctx.getEntryCount() < MAX_ADD_COUNT
                && close <= ctx.getLastEntryPrice() - ADD_SPACING_ATR * atr) {
            double riskDistance = STOP_LOSS_ATR * atr;
            return TradePlan.addRisk(RISK_PER_TRADE, riskDistance, TradePlan.SizingBase.CASH);
        }
        return TradePlan.hold();
    }

    /** 过去 n 根的最高价 */
    private double highestHigh(List<Kline> list) {
        return list.stream().mapToDouble(Kline::getHigh).max().orElse(0);
    }

    /** 过去 n 根的最低价 */
    private double lowestLow(List<Kline> list) {
        return list.stream().mapToDouble(Kline::getLow).min().orElse(Double.MAX_VALUE);
    }
}
