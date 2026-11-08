package com.quant.core.strategy;

import com.quant.common.enums.PositionSide;
import com.quant.common.model.Kline;
import com.quant.common.model.TradePlan;
import com.quant.core.indicator.IndicatorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * EMA20 均线策略（单向多空）
 *
 * <p>交易规则（状态机，复用引擎的 OPEN_SHORT / PositionSide 能力）：
 * <ul>
 *   <li>价格<b>上穿</b> EMA20（前收 ≤ 前EMA，当前收 &gt; 当前EMA）→ crossUp</li>
 *   <li>价格<b>下穿</b> EMA20（前收 ≥ 前EMA，当前收 &lt; 当前EMA）→ crossDown</li>
 *   <li>空仓（NONE）：crossUp → 开多；crossDown → 开空</li>
 *   <li>多头（LONG）：crossDown → 平多（引擎映射为 SELL）；其他 → HOLD</li>
 *   <li>空头（SHORT）：crossUp → 平空（引擎映射为 COVER）；其他 → HOLD</li>
 * </ul>
 *
 * <p>不做同根自动反手：交叉信号只会先平当前仓位，下一根 K 线若仍满足反向条件才允许新开仓，
 * 与引擎"强平/止损/止盈后同根不反手"和单向逐仓模型一致。
 * 实际上交叉信号是单根事件（需要前一根在 EMA 另一侧），平仓后的下一根天然不会重复触发反向开仓。
 *
 * <p>风控（通过 {@link TradePlan} 交引擎执行，多空对称）：
 * <ul>
 *   <li>仓位：总权益的 1%（EQUITY 基数，amount = 权益 × 0.01 / 价格）</li>
 *   <li>多头：止损 开仓价-100，止盈 开仓价+100</li>
 *   <li>空头：止损 开仓价+100（在上方），止盈 开仓价-100（在下方）</li>
 *   <li>持仓期间由引擎检查固定止损止盈（不依赖信号）</li>
 * </ul>
 *
 * <p>参数（可选，通过 BacktestTask.params 传入，均有默认值）：
 * emaPeriod=20, positionRatio=0.01, stopPoints=100, takeProfitPoints=100
 */
@Slf4j
@Component
public class EmaStrategy implements Strategy {

    private static final int DEFAULT_EMA_PERIOD = 20;

    @Override
    public String getId() {
        return "ema20";
    }

    @Override
    public int getMinPeriod() {
        // EMA 周期 + 2（需前一根 EMA 做交叉判断）
        return DEFAULT_EMA_PERIOD + 2;
    }

    @Override
    public TradePlan onBarPlan(StrategyContext ctx, Kline bar) {
        // 参数（支持外部传入，默认 ema20 / 1% 仓位 / 100点止损止盈）
        int emaPeriod = ctx.getParam("emaPeriod", DEFAULT_EMA_PERIOD);
        double positionRatio = ctx.getParam("positionRatio", 0.01);
        double stopPoints = ctx.getParam("stopPoints", 100.0);
        double takeProfitPoints = ctx.getParam("takeProfitPoints", 100.0);

        // 数据不足，不交易
        if (ctx.size() < emaPeriod + 2) {
            return TradePlan.hold();
        }

        List<Kline> history = ctx.history();
        int n = history.size();

        // 当前 EMA（含当前 bar）与 前一根 EMA（不含当前 bar）
        double currEma = IndicatorUtils.calcEMA(history, emaPeriod);
        double prevEma = IndicatorUtils.calcEMA(history.subList(0, n - 1), emaPeriod);

        double currClose = bar.getClose();
        double prevClose = history.get(n - 2).getClose();

        // 上穿：前收在前EMA之下（或贴着），当前收站到当前EMA之上
        boolean crossUp = prevClose <= prevEma && currClose > currEma;
        // 下穿：前收在前EMA之上（或贴着），当前收跌到当前EMA之下
        boolean crossDown = prevClose >= prevEma && currClose < currEma;

        // 单向状态机：空仓按交叉开多/开空；持仓仅在反向交叉时平仓，不做同根反手
        PositionSide side = ctx.getPositionSide();
        switch (side) {
            case NONE:
                if (crossUp) {
                    // 开多：仓位1%，止损开仓价-100，止盈开仓价+100
                    return TradePlan.openLong(positionRatio, currClose - stopPoints, currClose + takeProfitPoints);
                }
                if (crossDown) {
                    // 开空：仓位1%，止损开仓价+100（在上方），止盈开仓价-100（在下方）
                    return TradePlan.openShort(positionRatio, currClose + stopPoints, currClose - takeProfitPoints);
                }
                return TradePlan.hold();
            case LONG:
                // 下穿离场（平多，引擎映射为 SELL）
                if (crossDown) {
                    return TradePlan.close();
                }
                return TradePlan.hold();
            case SHORT:
                // 上穿离场（平空，引擎映射为 COVER）
                if (crossUp) {
                    return TradePlan.close();
                }
                return TradePlan.hold();
            default:
                return TradePlan.hold();
        }
    }
}
