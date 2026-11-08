package com.quant.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.quant.common.enums.TaskStatus;
import com.quant.common.model.BacktestReplay;
import com.quant.common.model.BacktestResult;
import com.quant.common.model.EquityPoint;
import com.quant.core.dto.BacktestReplayResponse;
import com.quant.core.dto.BacktestRequest;
import com.quant.core.dto.BacktestTaskVO;
import com.quant.core.entity.BacktestTaskDO;
import com.quant.core.exception.BacktestServiceException;
import com.quant.core.mapper.BacktestTaskMapper;
import com.quant.core.mq.BacktestMessageProducer;
import com.quant.core.strategy.Strategy;
import com.quant.core.strategy.StrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketQueryLimits;
import com.quant.common.market.MarketSelection;
import com.quant.common.market.MarketType;

/**
 * 回测任务服务（编排：提交 → MQ投递 → 查询）
 *
 * <p>Week 3 改造：@Async 线程池 → RocketMQ 事件驱动。
 *
 * 职责：
 * - submitBacktest：参数校验 + 存 WAITING + 同步发送 MQ 消息
 * - getTask/listTasks：查询任务，反序列化 resultSummary 为 BacktestResult
 *
 * <p>MQ 发送失败不报错：任务已持久化（WAITING），由 BacktestWatchdog 补偿投递。
 * 接口返回的 WAITING 语义就是"已持久化待调度"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final BacktestTaskMapper taskMapper;
    private final BacktestMessageProducer messageProducer;
    private final StrategyRegistry strategyRegistry;

    /**
     * 提交回测任务
     *
     * @param request 回测请求
     * @return 任务 ID
     */
    public Long submitBacktest(BacktestRequest request, Long userId) {
        MarketSelection selection = MarketCatalog.requireSelection(request.getMarketType(), request.getSymbol(), request.getInterval());
        // 基本参数校验
        if (request.getStrategyId() == null || request.getStrategyId().isBlank()) {
            throw new IllegalArgumentException("strategyId 不能为空");
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new IllegalArgumentException("symbol 不能为空");
        }
        if (request.getInterval() == null || request.getInterval().isBlank()) {
            throw new IllegalArgumentException("interval 不能为空");
        }

        int barLimit = MarketQueryLimits.normalizeBacktestLimit(request.getLimit());

        // 策略自行负责轻量提交参数校验，未知策略在写入任务前直接失败。
        Strategy strategy = strategyRegistry.require(request.getStrategyId());
        strategy.validateParameters(request.getParams());

        // 构造任务实体
        BacktestTaskDO task = new BacktestTaskDO();
        task.setUserId(userId);
        task.setStrategyId(request.getStrategyId());
        task.setMarketType(selection.marketType()); task.setSymbol(selection.symbol()); task.setInterval(selection.interval());
        task.setStartTs(request.getStartTs());
        task.setEndTs(request.getEndTs());
        task.setBarLimit(barLimit);
        // params 序列化为 JSON 字符串存储
        task.setParams(request.getParams() != null ? JSON.toJSONString(request.getParams()) : null);
        task.setStatus(TaskStatus.WAITING.name());
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());

        // 入库（id 自动回填）
        taskMapper.insert(task);
        log.info("回测任务已提交: id={}, strategy={}, symbol={}",
                task.getId(), task.getStrategyId(), task.getSymbol());

        // 同步发送 MQ 消息（失败不报错，由 Watchdog 补偿投递）
        boolean sent = messageProducer.syncSend(task.getId());
        if (!sent) {
            log.warn("MQ发送失败, 任务保持WAITING等待Watchdog补偿: taskId={}", task.getId());
        }

        return task.getId();
    }

    /**
     * 查询单个任务
     */
    public BacktestTaskVO getTask(Long id, Long userId) {
        BacktestTaskDO task = taskMapper.selectTaskByIdAndUserId(id, userId);
        if (task == null || !strategyRegistry.ids().contains(task.getStrategyId())) {
            return null;
        }
        return toVO(task);
    }

    /**
     * 查询回放快照（Week 7：GET /backtest/{id}/replay）
     *
     * <p>用户隔离落实在 SQL 条件（id + user_id）：任务不存在或不属于当前用户都返回 404，
     * 不泄漏任务存在性。只允许 SUCCESS 且 replay_data 非空的任务回放；
     * 旧任务（无 replay_data）返回 REPLAY_NOT_AVAILABLE，绝不回退为实时 K 线。
     *
     * @throws BacktestServiceException 404（不存在/不属于当前用户）/ 409（未完成或无回放数据）
     */
    public BacktestReplayResponse getReplay(Long id, Long userId) {
        // 用户隔离直接落在 SQL：selectReplayByIdAndUserId（含 replay_data）
        BacktestTaskDO task = taskMapper.selectReplayByIdAndUserId(id, userId);
        if (task == null || !strategyRegistry.ids().contains(task.getStrategyId())) {
            throw new BacktestServiceException(404, "TASK_NOT_FOUND", null, "回测任务不存在");
        }
        String status = task.getStatus();
        if (TaskStatus.WAITING.name().equals(status) || TaskStatus.RUNNING.name().equals(status)) {
            throw new BacktestServiceException(409, "REPLAY_NOT_READY", status, "回测任务尚未完成，无法回放，请稍后重试");
        }
        if (!TaskStatus.SUCCESS.name().equals(status)) {
            throw new BacktestServiceException(409, "REPLAY_NOT_AVAILABLE", status, "回测任务执行失败，无法回放，请重新运行");
        }
        if (task.getReplayData() == null || task.getReplayData().isBlank()) {
            throw new BacktestServiceException(409, "REPLAY_NOT_AVAILABLE", status,
                    "该任务没有回放数据（旧版本结果），请重新运行回测");
        }

        BacktestTaskVO vo = toVO(task);
        BacktestReplayResponse resp = new BacktestReplayResponse();
        resp.setTaskId(task.getId());
        resp.setStrategyId(task.getStrategyId());
        resp.setMarketType(task.getMarketType());
        resp.setSymbol(task.getSymbol());
        resp.setInterval(task.getInterval());
        resp.setStartTs(task.getStartTs());
        resp.setEndTs(task.getEndTs());
        resp.setParams(vo.getParams());
        resp.setResult(vo.getResult());
        try {
            resp.setReplay(JSON.parseObject(task.getReplayData(), BacktestReplay.class));
        } catch (Exception e) {
            // 回放快照损坏按业务失败处理：前端应提示重新运行而非拿到不完整的图
            log.error("replay_data 反序列化失败: taskId={}", task.getId(), e);
            throw new BacktestServiceException(409, "REPLAY_NOT_AVAILABLE", status,
                    "回放数据损坏，请重新运行回测");
        }
        return resp;
    }

    /**
     * 查询任务列表（按创建时间倒序，支持状态/策略筛选 + 分页）
     *
     * @param status     状态筛选（WAITING/RUNNING/SUCCESS/FAILED），null=全部
     * @param strategyId 策略筛选（如 ema20），null=全部
     * @param page       页码（从 1 开始）
     * @param size       每页条数（1~100）
     * @return 分页结果 {total, list}
     */
    public Map<String, Object> listTasks(String status, String strategyId, String marketType, int page, int size, Long userId) {
        // 参数校验
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1, 实际: " + page);
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size 必须在 1~100 之间, 实际: " + size);
        }

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BacktestTaskDO> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BacktestTaskDO>()
                        .eq(BacktestTaskDO::getUserId, userId)
                        .in(BacktestTaskDO::getStrategyId, strategyRegistry.ids())
                        .orderByDesc(BacktestTaskDO::getCreateTime);

        if (status != null && !status.isBlank()) {
            String upper = status.toUpperCase();
            try {
                TaskStatus.valueOf(upper);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("status 非法: " + status + ", 可选值: "
                        + Arrays.toString(TaskStatus.values()));
            }
            wrapper.eq(BacktestTaskDO::getStatus, upper);
        }

        // Week 7：列表可选 strategyId 筛选（供回放页选择历史任务）
        if (strategyId != null && !strategyId.isBlank()) {
            wrapper.eq(BacktestTaskDO::getStrategyId, strategyId.trim());
        }
        if (marketType != null) wrapper.eq(BacktestTaskDO::getMarketType, MarketCatalog.requireMarketType(marketType));

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BacktestTaskDO> pageObj =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BacktestTaskDO> result =
                taskMapper.selectPage(pageObj, wrapper);

        List<BacktestTaskVO> list = result.getRecords().stream().map(this::toVO).toList();

        Map<String, Object> response = new HashMap<>();
        response.put("total", result.getTotal());
        response.put("page", page);
        response.put("size", size);
        response.put("list", list);
        return response;
    }

    /**
     * DO → VO 转换（反序列化 resultSummary 为 BacktestResult）
     */
    private BacktestTaskVO toVO(BacktestTaskDO task) {
        BacktestTaskVO vo = new BacktestTaskVO();
        vo.setId(task.getId());
        vo.setStrategyId(task.getStrategyId());
        vo.setMarketType(task.getMarketType());
        vo.setSymbol(task.getSymbol());
        vo.setInterval(task.getInterval());
        vo.setStartTs(task.getStartTs());
        vo.setEndTs(task.getEndTs());
        vo.setStatus(task.getStatus());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());

        // 配置快照：params JSON → Map（用于任务详情和复盘）
        if (task.getParams() != null && !task.getParams().isBlank()) {
            try {
                vo.setParams(JSON.parseObject(task.getParams()));
            } catch (Exception e) {
                log.warn("params 反序列化失败: taskId={}", task.getId(), e);
            }
        }

        // SUCCESS 时反序列化结果摘要
        if (task.getResultSummary() != null && !task.getResultSummary().isBlank()) {
            try {
                JSONObject obj = JSON.parseObject(task.getResultSummary());

                // 旧版兼容：equityCurve 曾为 List<Double>（仅权益值，无时间），
                // 新版为 List<{time,equity}>。检测到数字数组时转换为带序号时间的 EquityPoint，
                // 保证旧回测结果仍可展示曲线（横轴退化为序号）。
                Object curve = obj.get("equityCurve");
                if (curve instanceof List<?> points && !points.isEmpty() && points.get(0) instanceof Number) {
                    List<EquityPoint> converted = new ArrayList<>(points.size());
                    for (int i = 0; i < points.size(); i++) {
                        converted.add(new EquityPoint(i, ((Number) points.get(i)).doubleValue()));
                    }
                    obj.put("equityCurve", converted);
                }

                vo.setResult(obj.toJavaObject(BacktestResult.class));
            } catch (Exception e) {
                log.warn("结果反序列化失败: taskId={}", task.getId(), e);
            }
        }
        return vo;
    }
}
