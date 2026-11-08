package com.quant.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 将等价输入规范化为稳定JSON，用于报告指纹和提示词中的事实序列化。 */
@Component
public class CanonicalJsonHasher {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** 对规范化后的 UTF-8 JSON 计算 SHA-256，作为报告输入指纹的一部分。 */
    public String hash(Object value) {
        // 先规范化再计算UTF-8 JSON的SHA-256，避免Map字段顺序制造不同的input/prompt/spec hash。
        try {
            JsonNode node = normalize(mapper.valueToTree(value), null);
            byte[] bytes = mapper.writeValueAsBytes(node);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot canonicalize report input", ex);
        }
    }

    /** 将事实或其他输入序列化为顺序稳定的 JSON。 */
    public String canonical(Object value) {
        try {
            return mapper.writeValueAsString(normalize(mapper.valueToTree(value), null));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot canonicalize report input", ex);
        }
    }

    /** 递归排序对象字段，并按约定排序事件和规则代码数组。 */
    private JsonNode normalize(JsonNode node, String fieldName) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            java.util.List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names)
                sorted.set(name, normalize(node.get(name), name));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            java.util.List<JsonNode> values = new ArrayList<>();
            node.forEach(values::add);

            // 只规范化有明确排序规则的两个数组；其它数组（例如K线）保持原顺序。
            if ("events".equals(fieldName))
                values.sort(Comparator.comparing(value -> value.path("sequence").asLong(Long.MAX_VALUE)));
            if ("ruleCodes".equals(fieldName))
                values.sort(Comparator.comparing(JsonNode::asText));

            values.forEach(value -> array.add(normalize(value, null)));
            return array;
        }
        return node;
    }
}
