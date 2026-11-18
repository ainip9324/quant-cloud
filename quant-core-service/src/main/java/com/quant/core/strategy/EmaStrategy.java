package com.quant.core.strategy;

import com.quant.common.enums.PositionSide;
import com.quant.common.model.Kline;
import com.quant.common.model.ExecutionEvent;
import com.quant.common.model.StrategyEvent;
import com.quant.common.model.TradePlan;
import com.quant.core.engine.SignalRunConfiguration;
import com.quant.core.indicator.IndicatorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * EMA20 均线策略（单向多空）
 *
 * <p>基础收盘交叉规则：
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
 * <p>信号 v2 增加双向回踩和 1R 信号退出门槛。策略只产生交易动作，仓位和保护价由风控处理。
 * 以下参数仅用于没有 execution 快照的兼容任务，通过 {@link TradePlan} 交引擎执行：
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
public class EmaStrategy implements Strategy, SignalStrategy {

    private static final int DEFAULT_EMA_PERIOD = 20;
    private static final String CROSS_SIGNAL_VERSION = "ema20-signal-v1";
    public static final String RETEST_SIGNAL_VERSION = "ema20-signal-v2";
    private static final String RETEST_ACTIVE = "emaRetestActive";
    private static final String INITIAL_RISK = "emaInitialRisk";
    private static final String EXIT_UNLOCKED = "emaExitUnlocked";

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
        // 旧任务继续按原参数生成计划，新执行流程直接调用 onSignal。
        double positionRatio = ctx.getParam("positionRatio", 0.01);
        double stopPoints = ctx.getParam("stopPoints", 100.0);
        double takeProfitPoints = ctx.getParam("takeProfitPoints", 100.0);
        double price = bar.getClose();
        return switch (onSignal(ctx, bar).action()) {
            case OPEN_LONG -> TradePlan.openLong(positionRatio, price - stopPoints, price + takeProfitPoints);
            case OPEN_SHORT -> TradePlan.openShort(positionRatio, price + stopPoints, price - takeProfitPoints);
            case CLOSE -> TradePlan.close();
            default -> TradePlan.hold();
        };
    }

    /** 信号算法版本随任务保存，防止后续升级静默改变历史解释。 */
    @Override
    public String signalVersion() { return RETEST_SIGNAL_VERSION; }

    @Override
    public boolean supportsSignalVersion(String version) {
        return CROSS_SIGNAL_VERSION.equals(version) || RETEST_SIGNAL_VERSION.equals(version);
    }

    /** 依据任务快照启用新版规则，旧任务仍按原交叉逻辑执行。 */
    @Override
    public void initialize(StrategyContext ctx) {
        Object snapshot = ctx.getParam("execution", null);
        if (snapshot == null) return;
        var execution = SignalRunConfiguration.from(Map.of("execution", snapshot), this);
        ctx.putState(RETEST_ACTIVE, RETEST_SIGNAL_VERSION.equals(execution.strategyVersion()));
    }

    /** 每笔真实开仓固定初始风险距离，任何成交退出都清除门槛状态。 */
    @Override
    public void onExecution(StrategyContext ctx, ExecutionEvent event) {
        if (!ctx.getState(RETEST_ACTIVE, false)) return;
        double risk = event.getEventType() == ExecutionEvent.EventType.OPEN
                ? Math.abs(event.getPrice() - ctx.getStopPrice()) : 0;
        ctx.putState(INITIAL_RISK, risk);
        ctx.putState(EXIT_UNLOCKED, false);
    }

    /** 新任务冻结 EMA 周期，拒绝小数截断和非法参数类型。 */
    @Override
    public void normalizeSignalParameters(Map<String, Object> params) {
        Object period = params.getOrDefault("emaPeriod", DEFAULT_EMA_PERIOD);
        if (!(period instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue() || number.intValue() < 2 || number.intValue() > 500) {
            throw new IllegalArgumentException("emaPeriod 必须是 2..500 的整数");
        }
        params.put("emaPeriod", ((Number) period).intValue());
    }

    /** 按收盘确认穿越、回踩或离场候选。 */
    @Override
    public SignalDecision onSignal(StrategyContext ctx, Kline bar) {
        return SignalDecision.of(crossover(ctx, bar));
    }

    /** 新旧版本共享交叉判断；回踩和离场门槛仅应用于信号 v2。 */
    private TradeSignal crossover(StrategyContext ctx, Kline bar) {
        int emaPeriod = ctx.getParam("emaPeriod", DEFAULT_EMA_PERIOD);

        // 数据不足，不交易
        if (ctx.size() < emaPeriod + 2) {
            return TradeSignal.HOLD;
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

        boolean retestActive = ctx.getState(RETEST_ACTIVE, false);
        PositionSide side = ctx.getPositionSide();
        if (retestActive && side != PositionSide.NONE) {
            return gatedExit(ctx, bar, side, side == PositionSide.LONG ? crossDown : crossUp);
        }
        switch (side) {
            case NONE:
                if (crossUp) {
                    return TradeSignal.OPEN_LONG;
                }
                if (crossDown) {
                    // 下穿开空；仓位和保护价格交由对应执行版本处理。
                    return TradeSignal.OPEN_SHORT;
                }
                if (retestActive) {
                    if (prevClose > prevEma && bar.getLow() < currEma && currClose > currEma) {
                        return retest(ctx, bar, currEma, PositionSide.LONG);
                    }
                    if (prevClose < prevEma && bar.getHigh() > currEma && currClose < currEma) {
                        return retest(ctx, bar, currEma, PositionSide.SHORT);
                    }
                }
                return TradeSignal.HOLD;
            case LONG:
                // 下穿离场（平多，引擎映射为 SELL）
                if (crossDown) {
                    return TradeSignal.CLOSE;
                }
                return TradeSignal.HOLD;
            case SHORT:
                // 上穿离场（平空，引擎映射为 COVER）
                if (crossUp) {
                    return TradeSignal.CLOSE;
                }
                return TradeSignal.HOLD;
            default:
                return TradeSignal.HOLD;
        }
    }

    /** 记录回踩候选，后续仍由引擎执行入场过滤和风控。 */
    private TradeSignal retest(StrategyContext ctx, Kline bar, double ema, PositionSide side) {
        ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_DECISION, "EMA_RETEST_CANDIDATE", null, side,
                bar.getClose(), "EMA 回踩收回，产生入场候选", Map.of("ema", ema));
        return side == PositionSide.LONG ? TradeSignal.OPEN_LONG : TradeSignal.OPEN_SHORT;
    }

    /** 首次收盘达到 1R 后，只接受其后新出现的反向穿越。 */
    private TradeSignal gatedExit(StrategyContext ctx, Kline bar, PositionSide side, boolean reverseCross) {
        if (ctx.getState(EXIT_UNLOCKED, false)) return reverseCross ? TradeSignal.CLOSE : TradeSignal.HOLD;
        double risk = ctx.getState(INITIAL_RISK, 0.0);
        double entry = ctx.getAvgEntryPrice();
        double target = side == PositionSide.LONG ? entry + risk : entry - risk;
        boolean reached = risk > 0 && (side == PositionSide.LONG ? bar.getClose() >= target : bar.getClose() <= target);
        if (reached) {
            ctx.putState(EXIT_UNLOCKED, true);
            ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_DECISION, "EMA_EXIT_UNLOCKED", null, side,
                    bar.getClose(), "收盘达到 1R，后续反向穿越可平仓", Map.of("unlockPrice", target));
        }
        if (reverseCross) {
            ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_DECISION, "EMA_EXIT_BLOCKED", null, side,
                    bar.getClose(), "反向穿越发生在信号退出解锁前，继续持仓", Map.of("unlockPrice", target));
        }
        return TradeSignal.HOLD;
    }
}
