package com.quant.common.model;

import lombok.Data;

/**
 * 回测引擎组合输出（Week 7）
 *
 * <p>保持 {@link BacktestResult} 只承载收益/回撤/权益/交易记录（result_summary），
 * 回放快照（K 线 + 策略事件）单独通过 {@link BacktestReplay} 承载（replay_data）。
 * Processor 成功后必须将两者在<b>同一条带 execution_token 条件</b>的更新中原子落库。
 */
@Data
public class BacktestRunOutput {

    /** 结果摘要（收益/回撤/权益曲线/交易记录） */
    private BacktestResult summary;

    /** 回放快照（引擎实际使用的 K 线 + 结构化事件） */
    private BacktestReplay replay;
}
