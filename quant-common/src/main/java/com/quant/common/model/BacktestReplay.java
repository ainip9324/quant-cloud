package com.quant.common.model;

import lombok.Data;

import java.util.List;

/**
 * 回测回放快照（Week 7，独立存储于 backtest_task.replay_data JSON 列）
 *
 * <p>回放接口只返回这份快照，禁止打开页面时重新拉取实时 K 线拼接结果。
 * 不要把 bars 塞进 result_summary：历史列表会反序列化 result_summary，
 * 加入最多 1000 根 K 线会显著放大列表响应。
 */
@Data
public class BacktestReplay {

    /** 快照结构版本（本阶段为 1） */
    private int schemaVersion = 1;

    /** 本次回测实际使用、按时间升序的 K 线列表（引擎收到的过滤后快照） */
    private List<Kline> bars;

    /** 按 sequence（time、产生顺序）稳定排序的决策/执行事件列表 */
    private List<StrategyEvent> events;
}
