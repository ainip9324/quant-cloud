package com.quant.core.strategy;

import com.quant.common.enums.PositionSide;
import com.quant.common.model.Kline;
import com.quant.common.model.StrategyEvent;
import com.quant.core.zone.ChartObjectHistory;
import java.util.Comparator;
import java.util.Map;

/** 水平区域和通道共用收盘反转；没有数量或保护价格。 */
public final class ChartZoneSignals {
    private ChartZoneSignals() {}
    private record Candidate(ChartObjectHistory.Revision revision, TradeSignal action, double distance) {}

    /** 实时提醒只判断单个有效对象的形态，不依赖回测账户。 */
    public static TradeSignal liveSignal(ChartObjectHistory.Revision zone, Kline previous, Kline bar, long period) {
        long closeTime = bar.getTime() + period;
        if (zone.effectiveFrom() > bar.getTime()
                || zone.effectiveUntil() != null && closeTime >= zone.effectiveUntil()) return TradeSignal.HOLD;
        long priorTime = previous == null ? 0 : previous.getTime() + period;
        return candidate(zone, previous, priorTime, bar, closeTime, zone.channel()).action();
    }

    /** 用任务版本判断触及收回；按候选距离及对象 ID 决定采用哪个对象。 */
    static SignalDecision decide(StrategyContext ctx, Kline bar) {
        if (ctx.getPositionSide() != PositionSide.NONE) return SignalDecision.of(TradeSignal.HOLD);
        ChartObjectHistory history = ctx.getState("chartObjectHistory", null);
        Kline previous = ctx.size() >= 2 ? ctx.history().get(ctx.size() - 2) : null;
        boolean singleBarChannel = ctx.getState("singleBarChannelEntry", false);
        long period = ctx.getState("chartObjectPeriod", 0L);
        long priorTime = previous == null ? 0 : previous.getTime() + period;
        long closeTime = bar.getTime() + period;
        Candidate selected = history.covering(bar.getTime(), closeTime).stream()
                .map(r -> candidate(r, previous, priorTime, bar, closeTime, singleBarChannel && r.channel()))
                .filter(c -> c.action() != TradeSignal.HOLD)
                .min(Comparator.comparingDouble(Candidate::distance).thenComparingLong(c -> c.revision().id()))
                .orElse(null);
        if (selected == null) return SignalDecision.of(TradeSignal.HOLD);
        var r = selected.revision();
        if (r.channel()) {
            var details = new java.util.HashMap<String, Object>(Map.of(
                    "sourceRevision", r.revision(), "confirmedAt", closeTime,
                    "lowerAtClose", r.lowerAt(closeTime), "upperAtClose", r.upperAt(closeTime),
                    "approximation", "CLOSE_BOUNDARY_OHLC"));
            if (singleBarChannel) {
                details.put("entryRule", "SINGLE_BAR_RETEST");
            } else {
                details.put("previousLower", r.lowerAt(priorTime));
                details.put("previousUpper", r.upperAt(priorTime));
            }
            ctx.recordEvent(bar.getTime(), StrategyEvent.CATEGORY_DECISION, "CHANNEL_TOUCH_APPROXIMATED",
                    Long.toString(r.id()), ctx.getPositionSide(), null,
                    singleBarChannel ? "通道单根触及并收回，按收盘边界与本根 OHLC 近似判断"
                            : "通道触及按收盘边界与本根 OHLC 近似判断，非逐笔精确触线", details);
        }
        return new SignalDecision(selected.action(), Long.toString(r.id()), r.revision());
    }

    /** 通道新版仅判断本根，其余对象和历史版本仍要求前收在区间外。 */
    private static Candidate candidate(ChartObjectHistory.Revision r, Kline previous, long priorTime,
                                       Kline bar, long closeTime, boolean singleBar) {
        double lower = r.lowerAt(closeTime), upper = r.upperAt(closeTime);
        if (!(bar.getLow() <= upper && bar.getHigh() >= lower)) return new Candidate(r, TradeSignal.HOLD, 0);
        if (singleBar) {
            if (bar.getClose() > upper) return new Candidate(r, TradeSignal.OPEN_LONG, bar.getClose() - upper);
            if (bar.getClose() < lower) return new Candidate(r, TradeSignal.OPEN_SHORT, lower - bar.getClose());
            return new Candidate(r, TradeSignal.HOLD, 0);
        }
        // 缺失行情可能使前收早于通道起点，不能向左外推来制造比较边界。
        if (previous == null || r.channel() && priorTime < r.effectiveFrom()) return new Candidate(r, TradeSignal.HOLD, 0);
        double priorClose = previous.getClose();
        double previousLower = r.lowerAt(priorTime), previousUpper = r.upperAt(priorTime);
        if (priorClose > previousUpper && bar.getClose() > upper) {
            return new Candidate(r, TradeSignal.OPEN_LONG, priorClose - previousUpper);
        }
        if (priorClose < previousLower && bar.getClose() < lower) {
            return new Candidate(r, TradeSignal.OPEN_SHORT, previousLower - priorClose);
        }
        return new Candidate(r, TradeSignal.HOLD, 0);
    }
}
