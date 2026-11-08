package com.quant.ai.report;

import com.quant.ai.core.BacktestSnapshot;
import com.quant.ai.core.dto.StrategyEventDto;
import com.quant.ai.error.AiErrorCode;
import com.quant.ai.error.AiModelException;
import com.quant.ai.facts.BacktestFacts;
import com.quant.ai.strategy.StrategySpecificationRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 模型只提出引用；事件/事实/规则的最终证据详情均从服务端可信输入解析。 */
@Component
public class EvidenceVerifier {
    private final StrategySpecificationRegistry specifications;

    public EvidenceVerifier(StrategySpecificationRegistry specifications) {
        this.specifications = specifications;
    }

    /** 先检查回放sequence可唯一定位，再逐条验证模型给出的候选证据。 */
    public List<VerifiedEvidence> verify(List<CandidateEvidence> candidates, BacktestSnapshot snapshot,
                                         BacktestFacts facts, String specificationVersion) {
        List<VerifiedEvidence> verified = new ArrayList<>();
        if (candidates == null)
            return verified;
        Set<String> ids = new HashSet<>();
        Set<Long> sequences = new HashSet<>();
        for (StrategyEventDto event : snapshot.replay().replay().events()) {
            if (event.sequence() == null || !sequences.add(event.sequence()))
                throw invalid("replay sequence is invalid");
        }
        for (CandidateEvidence candidate : candidates) {
            if (candidate.candidateId() == null || !candidate.candidateId().matches("E[1-9][0-9]*") || !ids.add(candidate.candidateId()))
                throw invalid("candidate id is invalid");

            // 不使用模型编写的事件详情或统计数值，只按引用类型在本次报告的输入里查找。
            Map<String, Object> detail = switch (candidate.type()) {
                case "EVENT" -> event(candidate, snapshot);
                case "FACT" -> fact(candidate, facts);
                case "SPEC" -> specification(candidate, snapshot.task().strategyId(), specificationVersion);
                default -> throw invalid("unsupported evidence type");
            };
            verified.add(new VerifiedEvidence(candidate.candidateId(), candidate.type(), detail));
        }
        return verified;
    }

    /** 按序号定位本次回放事件，用原始事件回填证据详情。 */
    private Map<String, Object> event(CandidateEvidence candidate, BacktestSnapshot snapshot) {
        if (candidate.sequence() == null || candidate.sequence() < 1)
            throw invalid("event sequence is required");
        StrategyEventDto event = snapshot.replay().replay().events().stream()
                .filter(item -> candidate.sequence().equals(item.sequence())).findFirst().orElseThrow(() -> invalid("event not found"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sequence", event.sequence());
        result.put("time", event.time());
        result.put("eventCode", event.code());
        result.put("sourceId", event.sourceId());
        return result;
    }

    /** 按允许的事实代码读取统计值或区域终态，拒绝缺失值。 */
    private Map<String, Object> fact(CandidateEvidence candidate, BacktestFacts facts) {
        // 只开放这些已定义的事实代码，避免模型借任意字段路径读取未授权信息。
        String code = candidate.factCode();
        Object value = switch (code == null ? "" : code) {
            case "FILL_COUNT" -> facts.overview().get("fillCount");
            case "COMPLETED_POSITION_COUNT" -> facts.overview().get("completedPositionCount");
            case "RETURN_RATE" -> facts.performance().get("returnRate");
            case "MAX_DRAWDOWN" -> facts.performance().get("maxDrawdown");
            case "DATA_LIMITATION" -> facts.dataLimitations();
            case "ZONE_FINAL_STATE" -> facts.zoneSummaries().stream().filter(zone -> candidate.sourceId() != null && candidate.sourceId().equals(zone.get("sourceId"))).findFirst().orElse(null);
            default -> throw invalid("unsupported fact code");
        };
        if (value == null)
            throw invalid("fact is unavailable");
        return Map.of("factCode", code, "value", value);
    }

    /** 核对候选规则是否属于本次报告的策略及版本。 */
    private Map<String, Object> specification(CandidateEvidence candidate, String strategyId, String version) {
        // 规则必须属于当前报告选定的策略、版本和受控ruleCode集合。
        if (!strategyId.equals(candidate.strategyId()) || !version.equals(candidate.version())
                || !specifications.containsRule(strategyId, version, candidate.ruleCode()))
            throw invalid("invalid specification evidence");
        return Map.of("strategyId", strategyId, "version", version, "ruleCode", candidate.ruleCode());
    }

    /** 构造证据校验错误，供生成流程与输出格式错误区分。 */
    private AiModelException invalid(String message) {
        // 与Schema失败区分：当前create只修复OUTPUT_INVALID，证据失败直接结束生成。
        return new AiModelException(AiErrorCode.AI_REPORT_EVIDENCE_INVALID, message);
    }
}
