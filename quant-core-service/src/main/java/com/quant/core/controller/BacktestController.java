package com.quant.core.controller;

import com.quant.core.config.RocketMQHealthIndicator;
import com.quant.core.dto.BacktestReplayResponse;
import com.quant.core.dto.BacktestRequest;
import com.quant.core.dto.BacktestTaskVO;
import com.quant.core.service.BacktestService;
import com.quant.core.strategy.Strategy;
import com.quant.core.strategy.StrategyRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.web.bind.annotation.*;
import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.core.security.CoreRequestAuthFilter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测服务 REST 接口
 *
 * 5 个接口：
 * - GET  /api/core/health                健康检查
 * - GET  /api/core/strategies            策略列表
 * - POST /api/core/backtest              提交回测（异步）
 * - GET  /api/core/backtest/{id}         查询回测结果
 * - GET  /api/core/backtest/{id}/replay  查询回放快照（Week 7）
 * - GET  /api/core/backtest              回测任务列表
 *
 * 经 gateway 访问：http://localhost:8080/api/core/...
 */
@Slf4j
@RestController
@RequestMapping("/api/core")
public class BacktestController {

    @Autowired
    private BacktestService backtestService;

    @Autowired
    private StrategyRegistry strategyRegistry;

    @Autowired
    private RocketMQHealthIndicator mqHealthIndicator;

    /**
     * 健康检查（含 MQ 状态聚合）
     *
     * 返回 core-service 自身状态 + RocketMQ 真实探测状态。
     * MQ 状态来自 RocketMQHealthIndicator（TCP 探测 NameServer），
     * 非无条件 UP，NameServer 不可达时返回 DOWN。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "quant-core-service");

        // MQ 状态：复用 Actuator HealthIndicator 的真实探测结果
        Health mqHealth = mqHealthIndicator.health();
        Map<String, Object> mq = new HashMap<>();
        mq.put("status", mqHealth.getStatus().getCode().toUpperCase());
        mq.put("detail", mqHealth.getDetails().getOrDefault("detail", mqHealth.getStatus().getDescription()));
        result.put("mq", mq);
        return result;
    }

    /**
     * 策略列表（前端用于展示可选策略）
     * 返回示例：[{"id":"turtle","minPeriod":21,"className":"TurtleStrategy"}]
     */
    @GetMapping("/strategies")
    public List<Map<String, Object>> strategies() {
        return strategyRegistry.all().stream()
                .map(s -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("id", s.getId());
                    info.put("minPeriod", s.getMinPeriod());
                    info.put("className", s.getClass().getSimpleName());
                    return info;
                })
                .toList();
    }

    /**
     * 提交回测任务（异步执行）
     * 返回示例：{"id":1,"status":"WAITING","message":"回测任务已提交"}
     */
    @PostMapping("/backtest")
    public Map<String, Object> submit(@RequestBody BacktestRequest request, @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        log.info("提交回测: strategyId={}, symbol={}, interval={}, k线数={}",
                request.getStrategyId(), request.getSymbol(), request.getInterval(), request.getLimit());
        Long id = backtestService.submitBacktest(request, principal.userId());

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("status", "WAITING");
        result.put("message", "回测任务已提交");
        return result;
    }

    /**
     * 查询回测结果
     * status: WAITING/RUNNING/SUCCESS/FAILED
     * SUCCESS 时 result 含 returnRate/maxDrawdown/trades/equityCurve
     */
    @GetMapping("/backtest/{id}")
    public BacktestTaskVO get(@PathVariable Long id, @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        return backtestService.getTask(id, principal.userId());
    }

    /**
     * 查询回放快照（Week 7：策略决策回放）
     *
     * <p>只返回该任务回测时持久化的 K 线快照与结构化事件（replay_data），
     * 禁止前端重新拉取实时 K 线。任务不存在/不属于当前用户 → 404；
     * 未完成 → 409 REPLAY_NOT_READY；失败或无回放数据（旧任务）→ 409 REPLAY_NOT_AVAILABLE。
     */
    @GetMapping("/backtest/{id}/replay")
    public BacktestReplayResponse replay(@PathVariable Long id, @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        return backtestService.getReplay(id, principal.userId());
    }

    /**
     * 回测任务列表（按创建时间倒序，支持状态/策略筛选 + 分页）
     *
     * @param status     状态筛选（WAITING/RUNNING/SUCCESS/FAILED），不传=全部
     * @param strategyId 策略筛选（如 ema20），不传=全部
     * @param page       页码，默认 1
     * @param size       每页条数，默认 20
     * @return {total, page, size, list}
     */
    @GetMapping("/backtest")
    public Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String strategyId,
            @RequestParam(required = false) String marketType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        return backtestService.listTasks(status, strategyId, marketType, page, size, principal.userId());
    }
}
