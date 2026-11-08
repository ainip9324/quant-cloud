package com.quant.ai.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.market.LiveMarketSnapshot;
import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketType;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只读查询共享盘面上受支持的公开成交价，由服务端校验并格式化报价。 */
@Component
class KnowledgeQuoteTool {
    private static final String NAME = "getPublicQuote";
    private static final String INTERVAL = "1h";
    private static final MarketType MARKET = MarketType.USDT_FUTURES;
    private static final List<String> SYMBOLS = MarketCatalog.supportedSymbols(MARKET);
    private static final long STALE_MILLIS = 10_000;
    private static final int MAX_SNAPSHOT_CHARS = 64_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final QuoteAnswer UNAVAILABLE =
            new QuoteAnswer("INSUFFICIENT", "暂时没有可用的实时行情，请稍后再试。");

    private final KnowledgeRedis redis;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    KnowledgeQuoteTool(KnowledgeRedis redis, ObjectMapper json, MeterRegistry metrics,
            @Value("${ai.knowledge.public-quote-enabled:false}") boolean enabled) {
        this(redis, json, metrics, enabled, Clock.systemUTC());
    }

    KnowledgeQuoteTool(KnowledgeRedis redis, ObjectMapper json, MeterRegistry metrics, boolean enabled, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.metrics = metrics;
        this.enabled = enabled;
        this.clock = clock;
    }

    boolean enabled() {
        return enabled;
    }

    /** 模型只看到工具名称和受支持的交易对参数。 */
    static FunctionTool definition() {
        return new FunctionTool(new FunctionTool.Function(
                "查询 Bitget USDT 永续合约的最新成交价，仅用于明确询问单个支持标的当前报价。",
                NAME,
                Map.of("type", "object", "required", List.of("symbol"), "additionalProperties", false,
                        "properties", Map.of("symbol", symbolSchema())),
                null));
    }

    /** 两种报价请求共用同一份标的范围。 */
    static Map<String, Object> symbolSchema() {
        return Map.of("type", "string", "enum", SYMBOLS);
    }

    /** 只执行一次符合约定的工具请求，拒绝未知名称、多调用和多余参数。 */
    QuoteAnswer execute(List<ToolCall> calls) throws JsonProcessingException {
        if (!enabled || calls.size() != 1) {
            throw KnowledgeException.modelFailed();
        }
        var call = calls.get(0);
        if (!NAME.equals(call.name()) || !"function".equals(call.type())
                || call.arguments() == null || call.arguments().length() > 256) {
            throw KnowledgeException.modelFailed();
        }
        var args = json.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(call.arguments());
        if (args == null || !args.isObject() || args.size() != 1 || !args.path("symbol").isTextual()) {
            throw KnowledgeException.modelFailed();
        }
        return quote(args.path("symbol").textValue());
    }

    /** 用已核对身份和时间的快照生成报价，记录工具耗时及固定枚举结果。 */
    QuoteAnswer quote(String symbol) {
        if (!enabled || !SYMBOLS.contains(symbol)) {
            throw KnowledgeException.modelFailed();
        }
        var sample = Timer.start(metrics);
        String outcome = "unavailable";
        try {
            String raw = redis.read("market:live:v1:" + MARKET + ":" + symbol + ":" + INTERVAL);
            if (raw == null || raw.length() > MAX_SNAPSHOT_CHARS) {
                return UNAVAILABLE;
            }
            var snapshot = json.readerFor(LiveMarketSnapshot.class)
                    .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .<LiveMarketSnapshot>readValue(raw);
            if (!isCurrent(snapshot, symbol)) {
                return UNAVAILABLE;
            }
            outcome = "success";
            return new QuoteAnswer("LIVE_QUOTE", "Bitget " + symbol + " USDT 永续合约最新成交价为 "
                    + snapshot.lastPrice().stripTrailingZeros().toPlainString() + " USDT。行情时间（北京时间）："
                    + TIME.format(Instant.ofEpochMilli(snapshot.priceTime())) + "。");
        } catch (RedisException | JsonProcessingException exception) {
            outcome = "read_error";
            return UNAVAILABLE;
        } finally {
            sample.stop(metrics.timer("ai.knowledge.quote", "outcome", outcome));
        }
    }

    /** 核对标的、价格及行情时间，避免把旧快照或其他标的的值当作报价。 */
    private boolean isCurrent(LiveMarketSnapshot snapshot, String symbol) {
        long now = clock.millis();
        return snapshot != null && snapshot.status() == LiveMarketSnapshot.Status.LIVE
                && snapshot.marketType() == MARKET && symbol.equals(snapshot.symbol())
                && INTERVAL.equals(snapshot.interval()) && snapshot.sequence() > 0
                && validPrice(snapshot.lastPrice()) && fresh(snapshot.priceTime(), now)
                && fresh(snapshot.priceReceivedAt(), now) && fresh(snapshot.emittedAt(), now);
    }

    private static boolean fresh(Long time, long now) {
        return time != null && time > 0 && time >= now - STALE_MILLIS && time <= now + STALE_MILLIS;
    }

    private static boolean validPrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || (long) price.precision() - price.scale() > 20) {
            return false;
        }
        return price.stripTrailingZeros().scale() <= 18;
    }

    record QuoteAnswer(String status, String text) {}
}
