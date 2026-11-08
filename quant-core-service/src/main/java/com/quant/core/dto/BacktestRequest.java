package com.quant.core.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.Map;

/**
 * 回测提交请求
 *
 * 字段说明：
 * - strategyId/symbol/interval 必填
 * - startTs/endTs 可选：指定回测时间范围（毫秒时间戳），未传则按 limit 拉取最新数据
 * - limit 可选：未指定时间范围时的拉取条数，默认 500
 * - params 可选：策略参数（透传给 StrategyContext），未传则用策略默认参数
 */
@Data
public class BacktestRequest {
    private String marketType;

    /** 策略标识，如 turtle */
    private String strategyId;

    /** 交易对，如 BTCUSDT */
    private String symbol;

    /** K线周期，如 1h、4h、1day */
    private String interval;

    /** 回测起始时间（毫秒，可选） */
    private Long startTs;

    /** 回测结束时间（毫秒，可选） */
    private Long endTs;

    /** 拉取条数（可选，默认 500） */
    @JsonDeserialize(using = StrictBacktestLimitDeserializer.class)
    private Integer limit;

    /** 策略参数（可选，透传给 StrategyContext） */
    private Map<String, Object> params;
}
