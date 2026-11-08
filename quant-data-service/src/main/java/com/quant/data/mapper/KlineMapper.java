package com.quant.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.data.entity.KlineDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import com.quant.common.market.MarketType;

/**
 * K线 Mapper
 * 核心设计：利用联合唯一索引 (symbol, interval, ts) 实现幂等写入
 */
@Mapper
public interface KlineMapper extends BaseMapper<KlineDO> {

    /**
     * 批量插入（幂等）：ON DUPLICATE KEY UPDATE
     * 利用联合唯一索引，重复数据自动更新而非报错
     */
    @Insert("<script>" +
            "INSERT INTO kline(market_type, symbol, `interval`, ts, open, high, low, close, volume) VALUES " +
            "<foreach collection='list' item='k' separator=','>" +
            "(#{k.marketType},#{k.symbol},#{k.interval},#{k.ts},#{k.open},#{k.high},#{k.low},#{k.close},#{k.volume})" +
            "</foreach>" +
            " ON DUPLICATE KEY UPDATE " +
            "open=VALUES(open),high=VALUES(high),low=VALUES(low),close=VALUES(close),volume=VALUES(volume)" +
            "</script>")
    int batchInsertOnDuplicate(@Param("list") List<KlineDO> list);

    /**
     * 按交易对+周期查询：取最新 limit 条并按时间升序（旧→新）返回
     * 子查询先 DESC LIMIT 取最新 N 条，外层再 ASC 排序，保证返回最新数据且顺序为旧→新
     */
    @Select("SELECT * FROM (SELECT * FROM kline WHERE market_type=#{marketType} AND symbol=#{symbol} AND `interval`=#{interval} ORDER BY ts DESC LIMIT #{limit}) t ORDER BY ts ASC")
    List<KlineDO> selectBySymbolInterval(@Param("marketType") MarketType marketType, @Param("symbol") String symbol,
                                          @Param("interval") String interval,
                                          @Param("limit") int limit);
}
