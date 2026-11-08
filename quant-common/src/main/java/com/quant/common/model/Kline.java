package com.quant.common.model;

import lombok.Data;

/**
 * K线数据模型
 * 用于传输K线数据（不直接映射数据库表，数据库实体为 KlineDO）
 */
@Data
public class Kline {
    /** K线开始时间（毫秒时间戳） */
    private Long time;
    /** 开盘价 */
    private double open;
    /** 最高价 */
    private double high;
    /** 最低价 */
    private double low;
    /** 收盘价 */
    private double close;
    /** 成交量 */
    private double volume;
}
