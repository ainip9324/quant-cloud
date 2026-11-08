package com.quant.ai.tools;

import com.quant.ai.context.AiRequestContext;
import com.quant.ai.core.BacktestSnapshot;
import com.quant.ai.core.CoreGatewayClient;
import com.quant.ai.core.CoreGatewayException;
import com.quant.ai.core.dto.KlineDto;
import com.quant.ai.core.dto.StrategyEventDto;
import com.quant.ai.error.AiErrorCode;
import com.quant.ai.facts.BacktestFacts;
import com.quant.ai.facts.BacktestFactsExtractor;
import com.quant.ai.strategy.StrategySpecificationRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal read-only tool service. Identity is supplied by ToolContext, never tool arguments. */
@Component
public class BacktestReadTools {

    private final CoreGatewayClient core;
    private final BacktestFactsExtractor factsExtractor;
    private final ToolResponseLimiter limiter;
    private final StrategySpecificationRegistry specifications;

    public BacktestReadTools(CoreGatewayClient core, BacktestFactsExtractor factsExtractor,
                             ToolResponseLimiter limiter, StrategySpecificationRegistry specifications) {
        this.core = core;
        this.factsExtractor = factsExtractor;
        this.limiter = limiter;
        this.specifications = specifications;
    }

    /** 提取回测任务、允许公开的参数和确定性绩效摘要。 */
    public Map<String, Object> getBacktestOverview(AiRequestContext context, long taskId) {
        BacktestSnapshot snapshot = core.getSnapshot(context, taskId);
        BacktestFacts facts = factsExtractor.extract(snapshot.task(), snapshot.replay());
        Map<String, Object> output = new LinkedHashMap<>();
        Map<String, Object> overview = facts.overview();
        output.put("taskId", overview.get("taskId"));
        output.put("strategyId", overview.get("strategyId"));
        output.put("marketType", overview.get("marketType"));
        output.put("symbol", overview.get("symbol"));
        output.put("interval", overview.get("interval"));
        output.put("startTs", overview.get("startTs"));
        output.put("endTs", overview.get("endTs"));
        output.put("status", overview.get("status"));
        output.put("parameters", factsExtractor.safeParameterSnapshot(snapshot.task()));
        output.putAll(facts.performance());
        copyIfPresent(overview, output, "fillCount");
        copyIfPresent(overview, output, "completedPositionCount");
        output.put("dataLimitations", facts.dataLimitations());
        return limiter.limitResponse(output);
    }

    /** 提取回放中的区域、持仓和拒绝事件摘要。 */
    public Map<String, Object> getReplayEventSummary(AiRequestContext context, long taskId) {
        BacktestSnapshot snapshot = core.getSnapshot(context, taskId);
        BacktestFacts facts = factsExtractor.extract(snapshot.task(), snapshot.replay());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("taskId", taskId);
        output.put("strategyId", snapshot.task().strategyId());
        output.put("marketType", snapshot.task().marketType().name());
        output.put("symbol", snapshot.task().symbol());
        output.put("interval", snapshot.task().interval());
        output.put("zoneSummaries", facts.zoneSummaries());
        output.put("tradeSummaries", facts.tradeSummaries());
        if (facts.overview().containsKey("completedPositionCount")) {
            output.put("completedPositionCount", facts.overview().get("completedPositionCount"));
        }
        output.put("rejectionSummary", facts.rejectionSummary());
        output.put("notableSequences", facts.notableSequences());
        output.put("dataLimitations", facts.dataLimitations());
        return limiter.limitResponse(output);
    }

    /** 按闭区间读取事件并补充附近 K 线，再按输出预算裁剪。 */
    public Map<String, Object> getReplayEventWindow(AiRequestContext context, long taskId,
                                                     long fromSequence, long toSequence) {
        if (fromSequence < 1 || toSequence < fromSequence || toSequence - fromSequence > 200) {
            throw invalid("sequence window must be 1..201 events");
        }
        BacktestSnapshot snapshot = core.getSnapshot(context, taskId);
        List<StrategyEventDto> selectedEvents = new ArrayList<>();
        long firstTime = Long.MAX_VALUE;
        long lastTime = Long.MIN_VALUE;
        for (StrategyEventDto event : snapshot.replay().replay().events()) {
            if (event.sequence() != null && event.sequence() >= fromSequence && event.sequence() <= toSequence) {
                selectedEvents.add(event);
                if (event.time() != null) {
                    firstTime = Math.min(firstTime, event.time());
                    lastTime = Math.max(lastTime, event.time());
                }
            }
        }
        List<KlineDto> nearbyBars = nearbyBars(snapshot.replay().replay().bars(), firstTime, lastTime);
        return limiter.limitWindow(selectedEvents, nearbyBars, Map.of("taskId", taskId, "marketType", snapshot.task().marketType().name(), "symbol", snapshot.task().symbol(), "interval", snapshot.task().interval()));
    }

    /** 读取登记的策略版本及规则代码，并限制输出大小。 */
    public Map<String, Object> getStrategySpecification(AiRequestContext context, String strategyId, String version) {
        return limiter.limitResponse(specifications.get(strategyId, version));
    }

    /** 从事件时间段附近选取前后 K 线，最多返回 40 根。 */
    private List<KlineDto> nearbyBars(List<KlineDto> bars, long firstTime, long lastTime) {
        if (bars.isEmpty() || firstTime == Long.MAX_VALUE) {
            return List.of();
        }
        int first = 0;
        int last = bars.size() - 1;
        for (int index = 0; index < bars.size(); index++) {
            if (bars.get(index).time() != null && bars.get(index).time() >= firstTime) {
                first = index;
                break;
            }
        }
        for (int index = bars.size() - 1; index >= 0; index--) {
            if (bars.get(index).time() != null && bars.get(index).time() <= lastTime) {
                last = index;
                break;
            }
        }
        int from = Math.max(0, first - 5);
        int to = Math.min(bars.size() - 1, last + 5);
        if (to - from + 1 > 40) {
            to = from + 39;
        }
        return new ArrayList<>(bars.subList(from, to + 1));
    }

    /** 仅复制源中存在的字段，保留缺失与空值的区别。 */
    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private CoreGatewayException invalid(String message) {
        return new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, message);
    }
}
