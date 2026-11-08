package com.quant.core.mq;

import com.quant.core.mapper.BacktestTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 回测任务 DLQ 消费者
 *
 * <p>消费 %DLQ%{consumer-group} 死信队列。当 MQ 重试 3 次仍失败后消息进入 DLQ，
 * 此消费者将仍为 WAITING 的任务原子更新为 FAILED（errorMsg=MQ_RETRY_EXHAUSTED），
 * 打破「MQ 重试 → revert WAITING → Watchdog 重投 → MQ 重试」的无限循环。
 *
 * <p>只处理 WAITING 状态——基础设施异常时 Processor 已 revert 为 WAITING，
 * 3 次重试后进 DLQ 时任务应在 WAITING。已是 SUCCESS/FAILED 的不受影响（幂等）。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "%DLQ%${backtest.consumer-group}",
        consumerGroup = "${backtest.dlq-consumer-group}"
)
public class BacktestDlqConsumer implements RocketMQListener<String> {

    @Autowired
    private BacktestTaskMapper taskMapper;

    @Value("${backtest.consumer-group}")
    private String consumerGroup;

    @Override
    public void onMessage(String message) {
        Long taskId;
        try {
            taskId = Long.parseLong(message.trim());
        } catch (NumberFormatException e) {
            log.error("DLQ: 无效的 taskId, 丢弃: message={}", message);
            return;
        }

        int affected = taskMapper.markFailedIfWaiting(taskId, "MQ_RETRY_EXHAUSTED: 超过最大重试次数(3)");
        if (affected > 0) {
            log.warn("DLQ: 任务 {} 标记为 FAILED (MQ_RETRY_EXHAUSTED), consumerGroup={}",
                    taskId, consumerGroup);
        } else {
            log.info("DLQ: 任务 {} 状态非 WAITING(已被处理或仍在RUNNING), 跳过", taskId);
        }
    }
}
