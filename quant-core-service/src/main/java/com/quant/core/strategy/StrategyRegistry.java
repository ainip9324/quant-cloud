package com.quant.core.strategy;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 策略注册表
 *
 * Spring 启动时注入所有 Strategy Bean，按 getId() 建立路由映射。
 * 新增策略只需实现 Strategy 接口 + @Component，自动注册，无需改此处代码。
 */
@Component
public class StrategyRegistry {

    private final Map<String, Strategy> strategies;

    public StrategyRegistry(List<Strategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(Strategy::getId, s -> s));
    }

    /** 按策略标识获取已注册策略；不存在时立即拒绝提交或由 Worker 标记历史任务失败。 */
    public Strategy require(String id) {
        Strategy strategy = strategies.get(id);
        if (strategy == null) {
            throw new IllegalArgumentException("策略不存在: " + id);
        }
        return strategy;
    }

    /** 当前版本可用的策略标识。 */
    public Collection<String> ids() {
        return Collections.unmodifiableSet(strategies.keySet());
    }

    /** 所有已注册策略 */
    public Collection<Strategy> all() {
        return strategies.values();
    }
}
