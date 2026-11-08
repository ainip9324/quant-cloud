package com.quant.data.service.impl;

import com.alibaba.fastjson2.JSON;
import com.quant.common.model.Kline;
import com.quant.data.entity.KlineDO;
import com.quant.data.mapper.KlineMapper;
import com.quant.data.service.KlineService;
import com.quant.data.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.quant.data.market.MarketDataQuery;
import com.quant.common.market.MarketType;

/**
 * K线业务服务实现
 *
 * 核心设计：三级查询（Redis → MySQL → Bitget API）
 * - 第一级：Redis 缓存（TTL 1小时），按 (symbol, interval, limit) 隔离，命中且未过期才返回
 * - 第二级：MySQL 查询（取最新 limit 条），命中且未过期才回填缓存并返回
 * - 第三级：调用 Bitget API，入库 + 写缓存
 *
 * 数据新鲜度：通过 isStale 判断最新K线时间戳是否在 2 个周期内，
 *             超过则视为过期，继续向下游更新，保证长时间后再访问能拿到最新行情
 */
@Slf4j
@Service
public class KlineServiceImpl implements KlineService {

    @Autowired
    private KlineMapper klineMapper;

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String CACHE_KEY_PREFIX = "kline:v2:";
    private static final long CACHE_TTL_SECONDS = 3600; // 1小时

    @Override
    public List<Kline> getKlines(MarketDataQuery query) {
        String symbol=query.selection().symbol(), interval=query.selection().interval(); int limit=query.limit();
        String cacheKey = buildCacheKey(query);

        // 1. 查 Redis 缓存（命中且未过期才返回）
        List<Kline> cached = getFromRedis(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            if (!isStale(cached, interval)) {
                log.debug("K线命中 Redis 缓存: {}", cacheKey);
                return cached;
            }
            log.info("Redis 缓存已过期，从下游更新: {}", cacheKey);
        }

        // 2. 查 MySQL（取最新 limit 条，升序）；命中且未过期才返回
        List<Kline> retainedHistory = List.of();
        MarketDataQuery upstreamQuery = query;
        List<KlineDO> dbData = klineMapper.selectBySymbolInterval(query.selection().marketType(), symbol, interval, limit);
        if (dbData != null && dbData.size() >= limit) {
            List<Kline> klines = convertToModel(dbData);
            if (!isStale(klines, interval)) {
                setToRedis(cacheKey, klines);
                log.debug("K线命中 MySQL，已回填缓存: {}", cacheKey);
                return klines;
            }
            log.info("MySQL 数据已过期，从 Bitget 更新: {} {}", symbol, interval);
            if (query.selection().marketType() == MarketType.USDT_FUTURES) {
                retainedHistory = klines;
                long missingPeriods = Math.max(0, (System.currentTimeMillis() - klines.get(klines.size() - 1).getTime())
                        / parseIntervalToMillis(interval));
                int refreshLimit = (int) Math.min(limit, Math.max(2L, missingPeriods + 2));
                upstreamQuery = MarketDataQuery.of(query.selection().marketType().name(), symbol, interval, refreshLimit);
            }
        }

        // 3. 拉 Bitget API + 入库 + 写缓存
        log.info("K线未命中缓存或已过期，从 Bitget 拉取: {} {} limit={}", symbol, interval, limit);
        List<Kline> apiData = marketDataService.getKlines(upstreamQuery);

        // 转换为 DO 并入库（幂等写入）
        List<KlineDO> doList = convertToDO(apiData, query);
        if (!doList.isEmpty()) {
            klineMapper.batchInsertOnDuplicate(doList);
        }

        // A complete historical window only needs its stale tail refreshed.
        List<Kline> result = apiData;
        if (!retainedHistory.isEmpty()) {
            TreeMap<Long, Kline> merged = new TreeMap<>();
            retainedHistory.forEach(bar -> merged.put(bar.getTime(), bar));
            apiData.forEach(bar -> merged.put(bar.getTime(), bar));
            result = merged.values().stream().skip(Math.max(0, merged.size() - limit)).toList();
        }
        setToRedis(cacheKey, result);
        return result;
    }

    @Override
    public int syncKlines(MarketDataQuery query) {
        String symbol=query.selection().symbol(), interval=query.selection().interval(); int limit=query.limit();
        log.info("同步K线: {} {} limit={}", symbol, interval, limit);

        // 从 Bitget 拉取
        List<Kline> data = marketDataService.getKlines(query);

        // 入库（幂等）
        List<KlineDO> doList = convertToDO(data, query);
        int inserted = 0;
        if (!doList.isEmpty()) {
            inserted = klineMapper.batchInsertOnDuplicate(doList);
        }

        // 刷新缓存：删除该 symbol+interval 的所有 limit 变体缓存（避免不同查询量缓存不一致）
        evictCacheByPattern(query);
        setToRedis(buildCacheKey(query), data);

        log.info("同步K线完成: {} {}, 入库 {} 条", symbol, interval, inserted);
        return inserted;
    }

    // ===== 私有方法 =====

    private String buildCacheKey(MarketDataQuery query) {
        // 缓存按 (symbol, interval, limit) 隔离，避免不同查询量互相污染
        String key = CACHE_KEY_PREFIX + query.selection().marketType() + ":" + query.selection().symbol() + ":" + query.selection().interval() + ":" + query.limit();
        // Old Futures entries can be fresh but contain only ~90 daily bars.
        // Existing market-scoped sync/live invalidation also matches this suffix.
        return query.selection().marketType() == MarketType.USDT_FUTURES ? key + ":history-v1" : key;
    }

    /**
     * 删除指定 symbol+interval 的所有缓存（覆盖不同 limit 变体）
     * 同步数据后调用，保证下次查询走 MySQL（最新）
     */
    private void evictCacheByPattern(MarketDataQuery query) {
        String pattern = CACHE_KEY_PREFIX + query.selection().marketType() + ":" + query.selection().symbol() + ":" + query.selection().interval() + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("清除缓存: pattern={}, count={}", pattern, keys.size());
        }
    }

    /**
     * 判断数据是否过期：最新K线 ts 距当前时间超过 2 个周期则视为过期
     */
    private boolean isStale(List<Kline> data, String interval) {
        if (data == null || data.isEmpty()) {
            return true;
        }
        long latestTs = data.get(data.size() - 1).getTime(); // 升序，最后一条最新
        long periodMillis = parseIntervalToMillis(interval);
        return System.currentTimeMillis() - latestTs > 2 * periodMillis;
    }

    /**
     * interval 字符串转毫秒（与 Bitget granularity 取值对齐）
     */
    private static long parseIntervalToMillis(String interval) {
        if (interval == null) {
            return 60 * 60_000L; // 默认1小时
        }
        return switch (interval) {
            case "1min", "1m" -> 60_000L;
            case "5min", "5m" -> 5 * 60_000L;
            case "15min", "15m" -> 15 * 60_000L;
            case "30min", "30m" -> 30 * 60_000L;
            case "1h" -> 60 * 60_000L;
            case "4h" -> 4 * 60 * 60_000L;
            case "1day" -> 24 * 60 * 60_000L;
            case "1week" -> 7 * 24 * 60 * 60_000L;
            default -> 60 * 60_000L;
        };
    }

    private List<Kline> getFromRedis(String key) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        return JSON.parseArray(json, Kline.class);
    }

    private void setToRedis(String key, List<Kline> data) {
        try {
            String json = JSON.toJSONString(data);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写 Redis 缓存失败: {}", e.getMessage());
        }
    }

    /** KlineDO → Kline 模型转换 */
    private List<Kline> convertToModel(List<KlineDO> doList) {
        return doList.stream().map(d -> {
            Kline k = new Kline();
            k.setTime(d.getTs());
            k.setOpen(d.getOpen());
            k.setHigh(d.getHigh());
            k.setLow(d.getLow());
            k.setClose(d.getClose());
            k.setVolume(d.getVolume());
            return k;
        }).collect(Collectors.toList());
    }

    /** Kline 模型 → KlineDO 转换（补充 symbol 和 interval） */
    private List<KlineDO> convertToDO(List<Kline> klines, MarketDataQuery query) {
        List<KlineDO> list = new ArrayList<>();
        for (Kline k : klines) {
            KlineDO d = new KlineDO();
            d.setMarketType(query.selection().marketType()); d.setSymbol(query.selection().symbol()); d.setInterval(query.selection().interval());
            d.setTs(k.getTime());
            d.setOpen(k.getOpen());
            d.setHigh(k.getHigh());
            d.setLow(k.getLow());
            d.setClose(k.getClose());
            d.setVolume(k.getVolume());
            list.add(d);
        }
        return list;
    }
}
