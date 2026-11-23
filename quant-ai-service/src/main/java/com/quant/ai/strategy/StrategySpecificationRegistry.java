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
    private static final Map<String, String> CURRENT = Map.of("zone-reversal", "v1", "ema20", "v1", "turtle", "v1");
    private static final Map<String, Set<String>> RULES = Map.of(
            "zone-reversal", Set.of("MIDPOINT_ENTRY", "ARMING", "TAKE_PROFIT_TRUNCATION", "MAX_CONSECUTIVE_STOPS"),
            "ema20", Set.of("EMA_CROSS_ENTRY_EXIT", "EMA_STOP_LOSS", "EMA_LONG_ONLY"),
            "turtle", Set.of("CHANNEL_ENTRY", "ATR_STOP", "PYRAMID_ADD", "EXIT_PRIORITY"));
    private static final Set<String> EMA_SIGNAL_RULES = Set.of("EMA_CROSS_ENTRY_EXIT", "NEXT_BAR_EXECUTION",
            "CONFIGURED_RISK_SIZING", "CONFIGURED_PROTECTION", "MAX_NOTIONAL_EXPOSURE");
    private static final Set<String> EMA_VOLATILITY_RULES = Set.of("EMA_CROSS_ENTRY_EXIT", "NEXT_BAR_EXECUTION",
            "CONFIGURED_RISK_SIZING", "CONFIGURED_PROTECTION", "LOW_VOLATILITY_FILTER", "MARGIN_CHECK");
    private static final Set<String> EMA_RETEST_RULES = Set.of("EMA_CROSS_ENTRY_EXIT", "NEXT_BAR_EXECUTION",
            "CONFIGURED_RISK_SIZING", "CONFIGURED_PROTECTION", "LOW_VOLATILITY_FILTER", "MARGIN_CHECK",
            "EMA_RETEST_ENTRY", "EMA_EXIT_AFTER_ONE_R");
    private static final Map<String, String> SIGNAL_VERSIONS = Map.of(
            "ema20", "ema20-signal-v1", "zone-reversal", "zone-reversal-signal-v1", "turtle", "turtle-signal-v1");
    private static final Map<String, Set<String>> SIGNAL_RULES = Map.of("ema20", EMA_SIGNAL_RULES,
            "turtle", Set.of("CHANNEL_ENTRY", "CHANNEL_EXIT", "PYRAMID_ADD", "NEXT_BAR_EXECUTION",
                    "CONFIGURED_PROTECTION", "MAX_POSITION_RISK"),
            "zone-reversal", Set.of("HISTORICAL_CHART_OBJECTS", "CLOSE_CONFIRMED_REVERSAL", "NEXT_BAR_EXECUTION",
                    "CONFIGURED_RISK_SIZING", "CONFIGURED_PROTECTION", "MAX_NOTIONAL_EXPOSURE"));
    private static final Set<String> CHANNEL_RULES = java.util.stream.Stream.concat(
            SIGNAL_RULES.get("zone-reversal").stream(), java.util.stream.Stream.of("CHANNEL_BOUNDARY_APPROXIMATION"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> EFFECTIVE_RULES = java.util.stream.Stream.concat(
            CHANNEL_RULES.stream().filter(code -> !"HISTORICAL_CHART_OBJECTS".equals(code)),
            java.util.stream.Stream.of("CONFIGURED_EFFECTIVE_TIME"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> SINGLE_BAR_CHANNEL_RULES = java.util.stream.Stream.concat(
            EFFECTIVE_RULES.stream(), java.util.stream.Stream.of("CHANNEL_SINGLE_BAR_RETEST"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** 依据任务自己的执行快照选择规范，已有任务继续使用 v1。 */
    public String versionFor(String strategyId, Map<String, Object> params) {
        if (params == null || !params.containsKey("execution")) return currentVersion(strategyId);
        if ("ema20".equals(strategyId) && params.get("execution") instanceof Map<?, ?> execution
                && execution.get("version") instanceof Number version && version.doubleValue() == 1
                && "ema20-signal-v2".equals(execution.get("strategyVersion"))
                && execution.get("risk") instanceof Map<?, ?> risk
                && risk.get("version") instanceof Number riskVersion && riskVersion.doubleValue() == 3) return "v5";
        if ("ema20".equals(strategyId) && params.get("execution") instanceof Map<?, ?> execution
                && execution.get("version") instanceof Number version && version.doubleValue() == 1
                && "ema20-signal-v1".equals(execution.get("strategyVersion"))
                && execution.get("risk") instanceof Map<?, ?> risk
                && risk.get("version") instanceof Number riskVersion) {
            if (riskVersion.doubleValue() == 3) return "v4";
            if (riskVersion.doubleValue() == 2) return "v3";
            if (riskVersion.doubleValue() != 1) {
                throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "unknown EMA risk version");
            }
        }
        if ("zone-reversal".equals(strategyId) && params.get("execution") instanceof Map<?, ?> execution
                && execution.get("version") instanceof Number version && version.doubleValue() == 1
                && "zone-reversal-signal-v5".equals(execution.get("strategyVersion"))) return "v6";
        if ("zone-reversal".equals(strategyId) && params.get("execution") instanceof Map<?, ?> execution
                && execution.get("version") instanceof Number version && version.doubleValue() == 1
                && "zone-reversal-signal-v4".equals(execution.get("strategyVersion"))) return "v5";
        if ("zone-reversal".equals(strategyId) && params.get("execution") instanceof Map<?, ?> execution
                && execution.get("version") instanceof Number version && version.doubleValue() == 1
                && "zone-reversal-signal-v3".equals(execution.get("strategyVersion"))) return "v4";
        if ("zone-reversal".equals(strategyId) && params.get("execution") instanceof Map<?, ?> execution
                && execution.get("version") instanceof Number version && version.doubleValue() == 1
                && "zone-reversal-signal-v2".equals(execution.get("strategyVersion"))) return "v3";
        if (SIGNAL_VERSIONS.containsKey(strategyId) && params.get("execution") instanceof Map<?, ?> execution
                && execution.get("version") instanceof Number version && version.doubleValue() == 1
                && SIGNAL_VERSIONS.get(strategyId).equals(execution.get("strategyVersion"))) return "v2";
        throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "unknown strategy execution version");
    }

    /** 由服务端为已知策略选择版本，create会把版本和规范内容hash一起纳入报告指纹。 */
    public String currentVersion(String strategyId) {
        String version = CURRENT.get(strategyId);
        if (version == null) throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "unknown strategy specification");
        return version;
    }

    /** 判断规则代码是否登记在指定策略的当前版本下。 */
    public boolean containsRule(String strategyId, String version, String ruleCode) {
        if ("zone-reversal".equals(strategyId) && "v6".equals(version)) return SINGLE_BAR_CHANNEL_RULES.contains(ruleCode);
        if ("zone-reversal".equals(strategyId) && "v5".equals(version)) return SINGLE_BAR_CHANNEL_RULES.contains(ruleCode);
        if ("ema20".equals(strategyId) && "v5".equals(version)) return EMA_RETEST_RULES.contains(ruleCode);
        if ("ema20".equals(strategyId) && "v4".equals(version)) return EMA_VOLATILITY_RULES.contains(ruleCode);
        if ("ema20".equals(strategyId) && "v3".equals(version)) return EMA_SIGNAL_RULES.contains(ruleCode);
        if ("zone-reversal".equals(strategyId) && "v4".equals(version)) return EFFECTIVE_RULES.contains(ruleCode);
        if ("zone-reversal".equals(strategyId) && "v3".equals(version)) return CHANNEL_RULES.contains(ruleCode);
        if (SIGNAL_RULES.containsKey(strategyId) && "v2".equals(version)) return SIGNAL_RULES.get(strategyId).contains(ruleCode);
        return currentVersion(strategyId).equals(version) && RULES.getOrDefault(strategyId, Set.of()).contains(ruleCode);
    }

    private Set<String> rules(String strategyId, String version) {
        if ("zone-reversal".equals(strategyId) && "v6".equals(version)) return SINGLE_BAR_CHANNEL_RULES;
        if ("zone-reversal".equals(strategyId) && "v5".equals(version)) return SINGLE_BAR_CHANNEL_RULES;
        if ("ema20".equals(strategyId) && "v5".equals(version)) return EMA_RETEST_RULES;
        if ("ema20".equals(strategyId) && "v4".equals(version)) return EMA_VOLATILITY_RULES;
        if ("ema20".equals(strategyId) && "v3".equals(version)) return EMA_SIGNAL_RULES;
        if ("zone-reversal".equals(strategyId) && "v4".equals(version)) return EFFECTIVE_RULES;
        if ("zone-reversal".equals(strategyId) && "v3".equals(version)) return CHANNEL_RULES;
        if (SIGNAL_RULES.containsKey(strategyId) && "v2".equals(version)) return SIGNAL_RULES.get(strategyId);
        if (currentVersion(strategyId).equals(version)) return RULES.get(strategyId);
        throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "unknown strategy specification");
    }

    /** 只读取classpath中登记的策略规范及规则代码；不是模型指定路径的文件读取。 */
    public Map<String, Object> get(String strategyId, String requestedVersion) {
        String version = requestedVersion == null || requestedVersion.isBlank() ? currentVersion(strategyId) : requestedVersion;
        Set<String> ruleCodes = rules(strategyId, version);
        String path = "strategy-specifications/" + strategyId + "/" + version + ".md";
        try (var in = new ClassPathResource(path).getInputStream()) {
            return Map.of("strategyId", strategyId, "version", version,
                    "specification", new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "ruleCodes", List.copyOf(ruleCodes));
        } catch (Exception ex) {
            throw new CoreGatewayException(AiErrorCode.AI_CORE_INVALID_RESPONSE, "strategy specification is unavailable");
        }
    }
}
