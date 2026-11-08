package com.quant.core.dto;

import com.quant.common.model.BacktestResult;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import com.quant.common.market.MarketType;

/**
 * 回测任务视图（返回给前端）
 *
 * 任务字段 + result（SUCCESS 时由 resultSummary JSON 反序列化得到）。
 * 保持简单：只暴露查询所需的字段，不暴露 params 等内部细节。
 */
@Data
public class BacktestTaskVO {

    private Long id;

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

    /** 任务状态：WAITING/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 失败原因（FAILED 时有值） */
    private String errorMsg;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 回测结果（SUCCESS 时有值，由 resultSummary 反序列化） */
    private BacktestResult result;

    /** 任务配置快照（提交时的策略参数，用于任务详情和复盘） */
    private Map<String, Object> params;
}
