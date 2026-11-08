package com.quant.ai.knowledge;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 用 Redis 统一扣减个人、访客池和全站的每日额度。 */
@Component
class KnowledgeQuota {
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");

    // KEYS 按顺序列出额度键。ARGV 首项是过期时间，后续各项与 KEYS 一一对应。
    // 脚本先检查全部额度再写入，避免某层已满时仍扣掉其他层。返回扣减后各层余额中的最小值。
    private static final String RESERVE = """
            local counts = {}
            local remaining = nil
            for i, key in ipairs(KEYS) do
                local count = tonumber(redis.call('GET', key) or '0')
                local limit = tonumber(ARGV[i + 1])
                if not count or count < 0 or count ~= math.floor(count) then
                    return redis.error_reply('Invalid quota counter')
                end
                if count >= limit then return -1 end
                counts[i] = count
                local after = limit - count - 1
                if not remaining or after < remaining then remaining = after end
            end
            for i, key in ipairs(KEYS) do
                redis.call('SET', key, counts[i] + 1, 'EXAT', ARGV[1])
            end
            return remaining
            """;

    private final KnowledgeProperties properties;
    private final KnowledgeRedis redis;
    private final Clock clock;

    @Autowired
    KnowledgeQuota(KnowledgeProperties properties, KnowledgeRedis redis) {
        this(properties, redis, Clock.systemUTC());
    }

    KnowledgeQuota(KnowledgeProperties properties, KnowledgeRedis redis, Clock clock) {
        this.properties = properties;
        this.redis = redis;
        this.clock = clock;
    }

    /** 原子预扣当天的问答额度，返回本次可用层级中的最小余额与刷新时间。 */
    Reservation reserve(String actor, boolean visitor) {
        var day = clock.instant().atZone(QUOTA_ZONE).toLocalDate();
        Instant resetAt = day.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant();
        String prefix = "knowledge:quota:" + day + ":";
        // 上海日期变更即使用新键。旧键多保留一分钟，供跨零点的在途请求完成扣减。
        String expiry = Long.toString(resetAt.plusSeconds(60).getEpochSecond());
        String total = Integer.toString(properties.totalDailyLimit());

        String[] keys = visitor
                ? new String[]{prefix + actor, prefix + "visitor-pool", prefix + "total"}
                : new String[]{prefix + actor, prefix + "total"};
        String[] limits = visitor
                ? new String[]{
                        expiry,
                        Integer.toString(properties.visitorDailyLimit()),
                        Integer.toString(properties.visitorPoolDailyLimit()),
                        total
                }
                : new String[]{expiry, Integer.toString(properties.accountDailyLimit()), total};

        final Long remaining;
        try {
            remaining = redis.connection().sync().eval(RESERVE, ScriptOutputType.INTEGER, keys, limits);
        } catch (RedisException exception) {
            // 无法确认脚本是否执行时直接失败，重试可能导致重复扣额。
            throw KnowledgeException.unavailable();
        }
        if (remaining == null) {
            throw KnowledgeException.unavailable();
        }
        if (remaining < 0) {
            throw new KnowledgeException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "KNOWLEDGE_QUOTA_EXHAUSTED",
                    "今日问答额度已用完。",
                    resetAt);
        }
        return new Reservation(remaining.intValue(), resetAt);
    }

    record Reservation(int remaining, Instant resetAt) {
    }
}
