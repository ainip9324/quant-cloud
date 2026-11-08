package com.quant.core.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 回测任务消息生产者
 *
 * <p>同步发送 taskId 到 RocketMQ，message key=taskId 便于 Dashboard 追踪。
 * 发送失败时仅 warn 不抛异常——任务已持久化（WAITING），
 * 由 BacktestWatchdog 补偿投递。
 */
@Slf4j
@Component
public class BacktestMessageProducer {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Value("${backtest.topic}")
    private String topic;

    /**
     * 同步发送回测任务消息
     *
     * @param taskId 任务 ID（消息体 + message key）
     * @return true=发送成功，false=发送失败（调用方不抛异常，由 Watchdog 补偿）
     */
    public boolean syncSend(Long taskId) {
        String payload = String.valueOf(taskId);
        try {
            SendResult result = rocketMQTemplate.syncSend(topic,
                    MessageBuilder.withPayload(payload)
                            .setHeader(RocketMQHeaders.KEYS, String.valueOf(taskId))
                            .build());
            if (result != null && result.getSendStatus() == SendStatus.SEND_OK) {
                log.info("MQ消息发送成功: topic={}, taskId={}, msgId={}",
                        topic, taskId, result.getMsgId());
                return true;
            }
            log.warn("MQ消息发送状态异常: topic={}, taskId={}, status={}",
                    topic, taskId, result != null ? result.getSendStatus() : "null");
            return false;
        } catch (Exception e) {
            log.warn("MQ消息发送失败, 任务将由Watchdog补偿投递: topic={}, taskId={}",
                    topic, taskId, e);
            return false;
        }
    }
}
