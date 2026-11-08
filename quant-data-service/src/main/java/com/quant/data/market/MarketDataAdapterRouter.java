package com.quant.data.market;
import com.quant.common.market.MarketType;
import com.quant.common.model.Kline;
import com.quant.data.service.MarketDataService;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class MarketDataAdapterRouter implements MarketDataService {
 private final Map<MarketType,MarketDataAdapter> adapters;
 public MarketDataAdapterRouter(List<MarketDataAdapter> values) { Map<MarketType,MarketDataAdapter> map=new EnumMap<>(MarketType.class); for(var adapter:values) { if(map.put(adapter.marketType(),adapter)!=null) throw new IllegalStateException("duplicate market adapter"); } if(map.size()!=MarketType.values().length) throw new IllegalStateException("missing market adapter"); adapters=Map.copyOf(map); }
 public List<Kline> getKlines(MarketDataQuery query) { return adapters.get(query.selection().marketType()).fetch(query); }
}
