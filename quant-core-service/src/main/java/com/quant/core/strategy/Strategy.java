package com.quant.core.strategy;

import com.quant.common.model.ExecutionEvent;
import com.quant.common.model.Kline;
import com.quant.common.model.TradePlan;

import java.util.Map;

/**
 * 策略接口抽象（接口抽象和策略扩展）
 *
 * <p>采用事件模型设计：BacktestEngine 逐根 K线回调 {@link #onBarPlan}，策略基于 Context 产生 TradePlan，
 * 引擎据此执行撮合。Week 2 同步执行回测，Week 3 通过 RocketMQ 实现真正事件驱动。
 *
 * <p>职责边界：
 * <ul>
 *   <li>Strategy：根据 K线 + 持仓状态产生 TradePlan（方向/仓位/止损止盈/平仓原因），不关心撮合记账</li>
 *   <li>BacktestEngine：按 TradePlan 执行撮合、账户、持仓、手续费、收益计算</li>
 * </ul>
 *
 * <p>策略可读取 {@link StrategyContext} 的持仓状态（getPosition/getEntryPrice/getEntryCount 等）做风控判断，
 * 但无法修改账户（修改由引擎执行）。
 *
 * <p>并发安全：实现类应为无状态单例（@Component），所有回测状态由 StrategyContext 持有。
 */
public interface Strategy {

    /** 策略标识（用于按 strategyId 路由，如 "turtle"、"ema20"） */
    String getId();

    /**
     * 初始化（每次回测开始前调用一次）
     * 策略可读取 ctx 参数做参数化配置，默认空实现
     */
    default void initialize(StrategyContext ctx) {}

    /**
     * 提交阶段的轻量参数校验。
     *
     * <p>只校验能够在提交请求时立即发现的错误；Worker 仍会在 {@link #initialize}
     * 中执行完整、防御性的参数校验。
     */
    default void validateParameters(Map<String, Object> params) {
    }

    /**
     * 每根 K线回调（交易计划，引擎唯一调用的信号方法）
     *
     * <p>策略返回 {@link TradePlan} 指导引擎执行。引擎据此开仓/加仓/平仓，并自动检查固定止损止盈。
     *
     * @param ctx 回测上下文（history 已含当前 bar，可读持仓状态）
     * @param bar 当前 K线
     * @return 交易计划（HOLD 表示无动作，引擎仍检查止损止盈）
     */
    default TradePlan onBarPlan(StrategyContext ctx, Kline bar) {
        return TradePlan.hold();
    }

    /** 策略所需的最小 K线周期（预热期，不足则 BacktestEngine 不调用 onBarPlan） */
    default int getMinPeriod() {
        return 50;
    }

    /**
     * 执行回报回调（引擎在每笔交易<b>真正执行成功后</b>调用一次，默认空实现保证旧策略兼容）。
     *
     * <p>用途：策略据此维护依赖真实成交结果的状态（如按 sourceId 统计某区间连续止损次数），
     * 不能通过账户状态猜测——计划被拒绝（如保证金不足）不会产生任何回报。
     *
     * @param ctx   本次回测独立的 StrategyContext（可用 putState/getState 保存运行状态）
     * @param event 真实成交回报（OPEN/STOP/TAKE_PROFIT/CLOSE/LIQUIDATION，含 sourceId）
     */
    default void onExecution(StrategyContext ctx, ExecutionEvent event) {
    }

}
