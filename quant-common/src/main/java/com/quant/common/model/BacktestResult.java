package com.quant.common.model;

import lombok.Data;

import java.util.List;

/**
 * 回测结果（存入 backtest_task.result_summary JSON 字段）
 *
 * 保持简单：收益率、最大回撤、交易记录、权益曲线。
 * 不扩展额外量化指标（夏普/胜率等）。
 */
@Data
public class BacktestResult {

    /** 收益率（%） */
    private double returnRate;

    /** 最大回撤（%） */
    private double maxDrawdown;

    /** 权益曲线（首个点为初始资金，末个点为最终权益，每点含K线时间戳） */
    private List<EquityPoint> equityCurve;

    /** 交易明细 */
    private List<Trade> trades;

    /** 杠杆倍数（回测配置快照，供历史任务展示） */
    private int leverage;

    /** 初始资金（回测配置快照，供历史任务展示） */
    private double initialCapital;
}
