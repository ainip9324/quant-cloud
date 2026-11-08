package com.quant.core.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.market.LiveMarketSnapshot;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Only Redis is authoritative here; transport errors must never become historical-DB fallback. */
public class RedisLiveMarketSource {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisLiveMarketSource(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public Optional<LiveMarketSnapshot> read(MonitorScope scope) {
        String value = redis.opsForValue().get("market:live:v1:" + scope.key());
        if (value == null) return Optional.empty();
        if (value.length() > 64_000) throw new IllegalStateException("Oversized live snapshot");
        try { return Optional.ofNullable(json.readValue(value, LiveMarketSnapshot.class)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid live snapshot"); }
    }
}
