package com.quant.common.model;

import lombok.Data;

/**
 * 回测任务模型（对应 backtest_task 表）
 * 体现异步任务状态流转：WAITING → RUNNING → SUCCESS/FAILED
 */
@Data
public class BacktestTask {
    private Long id;
    /** 策略标识 */
    private String strategyId;
    /** 交易对 */
    private String symbol;
    /** K线周期 */
    private String interval;
    /** 回测起始时间（毫秒） */
    private Long startTs;
    /** 回测结束时间（毫秒） */
    private Long endTs;
    /** 策略参数（JSON） */
    private String params;
    /** 任务状态：WAITING/RUNNING/SUCCESS/FAILED */
    private String status;
    /** 结果摘要（JSON） */
    private String resultSummary;
    /** 失败原因 */
    private String errorMsg;
    private Long createTime;
    private Long updateTime;
}
