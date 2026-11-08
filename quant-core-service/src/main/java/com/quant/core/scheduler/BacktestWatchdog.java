package com.quant.core.scheduler;

import com.quant.core.mapper.BacktestTaskMapper;
import com.quant.core.mq.BacktestMessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回测任务看门狗（超时恢复 + 消息丢失补偿）
 *
 * <p>两类超时处理：
 * <ol>
 *   <li><b>RUNNING 超时</b>（默认 10 分钟）：进程崩溃后任务永久卡 RUNNING。
 *       Watchdog 恢复为 WAITING 并重新投递消息。
 *       <br/>注意：恢复使用 {@code WHERE status='RUNNING' AND update_time < threshold}，
 *       防止误恢复正在执行的任务。旧执行者的 executionToken 不匹配，无法覆盖新结果。</li>
 *   <li><b>WAITING 超时</b>（默认 5 分钟）：消息发送失败或 Consumer 未消费。
 *       Watchdog 限量重新投递（每轮最多 10 条），避免消息洪泛。</li>
 * </ol>
 *
 * <p>DLQ 消费者保证 MQ 重试 3 次后任务最终标记为 FAILED，
 * Watchdog 不会无限重投（FAILED 状态不会被查询到）。
 */
@Slf4j
@Component
public class BacktestWatchdog {

    @Autowired
    private BacktestTaskMapper taskMapper;

    @Autowired
    private BacktestMessageProducer producer;

    @Value("${backtest.watchdog.running-timeout-minutes:30}")
    private int runningTimeoutMinutes;

    @Value("${backtest.watchdog.waiting-timeout-minutes:5}")
    private int waitingTimeoutMinutes;

    @Value("${backtest.watchdog.waiting-compensate-limit:10}")
    private int waitingCompensateLimit;

    /**
     * 每 60 秒扫描一次超时任务
     */
    @Scheduled(fixedDelayString = "${backtest.watchdog.poll-interval-ms:60000}")
    public void scanTimeoutTasks() {
        recoverTimedOutRunning();
        compensateTimedOutWaiting();
    }

    /**
     * 恢复 RUNNING 超时任务（进程崩溃恢复）
     */
    private void recoverTimedOutRunning() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(runningTimeoutMinutes);
        List<Long> timeoutIds = taskMapper.findTimeoutRunning(threshold, waitingCompensateLimit);
        if (timeoutIds.isEmpty()) {
            return;
        }

        log.warn("发现 {} 个 RUNNING 超时任务(>{}分钟), 恢复并重新投递: {}",
                timeoutIds.size(), runningTimeoutMinutes, timeoutIds);

        for (Long taskId : timeoutIds) {
            // 条件恢复：带时间校验，防止误恢复正在执行的任务
            int reverted = taskMapper.revertTimedOutRunning(taskId, threshold);
            if (reverted > 0) {
                log.info("RUNNING超时恢复WAITING: taskId={}", taskId);
                // 重新投递消息
                producer.syncSend(taskId);
            }
            // reverted=0: 任务刚被其他消费者领取（update_time 已更新），跳过
        }
    }

    /**
     * 补偿 WAITING 超时任务（消息丢失补偿）
     *
     * <p>对每个超时的 WAITING 任务，先执行原子 touch（UPDATE update_time WHERE status='WAITING'），
     * 只有 touch 成功才重新投递消息。touch 成功后 update_time 刷新，避免同一任务每分钟重复投递。
     */
    private void compensateTimedOutWaiting() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(waitingTimeoutMinutes);
        List<Long> timeoutIds = taskMapper.findTimeoutWaiting(threshold, waitingCompensateLimit);
        if (timeoutIds.isEmpty()) {
            return;
        }

        log.warn("发现 {} 个 WAITING 超时任务(>{}分钟), 尝试原子touch后投递: {}",
                timeoutIds.size(), waitingTimeoutMinutes, timeoutIds);

        for (Long taskId : timeoutIds) {
            // 原子 touch：带时间条件，多实例 Watchdog 下只有一个能成功
            int touched = taskMapper.touchWaiting(taskId, threshold);
            if (touched > 0) {
                log.info("WAITING超时touch成功, 重新投递: taskId={}", taskId);
                producer.syncSend(taskId);
            }
            // touched=0: 任务已被 MQ 消费者领取（状态已变），跳过不投递
        }
    }
}
