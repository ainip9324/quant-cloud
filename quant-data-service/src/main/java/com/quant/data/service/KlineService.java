package com.quant.data.service;

import com.quant.common.model.Kline;

import java.util.List;
import com.quant.data.market.MarketDataQuery;

/**
 * K线业务服务接口
 * 提供 Redis → MySQL → Bitget 三级查询
 */
public interface KlineService {

    /**
     * 获取K线（三级查询：Redis → MySQL → Bitget API）
     */
    List<Kline> getKlines(MarketDataQuery query);

    /**
     * 同步K线（从交易所拉取并入库，刷新缓存）
     *
     * @return 入库条数
     */
    int syncKlines(MarketDataQuery query);
}
