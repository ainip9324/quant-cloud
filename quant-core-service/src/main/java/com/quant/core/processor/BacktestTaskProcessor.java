package com.quant.core.processor;

import com.alibaba.fastjson2.JSON;
import com.quant.common.market.MarketQueryLimits;
import com.quant.common.model.BacktestResult;
import com.quant.common.model.BacktestRunOutput;
import com.quant.common.model.Kline;
import com.quant.core.engine.BacktestEngine;
import com.quant.core.entity.BacktestTaskDO;
import com.quant.core.feign.DataFeignClient;
import com.quant.core.mapper.BacktestTaskMapper;
import com.quant.core.strategy.Strategy;
import com.quant.core.strategy.StrategyRegistry;
import com.quant.core.util.IntervalUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 回测任务处理器（Week 3 事件驱动核心）
 *
 * <p>由 {@link com.quant.core.mq.BacktestConsumer} 调用，负责：
 * <ol>
 *   <li>原子领取：UPDATE SET RUNNING WHERE id=? AND WAITING（附 executionToken）</li>
 *   <li>执行回测：策略路由 → Feign拉K线 → engine.run</li>
 *   <li>状态更新：SUCCESS / FAILED（均带 token 条件更新，防旧执行者覆盖）</li>
 *   <li>异常分流：
 *     <ul>
 *       <li>业务异常（策略不存在、K线为空、策略NPE等）→ FAILED + ack</li>
 *       <li>基础设施异常（DB连接、Feign超时等）→ revert WAITING + throw → MQ重试</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>executionToken 机制：每次 claimTask 生成 UUID，所有后续更新（SUCCESS/FAILED/revert）
 * 都校验 token。Watchdog 超时恢复后旧线程的 token 不匹配，无法覆盖新执行者结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestTaskProcessor {

    private final BacktestTaskMapper taskMapper;
    private final StrategyRegistry strategyRegistry;
    private final BacktestEngine backtestEngine;
    private final DataFeignClient dataFeignClient;

    /**
     * 处理回测任务（由 Consumer 调用）
     *
     * @param taskId 任务 ID
     */
    public void process(Long taskId) {
        String token = UUID.randomUUID().toString();

        // ① 原子领取（防重复消费）
        int claimed = taskMapper.claimTask(taskId, token);
        if (claimed == 0) {
            log.debug("任务已被领取或已完成, 跳过: taskId={}", taskId);
            return; // ack
        }

        BacktestTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return; // ack
        }

        log.info("回测任务开始: id={}, strategy={}, symbol={}, interval={}, token={}",
                task.getId(), task.getStrategyId(), task.getSymbol(), task.getInterval(), token);

        try {
            BacktestRunOutput output = executeBacktest(task);

            // ③ 成功：result_summary 与 replay_data 在<b>同一条带 token 条件</b>的更新中原子落库
            // （JSON 序列化失败会抛异常 → FAILED，绝不写出只有 summary 或只有 replay 的半成品）
            String resultJson = JSON.toJSONString(output.getSummary());
            String replayJson = JSON.toJSONString(output.getReplay());
            int updated = taskMapper.updateSuccessWithReplay(taskId, token, resultJson, replayJson);
            if (updated == 0) {
                log.warn("任务已被Watchdog恢复并被新执行者接管(token不匹配), 放弃写入SUCCESS: taskId={}", taskId);
            } else {
                BacktestResult result = output.getSummary();
                log.info("回测任务成功: id={}, returnRate={}%, maxDrawdown={}%, trades={}, replayBars={}, replayEvents={}",
                        taskId, String.format("%.2f", result.getReturnRate()),
                        String.format("%.2f", result.getMaxDrawdown()),
                        result.getTrades().size(),
                        output.getReplay().getBars() != null ? output.getReplay().getBars().size() : 0,
                        output.getReplay().getEvents() != null ? output.getReplay().getEvents().size() : 0);
            }
            // ack

        } catch (BacktestBusinessException e) {
            // ④ 确定性业务异常 → FAILED + ack
            int updated = taskMapper.updateFailed(taskId, token, e.getMessage());
            if (updated == 0) {
                log.warn("任务已被接管, 无法写FAILED: taskId={}", taskId);
            } else {
                log.error("回测任务业务失败: id={}, error={}", taskId, e.getMessage());
            }
            // ack（不重试）

        } catch (Exception e) {
            if (isInfrastructureException(e)) {
                // ⑤ 基础设施异常 → revert WAITING + throw → MQ重试
                int reverted = taskMapper.revertToWaiting(taskId, token);
                if (reverted == 0) {
                    log.warn("任务已被接管, 无法revert: taskId={}", taskId);
                    return; // ack（已被接管，无需重试）
                }
                log.warn("基础设施异常, 恢复WAITING等待MQ重试: taskId={}, error={}",
                        taskId, e.getMessage());
                throw new RuntimeException("基础设施异常, 等待MQ重试: " + e.getMessage(), e);
                // throw → MQ 自动重试
            } else {
                // ⑥ 非确定性异常（策略NPE等代码错误）→ FAILED + ack
                String summary = e.getClass().getSimpleName() + ": " +
                        (e.getMessage() != null ? e.getMessage() : "无详细信息");
                int updated = taskMapper.updateFailed(taskId, token, summary);
                if (updated == 0) {
                    log.warn("任务已被接管, 无法写FAILED: taskId={}", taskId);
                } else {
                    log.error("回测任务异常失败: id={}, error={}", taskId, summary, e);
                }
                // ack（策略代码错误不重试）
            }
        }
    }

    /**
     * 执行回测核心逻辑（从 BacktestExecutor 迁移）
     *
     * @return 组合结果（summary + replay 快照），由调用方原子落库
     * @throws BacktestBusinessException 确定性业务异常（策略不存在、K线为空等）
     * @throws RuntimeException          基础设施异常或代码错误（由调用方分类处理）
     */
    private BacktestRunOutput executeBacktest(BacktestTaskDO task) {
        // 1. 路由策略
        Strategy strategy = strategyRegistry.require(task.getStrategyId());

        // 2. 估算拉取条数
        int limit = IntervalUtils.estimateLimit(task.getStartTs(), task.getEndTs(),
                task.getInterval(), task.getBarLimit() != null ? task.getBarLimit() : MarketQueryLimits.DEFAULT_BACKTEST_LIMIT);

        // 3. Feign 拉取 K线（可能抛 FeignException → 基础设施异常）
        List<Kline> klines = dataFeignClient.getKlines(task.getMarketType(), task.getSymbol(), task.getInterval(), limit);
        if (klines == null || klines.isEmpty()) {
            throw new BacktestBusinessException(
                    "K线数据为空: symbol=" + task.getSymbol() + ", interval=" + task.getInterval());
        }

        // 4. 按时间范围过滤
        klines = filterByTimeRange(klines, task.getStartTs(), task.getEndTs());
        if (klines.isEmpty()) {
            throw new BacktestBusinessException("时间范围内无K线数据");
        }

        // 5. 解析策略参数
        Map<String, Object> params = parseParams(task.getParams());

        // 6. 执行回测（策略内部异常如 NPE → 非基础设施 → FAILED）
        //    runWithReplay 返回结果摘要 + 回放快照（本次实际使用的过滤后 K线 + 结构化事件）
        return backtestEngine.runWithReplay(klines, strategy, params);
    }

    /**
     * 判断是否为基础设施异常（可重试）
     *
     * <p>只将明确的短暂性异常归为基础设施：
     * <ul>
     *   <li>Spring {@link DataAccessException}（DB 连接、超时、死锁等）</li>
     *   <li>Feign {@link feign.RetryableException}（连接拒绝、读写超时）</li>
     *   <li>Feign 5xx 服务端错误（data-service 内部异常）</li>
     * </ul>
     * 其余（IllegalArgumentException、NPE、ArithmeticException 等）均视为业务/代码异常 → FAILED。
     */
    private boolean isInfrastructureException(Exception e) {
        // DB 异常
        if (e instanceof DataAccessException) {
            return true;
        }
        // Feign 连接/超时异常
        if (e instanceof feign.RetryableException) {
            return true;
        }
        // Feign HTTP 5xx 服务端错误
        if (e instanceof feign.FeignException) {
            int status = ((feign.FeignException) e).status();
            return status >= 500;
        }
        return false;
    }

    /**
     * 按时间范围过滤 K线（闭区间 [startTs, endTs]）
     */
    private List<Kline> filterByTimeRange(List<Kline> klines, Long startTs, Long endTs) {
        if (startTs == null && endTs == null) {
            return klines;
        }
        return klines.stream()
                .filter(k -> (startTs == null || k.getTime() >= startTs)
                        && (endTs == null || k.getTime() <= endTs))
                .toList();
    }

    /**
     * 解析策略参数
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(paramsJson, Map.class);
        } catch (Exception e) {
            log.warn("策略参数解析失败，忽略参数: {}", paramsJson, e);
            return null;
        }
    }

    /**
     * 回测业务异常（确定性失败，不重试）
     *
     * <p>用于策略不存在、K线为空、时间范围无数据等明确的业务错误。
     * 与基础设施异常区分：业务异常写 FAILED + ack，不触发 MQ 重试。
     */
    public static class BacktestBusinessException extends RuntimeException {
        public BacktestBusinessException(String message) {
            super(message);
        }
    }
}
