package com.quant.core.util;

import com.quant.common.market.MarketQueryLimits;
import java.util.HashMap;
import java.util.Map;

/**
 * K线周期工具类
 *
 * 将 interval 字符串（如 "1h"、"4h"、"1day"）转换为毫秒数，
 * 用于根据回测时间范围（startTs ~ endTs）估算需要拉取的 K线条数。
 */
public final class IntervalUtils {

    /** interval → 毫秒 映射（与 Bitget 周期保持一致） */
    private static final Map<String, Long> INTERVAL_MILLIS = new HashMap<>();

    static {
        INTERVAL_MILLIS.put("1min", 60_000L);
        INTERVAL_MILLIS.put("5min", 5 * 60_000L);
        INTERVAL_MILLIS.put("15min", 15 * 60_000L);
        INTERVAL_MILLIS.put("30m", 30 * 60_000L);
        INTERVAL_MILLIS.put("1h", 60 * 60_000L);
        INTERVAL_MILLIS.put("4h", 4 * 60 * 60_000L);
        INTERVAL_MILLIS.put("1day", 24 * 60 * 60_000L);
        INTERVAL_MILLIS.put("1week", 7 * 24 * 60 * 60_000L);
    }

    private IntervalUtils() {
    }

    /**
     * interval 转毫秒
     *
     * @param interval K线周期，如 "1h"
     * @return 毫秒数；未知周期返回 0
     */
    public static long toMillis(String interval) {
        return INTERVAL_MILLIS.getOrDefault(interval, 0L);
    }

    /**
     * 根据时间范围估算需要拉取的 K线条数
     *
     * limit = (endTs - startTs) / periodMillis + buffer
     * - buffer=50：预留预热期（策略最小周期 + ATR 周期）所需的额外数据
     * - 上下限遵循 {@link MarketQueryLimits}：与 data-service 查询契约一致
     *
     * @param startTs      回测起始时间（毫秒），可为空
     * @param endTs        回测结束时间（毫秒），可为空
     * @param interval     K线周期
     * @param defaultLimit 未指定时间范围时的默认条数
     * @return 估算的 limit
     */
    public static int estimateLimit(Long startTs, Long endTs, String interval, int defaultLimit) {
        // 未指定时间范围，用默认值
        if (startTs == null || endTs == null) {
            return defaultLimit;
        }
        long periodMillis = toMillis(interval);
        if (periodMillis <= 0) {
            return defaultLimit;
        }
        long range = endTs - startTs;
        if (range <= 0) {
            return defaultLimit;
        }
        // +50 预留预热期缓冲
        int limit = (int) (range / periodMillis) + 50;
        return Math.max(MarketQueryLimits.MIN_LIMIT, Math.min(limit, MarketQueryLimits.MAX_LIMIT));
    }
}
