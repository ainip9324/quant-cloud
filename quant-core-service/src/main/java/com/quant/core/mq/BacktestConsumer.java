package com.quant.core.mq;

import com.quant.core.processor.BacktestTaskProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 回测任务消费者（薄消费者）
 *
 * <p>仅负责接收 taskId 并调用 {@link BacktestTaskProcessor#process(Long)}。
 * 所有执行逻辑、异常分类、状态更新都在 Processor 中完成。
 *
 * <p>并发控制：consumeThreadNumber=2（min），consumeThreadMax=4，沿用 Week 2 回测并发边界。
 * maxReconsumeTimes=3：基础设施异常时 Processor 抛出 → MQ 自动重试，3 次后进 DLQ。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "${backtest.topic}",
        consumerGroup = "${backtest.consumer-group}",
        consumeThreadNumber = 2,
        consumeThreadMax = 4,
        maxReconsumeTimes = 3,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class BacktestConsumer implements RocketMQListener<String> {

    @Autowired
    private BacktestTaskProcessor processor;

    @Override
    public void onMessage(String message) {
        Long taskId;
        try {
            taskId = Long.parseLong(message.trim());
        } catch (NumberFormatException e) {
            log.error("无效的 taskId 消息, 丢弃: message={}", message);
            return; // ack（消息格式错误无法恢复，不重试）
        }

        log.debug("收到回测任务消息: taskId={}", taskId);
        processor.process(taskId);
        // 正常返回 → ack；Processor 内部抛异常 → MQ 自动重试
    }
}
