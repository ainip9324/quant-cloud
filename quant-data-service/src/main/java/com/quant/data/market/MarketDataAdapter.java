package com.quant.data.market;
import com.quant.common.market.MarketType;
import com.quant.common.model.Kline;
import java.util.List;
public interface MarketDataAdapter { MarketType marketType(); List<Kline> fetch(MarketDataQuery query); }
