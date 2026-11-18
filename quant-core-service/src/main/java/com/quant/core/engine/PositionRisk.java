package com.quant.core.engine;

import com.quant.common.model.Account;
import com.quant.common.enums.PositionSide;
import com.quant.common.model.TradePlan;
import com.quant.core.strategy.TradeSignal;

/** 回测风控模块，为开仓和加仓计算数量及资金需求。 */
final class PositionRisk {

    private PositionRisk() {}

    enum Rejection {
        INVALID_AMOUNT,
        NO_AVAILABLE_BALANCE,
        INSUFFICIENT_MARGIN,
        MAX_NOTIONAL_EXPOSURE,
        MAX_POSITION_RISK
    }

    /** 准入时返回记账所需的数值，拒绝时返回原因及对应数值。 */
    record Assessment(double amount, double margin, double fee, Rejection rejection, AddRisk addRisk) {}

    record AddRisk(double existing, double additional, double limit) {
        double total() { return existing + additional; }
    }

    /** 按账户当前记录的权益或可用余额定仓，再检查保证金和手续费。 */
    static Assessment assess(Account account, TradePlan plan, double price, double feeRate) {
        return assess(account, plan, price, feeRate, null);
    }

    /** 检查方案配置的仓位上限，以及保证金和手续费是否充足。 */
    static Assessment assess(Account account, TradePlan plan, double price, double feeRate, RiskConfiguration limits) {
        double available = account.getAvailableBalance();
        double base = plan.getSizingBase() == TradePlan.SizingBase.CASH
                ? available : account.getEquity();
        double amount = plan.getSizingMode() == TradePlan.SizingMode.RISK_PER_PRICE_UNIT
                ? base * plan.getRiskRate() / plan.getRiskDistance()
                : base * plan.getPositionRatio() / price;

        if (!Double.isFinite(amount) || amount <= 0) {
            return new Assessment(amount, 0, 0, Rejection.INVALID_AMOUNT, null);
        }
        if (available <= 0) {
            return new Assessment(amount, 0, 0, Rejection.NO_AVAILABLE_BALANCE, null);
        }

        double notional = amount * price;
        double margin = notional / account.getLeverage();
        double fee = notional * feeRate;
        AddRisk addRisk = limits != null && plan.getAction() == TradePlan.Action.ADD
                ? new AddRisk(account.getPosition() * Math.max(0, stopDistance(account, account.getAvgEntryPrice())),
                        amount * stopDistance(account, price), account.getEquity() * limits.maxPositionRiskRatio())
                : null;
        if (limits != null && limits.maxNotionalRatio() != null
                && (account.getPosition() + amount) * price > account.getEquity() * limits.maxNotionalRatio()) {
            return new Assessment(amount, margin, fee, Rejection.MAX_NOTIONAL_EXPOSURE, addRisk);
        }
        if (addRisk != null && addRisk.total() > addRisk.limit()) {
            return new Assessment(amount, margin, fee, Rejection.MAX_POSITION_RISK, addRisk);
        }
        if (available < margin + fee) {
            return new Assessment(amount, margin, fee, Rejection.INSUFFICIENT_MARGIN, addRisk);
        }
        return new Assessment(amount, margin, fee, null, addRisk);
    }

    /** 用实际候选成交价生成保护价格和定仓计划，策略信号无需携带价格或数量。 */
    static TradePlan plan(TradeSignal signal, RiskConfiguration config, double price, Account account, double signalAtr) {
        if (signal == TradeSignal.CLOSE) return TradePlan.close();
        if (signal == TradeSignal.HOLD) return TradePlan.hold();
        if (signal == TradeSignal.ADD) {
            double distance = stopDistance(account, price);
            if (account.getStopPrice() <= 0 || !(distance > 0)) return TradePlan.add(0);
            var plan = config.preset() == RiskConfiguration.Preset.STOP_RISK
                    ? TradePlan.addRisk(config.allocation(), distance, TradePlan.SizingBase.EQUITY)
                    : TradePlan.add(config.allocation());
            plan.setEntryPrice(price);
            plan.setStopPrice(account.getStopPrice());
            plan.setTakeProfitPrice(account.getTakeProfitPrice());
            return plan;
        }
        boolean shortSide = signal == TradeSignal.OPEN_SHORT;
        double distance = config.usesAtr() ? signalAtr * config.atrMultiplier() : price * config.stopLossRatio();
        double stop = price + (shortSide ? distance : -distance);
        double takeProfit = price + (shortSide ? -distance : distance) * config.rewardRiskRatio();
        if (config.usesAtr() && (!Double.isFinite(stop) || stop <= 0 || stop == price
                || !Double.isFinite(takeProfit) || takeProfit <= 0 || takeProfit == price)) {
            return null;
        }
        if (!Double.isFinite(takeProfit) || takeProfit <= 0) {
            throw new IllegalArgumentException("风控参数生成的止盈价格无效");
        }
        TradePlan plan = shortSide ? TradePlan.openShort(config.allocation(), stop, takeProfit)
                : TradePlan.openLong(config.allocation(), stop, takeProfit);
        if (config.preset() == RiskConfiguration.Preset.STOP_RISK) {
            plan.setSizingMode(TradePlan.SizingMode.RISK_PER_PRICE_UNIT);
            plan.setRiskRate(config.allocation());
            plan.setRiskDistance(distance);
        }
        plan.setEntryPrice(price);
        return plan;
    }

    /** 加仓沿用现仓保护价，空头距离与多头镜像。 */
    private static double stopDistance(Account account, double price) {
        return account.getPositionSide() == PositionSide.SHORT
                ? account.getStopPrice() - price : price - account.getStopPrice();
    }
}
