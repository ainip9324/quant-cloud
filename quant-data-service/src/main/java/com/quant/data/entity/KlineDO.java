package com.quant.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.quant.common.market.MarketType;

/**
 * K线数据库实体（对应 kline 表）
 * 注意：数据库列名为 ts，模型字段为 time，需在 Service 层做转换
 */
@Data
@TableName("kline")
public class KlineDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private MarketType marketType;
    private String symbol;
    /** MyBatis-Plus 默认将 interval 映射为 INTERVAL 关键字，需注意 */
    private String interval;
    /** K线开始时间（毫秒） */
    private Long ts;
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Double volume;
    private java.util.Date createTime;
}
