package com.quant.ai.facts;

import com.quant.ai.core.dto.BacktestReplayDto;
import com.quant.ai.core.dto.BacktestResultDto;
import com.quant.ai.core.dto.BacktestTaskDto;
import com.quant.ai.core.dto.EquityPointDto;
import com.quant.ai.core.dto.ReplayResponseDto;
import com.quant.ai.core.dto.StrategyEventDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic facts only. It reads event code/details/sequence, never event
 * message text, and models the engine's one-way isolated account state.
 */
@Component
public class BacktestFactsExtractor {

    private static final Set<String> EXIT_CODES = Set.of("STOPPED", "TAKE_PROFIT", "CLOSED", "LIQUIDATED");

    /** Returns only documented strategy inputs; arbitrary Core fields never reach a tool response. */
    public Map<String, Object> safeParameterSnapshot(BacktestTaskDto task) {
        Map<String, Object> safe = new LinkedHashMap<>();
        Map<String, Object> params = task.params();
        if (params == null) {
            return safe;
        }
        for (String key : List.of("initialCapital", "leverage")) {
            Object value = params.get(key);
            if (value instanceof Number || value instanceof String || value instanceof Boolean) {
                safe.put(key, value);
            }
        }
        return safe;
    }

    /**
     * create在调用模型前先执行的确定性归约：读取已完成回测结果和结构化事件，
     * 计算表现、持仓配对与拒绝原因。缺少可靠依据时记录limitations，不补造事实。
     * 后续Prompt和EvidenceVerifier共用这份结果，模型不负责重算收益或解释自由文本事件。
     */
    public BacktestFacts extract(BacktestTaskDto task, ReplayResponseDto replayResponse) {
        List<String> limitations = new ArrayList<>();
        BacktestResultDto result = task.result();
        List<StrategyEventDto> events = validatedEvents(replayResponse.replay(), limitations);

        // 汇总指标来自结果字段；完成持仓依结构化入/出场事件配对，不能把成交笔数当持仓数。
        Map<String, Object> overview = overview(task, result, events, limitations);
        Map<String, Object> performance = performance(result, limitations);
        PositionResult positions = reducePositions(events, limitations);

        if (!positions.completedPositions().isEmpty()) {
            overview.put("completedPositionCount", positions.completedPositions().size());
        } else if (!events.isEmpty()) {
            limitations.add("completedPositionCount omitted because no deterministic entry/exit pairing exists");
        }

        // 拒绝原因同样依据事件code/details/sequence，不依据模型对message文案的解读。
        Map<String, Integer> rejections = rejectionCounts(events);
        List<Map<String, Object>> notable = notableSequences(events);
        if (isExplicitZeroFill(result) && !rejections.isEmpty()) {
            limitations.add("no fills; entry plans were rejected");
        }

        Map<String, Object> rejectionSummary = new LinkedHashMap<>();
        rejectionSummary.put("countsByReason", rejections);
        rejectionSummary.put("total", rejections.values().stream().mapToInt(Integer::intValue).sum());

        return new BacktestFacts(
                overview,
                performance,
                List.of(),
                positions.completedPositions(),
                rejectionSummary,
                notable,
                limitations
        );
    }

    /** 提取任务标识、市场、区间和事件数，并区分零成交与成交数据缺失。 */
    private Map<String, Object> overview(BacktestTaskDto task, BacktestResultDto result,
                                         List<StrategyEventDto> events, List<String> limitations) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("taskId", task.id());
        overview.put("strategyId", task.strategyId());
        overview.put("marketType", task.marketType().name());
        overview.put("symbol", task.symbol());
        overview.put("interval", task.interval());
        overview.put("status", task.status());
        overview.put("startTs", task.startTs());
        overview.put("endTs", task.endTs());
        overview.put("eventCount", events.size());
        if (result == null || result.trades() == null) {
            limitations.add("fillCount is unavailable because result.trades is missing");
        } else {
            overview.put("fillCount", result.trades().size());
        }
        return overview;
    }

    /** 读取已有绩效字段和最终权益，缺失项写入数据限制。 */
    private Map<String, Object> performance(BacktestResultDto result, List<String> limitations) {
        Map<String, Object> performance = new LinkedHashMap<>();
        if (result == null) {
            limitations.add("backtest result is unavailable");
            return performance;
        }
        addNumber(performance, "returnRate", result.returnRate(), limitations);
        addNumber(performance, "maxDrawdown", result.maxDrawdown(), limitations);
        addNumber(performance, "initialCapital", result.initialCapital(), limitations);
        if (result.equityCurve() == null || result.equityCurve().isEmpty()) {
            limitations.add("finalEquity is unavailable because equityCurve has no valid final point");
        } else {
            EquityPointDto last = result.equityCurve().get(result.equityCurve().size() - 1);
            if (last == null || last.equity() == null) {
                limitations.add("finalEquity is unavailable because equityCurve has no valid final point");
            } else {
                performance.put("finalEquity", last.equity());
            }
        }
        return performance;
    }

    /** 按事件顺序配对开仓、加仓和退出，记录无法可靠配对的情况。 */
    private PositionResult reducePositions(List<StrategyEventDto> events, List<String> limitations) {
        List<Map<String, Object>> completed = new ArrayList<>();
        PositionState position = null;

        for (StrategyEventDto event : events) {
            String sourceId = event.sourceId();
            if ("ENTRY_EXECUTED".equals(event.code())) {
                if (position == null) {
                    if (!validSide(event.positionSide())) {
                        limitations.add("entry event sequence " + event.sequence() + " has no valid position side");
                        continue;
                    }
                    position = PositionState.open(event);
                } else if (position.side.equals(event.positionSide())) {
                    position.recordSourceMismatch(sourceId, event.sequence(), "add", limitations);
                    position.addSequences.add(event.sequence());
                } else {
                    limitations.add("entry event sequence " + event.sequence()
                            + " contradicts the open " + position.side + " position");
                }
                continue;
            }
            if (EXIT_CODES.contains(event.code())) {
                if (position == null) {
                    limitations.add("exit event sequence " + event.sequence() + " has no deterministic matching entry");
                    continue;
                }
                position.recordSourceMismatch(sourceId, event.sequence(), "exit", limitations);
                completed.add(position.close(event));
                position = null;
            }
        }

        if (position != null) {
            limitations.add("one or more entries have no deterministic exit event");
        }
        return new PositionResult(completed);
    }

    /** 过滤无效或重复的事件序号，记录异常后按有效序号排序。 */
    private List<StrategyEventDto> validatedEvents(BacktestReplayDto replay, List<String> limitations) {
        if (replay == null || replay.events() == null) {
            limitations.add("replay events are unavailable");
            return List.of();
        }
        List<StrategyEventDto> valid = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (StrategyEventDto event : replay.events()) {
            if (event == null || event.sequence() == null || event.sequence() < 1) {
                limitations.add("replay contains an invalid event sequence");
            } else if (!seen.add(event.sequence())) {
                limitations.add("replay contains a duplicate event sequence " + event.sequence());
            } else {
                valid.add(event);
            }
        }
        valid.sort(Comparator.comparingLong(StrategyEventDto::sequence));
        return valid;
    }

    /** 按拒绝原因统计未获准入场的事件。 */
    private Map<String, Integer> rejectionCounts(List<StrategyEventDto> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (StrategyEventDto event : events) {
            if ("ENTRY_REJECTED".equals(event.code())) {
                String reason = event.details() == null ? null : string(event.details().get("reasonCode"));
                counts.merge(reason == null || reason.isBlank() ? "ENTRY_REJECTED" : reason, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 挑出入场拒绝等可供复盘引用的事件。 */
    private List<Map<String, Object>> notableSequences(List<StrategyEventDto> events) {
        List<Map<String, Object>> notable = new ArrayList<>();
        for (StrategyEventDto event : events) {
            if ("ENTRY_REJECTED".equals(event.code())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sequence", event.sequence());
                row.put("time", event.time());
                row.put("sourceId", event.sourceId());
                row.put("code", event.code());
                row.put("details", event.details() == null ? Map.of() : event.details());
                notable.add(row);
            }
        }
        return notable;
    }

    /** 仅在成交列表存在且为空时认定为零成交。 */
    private boolean isExplicitZeroFill(BacktestResultDto result) {
        return result != null && result.trades() != null && result.trades().isEmpty();
    }

    private boolean validSide(String side) {
        return "LONG".equals(side) || "SHORT".equals(side);
    }

    /** 写入可用的数值字段，缺失值写入数据限制。 */
    private void addNumber(Map<String, Object> target, String name, Double value, List<String> limitations) {
        if (value == null) {
            limitations.add(name + " is unavailable");
        } else {
            target.put(name, value);
        }
    }

    private String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private record PositionResult(List<Map<String, Object>> completedPositions) {
    }

    private static final class PositionState {
        private final String sourceId;
        private final String side;
        private final long entrySequence;
        private final List<Long> addSequences = new ArrayList<>();

        private PositionState(String sourceId, String side, long entrySequence) {
            this.sourceId = sourceId;
            this.side = side;
            this.entrySequence = entrySequence;
        }

        /** 从入场事件记录持仓方向、来源和首个事件序号。 */
        static PositionState open(StrategyEventDto entry) {
            return new PositionState(entry.sourceId(), entry.positionSide(), entry.sequence());
        }

        /** 从退出事件生成已完成持仓摘要，保留全部加仓序号。 */
        Map<String, Object> close(StrategyEventDto exit) {
            Map<String, Object> completed = new LinkedHashMap<>();
            completed.put("sourceId", sourceId);
            completed.put("entrySequence", entrySequence);
            completed.put("addSequences", List.copyOf(addSequences));
            completed.put("exitSequence", exit.sequence());
            completed.put("positionSide", side);
            completed.put("exitCode", exit.code());
            return completed;
        }

        /** 记录加仓或退出事件与开仓来源不一致的情况。 */
        void recordSourceMismatch(String eventSourceId, long sequence, String eventKind, List<String> limitations) {
            if (sourceId != null && !sourceId.isBlank() && eventSourceId != null && !eventSourceId.isBlank()
                    && !sourceId.equals(eventSourceId)) {
                limitations.add(eventKind + " event sequence " + sequence
                        + " has sourceId " + eventSourceId + " but the position was opened by " + sourceId);
            }
        }
    }
}
