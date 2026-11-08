package com.quant.common.market;

/**
 * Shared backend contract for the number of candles in one market-data query.
 *
 * <p>Keep core submission, asynchronous execution and data validation aligned.
 * Exchange page sizes and workspace display capacity are separate policies;
 * raising this ceiling also requires reviewing adapters and frontend inputs.
 */
public final class MarketQueryLimits {
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 1000;
    public static final int DEFAULT_BACKTEST_LIMIT = 500;
    public static final String INVALID_LIMIT_MESSAGE =
            "limit must be an integer between " + MIN_LIMIT + " and " + MAX_LIMIT;

    private MarketQueryLimits() {
    }

    /** Data queries require an explicit limit; their HTTP defaults belong to the endpoint. */
    public static int requireLimit(Integer limit) {
        if (limit == null || limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new InvalidMarketQueryException(INVALID_LIMIT_MESSAGE);
        }
        return limit;
    }

    /** Normalize missing backtest input before persisting a WAITING task. */
    public static int normalizeBacktestLimit(Integer limit) {
        return requireLimit(limit == null ? DEFAULT_BACKTEST_LIMIT : limit);
    }
}
