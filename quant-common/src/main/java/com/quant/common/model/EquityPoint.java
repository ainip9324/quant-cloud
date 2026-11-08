package com.quant.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权益曲线上的一个点（K线时间 + 权益值）
 *
 * <p>替代原来的 List<Double>，前端无需自行推算时间轴。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EquityPoint {

    /** K线时间戳（毫秒），与 Kline.time 对应 */
    private long time;

    /** 该时点的总权益 */
    private double equity;
}
