package com.quant.core.engine;

import com.quant.common.enums.PositionSide;
import com.quant.common.model.Account;
import com.quant.common.model.BacktestReplay;
import com.quant.common.model.BacktestResult;
import com.quant.common.model.BacktestRunOutput;
import com.quant.common.model.EquityPoint;
import com.quant.common.model.ExecutionEvent;
import com.quant.common.model.Kline;
import com.quant.common.model.StrategyEvent;
import com.quant.common.model.Trade;
import com.quant.common.model.TradePlan;
import com.quant.core.indicator.IndicatorUtils;
import com.quant.core.strategy.Strategy;
import com.quant.core.strategy.StrategyContext;
import com.quant.core.strategy.SignalStrategy;
import com.quant.core.strategy.TradeSignal;
import com.quant.core.strategy.SignalDecision;
import com.quant.core.zone.ChartObjectHistory;
import com.quant.core.util.IntervalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回测引擎（USDT 本位逐仓永续合约，单向多空）
 *
 * <p>职责边界（与 Strategy 严格分离）：
 * <ul>
 *   <li>SignalStrategy：闭合 K 线产生方向与动作，PositionRisk 在下一开盘生成定仓及保护价格</li>
 *   <li>旧任务 Strategy：继续产生原 TradePlan，保持历史参数和成交解释</li>
 *   <li>BacktestEngine：按 TradePlan 执行保证金记账、强平检查、手续费、收益计算</li>
 * </ul>
 *
 * <p>逐仓合约资金模型：
 * <ul>
 *   <li>开仓/加仓：扣 <b>保证金 + 手续费</b>（非完整名义价值），保证金 = 名义价值 / 杠杆</li>
 *   <li>逐仓隔离：每个仓位 positionMargin 独立，亏损上限为该保证金，不波及 availableBalance</li>
 *   <li>正常平仓：availableBalance += positionMargin + realizedPnl - closeFee（盈亏实现）</li>
 *   <li>强平：positionMargin 全损，availableBalance 不变（绝不穿仓）</li>
 *   <li>权益 = availableBalance + positionMargin + unrealizedPnl</li>
 *   <li>未实现盈亏带方向：unrealizedPnl = position × (markPrice - avgEntryPrice) × directionSign(side)</li>
 * </ul>
 *
 * <p>单向持仓：同一账户同一时刻只允许持有一边仓位（LONG 或 SHORT），
 * 数量始终为正数，方向由 Account.positionSide 表达。第一版不做自动反手，
 * 策略由多转空必须先平仓，下一根 K线才允许开反向仓。
 *
 * <p>旧任务每根 K线执行顺序（信号任务的开盘及盘中顺序见 executeSignalBar）：
 * <pre>① 强平检查（多头用 low / 空头用 high，margin 全损）→ ② 固定止损止盈 → ③ 策略计划</pre>
 * 强平/止损/止盈后本根 K线禁止重新开仓（liquidatedThisBar/closedThisBar），下一根才允许策略重新决策。
 *
 * <p>并发安全：run 方法内所有可变状态均为局部变量，多个回测并发调用互不干扰。
 */
@Slf4j
@Component
public class BacktestEngine {
    private static final int EMA_VOLATILITY_PERIOD = 20;
    private static final int EMA_VOLATILITY_BASELINE_PERIOD = 60;

    /** 手续费率 0.1%（按名义价值收取） */
    private static final double FEE_RATE = 0.001;

    /** 默认初始资金 */
    private static final double DEFAULT_INITIAL_CAPITAL = 100000;

    /** 默认杠杆 */
    private static final int DEFAULT_LEVERAGE = 10;

    // ===== 执行层拒绝原因（Week 7 结构化事件，供前端解释"为什么没交易"）=====
    /** 计划成交价不在当前K线 [low,high] 内（跳空/未触及） */
    public static final String REASON_PRICE_OUTSIDE_BAR = "PRICE_OUTSIDE_BAR";
    /** 计算出的开仓/加仓数量非法（非有限正数） */
    public static final String REASON_INVALID_AMOUNT = "INVALID_AMOUNT";
    /** 可用保证金 ≤ 0 */
    public static final String REASON_NO_AVAILABLE_BALANCE = "NO_AVAILABLE_BALANCE";
    /** 可用保证金不足以支付 保证金+手续费 */
    public static final String REASON_INSUFFICIENT_MARGIN = "INSUFFICIENT_MARGIN";
    /** 非法持仓状态（如已有持仓时重复开仓、无持仓时加仓/平仓） */
    public static final String REASON_ILLEGAL_POSITION_STATE = "ILLEGAL_POSITION_STATE";

    /**
     * 回测主入口（兼容入口：只返回结果摘要，不回放快照）
     *
     * @see #runWithReplay(List, Strategy, Map)
     */
    public BacktestResult run(List<Kline> klines, Strategy strategy, Map<String, Object> params) {
        return runWithReplay(klines, strategy, params).getSummary();
    }

    /**
     * 回测主入口（Week 7：返回结果摘要 + 回放快照）
     *
     * <p>Processor 使用本方法，成功后把 summary（result_summary）与 replay（replay_data）
     * 在同一条带 execution_token 条件的更新中原子落库。
     *
     * @param klines   完整 K线（时间升序，旧→新）
     * @param strategy 无状态策略单例
     * @param params   策略参数 + 回测配置（leverage / initialCapital），可空
     * @return 组合结果（summary + replay.bars + replay.events）
     */
    public BacktestRunOutput runWithReplay(List<Kline> klines, Strategy strategy, Map<String, Object> params) {
        SignalRunConfiguration execution = SignalRunConfiguration.from(params, strategy);
        if (execution != null && klines != null) {
            long lastOpen = execution.closedBefore() - IntervalUtils.toMillis(execution.interval());
            klines = klines.stream().filter(bar -> bar.getTime() <= lastOpen).toList();
        }
        ChartObjectHistory objects = execution != null && ((SignalStrategy) strategy).requiresChartObjects()
                ? new ChartObjectHistory(ChartObjectHistory.snapshot(params)) : null;
        if (objects != null) {
            long period = IntervalUtils.toMillis(execution.interval());
            if (klines == null || klines.stream().noneMatch(bar -> !objects.covering(bar.getTime(), bar.getTime() + period).isEmpty())) {
                throw new IllegalArgumentException("CHART_OBJECT_HISTORY_UNAVAILABLE: 实际行情窗口内没有历史可见的完整图表对象");
            }
        }
        if (klines == null || klines.isEmpty()) {
            log.warn("K线数据为空，返回空结果");
            return emptyOutput();
        }

        // ===== 参数读取与校验 =====
        int leverage = parseAndValidateLeverage(params);
        double initialCapital = parseAndValidateInitialCapital(params);

        // ===== 局部可变状态（并发隔离）=====
        Account account = new Account();
        account.setLeverage(leverage);
        account.setAvailableBalance(initialCapital);
        account.setEquity(initialCapital);
        List<Trade> trades = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>(klines.size());

        // 每次回测独立的 StrategyContext（并发安全关键），绑定账户供策略只读
        StrategyContext ctx = new StrategyContext(params);
        ctx.bindAccount(account);
        strategy.initialize(ctx);

        int minPeriod = strategy.getMinPeriod();
        SignalDecision pending = SignalDecision.of(TradeSignal.HOLD);
        double pendingAtr = 0;

        for (Kline bar : klines) {
            ctx.append(bar);

            // 预热期：不足最小周期，不调用策略，权益保持初始资金
            if (ctx.size() < minPeriod) {
                equityCurve.add(new EquityPoint(bar.getTime(), account.getEquity()));
                continue;
            }

            if (execution != null) {
                boolean protectedExit = executeSignalBar(strategy, ctx, account, bar, pending, execution.risk(),
                        pendingAtr, objects, trades);
                updateEquity(account, bar.getClose());
                pending = protectedExit ? SignalDecision.of(TradeSignal.HOLD) : ((SignalStrategy) strategy).onSignal(ctx, bar);
                boolean entry = pending.action() == TradeSignal.OPEN_LONG || pending.action() == TradeSignal.OPEN_SHORT;
                Double minimum = execution.risk().minVolatilityRatio();
                if (entry && minimum != null && minimum > 0 && suppressLowVolatilityEntry(ctx, bar, pending, minimum)) {
                    pending = SignalDecision.of(TradeSignal.HOLD);
                }
                if (pending.action() != TradeSignal.HOLD) {
                    long confirmedAt = bar.getTime() + IntervalUtils.toMillis(execution.interval());
                    Map<String, Object> evidence = new java.util.HashMap<>();
                    evidence.put("action", pending.action().name());
                    evidence.put("confirmedAt", confirmedAt);
                    evidence.put("strategyVersion", execution.strategyVersion());
                    if (pending.sourceRevision() != null) evidence.put("sourceRevision", pending.sourceRevision());
                    if (execution.risk().usesAtr() && entry) {
                        // 冻结信号收盘时的 ATR，开盘执行时不读取成交 K 线的高低价。
                        pendingAtr = IndicatorUtils.calcATR(ctx.history(), execution.risk().atrPeriod());
                        if (Double.isFinite(pendingAtr)) evidence.put("signalAtr", pendingAtr);
                    }
                    ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_DECISION, "SIGNAL_CREATED", pending.sourceId(),
                            account.getPositionSide(), null, "收盘确认信号，等待下一根开盘执行",
                            evidence);
                }
                equityCurve.add(new EquityPoint(bar.getTime(), account.getEquity()));
                continue;
            }

            boolean liquidatedThisBar = false;

            // ① 强平检查（最高优先级，逐仓 margin 全损，防穿仓）
            if (account.getPosition() > 0 && checkLiquidation(account, bar)) {
                liquidate(strategy, ctx, account, bar, trades);
                liquidatedThisBar = true;
            }

            // ② 固定止损止盈 + ③ 策略计划（强平后本根跳过，下一根才允许重新决策）
            if (!liquidatedThisBar) {
                TradePlan plan = strategy.onBarPlan(ctx, bar);
                executePlan(strategy, ctx, account, bar, plan, trades, null);
            }

            // 记录权益曲线（合约公式：availableBalance + positionMargin + 浮动盈亏）
            updateEquity(account, bar.getClose());
            equityCurve.add(new EquityPoint(bar.getTime(), account.getEquity()));
        }

        if (execution != null && pending.action() != TradeSignal.HOLD) {
            ctx.recordEvent(klines.get(klines.size() - 1).getTime(), StrategyEvent.CATEGORY_DECISION,
                    "SIGNAL_NOT_EXECUTED", pending.sourceId(), "最后一根收盘信号没有后续可执行 K 线");
        }

        // ===== 构造回测结果 =====
        double finalEquity = account.getEquity();
        // initialCapital=0 时除零会产生 NaN，此时收益率无意义，记为 0
        double returnRate = initialCapital > 0
                ? (finalEquity - initialCapital) / initialCapital * 100.0
                : 0.0;
        double maxDrawdown = calcMaxDrawdown(equityCurve);

        BacktestResult result = new BacktestResult();
        result.setReturnRate(returnRate);
        result.setMaxDrawdown(maxDrawdown);
        result.setEquityCurve(equityCurve);
        result.setTrades(trades);
        result.setLeverage(leverage);
        result.setInitialCapital(initialCapital);

        log.info("回测完成: 策略={}, 杠杆={}x, K线数={}, 初始资金={}, 最终权益={}, 收益率={}%, 最大回撤={}%, 交易次数={}",
                strategy.getId(), leverage, klines.size(),
                String.format("%.2f", initialCapital), String.format("%.2f", finalEquity),
                String.format("%.2f", returnRate), String.format("%.2f", maxDrawdown), trades.size());

        // ===== 构造回放快照（Week 7：本次回测实际使用的 K线 + 结构化事件）=====
        BacktestReplay replay = new BacktestReplay();
        replay.setBars(new ArrayList<>(klines));
        replay.setEvents(ctx.getEvents());

        BacktestRunOutput output = new BacktestRunOutput();
        output.setSummary(result);
        output.setReplay(replay);
        return output;
    }

    /** 比较信号收盘的短期波幅与此前的同周期基线，仅过滤入场。 */
    private boolean suppressLowVolatilityEntry(StrategyContext ctx, Kline bar, SignalDecision signal, double minimum) {
        List<Kline> history = ctx.history();
        List<Kline> prior = history.subList(0, history.size() - 1);
        double baseline = IndicatorUtils.calcATR(prior, EMA_VOLATILITY_BASELINE_PERIOD);
        double recent = IndicatorUtils.calcATR(history, EMA_VOLATILITY_PERIOD);
        double ratio = recent / baseline;
        boolean warmedUp = prior.size() > EMA_VOLATILITY_BASELINE_PERIOD;
        if (warmedUp && Double.isFinite(recent) && recent > 0 && Double.isFinite(baseline)
                && recent >= baseline * minimum) return false;

        var details = new java.util.HashMap<String, Object>();
        details.put("action", signal.action().name());
        details.put("threshold", minimum);
        if (Double.isFinite(ratio)) details.put("volatilityRatio", ratio);
        String message = warmedUp ? "波动不足，过滤 EMA 入场信号" : "波动基线尚未预热，过滤 EMA 入场信号";
        ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_DECISION, "ENTRY_FILTERED_VOLATILITY", signal.sourceId(),
                null, bar.getClose(), message, details);
        return true;
    }

    /** 下一根开盘执行待处理信号，然后检查本根持仓保护。 */
    private boolean executeSignalBar(Strategy strategy, StrategyContext ctx, Account account, Kline bar,
                                     SignalDecision signal, RiskConfiguration risk, double signalAtr,
                                     ChartObjectHistory objects, List<Trade> trades) {
        Kline opening = new Kline();
        opening.setTime(bar.getTime());
        opening.setOpen(bar.getOpen()); opening.setHigh(bar.getOpen());
        opening.setLow(bar.getOpen()); opening.setClose(bar.getOpen());
        updateEquity(account, bar.getOpen());
        boolean closed = protectSignalPosition(strategy, ctx, account, opening, trades, true);
        if (!closed && signal.action() != TradeSignal.HOLD) {
            TradePlan plan = risk.usesObjectStop() ? planZoneEntry(ctx, opening, signal, risk, objects)
                    : PositionRisk.plan(signal.action(), risk, bar.getOpen(), account, signalAtr);
            if (plan != null) {
                plan.setSourceId(signal.sourceId());
                executePlan(strategy, ctx, account, opening, plan, trades, risk);
            } else if (!risk.usesObjectStop()) {
                recordRejected(ctx, bar, "INVALID_ATR_PROTECTION", signal.sourceId(),
                        "ATR 未就绪或无法生成有效保护价", bar.getOpen());
            }
        }
        return protectSignalPosition(strategy, ctx, account, bar, trades, false) || closed;
    }

    /** 仅按开盘时仍有效的信号来源修订取价，缺口过期时不外推通道。 */
    private TradePlan planZoneEntry(StrategyContext ctx, Kline opening, SignalDecision signal,
                                    RiskConfiguration risk, ChartObjectHistory objects) {
        long time = opening.getTime();
        var zone = objects.covering(time, time).stream()
                .filter(revision -> Long.toString(revision.id()).equals(signal.sourceId())
                        && java.util.Objects.equals(revision.revision(), signal.sourceRevision()))
                .findFirst().orElse(null);
        if (zone == null) {
            recordRejected(ctx, opening, "ZONE_UNAVAILABLE_AT_OPEN", signal.sourceId(),
                    "信号来源区间在开仓时已失效或不可用", opening.getOpen());
            return null;
        }
        TradePlan plan = PositionRisk.planFromZone(signal.action(), risk, opening.getOpen(), zone, time);
        if (plan == null) {
            recordRejected(ctx, opening, "INVALID_ZONE_PROTECTION", signal.sourceId(),
                    "开盘价已越过区间止损或无法生成有效保护价", opening.getOpen());
        }
        return plan;
    }

    /** 跳空保护按开盘价执行，日内同时触及保护价时沿用强平、止损、止盈优先级。 */
    private boolean protectSignalPosition(Strategy strategy, StrategyContext ctx, Account account, Kline bar,
                                          List<Trade> trades, boolean opening) {
        if (account.getPosition() <= 0) return false;
        if (checkLiquidation(account, bar)) {
            liquidate(strategy, ctx, account, bar, trades);
            return true;
        }
        PositionSide side = account.getPositionSide();
        if (account.getStopPrice() > 0 && stopTriggered(side, bar, account.getStopPrice())) {
            closePosition(strategy, ctx, account, bar, opening ? bar.getOpen() : account.getStopPrice(),
                    side == PositionSide.SHORT ? "SHORT_STOP" : "STOP", trades);
            return true;
        }
        if (account.getTakeProfitPrice() > 0 && takeProfitTriggered(side, bar, account.getTakeProfitPrice())) {
            closePosition(strategy, ctx, account, bar, opening ? bar.getOpen() : account.getTakeProfitPrice(),
                    side == PositionSide.SHORT ? "SHORT_TP" : "TP", trades);
            return true;
        }
        return false;
    }

    /**
     * 执行交易计划（通用执行器，不认识具体策略）
     *
     * <p>执行顺序（每根K线，强平已在主循环优先处理）：
     * <ol>
     *   <li>检查现有持仓的固定止损止盈是否触发（风控优先，多空触发条件相反）
     *       <ul>
     *         <li>多头：止损 low ≤ stopPrice；止盈 high ≥ takeProfitPrice</li>
     *         <li>空头：止损 high ≥ stopPrice；止盈 low ≤ takeProfitPrice</li>
     *         <li>同时触发时先止损（保守）</li>
     *       </ul>
     *   </li>
     *   <li>执行计划动作：OPEN_LONG / OPEN_SHORT / ADD / CLOSE
     *       （止损止盈触发后本根跳过，与强平处理一致，下一根才允许重新决策）</li>
     * </ol>
     *
     * <p>单向状态机（非法 Action 安全忽略 + 日志）：
     * <pre>
     * NONE ─OPEN_LONG→ LONG      NONE ─OPEN_SHORT→ SHORT
     * LONG ─ADD/CLOSE/STOP/TP→ 保持/NONE    （LONG 下 OPEN_SHORT 非法）
     * SHORT─ADD/CLOSE/STOP/TP→ 保持/NONE    （SHORT 下 OPEN_LONG 非法）
     * </pre>
     */
    private void executePlan(Strategy strategy, StrategyContext ctx, Account account, Kline bar, TradePlan plan,
                             List<Trade> trades, RiskConfiguration limits) {
        // 1. 持仓止损止盈检查（风控优先，仅对设了固定止损止盈价的持仓）
        boolean closedThisBar = false;
        PositionSide side = account.getPositionSide();
        if (account.getPosition() > 0) {
            if (account.getStopPrice() > 0 && stopTriggered(side, bar, account.getStopPrice())) {
                String type = side == PositionSide.SHORT ? "SHORT_STOP" : "STOP";
                closePosition(strategy, ctx, account, bar, account.getStopPrice(), type, trades);
                closedThisBar = true;
            } else if (account.getTakeProfitPrice() > 0 && takeProfitTriggered(side, bar, account.getTakeProfitPrice())) {
                String type = side == PositionSide.SHORT ? "SHORT_TP" : "TP";
                closePosition(strategy, ctx, account, bar, account.getTakeProfitPrice(), type, trades);
                closedThisBar = true;
            }
        }

        // 止损止盈后本根跳过策略计划（与强平 liquidatedThisBar 一致，防止同根K线刚平仓又重新开仓）
        if (closedThisBar) {
            return;
        }

        // 2. 执行计划动作
        if (plan == null || plan.getAction() == TradePlan.Action.HOLD) {
            return;
        }
        switch (plan.getAction()) {
            case OPEN_LONG:
                if (account.getPosition() == 0 && side == PositionSide.NONE) {
                    openPositionByPlan(strategy, ctx, account, bar, plan, PositionSide.LONG, trades, limits);
                } else {
                    log.warn("OPEN_LONG 非法: 当前持仓 side={}, 数量={}, 已忽略（单向持仓，需先平仓）",
                            side, account.getPosition());
                    recordRejected(ctx, bar, REASON_ILLEGAL_POSITION_STATE, plan.getSourceId(),
                            "当前已有持仓，无法重复开多，入场计划未执行", null);
                }
                break;
            case OPEN_SHORT:
                if (account.getPosition() == 0 && side == PositionSide.NONE) {
                    openPositionByPlan(strategy, ctx, account, bar, plan, PositionSide.SHORT, trades, limits);
                } else {
                    log.warn("OPEN_SHORT 非法: 当前持仓 side={}, 数量={}, 已忽略（单向持仓，需先平仓）",
                            side, account.getPosition());
                    recordRejected(ctx, bar, REASON_ILLEGAL_POSITION_STATE, plan.getSourceId(),
                            "当前已有持仓，无法重复开空，入场计划未执行", null);
                }
                break;
            case ADD:
                if (account.getPosition() > 0) {
                    addPositionByPlan(strategy, ctx, account, bar, plan, trades, limits);
                } else {
                    log.warn("ADD 非法: 当前无持仓(NONE)，已忽略");
                    recordRejected(ctx, bar, REASON_ILLEGAL_POSITION_STATE, plan.getSourceId(),
                            "当前无持仓，无法加仓，入场计划未执行", null);
                }
                break;
            case CLOSE:
                if (account.getPosition() > 0) {
                    // 平仓类型：多空方向由当前持仓决定
                    String type;
                    if (plan.getCloseType() == TradePlan.CloseType.STOP) {
                        type = side == PositionSide.SHORT ? "SHORT_STOP" : "STOP";
                    } else {
                        type = side == PositionSide.SHORT ? "COVER" : "SELL";
                    }
                    closePosition(strategy, ctx, account, bar, bar.getClose(), type, trades);
                } else {
                    log.warn("CLOSE 非法: 当前无持仓(NONE)，已忽略");
                    recordRejected(ctx, bar, REASON_ILLEGAL_POSITION_STATE, plan.getSourceId(),
                            "当前无持仓，无法平仓", null);
                }
                break;
            default:
                break;
        }
    }

    /** 止损触发判断（多头 low≤止损价；空头 high≥止损价） */
    private boolean stopTriggered(PositionSide side, Kline bar, double stopPrice) {
        return side == PositionSide.SHORT ? bar.getHigh() >= stopPrice : bar.getLow() <= stopPrice;
    }

    /** 止盈触发判断（多头 high≥止盈价；空头 low≤止盈价） */
    private boolean takeProfitTriggered(PositionSide side, Kline bar, double takeProfitPrice) {
        return side == PositionSide.SHORT ? bar.getLow() <= takeProfitPrice : bar.getHigh() >= takeProfitPrice;
    }

    /**
     * 开仓（多空通用）：保证金扣款 + 三重校验（数量/余额/保证金充足），开仓后记录对应方向
     *
     * <p>成交价约定（OHLC K线级撮合，见 zone-reversal-strategy-plan.md §7）：
     * <ul>
     *   <li>entryPrice 为 null：按当前 K线收盘价成交（旧策略行为不变）</li>
     *   <li>entryPrice 非 null：仅当位于当前 K线 [low,high] 内时按该价成交；否则拒绝成交（跳空不假设计划成交价成交）</li>
     * </ul>
     * 成交成功后发送 OPEN 回报；因数量/余额/保证金不足被拒绝时<b>不发送</b>回报。
     */
    private void openPositionByPlan(Strategy strategy, StrategyContext ctx, Account account, Kline bar, TradePlan plan,
                                     PositionSide side, List<Trade> trades, RiskConfiguration limits) {
        // 计划成交价：仅当该价位于当前 K线 [low, high] 内才成交（limit 语义）
        double price = bar.getClose();
        if (plan.getEntryPrice() != null) {
            double planned = plan.getEntryPrice();
            if (planned < bar.getLow() || planned > bar.getHigh()) {
                log.warn("计划成交价 {} 不在当前K线 [low={}, high={}] 内，拒绝成交（不发送OPEN回报）",
                        planned, bar.getLow(), bar.getHigh());
                recordRejected(ctx, bar, REASON_PRICE_OUTSIDE_BAR, plan.getSourceId(),
                        String.format("计划成交价 %s 不在当前K线 [%s, %s] 范围内，入场未执行",
                                fmt(planned), fmt(bar.getLow()), fmt(bar.getHigh())), planned);
                return;
            }
            price = planned;
        }
        PositionRisk.Assessment risk = PositionRisk.assess(account, plan, price, FEE_RATE, limits);
        recordRiskEvaluation(ctx, bar, account, plan, risk, limits);
        if (risk.rejection() != null) {
            recordRiskRejection(ctx, bar, risk, plan.getSourceId(), price, account.getAvailableBalance(), false);
            return;
        }
        double amount = risk.amount();
        double margin = risk.margin();
        double fee = risk.fee();

        account.setAvailableBalance(account.getAvailableBalance() - margin - fee);
        account.setPosition(amount);
        account.setPositionSide(side);
        account.setPositionMargin(margin);
        account.setAvgEntryPrice(price);
        account.setLastEntryPrice(price);
        account.setEntryCount(1);
        account.setStopPrice(plan.getStopPrice());
        account.setTakeProfitPrice(plan.getTakeProfitPrice());
        account.setSourceId(plan.getSourceId() != null ? plan.getSourceId() : "");
        updateEquity(account, price);
        String type = side == PositionSide.SHORT ? "SHORT_SELL" : "BUY";
        trades.add(buildTrade(bar, type, price, amount, margin, fee, account));
        recordExecution(ctx, bar, type, side, price, plan.getSourceId());
        emitExecution(strategy, ctx, bar, type, side, price, amount, 0, account.getSourceId());
    }

    /** 通过风控校验后加仓，更新加权均价和累计保证金。 */
    private void addPositionByPlan(Strategy strategy, StrategyContext ctx, Account account, Kline bar, TradePlan plan,
                                    List<Trade> trades, RiskConfiguration limits) {
        double price = bar.getClose();
        PositionRisk.Assessment risk = PositionRisk.assess(account, plan, price, FEE_RATE, limits);
        recordRiskEvaluation(ctx, bar, account, plan, risk, limits);
        if (risk.rejection() != null) {
            recordRiskRejection(ctx, bar, risk, account.getSourceId(), price, account.getAvailableBalance(), true);
            return;
        }
        double amount = risk.amount();
        double margin = risk.margin();
        double fee = risk.fee();

        // 加权均价
        double oldPos = account.getPosition();
        double newAvg = (oldPos * account.getAvgEntryPrice() + amount * price) / (oldPos + amount);

        account.setAvailableBalance(account.getAvailableBalance() - margin - fee);
        account.setPosition(oldPos + amount);
        account.setPositionMargin(account.getPositionMargin() + margin);
        account.setAvgEntryPrice(newAvg);
        account.setLastEntryPrice(price);
        account.setEntryCount(account.getEntryCount() + 1);
        updateEquity(account, price);
        String type = account.getPositionSide() == PositionSide.SHORT ? "SHORT_ADD" : "ADD";
        trades.add(buildTrade(bar, type, price, amount, margin, fee, account));
        recordExecution(ctx, bar, type, account.getPositionSide(), price, account.getSourceId());
        emitExecution(strategy, ctx, bar, type, account.getPositionSide(), price, amount, 0, account.getSourceId());
    }

    /**
     * 正常平仓（多空通用，STOP/TP/SELL/COVER）：盈亏实现，保证金按盈亏返还
     *
     * <p>实现盈亏带方向：realizedPnl = position × (closePrice - avgEntryPrice) × directionSign(side)
     * （多头：数量×(平仓价-均价)；空头：数量×(均价-平仓价)）
     *
     * <p>逐仓语义：强平检查在主循环优先执行，此处未爆仓 → release > 0，不会让 availableBalance 变负。
     * release = positionMargin + realizedPnl - closeFee。
     *
     * <p>平仓回报必须携带开仓时保存的 sourceId（清仓前取出），使策略知道是哪一个区间的结果。
     */
    private void closePosition(Strategy strategy, StrategyContext ctx, Account account, Kline bar, double price, String type, List<Trade> trades) {
        PositionSide side = account.getPositionSide();   // 先取方向（clearPosition 会清空）
        double amount = account.getPosition();
        String sourceId = account.getSourceId();         // 先取来源（clearPosition 会清空）
        double realizedPnl = amount * (price - account.getAvgEntryPrice()) * directionSign(side);
        double closeFee = amount * price * FEE_RATE;
        double releasedMargin = account.getPositionMargin();  // 先存（清仓前）
        double release = releasedMargin + realizedPnl - closeFee;

        account.setAvailableBalance(account.getAvailableBalance() + release);
        clearPosition(account);
        updateEquity(account, price);
        trades.add(buildTrade(bar, type, price, amount, -releasedMargin, closeFee, side, account, sourceId));
        recordExecution(ctx, bar, type, side, price, sourceId);
        emitExecution(strategy, ctx, bar, type, side, price, amount, realizedPnl, sourceId);
    }

    /**
     * 强平（多空通用，LIQUIDATION/SHORT_LIQUIDATION）：逐仓核心 —— margin 全损，availableBalance 不变，绝不穿仓
     *
     * <p>与 {@link #closePosition} 独立：不计算 realizedPnl，不收 closeFee，
     * 保留开仓后剩余的可用保证金。从模型层面保证 availableBalance 永不为负。
     *
     * <p>成交价记录：多头 bar.low（保守），空头 bar.high（保守）。
     * 强平回报按一次失败计（realizedPnl 记 0，实际损失已通过保证金全损体现）。
     */
    private void liquidate(Strategy strategy, StrategyContext ctx, Account account, Kline bar, List<Trade> trades) {
        PositionSide side = account.getPositionSide();
        double liqPrice = side == PositionSide.SHORT ? bar.getHigh() : bar.getLow();  // 空头最坏价是 high
        double amount = account.getPosition();
        double lostMargin = account.getPositionMargin(); // 全损（先存）
        String sourceId = account.getSourceId();         // 先取来源（clearPosition 会清空）

        // 逐仓隔离：availableBalance 不变，不计算 realizedPnl，不收 closeFee
        clearPosition(account);
        updateEquity(account, bar.getClose());          // equity = availableBalance（仓位已清）
        String type = side == PositionSide.SHORT ? "SHORT_LIQUIDATION" : "LIQUIDATION";
        trades.add(buildTrade(bar, type, liqPrice, amount, -lostMargin, 0, side, account, sourceId));
        recordExecution(ctx, bar, type, side, liqPrice, sourceId);
        emitExecution(strategy, ctx, bar, type, side, liqPrice, amount, 0, sourceId);
        log.warn("{}强平触发: liqPrice={}, 仓位={}, 保证金全损={}, 剩余可用={}, 剩余权益={}",
                side == PositionSide.SHORT ? "空头" : "多头", liqPrice, amount,
                lostMargin, account.getAvailableBalance(), account.getEquity());
    }

    /**
     * 强平判断（K线级保守模型，多空最坏价格相反）
     *
     * <p>多头最坏价 = bar.low；空头最坏价 = bar.high。
     * <pre>
     * worstPrice = LONG ? bar.low : bar.high
     * unrealizedPnl = position × (worstPrice - avgEntryPrice) × directionSign(side)
     * positionEquity = positionMargin + unrealizedPnl
     * maintenanceMargin = position × worstPrice × maintenanceMarginRate
     * 当 positionEquity ≤ maintenanceMargin 时触发强平
     * </pre>
     */
    private boolean checkLiquidation(Account account, Kline bar) {
        PositionSide side = account.getPositionSide();
        double worstPrice = side == PositionSide.SHORT ? bar.getHigh() : bar.getLow();
        double unrealizedPnl = account.getPosition() * (worstPrice - account.getAvgEntryPrice()) * directionSign(side);
        double positionEquity = account.getPositionMargin() + unrealizedPnl;
        //稍复杂，最坏价格当时的权益的一定比例（比如0.5%）得到会被清算的权益数。即只关注那一刻，如果保证金小于这一刻的最小权益数，则会被清算。
        double maintenanceMargin = account.getPosition() * worstPrice * account.getMaintenanceMarginRate();
        return positionEquity <= maintenanceMargin;
    }

    /** 清空仓位状态（position + positionSide + 保证金 + 均价 + 止损止盈 + 来源全部重置） */
    private void clearPosition(Account account) {
        account.setPosition(0);
        account.setPositionSide(PositionSide.NONE);
        account.setPositionMargin(0);
        account.setAvgEntryPrice(0);
        account.setEntryCount(0);
        account.setLastEntryPrice(0);
        account.setStopPrice(0);
        account.setTakeProfitPrice(0);
        account.setSourceId("");
    }

    /**
     * 更新权益 = availableBalance + positionMargin + unrealizedPnl
     * unrealizedPnl = position × (markPrice - avgEntryPrice) × directionSign(side)
     */
    private void updateEquity(Account account, double markPrice) {
        double unrealizedPnl = account.getPosition() * (markPrice - account.getAvgEntryPrice())
                * directionSign(account.getPositionSide());
        account.setEquity(account.getAvailableBalance() + account.getPositionMargin() + unrealizedPnl);
    }

    /**
     * 方向因子：多头 +1，空头 -1，无持仓 0。
     * 统一多空盈亏公式，避免各处复制多空判断。
     */
    private double directionSign(PositionSide side) {
        return side == PositionSide.LONG ? 1.0
             : side == PositionSide.SHORT ? -1.0
             : 0.0;
    }

    /** 回放保留本次定仓预算、保护价格及准入结果，旧任务不新增事件。 */
    private void recordRiskEvaluation(StrategyContext ctx, Kline bar, Account account, TradePlan plan,
                                      PositionRisk.Assessment result, RiskConfiguration config) {
        if (config == null) return;
        var details = new java.util.HashMap<String, Object>(
                Map.of("riskVersion", config.version(), "preset", config.preset().name(),
                        "amount", result.amount(), "margin", result.margin(), "fee", result.fee(),
                        "equity", account.getEquity(), "stopPrice", plan.getStopPrice(),
                        "takeProfitPrice", plan.getTakeProfitPrice(),
                        "decision", result.rejection() == null ? "APPROVED" : result.rejection().name()));
        if (config.maxNotionalRatio() != null) details.put("maxNotionalRatio", config.maxNotionalRatio());
        if (result.addRisk() != null) {
            var evidence = result.addRisk();
            details.put("existingStopRisk", evidence.existing());
            details.put("additionalStopRisk", evidence.additional());
            details.put("totalStopRisk", evidence.total());
            details.put("stopRiskLimit", evidence.limit());
        }
        ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_DECISION, "RISK_EVALUATED", plan.getSourceId(),
                account.getPositionSide(), bar.getClose(), "风控方案 " + config.preset() + "，候选数量 " + fmt(result.amount()),
                details);
    }

    /** 把风控拒绝结果转换为已有的开仓或加仓回放事件。 */
    private void recordRiskRejection(StrategyContext ctx, Kline bar, PositionRisk.Assessment risk,
                                     String sourceId, double price, double available, boolean adding) {
        String action = adding ? "加仓" : "入场";
        String reasonCode;
        String message;
        switch (risk.rejection()) {
            case INVALID_AMOUNT -> {
                reasonCode = REASON_INVALID_AMOUNT;
                message = String.format("计算出的%s数量非法 (%s)，%s未执行",
                        adding ? "加仓" : "开仓", fmt(risk.amount()), action);
            }
            case NO_AVAILABLE_BALANCE -> {
                reasonCode = REASON_NO_AVAILABLE_BALANCE;
                message = "可用保证金不足，无法支付任何保证金与手续费，" + action + "未执行";
            }
            case INSUFFICIENT_MARGIN -> {
                reasonCode = REASON_INSUFFICIENT_MARGIN;
                message = String.format("保证金不足：需 %s（保证金 %s + 手续费 %s），可用 %s，%s未执行",
                        fmt(risk.margin() + risk.fee()), fmt(risk.margin()), fmt(risk.fee()), fmt(available), action);
            }
            case MAX_NOTIONAL_EXPOSURE -> {
                reasonCode = "MAX_NOTIONAL_EXPOSURE";
                message = "成交后总名义仓位超过风控上限，" + action + "未执行";
            }
            case MAX_POSITION_RISK -> {
                reasonCode = "MAX_POSITION_RISK";
                message = "加仓后累计止损风险超过风控上限";
            }
            default -> throw new IllegalStateException("Unexpected risk rejection: " + risk.rejection());
        }
        log.warn("{}", message);
        recordRejected(ctx, bar, reasonCode, sourceId, message, price);
    }

    /** 构造交易记录（开仓/加仓用：账户当前方向即交易方向，来源取账户当前持仓来源） */
    private Trade buildTrade(Kline bar, String type, double price, double amount,
                             double margin, double fee, Account account) {
        return buildTrade(bar, type, price, amount, margin, fee, account.getPositionSide(), account, account.getSourceId());
    }

    /**
     * 构造交易记录（平仓/强平用显式 side + sourceId：clearPosition 已把账户方向/来源清空，
     * 必须用平仓前保存的方向与来源，否则平仓记录 positionSide/sourceId 会错误地变成 NONE/空）
     */
    private Trade buildTrade(Kline bar, String type, double price, double amount,
                             double margin, double fee, PositionSide side, Account account, String sourceId) {
        Trade t = new Trade();
        t.setTime(bar.getTime());
        t.setType(type);
        t.setPositionSide(side);
        t.setPrice(price);
        t.setAmount(amount);
        t.setAvailableBalance(account.getAvailableBalance());
        t.setMargin(margin);
        t.setFee(fee);
        t.setPositionMargin(account.getPositionMargin());
        t.setEquity(account.getEquity());
        t.setAvgEntryPrice(account.getAvgEntryPrice());
        t.setSourceId(sourceId);
        return t;
    }

    /**
     * 发送执行回报（仅交易真正执行成功后调用；策略 onExecution 默认空实现保证旧策略兼容）
     */
    private void emitExecution(Strategy strategy, StrategyContext ctx, Kline bar, String type,
                               PositionSide side, double price, double amount,
                               double realizedPnl, String sourceId) {
        if (strategy == null) {
            return;
        }
        ExecutionEvent event = new ExecutionEvent();
        event.setTime(bar.getTime());
        event.setEventType(mapEventType(type));
        event.setPositionSide(side);
        event.setPrice(price);
        event.setAmount(amount);
        event.setRealizedPnl(realizedPnl);
        event.setSourceId(sourceId);
        strategy.onExecution(ctx, event);
    }

    /**
     * 记录引擎执行事件（EXECUTION 类，成交/退出；先于 emitExecution，保证
     * "执行事件 → 后续策略状态变化"的因果顺序，见 StrategyEvent 类注释）。
     *
     * <p>交易类型 → 事件码映射：BUY/SHORT_SELL/ADD/SHORT_ADD → ENTRY_EXECUTED；
     * STOP/SHORT_STOP → STOPPED；TP/SHORT_TP → TAKE_PROFIT；LIQUIDATION → LIQUIDATED；
     * SELL/COVER → CLOSED。
     */
    private void recordExecution(StrategyContext ctx, Kline bar, String type,
                                 PositionSide side, double price, String sourceId) {
        String code = mapExecutionEventCode(type);
        ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_EXECUTION, code, sourceId, side, price,
                executionMessage(code, price), null);
    }

    /**
     * 记录引擎执行拒绝事件（ENTRY_REJECTED，details.reasonCode 区分原因）。
     * 计划被拒绝时不发送 OPEN 回报（策略状态机仍保持等待，靠拒绝事件解释原因）。
     */
    private void recordRejected(StrategyContext ctx, Kline bar, String reasonCode,
                                String sourceId, String message, Double plannedPrice) {
        Map<String, Object> details = new java.util.HashMap<>();
        details.put("reasonCode", reasonCode);
        ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_EXECUTION, "ENTRY_REJECTED", sourceId,
                null, plannedPrice, message, details);
    }

    /** 执行事件码 → 面向用户的文案（前端判断依赖 code，本文案仅用于展示） */
    private String executionMessage(String code, double price) {
        switch (code) {
            case "ENTRY_EXECUTED":
                return String.format("已按 %s 建仓", fmt(price));
            case "STOPPED":
                return String.format("固定止损触发，按 %s 平仓", fmt(price));
            case "TAKE_PROFIT":
                return String.format("止盈触发，按 %s 平仓", fmt(price));
            case "LIQUIDATED":
                return String.format("强平触发，按 %s 平仓（保证金全损）", fmt(price));
            case "CLOSED":
                return String.format("信号平仓，按 %s 平仓", fmt(price));
            default:
                return String.format("执行完成，价格 %s", fmt(price));
        }
    }

    /** 交易类型 → 回放执行事件码 */
    private String mapExecutionEventCode(String type) {
        switch (type) {
            case "BUY":
            case "SHORT_SELL":
            case "ADD":
            case "SHORT_ADD":
                return "ENTRY_EXECUTED";
            case "STOP":
            case "SHORT_STOP":
                return "STOPPED";
            case "TP":
            case "SHORT_TP":
                return "TAKE_PROFIT";
            case "LIQUIDATION":
            case "SHORT_LIQUIDATION":
                return "LIQUIDATED";
            case "SELL":
            case "COVER":
            default:
                return "CLOSED";
        }
    }

    /** 数值格式化（去掉无意义的小数尾） */
    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.format("%,.0f", v);
        }
        return String.format("%,.4f", v);
    }

    /** 空结果（K线数据为空时返回） */
    private BacktestResult emptyResult() {
        BacktestResult result = new BacktestResult();
        result.setReturnRate(0);
        result.setMaxDrawdown(0);
        result.setEquityCurve(new ArrayList<>());
        result.setTrades(new ArrayList<>());
        return result;
    }

    /** 空组合输出（K线数据为空时返回） */
    private BacktestRunOutput emptyOutput() {
        BacktestRunOutput output = new BacktestRunOutput();
        output.setSummary(emptyResult());
        BacktestReplay replay = new BacktestReplay();
        replay.setBars(new ArrayList<>());
        replay.setEvents(new ArrayList<>());
        output.setReplay(replay);
        return output;
    }

    /** 交易类型 → 执行回报事件类型 */
    private ExecutionEvent.EventType mapEventType(String type) {
        switch (type) {
            case "BUY":
            case "SHORT_SELL":
                return ExecutionEvent.EventType.OPEN;
            case "STOP":
            case "SHORT_STOP":
                return ExecutionEvent.EventType.STOP;
            case "TP":
            case "SHORT_TP":
                return ExecutionEvent.EventType.TAKE_PROFIT;
            case "LIQUIDATION":
            case "SHORT_LIQUIDATION":
                return ExecutionEvent.EventType.LIQUIDATION;
            case "SELL":
            case "COVER":
            default:
                return ExecutionEvent.EventType.CLOSE;
        }
    }

    /**
     * 解析并校验杠杆（服务端校验，不依赖前端）
     * 规则：1~100 整数，null/非整数/NaN/Infinity/越界 → 抛 IllegalArgumentException
     */
    private int parseAndValidateLeverage(Map<String, Object> params) {
        if (params == null || !params.containsKey("leverage")) {
            return DEFAULT_LEVERAGE;
        }
        Object v = params.get("leverage");
        if (v == null) {
            return DEFAULT_LEVERAGE;
        }
        int leverage;
        try {
            leverage = Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("杠杆必须为整数, 实际: " + v);
        }
        if (leverage < 1 || leverage > 100) {
            throw new IllegalArgumentException("杠杆必须在 1~100 之间, 实际: " + leverage);
        }
        return leverage;
    }

    /**
     * 解析并校验初始资金（服务端校验，不依赖前端）
     * 规则：非负有限数，null → 默认值，负数/NaN/Infinity → 抛 IllegalArgumentException
     */
    private double parseAndValidateInitialCapital(Map<String, Object> params) {
        if (params == null || !params.containsKey("initialCapital")) {
            return DEFAULT_INITIAL_CAPITAL;
        }
        Object v = params.get("initialCapital");
        if (v == null) {
            return DEFAULT_INITIAL_CAPITAL;
        }
        double capital;
        try {
            capital = Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("初始资金必须为数值, 实际: " + v);
        }
        if (!Double.isFinite(capital)) {
            throw new IllegalArgumentException("初始资金不能为 NaN 或 Infinity");
        }
        if (capital < 0) {
            throw new IllegalArgumentException("初始资金不能为负数, 实际: " + capital);
        }
        return capital;
    }

    /** 计算最大回撤（%）：遍历权益曲线，跟踪峰值，算最大跌幅 */
    private double calcMaxDrawdown(List<EquityPoint> equityCurve) {
        if (equityCurve == null || equityCurve.isEmpty()) {
            return 0;
        }
        double peak = equityCurve.get(0).getEquity();
        double maxDrawdown = 0;
        for (EquityPoint point : equityCurve) {
            double equity = point.getEquity();
            if (equity > peak) {
                peak = equity;
            }
            if (peak > 0) {
                double drawdown = (peak - equity) / peak * 100.0;
                if (drawdown > maxDrawdown) {
                    maxDrawdown = drawdown;
                }
            }
        }
        return maxDrawdown;
    }
}
