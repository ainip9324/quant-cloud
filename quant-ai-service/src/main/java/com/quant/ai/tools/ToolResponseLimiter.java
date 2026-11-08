package com.quant.ai.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.ai.core.CoreGatewayException;
import com.quant.ai.error.AiErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies one UTF-8 budget to each model-visible response. Machine identifiers
 * are never string-truncated: only whole evidence rows may be removed.
 */
@Component
public class ToolResponseLimiter {

    public static final int MAX_BYTES = 48 * 1024;
    private static final List<String> GENERAL_PRUNE_ORDER = List.of(
            "bars", "events", "notableSequences", "tradeSummaries", "zoneSummaries"
    );

    private final ObjectMapper json;

    public ToolResponseLimiter(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Counts are the sum of top-level list elements in the payload. This makes
     * the meaning stable even when an event has nested fields such as details.
     */
    public Map<String, Object> limitResponse(Map<String, Object> source) {
        Map<String, Object> retained = deepCopy(source);
        int originalCount = topLevelListCount(retained);
        boolean truncated = trimWholeRows(retained, GENERAL_PRUNE_ORDER, originalCount, false);
        return withMetadata(retained, truncated, originalCount);
    }

    /**
     * A replay window has exactly one budget pass. Context bars are removed
     * before requested event evidence; all count fields describe final rows.
     */
    public Map<String, Object> limitWindow(List<?> events, List<?> bars) {
        return limitWindow(events, bars, Map.of());
    }
    /** 在含市场标识的回放窗口上执行一次裁剪，优先保留事件证据。 */
    public Map<String, Object> limitWindow(List<?> events, List<?> bars, Map<String, Object> metadata) {
        Map<String, Object> retained = new LinkedHashMap<>();
        retained.putAll(metadata);
        retained.put("events", new ArrayList<>(events));
        retained.put("bars", new ArrayList<>(bars));
        int originalEventCount = events.size();
        int originalBarCount = bars.size();
        int originalCount = originalEventCount + originalBarCount;

        boolean truncated = false;
        while (bytes(windowOutput(retained, truncated, originalCount, originalEventCount, originalBarCount)) > MAX_BYTES) {
            List<Object> target = firstNonEmptyList(retained, List.of("bars", "events"));
            if (target == null) {
                throw tooLarge();
            }
            target.remove(target.size() - 1);
            truncated = true;
        }
        return windowOutput(retained, truncated, originalCount, originalEventCount, originalBarCount);
    }

    /** 按优先顺序逐条裁掉列表末尾的记录，直到输出符合字节预算。 */
    private boolean trimWholeRows(Map<String, Object> retained, List<String> preferredKeys,
                                  int originalCount, boolean window) {
        boolean truncated = false;
        while (bytes(withMetadata(retained, truncated, originalCount)) > MAX_BYTES) {
            List<Object> target = firstNonEmptyList(retained, preferredKeys);
            if (target == null) {
                throw tooLarge();
            }
            target.remove(target.size() - 1);
            truncated = true;
        }
        return truncated;
    }

    /** 按裁剪优先级找到第一个可移除整行的列表。 */
    @SuppressWarnings("unchecked")
    private List<Object> firstNonEmptyList(Map<String, Object> source, List<String> preferredKeys) {
        for (String key : preferredKeys) {
            Object value = source.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return (List<Object>) list;
            }
        }
        return null;
    }

    /** 为工具响应添加裁剪标记和裁剪前后的条数。 */
    private Map<String, Object> withMetadata(Map<String, Object> response, boolean truncated, int originalCount) {
        Map<String, Object> output = new LinkedHashMap<>(response);
        output.put("truncated", truncated);
        output.put("originalCount", originalCount);
        output.put("returnedCount", topLevelListCount(response));
        return output;
    }

    /** 生成回放窗口输出，分别标明事件和 K 线的保留数量。 */
    private Map<String, Object> windowOutput(Map<String, Object> response, boolean truncated,
                                             int originalCount, int originalEventCount, int originalBarCount) {
        Map<String, Object> output = withMetadata(response, truncated, originalCount);
        output.put("originalEventCount", originalEventCount);
        output.put("returnedEventCount", listSize(response.get("events")));
        output.put("originalBarCount", originalBarCount);
        output.put("returnedBarCount", listSize(response.get("bars")));
        return output;
    }

    /** 复制待裁剪的 JSON 结构，避免修改调用方的原始数据。 */
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        return json.convertValue(source, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    /** 统计响应顶层各列表的条数，不计嵌套字段。 */
    private int topLevelListCount(Map<String, Object> source) {
        int count = 0;
        for (Object value : source.values()) {
            if (value instanceof List<?> list) {
                count += list.size();
            }
        }
        return count;
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    /** 计算序列化结果的 UTF-8 字节数，用于判断是否超限。 */
    private int bytes(Object value) {
        try {
            return json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception exception) {
            throw new CoreGatewayException(AiErrorCode.AI_CORE_INVALID_RESPONSE,
                    "tool response serialization failed");
        }
    }

    /** 在无法再移除整条记录时返回体积超限错误。 */
    private CoreGatewayException tooLarge() {
        return new CoreGatewayException(AiErrorCode.AI_TOOL_RESPONSE_TOO_LARGE,
                "tool response cannot fit within the UTF-8 byte limit without altering identifiers");
    }
}
