package com.quant.core.dto;

import com.quant.common.model.BacktestReplay;
import com.quant.common.model.BacktestResult;
import lombok.Data;

import java.util.Map;
import com.quant.common.market.MarketType;

/**
 * 回放查询响应（GET /api/core/backtest/{id}/replay）
 *
 * <p>组合任务信息 + 结果摘要 + 回放快照，避免前端自行拼接多套数据。
 * replay 只来源于该任务回测时持久化的 replay_data 快照，绝不回退为实时 K 线。
 */
@Data
public class BacktestReplayResponse {

    private Long taskId;

    /** 策略标识 */
    private String strategyId;
    private MarketType marketType;

    /** 交易对 */
    private String symbol;

    /** K线周期 */
    private String interval;

    /** 回测起始时间（毫秒） */
    private Long startTs;

    /** 回测结束时间（毫秒） */
    private Long endTs;

    /** 任务配置快照（提交时的策略参数） */
    private Map<String, Object> params;

    /** 结果摘要（returnRate/maxDrawdown/trades/equityCurve，与任务详情一致） */
    private BacktestResult result;

    /** 回放快照（schemaVersion/bars/events） */
    private BacktestReplay replay;
}
