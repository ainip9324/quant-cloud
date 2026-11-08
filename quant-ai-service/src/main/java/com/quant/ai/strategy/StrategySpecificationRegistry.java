package com.quant.ai.strategy;

import com.quant.ai.core.CoreGatewayException;
import com.quant.ai.error.AiErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Controlled classpath mapping, not RAG and not arbitrary filesystem access. */
@Component
public class StrategySpecificationRegistry {
    private static final Map<String, String> CURRENT = Map.of("ema20", "v1", "turtle", "v1");
    private static final Map<String, Set<String>> RULES = Map.of(
            "ema20", Set.of("EMA_CROSS_ENTRY_EXIT", "EMA_STOP_LOSS", "EMA_LONG_ONLY"),
            "turtle", Set.of("CHANNEL_ENTRY", "ATR_STOP", "PYRAMID_ADD", "EXIT_PRIORITY"));

    /** 由服务端为已知策略选择版本，create会把版本和规范内容hash一起纳入报告指纹。 */
    public String currentVersion(String strategyId) {
        String version = CURRENT.get(strategyId);
        if (version == null) throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "unknown strategy specification");
        return version;
    }

    /** 判断规则代码是否登记在指定策略的当前版本下。 */
    public boolean containsRule(String strategyId, String version, String ruleCode) {
        return currentVersion(strategyId).equals(version) && RULES.getOrDefault(strategyId, Set.of()).contains(ruleCode);
    }

    /** 只读取classpath中登记的策略规范及规则代码；不是模型指定路径的文件读取。 */
    public Map<String, Object> get(String strategyId, String requestedVersion) {
        String version = requestedVersion == null || requestedVersion.isBlank() ? currentVersion(strategyId) : requestedVersion;
        if (!currentVersion(strategyId).equals(version)) {
            throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "unknown strategy specification");
        }
        String path = "strategy-specifications/" + strategyId + "/" + version + ".md";
        try (var in = new ClassPathResource(path).getInputStream()) {
            return Map.of("strategyId", strategyId, "version", version,
                    "specification", new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "ruleCodes", List.copyOf(RULES.get(strategyId)));
        } catch (Exception ex) {
            throw new CoreGatewayException(AiErrorCode.AI_CORE_INVALID_RESPONSE, "strategy specification is unavailable");
        }
    }
}
