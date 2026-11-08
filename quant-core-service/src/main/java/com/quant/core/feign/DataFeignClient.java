package com.quant.core.feign;

import com.quant.common.model.Kline;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import com.quant.common.market.MarketType;

/**
 * data-service 的 Feign 客户端
 *
 * core-service 通过 Feign 调用 data-service 获取 K线数据，不直连 MySQL（服务解耦）。
 * 配合 Nacos 服务发现，按服务名 quant-data-service 路由，支持负载均衡。
 *
 * Feign 超时配置见 application.yml：
 *   spring.cloud.openfeign.client.config.quant-data-service.read-timeout: 30000
 * （data-service 可能回源调 Bitget，需要较长读取超时）
 */
@FeignClient(name = "quant-data-service", path = "/internal/data",
        configuration = CoreDataFeignConfiguration.class)
public interface DataFeignClient {

    /**
     * 获取 K线数据（三级查询：Redis → MySQL → Bitget）
     *
     * @param symbol   交易对，如 BTCUSDT
     * @param interval K线周期，如 1h、4h、1day
     * @param limit    返回条数（最新 N 条，时间升序）
     * @return K线列表（时间升序，旧→新）
     */
    @GetMapping("/klines")
    List<Kline> getKlines(@RequestParam("marketType") MarketType marketType, @RequestParam("symbol") String symbol,
                          @RequestParam("interval") String interval,
                          @RequestParam("limit") int limit);
}
