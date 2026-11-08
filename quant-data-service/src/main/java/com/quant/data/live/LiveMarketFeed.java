package com.quant.data.live;

import com.quant.common.market.LiveMarketSnapshot;
import java.util.Optional;

/** Exchange-independent read surface. Subscription lifecycle is owned by the application. */
public interface LiveMarketFeed {
    Optional<LiveMarketSnapshot> currentSnapshot(String marketType, String symbol, String interval);
}
