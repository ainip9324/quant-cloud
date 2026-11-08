package com.quant.data.service;

import com.quant.common.model.Kline;

import java.util.List;
import com.quant.data.market.MarketDataQuery;

/**
 * 交易所行情数据服务接口
 */
public interface MarketDataService {

    /**
     * 获取K线数据
     *
     * @param symbol   交易对，如 BTCUSDT
     * @param interval 周期，如 1h、4h、1day
     * @param limit    数量
     */
    List<Kline> getKlines(MarketDataQuery query);
}
