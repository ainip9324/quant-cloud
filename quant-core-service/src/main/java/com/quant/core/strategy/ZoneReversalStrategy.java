package com.quant.core.strategy;

import com.quant.core.util.IntervalUtils;
import com.quant.core.zone.ChartObjectHistory;
import com.quant.core.engine.SignalRunConfiguration;
import com.quant.common.enums.PositionSide;
import com.quant.common.model.ExecutionEvent;
import com.quant.common.model.Kline;
import com.quant.common.model.StrategyEvent;
import com.quant.common.model.TradePlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zone Reversal Strategy：价格区间左侧反转策略（单向逐仓，多空镜像）
 *
 * <p>含 execution 的新任务使用冻结共享区域与收盘信号，由独立风控定仓和保护。
 * 以下规则仅描述旧任务，见 docs/zone-reversal-strategy-plan.md：
 * <ul>
 *   <li>区间必须声明生效时间 effectiveFrom（UTC 毫秒时间戳），生效前对策略完全不可见：
 *       不可武装、不可触发、不参与止盈截断、不参与同根冲突选择；到达后初始 DISARMED（Week 7 §4.1）</li>
 *   <li>价格从区间<b>上方</b>明确离开后回落，触及区间<b>中点</b>时开多（ARMED_ABOVE）</li>
 *   <li>价格从区间<b>下方</b>明确离开后反弹，触及区间<b>中点</b>时开空（ARMED_BELOW）</li>
 *   <li>触发价 = 计划成交价 = 区间<b>中点</b> (lower+upper)/2（多空同一中点），且须位于当前 K线 [low,high] 内（§4.1/§4.2）</li>
 *   <li>持仓期间不加仓、不反手、不替换止损止盈，一律 HOLD（§4.4）</li>
 *   <li>止损位于区间外侧半个区间宽度处（§4.1/§4.2）</li>
 *   <li>先计算原始 rewardRiskRatio 止盈，再由其他<b>已生效</b>区间截断（§4.5）：多头取入场价上方、原始止盈前最近区间的 lower；空头对称取 upper；已生效后禁用（DISABLED）的区间仍参与拦截</li>
 *   <li>截断后重算实际 RR，仅当 RR &gt; 1.0 才生成开仓计划；RR ≤ 1.0 一律 HOLD（§4.6）</li>
 *   <li>同一区间连续止损达到 maxConsecutiveStops 后本次回测永久禁用（§5.2）</li>
 * </ul>
 *
 * <p>OHLC 撮合约定（§7）：
 * <ol>
 *   <li>区间中点（触发价）必须位于当前 K线 [low, high] 内才视为可触发</li>
 *   <li>触发价 = 计划成交价 entryPrice = 区间中点；策略触发条件已保证中点 ∈ [low,high]，引擎校验兜底</li>
 *   <li>新仓开仓当根不检查该新仓止损止盈，从下一根开始</li>
 *   <li>同根止损与止盈同时触发 → 止损优先（引擎保守规则）</li>
 *   <li>跳空导致中点不在 [low, high] 内 → 不触发、不假设成交</li>
 * </ol>
 *
 * <p>风险定仓（§4.3）：RISK_PER_PRICE_UNIT，amount = 开仓时权益 × riskRate / riskDistance，
 * riskDistance = 中点到止损的距离（§4.5 止盈截断不影响风险距离）。资金不足时引擎拒绝开仓。
 *
 * <p>状态机防卡死（§4.6/§5.1）：只有引擎实际成交（onExecution 收到 OPEN 回报）才将区间置为 IN_POSITION；
 * "计划被引擎拒绝"（保证金不足等）或"RR ≤ 1 不生成计划"时区间保持 ARMED，后续 K线可重新尝试。
 * 连续止损统计依赖真实执行回报，不通过账户状态猜测。
 *
 * <p>决策回放事件（Week 7）：状态变化（ZONE_EFFECTIVE/ARMED_ABOVE/ARMED_BELOW/STOP_COUNT_CHANGED/ZONE_DISABLED）、
 * 决策（ENTRY_PLANNED/TAKE_PROFIT_TRUNCATED/ENTRY_REJECTED_RR）由策略记录；真实成交/退出/拒绝由引擎记录。
 * 相同 time+sourceId+code 不重复记录（状态只在真正变化时产生事件）。
 *
 * <p>并发安全：无实例可变字段；所有参数与区间状态均存放在每次回测独立的 StrategyContext（putState/getState）。
 */
@Slf4j
@Component
public class ZoneReversalStrategy implements Strategy, SignalStrategy {

    /** ctx state 中 ZoneRuntimeState 的 key */
    private static final String STATE_KEY = "zoneReversalRuntime";
    public static final String OBJECT_STOP_SIGNAL_VERSION = "zone-reversal-signal-v5";

    /** 新版仅输出区域反转信号，历史任务仍保留原中点策略。 */
    @Override public String signalVersion() { return OBJECT_STOP_SIGNAL_VERSION; }
    @Override public boolean supportsSignalVersion(String version) {
        return "zone-reversal-signal-v1".equals(version) || "zone-reversal-signal-v2".equals(version)
                || "zone-reversal-signal-v3".equals(version) || "zone-reversal-signal-v4".equals(version)
                || OBJECT_STOP_SIGNAL_VERSION.equals(version);
    }
    @Override public boolean requiresChartObjects() { return true; }

    /** 快照与算法版本成对读取，旧任务不能被新版几何语义重解释。 */
    private static void requireSnapshotVersion(SignalRunConfiguration config, ChartObjectHistory.Snapshot snapshot) {
        int expected = switch (config.strategyVersion()) {
            case "zone-reversal-signal-v1" -> 1;
            case "zone-reversal-signal-v2" -> 2;
            default -> 3;
        };
        if (snapshot.version() != expected) throw new IllegalArgumentException("图表快照与信号版本不匹配");
    }

    /** 新路径拒绝旧定仓及区间专属保护参数，防止静默忽略配置。 */
    @Override public void normalizeSignalParameters(Map<String, Object> params) {
        for (String legacy : new String[] {"zones", "rewardRiskRatio", "stopBufferRatio", "rearmBufferRatio", "maxConsecutiveStops"}) {
            if (params.containsKey(legacy)) throw new IllegalArgumentException("新版区间反转不接受旧参数: " + legacy);
        }
    }

    /** 只读取任务内的历史对象和已闭合行情。 */
    @Override public SignalDecision onSignal(StrategyContext ctx, Kline bar) {
        return ChartZoneSignals.decide(ctx, bar);
    }

    @Override
    public String getId() {
        return "zone-reversal";
    }

    /** 区间策略无需指标预热，从第一根 K线开始判断 */
    @Override
    public int getMinPeriod() {
        return 1;
    }

    /**
     * 提交级参数校验（保证缺失/非法 effectiveFrom 返回 400）
     *
     * <p>只做轻量必填校验（zones 存在 + 每个区间 effectiveFrom 合法），完整的数值范围校验
     * 仍由 {@link #initialize} 的 parseZones 执行，防止内部调用绕过 Service 校验。
     *
     * @throws IllegalArgumentException 校验失败（由 GlobalExceptionHandler 转为 400）
     */
    @SuppressWarnings("unchecked")
    @Override
    public void validateParameters(Map<String, Object> params) {
        if (params != null && params.containsKey("execution")) {
            requireSnapshotVersion(SignalRunConfiguration.from(params, this), ChartObjectHistory.snapshot(params));
            return;
        }
        List<Map<String, Object>> rawZones = params != null ? (List<Map<String, Object>>) params.get("zones") : null;
        if (rawZones == null || rawZones.isEmpty()) {
            throw new IllegalArgumentException("zones 不能为空");
        }
        if (rawZones.size() > 20) {
            throw new IllegalArgumentException("zones 数量必须在 1..20 之间, 实际: " + rawZones.size());
        }
        for (int i = 0; i < rawZones.size(); i++) {
            Map<String, Object> m = rawZones.get(i);
            String id = m.get("id") != null ? String.valueOf(m.get("id")).trim() : null;
            // 缺失/非法 effectiveFrom 直接抛 400（Week 7 不可静默按全历史有效处理）
            parseEffectiveFrom(m.get("effectiveFrom"), "zones[" + i + "]" + (id != null ? "(" + id + ")" : ""));
        }
    }

    /**
     * 解析并校验参数（§9），构造区间运行时状态存入 ctx（每次回测独立）。
     * 校验失败抛 IllegalArgumentException，错误信息指出具体区间。
     */
    @Override
    @SuppressWarnings("unchecked")
    public void initialize(StrategyContext ctx) {
        Object execution = ctx.getParam("execution", null);
        if (execution != null) {
            var config = SignalRunConfiguration.from(Map.of("execution", execution), this);
            var snapshot = ChartObjectHistory.snapshot(Map.of("chartObjects", ctx.getParam("chartObjects", null)));
            requireSnapshotVersion(config, snapshot);
            ctx.putState("chartObjectHistory", new ChartObjectHistory(snapshot));
            ctx.putState("chartObjectPeriod", IntervalUtils.toMillis(config.interval()));
            ctx.putState("singleBarChannelEntry", "zone-reversal-signal-v4".equals(config.strategyVersion())
                    || OBJECT_STOP_SIGNAL_VERSION.equals(config.strategyVersion()));
            return;
        }
        double riskRate = d(ctx.getParam("riskRate", null), 0.01);
        double rewardRiskRatio = d(ctx.getParam("rewardRiskRatio", null), 2.0);
        double stopBufferRatio = d(ctx.getParam("stopBufferRatio", null), 0.5);
        double rearmBufferRatio = d(ctx.getParam("rearmBufferRatio", null), 0.2);
        int maxConsecutiveStops = i(ctx.getParam("maxConsecutiveStops", null), 2);

        if (riskRate <= 0 || riskRate > 0.1) {
            throw new IllegalArgumentException("riskRate 必须在 (0, 0.1] 之间, 实际: " + riskRate);
        }
        if (!Double.isFinite(riskRate) || !Double.isFinite(rewardRiskRatio)
                || !Double.isFinite(stopBufferRatio) || !Double.isFinite(rearmBufferRatio)) {
            throw new IllegalArgumentException("参数不能为 NaN 或 Infinity");
        }
        if (rewardRiskRatio <= 0) {
            throw new IllegalArgumentException("rewardRiskRatio 必须 > 0, 实际: " + rewardRiskRatio);
        }
        if (stopBufferRatio < 0) {
            throw new IllegalArgumentException("stopBufferRatio 必须 >= 0, 实际: " + stopBufferRatio);
        }
        if (rearmBufferRatio < 0) {
            throw new IllegalArgumentException("rearmBufferRatio 必须 >= 0, 实际: " + rearmBufferRatio);
        }
        if (maxConsecutiveStops < 1) {
            throw new IllegalArgumentException("maxConsecutiveStops 必须 >= 1, 实际: " + maxConsecutiveStops);
        }

        List<Map<String, Object>> rawZones = ctx.getParam("zones", null);
        ZoneRuntimeState runtime = parseZones(rawZones, riskRate, rewardRiskRatio,
                stopBufferRatio, rearmBufferRatio, maxConsecutiveStops);
        ctx.putState(STATE_KEY, runtime);
        log.info("zone-reversal 初始化: {} 个区间, riskRate={}, rewardRiskRatio={}, stopBufferRatio={}, rearmBufferRatio={}, maxConsecutiveStops={}",
                runtime.zones.size(), riskRate, rewardRiskRatio, stopBufferRatio, rearmBufferRatio, maxConsecutiveStops);
    }

    /**
     * 单根 K线决策（状态机）
     *
     * <p>生效标记：bar.time >= effectiveFrom 的区间标记为已生效并记录 ZONE_EFFECTIVE（只一次）。
     * 生效前区间不可见（不可武装/触发/参与冲突选择/参与止盈截断）。
     *
     * <p>有持仓 → 一律 HOLD（§4.4）。无持仓时：
     * <ol>
     *   <li>防御性自愈：IN_POSITION 但无持仓 → 恢复 DISARMED（正常流程下不会发生，仅兜底）</li>
     *   <li>触发判断（先于状态刷新）：ARMED_ABOVE 且 low≤mid≤high → 尝试开多；ARMED_BELOW 且 low≤mid≤high → 尝试开空（§4.1/§4.2，mid=区间中点）</li>
     *   <li>触发成功但实际 RR ≤ 1.0 → HOLD，状态不变（§4.6）；计划生成后仅当引擎成交（OPEN 回报）才置 IN_POSITION</li>
     *   <li>刷新武装：close &gt; upper+width×rearm → ARMED_ABOVE；close &lt; lower-width×rearm → ARMED_BELOW（§5.1）</li>
     *   <li>多区间同根触发：从上方选 upper 最高的，从下方选 lower 最低的，一根 K线最多一次开仓（§6）</li>
     * </ol>
     */
    @Override
    public TradePlan onBarPlan(StrategyContext ctx, Kline bar) {
        ZoneRuntimeState runtime = ctx.getState(STATE_KEY, null);
        if (runtime == null) {
            return TradePlan.hold();
        }

        // 生效标记（Week 7 §4.1）：到达 effectiveFrom 的区间对策略可见，只标记一次并记录 ZONE_EFFECTIVE。
        // 第一根生效 K线此时仅处于 DISARMED（此前不可武装），后面刷新循环允许其参与状态刷新，
        // 但触发循环要求 ARMED 状态，因此不会凭空触发入场。
        long barTime = bar.getTime();
        for (ZoneState z : runtime.zones) {
            if (!z.effective && barTime >= z.effectiveFrom) {
                z.effective = true;
                ctx.recordEvent(barTime, StrategyEvent.CATEGORY_ZONE_STATE, "ZONE_EFFECTIVE", z.id,
                        String.format("区间 %s 到达生效时间，开始参与决策", z.id));
            }
        }

        // §4.4 持仓期间一律 HOLD（不加仓/不反手/不替换止损止盈）
        if (ctx.getPositionSide() != PositionSide.NONE) {
            return TradePlan.hold();
        }

        // 防御性自愈：正常流程下 IN_POSITION 仅由 OPEN 成交回报设置（必有持仓），此分支不会触发
        for (ZoneState z : runtime.zones) {
            if (z.status == ZoneStatus.IN_POSITION) {
                log.debug("区间 {} 标记 IN_POSITION 但无持仓 → 状态异常，恢复 DISARMED", z.id);
                z.status = ZoneStatus.DISARMED;
            }
        }

        // 触发判断必须先于状态刷新：一根大 K 线同时触及中点时（low≤mid≤high），
        // 其 close 可能已落到区间另一侧，若先刷新会把 ARMED 状态撤销而漏掉"触及即触发"（OHLC 约定 §7.1）

        // 从上方回落触及区间中点：尝试开多（§4.1），冲突时选 upper 最高的（§6）；未生效区间不参与
        ZoneState longZone = null;
        for (ZoneState z : runtime.zones) {
            if (z.effective && z.status == ZoneStatus.ARMED_ABOVE
                    && bar.getLow() <= z.mid && bar.getHigh() >= z.mid) {
                if (longZone == null || z.upper > longZone.upper) {
                    longZone = z;
                }
            }
        }
        if (longZone != null) {
            TradePlan plan = buildLongPlan(ctx, runtime, bar, longZone);
            // RR ≤ 1.0 → HOLD：状态不变（保持 ARMED），不置 IN_POSITION，不产生交易（§4.6）
            return plan != null ? plan : TradePlan.hold();
        }

        // 从下方反弹触及区间中点：尝试开空（§4.2），冲突时选 lower 最低的（§6）；未生效区间不参与
        ZoneState shortZone = null;
        for (ZoneState z : runtime.zones) {
            if (z.effective && z.status == ZoneStatus.ARMED_BELOW
                    && bar.getLow() <= z.mid && bar.getHigh() >= z.mid) {
                if (shortZone == null || z.lower < shortZone.lower) {
                    shortZone = z;
                }
            }
        }
        if (shortZone != null) {
            TradePlan plan = buildShortPlan(ctx, runtime, bar, shortZone);
            return plan != null ? plan : TradePlan.hold();
        }

        // 状态刷新（§5.1 初次武装与重新武装）：仅已生效、未触发且未持仓的区间。
        // 区间角色随价格方向动态决定：ARMED_ABOVE 反向跌破 below → 直接翻转 ARMED_BELOW；
        // ARMED_BELOW 反向涨破 above → 直接翻转 ARMED_ABOVE（区间从阻力变支撑，无需先回 DISARMED）。
        double close = bar.getClose();
        for (ZoneState z : runtime.zones) {
            if (!z.effective || z.status == ZoneStatus.DISABLED || z.status == ZoneStatus.IN_POSITION) {
                continue;
            }
            double above = z.upper + z.width * runtime.rearmBufferRatio;
            double below = z.lower - z.width * runtime.rearmBufferRatio;
            if (z.status == ZoneStatus.DISARMED) {
                if (close > above) {
                    z.status = ZoneStatus.ARMED_ABOVE;
                    recordArmed(ctx, barTime, z, true);
                } else if (close < below) {
                    z.status = ZoneStatus.ARMED_BELOW;
                    recordArmed(ctx, barTime, z, false);
                }
            } else if (z.status == ZoneStatus.ARMED_ABOVE && close < below) {
                z.status = ZoneStatus.ARMED_BELOW;
                recordArmed(ctx, barTime, z, false);
            } else if (z.status == ZoneStatus.ARMED_BELOW && close > above) {
                z.status = ZoneStatus.ARMED_ABOVE;
                recordArmed(ctx, barTime, z, true);
            }
        }

        return TradePlan.hold();
    }

    /** 记录区间武装事件（ARMED_ABOVE / ARMED_BELOW），状态真正变化时调用一次 */
    private void recordArmed(StrategyContext ctx, long barTime, ZoneState z, boolean above) {
        String code = above ? "ARMED_ABOVE" : "ARMED_BELOW";
        ctx.recordEvent(barTime, StrategyEvent.CATEGORY_ZONE_STATE, code, z.id,
                above
                        ? String.format("区间 %s 从上方武装，等待回落至中点 %s 做多", z.id, fmt(z.mid))
                        : String.format("区间 %s 从下方武装，等待反弹至中点 %s 做空", z.id, fmt(z.mid)));
    }

    // ==================== 开仓计划构建（§4.1/§4.2/§4.5/§4.6）====================

    /**
     * 构建多头开仓计划（§4.1/§4.5/§4.6）
     * <ul>
     *   <li>entry = 区间中点 (lower+upper)/2；stop = lower - width×stopBufferRatio；riskDistance = entry - stop</li>
     *   <li>原始止盈 = entry + riskDistance×rewardRiskRatio，再由其他<b>已生效</b>区间截断（§4.5）</li>
     *   <li>截断后实际 RR &gt; 1.0 才返回计划；RR ≤ 1.0 返回 null（调用方 HOLD，状态不变）</li>
     *   <li>本方法不修改区间状态：只有引擎成交（OPEN 回报）才置 IN_POSITION</li>
     *   <li>记录决策事件：TAKE_PROFIT_TRUNCATED（被截断时）、ENTRY_REJECTED_RR（RR≤1 时）、ENTRY_PLANNED（生成计划时）</li>
     * </ul>
     */
    private TradePlan buildLongPlan(StrategyContext ctx, ZoneRuntimeState runtime, Kline bar, ZoneState self) {
        long barTime = bar.getTime();
        double entry = (self.lower + self.upper) / 2.0;
        double stop = self.lower - self.width * runtime.stopBufferRatio;
        double riskDistance = entry - stop;
        double rawTp = entry + riskDistance * runtime.rewardRiskRatio;
        TruncateOutcome t = truncateLongTakeProfit(runtime, self, entry, rawTp);
        double tp = t.price;
        double rr = (tp - entry) / riskDistance;

        Map<String, Object> details = new HashMap<>();
        details.put("entry", entry);
        details.put("stop", stop);
        details.put("rawTp", rawTp);
        details.put("actualTp", tp);
        details.put("rr", rr);

        if (t.blocker != null) {
            details.put("blockerZone", t.blocker.id);
            ctx.recordEvent(barTime, StrategyEvent.CATEGORY_DECISION, "TAKE_PROFIT_TRUNCATED", self.id,
                    PositionSide.LONG, null,
                    String.format("原始止盈 %s 被前方区间 %s 截断为 %s，实际 RR=%.2f",
                            fmt(rawTp), t.blocker.id, fmt(tp), rr), details);
        }

        if (rr <= 1.0) {
            ctx.recordEvent(barTime, StrategyEvent.CATEGORY_DECISION, "ENTRY_REJECTED_RR", self.id,
                    PositionSide.LONG, null,
                    String.format("实际 RR=%.2f，不满足严格大于 1，本次不开仓（等待重新武装）", rr), details);
            log.debug("区间 {} 多头 RR={} ≤ 1.0，不生成开仓计划（前方区间截断）", self.id, rr);
            return null;
        }

        ctx.recordEvent(barTime, StrategyEvent.CATEGORY_DECISION, "ENTRY_PLANNED", self.id,
                PositionSide.LONG, entry,
                String.format("区间 %s 触发做多计划：入场 %s，止损 %s，止盈 %s，实际 RR=%.2f",
                        self.id, fmt(entry), fmt(stop), fmt(tp), rr), details);
        return TradePlan.openLongRisk(runtime.riskRate, riskDistance, entry, stop, tp,
                self.id, TradePlan.SizingBase.EQUITY);
    }

    /**
     * 构建空头开仓计划（§4.2/§4.5/§4.6），与多头镜像对称
     */
    private TradePlan buildShortPlan(StrategyContext ctx, ZoneRuntimeState runtime, Kline bar, ZoneState self) {
        long barTime = bar.getTime();
        double entry = (self.lower + self.upper) / 2.0;
        double stop = self.upper + self.width * runtime.stopBufferRatio;
        double riskDistance = stop - entry;
        double rawTp = entry - riskDistance * runtime.rewardRiskRatio;
        TruncateOutcome t = truncateShortTakeProfit(runtime, self, entry, rawTp);
        double tp = t.price;
        double rr = (entry - tp) / riskDistance;

        Map<String, Object> details = new HashMap<>();
        details.put("entry", entry);
        details.put("stop", stop);
        details.put("rawTp", rawTp);
        details.put("actualTp", tp);
        details.put("rr", rr);

        if (t.blocker != null) {
            details.put("blockerZone", t.blocker.id);
            ctx.recordEvent(barTime, StrategyEvent.CATEGORY_DECISION, "TAKE_PROFIT_TRUNCATED", self.id,
                    PositionSide.SHORT, null,
                    String.format("原始止盈 %s 被前方区间 %s 截断为 %s，实际 RR=%.2f",
                            fmt(rawTp), t.blocker.id, fmt(tp), rr), details);
        }

        if (rr <= 1.0) {
            ctx.recordEvent(barTime, StrategyEvent.CATEGORY_DECISION, "ENTRY_REJECTED_RR", self.id,
                    PositionSide.SHORT, null,
                    String.format("实际 RR=%.2f，不满足严格大于 1，本次不开仓（等待重新武装）", rr), details);
            log.debug("区间 {} 空头 RR={} ≤ 1.0，不生成开仓计划（前方区间截断）", self.id, rr);
            return null;
        }

        ctx.recordEvent(barTime, StrategyEvent.CATEGORY_DECISION, "ENTRY_PLANNED", self.id,
                PositionSide.SHORT, entry,
                String.format("区间 %s 触发做空计划：入场 %s，止损 %s，止盈 %s，实际 RR=%.2f",
                        self.id, fmt(entry), fmt(stop), fmt(tp), rr), details);
        return TradePlan.openShortRisk(runtime.riskRate, riskDistance, entry, stop, tp,
                self.id, TradePlan.SizingBase.EQUITY);
    }

    /**
     * 止盈截断结果：截断后价格 + 起拦截作用的区间（null = 未截断）
     */
    private static final class TruncateOutcome {
        final double price;
        final ZoneState blocker;

        TruncateOutcome(double price, ZoneState blocker) {
            this.price = price;
            this.blocker = blocker;
        }
    }

    /**
     * 多头止盈截断（§4.5，Week 7 时间过滤）：在入场价上方、且会在原始止盈前被触及的其他<b>已生效</b>区间中，
     * 选最靠近入场价的区间，实际止盈 = 该区间 lower。当前触发区间排除，已生效后 DISABLED 的区间仍参与拦截。
     */
    private TruncateOutcome truncateLongTakeProfit(ZoneRuntimeState runtime, ZoneState self, double entry, double rawTp) {
        double best = rawTp;
        ZoneState blocker = null;
        for (ZoneState z : runtime.zones) {
            if (z == self || !z.effective) {
                continue;
            }
            // 上升路径先触及 lower：lower 须严格高于入场价、严格低于原始止盈
            if (z.lower > entry && z.lower < rawTp && z.lower < best) {
                best = z.lower;
                blocker = z;
            }
        }
        return new TruncateOutcome(best, blocker);
    }

    /**
     * 空头止盈截断（§4.5，Week 7 时间过滤）：在入场价下方、且会在原始止盈前被触及的其他<b>已生效</b>区间中，
     * 选最靠近入场价的区间，实际止盈 = 该区间 upper。当前触发区间排除，已生效后 DISABLED 的区间仍参与拦截。
     */
    private TruncateOutcome truncateShortTakeProfit(ZoneRuntimeState runtime, ZoneState self, double entry, double rawTp) {
        double best = rawTp;
        ZoneState blocker = null;
        for (ZoneState z : runtime.zones) {
            if (z == self || !z.effective) {
                continue;
            }
            // 下降路径先触及 upper：upper 须严格低于入场价、严格高于原始止盈
            if (z.upper < entry && z.upper > rawTp && z.upper > best) {
                best = z.upper;
                blocker = z;
            }
        }
        return new TruncateOutcome(best, blocker);
    }

    /**
     * 执行回报（§5.2 连续止损统计依赖真实执行回报，按 sourceId 归因）：
     * <ul>
     *   <li>OPEN：开仓真正成交，区间才进入 IN_POSITION（计划被拒绝时无 OPEN，区间保持 ARMED 可重试）</li>
     *   <li>STOP / LIQUIDATION：该区间连续止损 +1；达到阈值 → DISABLED，否则 → DISARMED</li>
     *   <li>TAKE_PROFIT：该区间连续止损清零 → DISARMED（中间无交易 K线不重置连续计数）</li>
     *   <li>CLOSE（信号平仓）：→ DISARMED，连续计数不变</li>
     *   <li>其他区间回报不影响本区间计数</li>
     * </ul>
     */
    @Override
    public void onExecution(StrategyContext ctx, ExecutionEvent event) {
        ZoneRuntimeState runtime = ctx.getState(STATE_KEY, null);
        if (runtime == null || event.getSourceId() == null || event.getSourceId().isEmpty()) {
            return;
        }
        ZoneState z = runtime.find(event.getSourceId());
        if (z == null) {
            return;
        }
        switch (event.getEventType()) {
            case OPEN:
                // 只有真实成交才进入 IN_POSITION（§4.6 状态机防卡死）
                z.status = ZoneStatus.IN_POSITION;
                break;
            case STOP:
            case LIQUIDATION:
                z.consecutiveStops++;
                recordStopCount(ctx, event.getTime(), z);
                if (z.consecutiveStops >= runtime.maxConsecutiveStops) {
                    z.status = ZoneStatus.DISABLED;
                    Map<String, Object> details = new HashMap<>();
                    details.put("stopCount", z.consecutiveStops);
                    details.put("threshold", runtime.maxConsecutiveStops);
                    ctx.recordEvent(event.getTime(), StrategyEvent.CATEGORY_ZONE_STATE, "ZONE_DISABLED", z.id,
                            null, null,
                            String.format("连续第 %d 次止损，区间 %s 已禁用，等待人工审核", z.consecutiveStops, z.id),
                            details);
                    log.info("区间 {} 连续止损 {} 次达到阈值，本次回测禁用", z.id, z.consecutiveStops);
                } else {
                    z.status = ZoneStatus.DISARMED;
                }
                break;
            case TAKE_PROFIT:
                if (z.consecutiveStops != 0) {
                    z.consecutiveStops = 0;
                    recordStopCount(ctx, event.getTime(), z);
                }
                z.status = ZoneStatus.DISARMED;
                break;
            case CLOSE:
                z.status = ZoneStatus.DISARMED;
                break;
            default:
                break;
        }
    }

    /** 记录连续止损次数变化事件（STOP_COUNT_CHANGED，计数真正变化时调用一次） */
    private void recordStopCount(StrategyContext ctx, long time, ZoneState z) {
        Map<String, Object> details = new HashMap<>();
        details.put("stopCount", z.consecutiveStops);
        ctx.recordEvent(time, StrategyEvent.CATEGORY_ZONE_STATE, "STOP_COUNT_CHANGED", z.id,
                null, null,
                String.format("区间 %s 连续止损计数变为 %d", z.id, z.consecutiveStops),
                details);
    }

    // ==================== 参数解析与校验（§9）====================

    @SuppressWarnings("unchecked")
    private ZoneRuntimeState parseZones(List<Map<String, Object>> rawZones, double riskRate,
                                        double rewardRiskRatio, double stopBufferRatio,
                                        double rearmBufferRatio, int maxConsecutiveStops) {
        if (rawZones == null || rawZones.isEmpty()) {
            throw new IllegalArgumentException("zones 不能为空");
        }
        if (rawZones.size() > 20) {
            throw new IllegalArgumentException("zones 数量必须在 1..20 之间, 实际: " + rawZones.size());
        }

        List<ZoneState> zones = new ArrayList<>(rawZones.size());
        for (int i = 0; i < rawZones.size(); i++) {
            Map<String, Object> m = rawZones.get(i);
            // id 先 trim 后存储/比较/返回，与前端规则一致（" zone-1 " 与 "zone-1" 视为同一 id）
            String id = m.get("id") != null ? String.valueOf(m.get("id")).trim() : null;
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("zones[" + i + "] 缺少 id");
            }
            double lower = d(m.get("lower"), Double.NaN);
            double upper = d(m.get("upper"), Double.NaN);
            if (Double.isNaN(lower) || Double.isNaN(upper)) {
                throw new IllegalArgumentException("zones[" + i + "](" + id + ") lower/upper 必须为数值");
            }
            if (!Double.isFinite(lower) || !Double.isFinite(upper)) {
                throw new IllegalArgumentException("zones[" + i + "](" + id + ") lower/upper 不能为 NaN 或 Infinity");
            }
            if (lower <= 0) {
                throw new IllegalArgumentException("zones[" + i + "](" + id + ") lower 必须 > 0, 实际: " + lower);
            }
            if (upper <= lower) {
                throw new IllegalArgumentException("zones[" + i + "](" + id + ") 上边界必须大于下边界");
            }
            long effectiveFrom = parseEffectiveFrom(m.get("effectiveFrom"), "zones[" + i + "](" + id + ")");
            zones.add(new ZoneState(id, lower, upper, effectiveFrom));
        }

        // id 唯一
        for (int i = 0; i < zones.size(); i++) {
            for (int j = i + 1; j < zones.size(); j++) {
                if (zones.get(i).id.equals(zones.get(j).id)) {
                    throw new IllegalArgumentException("zone id 必须唯一, 重复: " + zones.get(i).id);
                }
            }
        }

        // 区间互不重叠（开区间判定：L1 < U2 && L2 < U1 → 重叠）
        for (int i = 0; i < zones.size(); i++) {
            for (int j = i + 1; j < zones.size(); j++) {
                ZoneState a = zones.get(i);
                ZoneState b = zones.get(j);
                if (a.lower < b.upper && b.lower < a.upper) {
                    throw new IllegalArgumentException(a.id + " 与 " + b.id + " 存在重叠");
                }
            }
        }

        return new ZoneRuntimeState(riskRate, rewardRiskRatio, stopBufferRatio,
                rearmBufferRatio, maxConsecutiveStops, zones);
    }

    /** 数值转换（兼容 Integer/Long/Double/字符串），null → 默认值 */
    private static double d(Object v, double def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法数值: " + v);
        }
    }

    /** 正整数转换（maxConsecutiveStops）：必须是有限整数，拒绝小数截断（1.9→1）、NaN、Infinity；null → 默认值 */
    private static int i(Object v, int def) {
        if (v == null) {
            return def;
        }
        double value = d(v, def);
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            throw new IllegalArgumentException("必须为有限整数, 实际: " + v);
        }
        return (int) value;
    }

    /**
     * 严格解析 effectiveFrom（UTC 毫秒时间戳，Week 7 不可妥协项）：
     * <ul>
     *   <li>缺失 → 抛 IllegalArgumentException（提交时必须 400，禁止默认 0 / Long.MIN_VALUE）</li>
     *   <li>必须为正的有限数，且能无损转换为 Long：拒绝小数、NaN、Infinity、字符串非法、超 Long 范围</li>
     * </ul>
     */
    private static long parseEffectiveFrom(Object v, String zoneRef) {
        if (v == null) {
            throw new IllegalArgumentException(zoneRef + " 缺少 effectiveFrom（必须声明区间生效时间）");
        }
        double value;
        if (v instanceof Number) {
            value = ((Number) v).doubleValue();
        } else {
            try {
                value = Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(zoneRef + " effectiveFrom 必须为数值毫秒时间戳, 实际: " + v);
            }
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(zoneRef + " effectiveFrom 不能为 NaN 或 Infinity, 实际: " + v);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(zoneRef + " effectiveFrom 必须为正的毫秒时间戳, 实际: " + v);
        }
        if (value != Math.rint(value)) {
            throw new IllegalArgumentException(zoneRef + " effectiveFrom 必须为整数毫秒时间戳, 实际: " + v);
        }
        if (value > Long.MAX_VALUE) {
            throw new IllegalArgumentException(zoneRef + " effectiveFrom 超出 Long 范围, 实际: " + v);
        }
        return (long) value;
    }

    /** 数值格式化（去无意义小数尾） */
    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.format("%,.0f", v);
        }
        return String.format("%,.2f", v);
    }

    // ==================== 区间状态 ====================

    /** 区间状态机状态（§5） */
    enum ZoneStatus {
        /** 尚未满足重新武装条件，不允许触发 */
        DISARMED,
        /** 价格已明确位于区间上方，等待回落触及并开多 */
        ARMED_ABOVE,
        /** 价格已明确位于区间下方，等待反弹触及并开空 */
        ARMED_BELOW,
        /** 当前仓位由该区间触发，等待引擎平仓 */
        IN_POSITION,
        /** 连续止损达到阈值，本次运行永久禁用 */
        DISABLED
    }

    /** 单个区间运行状态（含配置快照，全部放 ctx，不在 Spring 单例字段） */
    static class ZoneState {
        final String id;
        final double lower;
        final double upper;
        final double width;
        /** 区间中点（触发价 = 计划成交价，多空同一中点） */
        final double mid;
        /** 生效时间（UTC 毫秒时间戳）：bar.time < effectiveFrom 时区间对策略完全不可见（Week 7） */
        final long effectiveFrom;
        /** 是否已生效（bar.time >= effectiveFrom 时置为 true，只置一次） */
        boolean effective = false;
        ZoneStatus status = ZoneStatus.DISARMED;
        int consecutiveStops = 0;

        ZoneState(String id, double lower, double upper, long effectiveFrom) {
            this.id = id;
            this.lower = lower;
            this.upper = upper;
            this.width = upper - lower;
            this.mid = (lower + upper) / 2.0;
            this.effectiveFrom = effectiveFrom;
        }
    }

    /** 每次回测独立的运行时状态（参数 + 所有区间状态） */
    static class ZoneRuntimeState {
        final double riskRate;
        final double rewardRiskRatio;
        final double stopBufferRatio;
        final double rearmBufferRatio;
        final int maxConsecutiveStops;
        final List<ZoneState> zones;

        ZoneRuntimeState(double riskRate, double rewardRiskRatio, double stopBufferRatio,
                         double rearmBufferRatio, int maxConsecutiveStops, List<ZoneState> zones) {
            this.riskRate = riskRate;
            this.rewardRiskRatio = rewardRiskRatio;
            this.stopBufferRatio = stopBufferRatio;
            this.rearmBufferRatio = rearmBufferRatio;
            this.maxConsecutiveStops = maxConsecutiveStops;
            this.zones = zones;
        }

        ZoneState find(String id) {
            for (ZoneState z : zones) {
                if (z.id.equals(id)) {
                    return z;
                }
            }
            return null;
        }
    }
}
