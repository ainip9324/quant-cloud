package com.quant.core.strategy;

import com.quant.common.enums.PositionSide;
import com.quant.common.model.Account;
import com.quant.common.model.Kline;
import com.quant.common.model.StrategyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略回测上下文（每次回测独立实例，保证并发安全）
 *
 * <p>持有本次回测的 K线历史、策略参数，以及账户的<b>只读视图</b>。
 * 策略通过本类读取持仓状态做风控判断，但拿不到 Account 对象本身（无法修改账户），
 * 改账户仍由 BacktestEngine 执行。
 *
 * <p>合约字段只读访问器：availableBalance / positionMargin / avgEntryPrice / equity / leverage 等。
 * 为兼容旧策略，保留 getBalance()/getEntryPrice() 作为别名。
 *
 * <p>并发安全：多个回测任务并发执行时，各自持有独立的 StrategyContext，Strategy 单例无状态，互不干扰。
 */
public class StrategyContext {

    /** K线历史（时间升序，旧→新），onBar 时已含当前 bar */
    private final List<Kline> history = new ArrayList<>();

    /** 策略参数（从 BacktestTask.params JSON 解析） */
    private final Map<String, Object> params;

    /** 每次回测独立的运行状态（策略私有，如区间状态机/连续止损计数；并发回测互不污染） */
    private final Map<String, Object> state = new HashMap<>();

    /** 账户引用（引擎绑定，策略只读，不对外暴露 Account 对象） */
    private Account account;

    /** 本次回测的决策/执行事件收集器（Week 7；引擎与策略共享，sequence 单调递增） */
    private final List<StrategyEvent> events = new ArrayList<>();

    /** sequence 分配器（每次回测独立，保证并发回测事件互不干扰） */
    private long eventSequence = 0;

    public StrategyContext(Map<String, Object> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }

    /** 引擎绑定账户（策略不应调用） */
    public void bindAccount(Account account) {
        this.account = account;
    }

    // ===== 持仓状态只读访问器（策略用，无法修改账户）=====

    /** 当前持仓数量 */
    public double getPosition() { return account != null ? account.getPosition() : 0; }

    /** 持仓方向（NONE/LONG/SHORT，单向：同一时刻只允许一边） */
    public PositionSide getPositionSide() { return account != null ? account.getPositionSide() : PositionSide.NONE; }

    /** 加权开仓均价 */
    public double getAvgEntryPrice() { return account != null ? account.getAvgEntryPrice() : 0; }

    /** 开仓均价（兼容别名，等同 getAvgEntryPrice） */
    public double getEntryPrice() { return getAvgEntryPrice(); }

    /** 入场次数（开仓=1，每次加仓+1） */
    public int getEntryCount() { return account != null ? account.getEntryCount() : 0; }

    /** 上次入场价 */
    public double getLastEntryPrice() { return account != null ? account.getLastEntryPrice() : 0; }

    /** 可用保证金 */
    public double getAvailableBalance() { return account != null ? account.getAvailableBalance() : 0; }

    /** 可用余额（兼容别名，等同 getAvailableBalance） */
    public double getBalance() { return getAvailableBalance(); }

    /** 当前逐仓占用保证金 */
    public double getPositionMargin() { return account != null ? account.getPositionMargin() : 0; }

    /** 杠杆倍数 */
    public int getLeverage() { return account != null ? account.getLeverage() : 100; }

    /** 总权益 = availableBalance + positionMargin + 浮动盈亏（带方向：多头+(现价-均价)，空头+(均价-现价)） */
    public double getEquity() {
        if (account == null) return 0;
        PositionSide side = account.getPositionSide();
        double sign = side == PositionSide.SHORT ? -1.0 : (side == PositionSide.LONG ? 1.0 : 0.0);
        double unrealizedPnl = account.getPosition() * (current().getClose() - account.getAvgEntryPrice()) * sign;
        return account.getAvailableBalance() + account.getPositionMargin() + unrealizedPnl;
    }

    // ===== K线历史 =====

    /** 追加一根 K线（BacktestEngine 在 onBar 前调用） */
    public void append(Kline bar) {
        history.add(bar);
    }

    /** 当前 K线（history 最后一条） */
    public Kline current() {
        return history.get(history.size() - 1);
    }

    /** 完整历史（含当前），供指标计算（如 ATR/EMA）使用 */
    public List<Kline> history() {
        return history;
    }

    /**
     * 回看过去 n 根（不含当前）
     * 用于 Donchian 通道等"用过去 N 根算信号"的场景
     */
    public List<Kline> lookBack(int n) {
        int size = history.size();
        if (size - 1 < n) {
            return Collections.emptyList();
        }
        return history.subList(size - 1 - n, size - 1);
    }

    /** 已处理的 K线数量（含当前） */
    public int size() {
        return history.size();
    }

    /**
     * 读取策略参数。
     *
     * <p>参数经 DB JSON 反序列化后数字为 BigDecimal（FastJSON 默认），
     * 而策略常用 int/double 局部变量接收（T 推断为 Integer/Double），
     * 直接强转会抛 ClassCastException；此处按默认值类型做安全数值转换。
     */
    @SuppressWarnings("unchecked")
    public <T> T getParam(String key, T defaultValue) {
        Object v = params.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number && defaultValue instanceof Number) {
            Number n = (Number) v;
            if (defaultValue instanceof Double || defaultValue instanceof Float) {
                return (T) Double.valueOf(n.doubleValue());
            }
            if (defaultValue instanceof Long) {
                return (T) Long.valueOf(n.longValue());
            }
            if (defaultValue instanceof Integer) {
                return (T) Integer.valueOf(n.intValue());
            }
            if (defaultValue instanceof Short) {
                return (T) Short.valueOf(n.shortValue());
            }
            if (defaultValue instanceof Byte) {
                return (T) Byte.valueOf(n.byteValue());
            }
        }
        return (T) v;
    }

    // ===== 每次回测独立的运行状态（策略私有，禁止放在 Spring 单例字段中）=====

    /**
     * 保存策略运行状态（如区间状态机、连续止损计数）。
     * <p>并发安全：StrategyContext 每次回测独立创建，多任务并发互不污染。
     */
    public void putState(String key, Object value) {
        state.put(key, value);
    }

    /**
     * 读取策略运行状态（不存在时返回 defaultValue）。
     */
    @SuppressWarnings("unchecked")
    public <T> T getState(String key, T defaultValue) {
        Object v = state.get(key);
        return v != null ? (T) v : defaultValue;
    }

    // ===== Week 7 事件收集器（本次回测独立）=====

    /**
     * 记录一条策略决策/执行事件（引擎与策略共用）。
     *
     * <p>sequence 由本方法自动分配（本次回测内单调递增），事件按产生顺序收集，
     * 保证同一根 K 线内的因果顺序（状态判断 → 计划/拒绝 → 执行/拒绝 → 状态变化）。
     *
     * <p>去重策略：收集器<b>不做全局去重</b>。调用方保证每个状态变化分支只记录一次、
     * 每个引擎执行分支只发送一次事件；测试断言"没有意外重复"。
     *
     * @return 已记录的事件对象（调用方可补填 details 后无需再调）
     */
    public StrategyEvent recordEvent(Long time, String category, String code, String sourceId,
                                     PositionSide positionSide, Double price, String message,
                                     Map<String, Object> details) {
        StrategyEvent e = new StrategyEvent();
        e.setTime(time);
        e.setSequence(++eventSequence);
        e.setCategory(category);
        e.setCode(code);
        e.setSourceId(sourceId);
        e.setPositionSide(positionSide);
        e.setPrice(price);
        e.setMessage(message);
        e.setDetails(details);
        events.add(e);
        return e;
    }

    /**
     * 记录事件（无需价格/方向/明细的简化入口）。
     */
    public StrategyEvent recordEvent(Long time, String category, String code, String sourceId, String message) {
        return recordEvent(time, category, code, sourceId, null, null, message, null);
    }

    /**
     * 读取本次回测的全部事件（按 sequence 升序，即产生顺序；返回副本防外部修改）。
     */
    public List<StrategyEvent> getEvents() {
        return new ArrayList<>(events);
    }
}
