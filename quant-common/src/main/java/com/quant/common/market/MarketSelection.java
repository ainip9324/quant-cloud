package com.quant.common.market;

/** Validated market identity. Only MarketCatalog may construct it. */

/**
 * 市场选择
 * 用于选择具体的市场，包括市场类型、合约符号和时间间隔。
 * @author HiKi
 */
public final class MarketSelection {
    private final MarketType marketType; private final String symbol; private final String interval;
    MarketSelection(MarketType marketType, String symbol, String interval) { this.marketType=marketType; this.symbol=symbol; this.interval=interval; }
    public MarketType marketType() { return marketType; } public String symbol() { return symbol; } public String interval() { return interval; }
}
